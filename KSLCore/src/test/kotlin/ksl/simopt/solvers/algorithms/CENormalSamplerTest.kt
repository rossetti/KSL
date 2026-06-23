package ksl.simopt.solvers.algorithms

import ksl.simopt.problem.ProblemDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A [CESampler] is self-sufficient: it can be exercised standalone (no [CrossEntropySolver]), which is
 * how new reference distributions can be developed and tested in isolation. These tests pin that
 * contract for the multivariate-normal sampler.
 */
class CENormalSamplerTest {

    private fun problem(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = "CENormalSamplerTest",
            modelIdentifier = "M",
            objFnResponseName = "y",
            inputNames = listOf("x1", "x2")
        )
        pd.inputVariable("x1", lowerBound = 0.0, upperBound = 10.0)
        pd.inputVariable("x2", lowerBound = 0.0, upperBound = 10.0)
        return pd
    }

    @Test
    fun samplerGeneratesPopulationsStandalone() {
        val sampler = CENormalSampler(problem())
        assertEquals(2, sampler.dimension, "dimension should match the problem input size")
        val population = sampler.sample(5)
        assertEquals(5, population.size, "should generate the requested number of points")
        population.forEach { assertEquals(2, it.size, "each point should match the dimension") }
        // Updating from an elite subset should keep the parameter dimension intact.
        sampler.updateParameters(population.take(2))
        assertEquals(2, sampler.parameters().size)
    }

    @Test
    fun standaloneSamplingIsReproducibleForAFixedStreamNumber() {
        fun firstPoint(): DoubleArray = CENormalSampler(problem(), streamNum = 1).sample()
        assertTrue(
            firstPoint().contentEquals(firstPoint()),
            "the same stream number should reproduce the same first sample"
        )
    }
}
