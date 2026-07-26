package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.ElementKind
import ksl.animation.LayoutPoint
import ksl.animation.LocationLayoutElement
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.EntityRoutes
import ksl.app.animation.replay.LocationFlow
import ksl.app.animation.replay.ResourceLocations
import ksl.app.animation.replay.withReadableOrientation
import ksl.app.animation.replay.withResourcesAtTheirLocations
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers what a generated layout now takes from the trace beyond positions: which way round the process runs,
 * which places connect to which, and where each machine stands.
 *
 * None of this is in a model's structure. A `DistancesModel` says how far apart its locations are and nothing
 * about which end is the entrance; nothing anywhere says the resource `Press` lives at the location `PressBay`.
 * The trace has all of it, and these are the assertions that say it is being read correctly — a layout that
 * silently stopped recovering any of them would still render, just wrongly.
 */
class ObservedFlowLayoutTest {

    private fun move(entity: Long, from: String, to: String, t: Double) = AnimationEvent.MoveStarted(
        simTime = t, entityId = entity, fromX = Double.NaN, fromY = Double.NaN,
        toX = Double.NaN, toY = Double.NaN, velocity = 1.0, duration = 1.0, arrivalTime = t + 1.0,
        fromLocationName = from, toLocationName = to
    )

    /** One part: created at Intake, seizes there, then walks Intake -> Middle -> Outgoing. */
    private val flowEvents = listOf(
        AnimationEvent.EntityCreated(0.0, 1L, "Part"),
        AnimationEvent.SeizeQueued(0.5, 1L, "Sorter", "Sorter:Q", 1),
        move(1L, "Intake", "Middle", 1.0),
        AnimationEvent.SeizeQueued(2.5, 1L, "Press", "Press:Q", 1),
        move(1L, "Middle", "Outgoing", 3.0),
    )

    @Test
    @DisplayName("the reading order of a venue is recovered from how entities moved through it")
    fun locationFlowRanksInProcessOrder() {
        val ranks = LocationFlow().also { acc -> flowEvents.forEach(acc::accept) }.result()
        val order = ranks.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }
        assertEquals(listOf("Intake", "Middle", "Outgoing"), order)
    }

    @Test
    @DisplayName("routes are the pairs entities travelled, undirected and without duplicates")
    fun entityRoutesAreTheObservedPairs() {
        val routes = EntityRoutes().also { acc ->
            (flowEvents + move(2L, "Outgoing", "Middle", 9.0)).forEach(acc::accept)
        }.result()
        // Outgoing->Middle is the same corridor as Middle->Outgoing; drawing it twice only doubles the line.
        assertEquals(listOf("Intake" to "Middle", "Middle" to "Outgoing"), routes)
    }

    /**
     * The first machine in a flow is the hard case: an entity is created at the first place and seizes there
     * before it has moved anywhere, so at seize time the trace has not yet said where it is. Left unhandled,
     * every model's first station is the one stranded off the floor plan.
     */
    @Test
    @DisplayName("a resource is placed at the location its work happens at, including the first one")
    fun resourceLocationsIncludeTheFirstStation() {
        val at = ResourceLocations().also { acc -> flowEvents.forEach(acc::accept) }.result()
        assertEquals("Intake", at["Sorter"], "the first station, never reached by a move")
        assertEquals("Middle", at["Press"])
    }

    // ── orientation ─────────────────────────────────────────────────────────────────────────────────

    /** Three locations on a line running the wrong way: the process starts at the right. */
    private fun backwards() = AnimationLayout(
        width = 400.0, height = 400.0,
        locations = listOf(
            LocationLayoutElement("Intake", LayoutPoint(300.0, 100.0)),
            LocationLayoutElement("Middle", LayoutPoint(200.0, 100.0)),
            LocationLayoutElement("Outgoing", LayoutPoint(100.0, 100.0)),
        ),
        resources = listOf(ResourceLayoutElement("Sorter", LayoutPoint(300.0, 100.0))),
        queues = listOf(QueueLayoutElement("Sorter:Q", LayoutPoint(300.0, 100.0))),
    )

    private fun spacings(layout: AnimationLayout): Map<Pair<String, String>, Double> {
        val placed = layout.locations.mapNotNull { l -> l.position?.let { l.locationName to it } }.sortedBy { it.first }
        val out = HashMap<Pair<String, String>, Double>()
        for (i in placed.indices) for (j in i + 1 until placed.size) {
            val (an, a) = placed[i]; val (bn, b) = placed[j]
            out[an to bn] = hypot(a.x - b.x, a.y - b.y)
        }
        return out
    }

    @Test
    @DisplayName("a backwards placement is turned to read left to right")
    fun orientationPutsTheProcessInReadingOrder() {
        val turned = backwards().withReadableOrientation(listOf("Intake", "Middle", "Outgoing"))
        val x = turned.locations.associate { it.locationName to assertNotNull(it.position).x }
        assertTrue(x.getValue("Intake") < x.getValue("Middle"), "the process must start on the left: $x")
        assertTrue(x.getValue("Middle") < x.getValue("Outgoing"))
    }

    /**
     * The whole justification for turning a placement automatically is that it costs nothing: a rigid transform
     * preserves every distance, so a placement derived from a distance matrix stays faithful. A sign error would
     * still produce a plausible-looking picture, with the wrong distances in it.
     */
    @Test
    @DisplayName("turning a placement preserves every distance in it")
    fun orientationIsRigid() {
        val before = backwards()
        val after = before.withReadableOrientation(listOf("Intake", "Middle", "Outgoing"))
        val b = spacings(before)
        val a = spacings(after)
        assertTrue(b.isNotEmpty())
        for ((pair, distance) in b) assertEquals(distance, assertNotNull(a[pair]), 1e-9, "$pair moved")
    }

    @Test
    @DisplayName("what sits on a location travels with it")
    fun coLocatedElementsFollowTheirLocation() {
        val turned = backwards().withReadableOrientation(listOf("Intake", "Middle", "Outgoing"))
        val intake = assertNotNull(turned.locations.first { it.locationName == "Intake" }.position)
        assertEquals(intake.x, turned.resources.single().position.x, 1e-9, "the machine stayed on its location")
        assertEquals(intake.y, turned.resources.single().position.y, 1e-9)
        assertEquals(intake.x, turned.queues.single().position.x, 1e-9)
    }

    /**
     * Refusing is a real outcome, not a gap. When the first and last of the process are not on the outside of
     * the placement there is no rotation that reads correctly, and a partially-correct turn would be harder to
     * explain than leaving it alone.
     */
    @Test
    @DisplayName("a placement that cannot be made to read left to right is left alone")
    fun orientationDeclinesWhenNoOrientationWorks() {
        // Middle is the far corner of a triangle, so it is leftmost or rightmost in every orientation that
        // puts Intake and Outgoing on the outside.
        val awkward = AnimationLayout(
            locations = listOf(
                LocationLayoutElement("Intake", LayoutPoint(0.0, 0.0)),
                LocationLayoutElement("Outgoing", LayoutPoint(10.0, 0.0)),
                LocationLayoutElement("Middle", LayoutPoint(5.0, 60.0)),
            )
        )
        val turned = awkward.withReadableOrientation(listOf("Intake", "Middle", "Outgoing"))
        assertEquals(awkward, turned, "no valid orientation exists, so the layout passes through untouched")
    }

    @Test
    @DisplayName("a layout with nothing placed passes through")
    fun orientationIsANoOpWithoutPlacedLocations() {
        val empty = AnimationLayout(width = 100.0, height = 100.0)
        assertEquals(empty, empty.withReadableOrientation(listOf("Intake", "Outgoing")))
    }
}

/**
 * Covers the assembly step: machines onto the places their work happens, and the consequences that follow.
 *
 * Driven through the real generator rather than the overlay in isolation, because what matters is that a
 * generated layout comes out this way — the overlay being correct while nothing calls it would look identical
 * to a passing test.
 */
class StationAssemblyTest {

    private fun move(entity: Long, from: String, to: String, t: Double) = AnimationEvent.MoveStarted(
        simTime = t, entityId = entity, fromX = Double.NaN, fromY = Double.NaN,
        toX = Double.NaN, toY = Double.NaN, velocity = 1.0, duration = 1.0, arrivalTime = t + 1.0,
        fromLocationName = from, toLocationName = to
    )

    private val events = listOf(
        AnimationEvent.EntityCreated(0.0, 1L, "Part"),
        AnimationEvent.ResourceStateChanged(0.1, "Press", "Busy", busyUnits = 1, capacity = 3),
        AnimationEvent.SeizeQueued(0.5, 1L, "Sorter", "Sorter:Q", 1),
        move(1L, "Intake", "PressBay", 1.0),
        AnimationEvent.SeizeQueued(2.5, 1L, "Press", "Press:Q", 1),
        AnimationEvent.QueueLengthChanged(2.5, "Press:Q", 4),
    )

    private val layout = AnimationLayout(
        width = 800.0, height = 400.0,
        locations = listOf(
            LocationLayoutElement("Intake", LayoutPoint(100.0, 200.0)),
            LocationLayoutElement("PressBay", LayoutPoint(500.0, 200.0)),
        ),
        // Where a scaffold puts them: a column off to one side, unrelated to the venue.
        resources = listOf(
            ResourceLayoutElement("Sorter", LayoutPoint(20.0, 20.0), size = 10.0),
            ResourceLayoutElement("Press", LayoutPoint(20.0, 90.0), size = 10.0),
        ),
        queues = listOf(
            QueueLayoutElement("Sorter:Q", LayoutPoint(0.0, 20.0)),
            QueueLayoutElement("Press:Q", LayoutPoint(0.0, 90.0)),
        ),
    )

    private fun assembled(): AnimationLayout = layout.withResourcesAtTheirLocations(
        AnimationSource(layout = null, header = AnimationTraceHeader(), events = events)
    )

    @Test
    @DisplayName("a machine is placed at the location its work happens at, and lifted clear of it")
    fun machinesLandOnTheirLocations() {
        val out = assembled()
        val press = out.resources.first { it.resourceName == "Press" }
        assertEquals(500.0, press.position.x, 1e-9, "the machine is over PressBay")
        // Lifted, because a mover at rest stands ON the location and would otherwise cover the machine.
        assertTrue(press.position.y < 200.0, "the machine must clear the spot a worker stands on")
    }

    @Test
    @DisplayName("a multi-server machine's queue head clears its whole block")
    fun queueHeadClearsACapacityWideBlock() {
        val out = assembled()
        val press = out.resources.first { it.resourceName == "Press" }
        val queue = out.queues.first { it.queueName == "Press:Q" }
        // Three cells of `size`, centred: the block's left edge is 1.5 sizes out, not half a size.
        val leftEdge = press.position.x - 3 * press.size / 2
        assertTrue(
            queue.position.x <= leftEdge,
            "head at ${queue.position.x} is inside a block reaching to $leftEdge",
        )
        assertEquals(180.0, queue.growthDegrees, "members grow away to the left, so a row reads queue -> server")
    }

    @Test
    @DisplayName("the labels that only repeat their neighbours are hidden")
    fun redundantLabelsAreSuppressed() {
        val out = assembled()
        fun hidden(kind: ElementKind, name: String) =
            out.labels.any { it.kind == kind && it.name == name && !it.visible }
        assertTrue(hidden(ElementKind.LOCATION, "PressBay"), "the location name repeats the machine's")
        assertTrue(hidden(ElementKind.QUEUE, "Press:Q"), "the queue's name repeats it again")
        assertTrue(
            out.labels.any { it.kind == ElementKind.QUEUE && it.name == "Press:Q" && it.valueVisible },
            "but the queue's count is the part that carries information",
        )
    }
}
