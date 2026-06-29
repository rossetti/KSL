package ksl.app.swing.animation.view

import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.geom.Rectangle2D
import kotlin.test.Test
import kotlin.test.assertTrue

/** Offscreen drawing checks for [ChartRenderer]: the bar fill and the plot line actually paint. */
class ChartRendererTest {

    private fun image() = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB).also {
        val g = it.createGraphics(); g.color = Color.WHITE; g.fillRect(0, 0, 200, 100); g.dispose()
    }

    private fun countColor(img: BufferedImage, target: Color): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width)
            if (img.getRGB(x, y) and 0xffffff == target.rgb and 0xffffff) n++
        return n
    }

    @Test
    fun `bar fills proportionally to value`() {
        val red = Color(0xd0, 0x10, 0x10)
        val full = image().also { img ->
            val g = img.createGraphics()
            ChartRenderer.bar(g, Rectangle2D.Double(10.0, 40.0, 100.0, 20.0), value = 100.0, maxValue = 100.0, color = red, label = "R")
            g.dispose()
        }
        val half = image().also { img ->
            val g = img.createGraphics()
            ChartRenderer.bar(g, Rectangle2D.Double(10.0, 40.0, 100.0, 20.0), value = 50.0, maxValue = 100.0, color = red, label = "R")
            g.dispose()
        }
        val fullPx = countColor(full, red)
        val halfPx = countColor(half, red)
        assertTrue(fullPx > halfPx, "full bar ($fullPx) should have more colored pixels than half ($halfPx)")
        assertTrue(halfPx > 0, "half bar should still paint")
    }

    @Test
    fun `time series draws a line for multiple samples`() {
        val blue = Color(0x10, 0x10, 0xd0)
        val img = image()
        val g = img.createGraphics()
        val samples = listOf(0.0 to 0.0, 1.0 to 5.0, 2.0 to 2.0, 3.0 to 8.0)
        ChartRenderer.timeSeries(
            g, Rectangle2D.Double(5.0, 5.0, 180.0, 80.0), samples,
            currentTime = 3.0, window = null, yMax = null, color = blue, label = "Q"
        )
        g.dispose()
        assertTrue(countColor(img, blue) > 10, "expected a visible plotted line")
    }
}
