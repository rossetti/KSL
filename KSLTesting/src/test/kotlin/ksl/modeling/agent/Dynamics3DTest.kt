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
 *  Phase 6.5 tests for [Dynamics3D], [Force3D], and the canonical
 *  [Forces3D.kt] factories. Mirrors [DynamicsTest] for the 3D case.
 */
class Dynamics3DTest {

    private open class DyVolModel(
        parent: ModelElement,
        torus: Boolean = false,
    ) : AgentModel(parent, "dy3d") {
        val ctx: Context<Agent> = Context("agents")
        val space: ContinuousVolume<Agent> = ContinuousVolume(
            ctx,
            xRange = 0.0..100.0,
            yRange = 0.0..100.0,
            zRange = 0.0..50.0,
            torus = torus,
        )
    }

    // ── Dynamics3D.step: Euler integration + force sum ─────────────────────

    @Test
    fun dynamics3DStepIntegratesConstantForce() {
        val m = Model("step3d-const")
        val tm = DyVolModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point3D(50.0, 50.0, 25.0))

        val dyn = Dynamics3D<AgentModel.Agent>(tm.space, mass = { 2.0 }, maxSpeed = 100.0)
        dyn.setVelocity(a, Point3D.ORIGIN)
        // Force (10, 0, 6); mass 2 → acceleration (5, 0, 3); dt=0.1 → vNew (0.5, 0, 0.3)
        dyn.addForce(constantForce3D(Point3D(10.0, 0.0, 6.0)))

        val (vNew, candidate) = dyn.step(a, 0.1)
        assertEquals(0.5, vNew.x, 1e-9)
        assertEquals(0.0, vNew.y, 1e-9)
        assertEquals(0.3, vNew.z, 1e-9)
        // candidate = pos + vNew * dt
        assertEquals(50.05, candidate.x, 1e-9)
        assertEquals(50.0, candidate.y, 1e-9)
        assertEquals(25.03, candidate.z, 1e-9)
    }

    @Test
    fun dynamics3DClampsToMaxSpeed() {
        val m = Model("clamp3d-max")
        val tm = DyVolModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point3D.ORIGIN)
        val dyn = Dynamics3D<AgentModel.Agent>(tm.space, mass = { 1.0 }, maxSpeed = 2.0)
        dyn.setVelocity(a, Point3D.ORIGIN)
        dyn.addForce(constantForce3D(Point3D(1000.0, 1000.0, 1000.0)))
        val (vNew, _) = dyn.step(a, 0.1)
        assertEquals(2.0, vNew.magnitude, 1e-9)
    }

    @Test
    fun dynamics3DRescalesBelowMinSpeed() {
        val m = Model("clamp3d-min")
        val tm = DyVolModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point3D.ORIGIN)
        val dyn = Dynamics3D<AgentModel.Agent>(
            tm.space, mass = { 1.0 }, maxSpeed = 5.0, minSpeed = 1.0,
        )
        dyn.setVelocity(a, Point3D(0.3, 0.4, 0.0))   // |v| = 0.5, below minSpeed
        val (vNew, _) = dyn.step(a, 0.1)
        assertEquals(1.0, vNew.magnitude, 1e-9)
    }

    @Test
    fun dynamics3DSetAndUntrackVelocity() {
        val m = Model("track3d")
        val tm = DyVolModel(m)
        val a = tm.Agent("a"); tm.ctx.add(a); tm.space.placeAt(a, Point3D.ORIGIN)

        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        assertEquals(null, dyn.velocityOf(a))
        dyn.setVelocity(a, Point3D(3.0, 4.0, 12.0))
        assertEquals(Point3D(3.0, 4.0, 12.0), dyn.velocityOf(a))
        dyn.untrack(a)
        assertEquals(null, dyn.velocityOf(a))
    }

    // ── Validation ─────────────────────────────────────────────────────────

    @Test
    fun dynamics3DRejectsInvalidSpeedDefaults() {
        val m = Model("validate3d")
        val tm = DyVolModel(m)
        assertThrows<IllegalArgumentException> {
            Dynamics3D<AgentModel.Agent>(tm.space, maxSpeed = -1.0)
        }
        assertThrows<IllegalArgumentException> {
            Dynamics3D<AgentModel.Agent>(tm.space, minSpeed = -0.1)
        }
    }

    @Test
    fun dynamics3DStepRequiresPositiveDt() {
        val m = Model("dt3d")
        val tm = DyVolModel(m)
        val a = tm.Agent("a"); tm.ctx.add(a); tm.space.placeAt(a, Point3D.ORIGIN)
        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        assertThrows<IllegalArgumentException> { dyn.step(a, 0.0) }
        assertThrows<IllegalArgumentException> { dyn.step(a, -0.1) }
    }

    // ── Force-factory behavior ─────────────────────────────────────────────

    @Test
    fun separation3DPushesAwayInAllAxes() {
        val m = Model("sep3d")
        val tm = DyVolModel(m)
        val me = tm.Agent("me"); val other = tm.Agent("other")
        tm.ctx.add(me); tm.ctx.add(other)
        tm.space.placeAt(me, Point3D(50.0, 50.0, 25.0))
        // Other is 1 unit east AND 1 unit up.
        tm.space.placeAt(other, Point3D(51.0, 50.0, 26.0))

        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        val force = separation3D<AgentModel.Agent>(radius = 5.0)
        val f = force.compute(me, dyn, dt = 0.05)
        // Should push me west and down (negative x and z).
        assertTrue(f.x < 0.0, "expected separation west; force was $f")
        assertEquals(0.0, f.y, 1e-9)
        assertTrue(f.z < 0.0, "expected separation down; force was $f")
    }

    @Test
    fun alignment3DMatchesNeighborVelocity() {
        val m = Model("align3d")
        val tm = DyVolModel(m)
        val me = tm.Agent("me"); val other = tm.Agent("other")
        tm.ctx.add(me); tm.ctx.add(other)
        tm.space.placeAt(me, Point3D(50.0, 50.0, 25.0))
        tm.space.placeAt(other, Point3D(52.0, 50.0, 25.0))

        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        dyn.setVelocity(me, Point3D.ORIGIN)
        // Neighbor flying east-and-up.
        dyn.setVelocity(other, Point3D(1.0, 0.0, 0.5))

        val force = alignment3D<AgentModel.Agent>(radius = 5.0)
        val f = force.compute(me, dyn, dt = 0.05)
        // avg(neighbors) - myVel = (1.0, 0.0, 0.5).
        assertEquals(1.0, f.x, 1e-9)
        assertEquals(0.0, f.y, 1e-9)
        assertEquals(0.5, f.z, 1e-9)
    }

    @Test
    fun cohesion3DUsesTorusAwareDeltas() {
        val m = Model("coh3d-torus")
        val tm = DyVolModel(m, torus = true)
        val me = tm.Agent("me"); val other = tm.Agent("other")
        tm.ctx.add(me); tm.ctx.add(other)
        // Me near east edge; peer near west edge (across the x wrap)
        // AND near top of z; the cohesion should pull me +x (short
        // way) and +z (short way, since z wraps at 50).
        tm.space.placeAt(me, Point3D(99.0, 50.0, 49.0))
        tm.space.placeAt(other, Point3D(1.0, 50.0, 1.0))

        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        val force = cohesion3D<AgentModel.Agent>(radius = 10.0)
        val f = force.compute(me, dyn, dt = 0.05)
        // Short-way deltas: x = +2, z = +2 (since 50 - 49 + 1 = 2).
        assertTrue(f.x > 0.0, "cohesion should pull me +x across wrap; force was $f")
        assertEquals(2.0, abs(f.x), 1e-9)
        assertTrue(f.z > 0.0, "cohesion should pull me +z across wrap; force was $f")
        assertEquals(2.0, abs(f.z), 1e-9)
    }

    @Test
    fun viscousDrag3DOpposesVelocity() {
        val m = Model("drag3d")
        val tm = DyVolModel(m)
        val a = tm.Agent("a"); tm.ctx.add(a); tm.space.placeAt(a, Point3D.ORIGIN)
        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        dyn.setVelocity(a, Point3D(3.0, 4.0, 12.0))
        val drag = viscousDrag3D<AgentModel.Agent>(coefficient = 2.0)
        val f = drag.compute(a, dyn, dt = 0.05)
        assertEquals(-6.0, f.x, 1e-9)
        assertEquals(-8.0, f.y, 1e-9)
        assertEquals(-24.0, f.z, 1e-9)
    }

    @Test
    fun weighted3DScalesAnotherForce() {
        val m = Model("weight3d")
        val tm = DyVolModel(m)
        val a = tm.Agent("a"); tm.ctx.add(a); tm.space.placeAt(a, Point3D.ORIGIN)
        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        val doubled = weighted3D<AgentModel.Agent>(
            constantForce3D(Point3D(2.0, 3.0, 5.0)),
            weight = 2.5,
        )
        val f = doubled.compute(a, dyn, dt = 0.05)
        assertEquals(5.0, f.x, 1e-9)
        assertEquals(7.5, f.y, 1e-9)
        assertEquals(12.5, f.z, 1e-9)
    }

    @Test
    fun peerRepulsion3DUsesCustomFalloff() {
        val m = Model("peer3d")
        val tm = DyVolModel(m)
        val me = tm.Agent("me"); val other = tm.Agent("other")
        tm.ctx.add(me); tm.ctx.add(other)
        tm.space.placeAt(me, Point3D(50.0, 50.0, 25.0))
        // 2 units to my east.
        tm.space.placeAt(other, Point3D(52.0, 50.0, 25.0))

        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        // Constant-magnitude falloff: every peer contributes a unit force.
        val force = peerRepulsion3D<AgentModel.Agent>(radius = 5.0) { _ -> 1.0 }
        val f = force.compute(me, dyn, dt = 0.05)
        // Unit vector me→away points -x; magnitude = 1.0.
        assertEquals(-1.0, f.x, 1e-9)
        assertEquals(0.0, f.y, 1e-9)
        assertEquals(0.0, f.z, 1e-9)
    }

    @Test
    fun desiredVelocity3DRelaxesTowardTarget() {
        val m = Model("desired3d")
        val tm = DyVolModel(m)
        val a = tm.Agent("a"); tm.ctx.add(a); tm.space.placeAt(a, Point3D.ORIGIN)
        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        dyn.setVelocity(a, Point3D.ORIGIN)
        // Constant direction (1, 0, 0); speed 5; tau 0.5; mass 1.
        // F = m * (v_desired - v_cur) / tau = 1 * (5, 0, 0) / 0.5 = (10, 0, 0).
        val force = desiredVelocity3D<AgentModel.Agent>(speed = 5.0, tau = 0.5) { _, _ ->
            Point3D(1.0, 0.0, 0.0)
        }
        val f = force.compute(a, dyn, dt = 0.05)
        assertEquals(10.0, f.x, 1e-9)
        assertEquals(0.0, f.y, 1e-9)
        assertEquals(0.0, f.z, 1e-9)
    }

    @Test
    fun desiredVelocity3DZeroDirectionGivesZeroForce() {
        val m = Model("desired3d-zero")
        val tm = DyVolModel(m)
        val a = tm.Agent("a"); tm.ctx.add(a); tm.space.placeAt(a, Point3D.ORIGIN)
        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        val force = desiredVelocity3D<AgentModel.Agent>(speed = 5.0, tau = 0.5) { _, _ ->
            Point3D.ORIGIN
        }
        assertEquals(Point3D.ORIGIN, force.compute(a, dyn, dt = 0.05))
    }

    // ── Wall repulsion: blocked voxel produces repulsion ───────────────────

    @Test
    fun wallRepulsion3DPushesAwayFromBlockedVoxel() {
        val m = Model("wall3d")
        val tm = DyVolModel(m)
        val a = tm.Agent("a"); tm.ctx.add(a)
        // Place agent at (5.5, 5.5, 5.5) — centered in voxel (5, 5, 5).
        tm.space.placeAt(a, Point3D(5.5, 5.5, 5.5))
        // Build a graph with a blocked voxel directly above the agent.
        val graph = VoxelGraph(10, 10, 10)
        graph.block(Voxel(5, 5, 6))   // one cell up (in z)
        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        val force = wallRepulsion3D<AgentModel.Agent>(
            graph, cellSize = 1.0, origin = Point3D.ORIGIN,
            scanRadius = 2.0, minDistance = 0.05,
        ) { d -> 100.0 / d }
        val f = force.compute(a, dyn, dt = 0.05)
        // Force should push downward (negative z) since the blocked
        // voxel is above. x and y components should be zero
        // (symmetric horizontally).
        assertTrue(f.z < 0.0, "wall above should push down; force was $f")
        assertEquals(0.0, f.x, 1e-9)
        assertEquals(0.0, f.y, 1e-9)
    }

    // ── Factory parameter validation ───────────────────────────────────────

    @Test
    fun force3DFactoriesValidateInputs() {
        assertThrows<IllegalArgumentException> {
            peerRepulsion3D<AgentModel.Agent>(radius = 0.0) { 1.0 }
        }
        assertThrows<IllegalArgumentException> {
            peerRepulsion3D<AgentModel.Agent>(radius = 1.0, minDistance = -0.1) { 1.0 }
        }
        assertThrows<IllegalArgumentException> { alignment3D<AgentModel.Agent>(radius = -1.0) }
        assertThrows<IllegalArgumentException> { cohesion3D<AgentModel.Agent>(radius = 0.0) }
        assertThrows<IllegalArgumentException> { separation3D<AgentModel.Agent>(radius = 0.0) }
        assertThrows<IllegalArgumentException> { viscousDrag3D<AgentModel.Agent>(coefficient = -1.0) }
        assertThrows<IllegalArgumentException> {
            desiredVelocity3D<AgentModel.Agent>(speed = 0.0, tau = 0.5) { _, _ -> Point3D.ORIGIN }
        }
        assertThrows<IllegalArgumentException> {
            desiredVelocity3D<AgentModel.Agent>(speed = 1.0, tau = 0.0) { _, _ -> Point3D.ORIGIN }
        }
        // wallRepulsion3D parameter validation.
        val g = VoxelGraph(5, 5, 5)
        assertThrows<IllegalArgumentException> {
            wallRepulsion3D<AgentModel.Agent>(g, cellSize = 0.0, origin = Point3D.ORIGIN, scanRadius = 1.0) { 1.0 }
        }
        assertThrows<IllegalArgumentException> {
            wallRepulsion3D<AgentModel.Agent>(g, cellSize = 1.0, origin = Point3D.ORIGIN, scanRadius = 0.0) { 1.0 }
        }
        assertThrows<IllegalArgumentException> {
            wallRepulsion3D<AgentModel.Agent>(
                g, cellSize = 1.0, origin = Point3D.ORIGIN, scanRadius = 1.0, minDistance = -0.1,
            ) { 1.0 }
        }
    }
}
