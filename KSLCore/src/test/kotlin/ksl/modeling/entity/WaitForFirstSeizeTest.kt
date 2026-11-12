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
import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  A seize trigger in a wait for the first of several things: a bed or a signal, a bed or a chair, a
 *  bed or a message. The first to fire wins; a losing seize request leaves its queue at the claim, its
 *  wait recorded, and is never served afterwards.
 */
class WaitForFirstSeizeTest {

    private class SeizeMix(parent: ModelElement) : AgentModel(parent, "seizeMix") {
        val bed = ResourceWithQ(this, "Bed")
        val chair = ResourceWithQ(this, "Chair")
        val shared = RequestQ(this, "Shared")
        val alarm = Signal(this, "Alarm")
        val log = mutableListOf<String>()
        val setups = mutableListOf<SeizeMix.() -> Unit>()

        inner class Hub : Agent("hub")

        val hub = Hub()

        /** Triggers held as properties, reused by every wait and replication. */
        val bedTrigger = bed.seizeTrigger()
        val chairTrigger = chair.seizeTrigger()
        val alarmTrigger = alarm.trigger()
        val messageTrigger = hub.mailbox.trigger()

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.(Actor) -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body(this@Actor) }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.(Actor) -> Unit): Actor =
            Actor(tag, body).also { activate(it.p) }

        fun holder(tag: String, r: ResourceWithQ, hold: Double, endPriority: Int = DELAY_PRIORITY): Actor = actor(tag) {
            val a = seize(r)
            delay(hold, endPriority)
            release(a)
        }

        fun name(r: WaitForFirstResult): String = when (r.trigger) {
            bedTrigger -> "bed"
            chairTrigger -> "chair"
            alarmTrigger -> "alarm"
            messageTrigger -> "message"
            null -> "timeout"
            else -> "other"
        }

        /** Waits for the first of [triggers], logs which and when, and releases what was seized. */
        suspend fun KSLProcessBuilder.first(tag: String, triggers: List<WaitTrigger<*>>, timeout: Double = 30.0): WaitForFirstResult {
            val r = waitForFirst(triggers, timeout = timeout)
            log.add("$tag:${name(r)}@$time")
            for (t in listOf(bedTrigger, chairTrigger)) if (t in triggers) r.valueOrNull(t)?.let { release(it) }
            return r
        }

        fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
        }

        fun stats(r: ResourceWithQ) = "${r.name}: waiting=${r.myWaitingQ.size}, waits=${r.myWaitingQ.timeInQ.withinReplicationStatistic.count.toInt()}"

        override fun initialize() {
            super.initialize()
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(reps: Int = 1, sink: AnimationSink? = null, setup: SeizeMix.() -> Unit): SeizeMix {
        val model = Model("SeizeMix${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = SeizeMix(model)
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    // ── The yield ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a wait with seize triggers yields once, at the lowest seizePriority, before it registers, as seize yields")
    fun yieldAtLowestSeizePriority() {
        fun scenario(priorities: Pair<Int, Int>): List<String> = run {
            holder("C", chair, hold = 20.0)
            // both delays end at time 1 ahead of either yield, so the yields compete at the same instant
            actor("V") { delay(1.0, 1); val a = seize(bed, seizePriority = 100); log.add("V:bed@$time"); delay(5.0); release(a) }
            actor("W") {
                delay(1.0, 1)
                val r = waitForFirst(bed.seizeTrigger(seizePriority = priorities.first), chair.seizeTrigger(seizePriority = priorities.second))
                log.add("W:${if (r.trigger === null) "timeout" else (r.valueOf(r.trigger as WaitTrigger<Allocation>)).resource.name}@$time")
                release(r.valueOf(r.trigger as WaitTrigger<Allocation>))
            }
        }.log
        // W yields at 50, ahead of V's seize at 100, and takes the bed at once; V waits for it
        assertEquals(listOf("W:Bed@1.0", "V:bed@1.0"), scenario(50 to 200))
        // W yields at 150, behind V, and waits for the bed
        assertEquals(listOf("V:bed@1.0", "W:Bed@6.0"), scenario(150 to 200))
    }

    // ── A seize and a signal ────────────────────────────────────────────────

    @Test
    @DisplayName("a seize and a signal: the signal wins, and the seize request leaves its queue at the claim, its wait recorded")
    fun signalBeatsSeize() {
        var seen = ""
        val m = run {
            holder("X", bed, hold = 10.0)
            actor("W") { first("W", listOf(bedTrigger, alarmTrigger)) }
            at(3.0) { alarm.signalAll(); seen = stats(bed) }
        }
        assertEquals(listOf("W:alarm@3.0"), m.log)
        assertEquals("Bed: waiting=0, waits=2", seen, "X's zero wait and W's withdrawn wait")
    }

    @Test
    @DisplayName("a seize and a signal: the seize wins, and the entity leaves the signal's queue at the claim, its wait recorded")
    fun seizeBeatsSignal() {
        var seen = ""
        val m = run {
            holder("X", bed, hold = 3.0)
            actor("W") { first("W", listOf(bedTrigger, alarmTrigger)) }
            at(3.5) { seen = "inAlarmQ=${alarm.waitingQ.size}, alarmWaits=${alarm.waitingQ.timeInQ.withinReplicationStatistic.count.toInt()}" }
        }
        assertEquals(listOf("W:bed@3.0"), m.log)
        assertEquals("inAlarmQ=0, alarmWaits=1", seen)
    }

    // ── Two seize triggers ──────────────────────────────────────────────────

    @Test
    @DisplayName("a bed or a chair: the first served wins; the other request leaves its queue recorded, and a later release serves the next waiter")
    fun bedOrChair() {
        var seen = ""
        val m = run {
            holder("X", bed, hold = 5.0)
            holder("Y", chair, hold = 8.0)
            actor("W") { first("W", listOf(bedTrigger, chairTrigger)) }
            actor("N") { delay(1.0); val a = seize(chair); log.add("N:chair@$time"); release(a) }
            at(5.5) { seen = stats(chair) }
        }
        assertEquals(listOf("W:bed@5.0", "N:chair@8.0"), m.log)
        assertEquals("Chair: waiting=1, waits=2", seen, "Y's zero wait and W's withdrawn wait; N still waits")
    }

    @Test
    @DisplayName("two seize triggers whose requests share a queue: a release that could serve both goes to the trigger passed first")
    fun sharedQueueArgumentOrder() {
        fun scenario(bedFirst: Boolean): List<String> = run {
            holder("X", bed, hold = 5.0)
            // both requests wait in the shared queue; one release of the bed could serve either
            val a = bed.seizeTrigger(queue = shared)
            val b = bed.seizeTrigger(queue = shared)
            actor("W") {
                val r = waitForFirst(if (bedFirst) listOf(a, b) else listOf(b, a), timeout = 30.0)
                log.add("W:${if (r.trigger === a) "a" else "b"}@$time")
                release(r.valueOf(r.trigger as WaitTrigger<Allocation>))
            }
        }.log
        assertEquals(listOf("W:a@5.0"), scenario(bedFirst = true))
        assertEquals(listOf("W:b@5.0"), scenario(bedFirst = false))
    }

    // ── A seize, a message and a timeout ────────────────────────────────────

    @Test
    @DisplayName("a seize, a message and a timeout, each winning in turn")
    fun seizeMessageTimeout() {
        fun scenario(bedAt: Double?, messageAt: Double?): List<String> = run {
            holder("X", bed, hold = bedAt ?: 40.0)
            actor("W") { first("W", listOf(bedTrigger, messageTrigger), timeout = 10.0) }
            if (messageAt != null) at(messageAt) { hub.mailbox.deliver(AgentMessage.Inform(hub, "m")) }
        }.log
        assertEquals(listOf("W:bed@3.0"), scenario(bedAt = 3.0, messageAt = 6.0))
        assertEquals(listOf("W:message@4.0"), scenario(bedAt = 6.0, messageAt = 4.0))
        assertEquals(listOf("W:timeout@10.0"), scenario(bedAt = null, messageAt = null))
    }

    @Test
    @DisplayName("a release and a signal at one instant: the higher-priority causing event wins, whatever the argument order")
    fun releaseAndSignalTie() {
        fun scenario(releaseFirst: Boolean, argumentsBedFirst: Boolean): List<String> = run {
            holder("X", bed, hold = 5.0, endPriority = if (releaseFirst) 10 else 30)
            actor("W") { first("W", if (argumentsBedFirst) listOf(bedTrigger, alarmTrigger) else listOf(alarmTrigger, bedTrigger)) }
            at(5.0, priority = 20) { alarm.signalAll() }
        }.log
        for (order in listOf(true, false)) {
            assertEquals(listOf("W:bed@5.0"), scenario(releaseFirst = true, argumentsBedFirst = order))
            assertEquals(listOf("W:alarm@5.0"), scenario(releaseFirst = false, argumentsBedFirst = order))
        }
    }

    // ── Interactions ────────────────────────────────────────────────────────

    @Test
    @DisplayName("resumeProcess() on an entity in a mixed wait with a seize trigger is refused; the wait goes on")
    fun externalResumeRefused() {
        var error: String? = null
        val m = run {
            holder("X", bed, hold = 5.0)
            val w = actor("W") { first("W", listOf(bedTrigger, alarmTrigger)) }
            at(2.0) { error = runCatching { w.resumeProcess() }.exceptionOrNull()?.message }
        }
        assertNotNull(error)
        assertEquals(listOf("W:bed@5.0"), m.log)
    }

    @Test
    @DisplayName("seize triggers held as properties are reused after a win, after a timeout, and in the next replication")
    fun reuse() {
        val m = run(reps = 2) {
            holder("X", bed, hold = 3.0)
            holder("Y", chair, hold = 30.0)
            actor("W") {
                first("W", listOf(bedTrigger, chairTrigger), timeout = 10.0)
                delay(5.0)
                holder("Z", bed, hold = 30.0)
                delay(1.0)
                first("W", listOf(bedTrigger, chairTrigger), timeout = 4.0)
                first("W", listOf(bedTrigger, alarmTrigger), timeout = 10.0)
            }
            at(15.0) { alarm.signalAll() }
        }
        assertEquals(listOf("W:bed@3.0", "W:timeout@13.0", "W:alarm@15.0"), m.log)
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("trace: a losing seize emits SeizeQueued and SeizeWaiting and nothing more")
    fun losingSeizeTrace() {
        val sink = CollectingSink()
        lateinit var w: SeizeMix.Actor
        run(sink = sink) {
            holder("X", bed, hold = 10.0)
            w = actor("W") { first("W", listOf(bedTrigger, alarmTrigger)) }
            at(3.0) { alarm.signalAll() }
        }
        val seize = sink.events.filter { it.entityIdOrNull == w.id }.mapNotNull {
            when (it) {
                is AnimationEvent.SeizeQueued -> "SeizeQueued@${it.simTime}"
                is AnimationEvent.SeizeWaiting -> "SeizeWaiting@${it.simTime}"
                is AnimationEvent.WaitTimedOut -> "WaitTimedOut@${it.simTime}"
                else -> null
            }
        }
        assertEquals(listOf("SeizeQueued@0.0", "SeizeWaiting@0.0"), seize)
        assertTrue(sink.events.any { it is AnimationEvent.SignalReceived && it.entityId == w.id })
    }
}
