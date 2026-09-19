package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  What an active fleet adds to an animation trace, and what it costs when nobody is watching.
 *
 *  Movement, vehicle state and zone occupancy are already emitted by the body, so a recording of a
 *  passive run and an active one look the same on a canvas: carts going places. The one thing an
 *  active fleet does that has no passive equivalent -- and that a viewer cannot infer from watching
 *  -- is that a **decision was made**, by an object, at an instant. That is the single tag this
 *  subsystem adds.
 *
 *  Two properties, for different reasons. Emission is *gated* on an active sink, so a run without
 *  animation pays a field read; a regression there is invisible, because the model would still be
 *  right and merely slower. And the tag round-trips through the trace format: a tag that serializes
 *  but will not parse makes a recording unreplayable, and nothing about writing it would reveal
 *  that.
 */
class AgvAnimationEmissionTest {

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean get() = true
        override fun emit(event: AnimationEvent) {
            events.add(event)
        }
    }

    /** Claims to be inactive, and fails loudly if anything is emitted to it anyway. */
    private class RefusingSink : AnimationSink {
        var emissions = 0
        override val isActive: Boolean get() = false
        override fun emit(event: AnimationEvent) {
            emissions++
        }
    }

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        override fun initialize() {
            repeat(3) { i -> activate(Part().p, timeUntilActivation = i * 80.0) }
        }
    }

    @Test
    @DisplayName("An assignment reaches the sink, carrying who, what, and where from and to")
    fun assignmentsAreEmitted() {
        val m = Model("AgvAnimation")
        val shop = Shop(m)
        val sink = CollectingSink()
        m.animationSink = sink
        m.numberOfReplications = 1
        m.lengthOfReplication = 400.0
        m.simulate()

        val made = sink.events.filterIsInstance<AnimationEvent.AgvAssignmentMade>()
        assertEquals(3, made.size, "one event per assignment was expected, got ${made.size}")

        val first = made.first()
        assertEquals("Agv", first.systemName)
        assertEquals("Cart1", first.vehicleName)
        assertEquals(SimpleAgvNetwork.ENTRY_STATION, first.origin)
        assertEquals(SimpleAgvNetwork.EXIT_STATION, first.destination)
        assertTrue(first.taskId > 0, "the event should identify the task it is about")
        assertEquals(3, made.map { it.taskId }.distinct().size,
            "three assignments of three different tasks should carry three ids")

        // The instants agree with the counter, so the trace and the statistics tell one story.
        assertEquals(
            made.size.toDouble(),
            shop.agv.dispatcher.numAssignmentsMade.value,
            "the trace and the assignment counter disagree about how many decisions were made"
        )
    }

    @Test
    @DisplayName("Nothing is emitted when no sink is listening")
    fun emissionIsGated() {
        val m = Model("AgvAnimationOff")
        Shop(m)
        val sink = RefusingSink()
        m.animationSink = sink
        m.numberOfReplications = 1
        m.lengthOfReplication = 400.0
        m.simulate()

        assertEquals(0, sink.emissions,
            "events were emitted to an inactive sink. The model would still be correct and merely " +
                    "slower, which is why this needs a test rather than being noticed")
    }

    @Test
    @DisplayName("The tag round-trips through the trace format")
    fun theTagRoundTrips() {
        // Pinned as serialized text, not merely constructed. The string tag is what a renderer, a
        // recorded trace and any tooling built on either agree upon; the Kotlin class name can be
        // refactored freely underneath it, and a rename that would break an existing recording
        // should fail here rather than be discovered by a viewer silently ignoring an unknown event.
        val event = AnimationEvent.AgvAssignmentMade(
            simTime = 12.5,
            systemName = "Agv",
            vehicleName = "Cart1",
            taskId = 7L,
            origin = "EntryStation",
            destination = "ExitStation"
        )
        val line = AnimationEvent.encodeToLine(event)
        assertTrue(line.contains("\"AgvAssignmentMade\""),
            "the serialized tag is not the documented one: $line")

        val back = assertNotNull(AnimationEvent.decodeFromLine(line),
            "the event serialized but would not parse, which makes a recording unreplayable")
        assertEquals(event, back, "the event did not survive a round trip: $line")
    }
}
