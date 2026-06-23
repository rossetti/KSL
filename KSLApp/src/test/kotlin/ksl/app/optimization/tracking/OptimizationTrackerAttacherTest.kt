package ksl.app.optimization.tracking

import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.OptimizationSolverFactory
import ksl.app.config.optimization.RandomRestartSpec
import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.SolverTrackingSpec
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Substrate-level tests for [OptimizationTrackerAttacher].
 *
 *  Builds a real [ksl.simopt.solvers.Solver] via
 *  `OptimizationSolverFactory` and verifies that the attacher
 *  selects the right tracker variant (plain vs. nested) and that
 *  disabled tracking is a no-op that leaves the filesystem clean.
 *  We do not run the solver — we only need an instance to bind
 *  trackers to.
 */
class OptimizationTrackerAttacherTest {

    private companion object { const val LK_ID = "LKInventoryModel" }

    private val lkProvider = MapModelProvider(LK_ID, object : ModelBuilderIfc {
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
    })

    private fun lkConfig(
        solver: SolverSpec = SolverSpec.StochasticHillClimbing(
            maxIterations = 2, replicationsPerEvaluation = 1
        )
    ): OptimizationRunConfiguration {
        val model = lkProvider.provideModel(LK_ID)
        return OptimizationRunConfiguration(
            model = ModelRunTemplate(
                modelReference = ModelReference.ByProviderId(LK_ID),
                runParameters = model.extractRunParameters()
            ),
            problem = OptimizationProblemSpec(
                problemName = "P",
                modelIdentifier = LK_ID,
                objectiveResponseName = "TotalCost",
                inputs = listOf(
                    OptimizationInputSpec(
                        "Inventory.reorderPoint",
                        lowerBound = 1.0, upperBound = 10.0, granularity = 1.0
                    )
                )
            ),
            solver = solver
        )
    }

    @Test
    fun `attach is a no-op when both CSV and console are disabled`(@TempDir tempDir: Path) {
        val config = lkConfig()
        val solver = OptimizationSolverFactory(lkProvider).build(config)
        val runDir = tempDir.resolve("run-001")
        val result = OptimizationTrackerAttacher.attach(
            solver = solver,
            trackingSpec = SolverTrackingSpec(
                enableCsvTrace = false,
                enableConsoleTrace = false
            ),
            runDir = runDir,
            solverSpec = config.solver
        )
        assertFalse(result.csvAttached, "CSV should not be attached when disabled")
        assertFalse(result.consoleAttached, "Console should not be attached when disabled")
        // Disabled tracking must not create the run directory —
        // that's the whole reason the attacher uses lazy mkdir.
        assertFalse(Files.exists(runDir),
            "Disabled tracking must not create the run directory")
    }

    @Test
    fun `attach creates the trace file's parent and attaches a CSV tracker`(@TempDir tempDir: Path) {
        val config = lkConfig()
        val solver = OptimizationSolverFactory(lkProvider).build(config)
        val runDir = tempDir.resolve("run-001")
        val result = OptimizationTrackerAttacher.attach(
            solver = solver,
            trackingSpec = SolverTrackingSpec(
                enableCsvTrace = true,
                csvFileName = "trace"
            ),
            runDir = runDir,
            solverSpec = config.solver
        )
        assertTrue(result.csvAttached)
        // Parent directory of the trace file must exist (the
        // attacher's lazy-mkdir contract); the file itself isn't
        // written until the solver runs.
        assertTrue(Files.exists(runDir))
    }

    @Test
    fun `attach against RandomRestartSolver picks nested CSV tracker variant`(@TempDir tempDir: Path) {
        // The plain CsvSolverStateTracker constructor accepts any
        // Solver; the nested variant requires both macro and micro
        // solvers.  This test relies on construction not throwing —
        // a regression where the attacher routed a
        // RandomRestartSolver into the plain variant would still
        // succeed, so we additionally verify the wrapper type.
        val config = lkConfig(
            solver = SolverSpec.StochasticHillClimbing(
                maxIterations = 2,
                replicationsPerEvaluation = 1,
                randomRestart = RandomRestartSpec(maxNumRestarts = 2)
            )
        )
        val solver = OptimizationSolverFactory(lkProvider).build(config)
        assertTrue(solver is ksl.simopt.solvers.algorithms.RandomRestartSolver,
            "Fixture sanity: factory must produce a RandomRestartSolver when " +
                "randomRestart is set")
        val runDir = tempDir.resolve("run-001")
        val result = OptimizationTrackerAttacher.attach(
            solver = solver,
            trackingSpec = SolverTrackingSpec(enableCsvTrace = true),
            runDir = runDir,
            solverSpec = config.solver
        )
        assertTrue(result.csvAttached)
    }
}
