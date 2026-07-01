package ksl.app.swing.animation.app

import ksl.animation.AnimationLayout
import ksl.animation.LayoutPoint
import ksl.animation.ResourceLayoutElement
import ksl.animation.NetworkStationLayoutElement
import ksl.animation.withScaffoldOverlapsNudged
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Item 5: auto-layout used to place a resource on top of an MDS-positioned DistancesModel station, so the
 * resource couldn't be found. The scaffold now nudges colliding glyphs apart (stations keep their position).
 */
class ScaffoldOverlapTest {

    @Test
    fun `nudge separates a co-located resource and station, keeping the station fixed (item 5)`() {
        val layout = AnimationLayout(
            stations = listOf(NetworkStationLayoutElement("S", LayoutPoint(100.0, 100.0))),
            resources = listOf(ResourceLayoutElement("R", LayoutPoint(100.0, 100.0)))
        )
        val nudged = layout.withScaffoldOverlapsNudged(minDist = 48.0)
        val s = nudged.stations.single().position
        val r = nudged.resources.single().position
        assertEquals(LayoutPoint(100.0, 100.0), s, "the station (an anchor) keeps its position")
        assertTrue(hypot(s.x - r.x, s.y - r.y) >= 48.0, "the resource is nudged clear of the station ($r vs $s)")
    }

    @Test
    fun `non-overlapping glyphs are left where they are`() {
        val layout = AnimationLayout(
            stations = listOf(NetworkStationLayoutElement("S", LayoutPoint(100.0, 100.0))),
            resources = listOf(ResourceLayoutElement("R", LayoutPoint(400.0, 100.0)))
        )
        val nudged = layout.withScaffoldOverlapsNudged()
        assertEquals(LayoutPoint(400.0, 100.0), nudged.resources.single().position, "far-apart glyphs are untouched")
    }
}
