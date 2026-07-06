package ksl.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.SpatialSpaceDescriptor
import ksl.animation.io.AnimationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Verifies 8K.6a: when the layout declares no spaces, the renderer uses the trace-derived space. */
class DerivedSpaceTest {

    @Test
    fun `effectiveSpaces falls back to the trace SpaceDefined when the layout has none`() {
        val layout = AnimationLayout(title = "no spaces") // deliberately no gridSpace/continuousSpace
        val events = listOf(
            AnimationEvent.SpaceDefined(0.0, "floor", "Grid", cols = 20, rows = 20, cellSize = 1.0, torus = true)
        )
        val r = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))

        val grid = r.effectiveSpaces.filterIsInstance<SpatialSpaceDescriptor.Grid>().single()
        assertEquals("floor", grid.name)
        assertEquals(20, grid.cols)
        assertEquals(20, grid.rows)
        assertTrue(grid.torus)
    }

    @Test
    fun `authored spaces win over trace-derived ones`() {
        val layout = AnimationLayout(
            spaces = listOf(SpatialSpaceDescriptor.Grid("authored", cols = 5, rows = 5, cellSize = 2.0))
        )
        val events = listOf(AnimationEvent.SpaceDefined(0.0, "floor", "Grid", cols = 20, rows = 20))
        val r = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events))

        assertEquals(listOf("authored"), r.effectiveSpaces.map { it.name }, "the layout's own spaces take precedence")
    }
}
