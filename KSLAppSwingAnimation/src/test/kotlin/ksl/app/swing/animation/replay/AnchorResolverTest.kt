package ksl.app.swing.animation.replay

import ksl.animation.AnchorKind
import ksl.animation.AnchorRef
import ksl.animation.AnimationLayout
import ksl.animation.LayoutPoint
import ksl.animation.LocationLayoutElement
import ksl.animation.PathDefinition
import ksl.animation.StationLayoutElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 4b: `AnchorResolver` resolves station/location names (location-first, with a station fallback) and finds
 * authored functional paths (forward, and reversed for a bidirectional one).
 */
class AnchorResolverTest {

    private val layout = AnimationLayout(
        stations = listOf(StationLayoutElement("B", LayoutPoint(0.0, 0.0)), StationLayoutElement("S", LayoutPoint(1.0, 1.0))),
        locations = listOf(LocationLayoutElement("A", LayoutPoint(10.0, 10.0)), LocationLayoutElement("B", LayoutPoint(100.0, 20.0))),
        paths = listOf(
            PathDefinition(
                "p", listOf(LayoutPoint(5.0, 5.0), LayoutPoint(6.0, 7.0)),
                from = AnchorRef.location("A"), to = AnchorRef.location("B"), bidirectional = true
            ),
            PathDefinition(
                "one", listOf(LayoutPoint(9.0, 9.0)),
                from = AnchorRef.location("A"), to = AnchorRef.station("S"), bidirectional = false
            )
        )
    )
    private val r = AnchorResolver.from(layout)

    @Test
    fun `resolve is location-first with a station fallback`() {
        assertEquals(WorldPoint(100.0, 20.0), r.resolve("B"), "B resolves to the location, not the same-named station")
        assertEquals(WorldPoint(1.0, 1.0), r.resolve("S"), "S has only a station, so it falls back")
        assertEquals(WorldPoint(0.0, 0.0), r.resolve("B", AnchorKind.NETWORK_STATION), "station-first prefers station B")
        assertNull(r.resolve("nope"))
    }

    @Test
    fun `station is station-only`() {
        assertEquals(WorldPoint(0.0, 0.0), r.station("B"))
        assertNull(r.station("A"), "A is a location only")
    }

    @Test
    fun `pathBetween finds forward and reversed bidirectional paths`() {
        assertEquals(listOf(WorldPoint(5.0, 5.0), WorldPoint(6.0, 7.0)), r.pathBetween("A", "B"), "forward waypoints")
        assertEquals(listOf(WorldPoint(6.0, 7.0), WorldPoint(5.0, 5.0)), r.pathBetween("B", "A"), "reversed for bidirectional")
        assertNull(r.pathBetween("A", "Z"), "no path")
        assertNull(r.pathBetween("S", "A"), "a unidirectional path is not matched in reverse")
    }
}
