package ksl.app.config.optimization

import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase O1 acceptance tests for [SolverTrackingSpec.attachTo] and
 * [SolverTrackerHandles].
 *
 * Each test builds a minimal SHC or RandomRestart-SHC solver via
 * [OptimizationSolverFactory], attaches the tracker(s) per the spec
 * under test, runs the solver to completion via `runAllIterations()`,
 * and verifies the written CSV (or its absence) and the lifecycle
 * around `stopAll`.
 *
 * Tests intentionally use the smallest possible configuration —
 * `maxIterations = 1`, `replicationsPerEvaluation = 1`, MM1 with 1
 * replication of 10 time units — so each test runs in well under
 * two seconds.
 */
class SolverTrackingAttachmentTest {

    // ── Test fixtures ────────────────────────────────────────────────────────

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

    private fun config(solver: SolverSpec): OptimizationRunConfiguration {
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
            solver = solver
        )
    }

    private fun factory(): OptimizationSolverFactory =
        OptimizationSolverFactory(provider = mm1Provider)

    private fun shcSpec(randomRestart: RandomRestartSpec? = null) =
        SolverSpec.StochasticHillClimbing(
            maxIterations = 1,
            replicationsPerEvaluation = 1,
            randomRestart = randomRestart,
            name = "shc-tracking-test"
        )

    // ── 1. enableCsvTrace = false → no trackers ──────────────────────────────

    @Test
    fun `enableCsvTrace and enableConsoleTrace both false produces no trackers`(@TempDir dir: Path) {
        val solver = factory().build(config(shcSpec()))
        val spec = SolverTrackingSpec(enableCsvTrace = false, enableConsoleTrace = false)

        val handles = spec.attachTo(solver, dir) { "ignored" }

        assertEquals(0, handles.size, "no trackers attached when both flags are off")
        // Run to completion and confirm no CSV was created.
        solver.runAllIterations()
        handles.stopAll()
        assertEquals(
            0,
            Files.list(dir).use { it.count() },
            "output directory must be empty when tracking is fully disabled"
        )
    }

    // ── 2. Plain solver + enableCsvTrace = true → single CSV with rows ──────

    @Test
    fun `enableCsvTrace on non-restart solver attaches single-level tracker and writes rows`(@TempDir dir: Path) {
        val solver = factory().build(config(shcSpec()))
        assertTrue(solver is StochasticHillClimber)
        val spec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = "trace1")

        val handles = spec.attachTo(solver, dir) { "should_not_be_used" }
        assertEquals(1, handles.size)

        solver.runAllIterations()
        handles.stopAll()

        val csv = dir.resolve("trace1.csv").toFile()
        assertTrue(csv.exists(), "CSV file must be created")
        val lines = csv.readLines()
        assertTrue(lines.size >= 2, "CSV must have a header + at least one data row; got ${lines.size}")
        assertTrue(lines.first().startsWith("RunNumber,"), "first line must be the header row")
    }

    // ── 3. RandomRestart solver → nested CSV with MACRO + MICRO rows ────────

    @Test
    fun `enableCsvTrace on RandomRestart solver attaches nested tracker with MACRO and MICRO rows`(@TempDir dir: Path) {
        val solver = factory().build(config(
            shcSpec(randomRestart = RandomRestartSpec(maxNumRestarts = 2))
        ))
        assertTrue(solver is RandomRestartSolver,
            "factory must wrap SHC in a RandomRestartSolver when randomRestart is set")
        val spec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = "nested_trace")

        val handles = spec.attachTo(solver, dir) { "ignored" }
        assertEquals(1, handles.size)

        solver.runAllIterations()
        handles.stopAll()

        val csv = dir.resolve("nested_trace.csv").toFile()
        assertTrue(csv.exists(), "nested CSV file must be created")
        val lines = csv.readLines()
        assertTrue(lines.size >= 3,
            "nested CSV must have a header + at least one MACRO row + at least one MICRO row")
        assertTrue(lines.first().contains("Level"),
            "nested CSV header must contain the Level column")
        val dataLines = lines.drop(1)
        assertTrue(dataLines.any { it.contains(",MACRO,") },
            "nested CSV must contain at least one MACRO row")
        assertTrue(dataLines.any { it.contains(",MICRO,") },
            "nested CSV must contain at least one MICRO row")
    }

    // ── 4. experimentLabel lands on every data row ──────────────────────────

    @Test
    fun `experimentLabel propagates to the tracker's ExperimentName column`(@TempDir dir: Path) {
        val solver = factory().build(config(shcSpec()))
        val spec = SolverTrackingSpec(
            enableCsvTrace = true,
            csvFileName = "label_test",
            experimentLabel = "TestRun_42"
        )

        val handles = spec.attachTo(solver, dir) { "ignored" }
        solver.runAllIterations()
        handles.stopAll()

        val lines = dir.resolve("label_test.csv").toFile().readLines()
        val dataLines = lines.drop(1)
        assertTrue(dataLines.isNotEmpty(), "expected at least one data row")
        assertTrue(
            dataLines.all { it.contains("TestRun_42") },
            "every data row must carry the experimentLabel; rows: $dataLines"
        )
    }

    // ── 5. csvFileName null defaults to the provided fallback ───────────────

    @Test
    fun `csvFileName null uses defaultFileName callback`(@TempDir dir: Path) {
        val solver = factory().build(config(shcSpec()))
        val spec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = null)

        val handles = spec.attachTo(solver, dir) { "fallback_stem" }
        solver.runAllIterations()
        handles.stopAll()

        assertTrue(
            dir.resolve("fallback_stem.csv").toFile().exists(),
            "CSV file should land at the fallback stem when csvFileName is null"
        )
    }

    // ── 6. csvFileName non-null overrides the fallback ──────────────────────

    @Test
    fun `csvFileName non-null overrides the fallback`(@TempDir dir: Path) {
        val solver = factory().build(config(shcSpec()))
        val spec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = "user_choice")

        val handles = spec.attachTo(solver, dir) { "should_not_be_used" }
        solver.runAllIterations()
        handles.stopAll()

        assertTrue(dir.resolve("user_choice.csv").toFile().exists())
        assertFalse(dir.resolve("should_not_be_used.csv").toFile().exists(),
            "fallback callback must not run when csvFileName is non-null")
    }

    // ── 7. CSV + console flags both on → two attached trackers ──────────────

    @Test
    fun `enableConsoleTrace alongside enableCsvTrace attaches both`(@TempDir dir: Path) {
        val solver = factory().build(config(shcSpec()))
        val spec = SolverTrackingSpec(
            enableCsvTrace = true,
            csvFileName = "both",
            enableConsoleTrace = true
        )

        val handles = spec.attachTo(solver, dir) { "ignored" }
        assertEquals(2, handles.size, "CSV + console flag should attach two trackers")

        // Run + stop cycle must be clean (no exceptions).
        solver.runAllIterations()
        handles.stopAll()
        assertTrue(dir.resolve("both.csv").toFile().exists())
    }

    // ── 8. stopAll detaches every tracker ──────────────────────────────────

    @Test
    fun `stopAll detaches trackers so subsequent emissions are silent`(@TempDir dir: Path) {
        val solver = factory().build(config(shcSpec()))
        val spec = SolverTrackingSpec(enableCsvTrace = true, csvFileName = "detach_test")

        val handles = spec.attachTo(solver, dir) { "ignored" }
        solver.runAllIterations()
        val rowsAfterFirstRun = dir.resolve("detach_test.csv").toFile().readLines().size
        assertTrue(rowsAfterFirstRun >= 2, "first run should have produced rows")
        handles.stopAll()

        // After stopAll, the trackers should not be subscribed any more.
        // We simulate a second run by emitting a fresh iteration snapshot
        // directly through the iterationEmitter — much cheaper than running
        // the solver again, and it directly tests the detach.
        solver.iterationEmitter.emit(
            ksl.simopt.solvers.SolverStateSnapshot(
                iterationNumber = 999,
                numOracleCalls = 0,
                numReplicationsRequested = 0,
                bestSolutionSoFar = solver.bestSolution,
                currentSolution = solver.currentSolution
            )
        )
        val rowsAfterSecondEmission = dir.resolve("detach_test.csv").toFile().readLines().size
        assertEquals(
            rowsAfterFirstRun,
            rowsAfterSecondEmission,
            "no new rows should be written after stopAll() detaches the tracker"
        )
    }

    private companion object {
        const val MM1_MODEL_ID = "MM1TrackingTest"
    }
}
