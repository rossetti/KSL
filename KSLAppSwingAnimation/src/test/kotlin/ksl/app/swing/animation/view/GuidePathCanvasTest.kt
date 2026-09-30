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

package ksl.app.swing.animation.view

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.GuidedPathIntersectionDef
import ksl.animation.GuidedPathLayoutElement
import ksl.animation.GuidedPathLinkDef
import ksl.animation.GuidedTransporterDef
import ksl.animation.GuidedTransporterLayoutElement
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop canvas draws a guide path and its cart, and a blocked cart's ring, headlessly, from the same
 * shared commands the web player draws.
 */
class GuidePathCanvasTest {

    private val layout = AnimationLayout(
        title = "Guided", width = 120.0, height = 60.0,
        guidedPaths = listOf(GuidedPathLayoutElement("Floor", linkColor = "#ff00ff", linkWidth = 4.0)),
        guidedTransporters = listOf(GuidedTransporterLayoutElement("Cart", color = "#00ff00", size = 10.0))
    )

    private val events = listOf(
        AnimationEvent.GuidedPathDefined(
            0.0, "Net",
            intersections = listOf(GuidedPathIntersectionDef("A", 10.0, 30.0), GuidedPathIntersectionDef("B", 110.0, 30.0)),
            links = listOf(GuidedPathLinkDef("AB", "A", "B", numZones = 4, bidirectional = true)),
            transporters = listOf(GuidedTransporterDef("Cart")),
            spaceName = "Floor"
        ),
        AnimationEvent.GuidedTransporterMoved(0.0, "Cart", "Net", "A", spaceName = "Floor"),
        AnimationEvent.GuidedTransporterStateChanged(0.0, "Cart", "Net", "MOVING_EMPTY", spaceName = "Floor"),
        AnimationEvent.GuidedTransporterMoved(10.0, "Cart", "Net", "AB.Zone2", "AB", 2, spaceName = "Floor"),
        AnimationEvent.GuidedTransporterStateChanged(10.0, "Cart", "Net", "BLOCKED", spaceName = "Floor")
    )

    private fun paint(t: Double): BufferedImage {
        val canvas = SimulationCanvas()
        canvas.setSize(480, 240)
        canvas.replay = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))
        canvas.currentTime = t
        val image = BufferedImage(480, 240, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); canvas.paint(g); g.dispose()
        return image
    }

    private fun count(img: BufferedImage, test: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val rgb = img.getRGB(x, y)
            if (test((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff)) n++
        }
        return n
    }

    @Test
    fun theGuidePathAndItsCartAreDrawn() {
        val image = paint(5.0)
        assertTrue(count(image) { r, g, b -> r == 0xff && g == 0 && b == 0xff } > 100, "the link, in its layout colour")
        assertTrue(count(image) { r, g, b -> r == 0 && g == 0xff && b == 0 } > 20, "the cart, in its layout colour")
    }

    @Test
    fun aBlockedCartIsRinged() {
        val red = { r: Int, g: Int, b: Int -> r > 0xc0 && g < 0x50 && b < 0x50 }
        assertTrue(count(paint(5.0), red) == 0, "no ring while moving")
        assertTrue(count(paint(12.0), red) > 10, "a red ring once blocked")
    }
}
