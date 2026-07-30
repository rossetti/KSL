package ksl.app.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutPoint
import ksl.app.animation.io.AnimationSource
import org.junit.jupiter.api.DisplayName
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A circular conveyor must be laid out as a ring, and a straight one as a line.
 *
 * The auto-layout placed every belt on a horizontal line. For a loop that asserts an end the model does not
 * have: the belt appeared to stop at its last station, and the anchor closing the circuit was discarded
 * outright, because a loop's anchor list repeats its first location and placement keeps the first position it
 * sees for a name. On the test-and-repair loop that hid the point of the model — a part wanting the station
 * it just left rides the long way round.
 *
 * The loop is stated in the trace rather than inferred: the first anchor name equals the last. Both branches
 * are pinned here, because the straight case was right all along and is the easier one to break while fixing
 * the other.
 */
class LoopConveyorAutoLayoutTest {

    private fun header() = AnimationTraceHeader(formatVersion = 1, baseTimeUnit = "MILLISECOND")

    private fun layoutFor(vararg events: AnimationEvent) =
        ReplayModel.build(AnimationSource(null, header(), events.toList(), null))
            .autoLayout(events.toList(), "t")

    /** Anchors as a conveyor reports them: the first location repeated at the end to close the circuit. */
    private fun loopAnchors() = AnimationEvent.ConveyorDefined(
        simTime = 0.0,
        conveyorName = "LoopConveyor",
        anchorLocations = listOf("Diagnostics", "Test1", "Test2", "Repair", "Test3", "Diagnostics"),
        anchorCells = listOf(0, 20, 40, 55, 100, 129)
    )

    private fun straightAnchors() = AnimationEvent.ConveyorDefined(
        simTime = 0.0,
        conveyorName = "Belt",
        anchorLocations = listOf("Entry", "Station1", "Station2", "Exit"),
        anchorCells = listOf(0, 20, 40, 60)
    )

    private fun positionsOf(layout: ksl.animation.AnimationLayout, names: List<String>): List<LayoutPoint> =
        names.mapNotNull { n -> layout.locations.firstOrNull { it.locationName == n }?.position }

    private fun dist(a: LayoutPoint, b: LayoutPoint) = hypot(a.x - b.x, a.y - b.y)

    @Test
    @DisplayName("a circular belt leaves the line, and the belt visibly closes")
    fun aLoopIsNotALine() {
        val names = listOf("Diagnostics", "Test1", "Test2", "Repair", "Test3")
        val layout = layoutFor(loopAnchors())
        val points = positionsOf(layout, names)
        assertEquals(
            names.size, points.size,
            "every anchor station must be placed: ${layout.locations.map { it.locationName }}"
        )

        val ys = points.map { it.y }
        assertTrue(
            (ys.max() - ys.min()) > 1.0,
            "a loop drawn at one y is the straight line this fix exists to stop: y values were $ys"
        )

        // The property that matters, and the one a line gets wrong: the belt RETURNS. Test3 is at cell 100 of
        // 129, only 29 cells from the start, so it must sit nearer Diagnostics than Test2 does at cell 40.
        // Strung out on a line the opposite holds, which is exactly what made the model unreadable.
        //
        // Deliberately not measured from the points' centroid. Five stations at cells 0, 20, 40, 55 and 100 of
        // 129 cluster in the first half of the loop, so their centroid is nowhere near the ring's centre;
        // asserting equal radii from it was the first version of this test and it failed a correct layout.
        val diagnostics = points[0]
        val test2 = points[2]
        val test3 = points[4]
        assertTrue(
            dist(test3, diagnostics) < dist(test2, diagnostics),
            "cell 100 of 129 is nearly back at the start, so it must be closer to it than cell 40 is: " +
                "Test3->Diagnostics ${dist(test3, diagnostics)}, Test2->Diagnostics ${dist(test2, diagnostics)}"
        )
    }

    @Test
    @DisplayName("cell order sets where each station sits on the ring")
    fun ringOrderFollowsCellOrder() {
        val points = positionsOf(layoutFor(loopAnchors()), listOf("Diagnostics", "Test1", "Test2", "Repair", "Test3"))

        // Extremes, not angles about an assumed centre. Cell 0 begins at the left of the ring, where a reader
        // starts; cell 100 of 129 is three quarters round and so the lowest on screen.
        assertEquals(points[0], points.minByOrNull { it.x }, "the cell-0 station belongs at the left of the ring")
        assertEquals(points[4], points.maxByOrNull { it.y }, "cell 100 of 129 is past the bottom of the ring")
    }

    @Test
    @DisplayName("a straight belt is still a straight line")
    fun aStraightBeltIsUnchanged() {
        val points = positionsOf(layoutFor(straightAnchors()), listOf("Entry", "Station1", "Station2", "Exit"))
        assertEquals(4, points.size, "every anchor must be placed")
        val ys = points.map { it.y }
        assertTrue((ys.max() - ys.min()) < 0.001, "a linear belt must stay on one line: $ys")
        assertEquals(points.map { it.x }.sorted(), points.map { it.x }, "x should increase with cell index")
    }

    @Test
    @DisplayName("a two-station circuit stays a line, since a ring of two is just a line")
    fun aDegenerateLoopStaysALine() {
        val layout = layoutFor(
            AnimationEvent.ConveyorDefined(
                simTime = 0.0,
                conveyorName = "Shuttle",
                anchorLocations = listOf("A", "B", "A"),
                anchorCells = listOf(0, 10, 20)
            )
        )
        val ys = positionsOf(layout, listOf("A", "B")).map { it.y }
        assertTrue((ys.max() - ys.min()) < 0.001, "two stations on a ring gains nothing over a line: $ys")
    }

    @Test
    @DisplayName("the ring is bounded, so a long belt does not dictate the canvas")
    fun theRingIsBounded() {
        // 4000 cells would be a ring some 9000 units across if the radius scaled without limit.
        val many = AnimationEvent.ConveyorDefined(
            simTime = 0.0,
            conveyorName = "Long",
            anchorLocations = listOf("P", "Q", "R", "S", "P"),
            anchorCells = listOf(0, 1000, 2000, 3000, 4000)
        )
        val points = positionsOf(layoutFor(many), listOf("P", "Q", "R", "S"))
        val span = maxOf(points.maxOf { it.x } - points.minOf { it.x }, points.maxOf { it.y } - points.minOf { it.y })
        assertTrue(span < 700.0, "the ring must stay bounded regardless of cell count; span was $span")
        assertTrue(abs(span) > 100.0, "and still be a ring rather than a dot: span was $span")
    }
}
