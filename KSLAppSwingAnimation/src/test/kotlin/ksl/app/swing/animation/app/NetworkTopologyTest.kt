package ksl.app.swing.animation.app

import ksl.animation.SpatialSpaceDescriptor
import ksl.animation.animation
import ksl.app.swing.animation.examples.AnimationDemo
import ksl.animation.io.AnimationSource
import ksl.animation.replay.ReplayModel
import ksl.examples.general.agent.NetworkRumorExample
import ksl.simulation.Model
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G7: a NetworkProjection (non-spatial graph) is snapshotted at replication start with an auto circular
 * layout — emitting a NetworkDefined backdrop (positioned nodes + weighted edges) and placing each agent at
 * its node slot so state colors render on top. This drives the whole pipeline through the real capture/replay
 * path: the model's friendship graph becomes a drawable Network space with the people placed on it.
 */
class NetworkTopologyTest {

    @Test
    fun `NetworkRumor friendship graph becomes a drawable Network space with agents on it (G7)`() {
        val m = Model("RumorModel")
        NetworkRumorExample(m, "rumor").apply { population = 30 }
        m.numberOfReplications = 1; m.lengthOfReplication = 40.0
        val layout = m.animation { title = "Rumor"; size(80.0, 80.0); objectClass("Person") { color = "#1f77b4"; size = 1.5 } }

        val files = AnimationDemo.generate(m, layout, baseName = "RumorG7Test")
        val replay = ReplayModel.build(AnimationSource.load(files.layoutFile, files.traceFile))

        val net = replay.effectiveSpaces.filterIsInstance<SpatialSpaceDescriptor.Network>().singleOrNull()
        assertTrue(net != null, "the trace yields a Network backdrop: ${replay.effectiveSpaces.map { it.name }}")
        assertEquals(30, net.nodes.size, "every person is laid out as a node")
        assertTrue(net.edges.isNotEmpty(), "the G(30, 0.1) friendship edges are carried")
        // Edge endpoints resolve to laid-out nodes (so the renderer can draw the connection lines).
        val ids = net.nodes.map { it.id }.toSet()
        assertTrue(net.edges.all { it.from in ids && it.to in ids }, "edges reference laid-out nodes")
        // The people are placed (at their node slots), so they render with their rumor-state colors.
        assertEquals(30, replay.agentNames.size, "all people are placed on the graph")
    }
}
