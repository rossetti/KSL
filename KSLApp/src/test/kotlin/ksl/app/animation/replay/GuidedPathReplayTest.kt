/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.GuidedPathIntersectionDef
import ksl.animation.GuidedPathLayoutElement
import ksl.animation.GuidedPathLinkDef
import ksl.animation.GuidedTransporterDef
import ksl.animation.LayoutPoint
import ksl.app.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The replay rules for vehicles, each on the smallest trace that shows it: where a transporter's front is
 * drawn on entering a zone, that it holds still while blocked instead of creeping, that a load rides with its
 * vehicle, that closures and assignments come and go, and that a restated guide path starts afresh.
 */
class GuidedPathReplayTest {

    /** A 100-long east-west link A to B in 4 zones, a spur-free north link B to C in 2 zones, and one cart. */
    private fun defined(t: Double = 0.0) = AnimationEvent.GuidedPathDefined(
        simTime = t, networkName = "Net", spaceName = "Floor",
        intersections = listOf(
            GuidedPathIntersectionDef("A", 0.0, 0.0, aliases = listOf("Dock")),
            GuidedPathIntersectionDef("B", 100.0, 0.0),
            GuidedPathIntersectionDef("C", 100.0, 50.0, aliases = listOf("Store"))
        ),
        links = listOf(
            GuidedPathLinkDef("AB", "A", "B", numZones = 4, bidirectional = true),
            GuidedPathLinkDef("BC", "B", "C", numZones = 2)
        ),
        transporters = listOf(GuidedTransporterDef("Cart", lengthInZones = 1, vehicleName = "AGV1"))
    )

    private fun moved(t: Double, zone: String, link: String? = null, k: Int = 0) =
        AnimationEvent.GuidedTransporterMoved(t, "Cart", "Net", zone, link, k, spaceName = "Floor")

    private fun state(t: Double, s: String, halted: Boolean = false) =
        AnimationEvent.GuidedTransporterStateChanged(t, "Cart", "Net", s, spaceName = "Floor", halted = halted)

    private fun replay(events: List<AnimationEvent>, layout: AnimationLayout? = null): ReplayModel =
        ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))

    private fun assertAt(expected: WorldPoint, actual: WorldPoint?, message: String) {
        assertNotNull(actual, message)
        assertEquals(expected.x, actual.x, 1e-9, "$message (x)")
        assertEquals(expected.y, actual.y, 1e-9, "$message (y)")
    }

    @Test
    fun aFrontIsDrawnAtTheFarSideOfTheZoneItEntered() {
        val m = replay(listOf(
            defined(), moved(0.0, "A"), state(0.0, "MOVING_EMPTY"),
            moved(10.0, "AB.Zone1", "AB", 1), moved(20.0, "AB.Zone2", "AB", 2)
        ))
        assertAt(WorldPoint(0.0, 0.0), m.vehicles.transporterPositionAt("Cart", 0.0), "placed at the intersection")
        assertAt(WorldPoint(25.0, 0.0), m.vehicles.transporterPositionAt("Cart", 10.0), "zone 1 of 4 ends a quarter along")
        assertAt(WorldPoint(37.5, 0.0), m.vehicles.transporterPositionAt("Cart", 15.0), "moving between entries")
        assertAt(WorldPoint(50.0, 0.0), m.vehicles.transporterPositionAt("Cart", 20.0), "zone 2 of 4")
    }

    @Test
    fun travellingBackAlongALinkUsesTheZoneBoundaryNearItsBeginEnd() {
        val m = replay(listOf(
            defined(), moved(0.0, "B"), state(0.0, "MOVING_EMPTY"),
            moved(10.0, "AB.Zone4", "AB", 4), moved(20.0, "AB.Zone3", "AB", 3)
        ))
        assertAt(WorldPoint(75.0, 0.0), m.vehicles.transporterPositionAt("Cart", 10.0), "entered zone 4 from B")
        assertAt(WorldPoint(50.0, 0.0), m.vehicles.transporterPositionAt("Cart", 20.0), "entered zone 3 from B")
    }

    @Test
    fun aBlockedTransporterHoldsStillAndThenTravels() {
        val m = replay(listOf(
            defined(), moved(0.0, "A"), state(0.0, "MOVING_EMPTY"),
            moved(10.0, "AB.Zone1", "AB", 1),
            state(10.0, "BLOCKED"),       // waits at the end of zone 1 for zone 2
            state(30.0, "MOVING_EMPTY"),  // zone 2 comes free
            moved(40.0, "AB.Zone2", "AB", 2)
        ))
        val v = m.vehicles
        assertAt(WorldPoint(25.0, 0.0), v.transporterPositionAt("Cart", 20.0), "no creeping while blocked")
        assertAt(WorldPoint(25.0, 0.0), v.transporterPositionAt("Cart", 30.0), "still at the zone boundary when freed")
        assertAt(WorldPoint(37.5, 0.0), v.transporterPositionAt("Cart", 35.0), "travels once moving again")
        assertEquals("BLOCKED", v.transporterStateAt("Cart", 20.0)?.state)
        assertTrue(v.transporterStateAt("Cart", 20.0)!!.isStill)
    }

    @Test
    fun aHaltedOrIdleTransporterDoesNotSlideTowardItsNextZone() {
        val m = replay(listOf(
            defined(), moved(0.0, "A"), state(0.0, "IDLE"),
            state(50.0, "IDLE", halted = true), state(70.0, "IDLE"),
            state(90.0, "MOVING_EMPTY"), moved(100.0, "AB.Zone1", "AB", 1)
        ))
        assertAt(WorldPoint(0.0, 0.0), m.vehicles.transporterPositionAt("Cart", 80.0), "parked until dispatched")
        assertAt(WorldPoint(12.5, 0.0), m.vehicles.transporterPositionAt("Cart", 95.0), "travels only after dispatch")
    }

    @Test
    fun theLayoutPlacesThePathAndSeparatesFloors() {
        val layout = AnimationLayout(
            guidedPaths = listOf(
                GuidedPathLayoutElement("Floor", offset = LayoutPoint(10.0, 20.0), scale = 2.0,
                    floorOffsetPerZ = LayoutPoint(0.0, 300.0))
            )
        )
        val def = defined().copy(
            intersections = listOf(
                GuidedPathIntersectionDef("A", 0.0, 0.0),
                GuidedPathIntersectionDef("B", 100.0, 0.0, z = 1.0),
                GuidedPathIntersectionDef("C", 100.0, 50.0)
            )
        )
        val g = assertNotNull(replay(listOf(def), layout).vehicles.guidePaths["Floor"])
        assertAt(WorldPoint(10.0, 20.0), g.intersectionPoint("A"), "offset")
        assertAt(WorldPoint(210.0, 320.0), g.intersectionPoint("B"), "scaled and lifted a floor")
    }

    @Test
    fun aLoadRidesWithItsVehicleUntilItAlights() {
        val m = replay(listOf(
            defined(), moved(0.0, "A"), state(0.0, "LOADING"),
            AnimationEvent.VehicleLoadBoarded(5.0, entityId = 7, vehicleName = "AGV1", bodyName = "Cart"),
            state(5.0, "MOVING_LOADED"),
            moved(15.0, "AB.Zone1", "AB", 1),
            AnimationEvent.VehicleLoadAlighted(20.0, entityId = 7, vehicleName = "AGV1", bodyName = "Cart")
        ))
        assertNull(m.carriedEntityPositionAt(7, 4.0), "not aboard before boarding")
        assertEquals(listOf(7L), m.vehicles.loadsAboardAt("Cart", 10.0))
        assertEquals(m.vehicles.transporterPositionAt("Cart", 10.0), m.carriedEntityPositionAt(7, 10.0))
        assertNull(m.carriedEntityPositionAt(7, 20.0), "set down")
        assertTrue(m.vehicles.loadsAboardAt("Cart", 20.0).isEmpty())
    }

    @Test
    fun closuresAreShadedUntilReleased() {
        fun closure(t: Double, s: String) = AnimationEvent.GuidedPathClosureChanged(
            t, "Spill", "Net", listOf("AB.Zone2", "AB.Zone3"), s, spaceName = "Floor", holderKind = "CLOSURE"
        )
        val m = replay(listOf(defined(), closure(10.0, "RESERVED"), closure(12.0, "HELD"), closure(30.0, "RELEASED")))
        assertEquals("RESERVED", m.vehicles.closuresAt("Floor", 11.0).single().state)
        assertEquals(listOf("AB.Zone2", "AB.Zone3"), m.vehicles.closuresAt("Floor", 20.0).single().zoneNames)
        assertTrue(m.vehicles.closuresAt("Floor", 31.0).isEmpty())
    }

    @Test
    fun anAssignmentResolvesItsStationsAndEndsOnCompletionOrRevocation() {
        fun made(t: Double, task: Long, body: String, vehicle: String) = AnimationEvent.AgvAssignmentMade(
            t, "Fleet", vehicle, task, origin = "Dock", destination = "Store", bodyName = body, networkName = "Net"
        )
        val m = replay(listOf(
            defined(),
            made(1.0, 1, "Cart", "AGV1"),
            AnimationEvent.AgvAssignmentEnded(5.0, "Fleet", "AGV1", 1, "COMPLETED"),
            made(6.0, 2, "Cart", "AGV1"),
            made(8.0, 2, "Cart2", "AGV2") // task 2 re-given to another vehicle
        ))
        val a = assertNotNull(m.vehicles.assignmentAt("Cart", 2.0))
        assertAt(WorldPoint(0.0, 0.0), a.originPoint, "Dock is an alias of A")
        assertAt(WorldPoint(100.0, 50.0), a.destinationPoint, "Store is an alias of C")
        assertNull(m.vehicles.assignmentAt("Cart", 5.5), "completed")
        assertEquals(2L, m.vehicles.assignmentAt("Cart", 7.0)?.taskId)
        assertNull(m.vehicles.assignmentAt("Cart", 9.0), "revoked when re-given")
        assertEquals(2L, m.vehicles.assignmentAt("Cart2", 9.0)?.taskId)
    }

    @Test
    fun aRestatedGuidePathPlacesItsCartsAfresh() {
        val m = replay(listOf(
            defined(0.0), moved(0.0, "A"), state(0.0, "MOVING_EMPTY"), moved(10.0, "AB.Zone1", "AB", 1),
            // A capture window opens at 100: the path, the cart and its state are restated.
            defined(100.0), moved(100.0, "C"), state(100.0, "IDLE")
        ))
        assertAt(WorldPoint(25.0, 0.0), m.vehicles.transporterPositionAt("Cart", 99.0), "where the first stretch left it")
        assertAt(WorldPoint(100.0, 50.0), m.vehicles.transporterPositionAt("Cart", 100.0), "jumps, rather than slides, to C")
    }

    @Test
    fun theBodyReachesBackAlongThePathItCameBy() {
        val m = replay(listOf(
            defined(), moved(0.0, "A"), state(0.0, "MOVING_EMPTY"),
            moved(10.0, "AB.Zone1", "AB", 1), moved(20.0, "AB.Zone2", "AB", 2)
        ))
        val body = m.vehicles.transporterBodyAt("Cart", 20.0, zones = 2)
        assertEquals(listOf(WorldPoint(50.0, 0.0), WorldPoint(25.0, 0.0), WorldPoint(0.0, 0.0)), body)
    }

    @Test
    fun theGuidePathFramesTheCoordinateBoundsAndAutoLayout() {
        val events = listOf(defined(), moved(0.0, "A"))
        val m = replay(events)
        val box = assertNotNull(m.coordinateBounds())
        assertEquals(0.0, box.minX); assertEquals(100.0, box.maxX); assertEquals(50.0, box.maxY)
        val layout = m.autoLayout(events)
        assertEquals(listOf("Floor"), layout.guidedPaths.map { it.spaceName })
        assertEquals(listOf("Cart"), layout.guidedTransporters.map { it.name })
        assertTrue(layout.locations.any { it.locationName == "Dock" }, "the path's stations are placed on it")
    }

    @Test
    fun transportersAndHoldQueuesAreNotPlacedAsResourcesAndQueues() {
        val events = listOf(
            defined(),
            AnimationEvent.ResourceStateChanged(0.0, "Cart", "IDLE", 0, 1),
            AnimationEvent.ResourceStateChanged(0.0, "Lathe", "IDLE", 0, 1),
            AnimationEvent.QueueLengthChanged(0.0, "Floor:RidingHoldQ", 0),
            AnimationEvent.QueueLengthChanged(0.0, "Lathe:Q", 0)
        )
        val m = replay(events)
        val layout = m.autoLayout(events)
        assertEquals(listOf("Lathe"), layout.resources.map { it.resourceName })
        assertEquals(listOf("Lathe:Q"), layout.queues.map { it.queueName })
        val compat = layoutTraceCompatibility(AnimationLayout(), m)
        assertTrue(compat.animatedButUnlaid.none { "Cart" in it || "RidingHoldQ" in it }, compat.summary())
    }

    @Test
    fun aPathThatClimbsIsSeparatedIntoFloorsWithTheTopLeftInPlace() {
        val climbing = defined().copy(
            intersections = listOf(
                GuidedPathIntersectionDef("A", 0.0, 0.0),
                GuidedPathIntersectionDef("B", 100.0, 0.0),
                GuidedPathIntersectionDef("C", 100.0, 40.0, z = 4.0) // one floor up, 4 units higher
            )
        )
        val events = listOf(climbing, moved(0.0, "A"))
        val layout = replay(events).autoLayout(events)
        val style = layout.guidedPaths.single()
        val perZ = assertNotNull(style.floorOffsetPerZ, "a climbing path gets a floor separation")
        val placed = replay(events, layout).vehicles.guidePaths.getValue("Floor")
        val a = placed.intersectionPoint("A")!!
        val c = placed.intersectionPoint("C")!!
        assertTrue(perZ.y < 0.0, "upper floors are drawn above")
        assertTrue(c.y < a.y, "C, a floor up, is above A")
        assertEquals(40.0, c.y, 1e-9, "the top floor stays where the trace put it")
        // The upper floor spans y 0..40 as drawn; the ground floor's top edge (A, at y 0 in the trace) is below it.
        assertTrue(a.y > 40.0, "the ground floor clears the floor above")
    }

    @Test
    fun theFleetsOwnQueuesBodiesAndControlAgentsAreNotPlaced() {
        val events = listOf(
            defined(),
            AnimationEvent.AgvAssignmentMade(1.0, "Fleet", "AGV1", 1, "Dock", "Store", bodyName = "Cart", networkName = "Net"),
            AnimationEvent.FleetVehicleStateChanged(1.0, "Fleet", "Van", bodyName = "Van:Body", state = "AVAILABLE"),
            AnimationEvent.EntityCreated(0.0, 90, "VehicleAgent"),
            AnimationEvent.EntityCreated(0.0, 91, "Part"),
            AnimationEvent.QObjectEnqueued(0.5, 90, "Fleet:AvailabilityQ"),
            AnimationEvent.QueueLengthChanged(0.5, "Fleet:AvailabilityQ", 1),
            AnimationEvent.QueueLengthChanged(0.5, "Fleet:Dispatcher:TaskQ", 0),
            AnimationEvent.QueueLengthChanged(0.5, "Van:Body:HomeBaseQ", 0),
            AnimationEvent.QueueLengthChanged(0.5, "AGV1:BodyQ", 0),
            AnimationEvent.QueueLengthChanged(0.5, "Lathe:Q", 0),
            AnimationEvent.ResourceStateChanged(0.0, "Van:Body", "IDLE", 0, 1),
            AnimationEvent.ResourceStateChanged(0.0, "Lathe", "IDLE", 0, 1)
        )
        val m = replay(events)
        val layout = m.autoLayout(events)
        assertEquals(listOf("Lathe:Q"), layout.queues.map { it.queueName })
        assertEquals(listOf("Lathe"), layout.resources.map { it.resourceName })
        assertTrue(layout.objectClasses.none { it.typeName == "VehicleAgent" }, "a control agent is not in the legend")
        assertTrue(layout.objectClasses.any { it.typeName == "Part" })
    }

    @Test
    fun onlyThePlacesARunUsesAreLabelledAndAnAliasLabelsItsIntersection() {
        val events = listOf(
            defined(), moved(0.0, "A"),
            AnimationEvent.VehicleLoadBoarded(1.0, 7, "AGV1", bodyName = "Cart", locationName = "B")
        )
        val names = replay(events).autoLayout(events).locations.map { it.locationName }.toSet()
        assertTrue("B" in names, "B has no alias but a load boarded there, so it is a station")
        assertTrue("Dock" in names && "Store" in names, "aliases name their intersections")
        assertTrue("A" !in names && "C" !in names, "an aliased intersection is labelled by its alias only")
    }

    @Test
    fun aLayoutWithAGuidePathIsNotTurned() {
        val layout = AnimationLayout(
            guidedPaths = listOf(GuidedPathLayoutElement("Floor")),
            locations = listOf(
                ksl.animation.LocationLayoutElement("In", LayoutPoint(0.0, 100.0)),
                ksl.animation.LocationLayoutElement("Out", LayoutPoint(0.0, 0.0))
            )
        )
        assertEquals(layout, layout.withReadableOrientation(listOf("In", "Out")),
            "turning the locations would detach them from the path, which is not turned")
    }
}
