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
 *  The 3D counterpart of `ForceAnalyticTest`.
 *
 *  `Forces3D` mirrors `Forces` factory for factory, and a 2D → 3D port is exactly
 *  where a transcription slip hides: a dropped `z` term in one branch, an axis
 *  copied twice, a magnitude computed over two components instead of three. None of
 *  that shows up in a coverage sweep, and little of it shows up in behavioral tests
 *  either — a flock with a broken z term still flocks, just flatly.
 *
 *  So every case here is chosen with all three components distinct and non-zero,
 *  which is what makes a dropped or duplicated axis fail loudly.
 */
class ForceAnalytic3DTest {

    private class VolumeModel(
        parent: ModelElement,
        torus: Boolean = false,
        size: Double = 100.0,
    ) : AgentModel(parent, "volumeModel") {
        val ctx: Context<Agent> = Context("agents")
        val space: ContinuousVolume<Agent> =
            ContinuousVolume(ctx, 0.0..size, 0.0..size, 0.0..size, torus = torus)
        inner class P(aName: String) : Agent(aName)
    }

    private fun model(torus: Boolean = false, size: Double = 100.0): VolumeModel =
        VolumeModel(Model("ForceAnalytic3D"), torus, size)

    private fun VolumeModel.place(name: String, x: Double, y: Double, z: Double): AgentModel.Agent {
        val a = P(name)
        ctx.add(a)
        space.placeAt(a, Point3D(x, y, z))
        return a
    }

    private fun assertPoint(x: Double, y: Double, z: Double, actual: Point3D, message: String) {
        assertEquals(x, actual.x, 1e-9, "$message (x)")
        assertEquals(y, actual.y, 1e-9, "$message (y)")
        assertEquals(z, actual.z, 1e-9, "$message (z)")
    }

    private fun unclamped(m: VolumeModel, mass: Double = 1.0): Dynamics3D<AgentModel.Agent> =
        Dynamics3D(m.space, mass = { mass }, maxSpeed = 1.0e6, minSpeed = 0.0)

    /**
     *  Evaluate one force in isolation, without registering it on the dynamics.
     *
     *  Kept distinct from `netForceOn`, which sums *all* registered forces: these
     *  cases assert the closed form of a single factory, so evaluating it directly
     *  keeps the assertion about that factory alone.
     */
    private fun evaluate(
        f: Force3D<AgentModel.Agent>,
        agent: AgentModel.Agent,
        dyn: Dynamics3D<AgentModel.Agent>,
        dt: Double = 1.0,
    ): Point3D = f.compute(agent, dyn, dt)

    // ── Exact force values, all three axes distinct ──────────────────────────

    @Test
    @DisplayName("A3.1/3D: alignment3D is exactly the velocity difference on all axes")
    fun alignment3DIsVelocityDifference() {
        val m = model()
        val a = m.place("a", 0.0, 0.0, 0.0)
        val b = m.place("b", 1.0, 0.0, 0.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point3D(1.0, 2.0, 3.0))
        dyn.setVelocity(b, Point3D(4.0, 6.0, 9.0))
        assertPoint(
            3.0, 4.0, 6.0,
            evaluate(alignment3D(radius = 5.0), a, dyn),
            "alignment3D = neighbour v - own v",
        )
    }

    @Test
    @DisplayName("A3.1/3D: cohesion3D is the mean offset toward neighbours")
    fun cohesion3DIsMeanOffset() {
        val m = model()
        val a = m.place("a", 0.0, 0.0, 0.0)
        m.place("b", 2.0, 0.0, 6.0)
        m.place("c", 0.0, 4.0, 2.0)
        val dyn = unclamped(m)
        // deltas (2,0,6) and (0,4,2); mean (1,2,4)
        assertPoint(
            1.0, 2.0, 4.0,
            evaluate(cohesion3D(radius = 20.0), a, dyn),
            "cohesion3D = centroid - position",
        )
    }

    @Test
    @DisplayName("A3.1/3D: separation3D is equal and opposite along a 3-4-12 offset")
    fun separation3DIsEqualAndOpposite() {
        val m = model()
        // (3,4,12) has magnitude exactly 13, so the unit vector is exact in binary.
        val a = m.place("a", 0.0, 0.0, 0.0)
        val b = m.place("b", 3.0, 4.0, 12.0)
        val dyn = unclamped(m)
        val sep = separation3D<AgentModel.Agent>(radius = 20.0)
        val fa = evaluate(sep, a, dyn)
        val fb = evaluate(sep, b, dyn)
        // |F| = 1/d = 1/13, directed away from the peer.
        val mag = 1.0 / 13.0
        assertPoint(-3.0 / 13.0 * mag, -4.0 / 13.0 * mag, -12.0 / 13.0 * mag, fa, "separation3D on a")
        assertPoint(3.0 / 13.0 * mag, 4.0 / 13.0 * mag, 12.0 / 13.0 * mag, fb, "separation3D on b")
        assertEquals(mag, fa.magnitude, 1e-9, "magnitude must be 1/d over all three axes")
    }

    @Test
    @DisplayName("A3.1/3D: viscousDrag3D opposes velocity on every axis")
    fun viscousDrag3DOpposesVelocity() {
        val m = model()
        val a = m.place("a", 0.0, 0.0, 0.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point3D(2.0, -3.0, 5.0))
        assertPoint(
            -1.0, 1.5, -2.5,
            evaluate(viscousDrag3D(coefficient = 0.5), a, dyn),
            "F = -c * v",
        )
    }

    @Test
    @DisplayName("A3.1/3D: desiredVelocity3D relaxes toward the target and scales with mass")
    fun desiredVelocity3DRelaxesTowardTarget() {
        val m = model()
        val a = m.place("a", 0.0, 0.0, 0.0)
        val dyn = unclamped(m, mass = 2.0)
        dyn.setVelocity(a, Point3D(0.0, 0.0, 0.0))
        // Unit direction along z only, so a leaked x or y term shows up as non-zero.
        val f = desiredVelocity3D<AgentModel.Agent>(speed = 3.0, tau = 1.5) { _, _ ->
            Point3D(0.0, 0.0, 1.0)
        }
        // F = (vDesired - vCurrent) * m / tau = (0,0,3) * 2 / 1.5 = (0,0,4)
        assertPoint(0.0, 0.0, 4.0, evaluate(f, a, dyn), "Helbing relaxation in 3D")
    }

    @Test
    @DisplayName("A3.1/3D: an agent with no peers feels no peer force from any 3D factory")
    fun soleAgent3DFeelsNoPeerForces() {
        for (f in listOf(
            alignment3D<AgentModel.Agent>(radius = 10.0),
            cohesion3D<AgentModel.Agent>(radius = 10.0),
            separation3D<AgentModel.Agent>(radius = 10.0),
        )) {
            val m = model()
            val a = m.place("a", 5.0, 5.0, 5.0)
            val dyn = unclamped(m)
            dyn.setVelocity(a, Point3D(1.0, 1.0, 1.0))
            assertPoint(0.0, 0.0, 0.0, evaluate(f, a, dyn), "self must be excluded in 3D too")
        }
    }

    // ── Integrator ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("A3.4/3D: step advances position with the new velocity on every axis")
    fun step3DUsesSemiImplicitEuler() {
        val m = model()
        val a = m.place("a", 0.0, 0.0, 0.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point3D(0.0, 0.0, 0.0))
        dyn.addForce(constantForce3D(Point3D(2.0, 4.0, 8.0)))
        val (vNew, pNew) = dyn.step(a, dt = 0.5)
        assertPoint(1.0, 2.0, 4.0, vNew, "v1 = v0 + (F/m)*dt")
        assertPoint(0.5, 1.0, 2.0, pNew, "p1 must use v1, not v0")
    }

    /**
     *  Wrapping must apply independently per axis. Here the x offset wraps (span 10,
     *  so 0.5 and 9.5 are 1 apart) while y and z do not, which catches a wrap applied
     *  to the wrong axis or uniformly to all three.
     */
    @Test
    @DisplayName("A3.3/3D: torus wrapping is per-axis")
    fun torusWrappingIsPerAxis() {
        val m = model(torus = true, size = 10.0)
        val a = m.place("a", 0.5, 5.0, 5.0)
        m.place("b", 9.5, 6.0, 7.0)
        val dyn = unclamped(m)
        // x wraps to -1.0; y and z are plain differences of +1 and +2.
        assertPoint(
            -1.0, 1.0, 2.0,
            evaluate(cohesion3D(radius = 4.0), a, dyn),
            "only the x axis should wrap",
        )
    }

    // ── D9 — 2D/3D parity for the overlay surface (finding H-11) ─────────────

    @Test
    @DisplayName("D9: netForceOn sums every registered force on all three axes")
    fun netForceOn3DSumsRegisteredForces() {
        val m = model()
        val a = m.place("a", 0.0, 0.0, 0.0)
        val dyn = unclamped(m)
        dyn.addForce(constantForce3D(Point3D(1.0, 2.0, 3.0)))
        dyn.addForce(constantForce3D(Point3D(0.5, -1.0, 4.0)))
        assertPoint(1.5, 1.0, 7.0, dyn.netForceOn(a), "netForceOn must sum all forces")
    }

    @Test
    @DisplayName("D9: netForceOn returns the origin for an agent with no position")
    fun netForceOn3DIsOriginForUnplacedAgent() {
        val m = model()
        val stray = m.P("stray")
        m.ctx.add(stray)
        val dyn = unclamped(m)
        dyn.addForce(constantForce3D(Point3D(1.0, 1.0, 1.0)))
        assertPoint(0.0, 0.0, 0.0, dyn.netForceOn(stray), "an unplaced agent has no force")
    }

    /**
     *  The overlay event `AgentVectorSampled` carries no z channel and the renderer
     *  draws in 2D, so 3D samples are deliberately flattened to x–y — the same
     *  convention `ContinuousVolume.placeAt` already uses for positions. This asserts
     *  the flattening is exactly a projection: x and y survive untouched, z is
     *  dropped rather than folded into another axis.
     */
    @Test
    @DisplayName("D9: overlaySample projects 3D velocity and force onto x-y")
    fun overlaySample3DFlattensToXY() {
        val m = model()
        val a = m.place("a", 0.0, 0.0, 0.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point3D(1.0, 2.0, 99.0))
        dyn.addForce(constantForce3D(Point3D(3.0, 4.0, 99.0)))

        val sample = dyn.overlaySample(wantVelocity = true, wantForce = true).single()
        assertEquals("a", sample.name)
        assertEquals(1.0, sample.vx, 1e-9)
        assertEquals(2.0, sample.vy, 1e-9)
        assertEquals(3.0, sample.fx, 1e-9)
        assertEquals(4.0, sample.fy, 1e-9)
    }

    @Test
    @DisplayName("D9: overlaySample marks uncaptured channels as NaN")
    fun overlaySample3DMarksUncapturedChannelsNaN() {
        val m = model()
        val a = m.place("a", 0.0, 0.0, 0.0)
        val dyn = unclamped(m)
        dyn.setVelocity(a, Point3D(1.0, 2.0, 3.0))
        dyn.addForce(constantForce3D(Point3D(3.0, 4.0, 5.0)))

        val velocityOnly = dyn.overlaySample(wantVelocity = true, wantForce = false).single()
        assertEquals(1.0, velocityOnly.vx, 1e-9)
        assertTrue(velocityOnly.fx.isNaN(), "force must be NaN when not requested")

        val forceOnly = dyn.overlaySample(wantVelocity = false, wantForce = true).single()
        assertTrue(forceOnly.vx.isNaN(), "velocity must be NaN when not requested")
        assertEquals(3.0, forceOnly.fx, 1e-9)
    }

    /**
     *  Before D9 a 3D model could not register its dynamics for the overlay at all —
     *  `attachDynamics` accepted only the 2D type — so the velocity/force overlay was
     *  inert for every `ContinuousVolume` model, `DroneDeliveryExample` included.
     */
    @Test
    @DisplayName("D9: a 3D dynamics can be attached for the overlay and is keyed by space")
    fun attachDynamics3DRegistersForOverlay() {
        val m = model()
        val dyn = unclamped(m)
        assertTrue(m.ctx.dynamics3DLinks.isEmpty(), "nothing attached yet")

        m.ctx.attachDynamics(dyn)
        assertEquals(listOf(dyn), m.ctx.dynamics3DLinks.toList())

        // Re-declaring the same space overwrites rather than accumulating, matching
        // the 2D overload, so per-replication initialize() calls stay idempotent.
        m.ctx.attachDynamics(dyn)
        assertEquals(1, m.ctx.dynamics3DLinks.size, "re-attach must overwrite, not accumulate")
    }
}
