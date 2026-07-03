package ksl.simopt.solvers.algorithms.isc

import ksl.utilities.random.rvariable.KSLRandom
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for [RmdSampler]: the coordinate-direction walk stays inside the most-promising area,
 *  honors box bounds and the integer grid, and is reproducible for a fixed stream.
 */
class RmdSamplerTest {

    @Test
    fun sampledPointsRemainInsideTheMostPromisingArea() {
        val pd = IscTestSupport.boxProblem(dim = 2, lb = 0.0, ub = 10.0, granularity = 1.0)
        val center = doubleArrayOf(5.0, 5.0)
        val mpa = MostPromisingArea(pd, center, listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(10.0, 0.0)))
        val sampler = RmdSampler(pd, KSLRandom.rnStream(1))
        repeat(200) {
            val p = sampler.sample(mpa, center, warmUp = 30)
            assertTrue(mpa.contains(p), "every sampled point must lie in the MPA (was ${p.toList()})")
        }
    }

    @Test
    fun sampledCoordinatesLandOnTheIntegerGrid() {
        val pd = IscTestSupport.boxProblem(dim = 2, lb = 0.0, ub = 10.0, granularity = 1.0)
        val mpa = MostPromisingArea(pd, doubleArrayOf(5.0, 5.0), emptyList())
        val sampler = RmdSampler(pd, KSLRandom.rnStream(2))
        repeat(100) {
            val p = sampler.sample(mpa, doubleArrayOf(5.0, 5.0), warmUp = 20)
            p.forEach { v ->
                assertEquals(v, Math.round(v).toDouble(), 1e-9, "granularity 1.0 means integer coordinates")
                assertTrue(v in 0.0..10.0, "coordinates stay within the box bounds")
            }
        }
    }

    @Test
    fun runsAreReproducibleForAFixedStream() {
        val pd = IscTestSupport.boxProblem(dim = 3, lb = 0.0, ub = 20.0, granularity = 1.0)
        val mpa = MostPromisingArea(pd, doubleArrayOf(10.0, 10.0, 10.0), emptyList())
        // The same underlying stream, reset to its start, must reproduce the same walk.
        val stream = KSLRandom.rnStream(7)
        stream.resetStartStream()
        val a = RmdSampler(pd, stream).sample(mpa, doubleArrayOf(10.0, 10.0, 10.0), warmUp = 40)
        stream.resetStartStream()
        val b = RmdSampler(pd, stream).sample(mpa, doubleArrayOf(10.0, 10.0, 10.0), warmUp = 40)
        assertArrayEquals(a, b, 1e-12, "resetting the stream must reproduce the same sampled point")
    }

    @Test
    fun continuousInputsAreSampledOffGrid() {
        val pd = IscTestSupport.boxProblem(dim = 1, lb = 0.0, ub = 1.0, granularity = 0.0)
        val mpa = MostPromisingArea(pd, doubleArrayOf(0.5), emptyList())
        val sampler = RmdSampler(pd, KSLRandom.rnStream(3))
        var sawNonInteger = false
        repeat(50) {
            val p = sampler.sample(mpa, doubleArrayOf(0.5), warmUp = 10)
            assertTrue(p[0] in 0.0..1.0, "continuous sample must stay in bounds")
            if (p[0] != 0.0 && p[0] != 1.0) sawNonInteger = true
        }
        assertTrue(sawNonInteger, "continuous granularity should produce off-grid values")
    }
}
