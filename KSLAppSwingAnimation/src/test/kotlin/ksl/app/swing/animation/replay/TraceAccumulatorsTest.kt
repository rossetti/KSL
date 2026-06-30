package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.MoverMode
import ksl.app.swing.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 1: the positional accumulators mine real coordinates from a trace in one pass, and the gate that
 * makes it robust — non-Cartesian models emit `NaN` coordinates (see `LocationIfc.x`), which every
 * accumulator skips, so coordinate-based traces get real centroids/homes while distance/network traces fall
 * back to the crude placement. Also covers `autoLayout`'s use of the mined positions.
 */
class TraceAccumulatorsTest {

    private fun moved(
        name: String,
        fromX: Double, fromY: Double, toX: Double, toY: Double,
        from: String? = null, to: String? = null,
        mode: MoverMode = MoverMode.EMPTY, t: Double = 1.0,
    ) = AnimationEvent.SpatialElementMoved(
        simTime = t, name = name,
        fromX = fromX, fromY = fromY, toX = toX, toY = toY,
        velocity = 1.0, duration = 1.0, arrivalTime = t + 1.0,
        fromLocationName = from, toLocationName = to, mode = mode,
    )

    private val NaN = Double.NaN

    // ----- ObservedExtent -----

    @Test
    fun `ObservedExtent bounds finite coordinates`() {
        val acc = ObservedExtent()
        listOf(moved("a", 0.0, 0.0, 10.0, 20.0), moved("a", 10.0, 20.0, -5.0, 8.0)).forEach(acc::accept)
        val box = acc.result()
        assertNotNull(box)
        assertEquals(-5.0, box.minX); assertEquals(0.0, box.minY)
        assertEquals(10.0, box.maxX); assertEquals(20.0, box.maxY)
    }

    @Test
    fun `ObservedExtent is null for NaN (non-Cartesian) coordinates`() {
        val acc = ObservedExtent()
        acc.accept(moved("a", NaN, NaN, NaN, NaN, from = "L1", to = "L2"))
        assertNull(acc.result())
    }

    // ----- LocationCentroids -----

    @Test
    fun `LocationCentroids averages finite coordinates and lists all names`() {
        val acc = LocationCentroids()
        acc.accept(moved("a", 0.0, 0.0, 10.0, 20.0, from = "Dock", to = "Shelf")) // Dock@(0,0)
        acc.accept(moved("a", 2.0, 4.0, 99.0, 99.0, from = "Dock", to = "Bin"))   // Dock@(2,4) -> centroid (1,2)
        val r = acc.result()
        assertEquals(setOf("Dock", "Shelf", "Bin"), r.names)
        assertEquals(1.0, r.centroids.getValue("Dock").x)
        assertEquals(2.0, r.centroids.getValue("Dock").y)
    }

    @Test
    fun `LocationCentroids lists NaN-coordinate names but gives them no centroid`() {
        val acc = LocationCentroids()
        acc.accept(moved("a", NaN, NaN, NaN, NaN, from = "L1", to = "L2"))
        val r = acc.result()
        assertEquals(setOf("L1", "L2"), r.names)
        assertTrue(r.centroids.isEmpty())
    }

    @Test
    fun `LocationCentroids includes conveyor anchors as names`() {
        val acc = LocationCentroids()
        acc.accept(AnimationEvent.ConveyorDefined(0.0, "C", listOf("In", "Out"), listOf(0, 5)))
        assertEquals(setOf("In", "Out"), acc.result().names)
    }

    // ----- MoverHomes -----

    @Test
    fun `MoverHomes prefers the RETURNING_HOME destination, else the first position`() {
        val acc = MoverHomes()
        acc.accept(moved("AGV1", 1.0, 1.0, 5.0, 5.0))                                  // first (1,1)
        acc.accept(moved("AGV1", 5.0, 5.0, 9.0, 9.0, mode = MoverMode.RETURNING_HOME)) // home (9,9)
        acc.accept(moved("AGV2", 3.0, 7.0, 8.0, 2.0))                                  // first (3,7) only
        val r = acc.result()
        assertEquals(setOf("AGV1", "AGV2"), r.names)
        assertEquals(9.0, r.homes.getValue("AGV1").x); assertEquals(9.0, r.homes.getValue("AGV1").y)
        assertEquals(3.0, r.homes.getValue("AGV2").x); assertEquals(7.0, r.homes.getValue("AGV2").y)
    }

    @Test
    fun `MoverHomes lists NaN movers but gives them no home`() {
        val acc = MoverHomes()
        acc.accept(moved("M", NaN, NaN, NaN, NaN, from = "L1", to = "L2"))
        val r = acc.result()
        assertEquals(setOf("M"), r.names)
        assertTrue(r.homes.isEmpty())
    }

    // ----- AgentStateNames -----

    @Test
    fun `AgentStateNames collects distinct states in first-seen order`() {
        val acc = AgentStateNames()
        listOf("idle", "busy", "idle", "blocked").forEach { acc.accept(AnimationEvent.AgentStateEntered(0.0, "A", it)) }
        assertEquals(listOf("idle", "busy", "blocked"), acc.result())
    }

    // ----- autoLayout integration -----

    private fun replayOf(events: List<AnimationEvent>) =
        ReplayModel.build(AnimationSource(layout = null, header = AnimationTraceHeader(), events = events))

    @Test
    fun `autoLayout places named locations at their real centroids for a Cartesian trace`() {
        val events = listOf(
            moved("AGV1", 0.0, 0.0, 100.0, 50.0, from = "Dock", to = "Shelf", mode = MoverMode.TRANSPORTING),
            moved("AGV1", 100.0, 50.0, 0.0, 0.0, from = "Shelf", to = "Dock", mode = MoverMode.RETURNING_HOME),
        )
        val layout = replayOf(events).autoLayout(events)

        val dock = layout.stations.firstOrNull { it.stationName == "Dock" }
        assertNotNull(dock); assertEquals(0.0, dock.position.x); assertEquals(0.0, dock.position.y)
        val shelf = layout.stations.firstOrNull { it.stationName == "Shelf" }
        assertNotNull(shelf); assertEquals(100.0, shelf.position.x); assertEquals(50.0, shelf.position.y)

        val agvPos = layout.movableResources.first { it.name == "AGV1" }.position
        assertNotNull(agvPos); assertEquals(0.0, agvPos.x) // seeded at home (Dock, via RETURNING_HOME)
    }

    @Test
    fun `autoLayout falls back to a ring for a non-Cartesian (NaN) trace`() {
        val events = listOf(
            moved("M", NaN, NaN, NaN, NaN, from = "A", to = "B"),
            moved("M", NaN, NaN, NaN, NaN, from = "B", to = "C"),
        )
        val layout = replayOf(events).autoLayout(events)

        assertEquals(setOf("A", "B", "C"), layout.stations.map { it.stationName }.toSet())
        // Ring placement: every station is equidistant from the canvas center (not at a real coordinate).
        val cx = layout.width / 2.0; val cy = layout.height / 2.0
        val radii = layout.stations.map { Math.hypot(it.position.x - cx, it.position.y - cy) }
        assertTrue(radii.all { kotlin.math.abs(it - radii.first()) < 1e-6 }, "ring stations equidistant from center")
        // Movers with no finite coordinates carry no seeded position.
        assertNull(layout.movableResources.first { it.name == "M" }.position)
    }
}
