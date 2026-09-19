package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem

import ksl.modeling.agent.AgentModel
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  V1 of the AGV plan's Phase 0. The one Phase 0 finding that could change the design, so it is
 *  verified before anything is built on it.
 *
 *  The design has an entity of the *modeller's* `ProcessModel` suspend in a `HoldQueue` owned by
 *  `AgvSystem`, which is an `AgentModel` and therefore a `ProcessModel` in its own right. The
 *  passive subsystem does the same thing successfully, but its transport system is a plain
 *  `ModelElement`, so the precedent is not exact: an entity held by a *second* process model is a
 *  configuration neither subsystem has actually exercised.
 *
 *  Three things are asserted, and the second is the one that matters:
 *
 *  1. The hold and the resume work across the model boundary at all.
 *  2. `dispose(entity)` is called on the entity's **owning** model, not on the model that owns the
 *     queue it waited in. An entity belongs to the process model that created it for its whole
 *     life; a queue is somewhere it visits. If this were the other way round, `AgvSystem` would
 *     receive disposal callbacks for entities it knows nothing about, and any modeller who
 *     overrode `dispose` to recycle entities would silently stop being called.
 *  3. The entity is out of the queue afterwards, so the wait leaves nothing behind.
 *
 *  If any of this had failed, the fallback recorded in the plan is for `AgvSystem` to own a plain
 *  `ModelElement` that holds the queues instead.
 */
class CrossModelHoldQueueTest {

    /** Stands in for `AgvSystem`: an `AgentModel` that owns a hold queue other models' entities
     *  wait in, and that rings the bell on a timer. */
    private class Holder(parent: ModelElement) : AgentModel(parent, "Holder") {

        val holdQ = HoldQueue(this, "Holder:HoldQ")

        val disposedHere = mutableListOf<String>()

        /** If this ever fires, the entity's disposal is being routed to the wrong model. */
        override fun dispose(entity: Entity) {
            disposedHere.add(entity.name)
        }

        /** The "bell": whatever is waiting is woken 5 time units after the model starts. */
        override fun initialize() {
            schedule(::ring, 5.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun ring(event: KSLEvent<Nothing>) {
            holdQ.removeAllAndResume()
        }
    }

    /** The modeller's own process model. Its entity suspends in a queue it does not own. */
    private class Shop(parent: ModelElement, private val holder: Holder) : ProcessModel(parent, "Shop") {

        val disposedHere = mutableListOf<String>()
        val log = mutableListOf<String>()

        override fun dispose(entity: Entity) {
            disposedHere.add(entity.name)
        }

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                log.add("held at $time in ${holder.holdQ.name}")
                hold(holder.holdQ, suspensionName = "waitingOnAnotherModel")
                log.add("resumed at $time; queue size now ${holder.holdQ.size}")
            }
        }

        override fun initialize() {
            activate(Part().p)
        }
    }

    @Test
    @DisplayName("V1: an entity holds in another process model's queue, and is disposed by its own")
    fun entityHoldsInAnotherProcessModelsQueue() {
        val m = Model("CrossModelHold")
        val holder = Holder(m)
        val shop = Shop(m, holder)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        // 1. The hold and the resume worked across the model boundary.
        assertEquals(
            listOf("held at 0.0 in Holder:HoldQ", "resumed at 5.0; queue size now 0"),
            shop.log,
            "the entity did not suspend in and resume from the other model's hold queue"
        )

        // 2. Disposal follows ownership, not the queue. This is the assertion the design rests on.
        assertEquals(listOf("Part"), shop.disposedHere,
            "the entity's own process model did not receive dispose()")
        assertTrue(holder.disposedHere.isEmpty(),
            "the queue's owner received dispose() for an entity it did not create: " +
                    "${holder.disposedHere}")

        // 3. Nothing left behind.
        assertEquals(0, holder.holdQ.size, "the entity was left in the hold queue")
    }
}
