package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.random.rvariable.KSLRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for the ISC global-phase selection components: [LinearRankingSelection],
 *  [StochasticUniversalSampling], and [NoiseGroupingProcedure].
 */
class NgaComponentsTest {

    private val pd: ProblemDefinition = IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 100.0)

    private fun sf(x: Double, fitness: Double, variance: Double = 1.0, count: Double = 5.0): SharedFitness {
        val sol: Solution = IscTestSupport.solutionWith(pd, doubleArrayOf(x), fitness, count)
        return SharedFitness(sol, fitness, variance, 1)
    }

    @Test
    fun linearRankingProbabilitiesSumToOneAndFavorTheBest() {
        // One member per group, best-first.
        val groups = listOf(5.0, 10.0, 20.0, 40.0).mapIndexed { i, v -> FitnessGroup(listOf(sf(i.toDouble(), v))) }
        val probs = LinearRankingSelection(eta = 1.5).selectionProbabilities(groups)
        val total = probs.sumOf { it.second }
        assertEquals(1.0, total, 1e-9, "linear-rank probabilities must sum to one")
        val best = probs.first().second
        val worst = probs.last().second
        assertTrue(best > worst, "the best-ranked individual must have the highest selection probability")
    }

    @Test
    fun groupAveragingGivesEqualProbabilityWithinAGroup() {
        // Two members tied in one group, plus a clearly worse third in its own group.
        val groups = listOf(
            FitnessGroup(listOf(sf(0.0, 1.0), sf(1.0, 1.0))),
            FitnessGroup(listOf(sf(2.0, 100.0)))
        )
        val probs = LinearRankingSelection(eta = 1.5).selectionProbabilities(groups)
        assertEquals(probs[0].second, probs[1].second, 1e-12,
            "members in the same noise-aware group share one selection probability")
    }

    @Test
    fun susSamplesTheRequestedNumberOfParents() {
        val weighted = (0..4).map { IscTestSupport.solutionWith(pd, doubleArrayOf(it.toDouble()), it.toDouble(), 5.0) to (5 - it).toDouble() }
        val parents = StochasticUniversalSampling().sample(weighted, 8, KSLRandom.rnStream(1))
        assertEquals(8, parents.size, "SUS must return exactly the requested number of parents")
    }

    @Test
    fun groupingMergesIndistinguishableAndSeparatesDistinctFitness() {
        val identical = (0..5).map { sf(it.toDouble(), 10.0, variance = 0.01) }
        val oneGroup = NoiseGroupingProcedure(alphaG = 0.1, gm = 3).group(identical)
        assertEquals(1, oneGroup.size, "members with identical fitness must form a single group")

        val spread = listOf(sf(0.0, 1.0, 0.01), sf(1.0, 50.0, 0.01), sf(2.0, 100.0, 0.01))
        val manyGroups = NoiseGroupingProcedure(alphaG = 0.1, gm = 3).group(spread)
        assertTrue(manyGroups.size > 1, "widely separated fitness (low noise) must split into multiple groups")
        assertTrue(manyGroups.size <= 3, "the number of groups must not exceed gm")
    }

    @Test
    fun nonUniformMutationStaysFinitePastTheHorizon() {
        // Regression: past the annealing horizon bigK, (1 - k/bigK) goes negative and a fractional
        // exponent produced NaN, which silently propagated to every mutated coordinate. Run the NGA a
        // few generations past bigK (BudgetRule never fires, so it runs to maxIterations), then
        // exercise the operator directly and require finite, in-bounds output.
        val evaluator = IscTestSupport.FunctionEvaluator(pd, IscTestSupport.sphere(doubleArrayOf(50.0)))
        val nga = NichingGeneticAlgorithmSolver(
            problemDefinition = pd,
            evaluator = evaluator,
            streamNum = 1,
            mutation = NonUniformMutation(bigK = 1, p3 = 1.0),
            transitionRules = listOf(BudgetRule(Int.MAX_VALUE)),
            maxIterations = 3,
            replicationsPerEvaluation = 2
        )
        nga.runAllIterations()
        assertTrue(nga.currentGeneration > 1, "the run must advance past bigK=1 to exercise the annealing path")
        val mutated = nga.mutation.mutate(doubleArrayOf(50.0), nga)
        mutated.forEach { v ->
            assertTrue(v.isFinite(), "annealed mutation past the horizon must be finite (regression: was NaN)")
            assertTrue(v in 0.0..100.0, "annealed mutation must stay within the input bounds")
        }
    }
}
