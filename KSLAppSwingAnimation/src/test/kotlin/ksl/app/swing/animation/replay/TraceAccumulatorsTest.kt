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

    @Test
    fun `ObjectTypeNames lists entity types and moving agent types, excluding static control agents`() {
        val acc = ObjectTypeNames()
        listOf(
            AnimationEvent.EntityCreated(0.0, 1L, "Customer"),
            AnimationEvent.AgentRegistered(0.0, "a1", "Person"),
            AnimationEvent.AgentPositionChanged(0.0, "a1", "space", 1.0, 1.0), // Person moves → animatable
            AnimationEvent.AgentRegistered(0.0, "ctrl", "Dispatcher"),          // registered, never moves → excluded
        ).forEach(acc::accept)
        assertEquals(setOf("Customer", "Person"), acc.result())
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

        val dock = layout.locations.firstOrNull { it.locationName == "Dock" }
        assertNotNull(dock); assertEquals(0.0, dock.position!!.x); assertEquals(0.0, dock.position!!.y)
        val shelf = layout.locations.firstOrNull { it.locationName == "Shelf" }
        assertNotNull(shelf); assertEquals(100.0, shelf.position!!.x); assertEquals(50.0, shelf.position!!.y)

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

        assertEquals(setOf("A", "B", "C"), layout.locations.map { it.locationName }.toSet())
        // Ring placement: every location is equidistant from the canvas center (not at a real coordinate).
        val cx = layout.width / 2.0; val cy = layout.height / 2.0
        val radii = layout.locations.map { Math.hypot(it.position!!.x - cx, it.position!!.y - cy) }
        assertTrue(radii.all { kotlin.math.abs(it - radii.first()) < 1e-6 }, "ring locations equidistant from center")
        // Movers with no finite coordinates carry no seeded position.
        assertNull(layout.movableResources.first { it.name == "M" }.position)
    }

    // ----- FlowOrder + flow-ordered placement -----

    private fun seize(entityId: Long, resource: String, t: Double = 1.0) =
        AnimationEvent.SeizeAllocated(simTime = t, entityId = entityId, resourceName = resource, amountAllocated = 1)

    private fun queued(entityId: Long, resource: String, queue: String, t: Double = 1.0) =
        AnimationEvent.SeizeQueued(simTime = t, entityId = entityId, resourceName = resource, queueName = queue, amountRequested = 1)

    private fun disposed(entityId: Long, t: Double = 1.0) =
        AnimationEvent.EntityDisposed(simTime = t, entityId = entityId)

    private fun resourceState(resource: String, t: Double = 0.0) =
        AnimationEvent.ResourceStateChanged(simTime = t, resourceName = resource, state = "idle", busyUnits = 0, capacity = 1)

    @Test
    fun `FlowOrder ranks resources by average seize order`() {
        val acc = FlowOrder()
        listOf(
            seize(1, "R1"), seize(1, "R2"), seize(1, "R3"),
            seize(2, "R1"), seize(2, "R2"), seize(2, "R3"),
        ).forEach(acc::accept)
        val r = acc.result()
        assertEquals(0, r.ranks["R1"]); assertEquals(1, r.ranks["R2"]); assertEquals(2, r.ranks["R3"])
    }

    @Test
    fun `FlowOrder gives parallel servers the same rank`() {
        val acc = FlowOrder()
        listOf(seize(1, "R1"), seize(1, "A"), seize(2, "R1"), seize(2, "B")).forEach(acc::accept)
        val r = acc.result()
        assertEquals(0, r.ranks["R1"])
        assertEquals(r.ranks["A"], r.ranks["B"], "parallel servers share a rank")
        assertEquals(1, r.ranks["A"])
    }

    @Test
    fun `FlowOrder maps each resource to its queue`() {
        val acc = FlowOrder()
        listOf(queued(1, "R1", "Q1"), seize(1, "R1")).forEach(acc::accept)
        assertEquals("Q1", acc.result().queueOfResource["R1"])
    }

    @Test
    fun `FlowOrder evicts per-entity state on dispose`() {
        val acc = FlowOrder()
        listOf(seize(1, "R1"), seize(1, "R2"), disposed(1), seize(1, "R3")).forEach(acc::accept)
        val r = acc.result()
        // R3 is seized at index 0 again (a fresh sequence after dispose), so it shares rank 0 with R1.
        assertEquals(0, r.ranks["R1"]); assertEquals(1, r.ranks["R2"]); assertEquals(0, r.ranks["R3"])
    }

    @Test
    fun `autoLayout places resources left-to-right in flow order`() {
        // Tandem R1 -> R2 -> R3, no spatial movement (non-spatial branch). ResourceStateChanged registers the
        // resources with the ReplayModel; SeizeAllocated drives the flow order.
        val events = listOf(
            resourceState("R1"), resourceState("R2"), resourceState("R3"),
            seize(1, "R1", 1.0), seize(1, "R2", 2.0), seize(1, "R3", 3.0),
            seize(2, "R1", 4.0), seize(2, "R2", 5.0), seize(2, "R3", 6.0),
        )
        val layout = replayOf(events).autoLayout(events)
        val x = { name: String -> layout.resources.first { it.resourceName == name }.position.x }
        assertTrue(x("R1") < x("R2"), "R1 left of R2")
        assertTrue(x("R2") < x("R3"), "R2 left of R3")
    }
}
