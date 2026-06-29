package ksl.app.swing.animation.app

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.SpaceInfo
import ksl.animation.animationInventory
import ksl.examples.general.agent.DroneDeliveryExample
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.Voxel
import ksl.modeling.agent.VoxelProjection
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * G8: the 3D agent spaces (ContinuousVolume, VoxelProjection) now emit positions and a backdrop, flattened to
 * the x–y (col/row) plane, so a 3D model like DroneDelivery animates in the 2D viewer.
 */
class DroneFlattenTest {

    private class CaptureSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    fun `ContinuousVolume appears as a 2D footprint and emits flattened positions (G8)`() {
        val m = Model("Drone"); DroneDeliveryExample(m, "drone")
        val inv = m.animationInventory()
        assertTrue(inv.spaces.any { it.kind == SpaceInfo.SpaceKind.CONTINUOUS },
            "the 3D volume is exposed as a 2D continuous footprint: ${inv.spaces}")

        val sink = CaptureSink(); m.animationSink = sink
        m.numberOfReplications = 1; m.lengthOfReplication = 200.0
        m.simulate()
        val moves = sink.events.filterIsInstance<AnimationEvent.AgentPositionChanged>()
        assertTrue(moves.isNotEmpty(), "drones emit flattened positions when run (got ${sink.events.size} events total)")
    }

    @Test
    fun `VoxelProjection emits positions flattened to col,row (G8)`() {
        val m = Model("Vox")
        val vm = VoxelTestModel(m)
        val sink = CaptureSink(); m.animationSink = sink
        vm.place()
        val moves = sink.events.filterIsInstance<AnimationEvent.AgentPositionChanged>()
        assertTrue(moves.any { it.x == 1.0 && it.y == 2.0 && it.z == 0.0 },
            "voxel placeAt emits (col,row,layer) flattened to x,y with layer as z: $moves")
    }

    /** A minimal agent model that places one permanent agent in a voxel grid. */
    private class VoxelTestModel(parent: ModelElement) : AgentModel(parent, "vox") {
        private val crew = Context<PermanentAgent>("crew")
        private val proj = VoxelProjection<PermanentAgent>(crew, columns = 5, rows = 5, layers = 3, name = "airspace")
        private val drone = PermanentAgent("d1")
        fun place() { crew.add(drone); proj.placeAt(drone, Voxel(1, 2, 0)) }
    }
}
