package ksl.app.animation.replay

import ksl.animation.AnchorKind
import ksl.animation.AnchorRef
import ksl.animation.AnimationLayout
import ksl.animation.LayoutPoint
import ksl.animation.LocationLayoutElement
import ksl.animation.PathDefinition
import ksl.animation.NetworkStationLayoutElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `AnchorResolver` resolves a name strictly as the requested kind (Phase 7: no cross-map fallback — legacy
 * locations-as-stations are upgraded at load) and finds authored functional paths (forward, and reversed for a
 * bidirectional one).
 */
class AnchorResolverTest {

    private val layout = AnimationLayout(
        stations = listOf(NetworkStationLayoutElement("B", LayoutPoint(0.0, 0.0)), NetworkStationLayoutElement("S", LayoutPoint(1.0, 1.0))),
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
    fun `resolve returns strictly the requested kind (no cross fallback)`() {
        assertEquals(WorldPoint(100.0, 20.0), r.resolve("B"), "B resolves to the location (LOCATION kind)")
        assertNull(r.resolve("S"), "S is a station only, so LOCATION resolution finds nothing (no fallback)")
        assertEquals(WorldPoint(0.0, 0.0), r.resolve("B", AnchorKind.NETWORK_STATION), "NETWORK_STATION resolves station B")
        assertEquals(WorldPoint(1.0, 1.0), r.resolve("S", AnchorKind.NETWORK_STATION), "S resolves as a station")
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
