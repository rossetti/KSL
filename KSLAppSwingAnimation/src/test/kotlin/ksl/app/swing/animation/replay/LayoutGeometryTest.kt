package ksl.app.swing.animation.replay

import ksl.animation.AnimationLayout
import ksl.animation.SpatialSpaceDescriptor
import ksl.modeling.agent.GridGeometrySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase 3: `withSpaceGeometry` stamps faithful static space geometry onto a layout — matched by space name,
 * only for spaces the layout has, never overriding geometry the layout already carries. This is the overlay
 * the trace path uses (a trace has space descriptors but no obstacle geometry).
 */
class LayoutGeometryTest {

    private fun grid(name: String) = SpatialSpaceDescriptor.Grid(name = name, cols = 5, rows = 5, cellSize = 10.0)
    private fun geom(space: String) = GridGeometrySpec(spaceName = space, cols = 5, rows = 5)

    @Test
    fun `stamps static geometry for a space the layout has`() {
        val layout = AnimationLayout(spaces = listOf(grid("G")))
        val out = layout.withSpaceGeometry(listOf(geom("G")))
        assertEquals(listOf("G"), out.spaceGeometry.map { it.spaceName })
    }

    @Test
    fun `ignores geometry for a space the layout lacks`() {
        val layout = AnimationLayout(spaces = listOf(grid("G")))
        val out = layout.withSpaceGeometry(listOf(geom("Other")))
        assertTrue(out.spaceGeometry.isEmpty(), "no orphan geometry for an absent space")
    }

    @Test
    fun `preserves geometry the layout already defines`() {
        val existing = geom("G")
        val layout = AnimationLayout(spaces = listOf(grid("G")), spaceGeometry = listOf(existing))
        val out = layout.withSpaceGeometry(listOf(geom("G")))
        assertEquals(1, out.spaceGeometry.size, "not duplicated")
        assertSame(existing, out.spaceGeometry.single(), "existing geometry unchanged")
    }

    @Test
    fun `empty geometry is a no-op`() {
        val layout = AnimationLayout(spaces = listOf(grid("G")))
        assertSame(layout, layout.withSpaceGeometry(emptyList()))
    }
}
