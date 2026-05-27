package ksl.app.config.optimization

import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simopt.solvers.algorithms.CENormalSampler
import ksl.simopt.solvers.algorithms.CrossEntropySolver
import ksl.simopt.solvers.algorithms.RSplineSolver
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 5.85 Step 6 acceptance: [OptimizationSolverFactory].
 *
 * Tests verify that each [SolverSpec] variant translates to the correct
 * engine factory call, that random-restart wrapping is applied when
 * requested, that starting points are forwarded, that
 * [EvaluationSpec]-driven cache and solver settings reach the built
 * solver, and that [PenaltyFunctionSpec] values land on the
 * [ksl.simopt.problem.ProblemDefinition].
 *
 * No solver is run end-to-end; the tests inspect the constructed
 * objects only.  Per-iteration solver behavior is exercised in the
 * existing simopt-solver integration tests, not here.
 */
class OptimizationSolverFactoryTest {

    // ── Test fixtures ────────────────────────────────────────────────────────

    private fun mm1Model(): Model {
        val model = Model(MM1_MODEL_ID, autoCSVReports = false)
        GIGcQueue(model, numServers = 1, name = "MM1Queue")
        model.numberOfReplications = 3
        model.lengthOfReplication = 100.0
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
    private fun secondResponseName(): String = mm1Model().responseNames[1]

    private fun config(
        solver: SolverSpec,
        problem: OptimizationProblemSpec? = null,
        evaluation: EvaluationSpec = EvaluationSpec()
    ): OptimizationRunConfiguration {
        val model = mm1Model()
        return OptimizationRunConfiguration(
            model = ModelRunTemplate(
                modelReference = ModelReference.ByProviderId(MM1_MODEL_ID),
                runParameters  = model.extractRunParameters()
            ),
            problem = problem ?: OptimizationProblemSpec(
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

    private fun factory(): OptimizationSolverFactory =
        OptimizationSolverFactory(provider = mm1Provider)

    // ── 1. Stochastic Hill Climbing ──────────────────────────────────────────

    @Test
    fun `factory builds StochasticHillClimber from SHC spec`() {
        val solver = factory().build(config(
            solver = SolverSpec.StochasticHillClimbing(
                maxIterations = 7,
                replicationsPerEvaluation = 4,
                name = "shc-instance"
            )
        ))
        assertTrue(solver is StochasticHillClimber)
        assertEquals(7, solver.maximumNumberIterations)
    }

    // ── 2. Simulated Annealing (Fixed temperature, Exponential cooling) ──────

    @Test
    fun `factory builds SimulatedAnnealing from SA spec`() {
        val solver = factory().build(config(
            solver = SolverSpec.SimulatedAnnealing(
                maxIterations = 12,
                replicationsPerEvaluation = 5,
                temperature = TemperatureSpec.Fixed(temperature = 500.0),
                coolingSchedule = CoolingScheduleSpec.Exponential(initialTemperature = 500.0, coolingRate = 0.93),
                stoppingTemperature = 0.01
            )
        ))
        assertTrue(solver is SimulatedAnnealing)
        assertEquals(12, solver.maximumNumberIterations)
        assertEquals(0.01, solver.stoppingTemperature)
    }

    // ── 3. Cross Entropy with Normal sampler ─────────────────────────────────

    @Test
    fun `factory builds CrossEntropySolver from CE spec with Normal sampler`() {
        val solver = factory().build(config(
            solver = SolverSpec.CrossEntropy(
                maxIterations = 10,
                replicationsPerEvaluation = 6,
                sampler = CESamplerSpec.Normal(
                    meanSmoother = 0.7,
                    sdSmoother   = 0.7,
                    coefficientOfVariationThreshold = 0.05,
                    streamNum = 21
                ),
                elitePct = 0.15,
                ceSampleSize = 75
            )
        ))
        assertTrue(solver is CrossEntropySolver)
        assertTrue(solver.ceSampler is CENormalSampler)
        assertEquals(0.15, solver.elitePct)
        assertEquals(75, solver.ceSampleSize)
    }

    // ── 4. RSpline ───────────────────────────────────────────────────────────

    @Test
    fun `factory builds RSplineSolver from RSpline spec`() {
        // RSpline requires an integer-ordered problem
        val problem = OptimizationProblemSpec(
            objectiveResponseName = firstResponseName(),
            inputs = listOf(
                OptimizationInputSpec(
                    name = firstInputKey(),
                    lowerBound = 1.0,
                    upperBound = 10.0,
                    granularity = 1.0
                )
            )
        )
        val solver = factory().build(config(
            solver = SolverSpec.RSpline(
                maxIterations = 15,
                initialNumReps = 2,
                sampleSizeGrowthRate = 1.1,
                maxNumReplications = 100
            ),
            problem = problem
        ))
        assertTrue(solver is RSplineSolver)
        assertEquals(15, solver.maximumNumberIterations)
    }

    // ── 5. Random restart wrapping ───────────────────────────────────────────

    @Test
    fun `factory wraps SHC in RandomRestartSolver when randomRestart is set`() {
        val solver = factory().build(config(
            solver = SolverSpec.StochasticHillClimbing(
                maxIterations = 5,
                replicationsPerEvaluation = 2,
                randomRestart = RandomRestartSpec(maxNumRestarts = 3)
            )
        ))
        assertTrue(solver is RandomRestartSolver)
        assertTrue((solver as RandomRestartSolver).restartingSolver is StochasticHillClimber)
    }

    @Test
    fun `factory wraps CE in RandomRestartSolver when randomRestart is set`() {
        val solver = factory().build(config(
            solver = SolverSpec.CrossEntropy(
                maxIterations = 5,
                replicationsPerEvaluation = 2,
                randomRestart = RandomRestartSpec(maxNumRestarts = 2),
                elitePct = 0.2
            )
        ))
        assertTrue(solver is RandomRestartSolver)
        val inner = (solver as RandomRestartSolver).restartingSolver
        assertTrue(inner is CrossEntropySolver)
        // CE-specific extras applied on the inner solver, not the wrapper
        assertEquals(0.2, (inner as CrossEntropySolver).elitePct)
    }

    // ── 6. Starting point applied ────────────────────────────────────────────

    @Test
    fun `factory forwards starting point to the built solver`() {
        val key = firstInputKey()
        val solver = factory().build(config(
            solver = SolverSpec.StochasticHillClimbing(
                maxIterations = 5,
                replicationsPerEvaluation = 2,
                startingPoint = mapOf(key to 1.5)
            )
        ))
        val sp = solver.startingPoint
        assertNotNull(sp, "starting point should be forwarded")
        assertEquals(1.5, sp[key])
    }

    // ── 7. EvaluationSpec settings applied ───────────────────────────────────

    @Test
    fun `EvaluationSpec snapshotFrequency and ensureProblemFeasibleRequests are applied`() {
        val solver = factory().build(config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2),
            evaluation = EvaluationSpec(
                snapshotFrequency = 7,
                ensureProblemFeasibleRequests = true
            )
        ))
        assertEquals(7, solver.snapShotFrequency)
        assertTrue(solver.ensureProblemFeasibleRequests)
    }

    // ── 8. useSolutionCache = false disables cache I/O ───────────────────────

    @Test
    fun `useSolutionCache false disables both cache lookups and puts`() {
        val solver = factory().build(config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2),
            evaluation = EvaluationSpec(useSolutionCache = false)
        ))
        val cache = solver.evaluator.cache
        assertNotNull(cache, "evaluator should still hold a cache instance")
        assertEquals(false, cache.allowCacheLookups)
        assertEquals(false, cache.allowCachePuts)
    }

    // ── 9. Cache-builder helpers (spec → engine mapping) ────────────────────
    //
    // These tests target the public companion helpers
    // [OptimizationSolverFactory.makeSolutionCache] and
    // [OptimizationSolverFactory.makeSimulationRunCache] directly rather than
    // observing the constructed Evaluator.  Evaluator does not expose its
    // simulation-run cache publicly, and we deliberately do not modify the
    // engine API just to enable observation here.

    @Test
    fun `makeSimulationRunCache returns non-null when useSimulationRunCache is true`() {
        val cache = OptimizationSolverFactory.makeSimulationRunCache(
            EvaluationSpec(useSimulationRunCache = true)
        )
        assertNotNull(cache, "Expected non-null cache when useSimulationRunCache=true")
    }

    @Test
    fun `makeSimulationRunCache returns null when useSimulationRunCache is false`() {
        val cache = OptimizationSolverFactory.makeSimulationRunCache(
            EvaluationSpec(useSimulationRunCache = false)
        )
        assertNull(cache, "Expected null cache when useSimulationRunCache=false")
    }

    @Test
    fun `makeSolutionCache returns a usable cache when useSolutionCache is true`() {
        val cache = OptimizationSolverFactory.makeSolutionCache(
            EvaluationSpec(useSolutionCache = true)
        )
        assertEquals(true, cache.allowCacheLookups)
        assertEquals(true, cache.allowCachePuts)
    }

    @Test
    fun `makeSolutionCache returns a disabled cache when useSolutionCache is false`() {
        val cache = OptimizationSolverFactory.makeSolutionCache(
            EvaluationSpec(useSolutionCache = false)
        )
        assertEquals(false, cache.allowCacheLookups)
        assertEquals(false, cache.allowCachePuts)
    }

    // ── 10. ByProviderId without a provider throws ──────────────────────────

    @Test
    fun `ByProviderId reference without a provider throws IllegalArgumentException`() {
        val factoryWithoutProvider = OptimizationSolverFactory(provider = null)
        assertThrows<IllegalArgumentException> {
            factoryWithoutProvider.build(config(
                solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2)
            ))
        }
    }

    // ── 11. Type-level penalty defaults applied to the ProblemDefinition ─────

    @Test
    fun `problem-level default penalty functions are translated to engine instances`() {
        // Build with non-default penalty defaults to be sure the values reach the engine.
        val problem = OptimizationProblemSpec(
            objectiveResponseName = firstResponseName(),
            inputs = listOf(
                OptimizationInputSpec(name = firstInputKey(), lowerBound = 0.1, upperBound = 10.0)
            ),
            defaultLinearPenalty   = PenaltyFunctionSpec.WithMemory(basePenalty = 200.0),
            defaultResponsePenalty = PenaltyFunctionSpec.DynamicPolynomial(basePenalty = 75.0)
        )
        val solver = factory().build(config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2),
            problem = problem
        ))
        val pd = solver.problemDefinition
        assertTrue(pd.defaultLinearPenalty is ksl.simopt.problem.PenaltyFunctionWithMemory,
            "Expected defaultLinearPenalty translated to PenaltyFunctionWithMemory")
        assertTrue(pd.defaultResponsePenalty is ksl.simopt.problem.DynamicPolynomialPenalty,
            "Expected defaultResponsePenalty translated to DynamicPolynomialPenalty")
        assertEquals(200.0,
            (pd.defaultLinearPenalty as ksl.simopt.problem.PenaltyFunctionWithMemory).basePenalty)
        assertEquals(75.0,
            (pd.defaultResponsePenalty as ksl.simopt.problem.DynamicPolynomialPenalty).basePenalty)
    }

    // ── 12. Per-constraint penalty applied (Option A engine extension) ───────

    @Test
    fun `per-constraint penalty function is attached on linear and response constraints`() {
        val problem = OptimizationProblemSpec(
            objectiveResponseName = firstResponseName(),
            inputs = listOf(
                OptimizationInputSpec(name = firstInputKey(), lowerBound = 0.1, upperBound = 10.0)
            ),
            responseNames = listOf(secondResponseName()),
            linearConstraints = listOf(
                LinearConstraintSpec(
                    coefficients = mapOf(firstInputKey() to 1.0),
                    rhsValue = 5.0,
                    penaltyFunction = PenaltyFunctionSpec.DynamicPolynomial(basePenalty = 250.0)
                )
            ),
            responseConstraints = listOf(
                ResponseConstraintSpec(
                    name = secondResponseName(),
                    rhsValue = 0.95,
                    inequalityType = InequalityType.GREATER_THAN,
                    penaltyFunction = PenaltyFunctionSpec.WithMemory(basePenalty = 50.0)
                )
            )
        )
        val solver = factory().build(config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2),
            problem = problem
        ))
        val linear = solver.problemDefinition.linearConstraints.first()
        val response = solver.problemDefinition.responseConstraints.first()
        assertTrue(linear.penaltyFunction is ksl.simopt.problem.DynamicPolynomialPenalty,
            "Expected per-linear-constraint penalty translated to DynamicPolynomialPenalty")
        assertTrue(response.penaltyFunction is ksl.simopt.problem.PenaltyFunctionWithMemory,
            "Expected per-response-constraint penalty translated to PenaltyFunctionWithMemory")
        assertEquals(250.0,
            (linear.penaltyFunction as ksl.simopt.problem.DynamicPolynomialPenalty).basePenalty)
        assertEquals(50.0,
            (response.penaltyFunction as ksl.simopt.problem.PenaltyFunctionWithMemory).basePenalty)
    }

    // ── Draft-document rejection ─────────────────────────────────────────────

    @Test
    fun `factory rejects null problem with a clear error`() {
        val cfg = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 1)
        ).copy(problem = null)
        val ex = assertThrows<IllegalStateException> { factory().build(cfg) }
        assertTrue(
            "problem" in (ex.message ?: ""),
            "Error message should name the missing 'problem' section; was: ${ex.message}"
        )
    }

    @Test
    fun `factory rejects null solver with a clear error`() {
        val cfg = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 1)
        ).copy(solver = null)
        val ex = assertThrows<IllegalStateException> { factory().build(cfg) }
        assertTrue(
            "solver" in (ex.message ?: ""),
            "Error message should name the missing 'solver' section; was: ${ex.message}"
        )
    }

    @Test
    fun `factory rejects both null problem and null solver and names both`() {
        val cfg = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 1)
        ).copy(problem = null, solver = null)
        val ex = assertThrows<IllegalStateException> { factory().build(cfg) }
        assertTrue("problem" in (ex.message ?: "") && "solver" in (ex.message ?: ""),
            "Error should name both missing sections; was: ${ex.message}")
    }

    private companion object {
        const val MM1_MODEL_ID = "MM1OptSolverFactoryTest"
    }
}
