package ksl.app.swing.animation.app

import ksl.animation.AnimationLayout
import ksl.animation.ElementKind
import ksl.animation.LayoutPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V5c: a response/counter can be shown as value / bar / plot / summary / histogram (one at a time). */
class ResponseDisplayTest {

    @Test
    fun `switching display type moves the response between the layout's display lists`() {
        var layout = AnimationLayout().withElementAdded(ElementKind.RESPONSE, "NumInSystem", 700.0, 80.0)
        // Default placement is a value read-out.
        assertEquals(ResponseDisplay.VALUE, layout.responseDisplayOf("NumInSystem"))
        assertTrue(layout.values.any { it.responseName == "NumInSystem" })

        layout = layout.withResponseDisplay("NumInSystem", ResponseDisplay.BAR, 700.0, 80.0)
        assertEquals(ResponseDisplay.BAR, layout.responseDisplayOf("NumInSystem"))
        assertTrue(layout.bars.any { it.responseName == "NumInSystem" })
        assertTrue(layout.values.none { it.responseName == "NumInSystem" }, "no longer a value")

        layout = layout.withResponseDisplay("NumInSystem", ResponseDisplay.PLOT, 700.0, 80.0)
        assertEquals(ResponseDisplay.PLOT, layout.responseDisplayOf("NumInSystem"))
        assertTrue(layout.plots.any { it.responseName == "NumInSystem" })
        assertTrue(layout.bars.none { it.responseName == "NumInSystem" }, "exactly one display kind at a time")

        // Still counts as placed (across any display kind) and validates if the name is real.
        assertTrue(layout.isPlaced(ElementKind.RESPONSE, "NumInSystem"))

        // Remove clears it from every display list.
        layout = layout.withElementRemoved(ElementKind.RESPONSE, "NumInSystem")
        assertTrue(!layout.isPlaced(ElementKind.RESPONSE, "NumInSystem"))
    }

    @Test
    fun `histogram can be authored as a discrete frequency chart`() {
        var layout = AnimationLayout().withElementAdded(ElementKind.RESPONSE, "TimeInSystem", 700.0, 80.0)
        // Plain (continuous) histogram.
        layout = layout.withResponseDisplay("TimeInSystem", ResponseDisplay.HISTOGRAM, 700.0, 80.0)
        assertEquals(ResponseDisplay.HISTOGRAM, layout.responseDisplayOf("TimeInSystem"))
        assertTrue(!layout.responseHistogramIsDiscrete("TimeInSystem"), "default histogram is continuous")

        // Discrete = the integer-frequency form (the "frequency" display the user asked for).
        layout = layout.withResponseDisplay("TimeInSystem", ResponseDisplay.HISTOGRAM, 700.0, 80.0, discrete = true)
        assertEquals(ResponseDisplay.HISTOGRAM, layout.responseDisplayOf("TimeInSystem"))
        assertTrue(layout.responseHistogramIsDiscrete("TimeInSystem"), "discrete flag is set")
        assertTrue(layout.histograms.single { it.responseName == "TimeInSystem" }.discrete)
    }

    @Test
    fun `chart styling can be authored without changing the display form (P6)`() {
        var layout = AnimationLayout().withElementAdded(ElementKind.RESPONSE, "WIP", 700.0, 80.0)
            .withResponseDisplay("WIP", ResponseDisplay.BAR, 700.0, 80.0)
        // Default bar styling.
        assertEquals(100.0, layout.bars.single().maxValue)
        // Restyle in place: max/color/size change, position and form are preserved.
        layout = layout.withBarStyle("WIP", maxValue = 50.0, color = "#2ca02c", width = 6.0, height = 0.7)
        val bar = layout.bars.single { it.responseName == "WIP" }
        assertEquals(50.0, bar.maxValue)
        assertEquals("#2ca02c", bar.color)
        assertEquals(6.0, bar.width)
        assertEquals(LayoutPoint(700.0, 80.0), bar.position)
        assertEquals(ResponseDisplay.BAR, layout.responseDisplayOf("WIP"), "still a bar")

        // Histogram styling carries bins/color and the discrete (frequency) form together.
        layout = layout.withResponseDisplay("WIP", ResponseDisplay.HISTOGRAM, 700.0, 80.0)
            .withHistogramStyle("WIP", bins = 25, color = "#d62728", width = 200.0, height = 100.0, discrete = true)
        val h = layout.histograms.single { it.responseName == "WIP" }
        assertEquals(25, h.bins)
        assertTrue(h.discrete)
        assertEquals("#d62728", h.color)
    }
}
