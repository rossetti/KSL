package ksl.app.swing.animation.app

import ksl.animation.AnimationLayout
import ksl.animation.SpatialSpaceDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** V7b: agent state colors and spatial spaces can be authored (edit transforms). */
class AgentConfigTest {

    @Test
    fun `agent state colors set and remove`() {
        var layout = AnimationLayout()
            .withAgentStateColor("Infected", "#d62728")
            .withAgentStateColor("Recovered", "#2ca02c")
        assertEquals("#d62728", layout.agentStateColors["Infected"])
        assertEquals(2, layout.agentStateColors.size)
        layout = layout.withAgentStateColor("Infected", "#ff0000") // replace
        assertEquals("#ff0000", layout.agentStateColors["Infected"])
        layout = layout.withAgentStateColorRemoved("Recovered")
        assertTrue("Recovered" !in layout.agentStateColors)
    }

    @Test
    fun `continuous and grid spaces add, replace by name, and remove`() {
        var layout = AnimationLayout().withContinuousSpace("floor", 0.0, 800.0, 0.0, 600.0)
        val cont = layout.spaces.single() as SpatialSpaceDescriptor.Continuous
        assertEquals(800.0, cont.xMax)
        // Same name → replace (grid instead of continuous).
        layout = layout.withGridSpace("floor", cols = 20, rows = 10, cellSize = 16.0)
        assertEquals(1, layout.spaces.size)
        val grid = layout.spaces.single() as SpatialSpaceDescriptor.Grid
        assertEquals(20, grid.cols)
        layout = layout.withSpaceRemoved("floor")
        assertTrue(layout.spaces.isEmpty())
    }

    @Test
    fun `grid origin and torus are authorable (P7)`() {
        val layout = AnimationLayout().withGridSpace("g", cols = 20, rows = 20, cellSize = 1.0, originX = 5.0, originY = -3.0, torus = true)
        val g = layout.spaces.single() as SpatialSpaceDescriptor.Grid
        assertEquals(5.0, g.originX)
        assertEquals(-3.0, g.originY)
        assertTrue(g.torus)
        // Round-trips through TOML (the format the GUI saves).
        val back = AnimationLayout.fromToml(layout.toToml()).spaces.single() as SpatialSpaceDescriptor.Grid
        assertEquals(5.0, back.originX); assertTrue(back.torus)
    }

    @Test
    fun `network spaces are authorable with nodes and edges (P8)`() {
        val nodes = listOf(ksl.animation.NetworkNode("A", ksl.animation.LayoutPoint(0.0, 0.0)),
            ksl.animation.NetworkNode("B", ksl.animation.LayoutPoint(10.0, 0.0)))
        val edges = listOf(ksl.animation.NetworkEdge("A", "B", 2.5))
        val layout = AnimationLayout().withNetworkSpace("net", nodes, edges)
        val net = layout.spaces.single() as SpatialSpaceDescriptor.Network
        assertEquals(listOf("A", "B"), net.nodes.map { it.id })
        assertEquals(2.5, net.edges.single().weight)
        // Round-trips through TOML.
        val back = AnimationLayout.fromToml(layout.toToml()).spaces.single() as SpatialSpaceDescriptor.Network
        assertEquals(2, back.nodes.size)
        assertEquals("B", back.edges.single().to)
    }
}
