package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutPoint
import ksl.animation.NetworkStationLayoutElement
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.view.SimulationCanvas
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies the fallback used by "Open trace…": a trace with no layout is given a grid layout derived from
 * its own elements ([ReplayModel.autoLayout]), so the replay renders instead of showing a blank canvas.
 */
class AutoLayoutTest {

    private val events = listOf(
        AnimationEvent.ReplicationStarted(0.0, 1),
        AnimationEvent.QueueLengthChanged(0.0, "WaitQ", 2),
        AnimationEvent.ResourceStateChanged(0.0, "Server", "Server_Busy", busyUnits = 1, capacity = 1),
        AnimationEvent.ResponseObserved(1.0, "NumInSystem", 3.0),
        AnimationEvent.QueueLengthChanged(2.0, "WaitQ", 0),
        AnimationEvent.ResourceStateChanged(2.0, "Server", "Server_Idle", busyUnits = 0, capacity = 1)
    )

    private fun model(layout: ksl.animation.AnimationLayout?) =
        ReplayModel.build(AnimationSource(layout = layout, header = AnimationTraceHeader(), events = events))

    @Test
    fun `auto layout places the trace's resources and queues but no clock`() {
        val layout = model(null).autoLayout(events, "My Trace")
        assertEquals("My Trace", layout.title)
        assertContains(layout.resources.map { it.resourceName }, "Server")
        assertContains(layout.queues.map { it.queueName }, "WaitQ")
        assertTrue(layout.clocks.isEmpty(), "the clock is opt-in (added from the palette), not auto-placed")
        // Response stats are intentionally omitted to avoid a tall, overlapping auto-grid.
        assertTrue(layout.values.isEmpty(), "responses are not auto-placed")
    }

    @Test
    fun `a layout-less source renders blank but the auto layout renders content`() {
        val blank = paintedPixels(model(null))
        val populated = paintedPixels(model(model(null).autoLayout(events)))
        assertTrue(populated > 500, "auto layout draws content, got $populated px")
        assertTrue(populated > blank * 5, "auto layout draws far more than the layout-less render ($populated vs $blank)")
    }

    @Test
    fun `a spatial trace frames the grid space and assigns agent state colors`() {
        // A grid space + two agents that move and report states (no resources/queues): the classic agent case
        // that Quick view used to render as a tiny corner blob with no coloring (P5).
        val spatial = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.SpaceDefined(0.0, "grid", "Grid", cols = 20, rows = 20, cellSize = 1.0),
            AnimationEvent.AgentPositionChanged(0.0, "a1", "grid", 1.0, 1.0),
            AnimationEvent.AgentStateEntered(0.0, "a1", "Susceptible"),
            AnimationEvent.AgentPositionChanged(1.0, "a1", "grid", 5.0, 8.0),
            AnimationEvent.AgentStateEntered(1.0, "a1", "Infected"),
            AnimationEvent.AgentPositionChanged(0.0, "a2", "grid", 18.0, 17.0),
            AnimationEvent.AgentStateEntered(0.0, "a2", "Recovered")
        )
        val probe = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = spatial))
        val layout = probe.autoLayout(spatial)
        // The grid space is carried over and the canvas is framed to its 20x20 extent (not a default 800x700).
        assertTrue(layout.spaces.isNotEmpty(), "the derived grid space is included")
        assertTrue(layout.width <= 60.0 && layout.height <= 60.0, "canvas is framed to the small grid, got ${layout.width}x${layout.height}")
        // Agent state colors are assigned from the trace's distinct states.
        assertContains(layout.agentStateColors.keys, "Infected")
        assertContains(layout.agentStateColors.keys, "Recovered")
    }

    @Test
    fun `a spatial trace seeds an editable object-class per type sized to the grid`() {
        val spatial = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.SpaceDefined(0.0, "grid", "Grid", cols = 20, rows = 20, cellSize = 1.0),
            AnimationEvent.AgentRegistered(0.0, "a1", "Person"),
            AnimationEvent.AgentPositionChanged(0.0, "a1", "grid", 1.0, 1.0),
            AnimationEvent.AgentRegistered(0.0, "a2", "Person"),
            AnimationEvent.AgentPositionChanged(1.0, "a2", "grid", 5.0, 8.0)
        )
        val layout = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = spatial)).autoLayout(spatial)
        val person = layout.objectClasses.singleOrNull { it.typeName == "Person" }
        assertNotNull(person, "an object-class is seeded for the discovered agent type")
        // ~0.7 of the cell size (1.0), not the renderer's invisible 10-unit default (the blob).
        assertEquals(0.7, person.size, 1e-9)
    }

    @Test
    fun `a station-network trace places stations left-to-right in flow order`() {
        val net = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.EnteredNetwork(0.0, 1L, "Net", "In"),
            AnimationEvent.StationEntered(0.0, 1L, "S1"),
            AnimationEvent.StationExited(1.0, 1L, "S1"),
            AnimationEvent.StationEntered(1.0, 1L, "S2"),
            AnimationEvent.ExitedNetwork(2.0, 1L, "Net", "Out"),
            AnimationEvent.EnteredNetwork(0.5, 2L, "Net", "In"),
            AnimationEvent.StationEntered(0.5, 2L, "S1"),
            AnimationEvent.StationEntered(1.5, 2L, "S2"),
            AnimationEvent.ExitedNetwork(2.5, 2L, "Net", "Out")
        )
        val layout = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = net)).autoLayout(net)
        val s1 = layout.stations.firstOrNull { it.stationName == "S1" }
        val s2 = layout.stations.firstOrNull { it.stationName == "S2" }
        assertNotNull(s1, "the upstream station is placed (so network entities render)")
        assertNotNull(s2, "the downstream station is placed")
        assertTrue(s1.position.x < s2.position.x, "S1 (upstream) is left of S2: ${s1.position.x} vs ${s2.position.x}")
    }

    @Test
    fun `network stations stay stations, travel locations become locations, no name in both`() {
        val evs = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.StationEntered(0.0, 1L, "Drill"),                        // a network station
            AnimationEvent.SpatialElementMoved(                                     // a mover between named places
                0.0, "AGV", fromX = Double.NaN, fromY = Double.NaN, toX = Double.NaN, toY = Double.NaN,
                velocity = 1.0, duration = 5.0, arrivalTime = 5.0, fromLocationName = "Dock", toLocationName = "Bay"
            )
        )
        val layout = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = evs)).autoLayout(evs)
        val stations = layout.stations.map { it.stationName }
        val locations = layout.locations.map { it.locationName }
        assertContains(stations, "Drill")
        assertTrue("Dock" in locations && "Bay" in locations, "travel endpoints are locations: $locations")
        assertTrue("Dock" !in stations && "Bay" !in stations, "travel endpoints are not stations")
        assertTrue(stations.none { it in locations }, "no name is both a station and a location")
    }

    @Test
    fun `auto-placed queues grow toward the left (180 degrees, head-right)`() {
        val layout = model(null).autoLayout(events)
        assertTrue(layout.queues.isNotEmpty(), "the WaitQ is placed")
        assertTrue(layout.queues.all { it.growthDegrees == 180.0 }, "auto queues grow at 180°")
    }

    @Test
    fun `process-entity MoveStarted events surface named locations at their real coordinates`() {
        // A process entity walking between named Euclidean points emits MoveStarted (movers emit
        // SpatialElementMoved) — the miners must honor both so the endpoints show as located anchors, not just a
        // moving glyph with nothing on the layout (Example02 MovingParts).
        val evs = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.MoveStarted(
                0.0, 1L, fromX = 80.0, fromY = 380.0, toX = 300.0, toY = 180.0,
                velocity = 30.0, duration = 1.0, arrivalTime = 1.0, fromLocationName = "Enter", toLocationName = "Station1"
            ),
            AnimationEvent.MoveStarted(
                1.0, 1L, fromX = 300.0, fromY = 180.0, toX = 560.0, toY = 180.0,
                velocity = 30.0, duration = 1.0, arrivalTime = 2.0, fromLocationName = "Station1", toLocationName = "Station2"
            )
        )
        val layout = ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = evs)).autoLayout(evs)
        val byName = layout.locations.associateBy { it.locationName }
        assertTrue(byName.keys.containsAll(listOf("Enter", "Station1", "Station2")), "named endpoints surface: ${byName.keys}")
        val enter = byName["Enter"]?.position
        assertNotNull(enter, "Enter is placed")
        assertEquals(80.0, enter.x, 1e-9, "Enter at its real coordinate (Cartesian branch, not a ring)")
        assertEquals(380.0, enter.y, 1e-9)
    }

    @Test
    fun `station contents draw only when the toggle is on (off by default)`() {
        assertFalse(SimulationCanvas().showStationContents, "station contents are off by default")
        val layout = AnimationLayout(
            width = 200.0, height = 200.0,
            stations = listOf(NetworkStationLayoutElement("S", LayoutPoint(100.0, 100.0)))
        )
        val evs = listOf(
            AnimationEvent.ReplicationStarted(0.0, 1),
            AnimationEvent.EnteredNetwork(0.0, 1L, "Net", "S"),
            AnimationEvent.StationEntered(0.0, 1L, "S")
        )
        val model = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), evs))
        fun painted(showContents: Boolean): Int {
            val canvas = SimulationCanvas().apply { setSize(300, 300); replay = model; currentTime = 1.0; showStationContents = showContents }
            val img = BufferedImage(300, 300, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics(); canvas.paint(g); g.dispose()
            var n = 0
            for (y in 0 until img.height) for (x in 0 until img.width) if (img.getRGB(x, y) and 0xffffff != 0xffffff) n++
            return n
        }
        assertTrue(painted(true) > painted(false), "the item at the station draws only with the toggle on")
    }

    private fun paintedPixels(replay: ReplayModel): Int {
        val canvas = SimulationCanvas().apply { setSize(600, 400); this.replay = replay; currentTime = 0.0 }
        val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); canvas.paint(g); g.dispose()
        var painted = 0
        for (y in 0 until image.height) for (x in 0 until image.width)
            if (image.getRGB(x, y) and 0xffffff != 0xffffff) painted++
        return painted
    }
}
