/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.agent

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Phase A3 — force factories and the integrator, checked **analytically**.
 *
 *  Each Reynolds / Helbing force has a closed form that can be evaluated by hand on
 *  a two- or three-agent configuration. Asserting the exact expected vector is a
 *  far stronger check than comparing emergent behavior against a published figure:
 *  it pins normalization, sign, self-exclusion, and radius handling individually,
 *  and it fails loudly on a refactor that changes any of them.
 *
 *  Investigation established that `Dynamics.step` is **semi-implicit (symplectic)
 *  Euler** — velocity is advanced from the summed force, clamped, and only then is
 *  position advanced using the *new* velocity. That ordering is the correct choice
 *  for particle dynamics and is pinned explicitly below, since an "improvement" to
 *  plain explicit Euler would be silent and would degrade long-run energy behavior.
 *
 *  These tests also give first coverage to `Dynamics.netForceOn`, which had none.
 */
class ForceAnalyticTest {

    private class ForceModel(
        parent: ModelElement,
        torus: Boolean = false,
        size: Double = 100.0,
    ) : AgentModel(parent, "forceModel") {
        val ctx: Context<Agent> = Context("agents")
        val space: ContinuousProjection<Agent> =
            ContinuousProjection(ctx, 0.0..size, 0.0..size, torus = torus)
        inner class P(aName: String) : Agent(aName)
    }

    private fun model(torus: Boolean = false, size: Double = 100.0): ForceModel =
        ForceModel(Model("ForceAnalytic"), torus, size)

    private fun ForceModel.place(name: String, x: Double, y: Double): AgentModel.Agent {
        val a = P(name)
        ctx.add(a)
        space.placeAt(a, Point2D(x, y))
        return a
    }

    private fun assertPoint(x: Double, y: Double, actual: Point2D, message: String) {
        assertEquals(x, actual.x, 1e-9, "$message (x)")
        assertEquals(y, actual.y, 1e-9, "$message (y)")
    }

    /** A dynamics with clamping effectively disabled, so raw force maths shows through. */
    private fun unclamped(m: ForceModel, mass: Double = 1.0): Dynamics<AgentModel.Agent> =
        Dynamics(m.space, mass = { mass }, maxSpeed = 1.0e6, minSpeed = 0.0)

    // ── A3.1 — exact force values ────────────────────────────────────────────

    @Test
    @DisplayName("A3.1: alignment with one neighbour is exactly the velocity difference")
    fun alignmentWithOneNeighbourIsVelocityDifference() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        val b = m.place("b", 1.0, 0.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point2D(1.0, 0.0))
        dyn.setVelocity(b, Point2D(3.0, 4.0))
        dyn.addForce(alignment(radius = 5.0))
        assertPoint(2.0, 4.0, dyn.netForceOn(a), "alignment = avg(neighbour v) - own v")
    }

    @Test
    @DisplayName("A3.1: alignment averages over several neighbours")
    fun alignmentAveragesOverNeighbours() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        val b = m.place("b", 1.0, 0.0)
        val c = m.place("c", 0.0, 1.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point2D(1.0, 0.0))
        dyn.setVelocity(b, Point2D(3.0, 4.0))
        dyn.setVelocity(c, Point2D(1.0, 2.0))
        dyn.addForce(alignment(radius = 5.0))
        // avg neighbour velocity = (2, 3); minus own (1, 0) = (1, 3)
        assertPoint(1.0, 3.0, dyn.netForceOn(a), "alignment over two neighbours")
    }

    @Test
    @DisplayName("A3.1: cohesion is the mean offset toward neighbours")
    fun cohesionIsMeanOffsetTowardNeighbours() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        m.place("b", 2.0, 0.0)
        m.place("c", 0.0, 4.0)
        val dyn = unclamped(m)
        dyn.addForce(cohesion(radius = 10.0))
        // deltas (2,0) and (0,4); mean (1,2) — i.e. centroid minus own position.
        assertPoint(1.0, 2.0, dyn.netForceOn(a), "cohesion = centroid - position")
    }

    @Test
    @DisplayName("A3.1: separation is equal and opposite and falls off as 1/d")
    fun separationIsEqualAndOppositeWithInverseDistanceFalloff() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        val b = m.place("b", 3.0, 0.0)
        val dyn = unclamped(m)
        dyn.addForce(separation<AgentModel.Agent>(radius = 5.0))
        val fa = dyn.netForceOn(a)
        val fb = dyn.netForceOn(b)
        // |F| = 1/d = 1/3, directed away from the peer.
        assertPoint(-1.0 / 3.0, 0.0, fa, "separation on a points away from b")
        assertPoint(1.0 / 3.0, 0.0, fb, "separation on b points away from a")
        assertPoint(0.0, 0.0, Point2D(fa.x + fb.x, fa.y + fb.y), "forces must cancel pairwise")
    }

    @Test
    @DisplayName("A3.1: viscous drag opposes velocity linearly")
    fun viscousDragOpposesVelocity() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point2D(2.0, -3.0))
        dyn.addForce(viscousDrag(coefficient = 0.5))
        assertPoint(-1.0, 1.5, dyn.netForceOn(a), "F = -c * v")
    }

    @Test
    @DisplayName("A3.1: desiredVelocity relaxes toward the target and scales with mass")
    fun desiredVelocityRelaxesTowardTargetAndScalesWithMass() {
        for (mass in listOf(1.0, 2.0)) {
            val m = model()
            val a = m.place("a", 0.0, 0.0)
            val dyn = unclamped(m, mass = mass)
            dyn.setVelocity(a, Point2D(0.5, 0.0))
            dyn.addForce(
                desiredVelocity(speed = 2.0, tau = 0.5) { _, _ -> Point2D(1.0, 0.0) }
            )
            // F = (vDesired - vCurrent) * m / tau = ((2,0) - (0.5,0)) * mass / 0.5
            assertPoint(3.0 * mass, 0.0, dyn.netForceOn(a), "Helbing relaxation, mass=$mass")
        }
    }

    @Test
    @DisplayName("A3.1: weighted scales the underlying force")
    fun weightedScalesTheUnderlyingForce() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point2D(2.0, -3.0))
        dyn.addForce(weighted(viscousDrag(coefficient = 0.5), 2.0))
        assertPoint(-2.0, 3.0, dyn.netForceOn(a), "weighted doubles the drag")
    }

    // ── A3.2 — radius boundary and self-exclusion ────────────────────────────

    /**
     *  `ContinuousProjection.within` uses `d <= radius`, so a peer sitting exactly on
     *  the boundary counts as a neighbour. Pinned because `<` versus `<=` is exactly
     *  the sort of detail that drifts, and it must be consistent across factories.
     */
    @Test
    @DisplayName("A3.2: a peer exactly at the radius counts; just beyond does not")
    fun radiusBoundaryIsInclusive() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        val onBoundary = m.place("b", 4.0, 0.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point2D(0.0, 0.0))
        dyn.setVelocity(onBoundary, Point2D(0.0, 2.0))
        dyn.addForce(alignment(radius = 4.0))
        assertPoint(0.0, 2.0, dyn.netForceOn(a), "peer exactly at radius must be included")

        val m2 = model()
        val a2 = m2.place("a", 0.0, 0.0)
        val beyond = m2.place("b", 4.0001, 0.0)
        val dyn2 = unclamped(m2)
        dyn2.setVelocity(a2, Point2D(0.0, 0.0))
        dyn2.setVelocity(beyond, Point2D(0.0, 2.0))
        dyn2.addForce(alignment(radius = 4.0))
        assertPoint(0.0, 0.0, dyn2.netForceOn(a2), "peer beyond radius must be excluded")
    }

    @Test
    @DisplayName("A3.2: an agent with no peers feels no peer force from any factory")
    fun soleAgentFeelsNoPeerForces() {
        for (force in listOf(
            alignment<AgentModel.Agent>(radius = 10.0),
            cohesion<AgentModel.Agent>(radius = 10.0),
            separation<AgentModel.Agent>(radius = 10.0),
        )) {
            val m = model()
            val a = m.place("a", 5.0, 5.0)
            val dyn = unclamped(m)
            dyn.setVelocity(a, Point2D(1.0, 1.0))
            dyn.addForce(force)
            assertPoint(0.0, 0.0, dyn.netForceOn(a), "self must be excluded from its own neighbourhood")
        }
    }

    // ── A3.3 — torus-aware deltas ────────────────────────────────────────────

    /**
     *  On a torus of span 10, agents at x = 0.5 and x = 9.5 are 1 apart across the
     *  wrap, not 9 apart. Position-based forces must use the wrapped delta, so
     *  cohesion pulls `a` toward the boundary (negative x), not back across the world.
     */
    @Test
    @DisplayName("A3.3: cohesion uses the wrapped delta on a torus")
    fun cohesionUsesWrappedDeltaOnTorus() {
        val m = model(torus = true, size = 10.0)
        val a = m.place("a", 0.5, 5.0)
        m.place("b", 9.5, 5.0)
        val dyn = unclamped(m)
        dyn.addForce(cohesion(radius = 2.0))
        assertPoint(-1.0, 0.0, dyn.netForceOn(a), "cohesion should pull across the wrap")
    }

    @Test
    @DisplayName("A3.3: separation pushes across the wrap on a torus")
    fun separationPushesAcrossTheWrapOnTorus() {
        val m = model(torus = true, size = 10.0)
        val a = m.place("a", 0.5, 5.0)
        m.place("b", 9.5, 5.0)
        val dyn = unclamped(m)
        dyn.addForce(separation<AgentModel.Agent>(radius = 2.0))
        // wrapped separation is 1.0, so |F| = 1/1 = 1, directed away from b (+x).
        assertPoint(1.0, 0.0, dyn.netForceOn(a), "separation should repel across the wrap")
    }

    /**
     *  Alignment is computed from velocities alone, so wrapping must not touch it.
     *  The same velocity configuration gives the same force on a torus and off it.
     */
    @Test
    @DisplayName("A3.3: alignment is unaffected by torus geometry")
    fun alignmentIsUnaffectedByTorusGeometry() {
        val results = listOf(true, false).map { torus ->
            val m = model(torus = torus, size = 10.0)
            val a = m.place("a", 5.0, 5.0)
            val b = m.place("b", 6.0, 5.0)
            val dyn = unclamped(m)
            dyn.setVelocity(a, Point2D(1.0, 0.0))
            dyn.setVelocity(b, Point2D(3.0, 4.0))
            dyn.addForce(alignment(radius = 2.0))
            dyn.netForceOn(a)
        }
        assertPoint(2.0, 4.0, results[0], "alignment on a torus")
        assertPoint(2.0, 4.0, results[1], "alignment off a torus")
    }

    // ── A3.4 — integrator characterization ───────────────────────────────────

    /**
     *  Semi-implicit Euler: `v1 = v0 + (F/m)·dt`, then `p1 = p0 + v1·dt` using the
     *  **new** velocity. Plain explicit Euler would advance position by `v0·dt` and
     *  leave a stationary agent at the origin, so this configuration distinguishes
     *  the two decisively.
     */
    @Test
    @DisplayName("A3.4: step advances position with the new velocity (semi-implicit Euler)")
    fun stepUsesSemiImplicitEuler() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point2D(0.0, 0.0))
        dyn.addForce(constantForce(Point2D(2.0, 0.0)))
        val (vNew, pNew) = dyn.step(a, dt = 0.5)
        assertPoint(1.0, 0.0, vNew, "v1 = v0 + (F/m)*dt")
        assertPoint(0.5, 0.0, pNew, "p1 must use v1, not v0 (explicit Euler would give 0)")
    }

    @Test
    @DisplayName("A3.4: the speed clamp is applied before the position update")
    fun speedClampAppliedBeforePositionUpdate() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        val dyn = Dynamics(m.space, mass = { 1.0 }, maxSpeed = 0.5, minSpeed = 0.0)
        dyn.setVelocity(a, Point2D(0.0, 0.0))
        dyn.addForce(constantForce(Point2D(2.0, 0.0)))
        val (vNew, pNew) = dyn.step(a, dt = 0.5)
        assertPoint(0.5, 0.0, vNew, "velocity clamped to maxSpeed")
        assertPoint(0.25, 0.0, pNew, "position must advance by the clamped velocity")
    }

    /**
     *  A stationary agent under a positive `minSpeed` has no direction to preserve, so
     *  the clamp substitutes a deterministic jitter direction at exactly `minSpeed`
     *  rather than leaving the agent frozen.
     */
    @Test
    @DisplayName("A3.4: minSpeed rescues a stationary agent at exactly minSpeed")
    fun minSpeedRescuesStationaryAgent() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        val dyn = Dynamics(m.space, mass = { 1.0 }, maxSpeed = 5.0, minSpeed = 1.0)
        dyn.setVelocity(a, Point2D(0.0, 0.0))
        val (vNew, _) = dyn.step(a, dt = 0.5)
        assertEquals(1.0, vNew.magnitude, 1e-9, "a frozen agent is restarted at minSpeed")
    }

    @Test
    @DisplayName("A3.4: stepAll is order-independent where per-agent step is not")
    fun stepAllIsIndependentOfIterationOrder() {
        val m = model()
        val a = m.place("a", 0.0, 0.0)
        val b = m.place("b", 3.0, 0.0)
        val dyn = unclamped(m)
        dyn.addForce(separation<AgentModel.Agent>(radius = 10.0))

        val forward = dyn.stepAll(listOf(a, b), dt = 0.1).associate { it.first to it.second }
        val reversed = dyn.stepAll(listOf(b, a), dt = 0.1).associate { it.first to it.second }
        assertPoint(forward.getValue(a).x, forward.getValue(a).y, reversed.getValue(a), "a")
        assertPoint(forward.getValue(b).x, forward.getValue(b).y, reversed.getValue(b), "b")
        assertTrue(forward.getValue(a).x < 0.0, "a should be pushed away from b")
    }
}
