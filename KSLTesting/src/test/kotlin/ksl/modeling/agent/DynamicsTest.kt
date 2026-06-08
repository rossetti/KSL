/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Phase 5.4 tests for [Dynamics], [Force], the canonical [Forces.kt]
 *  factories, and the torus-aware delta added to [ContinuousProjection].
 */
class DynamicsTest {

    // ── Test fixture: a tiny AgentModel with a ContinuousProjection. ──────

    private open class DyModel(parent: ModelElement, torus: Boolean = false) :
        AgentModel(parent, "dymodel") {
        val ctx: Context<Agent> = Context("agents")
        val space: ContinuousProjection<Agent> = ContinuousProjection(
            ctx,
            xRange = 0.0..100.0,
            yRange = 0.0..100.0,
            torus = torus,
        )
    }

    // ── ContinuousProjection.delta torus-aware ─────────────────────────────

    @Test
    fun deltaIsTrivialForNonTorus() {
        val m = Model("delta-nontorus")
        val tm = DyModel(m, torus = false)
        val delta = tm.space.delta(Point2D(10.0, 20.0), Point2D(30.0, 25.0))
        assertEquals(20.0, delta.x, 1e-9)
        assertEquals(5.0, delta.y, 1e-9)
    }

    @Test
    fun deltaWrapsShortDirectionOnTorus() {
        val m = Model("delta-torus")
        val tm = DyModel(m, torus = true)
        // World is 0..100. From 99 to 1: the short way is +2 (wrapping), not -98.
        val short = tm.space.delta(Point2D(99.0, 50.0), Point2D(1.0, 50.0))
        assertEquals(2.0, short.x, 1e-9)
        assertEquals(0.0, short.y, 1e-9)
        // From 1 to 99: short way is -2.
        val rev = tm.space.delta(Point2D(1.0, 50.0), Point2D(99.0, 50.0))
        assertEquals(-2.0, rev.x, 1e-9)
        // Long distances stay short — picks whichever of d or (span - d) is smaller.
        val mid = tm.space.delta(Point2D(20.0, 0.0), Point2D(70.0, 0.0))
        assertEquals(50.0, mid.x, 1e-9)
    }

    // ── Dynamics.step: Euler integration + force sum ──────────────────────

    @Test
    fun dynamicsStepIntegratesConstantForce() {
        val m = Model("step-const")
        val tm = DyModel(m)
        val a: AgentModel.Agent = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point2D(50.0, 50.0))

        val dyn = Dynamics<AgentModel.Agent>(tm.space, mass = { 2.0 }, maxSpeed = 100.0)
        dyn.setVelocity(a, Point2D(0.0, 0.0))
        // Constant force +10 in x direction → acceleration 5, vNew after dt=0.1: 0.5
        dyn.addForce(constantForce(Point2D(10.0, 0.0)))

        val (vNew, candidate) = dyn.step(a, 0.1)
        assertEquals(0.5, vNew.x, 1e-9)
        assertEquals(0.0, vNew.y, 1e-9)
        // Candidate = pos + vNew * dt = (50 + 0.05, 50)
        assertEquals(50.05, candidate.x, 1e-9)
    }

    @Test
    fun dynamicsClampsToMaxSpeed() {
        val m = Model("clamp-max")
        val tm = DyModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point2D(0.0, 0.0))

        val dyn = Dynamics<AgentModel.Agent>(tm.space, mass = { 1.0 }, maxSpeed = 2.0)
        dyn.setVelocity(a, Point2D(0.0, 0.0))
        dyn.addForce(constantForce(Point2D(1000.0, 0.0)))   // would-be huge

        val (vNew, _) = dyn.step(a, 0.1)
        // Pre-clamp velocity would be 100.0 in x; clamped to maxSpeed = 2.0.
        assertEquals(2.0, vNew.magnitude, 1e-9)
    }

    @Test
    fun dynamicsRescalesBelowMinSpeed() {
        val m = Model("clamp-min")
        val tm = DyModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point2D(0.0, 0.0))

        val dyn = Dynamics<AgentModel.Agent>(
            tm.space, mass = { 1.0 }, maxSpeed = 5.0, minSpeed = 1.0,
        )
        dyn.setVelocity(a, Point2D(0.5, 0.0))   // below minSpeed
        // No forces; velocity stays small, then gets rescaled to minSpeed.
        val (vNew, _) = dyn.step(a, 0.1)
        assertEquals(1.0, vNew.magnitude, 1e-9)
    }

    @Test
    fun dynamicsSetAndUntrackVelocity() {
        val m = Model("track")
        val tm = DyModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point2D(0.0, 0.0))

        val dyn = Dynamics<AgentModel.Agent>(tm.space)
        assertEquals(null, dyn.velocityOf(a))
        dyn.setVelocity(a, Point2D(3.0, 4.0))
        assertEquals(Point2D(3.0, 4.0), dyn.velocityOf(a))
        dyn.untrack(a)
        assertEquals(null, dyn.velocityOf(a))
    }

    // ── Dynamics validation ────────────────────────────────────────────────

    @Test
    fun dynamicsRejectsInvalidSpeedDefaults() {
        val m = Model("validate")
        val tm = DyModel(m)
        assertThrows<IllegalArgumentException> {
            Dynamics<AgentModel.Agent>(tm.space, maxSpeed = -1.0)
        }
        assertThrows<IllegalArgumentException> {
            Dynamics<AgentModel.Agent>(tm.space, minSpeed = -0.1)
        }
    }

    @Test
    fun dynamicsStepRequiresPositiveDt() {
        val m = Model("dt-pos")
        val tm = DyModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point2D(0.0, 0.0))
        val dyn = Dynamics<AgentModel.Agent>(tm.space)
        assertThrows<IllegalArgumentException> { dyn.step(a, 0.0) }
        assertThrows<IllegalArgumentException> { dyn.step(a, -0.1) }
    }

    // ── Force-factory unit tests ───────────────────────────────────────────

    @Test
    fun separationPushesAwayFromCloseNeighbor() {
        val m = Model("sep")
        val tm = DyModel(m)
        val me = tm.Agent("me"); val other = tm.Agent("other")
        tm.ctx.add(me); tm.ctx.add(other)
        tm.space.placeAt(me, Point2D(50.0, 50.0))
        tm.space.placeAt(other, Point2D(51.0, 50.0))   // 1.0 unit to my east

        val dyn = Dynamics<AgentModel.Agent>(tm.space)
        val force = separation<AgentModel.Agent>(radius = 5.0)
        val f = force.compute(me, dyn, dt = 0.05)
        // Should push me west (negative x direction).
        assertTrue(f.x < 0.0, "expected separation to push me west; force was $f")
        assertEquals(0.0, f.y, 1e-9)
    }

    @Test
    fun alignmentMatchesNeighborVelocity() {
        val m = Model("align")
        val tm = DyModel(m)
        val me = tm.Agent("me"); val other = tm.Agent("other")
        tm.ctx.add(me); tm.ctx.add(other)
        tm.space.placeAt(me, Point2D(50.0, 50.0))
        tm.space.placeAt(other, Point2D(52.0, 50.0))

        val dyn = Dynamics<AgentModel.Agent>(tm.space)
        dyn.setVelocity(me, Point2D(0.0, 0.0))
        dyn.setVelocity(other, Point2D(1.0, 0.0))    // neighbor flying east

        val force = alignment<AgentModel.Agent>(radius = 5.0)
        val f = force.compute(me, dyn, dt = 0.05)
        // Force is avg(neighbors) - myVel = (1, 0) - (0, 0).
        assertEquals(1.0, f.x, 1e-9)
        assertEquals(0.0, f.y, 1e-9)
    }

    @Test
    fun cohesionUsesTorusAwareDeltas() {
        val m = Model("coh-torus")
        val tm = DyModel(m, torus = true)
        val me = tm.Agent("me"); val other = tm.Agent("other")
        tm.ctx.add(me); tm.ctx.add(other)
        tm.space.placeAt(me, Point2D(99.0, 50.0))     // near east edge
        tm.space.placeAt(other, Point2D(1.0, 50.0))   // near west edge — across the wrap

        val dyn = Dynamics<AgentModel.Agent>(tm.space)
        val force = cohesion<AgentModel.Agent>(radius = 10.0)
        val f = force.compute(me, dyn, dt = 0.05)
        // Delta from me to other is +2 east (the short way across the wrap),
        // so cohesion force points east (+x).
        assertTrue(f.x > 0.0, "cohesion should pull me east across the wrap; force was $f")
        assertEquals(2.0, abs(f.x), 1e-9)
    }

    @Test
    fun peerRepulsionUsesCustomFalloff() {
        val m = Model("peer")
        val tm = DyModel(m)
        val me = tm.Agent("me"); val other = tm.Agent("other")
        tm.ctx.add(me); tm.ctx.add(other)
        tm.space.placeAt(me, Point2D(50.0, 50.0))
        tm.space.placeAt(other, Point2D(52.0, 50.0))  // 2.0 east

        val dyn = Dynamics<AgentModel.Agent>(tm.space)
        // Constant-magnitude falloff: every peer contributes a unit force.
        val force = peerRepulsion<AgentModel.Agent>(radius = 5.0) { _ -> 1.0 }
        val f = force.compute(me, dyn, dt = 0.05)
        // Unit vector me→away points -x; magnitude = 1.0.
        assertEquals(-1.0, f.x, 1e-9)
        assertEquals(0.0, f.y, 1e-9)
    }

    @Test
    fun viscousDragOpposesVelocity() {
        val m = Model("drag")
        val tm = DyModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point2D(0.0, 0.0))

        val dyn = Dynamics<AgentModel.Agent>(tm.space)
        dyn.setVelocity(a, Point2D(3.0, 4.0))
        val drag = viscousDrag<AgentModel.Agent>(coefficient = 2.0)
        val f = drag.compute(a, dyn, dt = 0.05)
        // F = -2 * v = (-6, -8)
        assertEquals(-6.0, f.x, 1e-9)
        assertEquals(-8.0, f.y, 1e-9)
    }

    @Test
    fun weightedScalesAnotherForce() {
        val m = Model("weight")
        val tm = DyModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point2D(0.0, 0.0))

        val dyn = Dynamics<AgentModel.Agent>(tm.space)
        val doubled = weighted<AgentModel.Agent>(constantForce(Point2D(2.0, 3.0)), weight = 2.5)
        val f = doubled.compute(a, dyn, dt = 0.05)
        assertEquals(5.0, f.x, 1e-9)
        assertEquals(7.5, f.y, 1e-9)
    }

    // ── Factory parameter validation ───────────────────────────────────────

    @Test
    fun forceFactoriesValidateInputs() {
        assertThrows<IllegalArgumentException> { peerRepulsion<AgentModel.Agent>(radius = 0.0) { 1.0 } }
        assertThrows<IllegalArgumentException> {
            peerRepulsion<AgentModel.Agent>(radius = 1.0, minDistance = -0.1) { 1.0 }
        }
        assertThrows<IllegalArgumentException> { alignment<AgentModel.Agent>(radius = -1.0) }
        assertThrows<IllegalArgumentException> { cohesion<AgentModel.Agent>(radius = 0.0) }
        assertThrows<IllegalArgumentException> { viscousDrag<AgentModel.Agent>(coefficient = -1.0) }
    }
}
