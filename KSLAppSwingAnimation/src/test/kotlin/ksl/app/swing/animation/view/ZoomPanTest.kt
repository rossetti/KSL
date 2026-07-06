package ksl.app.swing.animation.view

import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.io.AnimationSource
import ksl.animation.replay.ReplayModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V3: programmatic zoom controls change/clamp the zoom and reset restores the fit view. */
class ZoomPanTest {

    private fun canvas() = SimulationCanvas().apply {
        setSize(800, 600)
        replay = ReplayModel.build(AnimationSource(layout = AnimationLayout(width = 800.0, height = 600.0),
            header = AnimationTraceHeader(), events = emptyList()))
    }

    @Test
    fun `zoom in and out change the zoom level and reset restores fit`() {
        val c = canvas()
        assertEquals(1.0, c.zoomLevel)
        c.zoomIn(); assertTrue(c.zoomLevel > 1.0, "zoom in increases zoom")
        c.resetView(); assertEquals(1.0, c.zoomLevel)
        c.zoomOut(); assertTrue(c.zoomLevel < 1.0, "zoom out decreases zoom")
        c.resetView(); assertEquals(1.0, c.zoomLevel)
    }

    @Test
    fun `zoom is clamped to a sane range`() {
        val c = canvas()
        repeat(100) { c.zoomIn() }
        assertTrue(c.zoomLevel <= 20.0, "zoom clamped at the top, was ${c.zoomLevel}")
        repeat(200) { c.zoomOut() }
        assertTrue(c.zoomLevel >= 0.1, "zoom clamped at the bottom, was ${c.zoomLevel}")
    }
}
