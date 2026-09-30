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

package ksl.app.animation.scene

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.GuidedPathIntersectionDef
import ksl.animation.GuidedPathLinkDef
import ksl.animation.GuidedTransporterDef
import ksl.animation.GuidedTransporterLayoutElement
import ksl.animation.LayoutPoint
import ksl.animation.QueueLayoutElement
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.style.RgbaColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the vehicle drawing decisions, on a two-link guide path with one cart: what a link, a closure, a blocked
 * or loaded cart and an assignment look like, and that a carried load is drawn once, on its cart.
 */
class VehicleSceneTest {

    private val definition = AnimationEvent.GuidedPathDefined(
        simTime = 0.0, networkName = "Net", spaceName = "Floor",
        intersections = listOf(
            GuidedPathIntersectionDef("A", 0.0, 0.0, aliases = listOf("Dock")),
            GuidedPathIntersectionDef("B", 100.0, 0.0),
            GuidedPathIntersectionDef("C", 100.0, 50.0, aliases = listOf("Store"))
        ),
        links = listOf(
            GuidedPathLinkDef("AB", "A", "B", numZones = 4, bidirectional = true),
            GuidedPathLinkDef("BC", "B", "C", numZones = 2)
        ),
        transporters = listOf(GuidedTransporterDef("Cart", vehicleName = "AGV1"))
    )

    private fun moved(t: Double, zone: String, link: String? = null, k: Int = 0) =
        AnimationEvent.GuidedTransporterMoved(t, "Cart", "Net", zone, link, k, spaceName = "Floor")

    private fun state(t: Double, s: String) =
        AnimationEvent.GuidedTransporterStateChanged(t, "Cart", "Net", s, spaceName = "Floor")

    private fun scene(events: List<AnimationEvent>, t: Double, layout: AnimationLayout? = AnimationLayout()): Scene =
        SceneBuilder(ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))).build(t)

    private val base = listOf(definition, moved(0.0, "A"), state(0.0, "MOVING_EMPTY"), moved(10.0, "AB.Zone1", "AB", 1))

    @Test
    fun aOneWayLinkCarriesAnArrowAndATwoWayLinkDoesNot() {
        val path = scene(base, 5.0).commandsOf("guidePaths")
        assertEquals(1, path.count { it is DrawCmd.ArrowHead }, "only BC is one-way")
        // Two links, then AB's three interior zone ticks and BC's one.
        assertEquals(2 + 3 + 1, path.count { it is DrawCmd.Polyline })
        assertEquals(3, path.count { it is DrawCmd.Circle }, "one marker per intersection")
    }

    @Test
    fun aClosureIsShadedWhileItHoldsAndGoneWhenReleased() {
        fun closure(t: Double, s: String) =
            AnimationEvent.GuidedPathClosureChanged(t, "Spill", "Net", listOf("AB.Zone2"), s, spaceName = "Floor")
        val events = base + listOf(closure(20.0, "RESERVED"), closure(20.0, "HELD"), closure(40.0, "RELEASED"))
        val closed = RgbaColor.parse("#d62728")
        fun shading(t: Double) = scene(events, t).commandsOf("guidePaths")
            .filterIsInstance<DrawCmd.Polyline>().filter { it.color.r == closed.r && it.color.g == closed.g }
        val held = shading(30.0).single()
        assertEquals(listOf(25.0 to 0.0, 50.0 to 0.0), held.points, "zone 2 of 4 is the second quarter of AB")
        assertTrue(shading(45.0).isEmpty())
    }

    @Test
    fun aBlockedCartIsRingedUnlessTheLayoutGivesItABlockedColour() {
        val events = base + state(10.0, "BLOCKED")
        val ringed = scene(events, 15.0).commandsOf("transporters")
        assertEquals(1, ringed.count { it is DrawCmd.Circle && it.stroke != null }, "a ring says blocked")

        val styled = AnimationLayout(guidedTransporters = listOf(GuidedTransporterLayoutElement("Cart", blockedColor = "#ff00ff")))
        val coloured = scene(events, 15.0, styled).commandsOf("transporters")
        assertTrue(coloured.none { it is DrawCmd.Circle }, "the colour says it instead")
        assertEquals(RgbaColor.parse("#ff00ff"), coloured.filterIsInstance<DrawCmd.Glyph>().single().fill)
    }

    @Test
    fun aLoadIsDrawnOnceOnItsCartWithACountWhenThereAreSeveral() {
        val events = base + listOf(
            AnimationEvent.EntityCreated(1.0, 7, "Part"),
            AnimationEvent.EntityCreated(1.0, 8, "Part"),
            AnimationEvent.QObjectEnqueued(2.0, 7, "Floor:RidingHoldQ"),
            AnimationEvent.VehicleLoadBoarded(2.0, 7, "AGV1", bodyName = "Cart"),
            AnimationEvent.VehicleLoadBoarded(3.0, 8, "AGV1", bodyName = "Cart")
        )
        val layout = AnimationLayout(queues = listOf(QueueLayoutElement("Floor:RidingHoldQ", LayoutPoint(0.0, 80.0))))
        val s = scene(events, 5.0, layout)
        val cart = s.commandsOf("transporters")
        assertEquals(2, cart.count { it is DrawCmd.Glyph }, "the cart and one load glyph")
        assertEquals("2", cart.filterIsInstance<DrawCmd.Text>().single().text)
        assertTrue(s.commandsOf("entities").isEmpty(), "not drawn again in free space")
        assertTrue(s.commandsOf("queues").none { it is DrawCmd.Glyph }, "nor in the hold queue that also records it")
    }

    @Test
    fun anAssignmentPointsAtThePickupThenTheDropOff() {
        val events = base + listOf(
            AnimationEvent.EntityCreated(1.0, 7, "Part"),
            AnimationEvent.AgvAssignmentMade(1.0, "Fleet", "AGV1", 1, "Store", "Dock", bodyName = "Cart", networkName = "Net", loadEntityId = 7),
            AnimationEvent.VehicleLoadBoarded(20.0, 7, "AGV1", bodyName = "Cart")
        )
        fun target(t: Double) = scene(events, t).commandsOf("assignments")
            .filterIsInstance<DrawCmd.Polyline>().single().points.last()
        assertEquals(100.0 to 50.0, target(5.0), "to Store, where the load waits")
        assertEquals(0.0 to 0.0, target(25.0), "to Dock once it is aboard")
    }
}
