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
import kotlin.test.assertFailsWith

/**
 *  **A tour is mutable behind its cursor and frozen in front of it.**
 *
 *  Multi-load makes a tour something that changes while it is being walked: a task inserted into a
 *  vehicle's itinerary, or taken back out when it is revoked. What must not change is the part
 *  already walked. Rewriting history would let a vehicle be told to collect a load it has already
 *  set down, and the symptom would be a delivery counted twice rather than an exception.
 *
 *  Editing is by **task** rather than by stop, in both directions, and that is the load-bearing
 *  choice here. The two stops of a transport are not independent: taking out a pickup and leaving
 *  its set-down would route a vehicle to put down something it never collected, and putting in a
 *  pickup without its set-down would have it collect something it never puts down. A task whose
 *  pickup has already been reached cannot be removed at all -- which is the same statement `A4`
 *  makes about revocation, arrived at from the other side.
 *
 *  Only a transport's stops name a task. A repositioning errand and a line's cycle name none, and
 *  neither has a pairing to protect, so both go in as the ordinary block of stops they are.
 *
 *  Nothing calls [Tour.insert] or [Tour.remove] yet. They are here, tested, ahead of the tour
 *  policy that will use them, because the invariant they protect is easier to state now than to
 *  retrofit around a caller.
 */
class TourEditingTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 25.0, beginDirection = 0.0)
            .link("BC", "B", "C", length = 100.0, zoneLength = 25.0, beginDirection = 90.0)
            .link("CA", "C", "A", length = 100.0, zoneLength = 25.0, beginDirection = 180.0)
            .build()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(agv, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")

        inner class Load(aName: String) : Entity(aName)

        fun task(name: String, from: String, to: String): Dispatcher.TransportTask =
            agv.dispatcher.postTransport(
                Load(name), from, to, ConstantRV.ZERO, ConstantRV.ZERO, 1
            )
    }

    private fun shop(): Shop {
        val m = Model("TourEditing")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 1.0
        m.simulate()
        return shop
    }

    private fun stop(where: String) = TourStop(where, Reposition)

    @Test
    @DisplayName("A stop inserted at position zero is the one the vehicle goes to next")
    fun insertAtTheFront() {
        val tour = Tour(listOf(stop("A"), stop("B")))
        tour.insert(listOf(stop("Z")), 0)
        assertEquals("Z", tour.nextStop?.location)
        assertEquals(listOf("Z", "A", "B"), tour.stops.map { it.location })
    }

    @Test
    @DisplayName("A stop can be inserted at the end, which is what appending a task means")
    fun insertAtTheEnd() {
        val tour = Tour(listOf(stop("A"), stop("B")))
        tour.insert(listOf(stop("Z")), 2)
        assertEquals(listOf("A", "B", "Z"), tour.stops.map { it.location })
    }

    @Test
    @DisplayName("Positions are counted from the next stop, not from the beginning of the tour")
    fun positionsAreRelativeToTheCursor() {
        val tour = Tour(listOf(stop("A"), stop("B"), stop("C")))
        tour.advance()                       // A is history
        tour.insert(listOf(stop("Z")), 0)
        assertEquals(
            listOf("A", "Z", "B", "C"), tour.stops.map { it.location },
            "position 0 must mean next, not first: a vehicle past A cannot be sent to a new first stop"
        )
        assertEquals("Z", tour.nextStop?.location)
    }

    @Test
    @DisplayName("The past is frozen: nothing can be inserted before the cursor")
    fun thePastIsFrozen() {
        val tour = Tour(listOf(stop("A"), stop("B"), stop("C")))
        tour.advance()
        tour.advance()
        assertFailsWith<IllegalArgumentException> { tour.insert(listOf(stop("Z")), -1) }
        assertFailsWith<IllegalArgumentException>("only one stop is left, so 2 is past the end") {
            tour.insert(listOf(stop("Z")), 2)
        }
    }

    @Test
    @DisplayName("Removing a task takes out both its stops, because they are not independent")
    fun removalIsByTask() {
        val shop = shop()
        val one = shop.task("One", "A", "B")
        val two = shop.task("Two", "B", "C")
        val tour = Tour(
            listOf(
                TourStop("A", PickUp(one)),
                TourStop("B", PickUp(two)),
                TourStop("B", SetDown(one)),
                TourStop("C", SetDown(two))
            )
        )
        assertEquals(2, tour.remove(one), "both of the task's stops should have gone")
        assertEquals(
            listOf("B", "C"), tour.stops.map { it.location },
            "leaving a set-down behind would route the vehicle to put down what it never collected"
        )
        assertEquals(0, tour.remove(one), "removing it again should find nothing and say so")
    }

    @Test
    @DisplayName("A task whose pickup has already happened cannot be taken out")
    fun aCollectedTaskCannotBeRemoved() {
        val shop = shop()
        val one = shop.task("One", "A", "B")
        val tour = Tour(
            listOf(
                TourStop("A", PickUp(one)),
                TourStop("B", SetDown(one))
            )
        )
        tour.advance()                       // the pickup has happened; the load is aboard
        val thrown = assertFailsWith<IllegalArgumentException>(
            "this is A4 seen from the tour's side: once the load is aboard the commitment stands"
        ) { tour.remove(one) }
        assertEquals(true, thrown.message!!.contains("already been reached"))
    }

    @Test
    @DisplayName("Removing a task the tour never had changes nothing")
    fun removingAnAbsentTaskIsHarmless() {
        val shop = shop()
        val one = shop.task("One", "A", "B")
        val other = shop.task("Other", "B", "C")
        val tour = Tour(listOf(TourStop("A", PickUp(one)), TourStop("B", SetDown(one))))
        assertEquals(0, tour.remove(other))
        assertEquals(2, tour.stops.size)
    }

    @Test
    @DisplayName("A task goes in whole, its stops contiguous and in order")
    fun insertionIsByTask() {
        val shop = shop()
        val one = shop.task("One", "A", "B")
        val two = shop.task("Two", "B", "C")
        val tour = Tour(listOf(TourStop("A", PickUp(one)), TourStop("B", SetDown(one))))
        tour.insert(listOf(TourStop("B", PickUp(two)), TourStop("C", SetDown(two))), 1)
        assertEquals(
            listOf("A", "B", "C", "B"), tour.stops.map { it.location },
            "the block goes in where it was asked to, in the order given"
        )
        assertEquals(
            2, tour.remove(two),
            "a task put in whole comes out whole, which is the point of putting it in whole"
        )
    }

    @Test
    @DisplayName("Stops of two different tasks cannot be put in together")
    fun aBlockBelongsToOneTask() {
        val shop = shop()
        val one = shop.task("One", "A", "B")
        val two = shop.task("Two", "B", "C")
        val tour = Tour(listOf(stop("A")))
        val thrown = assertFailsWith<IllegalArgumentException>(
            "splitting a transport across two insertions is what this refuses to make possible"
        ) { tour.insert(listOf(TourStop("A", PickUp(one)), TourStop("C", SetDown(two))), 0) }
        assertEquals(true, thrown.message!!.contains("must belong to one task"))
        assertEquals(1, tour.stops.size, "a refused insertion must leave the tour alone")
    }

    @Test
    @DisplayName("A task already in the tour cannot be put in again")
    fun aTaskCannotBeInsertedTwice() {
        val shop = shop()
        val one = shop.task("One", "A", "B")
        val stops = listOf(TourStop("A", PickUp(one)), TourStop("B", SetDown(one)))
        val tour = Tour(stops)
        val thrown = assertFailsWith<IllegalArgumentException> { tour.insert(stops, 0) }
        assertEquals(true, thrown.message!!.contains("already in this tour"))
        assertEquals(2, tour.stops.size, "a refused insertion must leave the tour alone")
    }

    @Test
    @DisplayName("A task already reached still counts as in the tour")
    fun aTaskBehindTheCursorStillBlocksReinsertion() {
        val shop = shop()
        val one = shop.task("One", "A", "B")
        val stops = listOf(TourStop("A", PickUp(one)), TourStop("B", SetDown(one)))
        val tour = Tour(stops)
        tour.advance()                       // the pickup has happened; the load is aboard
        val thrown = assertFailsWith<IllegalArgumentException>(
            "a load aboard must not get a second pickup, and remove already refuses the mirror case"
        ) { tour.insert(stops, 0) }
        assertEquals(
            true, thrown.message!!.contains("already in this tour"),
            "it must be the identity guard that refuses this, not the position check"
        )
    }

    @Test
    @DisplayName("An empty block is refused rather than silently doing nothing")
    fun anEmptyBlockIsRefused() {
        val tour = Tour(listOf(stop("A")))
        assertFailsWith<IllegalArgumentException> { tour.insert(emptyList(), 0) }
    }

    @Test
    @DisplayName("Task-less stops have no pairing to protect and go in freely")
    fun taskLessStopsAreUnrestricted() {
        val tour = Tour(listOf(stop("A")))
        tour.insert(listOf(stop("Z"), stop("Y")), 0)
        tour.insert(listOf(stop("Z")), 0)
        assertEquals(
            listOf("Z", "Z", "Y", "A"), tour.stops.map { it.location },
            "a repositioning errand and a line's cycle name no task, so nothing is being split"
        )
    }
}
