/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.service.capability.render

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.GuidedPathLayoutElement
import ksl.animation.GuidedTransporterLayoutElement
import ksl.animation.animationInventory
import ksl.app.animation.replay.guidedPathPreviewEvents
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server's layout image draws a model's guide path before any run, from geometry the model supplies and
 * styling the layout supplies, and a transporter at its home base.
 */
class GuidePathRenderTest {

    private class Line(parent: ModelElement) : ModelElement(parent, "Line") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Net")
            .link("AB", "A", "B", length = 10.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .link("BC", "B", "C", length = 20.0, zoneLength = 5.0, type = LinkType.BIDIRECTIONAL)
            .station("Dock", "C")
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Floor")
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(5.0), 1, EndOfZoneControl(), "Cart")
            .also { it.homeBase = "Dock" }
    }

    private val magenta = 0xff00ff
    private val lime = 0x00ff00

    private fun count(img: BufferedImage, rgb: Int): Int {
        var n = 0
        for (x in 0 until img.width) for (y in 0 until img.height) if (img.getRGB(x, y) and 0xffffff == rgb) n++
        return n
    }

    private val layout = AnimationLayout(
        width = 400.0, height = 300.0,
        guidedPaths = listOf(GuidedPathLayoutElement("Floor", linkColor = "#ff00ff", linkWidth = 4.0)),
        guidedTransporters = listOf(GuidedTransporterLayoutElement("Cart", color = "#00ff00", size = 4.0))
    )

    @Test
    fun theModelsGuidePathIsSynthesizedWithItsCartAtHome() {
        val events = guidedPathPreviewEvents(Model("Preview").also { Line(it) }.animationInventory().guidedPaths)
        val def = events.filterIsInstance<AnimationEvent.GuidedPathDefined>().single()
        assertEquals("Floor", def.spaceName)
        assertEquals(setOf("AB", "BC"), def.links.map { it.name }.toSet())
        assertEquals("C", events.filterIsInstance<AnimationEvent.GuidedTransporterMoved>().single().zoneName,
            "the home base Dock is an alias of C")
    }

    @Test
    fun theLayoutImageDrawsThePathAndCartOnlyWhenTheModelSuppliesThem() {
        val events = guidedPathPreviewEvents(Model("Preview").also { Line(it) }.animationInventory().guidedPaths)
        val with = AnimationLayoutRenderer.renderToImage(layout, events)
        assertTrue(count(with, magenta) > 100, "the links, in the layout's link colour")
        assertTrue(count(with, lime) > 10, "the cart, in its layout colour")

        val without = AnimationLayoutRenderer.renderToImage(layout)
        assertEquals(0, count(without, magenta), "a layout alone has no geometry to draw")
    }
}
