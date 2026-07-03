package ksl.app.config.optimization

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.orchestrator.OptimizationOrchestrator
import ksl.app.session.RunResult
import ksl.app.validation.OptimizationConfigurationValidator
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase-5 acceptance for concurrent random restarts across the app layer:
 * spec TOML round-trip (including legacy documents without the new field),
 * validator rules (unsupported algorithms, parallel-evaluation conflict),
 * factory pass-through, per-restart trace files, and orchestrator
 * cancellation of a concurrent run.
 */
class ConcurrentRestartsConfigTest {

    // ── Fixtures (MM1 pattern shared with the sibling test classes) ──────────

    private fun mm1Model(): Model {
        val model = Model(MM1_MODEL_ID, autoCSVReports = false)
        GIGcQueue(model, numServers = 1, name = "MM1Queue")
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        return model
    }

    private val mm1Provider: ModelProviderIfc = MapModelProvider(
        MM1_MODEL_ID,
        object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = mm1Model()
        }
    )

    private fun firstInputKey(): String = mm1Model().inputKeys().first()
    private fun firstResponseName(): String = mm1Model().responseNames.first()

    private fun config(
        solver: SolverSpec,
        evaluation: EvaluationSpec = EvaluationSpec()
    ): OptimizationRunConfiguration {
        val model = mm1Model()
        return OptimizationRunConfiguration(
            model = ModelRunTemplate(
                modelReference = ModelReference.ByProviderId(MM1_MODEL_ID),
                runParameters  = model.extractRunParameters()
            ),
            problem = OptimizationProblemSpec(
                modelIdentifier = MM1_MODEL_ID,
                objectiveResponseName = firstResponseName(),
                inputs = listOf(
                    OptimizationInputSpec(
                        name = firstInputKey(),
                        lowerBound = 0.1,
                        upperBound = 10.0
                    )
                )
            ),
            solver     = solver,
            evaluation = evaluation
        )
    }

    private fun shcSpec(
        randomRestart: RandomRestartSpec?,
        maxIterations: Int = 1,
        replicationsPerEvaluation: Int = 1
    ) = SolverSpec.StochasticHillClimbing(
        maxIterations = maxIterations,
        replicationsPerEvaluation = replicationsPerEvaluation,
        randomRestart = randomRestart,
        name = "shc-concurrent-config-test"
    )

    private fun factory(): OptimizationSolverFactory =
        OptimizationSolverFactory(provider = mm1Provider)

    // ── 1. Spec codec ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("concurrentRestarts round-trips through TOML; legacy documents default to 1")
    fun tomlRoundTrip() {
        val original = config(shcSpec(RandomRestartSpec(maxNumRestarts = 4, concurrentRestarts = 3)))
        val decoded = OptimizationRunConfigurationToml.decode(
            OptimizationRunConfigurationToml.encode(original)
        )
        assertEquals(
            RandomRestartSpec(maxNumRestarts = 4, concurrentRestarts = 3),
            decoded.solver?.randomRestart
        )

        // A legacy document that never mentions the field decodes to the sequential default.
        val legacyText = OptimizationRunConfigurationToml
            .encode(config(shcSpec(RandomRestartSpec(maxNumRestarts = 4))))
            .lineSequence()
            .filterNot { it.trim().startsWith("concurrentRestarts") }
            .joinToString("\n")
        val legacy = OptimizationRunConfigurationToml.decode(legacyText)
        assertEquals(1, legacy.solver?.randomRestart?.concurrentRestarts,
            "documents without the field must decode to the sequential default")
    }

    // ── 2. Validator rules ────────────────────────────────────────────────────

    @Test
    @DisplayName("Validator accepts concurrent restarts for SHC and SA")
    fun validatorAcceptsSupportedAlgorithms() {
        val result = OptimizationConfigurationValidator.validate(
            config(shcSpec(RandomRestartSpec(maxNumRestarts = 4, concurrentRestarts = 2)))
        )
        assertTrue(result.isValid) { "unexpected errors: ${result.errors}" }
    }

    @Test
    @DisplayName("Validator rejects concurrent restarts for unsupported algorithms")
    fun validatorRejectsUnsupportedAlgorithm() {
        val ce = SolverSpec.CrossEntropy(
            maxIterations = 5,
            replicationsPerEvaluation = 1,
            randomRestart = RandomRestartSpec(maxNumRestarts = 4, concurrentRestarts = 2)
        )
        val result = OptimizationConfigurationValidator.validate(config(ce))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "CONCURRENT_RESTARTS_UNSUPPORTED_ALGORITHM" }) {
            "expected the unsupported-algorithm error; got ${result.errors}"
        }
    }

    @Test
    @DisplayName("Validator rejects concurrent restarts combined with parallel evaluation")
    fun validatorRejectsParallelEvaluationConflict() {
        val result = OptimizationConfigurationValidator.validate(
            config(
                shcSpec(RandomRestartSpec(maxNumRestarts = 4, concurrentRestarts = 2)),
                evaluation = EvaluationSpec(parallelEvaluation = true)
            )
        )
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.code == "CONCURRENT_RESTARTS_PARALLEL_EVALUATION_CONFLICT" }) {
            "expected the parallel-evaluation conflict error; got ${result.errors}"
        }
    }

    // ── 3. Factory pass-through ───────────────────────────────────────────────

    @Test
    @DisplayName("Factory builds a concurrent RandomRestartSolver from the spec")
    fun factoryBuildsConcurrentRestartSolver() {
        val solver = factory().build(
            config(shcSpec(RandomRestartSpec(maxNumRestarts = 3, concurrentRestarts = 3)))
        )
        assertTrue(solver is RandomRestartSolver)
        solver as RandomRestartSolver
        assertTrue(solver.isConcurrentMode)
        assertEquals(3, solver.concurrentRestarts)
        assertEquals("3", solver.configurationProperties["concurrentRestarts"])
    }

    @Test
    @DisplayName("Factory backstop rejects concurrent restarts for unsupported algorithms")
    fun factoryRejectsUnsupportedAlgorithm() {
        val ce = SolverSpec.CrossEntropy(
            maxIterations = 5,
            replicationsPerEvaluation = 1,
            randomRestart = RandomRestartSpec(maxNumRestarts = 4, concurrentRestarts = 2)
        )
        assertThrows<IllegalArgumentException> { factory().build(config(ce)) }
    }

    // ── 4. Per-restart trace files ────────────────────────────────────────────

    @Test
    @DisplayName("Concurrent restarts produce an outer trace plus per-restart trace files")
    fun concurrentTrackingWritesPerRestartFiles(@TempDir dir: Path) {
        val solver = factory().build(
            config(shcSpec(RandomRestartSpec(maxNumRestarts = 2, concurrentRestarts = 2)))
        ) as RandomRestartSolver
        val spec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = "trace")

        val handles = spec.attachTo(solver, dir) { "ignored" }
        solver.runAllIterations()
        handles.stopAll()

        val files = Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
        assertTrue("trace.csv" in files) { "outer trace missing; files: $files" }
        assertTrue("trace_restart_00.csv" in files) { "restart 0 trace missing; files: $files" }
        assertTrue("trace_restart_01.csv" in files) { "restart 1 trace missing; files: $files" }
        assertEquals(3, handles.size, "outer tracker + one per restart")
        for (file in files) {
            assertTrue(Files.size(dir.resolve(file)) > 0) { "$file must not be empty" }
        }
    }

    // ── 5. Orchestrator cancellation of a concurrent run ─────────────────────

    @Test
    @Timeout(60)
    @DisplayName("Cancelling an orchestrated concurrent-restart run terminates promptly")
    fun orchestratorCancellationTerminatesPromptly() {
        // The hill climber's no-improvement checker would end restarts on the noisy MM1
        // model within a handful of iterations; raise the default so each restart is
        // effectively endless and the cancel is what ends the run. Restored afterwards.
        val savedThreshold = ksl.simopt.solvers.algorithms.StochasticHillClimber.defaultNoImproveThresholdForSHC
        ksl.simopt.solvers.algorithms.StochasticHillClimber.defaultNoImproveThresholdForSHC = 1_000_000
        try {
            val solver = factory().build(
                config(shcSpec(
                    RandomRestartSpec(maxNumRestarts = 6, concurrentRestarts = 2),
                    maxIterations = 200_000,
                    replicationsPerEvaluation = 1
                ))
            )
            val handle = OptimizationOrchestrator().submit(solver = solver)
            Thread.sleep(1000)   // let the first wave of restarts get in flight
            handle.cancel("test cancel")
            val result = runBlocking { withTimeout(45_000) { handle.result.await() } }
            assertNotNull(result)
            assertFalse(result is RunResult.OptimizationCompleted) {
                "a cancelled run must not report normal completion"
            }
        } finally {
            ksl.simopt.solvers.algorithms.StochasticHillClimber.defaultNoImproveThresholdForSHC = savedThreshold
        }
    }

    private companion object {
        const val MM1_MODEL_ID = "MM1ConcurrentRestartsTest"
    }
}
