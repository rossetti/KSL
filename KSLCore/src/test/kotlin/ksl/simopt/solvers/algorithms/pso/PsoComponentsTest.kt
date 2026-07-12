package ksl.simopt.solvers.algorithms.pso

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 *  Unit tests for the particle swarm strategy components: inertia schedules, boundary handlers, and
 *  velocity initializers. Components that draw randomness do so through a (non-run) solver's single
 *  stream, so a fixed stream number keeps the tests reproducible.
 */
class PsoComponentsTest {

    private val pd = PsoTestSupport.boxProblem(dim = 3, lb = -5.0, ub = 5.0)
    private fun solver() = PsoTestSupport.makeSolver(pd, PsoTestSupport.sphere(doubleArrayOf(0.0, 0.0, 0.0)), streamNum = 1)

    // ---- Inertia schedules ----

    @Test
    fun constantInertiaIsFlat() {
        val schedule = ConstantInertia(0.7)
        assertEquals(0.7, schedule.nextInertia(0), 1e-12)
        assertEquals(0.7, schedule.nextInertia(50), 1e-12)
        assertEquals(0.7, schedule.nextInertia(10_000), 1e-12)
    }

    @Test
    fun linearDecreasingInertiaInterpolatesAndFloors() {
        val schedule = LinearDecreasingInertia(initialInertia = 0.9, finalInertia = 0.4, horizon = 100)
        assertEquals(0.9, schedule.nextInertia(0), 1e-12, "starts at the initial inertia")
        assertEquals(0.65, schedule.nextInertia(50), 1e-9, "is the midpoint at the half horizon")
        assertEquals(0.4, schedule.nextInertia(100), 1e-12, "reaches the final inertia at the horizon")
        assertEquals(0.4, schedule.nextInertia(500), 1e-12, "is floored at the final inertia beyond the horizon")
    }

    @Test
    fun inertiaScheduleValidatesParameters() {
        assertThrows(IllegalArgumentException::class.java) { ConstantInertia(0.0) }
        assertThrows(IllegalArgumentException::class.java) {
            LinearDecreasingInertia(initialInertia = 0.4, finalInertia = 0.9)
        }
    }

    // ---- Solver default inertia schedule ----

    @Test
    fun defaultInertiaScheduleHorizonMatchesMaximumIterations() {
        // Regression: with no schedule supplied, the default LinearDecreasingInertia's horizon must
        // track the caller's maximumIterations, not the fixed psoDefaultMaxIterations (100). Otherwise
        // a raised iteration cap leaves the inertia pinned at the exploitation floor for the run's tail.
        val solver = ParticleSwarmSolver(
            problemDefinition = pd,
            evaluator = PsoTestSupport.FunctionEvaluator(pd, PsoTestSupport.sphere(doubleArrayOf(0.0, 0.0, 0.0))),
            streamNum = 1,
            maximumIterations = 250,
            replicationsPerEvaluation = 1
        )
        val schedule = solver.inertiaSchedule
        assertTrue(schedule is LinearDecreasingInertia, "the default schedule should be a LinearDecreasingInertia")
        val lin = schedule as LinearDecreasingInertia
        assertEquals(250, lin.horizon, "the default horizon should track maximumIterations")
        assertEquals(lin.initialInertia, lin.nextInertia(0), 1e-12, "starts at the initial inertia")
        assertEquals(lin.finalInertia, lin.nextInertia(250), 1e-12, "reaches the floor exactly at maximumIterations")
        assertTrue(
            lin.nextInertia(100) > lin.finalInertia,
            "must not be pinned at the floor by iteration 100 (the fixed-horizon-100 bug)"
        )
    }

    @Test
    fun suppliedInertiaScheduleIsUsedAsIs() {
        // A caller-supplied schedule must be used exactly as given — never replaced or re-horizoned.
        val custom = LinearDecreasingInertia(horizon = 42)
        val solver = ParticleSwarmSolver(
            problemDefinition = pd,
            evaluator = PsoTestSupport.FunctionEvaluator(pd, PsoTestSupport.sphere(doubleArrayOf(0.0, 0.0, 0.0))),
            streamNum = 1,
            inertiaSchedule = custom,
            maximumIterations = 250,
            replicationsPerEvaluation = 1
        )
        assertSame(custom, solver.inertiaSchedule, "a supplied schedule must be used as-is")
        assertEquals(42, (solver.inertiaSchedule as LinearDecreasingInertia).horizon)
    }

    // ---- Boundary handlers ----

    @Test
    fun clampToBoundsClipsOutOfRangeCoordinates() {
        val handler = ClampToBounds()
        val x = doubleArrayOf(-9.0, 0.0, 12.0)
        val result = handler.enforce(x, pd)
        assertEquals(-5.0, result[0], 1e-12, "below the lower bound clamps to the lower bound")
        assertEquals(0.0, result[1], 1e-12, "within range is unchanged")
        assertEquals(5.0, result[2], 1e-12, "above the upper bound clamps to the upper bound")
        assertNotSame(x, result, "the handler must return a new array")
        assertEquals(12.0, x[2], 1e-12, "the handler must not modify the supplied array")
    }

    @Test
    fun reflectAtBoundsKeepsResultWithinRange() {
        val handler = ReflectAtBounds()
        val result = handler.enforce(doubleArrayOf(-7.0, 6.0, 1.0), pd)
        result.forEach { assertTrue(it in -5.0..5.0, "reflected coordinates must stay within bounds (was $it)") }
        // A small overshoot reflects by the overshoot amount: -7 -> -5 + ( -5 - (-7) ) = -3
        assertEquals(-3.0, result[0], 1e-12)
        assertEquals(4.0, result[1], 1e-12) // 6 -> 5 - (6 - 5) = 4
    }

    // ---- Velocity initializers ----

    @Test
    fun zeroVelocityIsAllZeros() {
        val v = ZeroVelocity().initialVelocity(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(2.0, 2.0, 2.0), solver())
        assertEquals(3, v.size)
        v.forEach { assertEquals(0.0, it, 1e-12) }
    }

    @Test
    fun uniformRandomVelocityStaysWithinVMax() {
        val vMax = doubleArrayOf(2.0, 3.0, 0.0)
        val v = UniformRandomVelocity().initialVelocity(doubleArrayOf(0.0, 0.0, 0.0), vMax, solver())
        assertTrue(v[0] in -2.0..2.0, "component 0 must lie within +/- vMax")
        assertTrue(v[1] in -3.0..3.0, "component 1 must lie within +/- vMax")
        assertEquals(0.0, v[2], 1e-12, "a zero vMax component must initialize to zero")
    }

    @Test
    fun uniformRandomVelocityIsReproducibleForAFixedStream() {
        val vMax = doubleArrayOf(2.0, 2.0, 2.0)
        val a = UniformRandomVelocity().initialVelocity(doubleArrayOf(0.0, 0.0, 0.0), vMax, solver())
        val b = UniformRandomVelocity().initialVelocity(doubleArrayOf(0.0, 0.0, 0.0), vMax, solver())
        assertEquals(a.toList(), b.toList(), "the same stream number must reproduce the same velocity")
    }
}
