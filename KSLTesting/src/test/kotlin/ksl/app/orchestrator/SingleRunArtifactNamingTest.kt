/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.orchestrator

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.session.RunResult
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Substrate tests for the artifact-naming behaviour of
 *  `SingleRunOrchestrator` — pinned in E.4.1 after manual testing
 *  surfaced that on-disk DB / CSV files were named after the model
 *  rather than the analyst-supplied analysis name.
 *
 *  The orchestrator now derives a single per-run `outputStem` from
 *  `OutputConfig.analysisName` (with a fallback to the model's own
 *  sanitised name when the analysis name is blank or the `"Untitled"`
 *  sentinel) and uses that stem as the basis for the SQLite database
 *  file and both per-kind CSV report files.  Matches
 *  `ScenarioOrchestrator`'s convention so the Single and Scenario
 *  apps name their artifacts consistently.
 */
class SingleRunArtifactNamingTest {

    private companion object {
        const val MM1_ID = "ArtifactNamingMM1"
        const val TIMEOUT_MS = 30_000L
    }

    private val mm1Provider = MapModelProvider(MM1_ID, object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(MM1_ID, autoCSVReports = false)
            model.numberOfReplications = 3
            model.lengthOfReplication = 100.0
            GIGcQueue(model, numServers = 1, name = "Q")
            return model
        }
    })

    private fun buildConfig(outputDir: Path, outputConfig: OutputConfig): RunConfiguration =
        RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "single",
                    modelReference = ModelReference.ByProviderId(MM1_ID)
                )
            ),
            outputConfig = outputConfig.copy(
                outputDirectory = outputDir.toAbsolutePath().normalize().toString()
            )
        )

    private fun submitAndAwait(config: RunConfiguration) = runBlocking {
        KSLAppSession(mm1Provider, this).use { session ->
            val handle = session.submit(RunSpec.Single(config))
            val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
            assertIs<RunResult.Completed>(result)
        }
    }

    private fun listFiles(dir: Path): List<String> =
        if (Files.exists(dir)) Files.list(dir).use { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        } else emptyList()

    // ── Database stem ────────────────────────────────────────────────────

    @Test
    fun `SQLite database file stem honours OutputConfig analysisName`(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                analysisName = "QueueStudy-Phase1",
                enableKSLDatabase = true
            )
        )
        submitAndAwait(cfg)

        val dbDir = tempDir.resolve("dbDir")
        val dbFiles = listFiles(dbDir)
        assertTrue("QueueStudy-Phase1.db" in dbFiles,
            "DB file must be named '<analysisName>.db'; got: $dbFiles")
        // And NOT the legacy `<modelName>.db` form.
        assertTrue("$MM1_ID.db" !in dbFiles,
            "Legacy model-name-derived DB file must not be written when analysisName is set; got: $dbFiles")
    }

    @Test
    fun `SQLite database file stem falls back to model name when analysisName is blank`(
        @TempDir tempDir: Path
    ) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                analysisName = "",
                enableKSLDatabase = true
            )
        )
        submitAndAwait(cfg)

        val dbFiles = listFiles(tempDir.resolve("dbDir"))
        assertTrue("$MM1_ID.db" in dbFiles,
            "Blank analysisName must fall back to '<modelName>.db'; got: $dbFiles")
    }

    @Test
    fun `SQLite database file stem falls back to model name when analysisName is the Untitled sentinel`(
        @TempDir tempDir: Path
    ) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                analysisName = "Untitled",
                enableKSLDatabase = true
            )
        )
        submitAndAwait(cfg)

        val dbFiles = listFiles(tempDir.resolve("dbDir"))
        // The orchestrator explicitly checks for the
        // `SingleAppPaths.UNTITLED` sentinel and triggers the model-name
        // fallback — same rule `SingleAppPaths.appWorkspaceDir` uses for
        // the workspace folder, so the directory layer and the artifact
        // layer fall back in lockstep.
        assertTrue("$MM1_ID.db" in dbFiles,
            "Untitled sentinel must trigger model-name fallback; got: $dbFiles")
        assertTrue("Untitled.db" !in dbFiles,
            "Untitled.db must not be written — the sentinel triggers the fallback; got: $dbFiles")
    }

    // ── CSV stems ────────────────────────────────────────────────────────

    @Test
    fun `replication CSV report stem honours OutputConfig analysisName`(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                analysisName = "ReplicationStudy",
                enableReplicationCSV = true
            )
        )
        submitAndAwait(cfg)

        val csvFiles = listFiles(tempDir.resolve("csvDir"))
        assertTrue(csvFiles.any { it.startsWith("ReplicationStudy_CSVReplicationReport") },
            "Replication CSV report must start with '<analysisName>_CSVReplicationReport'; got: $csvFiles")
        // The legacy model-name-derived report must not be written.
        assertTrue(csvFiles.none { it.startsWith("${MM1_ID}_CSVReplicationReport") },
            "Legacy '<modelName>_CSVReplicationReport' must not be written when analysisName is set; got: $csvFiles")
    }

    @Test
    fun `experiment CSV report stem honours OutputConfig analysisName`(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                analysisName = "ExperimentStudy",
                enableExperimentCSV = true
            )
        )
        submitAndAwait(cfg)

        val csvFiles = listFiles(tempDir.resolve("csvDir"))
        assertTrue(csvFiles.any { it.startsWith("ExperimentStudy_CSVExperimentReport") },
            "Experiment CSV report must start with '<analysisName>_CSVExperimentReport'; got: $csvFiles")
        assertTrue(csvFiles.none { it.startsWith("${MM1_ID}_CSVExperimentReport") },
            "Legacy '<modelName>_CSVExperimentReport' must not be written when analysisName is set; got: $csvFiles")
    }

    @Test
    fun `CSV reports fall back to model name when analysisName is blank`(
        @TempDir tempDir: Path
    ) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                analysisName = "",
                enableReplicationCSV = true,
                enableExperimentCSV = true
            )
        )
        submitAndAwait(cfg)

        val csvFiles = listFiles(tempDir.resolve("csvDir"))
        assertTrue(csvFiles.any { it.startsWith("${MM1_ID}_CSVReplicationReport") },
            "Blank analysisName replication report must fall back to '<modelName>_CSVReplicationReport'; got: $csvFiles")
        assertTrue(csvFiles.any { it.startsWith("${MM1_ID}_CSVExperimentReport") },
            "Blank analysisName experiment report must fall back to '<modelName>_CSVExperimentReport'; got: $csvFiles")
    }

    // ── No artifacts when toggles off ────────────────────────────────────

    @Test
    fun `no DB or CSV files are written when their toggles are disabled`(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                analysisName = "ShouldNotAppear",
                enableKSLDatabase = false,
                enableReplicationCSV = false,
                enableExperimentCSV = false
            )
        )
        submitAndAwait(cfg)

        // The csvDir / dbDir may not even be created when no observers
        // attach.  Both "no directory" and "directory present but empty"
        // are acceptable — assert no analysis-named artifacts exist.
        val csvFiles = listFiles(tempDir.resolve("csvDir"))
        val dbFiles = listFiles(tempDir.resolve("dbDir"))
        assertTrue(csvFiles.none { it.contains("ShouldNotAppear") },
            "No CSV files must mention 'ShouldNotAppear' when CSV toggles are off; got: $csvFiles")
        assertTrue(dbFiles.none { it.contains("ShouldNotAppear") },
            "No DB files must mention 'ShouldNotAppear' when KSLDatabase toggle is off; got: $dbFiles")
    }
}
