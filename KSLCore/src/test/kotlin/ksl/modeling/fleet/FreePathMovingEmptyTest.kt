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

package ksl.modeling.fleet

import ksl.modeling.entity.ProcessModel
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A free-path vehicle whose last load leaves part way through a journey, because the load's process is terminated,
 *  goes on as moving empty: `FracTimeMovingEmpty` is the time moving with no load.
 *
 *  The cart carries from A to B, 10 apart at speed 1: it leaves A at 0 and reaches B at 10.
 */
class FreePathMovingEmptyTest {

    private class Yard(parent: ModelElement, val parts: Int, val capacity: Int) : ProcessModel(parent, "Yard") {
        val plane = Euclidean2DPlane()
        val a = plane.Point(0.0, 0.0, "A")
        val b = plane.Point(10.0, 0.0, "B")

        init {
            spatialModel = plane
        }

        val fleet = FreePathFleet(this, plane, listOf(a, b), name = "Fleet")
        val vehicle = FreePathVehicle(fleet, "A", ConstantRV(1.0), name = "Cart", loadCapacity = capacity)
        val log = mutableListOf<String>()
        var moving = Double.NaN
        var loaded = Double.NaN
        var empty = Double.NaN

        inner class Part : Entity() {
            val run = process {
                currentLocation = a
                transportByFleet(fleet, destination = "B", origin = "A")
            }
        }

        inner class Controller(val part: Part) : Entity() {
            val run = process {
                delay(1.0)
                log.add("aboard@1=${vehicle.numLoadsAboard}")
                delay(1.0)
                part.terminateProcess()
                delay(3.0)
                val r = vehicle.resource
                log.add("at5 moving=${r.isMoving} transporting=${r.isTransporting} empty=${r.isMovingEmpty} aboard=${vehicle.numLoadsAboard}")
            }
        }

        override fun initialize() {
            log.clear()
            val first = Part()
            activate(first.run)
            repeat(parts - 1) { activate(Part().run) }
            activate(Controller(first).run)
        }

        override fun afterReplication() {
            moving = vehicle.body.fracTimeMoving.withinReplicationStatistic.weightedAverage
            loaded = vehicle.body.fracTimeTransporting.withinReplicationStatistic.weightedAverage
            empty = vehicle.body.fracTimeMovingEmpty.withinReplicationStatistic.weightedAverage
            super.afterReplication()
        }
    }

    private fun run(parts: Int, capacity: Int, length: Double): Yard {
        val model = Model("FreePathMovingEmpty${System.nanoTime()}", autoCSVReports = false)
        val yard = Yard(model, parts, capacity)
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        model.simulate()
        return yard
    }

    @Test
    @DisplayName("its only load terminated at 2: the cart moves empty from 2 to 10")
    fun onlyLoadTerminated() {
        val yard = run(parts = 1, capacity = 1, length = 12.0)
        assertEquals(listOf("aboard@1=1", "at5 moving=true transporting=false empty=true aboard=0"), yard.log)
        assertEquals(10.0 / 12.0, yard.moving, 1e-10)
        assertEquals(2.0 / 12.0, yard.loaded, 1e-10)
        assertEquals(8.0 / 12.0, yard.empty, 1e-10)
    }

    @Test
    @DisplayName("the replication ends at 8, during the empty remainder: moving empty from 2 to 8")
    fun replicationEndsWhileMovingEmpty() {
        val yard = run(parts = 1, capacity = 1, length = 8.0)
        assertEquals(1.0, yard.moving, 1e-10)
        assertEquals(2.0 / 8.0, yard.loaded, 1e-10)
        assertEquals(6.0 / 8.0, yard.empty, 1e-10)
    }
}
