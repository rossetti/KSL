package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for [NicheIdentifier] (ISC Algorithm 2): niche centers, count `q`, and radius `r` on
 *  hand-constructed populations whose niche structure is known.
 */
class NicheIdentifierTest {

    private val pd: ProblemDefinition = IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 10.0, granularity = 1.0)
    private val identifier = NicheIdentifier()
    private val byValue: (Solution, Solution) -> Int = { a, b -> a.penalizedObjFncValue.compareTo(b.penalizedObjFncValue) }

    /** A bimodal objective on the integer line: minima at x = 2 and x = 8 (both value 0). */
    private fun bimodal(x: Double): Double {
        val a = (x - 2.0) * (x - 2.0)
        val b = (x - 8.0) * (x - 8.0)
        return minOf(a, b)
    }

    private fun populationOver(range: IntRange, f: (Double) -> Double): List<Solution> =
        range.map { x -> IscTestSupport.solutionWith(pd, doubleArrayOf(x.toDouble()), f(x.toDouble()), count = 5.0) }

    @Test
    fun twoSeparatedMinimaYieldTwoNiches() {
        val population = populationOver(0..10, ::bimodal)
        val result = identifier.identify(population, pd, byValue)
        assertEquals(2, result.count, "the bimodal population must yield exactly two niche centers")
        val centerX = result.niches.map { it.center.inputMap.inputValues[0] }.sorted()
        assertEquals(listOf(2.0, 8.0), centerX, "the centers must sit at the two minima")
        assertEquals(3.0, result.radius, 1e-9, "r = 0.5 * |8 - 2| = 3.0")
    }

    @Test
    fun unimodalPopulationYieldsOneNiche() {
        val population = populationOver(0..10) { x -> (x - 5.0) * (x - 5.0) }
        val result = identifier.identify(population, pd, byValue)
        assertEquals(1, result.count, "a unimodal population must yield a single niche")
        assertEquals(5.0, result.niches.first().center.inputMap.inputValues[0], 1e-9, "the center is the minimizer")
    }

    @Test
    fun singletonPopulationIsOneNiche() {
        val population = populationOver(3..3, ::bimodal)
        val result = identifier.identify(population, pd, byValue)
        assertEquals(1, result.count)
        assertEquals(1, result.niches.first().size)
    }

    @Test
    fun everyPopulationMemberIsAssignedToANiche() {
        val population = populationOver(0..10, ::bimodal)
        val result = identifier.identify(population, pd, byValue)
        val assigned = result.niches.sumOf { it.size }
        assertTrue(assigned >= population.size,
            "with two centers at distance 6 and r = 3, every member lies within r of a center")
    }
}
