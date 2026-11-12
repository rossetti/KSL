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
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResourceWithQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Seizing a resource with a time limit: reneging. While it waits, the request is in the queue as a
 *  `seize` request is, and is served in the same way; when the time runs out it leaves without
 *  service, its wait recorded unless asked not to, and nothing is allocated. A promise broken between
 *  the wake and the resume makes it wait again for the time left.
 */
class TimedSeizeTest {

    private class SeizeModel(parent: ModelElement, capacity: Int, discipline: Queue.Discipline) : ProcessModel(parent, "timedSeize") {
        private val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        val locA = dm.Location("A")
        val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val r = ResourceWithQ(this, "R", capacity, RequestQ(this, "RQ", discipline))
        val r2 = ResourceWithQ(this, "R2")
        val truck = MovableResourceWithQ(this, locA, speed, name = "Truck")
        val q: RequestQ get() = r.myWaitingQ
        val log = mutableListOf<String>()
        val setups = mutableListOf<SeizeModel.() -> Unit>()
        val actors = mutableMapOf<String, Actor>()

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.(Actor) -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body(this@Actor) }

            fun moveTo(target: ResourceWithQ, waitStats: Boolean = false) {
                val request = q.toList().first { it.entity === this }
                moveRequestToResource(request as ResourceRequest, q, target, RESUME_PRIORITY, waitStats)
            }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.(Actor) -> Unit): Actor =
            Actor(tag, body).also { actors[tag] = it; activate(it.p) }

        /** Holds [n] units of R from [from] for [hold], releasing at [releasePriority]. */
        fun holder(tag: String, from: Double = 0.0, hold: Double, n: Int = 1, endPriority: Int = DELAY_PRIORITY,
                   releasePriority: Int = RELEASE_PRIORITY, after: () -> Unit = {}): Actor = actor(tag) {
            delay(from)
            val a = seize(r, n)
            delay(hold, endPriority)
            release(a, releasePriority)
            after()
        }

        /** Seizes R with [timeout] at [from], logs the outcome, and holds a unit for [hold] if served. */
        fun timed(tag: String, from: Double, timeout: Double, hold: Double = 1.0, n: Int = 1,
                  timeoutPriority: Int = TIMEOUT_PRIORITY, record: Boolean = true): Actor = actor(tag) {
            delay(from)
            val result = seizeOrTimeout(r, n, timeout, timeoutPriority = timeoutPriority, recordRenegedWait = record)
            note(tag, result)
            result.valueOrNull()?.let { delay(hold); release(it) }
        }

        fun untimed(tag: String, from: Double, hold: Double = 1.0, n: Int = 1): Actor = actor(tag) {
            delay(from)
            val a = seize(r, n)
            log.add("$tag:served@$time")
            delay(hold)
            release(a)
        }

        fun note(tag: String, result: WaitResult<Allocation>) {
            log.add(if (result.timedOut) "$tag:timedOut waited=${result.timeWaited}@$time"
            else "$tag:served by ${result.value.resource.name} waited=${result.timeWaited}@$time")
        }

        fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
        }

        fun stats(queue: RequestQ = q): String =
            "waiting=${queue.size}, waits=${queue.timeInQ.withinReplicationStatistic.count.toInt()}"

        override fun initialize() {
            log.clear()
            actors.clear()
            for (s in setups) s()
        }
    }

    private fun run(
        capacity: Int = 1,
        discipline: Queue.Discipline = Queue.Discipline.FIFO,
        reps: Int = 1,
        warmUp: Double = 0.0,
        sink: AnimationSink? = null,
        setup: SeizeModel.() -> Unit
    ): SeizeModel {
        val model = Model("TimedSeize${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = SeizeModel(model, capacity, discipline)
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = warmUp
        model.simulate()
        return m
    }

    // ── The basic outcomes ──────────────────────────────────────────────────

    @Test
    @DisplayName("fast path: units free and nobody ahead, served at once with a wait of zero")
    fun fastPath() {
        val m = run { timed("T", 0.0, timeout = 5.0) }
        assertEquals(listOf("T:served by R waited=0.0@0.0"), m.log)
    }

    @Test
    @DisplayName("served before the deadline, and the timer is gone: the entity's next delay runs its full length")
    fun servedBeforeDeadline() {
        val m = run {
            holder("X", hold = 3.0)
            actor("T") {
                delay(1.0)
                note("T", seizeOrTimeout(r, timeout = 10.0).also { release(it.value) })
                delay(20.0)
                log.add("T:delayed@$time")
            }
        }
        assertEquals(listOf("T:served by R waited=2.0@3.0", "T:delayed@23.0"), m.log)
    }

    @Test
    @DisplayName("timed out: the request leaves its queue, its wait recorded, with no promise left; the next release serves the next request")
    fun timedOut() {
        var seen = ""
        val m = run {
            holder("X", hold = 10.0)
            timed("T", 1.0, timeout = 4.0)
            untimed("B", 2.0)
            at(6.0) { seen = stats() + ", available=${r.numAvailableUnits}" }
        }
        assertEquals(listOf("T:timedOut waited=4.0@5.0", "B:served@10.0"), m.log)
        assertEquals("waiting=1, waits=2, available=0", seen, "X's zero wait and T's reneged wait; B still waits")
    }

    @Test
    @DisplayName("a release at the deadline instant serves the request at default priorities; an earlier timer wins")
    fun deadlineInstant() {
        val served = run {
            holder("X", hold = 5.0)
            timed("T", 1.0, timeout = 4.0)
        }
        assertEquals(listOf("T:served by R waited=4.0@5.0"), served.log)
        val timedOut = run {
            holder("X", hold = 5.0)
            timed("T", 1.0, timeout = 4.0, timeoutPriority = -100)
        }
        assertEquals(listOf("T:timedOut waited=4.0@5.0"), timedOut.log)
    }

    @Test
    @DisplayName("a timer due between the wake and the resume finds the wait claimed: served, with the promised unit")
    fun timerAfterWake() {
        val m = run {
            // the release's event, then the timer, then the resume the release scheduled
            holder("X", hold = 5.0, endPriority = -10, releasePriority = 10)
            timed("T", 1.0, timeout = 4.0, timeoutPriority = 5)
        }
        assertEquals(listOf("T:served by R waited=4.0@5.0"), m.log)
    }

    @Test
    @DisplayName("recordRenegedWait: true records the reneged wait in time in queue; false leaves it out while number in queue counted it")
    fun recordRenegedWait() {
        fun scenario(record: Boolean): Pair<Int, Double> {
            var count = 0
            var numInQ = 0.0
            run {
                holder("X", hold = 10.0)
                timed("T", 2.0, timeout = 4.0, record = record)
                at(8.0) {
                    count = q.timeInQ.withinReplicationStatistic.count.toInt()
                    numInQ = q.numInQ.withinReplicationStatistic.weightedAverage
                }
            }
            return count to numInQ
        }
        val (recorded, numInQRecorded) = scenario(true)
        val (left, numInQLeft) = scenario(false)
        assertEquals(2, recorded)
        assertEquals(1, left)
        assertEquals(numInQRecorded, numInQLeft, 1e-12)
        assertTrue(numInQLeft > 0.0)
    }

    // ── A broken promise ────────────────────────────────────────────────────

    @Test
    @DisplayName("a promised unit going away between the wake and the resume: the re-wait keeps the original deadline")
    fun brokenPromiseTimesOut() {
        var seen = ""
        val m = run {
            holder("X", hold = 5.0, after = { r.changeCapacity(r.CapacityChangeNotice(capacity = 0)) })
            timed("T", 1.0, timeout = 6.0)
            at(7.5) { seen = stats() }
        }
        assertEquals(listOf("T:timedOut waited=6.0@7.0"), m.log)
        assertEquals("waiting=0, waits=2", seen)
    }

    @Test
    @DisplayName("a promised unit going away, then capacity back before the deadline: the re-wait is served")
    fun brokenPromiseServedLater() {
        val m = run {
            holder("X", hold = 5.0, after = { r.changeCapacity(r.CapacityChangeNotice(capacity = 0)) })
            timed("T", 1.0, timeout = 6.0)
            at(6.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 1)) }
        }
        assertEquals(listOf("T:served by R waited=5.0@6.0"), m.log)
    }

    // ── Queues, rules and moves ─────────────────────────────────────────────

    @Test
    @DisplayName("a reneged request does not disturb the order a selection rule gives the others")
    fun selectionRuleOrder() {
        val m = run {
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, queue ->
                queue.toList().filter { it.resource == resource }.takeLast(1)
            }
            holder("X", hold = 10.0)
            untimed("U1", 1.0)
            timed("T", 2.0, timeout = 3.0)
            untimed("U2", 3.0)
        }
        assertEquals(listOf("T:timedOut waited=3.0@5.0", "U2:served@10.0", "U1:served@11.0"), m.log)
    }

    @Test
    @DisplayName("under a rule that serves only the head, the head reneging lets the next be served at that instant")
    fun renegingHeadUnderRule() {
        val m = run(capacity = 2) {
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, queue ->
                listOfNotNull(queue.toList().firstOrNull { it.resource == resource })
            }
            holder("X1", hold = 3.0)
            holder("X2", hold = 20.0)
            timed("H", 1.0, timeout = 5.0, n = 2)
            untimed("B", 2.0)
        }
        // both at 6: B's resume runs first, at the release priority of the offer
        assertEquals(setOf("H:timedOut waited=5.0@6.0", "B:served@6.0"), m.log.toSet())
    }

    @Test
    @DisplayName("a timed request moved to a resource with units free is served there at once, and its timer is gone")
    fun movedAndServed() {
        val m = run {
            holder("X", hold = 20.0)
            val t = actor("T") {
                delay(1.0)
                val result = seizeOrTimeout(r, timeout = 10.0)
                note("T", result)
                release(result.value)
                delay(20.0)
                log.add("T:delayed@$time")
            }
            at(3.0) { t.moveTo(r2) }
        }
        assertEquals(listOf("T:served by R2 waited=2.0@3.0", "T:delayed@23.0"), m.log)
    }

    @Test
    @DisplayName("a timed request moved to a busy resource times out there; its reneged wait is recorded in the queue it is in")
    fun movedThenTimedOut() {
        var seen = ""
        val m = run {
            holder("X", hold = 20.0)
            actor("Y") { val a = seize(r2); delay(20.0); release(a) }
            val t = timed("T", 1.0, timeout = 6.0)
            at(3.0) { t.moveTo(r2) }
            at(8.0) { seen = stats(r2.myWaitingQ) + "; " + stats() }
        }
        assertEquals(listOf("T:timedOut waited=6.0@7.0"), m.log)
        assertEquals("waiting=0, waits=2; waiting=0, waits=1", seen, "Y's zero wait and T's reneged wait in R2's queue; R's queue has only X's")
    }

    @Test
    @DisplayName("an entity's priority changed while its request waits in a ranked queue: as for an untimed request; the timer still fires")
    fun priorityChangeRanked() {
        // A request takes the entity's priority when it enters the queue; a later change to the entity's
        // priority does not move it, for an untimed request as for a timed one.
        val untimed = run(discipline = Queue.Discipline.RANKED) {
            holder("X", hold = 5.0)
            untimed("A", 1.0)
            val b = untimed("B", 2.0)
            at(3.0) { b.priority = 0 }
        }
        assertEquals(listOf("A:served@5.0", "B:served@6.0"), untimed.log)
        val timed = run(discipline = Queue.Discipline.RANKED) {
            holder("X", hold = 5.0)
            timed("A", 1.0, timeout = 8.0)
            val b = timed("B", 2.0, timeout = 3.5)
            at(3.0) { b.priority = 0 }
        }
        assertEquals(listOf("A:served by R waited=4.0@5.0", "B:timedOut waited=3.5@5.5"), timed.log)
    }

    @Test
    @DisplayName("a capacity increase serves a timed request through the same claim as a release")
    fun capacityIncrease() {
        val m = run {
            holder("X", hold = 20.0)
            timed("T", 1.0, timeout = 10.0)
            at(3.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 2)) }
        }
        assertEquals(listOf("T:served by R waited=2.0@3.0"), m.log)
    }

    @Test
    @DisplayName("a capacity decrease while a timed request waits: it times out with no units promised")
    fun capacityDecrease() {
        for (rule in CapacityChangeRule.entries) {
            var seen = ""
            val m = run(capacity = 2) {
                r.capacityChangeRule = rule
                holder("X1", hold = 20.0)
                holder("X2", hold = 20.0)
                timed("T", 1.0, timeout = 4.0)
                at(2.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 1)) }
                at(6.0) { seen = stats() + ", promised=${r.myPromisedUnits}" }
            }
            assertEquals(listOf("T:timedOut waited=4.0@5.0"), m.log, "$rule")
            assertEquals("waiting=0, waits=3, promised=0", seen, "$rule")
        }
    }

    // ── Termination and the end of a replication ────────────────────────────

    @Test
    @DisplayName("termination while waiting: the request leaves with nothing recorded; nothing fires at the deadline")
    fun terminationWhileWaiting() {
        var seen = ""
        val m = run {
            holder("X", hold = 10.0)
            val t = timed("T", 1.0, timeout = 4.0)
            untimed("B", 2.0)
            at(3.0) { t.terminateProcess() }
            at(6.0) { seen = stats() }
        }
        assertEquals(listOf("B:served@10.0"), m.log)
        assertEquals("waiting=1, waits=1", seen, "only X's zero wait; B waits")
    }

    @Test
    @DisplayName("termination after the wake, before the resume: the promised unit goes to the next waiter")
    fun terminationAfterWake() {
        val m = run {
            lateinit var t: SeizeModel.Actor
            holder("X", hold = 5.0, after = { t.terminateProcess() })
            t = timed("T", 1.0, timeout = 10.0)
            untimed("B", 2.0)
        }
        assertEquals(listOf("B:served@5.0"), m.log)
    }

    @Test
    @DisplayName("termination during the re-wait: the request leaves and nothing fires at the deadline")
    fun terminationDuringRewait() {
        var seen = ""
        val m = run {
            holder("X", hold = 5.0, after = { r.changeCapacity(r.CapacityChangeNotice(capacity = 0)) })
            val t = timed("T", 1.0, timeout = 6.0)
            at(6.0) { t.terminateProcess() }
            at(7.5) { seen = stats() + ", group=${t.myWaitGroup}" }
        }
        assertEquals(emptyList(), m.log)
        assertEquals("waiting=0, waits=1, group=null", seen)
    }

    @Test
    @DisplayName("a timed seize open at the end of a replication leaves nothing behind for the next")
    fun openAtEndOfReplication() {
        val seen = mutableListOf<String>()
        run(reps = 3) {
            holder("X", hold = 100.0)
            timed("T", 1.0, timeout = 100.0)
            at(2.0) { seen.add(stats()) }
        }
        assertEquals(List(3) { "waiting=1, waits=1" }, seen)
    }

    // ── Other forms and interactions ────────────────────────────────────────

    @Test
    @DisplayName("a movable resource through its own overload: served at once, then a second request times out")
    fun movableResource() {
        val m = run {
            actor("A") { val r1 = seizeOrTimeout(truck, timeout = 5.0); note("A", r1); delay(10.0); release(r1.value) }
            actor("B") { delay(1.0); note("B", seizeOrTimeout(truck, timeout = 3.0)) }
        }
        assertEquals(listOf("A:served by Truck waited=0.0@0.0", "B:timedOut waited=3.0@4.0"), m.log)
    }

    @Test
    @DisplayName("resumeProcess() on an entity in a timed seize is refused at the call; the wait goes on")
    fun externalResumeRefused() {
        var error: String? = null
        var canResume: Boolean? = null
        val m = run {
            holder("X", hold = 5.0)
            val t = timed("T", 1.0, timeout = 10.0)
            at(2.0) {
                canResume = t.canResumeProcess
                error = runCatching { t.resumeProcess() }.exceptionOrNull()?.message
            }
        }
        assertEquals(false, canResume)
        assertNotNull(error)
        assertEquals(listOf("T:served by R waited=4.0@5.0"), m.log)
    }

    @Test
    @DisplayName("while waiting, a lone timed seize reports what seize reports")
    fun state() {
        var seen = ""
        run {
            holder("X", hold = 5.0)
            val t = timed("T", 1.0, timeout = 10.0)
            at(2.0) { seen = "${t.isWaitingForResource}:${t.currentSuspendType}" }
        }
        assertEquals("true:SEIZE", seen)
    }

    @Test
    @DisplayName("a wait across the warm-up is recorded as an untimed wait ending at the same time")
    fun acrossWarmUp() {
        fun stats(m: SeizeModel) = with(m.q) {
            listOf(timeInQ.withinReplicationStatistic.count, timeInQ.withinReplicationStatistic.weightedAverage,
                numInQ.withinReplicationStatistic.weightedAverage)
        }
        val untimed = run(warmUp = 5.0) { holder("X", hold = 8.0); untimed("T", 2.0) }
        val served = run(warmUp = 5.0) { holder("X", hold = 8.0); timed("T", 2.0, timeout = 20.0) }
        assertEquals(stats(untimed), stats(served))
        val untimedTo8 = run(warmUp = 5.0) { holder("X", hold = 8.0); untimed("T", 2.0) }
        val timedOutAt8 = run(warmUp = 5.0) { holder("X", hold = 9.0); timed("T", 2.0, timeout = 6.0) }
        assertEquals(stats(untimedTo8), stats(timedOutAt8))
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("trace: SeizeQueued and SeizeWaiting as for seize; WaitTimedOut once on a timeout")
    fun trace() {
        val sink = CollectingSink()
        lateinit var served: SeizeModel.Actor
        lateinit var timedOut: SeizeModel.Actor
        run(sink = sink) {
            holder("X", hold = 5.0)
            served = timed("S", 1.0, timeout = 10.0)
            timedOut = timed("T", 2.0, timeout = 1.0)
        }
        fun of(a: SeizeModel.Actor) = sink.events.filter { it.entityIdOrNull == a.id }.mapNotNull {
            when (it) {
                is AnimationEvent.SeizeQueued -> "SeizeQueued@${it.simTime}"
                is AnimationEvent.SeizeWaiting -> "SeizeWaiting@${it.simTime}"
                is AnimationEvent.WaitTimedOut -> "WaitTimedOut@${it.simTime}"
                else -> null
            }
        }
        assertEquals(listOf("SeizeQueued@1.0", "SeizeWaiting@1.0"), of(served))
        assertEquals(listOf("SeizeQueued@2.0", "SeizeWaiting@2.0", "WaitTimedOut@3.0"), of(timedOut))
        assertFalse(of(served).contains("WaitTimedOut@11.0"))
    }
}
