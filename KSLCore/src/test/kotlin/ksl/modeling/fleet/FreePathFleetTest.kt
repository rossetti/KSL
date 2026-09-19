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

import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.entity.ProcessModel
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  **The whole fleet layer, over a free path.**
 *
 *  A dispatcher, tasks, tours, stops, lines and multi-load consolidation, running over a
 *  `Euclidean2DPlane` instead of a guide path, with no change to any of them. That is the claim the
 *  seam has been making since Phase 1 and this is where it is either true or it is not.
 *
 *  What the free path does *not* have, it reports as not having: no blocking, no zones. Those rows
 *  are registered and stay at zero, which is a free-path model's central assumption showing up in
 *  the output rather than being left to be remembered.
 */
class FreePathFleetTest {

    // ---- posted transport ----------------------------------------------------------------------

    private class Yard(parent: ModelElement, capacity: Int) : ProcessModel(parent, "Yard") {

        val plane = Euclidean2DPlane()

        // Named places are what a fleet is written in; the plane supplies the geometry.
        val places = listOf(
            plane.Point(0.0, 0.0, "Depot"),
            plane.Point(100.0, 0.0, "Press"),
            plane.Point(100.0, 100.0, "Paint"),
            plane.Point(0.0, 100.0, "Ship")
        )

        init {
            spatialModel = plane
        }

        val fleet = FreePathFleet(
            this, plane, places,
            assignmentPolicy = BatchedAssignmentPolicy(window = 5.0, inner = ConsolidatingPolicy()),
            name = "Fleet"
        )

        val cart = FreePathVehicle(
            fleet, "Depot", ConstantRV(10.0), name = "Cart",
            loadCapacity = capacity, stepSize = 5.0
        )

        val results = mutableListOf<FleetTransportResult>()
        var maxAboard: Int = 0

        inner class Load(aName: String, val from: String, val to: String) : Entity(aName) {
            val p = process(isDefaultProcess = true) {
                currentLocation = fleet.space.requireLocation(from)
                results.add(transportByFleet(fleet, to, origin = from))
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            if (cart.numLoadsAboard > maxAboard) maxAboard = cart.numLoadsAboard
        }

        override fun initialize() {
            maxAboard = 0
            activate(Load("One", "Press", "Ship").p)
            activate(Load("Two", "Paint", "Ship").p)
            var t = 0.25
            while (t < 150.0) {
                schedule(::sample, t)
                t += 0.25
            }
        }
    }

    private fun runYard(capacity: Int): Yard {
        val m = Model("FreePathYard$capacity")
        val yard = Yard(m, capacity)
        m.numberOfReplications = 1
        m.lengthOfReplication = 400.0
        m.simulate()
        return yard
    }

    @Test
    @DisplayName("A dispatcher, a task and a tour run over a plane with nothing changed")
    fun theFleetRunsOnAFreePath() {
        val yard = runYard(capacity = 1)
        assertEquals(2, yard.results.size, "not every load was delivered")
        assertTrue(yard.results.all { it.vehicleName == "Cart" })
        assertTrue(yard.results.all { it.totalTime > 0.0 })
        assertEquals(2.0, yard.fleet.dispatcher.numTasksCompleted.value)
    }

    @Test
    @DisplayName("Multi-load consolidation works over a free path, as it does over a guide path")
    fun consolidationWorksOnAFreePath() {
        val one = runYard(capacity = 1)
        val two = runYard(capacity = 2)
        assertEquals(1, one.maxAboard, "a one-load vehicle carried more than one")
        assertEquals(
            2, two.maxAboard,
            "a two-load vehicle never had both loads aboard at once, so the capacity bought nothing"
        )
        // Consolidating collects both before delivering either, so the second load waits less.
        assertTrue(
            two.results.sumOf { it.totalTime } < one.results.sumOf { it.totalTime },
            "consolidating over a free path was not better than not consolidating: " +
                    "${two.results.map { it.totalTime }} against ${one.results.map { it.totalTime }}"
        )
    }

    @Test
    @DisplayName("A free path reports its central assumption rather than leaving it to be remembered")
    fun theAbsenceOfBlockingIsReported() {
        val yard = runYard(capacity = 1)
        assertEquals(
            0.0, yard.cart.fracTimeBlocked.withinReplicationStatistic.weightedAverage,
            "a free-path vehicle was blocked, which a free path asserts cannot happen"
        )
        assertEquals(0.0, yard.cart.numTimesBlocked.value)
    }

    // ---- a declared service ---------------------------------------------------------------------

    private class Town(parent: ModelElement) : ProcessModel(parent, "Town") {

        val plane = Euclidean2DPlane()

        val places = listOf(
            plane.Point(0.0, 0.0, "A"),
            plane.Point(100.0, 0.0, "B"),
            plane.Point(100.0, 100.0, "C")
        )

        init {
            spatialModel = plane
        }

        val fleet = FreePathFleet(this, plane, places, name = "Fleet")

        val stopA = Stop(fleet, "A", "StopA")
        val stopB = Stop(fleet, "B", "StopB")
        val stopC = Stop(fleet, "C", "StopC")

        val line = Line("Circular", listOf(LineStop(stopA), LineStop(stopB), LineStop(stopC)))

        val bus = FreePathVehicle(
            fleet, "A", ConstantRV(10.0), name = "Bus", loadCapacity = 4, stepSize = 5.0
        )

        val results = mutableListOf<TransitResult>()

        inner class Rider(aName: String, val from: Stop, val to: String) : Entity(aName) {
            val p = process(isDefaultProcess = true) {
                currentLocation = fleet.space.requireLocation(from.location)
                results.add(rideFrom(from, to))
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun openTheService(event: KSLEvent<Nothing>) {
            repeat(6) { fleet.dispatcher.postLine(line) }
        }

        override fun initialize() {
            activate(Rider("R1", stopA, "C").p)
            activate(Rider("R2", stopB, "C").p)
            schedule(::openTheService, 1.0)
        }
    }

    @Test
    @DisplayName("A declared service runs stop to stop on a free path, and reports per stop")
    fun aLineRunsOnAFreePath() {
        val m = Model("FreePathTown")
        val town = Town(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 900.0
        m.simulate()

        assertEquals(2, town.results.size, "a rider was never carried")
        assertEquals(1.0, town.stopA.numBoarded.value, "one boarded at A")
        assertEquals(1.0, town.stopB.numBoarded.value, "one boarded at B")
        assertEquals(2.0, town.stopC.numAlighted.value, "both were bound for C")
        assertTrue(town.results.all { it.destination == "C" })
        assertTrue(
            town.results.all { it.timeAboard > 0.0 },
            "a ride took no time, so nobody actually travelled"
        )
    }
}
