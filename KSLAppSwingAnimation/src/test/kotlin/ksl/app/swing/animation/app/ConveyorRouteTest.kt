package ksl.app.swing.animation.app

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.ConveyorLayoutElement
import ksl.animation.LayoutPoint
import ksl.animation.SegmentRoute
import ksl.animation.NetworkStationLayoutElement
import ksl.examples.general.animationbundle.Example08ConveyorTandem
import ksl.animation.io.AnimationSource
import ksl.animation.replay.ReplayModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 10.5c (§6.7): with an authored ConveyorLayoutElement, cells distribute along the routed polyline
 * (entry → waypoints → exit) by arc length; with no route the belt stays a straight anchor-to-anchor line.
 */
class ConveyorRouteTest {

    private val stations = listOf(
        NetworkStationLayoutElement("A", LayoutPoint(0.0, 0.0)),
        NetworkStationLayoutElement("B", LayoutPoint(100.0, 0.0))
    )
    private val events = listOf(
        AnimationEvent.ConveyorDefined(0.0, "C", anchorLocations = listOf("A", "B"), anchorCells = listOf(0, 10))
    )

    private fun model(route: ConveyorLayoutElement?): ReplayModel {
        val layout = AnimationLayout(stations = stations, conveyors = listOfNotNull(route))
        return ReplayModel.build(AnimationSource(layout = layout, header = AnimationTraceHeader(), events = events))
    }

    @Test
    fun `a waypoint bends the belt off the straight line`() {
        val routed = model(ConveyorLayoutElement("C", segments = listOf(
            SegmentRoute("A", "B", waypoints = listOf(LayoutPoint(50.0, 50.0)))
        )))
        val mid = routed.conveyorCellPosition("C", 5) // halfway by cell
        assertNotNull(mid, "the routed mid cell resolves")
        // Straight line would be y≈0 at x≈50; the waypoint at (50,50) pulls the mid cell upward.
        assertTrue(mid.y > 25.0, "mid cell follows the waypoint (y=${mid.y}), not the straight belt")
    }

    @Test
    fun `with no route the belt is the straight line (fallback preserved)`() {
        val straight = model(null)
        val mid = straight.conveyorCellPosition("C", 5)
        assertNotNull(mid)
        assertTrue(kotlin.math.abs(mid.x - 50.0) < 1.0 && kotlin.math.abs(mid.y) < 1.0,
            "straight belt midpoint near (50,0), got (${mid.x}, ${mid.y})")
    }

    // ── 10.5d: the editor Conveyor tool ──

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Example08ConveyorTandem.buildModel().apply { numberOfReplications = 1; lengthOfReplication = 10.0 }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `conveyor tool creates a straight belt then routes a segment's waypoints`() {
        val c = AnimationAppController("conv", builder)
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                val name = c.inventory.conveyorInfos.first().name
                panel.addConveyorForTest(name)                       // straight belt from inventory segments
                val created = c.layout.value!!.conveyors.first { it.conveyorName == name }
                panel.armConveyorRouteForTest(name, 0)
                val armed = panel.isConveyorRouteArmedForTest()
                panel.clickConveyorWaypointForTest(30.0, 40.0)
                panel.clickConveyorWaypointForTest(60.0, 40.0)
                panel.finishConveyorRouteForTest()                   // double-click commit
                Triple(created, c.layout.value!!.conveyors.first { it.conveyorName == name } to armed, panel.isConveyorRouteArmedForTest())
            }
            assertTrue(r.first.segments.isNotEmpty(), "belt created with the inventory's segments")
            assertTrue(r.first.segments.all { it.waypoints.isEmpty() }, "initially straight (no waypoints)")
            assertTrue(r.second.second, "armed after choosing a segment to route")
            assertEquals(
                listOf(LayoutPoint(30.0, 40.0), LayoutPoint(60.0, 40.0)),
                r.second.first.segments[0].waypoints, "segment 0 carries the routed waypoints"
            )
            assertFalse(r.third, "disarmed after finishing the route")
        } finally { c.close() }
    }

    @Test
    fun `conveyors tab lists placed conveyors and removing clears it`() {
        val c = AnimationAppController("conv3", builder)
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                val name = c.inventory.conveyorInfos.first().name
                panel.addConveyorForTest(name)
                val listed = panel.conveyorTabListForTest()
                c.removeConveyorLayout(name); panel.refreshForTest()
                listed to panel.conveyorTabListForTest()
            }
            assertTrue(r.first.any { it.startsWith("Conveyor") }, "placed conveyor is listed in the tab: ${r.first}")
            assertTrue(r.second.isEmpty(), "removing the conveyor clears the tab list")
        } finally { c.close() }
    }

    @Test
    fun `double-click near a belt picks its segment for re-routing`() {
        val c = AnimationAppController("conv2", builder)
        try {
            val r = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                c.placeLayoutElement(ksl.animation.ElementKind.STATION, "A", 0.0, 0.0)
                c.placeLayoutElement(ksl.animation.ElementKind.STATION, "B", 100.0, 0.0)
                c.setConveyorLayout(ksl.animation.ConveyorLayoutElement("C",
                    segments = listOf(SegmentRoute("A", "B"))))
                // A point near the A→B midline is on the belt; a far point is not.
                panel.pickConveyorSegmentForTest(50.0, 5.0, 10.0) to panel.pickConveyorSegmentForTest(50.0, 500.0, 10.0)
            }
            assertEquals("C" to 0, r.first, "the belt segment under the click is picked")
            assertEquals(null, r.second, "a point off the belt picks nothing")
        } finally { c.close() }
    }
}
