/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.entity

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.AnimationTraceHeader
import ksl.animation.TraceFileReader
import ksl.animation.emitters.ResourceAnimationEmitter
import ksl.animation.entityIdOrNull
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The trace of a seize request that leaves its queue without being served. A reader of the trace
 *  keeps the members of a resource's queue from it: `SeizeQueued` puts an entity in, and
 *  `SeizeAllocated` or `SeizeWithdrawn` takes it out. A terminated entity's request is reported by
 *  `EntityTerminated` alone.
 */
class SeizeWithdrawnTraceTest {

    private class Clinic(parent: ModelElement) : ProcessModel(parent, "clinic") {
        val bed = ResourceWithQ(this, "Bed")
        val chair = ResourceWithQ(this, "Chair")
        val alarm = Signal(this, "Alarm")
        val setups = mutableListOf<Clinic.() -> Unit>()

        init {
            // the allocation events come from a listener on the resource, as an animation capture attaches one
            for (r in listOf(bed, chair)) r.addAllocationListener(ResourceAnimationEmitter(r))
        }

        inner class Actor(tag: String, val movesTo: ResourceWithQ? = null, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }

            override fun resourceBecameInactiveWhileWaitingInQueueWithSeizeRequest(queue: RequestQ, resource: Resource, request: Request) {
                val target = movesTo ?: return
                moveRequestToResource(request as ResourceRequest, queue, target, RESUME_PRIORITY)
            }
        }

        fun actor(tag: String, movesTo: ResourceWithQ? = null, body: suspend KSLProcessBuilder.() -> Unit): Actor =
            Actor(tag, movesTo, body).also { activate(it.p) }

        fun holder(tag: String, r: ResourceWithQ, hold: Double): Actor = actor(tag) {
            val a = seize(r)
            delay(hold)
            release(a)
        }

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = KSLEvent.DEFAULT_PRIORITY)
        }

        override fun initialize() {
            for (s in setups) s()
        }
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    private fun run(setup: Clinic.() -> Unit): CollectingSink {
        val sink = CollectingSink()
        val model = Model("Clinic${System.nanoTime()}", autoCSVReports = false)
        model.animationSink = sink
        val m = Clinic(model)
        m.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return sink
    }

    /** The seize events of [entity] in the order they were emitted, with what ended its wait. */
    private fun CollectingSink.seizesOf(entity: ProcessModel.Entity): List<String> =
        events.filter { it.entityIdOrNull == entity.id }.mapNotNull {
            when (it) {
                is AnimationEvent.SeizeQueued -> "queued ${it.resourceName} in ${it.queueName}@${it.simTime}"
                is AnimationEvent.SeizeWithdrawn -> "withdrawn ${it.resourceName} from ${it.queueName}@${it.simTime}"
                is AnimationEvent.SeizeAllocated -> "allocated ${it.resourceName}@${it.simTime}"
                is AnimationEvent.WaitTimedOut -> "timed out@${it.simTime}"
                is AnimationEvent.SignalReceived -> "signal@${it.simTime}"
                is AnimationEvent.EntityTerminated -> "terminated@${it.simTime}"
                else -> null
            }
        }

    @Test
    @DisplayName("a seize that loses a wait to a signal is withdrawn from its queue at the signal")
    fun losesToASignal() {
        lateinit var w: ProcessModel.Entity
        val sink = run {
            holder("X", bed, hold = 10.0)
            w = actor("W") { waitForFirst(bed.seizeTrigger(), alarm.trigger()) }
            at(3.0) { alarm.signalAll() }
        }
        assertEquals(
            listOf("queued Bed in Bed:Q@0.0", "withdrawn Bed from Bed:Q@3.0", "signal@3.0"),
            sink.seizesOf(w)
        )
    }

    // A seize trigger's win holds once its units are taken, as the entity resumes; the wait's other triggers are
    // withdrawn then, in the same instant, so the losing seize is withdrawn after the winner is allocated.
    @Test
    @DisplayName("of two seizes in one wait, the one served is allocated and then the other withdrawn")
    fun losesToAnotherSeize() {
        lateinit var w: ProcessModel.Entity
        val sink = run {
            holder("X", bed, hold = 10.0)
            holder("Y", chair, hold = 4.0)
            w = actor("W") {
                val r = waitForFirst(bed.seizeTrigger(), chair.seizeTrigger())
                delay(1.0)
                @Suppress("UNCHECKED_CAST")
                release(r.valueOf(r.trigger as WaitTrigger<Allocation>))
            }
        }
        assertEquals(
            listOf("queued Bed in Bed:Q@0.0", "queued Chair in Chair:Q@0.0", "allocated Chair@4.0", "withdrawn Bed from Bed:Q@4.0"),
            sink.seizesOf(w)
        )
    }

    @Test
    @DisplayName("a seize whose time runs out is withdrawn from its queue, and then the time-out is reported")
    fun timesOut() {
        lateinit var w: ProcessModel.Entity
        val sink = run {
            holder("X", bed, hold = 10.0)
            w = actor("W") { seizeOrTimeout(bed, timeout = 2.0) }
        }
        assertEquals(
            listOf("queued Bed in Bed:Q@0.0", "withdrawn Bed from Bed:Q@2.0", "timed out@2.0"),
            sink.seizesOf(w)
        )
    }

    @Test
    @DisplayName("a request moved to another resource is withdrawn from the queue it leaves and queued where it goes")
    fun moved() {
        lateinit var w: ProcessModel.Entity
        val sink = run {
            holder("X", bed, hold = 10.0)
            holder("Y", chair, hold = 12.0)
            w = actor("W", movesTo = chair) { val a = seize(bed); delay(1.0); release(a) }
            // the bed goes off shift when X, who holds it, is done at 10: W's request moves then
            at(2.0) { bed.goOffShift() }
        }
        assertEquals(
            listOf("queued Bed in Bed:Q@0.0", "withdrawn Bed from Bed:Q@10.0", "queued Chair in Chair:Q@10.0", "allocated Chair@12.0"),
            sink.seizesOf(w)
        )
    }

    @Test
    @DisplayName("a served seize is never withdrawn")
    fun servedIsNotWithdrawn() {
        lateinit var w: ProcessModel.Entity
        val sink = run {
            holder("X", bed, hold = 3.0)
            w = actor("W") { val a = seize(bed); delay(1.0); release(a) }
        }
        assertEquals(listOf("queued Bed in Bed:Q@0.0", "allocated Bed@3.0"), sink.seizesOf(w))
    }

    @Test
    @DisplayName("a waiter terminated in the queue is reported by its termination, not as withdrawn")
    fun terminated() {
        lateinit var w: Clinic.Actor
        val sink = run {
            holder("X", bed, hold = 10.0)
            w = actor("W") { waitForFirst(bed.seizeTrigger(), alarm.trigger()) }
            at(3.0) { w.terminateProcess() }
        }
        assertEquals(listOf("queued Bed in Bed:Q@0.0", "terminated@3.0"), sink.seizesOf(w))
    }

    @Test
    @DisplayName("the event encodes and decodes, reads back from a trace file, and is keyed to its entity")
    fun roundTrip() {
        val event = AnimationEvent.SeizeWithdrawn(12.5, 42L, "Bed", "Bed:Q")
        assertEquals(event, AnimationEvent.decodeFromLine(AnimationEvent.encodeToLine(event)))
        assertEquals(42L, event.entityIdOrNull)
        assertTrue(AnimationEvent.encodeToLine(event).contains("\"event\":\"SeizeWithdrawn\""))
        val trace = Files.createTempFile("withdrawn", ".atf")
        try {
            Files.writeString(trace, AnimationTraceHeader().encodeToLine() + "\n" + AnimationEvent.encodeToLine(event) + "\n")
            val (_, events) = TraceFileReader.readAll(trace)
            assertEquals(listOf<AnimationEvent>(event), events)
        } finally {
            Files.deleteIfExists(trace)
        }
    }
}
