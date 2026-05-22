package ksl.app.orchestrator

import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.config.toOverrides
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.app.session.OrchestratorSummary
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Acceptance tests for Phase 5: ScenarioOrchestrator.
 *
 * Model: GIGcQueue M/M/1 with two scenarios run concurrently.
 */
class ScenarioOrchestratorTest {

    private companion object {
        const val MM1_ID = "MM1Scenario"
    }

    private val mm1Provider: ModelProviderIfc = MapModelProvider(
        MM1_ID,
        object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val model = Model(MM1_ID, autoCSVReports = false)
                model.numberOfReplications = 3
                model.lengthOfReplication = 100.0
                GIGcQueue(model, numServers = 1, name = "MM1")
                return model
            }
        }
    )

    private fun twoScenarioConfig(): RunConfiguration {
        val model = mm1Provider.provideModel(MM1_ID)
        val runParams = model.extractRunParameters()
        return RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "LowLoad",
                    modelReference = ModelReference.ByProviderId(MM1_ID),
                    runOverrides = runParams.toOverrides()
                ),
                ScenarioSpec(
                    name = "HighLoad",
                    modelReference = ModelReference.ByProviderId(MM1_ID),
                    runOverrides = runParams.toOverrides()
                )
            )
        )
    }

    @Test
    fun `two scenarios resolve as BatchCompleted with two snapshots`() = runBlocking {
        val config = twoScenarioConfig()
        val orchestrator = ScenarioOrchestrator()
        val handle = orchestrator.submit(config, mm1Provider, scope = this)
        val result = handle.result.await()

        assertIs<RunResult.BatchCompleted>(result)
        val orchestratorResult = result as RunResult.BatchCompleted
        assertEquals(2, orchestratorResult.snapshots.size,
            "Expected one snapshot per scenario")
        assertEquals(0, orchestratorResult.summary.failedItems,
            "Expected no failed scenarios")
        assertEquals(2, orchestratorResult.summary.completedItems)
    }

    @Test
    fun `ScenarioReplicationsCompleted fires once per successful scenario before ScenarioCompleted`() = runBlocking {
        val config = twoScenarioConfig()
        val orchestrator = ScenarioOrchestrator()
        val handle = orchestrator.submit(config, mm1Provider, scope = this)

        val events = mutableListOf<RunEvent>()
        val collectJob = launch {
            handle.events
                .takeWhile { it !is RunEvent.RunCompleted && it !is RunEvent.RunFailed }
                .collect { events.add(it) }
        }
        handle.result.await()
        collectJob.join()

        val repsDone = events.filterIsInstance<RunEvent.ScenarioReplicationsCompleted>()
        val completed = events.filterIsInstance<RunEvent.ScenarioCompleted>()

        // One ReplicationsCompleted per scenario, exactly.
        assertEquals(2, repsDone.size, "Expected exactly one ScenarioReplicationsCompleted per scenario")
        assertEquals(setOf("LowLoad", "HighLoad"), repsDone.map { it.scenarioName }.toSet())

        // Per-scenario ordering: ReplicationsCompleted strictly precedes
        // ScenarioCompleted for the same scenario.
        for (name in setOf("LowLoad", "HighLoad")) {
            val repsIdx = events.indexOfFirst {
                it is RunEvent.ScenarioReplicationsCompleted && it.scenarioName == name
            }
            val completedIdx = events.indexOfFirst {
                it is RunEvent.ScenarioCompleted && it.scenarioName == name
            }
            assertTrue(
                repsIdx >= 0 && completedIdx >= 0 && repsIdx < completedIdx,
                "ReplicationsCompleted for '$name' must precede ScenarioCompleted " +
                    "(repsIdx=$repsIdx, completedIdx=$completedIdx)"
            )
        }
        // Sanity: all expected ScenarioCompleted events still emitted.
        assertEquals(2, completed.size)
    }

    @Test
    fun `ScenarioCompleted events emitted for each scenario in order`() = runBlocking {
        val config = twoScenarioConfig()
        val orchestrator = ScenarioOrchestrator()
        val handle = orchestrator.submit(config, mm1Provider, scope = this)

        val scenarioEvents = mutableListOf<RunEvent.ScenarioCompleted>()
        val collectJob = launch {
            handle.events
                .takeWhile { it !is RunEvent.RunCompleted && it !is RunEvent.RunFailed }
                .filterIsInstance<RunEvent.ScenarioCompleted>()
                .collect { scenarioEvents.add(it) }
        }
        handle.result.await()
        collectJob.join()

        assertEquals(2, scenarioEvents.size, "Expected exactly 2 ScenarioCompleted events")
        assertEquals(1, scenarioEvents[0].index)
        assertEquals(2, scenarioEvents[1].index)
        assertEquals(2, scenarioEvents[0].totalScenarios)
    }

    @Test
    fun `per-scenario CSV writes under outputConfig outputDirectory`() = runBlocking {
        val tmp = java.nio.file.Files.createTempDirectory("scenario-orch-csv-")
        val model = mm1Provider.provideModel(MM1_ID)
        val runParams = model.extractRunParameters()
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "CsvOn",
                    modelReference = ModelReference.ByProviderId(MM1_ID),
                    runOverrides = runParams.toOverrides(),
                    enableReplicationCSV = true,
                    enableExperimentCSV = true
                )
            ),
            outputConfig = OutputConfig(
                outputDirectory = tmp.toAbsolutePath().toString()
            )
        )
        val handle = ScenarioOrchestrator().submit(config, mm1Provider, scope = this)
        val result = handle.result.await()
        assertIs<RunResult.BatchCompleted>(result)

        // Substrate writes CSVs under
        //   <outputDirectory>/<modelName>_OutputDir/csvDir/*.csv
        // OutputDirectory's default ctor names the subdir from the
        // root path it was given, so we walk the tree looking for any
        // .csv beneath tmp rather than depending on the exact subdir
        // layout.
        val allFiles = java.nio.file.Files.walk(tmp).use { stream ->
            stream.filter { java.nio.file.Files.isRegularFile(it) }
                .map { it.toString() }
                .toList()
        }
        val csvCount = allFiles.count { it.endsWith(".csv") }
        assertTrue(
            csvCount > 0,
            "Expected at least one .csv file under $tmp after the run; found none.  " +
                "Files actually present under $tmp:\n  " + allFiles.joinToString("\n  ")
        )
    }

    @Test
    fun `orchestrator creates outputDirectory if it does not yet exist`() = runBlocking {
        // Regression: ScenarioOrchestrator used to pass the
        // GUI-supplied outputDirectory straight to the runner without
        // ensuring the directory existed on disk first.  The runner's
        // KSLDatabase default constructor then tried to open a SQLite
        // file inside a missing parent and failed with SQLITE_CANTOPEN.
        // Reproduces the failure mode reported against the Scenario
        // app on a fresh workspace.
        val parent = java.nio.file.Files.createTempDirectory("scenario-orch-missing-")
        // Resolve a non-existent child path.  Submit must create it.
        val outputDir = parent.resolve("created-by-orchestrator")
        check(!java.nio.file.Files.exists(outputDir)) { "test setup: outputDir must not exist yet" }

        val model = mm1Provider.provideModel(MM1_ID)
        val runParams = model.extractRunParameters()
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "S",
                    modelReference = ModelReference.ByProviderId(MM1_ID),
                    runOverrides = runParams.toOverrides()
                )
            ),
            outputConfig = OutputConfig(
                outputDirectory = outputDir.toAbsolutePath().toString()
            )
        )
        val handle = ScenarioOrchestrator().submit(config, mm1Provider, scope = this)
        val result = handle.result.await()
        assertIs<RunResult.BatchCompleted>(result)
        assertTrue(
            java.nio.file.Files.isDirectory(outputDir),
            "outputDirectory was not created by the orchestrator: $outputDir"
        )
    }

    @Test
    fun `scenario name with slashes does not create nested output directories`() = runBlocking {
        // Regression: scenario names like "M/M/1 Queue" used to reach
        // KSLFileUtil.createSubDirectory unsanitized, producing a
        // nested tree (M/M/1_Queue_OutputDir/) instead of a flat
        // sibling (M_M_1_Queue_OutputDir/).  See sanitizeForFilesystem
        // in ScenarioRunner.kt.
        val tmp = java.nio.file.Files.createTempDirectory("scenario-orch-slash-")
        val model = mm1Provider.provideModel(MM1_ID)
        val runParams = model.extractRunParameters()
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "M/M/1 Queue",
                    modelReference = ModelReference.ByProviderId(MM1_ID),
                    runOverrides = runParams.toOverrides()
                )
            ),
            outputConfig = OutputConfig(
                outputDirectory = tmp.toAbsolutePath().toString()
            )
        )
        val handle = ScenarioOrchestrator().submit(config, mm1Provider, scope = this)
        val result = handle.result.await()
        assertIs<RunResult.BatchCompleted>(result)

        // No child directory named just "M" — that would indicate the
        // slashes were being interpreted as path separators.
        val children = java.nio.file.Files.list(tmp).use { it.toList() }
        val childNames = children.map { it.fileName.toString() }
        assertTrue(
            "M" !in childNames,
            "Unsanitised slashes leaked into the output tree.  " +
                "Direct children of $tmp:\n  " + childNames.joinToString("\n  ")
        )
        // And the sanitised model dir must be present as a flat sibling.
        assertTrue(
            childNames.any { it.startsWith("M_M_1_Queue") },
            "Expected a flat 'M_M_1_Queue*_OutputDir' under $tmp.  " +
                "Direct children:\n  " + childNames.joinToString("\n  ")
        )
    }

    @Test
    fun `empty scenarios list throws IllegalArgumentException before submitting`() {
        val config = RunConfiguration() // no scenarios, no bundleRefs
        var threw = false
        try {
            ScenarioOrchestrator().submit(config, mm1Provider)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "Expected IllegalArgumentException for empty scenarios list")
    }
}
