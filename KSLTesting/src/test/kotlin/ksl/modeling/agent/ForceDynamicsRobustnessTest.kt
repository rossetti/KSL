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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Batch 1 audit fixes:
 *   - H1: coincident agents / on-wall agents get a nonzero, separating
 *     force instead of a zero direction.
 *   - H2: zero-velocity bodies are boosted to minSpeed; minSpeed and
 *     maxSpeed are cross-validated.
 *   - M7: stepAll is an order-independent (Jacobi) batch update.
 */
class ForceDynamicsRobustnessTest {

    private open class DyModel(parent: ModelElement, torus: Boolean = false) :
        AgentModel(parent, "dymodel") {
        val ctx: Context<Agent> = Context("agents")
        val space: ContinuousProjection<Agent> =
            ContinuousProjection(ctx, xRange = 0.0..100.0, yRange = 0.0..100.0, torus = torus)
    }

    private open class DyVolModel(parent: ModelElement) : AgentModel(parent, "dy3d") {
        val ctx: Context<Agent> = Context("agents")
        val space: ContinuousVolume<Agent> =
            ContinuousVolume(ctx, xRange = 0.0..100.0, yRange = 0.0..100.0, zRange = 0.0..50.0)
    }

    // ── H1: coincident agents separate (2D) ──────────────────────────────────

    @Test
    fun coincidentAgentsGetAntisymmetricNonzeroSeparation() {
        val m = Model("coincident-2d")
        val tm = DyModel(m)
        val a = tm.Agent("a"); val b = tm.Agent("b")
        tm.ctx.add(a); tm.ctx.add(b)
        tm.space.placeAt(a, Point2D(50.0, 50.0))
        tm.space.placeAt(b, Point2D(50.0, 50.0)) // exactly coincident

        val dyn = Dynamics<AgentModel.Agent>(tm.space)
        val sep = separation<AgentModel.Agent>(radius = 5.0, minDistance = 0.5)
        val fa = sep.compute(a, dyn, 0.1)
        val fb = sep.compute(b, dyn, 0.1)

        // Magnitude is the clamped falloff 1/minDistance = 2.0, on a unit jitter.
        assertEquals(2.0, fa.magnitude, 1e-9, "coincident pair must get a finite, nonzero push")
        assertTrue(fa.magnitude > 0.0)
        // Antisymmetric: the two agents are pushed in opposite directions.
        assertEquals(-fb.x, fa.x, 1e-9, "coincident pushes must be opposite")
        assertEquals(-fb.y, fa.y, 1e-9)
    }

    @Test
    fun coincidentSeparationIsDeterministicAcrossRuns() {
        fun forceOnA(): Point2D {
            val tm = DyModel(Model("det-2d"))
            val a = tm.Agent("a"); val b = tm.Agent("b")
            tm.ctx.add(a); tm.ctx.add(b)
            tm.space.placeAt(a, Point2D(10.0, 10.0))
            tm.space.placeAt(b, Point2D(10.0, 10.0))
            return separation<AgentModel.Agent>(radius = 5.0, minDistance = 0.5)
                .compute(a, Dynamics(tm.space), 0.1)
        }
        val f1 = forceOnA(); val f2 = forceOnA()
        assertEquals(f1.x, f2.x, 0.0, "identity-based jitter must be reproducible")
        assertEquals(f1.y, f2.y, 0.0)
    }

    // ── H1: agent inside a wall cell is pushed out the nearest face (2D) ─────

    @Test
    fun agentInsideWallCellIsPushedOutNearestFace() {
        val m = Model("wall-2d")
        val tm = DyModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a)
        // Cell (1,1) spans [1,2]x[1,2]. Place the agent inside it, near the
        // top face (y close to 2) so the least-penetration normal is +y.
        tm.space.placeAt(a, Point2D(1.5, 1.9))

        val graph = GridGraph(3, 3)
        graph.block(Cell(1, 1))
        val wall = wallRepulsion<AgentModel.Agent>(
            graph, cellSize = 1.0, origin = Point2D(0.0, 0.0),
            scanRadius = 2.0, minDistance = 0.5,
        ) { d -> 1.0 / d }

        val f = wall.compute(a, Dynamics(tm.space), 0.1)
        assertEquals(0.0, f.x, 1e-9, "nearest face is the top → no x component")
        assertTrue(f.y > 0.0, "agent should be pushed up out of the wall; got $f")
        assertEquals(2.0, f.magnitude, 1e-9) // falloff(minDistance)=1/0.5
    }

    // ── H1: 3D analogs ────────────────────────────────────────────────────────

    @Test
    fun coincidentAgentsSeparateIn3D() {
        val m = Model("coincident-3d")
        val tm = DyVolModel(m)
        val a = tm.Agent("a"); val b = tm.Agent("b")
        tm.ctx.add(a); tm.ctx.add(b)
        tm.space.placeAt(a, Point3D(20.0, 20.0, 20.0))
        tm.space.placeAt(b, Point3D(20.0, 20.0, 20.0))

        val dyn = Dynamics3D<AgentModel.Agent>(tm.space)
        val sep = separation3D<AgentModel.Agent>(radius = 5.0, minDistance = 0.5)
        val fa = sep.compute(a, dyn, 0.1)
        val fb = sep.compute(b, dyn, 0.1)

        assertEquals(2.0, fa.magnitude, 1e-9)
        assertEquals(-fb.x, fa.x, 1e-9)
        assertEquals(-fb.y, fa.y, 1e-9)
        assertEquals(-fb.z, fa.z, 1e-9)
    }

    @Test
    fun agentInsideWallVoxelIsPushedOutNearestFaceIn3D() {
        val m = Model("wall-3d")
        val tm = DyVolModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a)
        // Voxel (1,1,1) spans [1,2]^3. Place near the +z face.
        tm.space.placeAt(a, Point3D(1.5, 1.5, 1.9))

        val graph = VoxelGraph(3, 3, 3)
        graph.block(Voxel(1, 1, 1))
        val wall = wallRepulsion3D<AgentModel.Agent>(
            graph, cellSize = 1.0, origin = Point3D(0.0, 0.0, 0.0),
            scanRadius = 2.0, minDistance = 0.5,
        ) { d -> 1.0 / d }

        val f = wall.compute(a, Dynamics3D(tm.space), 0.1)
        assertEquals(0.0, f.x, 1e-9)
        assertEquals(0.0, f.y, 1e-9)
        assertTrue(f.z > 0.0, "agent should be pushed up out of the wall voxel; got $f")
        assertEquals(2.0, f.magnitude, 1e-9)
    }

    // ── H2: zero-velocity boost and speed cross-validation ────────────────────

    @Test
    fun zeroVelocityBodyIsBoostedToMinSpeed() {
        val m = Model("zerov")
        val tm = DyModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point2D(50.0, 50.0))

        val dyn = Dynamics<AgentModel.Agent>(tm.space, maxSpeed = 4.0, minSpeed = 1.0)
        dyn.setVelocity(a, Point2D.ORIGIN) // exactly at rest, no forces
        val (vNew, _) = dyn.step(a, 0.1)
        assertEquals(1.0, vNew.magnitude, 1e-9, "a frozen body must be kicked up to minSpeed")
    }

    @Test
    fun zeroVelocityStaysAtRestWhenMinSpeedIsZero() {
        val m = Model("zerov-rest")
        val tm = DyModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point2D(50.0, 50.0))

        val dyn = Dynamics<AgentModel.Agent>(tm.space, maxSpeed = 4.0, minSpeed = 0.0)
        dyn.setVelocity(a, Point2D.ORIGIN)
        val (vNew, _) = dyn.step(a, 0.1)
        assertEquals(0.0, vNew.magnitude, 1e-9, "minSpeed 0 must allow the body to rest")
    }

    @Test
    fun zeroVelocityBodyIsBoostedToMinSpeedIn3D() {
        val m = Model("zerov-3d")
        val tm = DyVolModel(m)
        val a = tm.Agent("a")
        tm.ctx.add(a); tm.space.placeAt(a, Point3D(20.0, 20.0, 20.0))

        val dyn = Dynamics3D<AgentModel.Agent>(tm.space, maxSpeed = 8.0, minSpeed = 2.0)
        dyn.setVelocity(a, Point3D.ORIGIN)
        val (vNew, _) = dyn.step(a, 0.1)
        assertEquals(2.0, vNew.magnitude, 1e-9)
    }

    @Test
    fun minSpeedAboveMaxSpeedIsRejectedAtConstruction() {
        // Separate models: two same-named contexts under one Model would
        // collide on KSL's global element-name uniqueness.
        val tm = DyModel(Model("speed-validate-2d"))
        val tm3 = DyVolModel(Model("speed-validate-3d"))
        assertThrows<IllegalArgumentException> {
            Dynamics<AgentModel.Agent>(tm.space, maxSpeed = 4.0, minSpeed = 5.0)
        }
        assertThrows<IllegalArgumentException> {
            Dynamics3D<AgentModel.Agent>(tm3.space, maxSpeed = 4.0, minSpeed = 5.0)
        }
    }

    @Test
    fun speedOrderIsEnforcedOnMutation() {
        val m = Model("speed-mutate")
        val tm = DyModel(m)
        val dyn = Dynamics<AgentModel.Agent>(tm.space, maxSpeed = 4.0, minSpeed = 1.0)
        assertThrows<IllegalArgumentException> { dyn.minSpeed = 5.0 } // > maxSpeed
        assertThrows<IllegalArgumentException> { dyn.maxSpeed = 0.5 } // < minSpeed
        // A valid widening then narrowing works.
        dyn.maxSpeed = 10.0
        dyn.minSpeed = 8.0
        assertEquals(8.0, dyn.minSpeed, 1e-9)
    }

    // ── M7: stepAll is an order-independent Jacobi update ─────────────────────

    @Test
    fun stepAllIsOrderIndependent() {
        val m = Model("jacobi")
        val tm = DyModel(m)
        val a = tm.Agent("a"); val b = tm.Agent("b"); val c = tm.Agent("c")
        for (w in listOf(a, b, c)) tm.ctx.add(w)
        tm.space.placeAt(a, Point2D(10.0, 10.0))
        tm.space.placeAt(b, Point2D(12.0, 10.0))
        tm.space.placeAt(c, Point2D(11.0, 12.0))
        val dyn = Dynamics<AgentModel.Agent>(tm.space, maxSpeed = 100.0)
        dyn.addForce(separation(radius = 20.0, minDistance = 1.0))
        for (w in listOf(a, b, c)) dyn.setVelocity(w, Point2D.ORIGIN)

        val forward = dyn.stepAll(listOf(a, b, c), 0.1).associate { (ag, v, p) -> ag to (v to p) }
        val reverse = dyn.stepAll(listOf(c, b, a), 0.1).associate { (ag, v, p) -> ag to (v to p) }

        for (w in listOf(a, b, c)) {
            assertEquals(forward[w], reverse[w], "stepAll result for $w must not depend on order")
        }
    }

    @Test
    fun stepAllMatchesIndividualStepsOnSharedState() {
        // Because stepAll applies nothing, each triple must equal the
        // standalone step() computed against the same (unmodified) state.
        val m = Model("jacobi-parity")
        val tm = DyModel(m)
        val a = tm.Agent("a"); val b = tm.Agent("b")
        tm.ctx.add(a); tm.ctx.add(b)
        tm.space.placeAt(a, Point2D(10.0, 10.0))
        tm.space.placeAt(b, Point2D(13.0, 10.0))
        val dyn = Dynamics<AgentModel.Agent>(tm.space, maxSpeed = 100.0)
        dyn.addForce(separation(radius = 20.0, minDistance = 1.0))
        dyn.setVelocity(a, Point2D.ORIGIN); dyn.setVelocity(b, Point2D.ORIGIN)

        val batch = dyn.stepAll(listOf(a, b), 0.1).associate { (ag, v, p) -> ag to (v to p) }
        val stepA = dyn.step(a, 0.1)
        val stepB = dyn.step(b, 0.1)
        assertEquals(stepA, batch[a]!!.first to batch[a]!!.second)
        assertEquals(stepB, batch[b]!!.first to batch[b]!!.second)
    }
}
