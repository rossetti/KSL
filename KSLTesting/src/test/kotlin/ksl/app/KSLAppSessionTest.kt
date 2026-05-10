package ksl.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.config.optimization.CoolingScheduleSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.TemperatureSpec
import ksl.app.session.KSLRuntimeError
import ksl.app.session.RunAttachmentIfc
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.app.session.RunWarningType
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.book.appendixD.GIGcQueue
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KSLAppSessionTest {

    private companion object {
        const val MM1_ID = "SessionMM1"
        const val LK_ID = "LKInventoryModel"
        const val TIMEOUT_MS = 30_000L
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

    private val lkBuilder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(LK_ID, autoCSVReports = false)
            LKInventoryModel(model, "Inventory")
            model.lengthOfReplication = 120.0
            model.numberOfReplications = 5
            model.lengthOfReplicationWarmUp = 20.0
            return model
        }
    }

    private val lkProvider: ModelProviderIfc = MapModelProvider(LK_ID, lkBuilder)

    private fun mm1Config(replications: Int = 3, repLength: Double = 100.0): RunConfiguration {
        val model = mm1Provider.provideModel(MM1_ID)
        return RunConfiguration(
            modelReference = ModelReference.ByProviderId(MM1_ID),
            experimentRunParameters = model.extractRunParameters().copy(
                numberOfReplications = replications,
                lengthOfReplication = repLength
            )
        )
    }

    private fun twoScenarioConfig(): RunConfiguration {
        val model = mm1Provider.provideModel(MM1_ID)
        val runParams = model.extractRunParameters()
        return RunConfiguration(
            modelReference = ModelReference.ByProviderId(MM1_ID),
            experimentRunParameters = runParams,
            scenarios = listOf(
                ScenarioSpec("LowLoad", runParams),
                ScenarioSpec("HighLoad", runParams)
            )
        )
    }

    private fun lkConfig(replications: Int = 5): RunConfiguration {
        val model = lkProvider.provideModel(LK_ID)
        return RunConfiguration(
            modelReference = ModelReference.ByProviderId(LK_ID),
            experimentRunParameters = model.extractRunParameters().copy(numberOfReplications = replications)
        )
    }

    private fun buildExperiment(): ParallelDesignedExperiment {
        val oq = TwoLevelFactor("OrderQuantity", low = 5.0, high = 20.0)
        val rp = TwoLevelFactor("ReorderPoint", low = 1.0, high = 5.0)
        val design = TwoLevelFactorialDesign(setOf(oq, rp))
        val settings = mapOf(
            oq to "Inventory.orderQuantity",
            rp to "Inventory.reorderPoint"
        )
        return ParallelDesignedExperiment("SessionLKExperiment", lkBuilder, settings, design)
    }

    /**
     * LK inventory optimization configuration mirroring the legacy
     * `buildSolver()` helper but expressed as the post-Step-7
     * [OptimizationRunConfiguration] shape.
     */
    private fun lkOptimizationConfig(
        objectiveResponseName: String = "TotalCost",
        solver: SolverSpec = SolverSpec.StochasticHillClimbing(
            maxIterations = 3,
            replicationsPerEvaluation = 3
        )
    ): OptimizationRunConfiguration {
        val model = lkProvider.provideModel(LK_ID)
        return OptimizationRunConfiguration(
            model = ModelRunTemplate(
                modelReference = ModelReference.ByProviderId(LK_ID),
                runParameters  = model.extractRunParameters()
            ),
            problem = OptimizationProblemSpec(
                problemName           = "InventoryProblem",
                modelIdentifier       = LK_ID,
                objectiveResponseName = objectiveResponseName,
                inputs = listOf(
                    OptimizationInputSpec("Inventory.orderQuantity",
                        lowerBound = 1.0, upperBound = 100.0, granularity = 1.0),
                    OptimizationInputSpec("Inventory.reorderPoint",
                        lowerBound = 1.0, upperBound = 100.0, granularity = 1.0)
                )
            ),
            solver = solver
        )
    }

    @Test
    fun `single spec completes through session`() = runBlocking {
        KSLAppSession(mm1Provider, this).use { session ->
            val handle = session.submit(RunSpec.Single(mm1Config()))
            val result = handle.result.await()

            assertIs<RunResult.Completed>(result)
            assertTrue(result.snapshot.acrossRepStats.isNotEmpty())
        }
    }

    @Test
    fun `scenario spec completes through session`() = runBlocking {
        KSLAppSession(mm1Provider, this).use { session ->
            val handle = session.submit(RunSpec.Scenarios(twoScenarioConfig()))
            val result = handle.result.await()

            assertIs<RunResult.BatchCompleted>(result)
            assertEquals(2, result.snapshots.size)
            assertEquals(0, result.summary.failedItems)
        }
    }

    @Test
    fun `experiment spec completes through session`() = runBlocking {
        KSLAppSession(lkProvider, this).use { session ->
            val experiment = buildExperiment()
            val totalDesignPoints = experiment.design.designPoints().size
            val handle = session.submit(
                RunSpec.Experiment(
                    config = lkConfig(),
                    experiment = experiment,
                    numRepsPerDesignPoint = 5
                )
            )
            val result = handle.result.await()

            assertIs<RunResult.BatchCompleted>(result)
            assertEquals(totalDesignPoints, result.snapshots.size)
            assertEquals(0, result.summary.failedItems)
        }
    }

    @Test
    fun `optimization spec completes through session`() = runBlocking {
        KSLAppSession(lkProvider, this).use { session ->
            val handle = session.submit(RunSpec.Optimization(lkOptimizationConfig()))
            val result = handle.result.await()

            assertIs<RunResult.OptimizationCompleted>(result)
            assertTrue(result.iterationHistory.isNotEmpty())
            assertEquals(0, result.summary.failedItems)
        }
    }

    @Test
    fun `optimization spec with invalid config returns ConfigurationError handle`() = runBlocking {
        KSLAppSession(lkProvider, this).use { session ->
            val invalid = lkOptimizationConfig(objectiveResponseName = "DoesNotExistOnModel")
            val handle = session.submit(RunSpec.Optimization(invalid))
            val result = handle.result.await()

            assertIs<RunResult.Failed>(result)
            val error = result.error
            assertIs<KSLRuntimeError.ConfigurationError>(error)
            val validationResult = error.validationResult
            assertTrue(validationResult != null && validationResult.errors.any {
                it.path == "problem.objectiveResponseName" && it.code == "OBJECTIVE_RESPONSE_UNKNOWN"
            }, "Expected OBJECTIVE_RESPONSE_UNKNOWN at problem.objectiveResponseName; got $validationResult")
        }
    }

    @Test
    fun `optimization spec validation warnings emit as RunWarning events`() = runBlocking {
        KSLAppSession(lkProvider, this).use { session ->
            // Fixed initial temperature (1000.0) deliberately mismatched against the
            // cooling-schedule's initialTemperature (500.0) to trigger
            // SA_COOLING_INITIAL_TEMP_MISMATCH from OptimizationConfigurationValidator.
            val saConfig = lkOptimizationConfig(
                solver = SolverSpec.SimulatedAnnealing(
                    maxIterations = 2,
                    replicationsPerEvaluation = 1,
                    temperature = TemperatureSpec.Fixed(temperature = 1000.0),
                    coolingSchedule = CoolingScheduleSpec.Exponential(initialTemperature = 500.0),
                    stoppingTemperature = 0.01
                )
            )
            val handle = session.submit(RunSpec.Optimization(saConfig))

            // Collect the first RunWarning event independently of run completion.
            val warningSeen = CompletableDeferred<RunEvent.RunWarning>()
            val collector = launch {
                warningSeen.complete(handle.events.filterIsInstance<RunEvent.RunWarning>().first())
            }

            val warning = withTimeout(TIMEOUT_MS) { warningSeen.await() }
            collector.cancel()

            val warningType = warning.warning
            assertIs<RunWarningType.ConfigurationWarnings>(warningType)
            assertTrue(warningType.warnings.any { it.code == "SA_COOLING_INITIAL_TEMP_MISMATCH" },
                "Expected SA_COOLING_INITIAL_TEMP_MISMATCH; got ${warningType.warnings}")

            // Let the run finish so `use { … }` cleanup is clean.
            handle.result.await()
        }
    }

    @Test
    fun `cancel through session handle resolves as Cancelled`() = runBlocking {
        KSLAppSession(mm1Provider).use { session ->
            val handle = session.submit(RunSpec.Single(mm1Config(replications = 30, repLength = 50_000.0)))

            handle.cancel("session cancel")
            val result = withTimeout(TIMEOUT_MS) { handle.result.await() }

            assertIs<RunResult.Cancelled>(result)
            assertEquals("session cancel", result.reason)
        }
    }

    @Test
    fun `close cancels in-flight session runs`() = runBlocking {
        val session = KSLAppSession(mm1Provider)
        val handle = session.submit(RunSpec.Single(mm1Config(replications = 30, repLength = 50_000.0)))

        session.close()
        val result = withTimeout(TIMEOUT_MS) { handle.result.await() }

        assertIs<RunResult.Cancelled>(result)
        assertEquals("KSLAppSession closed", result.reason)
    }

    @Test
    fun `invalid config returns immediate ConfigurationError handle`() = runBlocking {
        KSLAppSession(mm1Provider, this).use { session ->
            val baseConfig = mm1Config()
            val invalidConfig = baseConfig.copy(
                experimentRunParameters = baseConfig.experimentRunParameters.copy(
                    lengthOfReplication = 10.0,
                    lengthOfReplicationWarmUp = 10.0
                )
            )
            val handle = session.submit(RunSpec.Single(invalidConfig))
            val result = handle.result.await()

            assertIs<RunResult.Failed>(result)
            val error = result.error
            assertIs<KSLRuntimeError.ConfigurationError>(error)
            assertTrue(error.validationResult?.errors?.isNotEmpty() == true)
            assertTrue(handle.events.replayCache.single() is RunEvent.RunFailed)
        }
    }

    @Test
    fun `validation warnings are emitted before the Started event`() = runBlocking {
        KSLAppSession(mm1Provider, this).use { session ->
            val warningConfig = mm1Config().let { config ->
                config.copy(
                    experimentRunParameters = config.experimentRunParameters.copy(
                        experimentName = "",
                        runName = ""
                    )
                )
            }

            val handle = session.submit(RunSpec.Single(warningConfig))
            handle.result.await()
            val events = handle.events.replayCache

            val warningIndex = events.indexOfFirst {
                it is RunEvent.RunWarning &&
                    it.warning is RunWarningType.ConfigurationWarnings
            }
            // Match the sealed Started parent so this test expresses the universal
            // rule (warnings precede whichever Started variant the orchestrator emits).
            val startedIndex = events.indexOfFirst { it is RunEvent.Started }

            assertTrue(warningIndex >= 0, "Expected configuration warning event")
            assertTrue(startedIndex >= 0, "Expected a RunEvent.Started event")
            assertTrue(warningIndex < startedIndex, "Configuration warnings must precede Started")
        }
    }

    @Test
    fun `single spec forwards attachments`() = runBlocking {
        KSLAppSession(mm1Provider, this).use { session ->
            var attachCount = 0
            var detachCount = 0
            val detached = CompletableDeferred<Unit>()
            val attachment = object : RunAttachmentIfc {
                override fun onAttach(model: Model, scope: CoroutineScope) {
                    attachCount++
                }

                override fun onDetach() {
                    detachCount++
                    detached.complete(Unit)
                }
            }

            val handle = session.submit(RunSpec.Single(mm1Config()), attachments = listOf(attachment))
            val result = handle.result.await()
            withTimeout(TIMEOUT_MS) { detached.await() }

            assertIs<RunResult.Completed>(result)
            assertEquals(1, attachCount)
            assertEquals(1, detachCount)
        }
    }

    @Test
    fun `attachments on non-single spec fail immediately`() = runBlocking {
        KSLAppSession(mm1Provider, this).use { session ->
            val attachment = object : RunAttachmentIfc {
                override fun onAttach(model: Model, scope: CoroutineScope) = Unit
                override fun onDetach() = Unit
            }

            val handle = session.submit(
                RunSpec.Scenarios(twoScenarioConfig()),
                attachments = listOf(attachment)
            )
            val result = handle.result.await()

            assertIs<RunResult.Failed>(result)
            val error = result.error
            assertIs<KSLRuntimeError.ConfigurationError>(error)
            assertEquals(
                "ATTACHMENTS_UNSUPPORTED_FOR_RUN_SPEC",
                error.validationResult?.errors?.single()?.code
            )
        }
    }

    // ── Synchronous-convenience helpers (Phase 5.85 follow-up) ────────────────

    @Test
    fun `submitAndAwaitBlocking returns the same Completed result as the suspending path`() {
        // Deliberately a non-suspend test method (no runBlocking { }) — exercises
        // the public path a `fun main()` user would take.
        KSLAppSession(mm1Provider).use { session ->
            val result = session.submitAndAwaitBlocking(RunSpec.Single(mm1Config()))

            assertIs<RunResult.Completed>(result)
            assertTrue(result.snapshot.acrossRepStats.isNotEmpty())
        }
    }

    @Test
    fun `submitAndAwaitBlocking surfaces ConfigurationError for invalid configs without throwing`() {
        KSLAppSession(mm1Provider).use { session ->
            val invalidConfig = mm1Config().let { c ->
                c.copy(
                    experimentRunParameters = c.experimentRunParameters.copy(
                        lengthOfReplication = 10.0,
                        lengthOfReplicationWarmUp = 10.0
                    )
                )
            }
            val result = session.submitAndAwaitBlocking(RunSpec.Single(invalidConfig))

            assertIs<RunResult.Failed>(result)
            assertIs<KSLRuntimeError.ConfigurationError>(result.error)
        }
    }

    @Test
    fun `RunHandle awaitResultBlocking returns the same result as result_await`() = runBlocking {
        // submit() itself is non-suspend; await the result via the new blocking
        // helper from a separate thread to confirm no thread-affinity assumption.
        KSLAppSession(mm1Provider, this).use { session ->
            val handle = session.submit(RunSpec.Single(mm1Config()))
            val result = withContext(Dispatchers.IO) { handle.awaitResultBlocking() }
            assertIs<RunResult.Completed>(result)
        }
    }
}
