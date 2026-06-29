package ksl.app.swing.animation.app

import ksl.animation.ElementKind
import ksl.animation.animationInventory
import ksl.examples.general.animationbundle.Example07StationTandem
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * G3: network boundary ports — a source ("Arrivals") and sink ("Exit") — extend ModelElement (not Station) and
 * implement NetworkIngress/NetworkEgress. They were dropped from the station inventory, so the layout editor
 * never offered them for placement even though the hand-authored layouts draw them. They are now classified as
 * placeable stations.
 */
class SourceSinkInventoryTest {

    @Test
    fun `source and sink stations appear in the station inventory`() {
        val inv = Example07StationTandem.buildModel().animationInventory()
        val stations = inv.namesOf(ElementKind.STATION)
        // The processing stations were always listed; the source/sink are the fix.
        assertTrue(stations.any { it.endsWith("Station1") }, "processing station listed: $stations")
        assertTrue(stations.any { it.endsWith("Arrivals") }, "source station now listed (G3): $stations")
        assertTrue(stations.any { it.endsWith("Exit") }, "sink station now listed (G3): $stations")
    }
}
