package ksl.animation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [AnimationLayout.anchorPosition] / [AnimationLayout.pathPolyline] (E1): a functional path's
 * from/to anchors must bracket its waypoints so an endpoints-only path (the natural Enter→Station1 gesture in
 * Ex09) still yields a drawable two-point segment — the fix for "I couldn't add a path between two locations."
 */
class PathPolylineTest {

    @Test
    fun `anchorPosition resolves a location, falling back to a station of the same name`() {
        val lay = AnimationLayout(
            locations = listOf(LocationLayoutElement("Enter", LayoutPoint(10.0, 20.0))),
            stations = listOf(NetworkStationLayoutElement("Dock", LayoutPoint(30.0, 40.0)))
        )
        assertEquals(LayoutPoint(10.0, 20.0), lay.anchorPosition(AnchorRef.location("Enter")))
        assertEquals(LayoutPoint(30.0, 40.0), lay.anchorPosition(AnchorRef.station("Dock")))
        assertEquals(LayoutPoint(30.0, 40.0), lay.anchorPosition(AnchorRef.location("Dock")), "declared kind absent -> other kind")
        assertEquals(null, lay.anchorPosition(AnchorRef.location("Nope")))
    }

    @Test
    fun `functional path with no waypoints still yields a drawable two-point segment (Ex09)`() {
        val lay = AnimationLayout(
            locations = listOf(
                LocationLayoutElement("Enter", LayoutPoint(0.0, 0.0)),
                LocationLayoutElement("Station1", LayoutPoint(100.0, 0.0))
            )
        )
        val path = PathDefinition("p", points = emptyList(), from = AnchorRef.location("Enter"), to = AnchorRef.location("Station1"))
        val pts = lay.pathPolyline(path)
        assertEquals(listOf(LayoutPoint(0.0, 0.0), LayoutPoint(100.0, 0.0)), pts, "endpoints bracket the (empty) waypoints")
        assertTrue(pts.size >= 2, "drawPolyline needs >= 2 points to render")
    }

    @Test
    fun `functional path threads waypoints between resolved endpoints`() {
        val lay = AnimationLayout(
            locations = listOf(
                LocationLayoutElement("A", LayoutPoint(0.0, 0.0)),
                LocationLayoutElement("B", LayoutPoint(10.0, 10.0))
            )
        )
        val path = PathDefinition("p", points = listOf(LayoutPoint(5.0, 0.0)), from = AnchorRef.location("A"), to = AnchorRef.location("B"))
        assertEquals(listOf(LayoutPoint(0.0, 0.0), LayoutPoint(5.0, 0.0), LayoutPoint(10.0, 10.0)), lay.pathPolyline(path))
    }

    @Test
    fun `legacy decorative path (no anchors) draws its own points unchanged`() {
        val path = PathDefinition("p", points = listOf(LayoutPoint(1.0, 1.0), LayoutPoint(2.0, 2.0)))
        assertEquals(listOf(LayoutPoint(1.0, 1.0), LayoutPoint(2.0, 2.0)), AnimationLayout().pathPolyline(path))
    }

    @Test
    fun `functional path with an unplaced endpoint drops the missing point`() {
        val lay = AnimationLayout(locations = listOf(LocationLayoutElement("A", LayoutPoint(0.0, 0.0))))
        val path = PathDefinition("p", points = emptyList(), from = AnchorRef.location("A"), to = AnchorRef.location("Missing"))
        assertEquals(listOf(LayoutPoint(0.0, 0.0)), lay.pathPolyline(path), "the unresolved endpoint is dropped")
    }
}
