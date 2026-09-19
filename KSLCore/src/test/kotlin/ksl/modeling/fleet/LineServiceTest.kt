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

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  **A service that runs a fixed route, stop to stop, carrying whoever is waiting.**
 *
 *  Nothing here is posted to a dispatcher on a rider's behalf and nothing is assigned to a vehicle
 *  for one. A rider stands at a stop; a vehicle comes past running a declared line; whoever is
 *  bound for somewhere it is still going gets on. The tour that carries them is the same object,
 *  walked by the same loop, that carries a single posted load — which is the claim this phase makes
 *  and the thing these tests exist to hold to.
 *
 *  The layout is a one-way loop `A -> B -> C -> D -> A`, so every rider's journey is forced through
 *  the stops in between and "somewhere further along" is a real constraint rather than a formality.
 */
class LineServiceTest {

    private class Town(
        parent: ModelElement,
        capacity: Int,
        cycles: Int,
        cyclic: Boolean
    ) : ProcessModel(parent, "Town") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 50.0, beginDirection = 90.0)
            .link("CD", "C", "D", length = 100.0, zoneLength = 50.0, beginDirection = 180.0)
            .link("DA", "D", "A", length = 100.0, zoneLength = 50.0, beginDirection = 270.0)
            .build()

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(this, network, name = "Fleet")

        val stopA = Stop(fleet, "A", "StopA")
        val stopB = Stop(fleet, "B", "StopB")
        val stopC = Stop(fleet, "C", "StopC")
        val stopD = Stop(fleet, "D", "StopD")

        val line = Line(
            "Circular",
            listOf(LineStop(stopA), LineStop(stopB), LineStop(stopC), LineStop(stopD)),
            cyclic = cyclic
        )

        val bus = AgvVehicle(
            fleet, TransporterPlacement.At("A"), ConstantRV(10.0),
            name = "Bus", loadCapacity = capacity
        )

        val results = mutableListOf<TransitResult>()

        inner class Rider(aName: String, val from: Stop, val to: String) : Entity(aName) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from.location)
                val r = rideFrom(from, to)
                results.add(r)
            }
        }

        /** What the model does at the start of every replication. Overridden by the fixtures. */
        var riders: () -> List<Rider> = { emptyList() }

        private val numCycles = cycles

        override fun initialize() {
            // One post is one cycle, so a service that keeps running is a board with several
            // cycles on it. The single vehicle takes them one after another.
            repeat(numCycles) { fleet.dispatcher.postLine(line) }
            for (r in riders()) activate(r.p)
        }
    }

    private fun run(
        capacity: Int = 4,
        cycles: Int = 6,
        horizon: Double = 900.0,
        cyclic: Boolean = true,
        riders: (Town) -> List<Town.Rider>
    ): Town {
        val m = Model("LineService")
        val town = Town(m, capacity, cycles, cyclic)
        town.riders = { riders(town) }
        m.numberOfReplications = 1
        m.lengthOfReplication = horizon
        m.simulate()
        return town
    }

    @Test
    @DisplayName("A rider waiting at a stop is carried to a stop further along the line")
    fun ridesTheLine() {
        val town = run { t -> listOf(t.Rider("R1", t.stopA, "C")) }
        assertEquals(1, town.results.size, "the rider was never carried")
        val r = town.results.single()
        assertEquals("A", r.origin)
        assertEquals("C", r.destination)
        assertEquals("Bus", r.vehicleName)
        assertTrue(r.timeAboard > 0.0, "a ride from A to C takes time, but timeAboard was ${r.timeAboard}")
        assertEquals(r.waitForVehicle + r.timeAboard, r.totalTime, 1e-9)
    }

    @Test
    @DisplayName("The stop's own rows count what happened there, and agree with the riders")
    fun perStopRowsAgree() {
        val town = run { t ->
            listOf(
                t.Rider("R1", t.stopA, "C"),
                t.Rider("R2", t.stopA, "D"),
                t.Rider("R3", t.stopB, "D")
            )
        }
        assertEquals(3, town.results.size)
        assertEquals(2.0, town.stopA.numBoarded.value, "two boarded at A")
        assertEquals(1.0, town.stopB.numBoarded.value, "one boarded at B")
        assertEquals(1.0, town.stopC.numAlighted.value, "one alighted at C")
        assertEquals(2.0, town.stopD.numAlighted.value, "two alighted at D")
        // Nothing rode without boarding, and nothing boarded without riding.
        val boarded = town.fleet.stops.sumOf { it.numBoarded.value }
        val alighted = town.fleet.stops.sumOf { it.numAlighted.value }
        assertEquals(boarded, alighted, "somebody boarded and never got off")
        assertEquals(town.results.size.toDouble(), boarded)
    }

    @Test
    @DisplayName("A full vehicle leaves people standing, and the stop says so")
    fun aFullVehicleIsCounted() {
        val town = run(capacity = 2) { t ->
            (1..5).map { t.Rider("R$it", t.stopA, "C") }
        }
        assertTrue(
            town.stopA.numPassedByFull.value >= 1.0,
            "five riders wanted a two-seat vehicle, so at least one visit left somebody standing; " +
                    "NumPassedByFull was ${town.stopA.numPassedByFull.value}"
        )
        // Everybody got there in the end, on a later cycle, and each knows how many it watched go.
        assertEquals(5, town.results.size, "somebody was never carried")
        assertTrue(
            town.results.any { it.numVehiclesPassed > 0 },
            "nobody recorded having watched a full vehicle leave"
        )
        assertTrue(
            town.results.all { it.numVehiclesPassed == 0 } || town.results.map { it.waitForVehicle }.distinct().size > 1,
            "if some were passed by, they cannot all have waited the same time"
        )
    }

    @Test
    @DisplayName("On a loop a rider is carried the long way round rather than put off at the end of a lap")
    fun aLoopCarriesTheLongWayRound() {
        // Boarding at C bound for B, on a one-way loop: B comes before C in the declared order, so
        // reaching it means riding C -> D -> A and on into the next lap. That is what a circular
        // service is *for*, and it is the case a design that ended every rider's journey with the
        // cycle could not express at all.
        val town = run { t -> listOf(t.Rider("R1", t.stopC, "B")) }
        assertEquals(1, town.results.size, "the rider was never carried")
        val r = town.results.single()
        assertEquals("C", r.origin)
        assertEquals("B", r.destination)
        // Three quarters of a 400-unit loop at 10 per unit time is 30, and the rider cannot have
        // got there in less without having been carried backwards along a one-way path.
        assertTrue(
            r.timeAboard >= 30.0,
            "C to B the long way round is at least 30 units of riding, but timeAboard was " +
                    "${r.timeAboard}"
        )
        assertEquals(1.0, town.stopB.numAlighted.value, "it got off somewhere other than B")
    }

    @Test
    @DisplayName("On a one-way line a rider is taken only where the vehicle is still going")
    fun onlyOnwardRidersBoardAOneWayLine() {
        // The same journey on a line that does *not* come back. Nothing on this run ever goes from
        // C to B, so the rider stands at the stop for the whole replication -- which is a reported
        // outcome rather than a hang, and is what the stop's queue is for.
        val town = run(cyclic = false) { t -> listOf(t.Rider("R1", t.stopC, "B")) }
        assertEquals(0, town.results.size, "nothing on this line goes from C to B")
        assertEquals(0.0, town.stopC.numBoarded.value, "somebody boarded a service that was no use")
        // It stood there for the whole run, which the stop reports rather than the model hanging.
        assertTrue(
            town.stopC.waitingQ.numInQ.withinReplicationStatistic.weightedAverage > 0.0,
            "the stop recorded nobody waiting, though nobody was ever carried"
        )
    }

    @Test
    @DisplayName("Riding is not posting: the dispatcher's queue reports the cycles, not the riders")
    fun ridersAreNotPostedWork() {
        val town = run { t ->
            listOf(t.Rider("R1", t.stopA, "C"), t.Rider("R2", t.stopB, "D"))
        }
        // Six cycles were posted and nothing else was. `A11` narrows here rather than bending: the
        // task queue reports the fleet's *work*, and a rider's wait is the stop's to report.
        assertEquals(6.0, town.fleet.dispatcher.numTasksPosted.value)
        assertTrue(town.stopA.numBoarded.value >= 1.0, "nobody ever boarded at the stop")
    }
}
