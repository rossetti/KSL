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
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueListenerIfc
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResource
import ksl.modeling.spatial.MovableResourcePoolWithQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  A seize that waited before it was served: the name of its allocation, whatever kind of thing it seized and
 *  however it was served; a `FINISH` preemptor the model resumes while its reservation still collects units; and
 *  the trace of a seize trigger whose wait ends while its request is being queued.
 */
class WaitedSeizeEdgesTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        val locA = dm.Location("A")
        val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val plans = mutableListOf<Shop.() -> Unit>()
        val log = mutableListOf<String>()

        inner class Actor(tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }

            /** Moves this entity's waiting request for [from] to [to], as a model's own code might. */
            fun reroute(from: ResourceWithQ, to: ResourceWithQ) {
                val request = from.myWaitingQ.first { it.entity === this } as ResourceRequest
                moveRequestToResource(request, from.myWaitingQ, to, RESUME_PRIORITY)
            }
        }

        fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = KSLEvent.DEFAULT_PRIORITY)
        }

        override fun initialize() {
            log.clear()
            for (p in plans) p()
        }
    }

    /** [build] makes the model's elements before the run, and returns what each replication starts. */
    private fun run(model: Model = Model("Waited${System.nanoTime()}", autoCSVReports = false), build: Shop.() -> (Shop.() -> Unit)): Shop {
        val shop = Shop(model)
        shop.plans.add(shop.build())
        model.lengthOfReplication = 100.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        return shop
    }

    @Test
    @DisplayName("a seize of a resource that waited names its allocation with the seize's suspension name")
    fun resourceSeizeThatWaited() {
        val shop = run {
            val r = ResourceWithQ(this, "R")
            return@run {
            actor("A") { val a = seize(r); delay(5.0); release(a) }
            actor("B") { delay(1.0); val a = seize(r, suspensionName = "Get worker"); log.add("${a.name}@$time"); release(a) }
            }
        }
        assertEquals(listOf("Get worker@5.0"), shop.log)
    }

    @Test
    @DisplayName("a seize of a resource pool that waited names its allocation with the seize's suspension name")
    fun poolSeizeThatWaited() {
        val shop = run {
            val pool = ResourcePoolWithQ(this, listOf(Resource(this, "RA")), name = "P")
            return@run {
            actor("A") { val a = seize(pool); delay(5.0); release(a) }
            actor("B") { delay(1.0); val a = seize(pool, suspensionName = "Get worker"); log.add("${a.name}@$time"); release(a) }
            }
        }
        assertEquals(listOf("Get worker@5.0"), shop.log)
    }

    @Test
    @DisplayName("a seize of a movable resource pool that waited names its allocation with the seize's suspension name")
    fun movablePoolSeizeThatWaited() {
        val shop = run {
            val pool = MovableResourcePoolWithQ(this, listOf(MovableResource(this, locA, speed, "T")), speed, RequestQ(this, "FQ"), "F")
            return@run {
            actor("A") { val a = seize(pool, requestLocation = locA); delay(5.0); release(a) }
            actor("B") {
                delay(1.0)
                val a = seize(pool, requestLocation = locA, suspensionName = "Get worker")
                log.add("${a.name}@$time")
                release(a)
            }
            }
        }
        assertEquals(listOf("Get worker@5.0"), shop.log)
    }

    @Test
    @DisplayName("a waiting seize moved to a resource that serves it at once names its allocation with the seize's suspension name")
    fun movedSeize() {
        val shop = run {
            val r1 = ResourceWithQ(this, "R1")
            val r2 = ResourceWithQ(this, "R2")
            return@run {
            actor("A") { val a = seize(r1); delay(50.0); release(a) }
            val b = actor("B") { delay(1.0); val a = seize(r1, suspensionName = "Get worker"); log.add("${a.name}@$time"); release(a) }
            at(3.0) { b.reroute(r1, r2) }
            }
        }
        assertEquals(listOf("Get worker@3.0"), shop.log)
    }

    @Test
    @DisplayName("a FINISH preemptor whose reservation completes names its allocation with the preempt's suspension name")
    fun finishReservationCompleted() {
        val shop = run {
            val r = ResourceWithQ(this, "R")
            return@run {
            actor("L", 9) { val a = seize(r); delay(5.0); release(a) }
            actor("F", 1) {
                delay(1.0)
                val a = preempt(r, 1, rule = InterruptRule.FINISH, suspensionName = "Take over")
                log.add("${a.name}@$time")
                release(a)
            }
            }
        }
        assertEquals(listOf("Take over@5.0"), shop.log)
    }

    /**
     *  R has 2, held by L1 to 5 and L2 to 20. F preempts both under FINISH at 1 and reserves them; at 5 it has
     *  collected L1's unit and still needs L2's. The model resumes F at 6: it waits on, its reservation as it was,
     *  and takes both units when L2 releases at 20.
     */
    @Test
    @DisplayName("a FINISH preemptor the model resumes while its reservation still collects units waits on and is served when it completes")
    fun finishPreemptorResumedMidReservation() {
        var collectedAt10 = -1
        val shop = run {
            val r = ResourceWithQ(this, "R", capacity = 2)
            lateinit var f: Shop.Actor
            return@run {
            actor("L1", 9) { val a = seize(r); delay(5.0); release(a) }
            actor("L2", 9) { val a = seize(r); delay(20.0); release(a) }
            f = actor("F", 1) { delay(1.0); val a = preempt(r, 2, rule = InterruptRule.FINISH); log.add("F got ${a.amount}@$time"); release(a) }
            at(6.0) { f.resumeProcess() }
            at(10.0) { collectedAt10 = r.numBusy }
            }
        }
        assertEquals(listOf("F got 2@20.0"), shop.log)
        assertEquals(1, collectedAt10, "R's busy units at 10: only L2's, L1's unit held for F")
    }

    /**
     *  W waits for the first of R1 (held by H1) and R2 (held by H2). As W's request enters R2's queue, a listener of
     *  that queue raises R1's capacity, which serves W at R1 and withdraws the request at R2 before it is announced
     *  as queued. The trace never shows that request queued after it was withdrawn.
     */
    @Test
    @DisplayName("a seize trigger's request withdrawn as it is queued is not reported queued after it was withdrawn")
    fun withdrawnAsItIsQueued() {
        val events = mutableListOf<AnimationEvent>()
        val model = Model("WaitedTrace${System.nanoTime()}", autoCSVReports = false)
        model.animationSink = object : AnimationSink {
            override val isActive: Boolean = true
            override fun emit(event: AnimationEvent) { events.add(event) }
        }
        lateinit var w: Shop.Actor
        run(model) {
            val r1 = ResourceWithQ(this, "R1")
            val r2 = ResourceWithQ(this, "R2")
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
                val result = waitForFirst(r1.seizeTrigger(), r2.seizeTrigger())
                @Suppress("UNCHECKED_CAST")
                release(result.valueOf(result.trigger as WaitTrigger<Allocation>))
            }
            }
        }
        val atR2 = events.filter { it.entityIdOrNull == w.id }.mapNotNull {
            when (it) {
                is AnimationEvent.SeizeQueued -> if (it.resourceName == "R2") "queued" else null
                is AnimationEvent.SeizeWithdrawn -> if (it.resourceName == "R2") "withdrawn" else null
                else -> null
            }
        }
        assertTrue("withdrawn" in atR2, "$atR2")
        assertTrue("queued" !in atR2.drop(atR2.indexOf("withdrawn") + 1), "reported queued after it was withdrawn: $atR2")
    }

    @Test
    @DisplayName("a seize trigger that fires at once names its allocation with its wait's suspension name")
    fun seizeTriggerFiredAtOnce() {
        val shop = run {
            val r = ResourceWithQ(this, "R")
            val pool = ResourcePoolWithQ(this, listOf(Resource(this, "RA")), name = "P")
            return@run {
            actor("W") {
                val a = seizeOrTimeout(r, timeout = 5.0, suspensionName = "Grab").valueOrNull()!!
                log.add("R ${a.name}")
                release(a)
                val result = waitForFirst(pool.seizeTrigger(), timeout = 5.0, suspensionName = "Grab pool")
                @Suppress("UNCHECKED_CAST")
                val pa = result.valueOf(result.trigger as WaitTrigger<ResourcePoolAllocation>)
                log.add("P ${pa.name}")
                release(pa)
            }
            }
        }
        assertEquals(listOf("R Grab", "P Grab pool"), shop.log)
    }

    /**
     *  A holds R to 5. B seizes R at 1 and waits; the model asks at 1 for B to resume at 5, after A's release in the
     *  same instant. The release finds B with a resume already coming, so it passes B over; B takes the unit when it
     *  resumes.
     */
    @Test
    @DisplayName("a waiting seize the model has asked to resume later is passed over by a release before that resume")
    fun waitingSeizeWithAResumeComing() {
        val shop = run {
            val r = ResourceWithQ(this, "R")
            lateinit var b: Shop.Actor
            return@run {
            actor("A") { val a = seize(r); delay(5.0); release(a) }
            b = actor("B") { delay(1.0); val a = seize(r); log.add("B got@$time"); release(a) }
            at(2.0) { b.resumeProcess(3.0, priority = KSLEvent.DEFAULT_PRIORITY + 50) }
            }
        }
        assertEquals(listOf("B got@5.0"), shop.log)
    }

    /**
     *  As above for a FINISH preemptor: R has 2, held by L1 to 5 and L2 to 20; F reserves both at 1. At 6 the model
     *  asks for F to resume at 20, after L2's release in the same instant. That release passes F's reservation over;
     *  when F resumes, its reservation completes from the unit left free and it takes both units.
     */
    @Test
    @DisplayName("a FINISH preemptor the model has asked to resume later is completed when it resumes, not by a release before it")
    fun finishPreemptorWithAResumeComing() {
        val shop = run {
            val r = ResourceWithQ(this, "R", capacity = 2)
            lateinit var f: Shop.Actor
            return@run {
            actor("L1", 9) { val a = seize(r); delay(5.0); release(a) }
            actor("L2", 9) { val a = seize(r); delay(20.0); release(a) }
            f = actor("F", 1) { delay(1.0); val a = preempt(r, 2, rule = InterruptRule.FINISH); log.add("F got ${a.amount}@$time"); release(a) }
            at(6.0) { f.resumeProcess(14.0, priority = KSLEvent.DEFAULT_PRIORITY + 50) }
            }
        }
        assertEquals(listOf("F got 2@20.0"), shop.log)
    }

    /**
     *  B waits on R1 and is moved at 3 to R2, which is busy. A listener of R2's queue records what the request it
     *  sees entering asks for: R2, the resource it was moved to.
     */
    @Test
    @DisplayName("a request moved into a queue asks for its new resource as it enters that queue")
    fun movedRequestAsItEntersItsNewQueue() {
        val shop = run {
            val r1 = ResourceWithQ(this, "R1")
            val r2 = ResourceWithQ(this, "R2")
            r2.myWaitingQ.addQueueListener(object : QueueListenerIfc<ProcessModel.Entity.Request> {
                override fun update(status: Queue.Status, queue: Queue<ProcessModel.Entity.Request>, qObject: ProcessModel.Entity.Request?) {
                    if (status == Queue.Status.ENQUEUED && qObject?.entity?.name == "B") log.add("entered asking for ${qObject.resource.name}")
                }
            })
            return@run {
            actor("A1") { val a = seize(r1); delay(50.0); release(a) }
            actor("A2") { val a = seize(r2); delay(50.0); release(a) }
            val b = actor("B") { delay(1.0); val a = seize(r1); release(a) }
            at(3.0) { b.reroute(r1, r2) }
            }
        }
        assertEquals(listOf("entered asking for R2"), shop.log)
    }
}
