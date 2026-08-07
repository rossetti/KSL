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

import ksl.modeling.entity.KSLProcess
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Phase C4 — the `runDynamics*` process drivers.
 *
 *  `Dynamics.step` and `Dynamics.stepAll` were tested; the four
 *  `KSLProcessBuilder` drivers that wrap them for real models had **no test
 *  reference at all**. `runDynamicsAll` matters most: it is the library's only
 *  synchronous, order-independent population driver, and the whole reason it exists
 *  is the property asserted below.
 *
 *  The distinction it embodies is not cosmetic. Driving a population by giving each
 *  agent its own `runDynamics` loop is a Gauss-Seidel update — earlier agents' moves
 *  bias later ones within the same tick, so results depend on iteration order.
 *  `runDynamicsAll` computes every agent's step from the shared prior state before
 *  applying any of them, which is a Jacobi update and order-independent.
 */
class DynamicsDriverTest {

    private class DriverModel(
        parent: ModelElement,
        val useBatchDriver: Boolean,
        val reversed: Boolean = false,
        val stopAfter: Int = Int.MAX_VALUE,
    ) : AgentModel(parent, "drivers") {

        val ctx: Context<Mover> = Context("movers")
        val space: ContinuousProjection<Mover> =
            ContinuousProjection(ctx, 0.0..1000.0, 0.0..1000.0)

        inner class Mover(aName: String) : Agent(aName)

        lateinit var dynamics: Dynamics<Mover>
        lateinit var a: Mover
        lateinit var b: Mover

        var ticks: Int = 0

        /** Drives the population; a single controller when batching, else per-agent. */
        inner class Controller : Agent("controller") {
            val script: KSLProcess = process(isDefaultProcess = true) {
                runDynamicsAll(
                    dynamics,
                    agents = { if (reversed) listOf(b, a) else listOf(a, b) },
                    dt = 1.0,
                    until = { ticks++ >= stopAfter },
                )
            }
        }

        override fun initialize() {
            super.initialize()
            ticks = 0
            a = Mover("a")
            b = Mover("b")
            ctx.add(a); ctx.add(b)
            space.placeAt(a, Point2D(100.0, 100.0))
            space.placeAt(b, Point2D(103.0, 100.0))

            dynamics = Dynamics(space, mass = { 1.0 }, maxSpeed = 1.0e6, minSpeed = 0.0)
            dynamics.addForce(separation(radius = 50.0))

            if (useBatchDriver) {
                activate(Controller().script)
            } else {
                // Per-agent loops: a Gauss-Seidel update, order-dependent.
                for (m in if (reversed) listOf(b, a) else listOf(a, b)) {
                    activate(object : Agent("drive-${m.name}") {
                        val script: KSLProcess = process(isDefaultProcess = true) {
                            // stopAfter applies to the batch driver only; per-agent
                            // loops run for the whole replication here.
                            runDynamics(m, dynamics, dt = 1.0) { false }
                        }
                    }.script)
                }
            }
        }
    }

    private fun run(m: DriverModel, length: Double = 5.0): Pair<Point2D, Point2D> {
        m.model.numberOfReplications = 1
        m.model.lengthOfReplication = length
        m.model.simulate()
        return m.space.positionOf(m.a)!! to m.space.positionOf(m.b)!!
    }

    // ── The property runDynamicsAll exists for ───────────────────────────────

    @Test
    @DisplayName("C4: runDynamicsAll gives the same result regardless of agent order")
    fun batchDriverIsOrderIndependent() {
        val forward = run(DriverModel(Model("batchFwd"), useBatchDriver = true, reversed = false))
        val backward = run(DriverModel(Model("batchRev"), useBatchDriver = true, reversed = true))
        assertEquals(forward.first.x, backward.first.x, 1e-9, "agent a, x")
        assertEquals(forward.first.y, backward.first.y, 1e-9, "agent a, y")
        assertEquals(forward.second.x, backward.second.x, 1e-9, "agent b, x")
        assertEquals(forward.second.y, backward.second.y, 1e-9, "agent b, y")
    }

    /**
     *  The companion half: the batch driver must actually be *doing* something, or
     *  order-independence would be trivially true. Mutually repelling agents must
     *  separate.
     */
    @Test
    @DisplayName("C4: runDynamicsAll actually integrates — peers separate under repulsion")
    fun batchDriverIntegrates() {
        val m = DriverModel(Model("batchMoves"), useBatchDriver = true)
        val (pa, pb) = run(m)
        assertTrue(pa.x < 100.0, "a should be pushed left, was ${pa.x}")
        assertTrue(pb.x > 103.0, "b should be pushed right, was ${pb.x}")
    }

    // ── The per-agent driver ─────────────────────────────────────────────────

    @Test
    @DisplayName("C4: runDynamics advances a single agent under its forces")
    fun perAgentDriverIntegrates() {
        val m = DriverModel(Model("perAgent"), useBatchDriver = false)
        val (pa, pb) = run(m)
        assertTrue(pa.x < 100.0, "a should be pushed left, was ${pa.x}")
        assertTrue(pb.x > 103.0, "b should be pushed right, was ${pb.x}")
    }

    /**
     *  `until` is evaluated each tick and must end the loop. Without this the driver
     *  would run for the whole replication regardless, and a model that expects to
     *  stop early would silently keep integrating.
     */
    @Test
    @DisplayName("C4: the until predicate stops the driver")
    fun untilPredicateStopsTheLoop() {
        val stopped = DriverModel(Model("stopped"), useBatchDriver = true, stopAfter = 2)
        val (pa, _) = run(stopped, length = 20.0)

        val unstopped = DriverModel(Model("unstopped"), useBatchDriver = true)
        val (pa2, _) = run(unstopped, length = 20.0)

        assertTrue(
            pa.x > pa2.x,
            "a stopped driver should have moved less far; stopped=${pa.x} unstopped=${pa2.x}",
        )
    }

    // ── 3D ───────────────────────────────────────────────────────────────────

    private class Driver3DModel(parent: ModelElement, val batch: Boolean) :
        AgentModel(parent, "drivers3d") {

        val ctx: Context<Flyer> = Context("flyers")
        val space: ContinuousVolume<Flyer> =
            ContinuousVolume(ctx, 0.0..1000.0, 0.0..1000.0, 0.0..1000.0)

        inner class Flyer(aName: String) : Agent(aName)

        lateinit var dynamics: Dynamics3D<Flyer>
        lateinit var a: Flyer

        override fun initialize() {
            super.initialize()
            a = Flyer("a")
            ctx.add(a)
            space.placeAt(a, Point3D(10.0, 20.0, 30.0))
            dynamics = Dynamics3D(space, mass = { 1.0 }, maxSpeed = 1.0e6, minSpeed = 0.0)
            dynamics.addForce(constantForce3D(Point3D(1.0, 2.0, 4.0)))

            activate(object : Agent("driver") {
                val script: KSLProcess = process(isDefaultProcess = true) {
                    if (batch) {
                        runDynamics3DAll(dynamics, agents = { listOf(a) }, dt = 1.0)
                    } else {
                        runDynamics3D(a, dynamics, dt = 1.0)
                    }
                }
            }.script)
        }
    }

    @Test
    @DisplayName("C4: both 3D drivers integrate on every axis")
    fun threeDDriversIntegrateOnEveryAxis() {
        for (batch in listOf(false, true)) {
            val m = Driver3DModel(Model("d3d-$batch"), batch)
            m.model.numberOfReplications = 1
            m.model.lengthOfReplication = 3.0
            m.model.simulate()
            val p = m.space.positionOf(m.a)!!
            assertTrue(p.x > 10.0, "x should advance (batch=$batch), was ${p.x}")
            assertTrue(p.y > 20.0, "y should advance (batch=$batch), was ${p.y}")
            assertTrue(p.z > 30.0, "z should advance (batch=$batch), was ${p.z}")
            // Constant force with distinct components: displacement ratios follow it.
            val dx = p.x - 10.0
            val dz = p.z - 30.0
            assertEquals(4.0, dz / dx, 1e-9, "z:x displacement should track the 4:1 force ratio")
        }
    }
}
