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
import ksl.animation.entityIdOrNull
import ksl.animation.emitters.ResourceAnimationEmitter
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueListenerIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Edges of serving a request at the decision: what follows the service of a pool request at another member, a
 *  wait's other seize trigger once the wait is won, the name of an allocation served as its wait registers, and the
 *  trace of a seize trigger whose own arrival serves another trigger of its wait.
 */
class ServedRequestEdgesTest {

    /** A queue rule that serves only the head of the queue, whatever it asks for. */
    private fun headOnly() = RequestSelectionRuleIfc { _, requestQ -> listOfNotNull(requestQ.firstOrNull()) }

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val plans = mutableListOf<Shop.() -> Unit>()
        val log = mutableListOf<String>()

        inner class Actor(tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = KSLEvent.DEFAULT_PRIORITY)
        }

        override fun initialize() {
            log.clear()
            for (p in plans) p()
        }
    }

    /** [build] makes the model's elements before the run, and returns what each replication starts. */
    private fun run(model: Model = Model("Edges${System.nanoTime()}", autoCSVReports = false), build: Shop.() -> (Shop.() -> Unit)): Shop {
        val shop = Shop(model)
        shop.plans.add(shop.build())
        model.lengthOfReplication = 30.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        return shop
    }

    /**
     *  X (2 units) and Y (1) form a pool whose queue serves only its head. H holds both units of X to 10. R asks the
     *  pool for 2 at 1 and waits (Y alone cannot serve it); Q asks for 1 of Y through the same queue at 2 and waits
     *  behind R. At 10 X's release serves R from X, leaving Y's unit free with Q now at the head: Q is served then.
     */
    @Test
    @DisplayName("a pool request served from one member leaves another member's free units offered to the request the rule chooses next")
    fun poolRequestServedFromOneMember() {
        val shop = run {
            val q = RequestQ(this, "Q").also { it.requestSelectionRule = headOnly() }
            val x = ResourceWithQ(this, "X", capacity = 2)
            val y = ResourceWithQ(this, "Y", capacity = 1)
            val pool = ResourcePoolWithQ(this, listOf(x, y), q, "P")
            return@run {
            actor("H") { val a = seize(x, 2); delay(10.0); release(a) }
            actor("R") { delay(1.0); val a = seize(pool, 2); log.add("R got@$time"); delay(5.0); release(a) }
            actor("Q") { delay(2.0); val a = seize(y, 1, queue = q); log.add("Q got@$time"); delay(1.0); release(a) }
            }
        }
        assertEquals(listOf("R got@10.0", "Q got@10.0"), shop.log)
    }

    /**
     *  W waits up to 2 for the first of R1 and R2, each held by another to 20. When the time runs out, the wait releases
     *  W's request at R1 first; a listener of R1's queue then raises R2's capacity, which offers R2's units while W's
     *  request there is still queued. The wait has been won by its timer, so that request is passed over: nothing is
     *  allocated to W, and R2's new unit stays free for whoever asks next.
     */
    @Test
    @DisplayName("once a wait is won, its other seize triggers are passed over: an offer made as they are released allocates nothing to them")
    fun otherSeizeTriggerAfterTheWaitIsWon() {
        var busyR2AtEnd = -1
        val shop = run {
            val r1 = ResourceWithQ(this, "R1")
            val r2 = ResourceWithQ(this, "R2")
            lateinit var w: Shop.Actor
            r1.myWaitingQ.addQueueListener(object : QueueListenerIfc<ProcessModel.Entity.Request> {
                override fun update(status: Queue.Status, queue: Queue<ProcessModel.Entity.Request>, qObject: ProcessModel.Entity.Request?) {
                    if (status == Queue.Status.DEQUEUED && qObject?.entity === w) r2.changeCapacity(r2.CapacityChangeNotice(capacity = 2))
                }
            })
            return@run {
            actor("H1") { val a = seize(r1); delay(20.0); release(a) }
            actor("H2") { val a = seize(r2); delay(20.0); release(a) }
            w = actor("W") {
                delay(1.0)
                val result = waitForFirst(r1.seizeTrigger(), r2.seizeTrigger(), timeout = 2.0)
                log.add("W ${if (result.timedOut) "timed out" else "won"}@$time")
            }
            at(5.0) { busyR2AtEnd = r2.numBusy }
            }
        }
        assertEquals(listOf("W timed out@3.0"), shop.log)
        assertEquals(1, busyR2AtEnd, "R2's busy units: only H2's")
    }

    /**
     *  A seize trigger whose queue has a selection rule registers ahead of the wait's other triggers and is served as it
     *  registers when its units are free; its allocation carries the suspension name the wait was given, as when it is
     *  served later.
     */
    @Test
    @DisplayName("a seize trigger served as its wait registers names its allocation with the wait's suspension name")
    fun allocationServedAtRegistrationIsNamed() {
        var name: String? = "not set"
        run {
            val r = ResourceWithQ(this, "R").also { it.myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { res, q -> q.filter { it.resource == res } } }
            return@run {
            actor("W") {
                val result = seizeOrTimeout(r, timeout = 5.0, suspensionName = "grab")
                val a = result.valueOrNull()!!
                name = a.name
                release(a)
            }
            }
        }
        assertEquals("grab", name)
    }

    /**
     *  R's queue has a rule that chooses only once two requests wait. W waits for the first of two seize triggers of R
     *  through it: the first waits, and the second's arrival lets the rule choose the first, which is served and wins,
     *  withdrawing the second. The trace shows the second withdrawn, and never waiting afterwards.
     */
    @Test
    @DisplayName("a seize trigger whose own arrival serves another trigger of its wait is not reported waiting after it is withdrawn")
    fun triggerWithdrawnByItsOwnArrivalIsNotReportedWaiting() {
        val events = mutableListOf<AnimationEvent>()
        val model = Model("EdgesTrace${System.nanoTime()}", autoCSVReports = false)
        model.animationSink = object : AnimationSink {
            override val isActive: Boolean = true
            override fun emit(event: AnimationEvent) { events.add(event) }
        }
        lateinit var w: Shop.Actor
        run(model) {
            val r = ResourceWithQ(this, "R", capacity = 2)
            r.addAllocationListener(ResourceAnimationEmitter(r))
            r.myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { res, q ->
                val mine = q.filter { it.resource == res }
                if (mine.size >= 2) mine else emptyList()
            }
            return@run {
            w = actor("W") {
                val first = r.seizeTrigger()
                val second = r.seizeTrigger()
                val result = waitForFirst(first, second)
                @Suppress("UNCHECKED_CAST")
                release(result.valueOf(result.trigger as WaitTrigger<Allocation>))
            }
            }
        }
        val mine = events.filter { it.entityIdOrNull == w.id }.mapNotNull {
            when (it) {
                is AnimationEvent.SeizeQueued -> "queued"
                is AnimationEvent.SeizeWaiting -> "waiting"
                is AnimationEvent.SeizeWithdrawn -> "withdrawn"
                is AnimationEvent.SeizeAllocated -> "allocated"
                else -> null
            }
        }
        val withdrawnAt = mine.indexOf("withdrawn")
        assertTrue(withdrawnAt >= 0, "$mine")
        assertTrue("waiting" !in mine.drop(withdrawnAt + 1), "reported waiting after it was withdrawn: $mine")
    }

    /**
     *  W waits for the first of R1 (held by H) and R2 (held by H2). As W's request enters R2's queue, a listener of
     *  that queue raises R1's capacity, which serves W's request at R1: the wait is won while its second seize trigger
     *  is still placing its request. That request leaves R2's queue, and W takes R1.
     */
    @Test
    @DisplayName("a wait won while a seize trigger is placing its request leaves that request out of its queue")
    fun waitWonWhileATriggerPlacesItsRequest() {
        var queuedAtR2 = -1
        val shop = run {
            val r1 = ResourceWithQ(this, "R1")
            val r2 = ResourceWithQ(this, "R2")
            lateinit var w: Shop.Actor
            r2.myWaitingQ.addQueueListener(object : QueueListenerIfc<ProcessModel.Entity.Request> {
                override fun update(status: Queue.Status, queue: Queue<ProcessModel.Entity.Request>, qObject: ProcessModel.Entity.Request?) {
                    if (status == Queue.Status.ENQUEUED && qObject?.entity === w) r1.changeCapacity(r1.CapacityChangeNotice(capacity = 2))
                }
            })
            return@run {
            actor("H1") { val a = seize(r1); delay(20.0); release(a) }
            actor("H2") { val a = seize(r2); delay(20.0); release(a) }
            w = actor("W") {
                delay(1.0)
                val first = r1.seizeTrigger()
                val result = waitForFirst(first, r2.seizeTrigger())
                log.add("W won ${if (result.trigger === first) "R1" else "R2"}@$time")
                @Suppress("UNCHECKED_CAST")
                release(result.valueOf(result.trigger as WaitTrigger<Allocation>))
            }
            at(5.0) { queuedAtR2 = r2.myWaitingQ.size }
            }
        }
        assertEquals(listOf("W won R1@1.0"), shop.log)
        assertEquals(0, queuedAtR2, "requests left in R2's queue")
    }
}
