package ksl.simopt.solvers

import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.ResponseFunctionOracle
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.AppreciateDepreciateSequence
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ParkKimMemory
import ksl.simopt.problem.ParkKimPenalty
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.CrossEntropySolver
import ksl.simopt.solvers.algorithms.RSplineSolver
import org.junit.jupiter.api.Test
import kotlin.math.min
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Fast, deterministic integration guards that drive the REAL Cross-Entropy and R-SPLINE solvers over
 * a cheap synthetic constrained problem (no discrete-event model), verifying that:
 *  - the memoryless default penalty steers the solver into the feasible region (the CE-regression
 *    invariant, at the solver level); and
 *  - a Park-Kim PFM penalty, under a growth replication schedule (CE) or R-SPLINE's natural growth,
 *    accumulates per-solution memory across re-sampled design points and still lands feasible.
 *
 * Synthetic problem: minimize Cost = x + y (with small noise) over integer (x, y) in [1, 20]^2 subject
 * to Service >= 0.95, where Service(x, y) = min(0.99, 0.50 + 0.10*(x + y)). Service saturates just past
 * the boundary: x + y <= 4 is infeasible (Service <= 0.90) while x + y >= 5 is feasible with margin
 * (Service = 0.99). Minimizing cost pulls the search toward the cheap infeasible corner (x + y = 2,
 * Service 0.70); a working penalty must stop the descent at the feasible region (x + y >= 5) -- the
 * same behavior whose absence was the CE regression. Landing anywhere feasible gives Service 0.99, so
 * the feasibility assertion is robust to the search's exact stopping point.
 *
 * The objective carries small stream-driven noise (a deterministic zero-variance oracle breaks the
 * solvers' confidence-interval convergence checks); with fixed streams the whole search is reproducible.
 */
class SolverPfmIntegrationTest {

    private val modelId = "pfmSolverModel"
    private val cost = "Cost"
    private val service = "Service"

    private fun problem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "pfmSolverProblem",
            modelIdentifier = modelId,
            objFnResponseName = cost,
            inputNames = listOf("x", "y"),
            responseNames = listOf(service)
        )
        pd.inputVariable("x", 1.0, 20.0, granularity = 1.0)
        pd.inputVariable("y", 1.0, 20.0, granularity = 1.0)
        pd.responseConstraint(service, 0.95, InequalityType.GREATER_THAN)
        return pd
    }

    private fun evaluator(pd: ProblemDefinition): EvaluatorIfc {
        val oracle = ResponseFunctionOracle(
            modelId, setOf(cost, service),
            ResponseFunctionBuilderIfc { streamProvider ->
                val stream = streamProvider.rnStream(1) // acquired at construction (oracle contract)
                ResponseFunctionIfc { inputs ->
                    val x = inputs.getValue("x")
                    val y = inputs.getValue("y")
                    // Small mean-zero noise on the objective so replication variance is positive.
                    val noise = stream.randU01() - 0.5
                    mapOf(cost to (x + y + noise), service to min(0.99, 0.50 + 0.10 * (x + y)))
                }
            }
        )
        return Evaluator(pd, oracle, MemorySolutionCache())
    }

    private fun maxVisitCount(solver: Solver): Int =
        solver.evaluator.cache?.values.orEmpty()
            .mapNotNull { it.penaltyMemory[service] as? ParkKimMemory }
            .maxOfOrNull { it.visitCount } ?: 0

    private fun report(tag: String, best: Solution, maxVisits: Int) {
        println(
            "[$tag] x=${best.inputMap["x"]} y=${best.inputMap["y"]} cost=${best.estimatedObjFncValue} " +
                "service=${best.responseAverages[service]} feasible=${best.isResponseConstraintFeasible()} " +
                "maxVisitCount=$maxVisits"
        )
    }

    @Test
    fun ceWithMemorylessDefaultFindsFeasible() {
        val pd = problem()
        val solver = CrossEntropySolver(
            pd, evaluator(pd),
            maximumIterations = 30,
            replicationsPerEvaluation = FixedReplicationsPerEvaluation(30),
            streamNum = 1
        )
        solver.runAllIterations()
        val best = solver.bestSolution
        report("CE-memoryless", best, maxVisitCount(solver))
        assertNotNull(best)
        assertTrue(best.responseAverages[service]!! >= 0.95,
            "the memoryless penalty must steer CE into the feasible region (Service >= 0.95)")
    }

    @Test
    fun ceWithGrowthAndPfmEngagesAndFindsFeasible() {
        val pd = problem()
        pd.defaultResponsePenalty = ParkKimPenalty(AppreciateDepreciateSequence(2.0, 0.5, 1.0))
        val solver = CrossEntropySolver(
            pd, evaluator(pd),
            maximumIterations = 30,
            replicationsPerEvaluation = FixedGrowthRateReplicationSchedule(
                initialNumReps = 20, growthRate = 0.15, maxNumReplications = 200
            ),
            streamNum = 1
        )
        solver.runAllIterations()
        val best = solver.bestSolution
        val visits = maxVisitCount(solver)
        report("CE-PFM", best, visits)
        assertTrue(pd.hasMemoryfulPenalty(), "the problem should carry a memoryful (PFM) penalty")
        assertTrue(visits >= 2, "PFM memory must accumulate across re-sampled points under the growth schedule")
        assertTrue(best.responseAverages[service]!! >= 0.95, "CE + PFM must recommend a feasible point")
    }

    @Test
    fun rsplineWithGrowthAndPfmEngagesAndFindsFeasible() {
        val pd = problem()
        pd.defaultResponsePenalty = ParkKimPenalty(AppreciateDepreciateSequence(2.0, 0.5, 1.0))
        val solver = RSplineSolver(
            pd, evaluator(pd),
            maximumIterations = 20,
            initialNumReps = 10,
            sampleSizeGrowthRate = 0.15,
            maxNumReplications = 200,
            streamNum = 1
        )
        solver.runAllIterations()
        val best = solver.bestSolution
        val visits = maxVisitCount(solver)
        report("RSPLINE-PFM", best, visits)
        assertTrue(pd.hasMemoryfulPenalty(), "the problem should carry a memoryful (PFM) penalty")
        assertTrue(visits >= 2, "PFM memory must accumulate across R-SPLINE's naturally growing re-samples")
        assertTrue(best.responseAverages[service]!! >= 0.95, "R-SPLINE + PFM must recommend a feasible point")
    }
}
