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
import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.sendMessage
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  An allocation's triggers: `interruptedTrigger` fires when its units go missing, with the cause, and
 *  `restoredTrigger` when they have all come back. They compose with other waits in `waitForFirst`, any
 *  entity may wait on them, and a released allocation's never fire.
 */
class AllocationTriggerTest {

    private class TModel(parent: ModelElement) : ProcessModel(parent, "triggers") {
        val r = ResourceWithQ(this, "R")
        val r2 = ResourceWithQ(this, "R2", capacity = 2)
        val other = ResourceWithQ(this, "Other")
        val signal = Signal(this, "Sig")

        val log = mutableListOf<String>()
        val setups = mutableListOf<TModel.() -> Unit>()

        /** A trigger kept from one replication to the next. */
        var kept: WaitTrigger<InterruptCause>? = null

        init {
            for (res in listOf(r, r2, other)) res.interruptRule = InterruptRule.RESUME
        }

        inner class Actor(val tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) = actor("at$t") { delay(t); action() }

        fun describe(result: WaitForFirstResult, names: Map<WaitTrigger<*>, String>): String =
            if (result.timedOut) "timed out" else names.getValue(result.trigger!!)

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }

        override fun replicationEnded() {
            LoansAndFreezesCheck.check(r, r2, other)
        }
    }

    private fun run(reps: Int = 1, length: Double = 100.0, sink: AnimationSink? = null,
                    configure: TModel.() -> Unit = {}, setup: TModel.() -> Unit): TModel {
        val model = Model("Triggers${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = TModel(model)
        m.configure()
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return m
    }

    // ---------- the claim points ----------

    @Test
    @DisplayName("a failure claims the interrupted trigger with FAILURE, and the repair claims the restored trigger")
    fun failureAndRepair() {
        val m = run {
            actor("H") {
                val a = seize(r)
                val lost = a.interruptedTrigger()
                val first = waitForFirst(lost)
                log.add("interrupted@$time ${first.valueOf(lost)}")
                val back = a.restoredTrigger()
                waitForFirst(back)
                log.add("restored@$time")
                release(a)
            }
            at(3.0) { r.fail(); LoansAndFreezesCheck.check(r) }
            at(7.0) { r.repair() }
        }
        assertEquals(listOf("interrupted@3.0 FAILURE", "restored@7.0"), m.log)
    }

    @Test
    @DisplayName("a preemption claims the interrupted trigger with PREEMPTION, and the borrower's release claims the restored trigger")
    fun preemptionAndReturnedLoan() {
        val m = run {
            actor("L", priority = 9) {
                val a = seize(r)
                val lost = a.interruptedTrigger()
                val cause = waitForFirst(lost).valueOf(lost)
                log.add("interrupted@$time $cause")
                waitForFirst(a.restoredTrigger())
                log.add("restored@$time")
                release(a)
            }
            actor("P", priority = 1) { delay(2.0); val a = preempt(r); delay(4.0); release(a) }
        }
        assertEquals(listOf("interrupted@2.0 PREEMPTION", "restored@6.0"), m.log)
    }

    @Test
    @DisplayName("the fast path: interrupted with units already missing, and restored with none missing, fire at once")
    fun fastPath() {
        val m = run {
            actor("H") {
                val a = seize(r)
                val back = a.restoredTrigger()
                waitForFirst(back)
                log.add("restored at once@$time")
                delay(5.0, uses = emptyList())
                val lost = a.interruptedTrigger()
                val cause = waitForFirst(lost).valueOf(lost)
                log.add("interrupted at once@$time $cause")
                release(a)
            }
            at(2.0) { r.fail() }
            at(9.0) { r.repair() }
        }
        assertEquals(listOf("restored at once@0.0", "interrupted at once@5.0 FAILURE"), m.log)
    }

    @Test
    @DisplayName("a fast-path fire on an allocation with units missing for both reasons reports FAILURE")
    fun bothReasonsReportFailure() {
        lateinit var held: Allocation
        val m = run {
            actor("L", priority = 9) { held = seize(r2, 2); delay(20.0); release(held) }
            actor("P", priority = 1) { delay(2.0); val a = preempt(r2); delay(10.0); release(a) }
            at(3.0) { r2.fail() }
            actor("S") {
                delay(4.0)
                log.add("loan=${held.missingByLoan} failure=${held.missingByFailure}")
                val lost = held.interruptedTrigger()
                val cause = waitForFirst(lost).valueOf(lost)
                log.add("fast@$time $cause")
            }
            at(6.0) { r2.repair() }
        }
        assertEquals(listOf("loan=1 failure=1", "fast@4.0 FAILURE"), m.log)
    }

    // ---------- who waits ----------

    @Test
    @DisplayName("a waiter that is not the holder is claimed at the return; the holder, in a reporting delay, decides for itself")
    fun supervisorWaits() {
        val m = run {
            lateinit var held: Allocation
            actor("H") {
                held = seize(r)
                val res = delayOrInterrupt(10.0)
                log.add("holder told@$time")
                // the work goes on with a plain delay, which begins held while the unit is missing
                delay((res as DelayResult.Interrupted).timeRemaining)
                log.add("holder done@$time")
                release(held)
            }
            actor("S") { delay(1.0); waitForFirst(held.restoredTrigger(), timeout = 50.0).let { log.add("supervisor@$time") } }
            at(3.0) { r.fail() }
            at(7.0) { r.repair() }
        }
        // the restored trigger was not missing at 1, so it fired at once; then H reports at 3 and resumes its rest
        // as a held plain delay, which starts at the repair at 7 and ends at 14
        assertEquals(listOf("supervisor@1.0", "holder told@3.0", "holder done@14.0"), m.log)
    }

    @Test
    @DisplayName("after a report, a supervisor waiting for the return and a plain delay begun held both go on at the repair")
    fun restoredThenHeldDelay() {
        val m = run {
            lateinit var held: Allocation
            actor("H") {
                held = seize(r)
                delayOrInterrupt(10.0)
                log.add("told@$time")
                delay(5.0)
                log.add("done@$time")
                release(held)
            }
            actor("S") { delay(4.0); waitForFirst(held.restoredTrigger()); log.add("supervisor@$time") }
            at(3.0) { r.fail() }
            at(7.0) { r.repair() }
        }
        assertEquals(listOf("told@3.0", "supervisor@7.0", "done@12.0"), m.log)
    }

    // ---------- composed with other waits ----------

    @Test
    @DisplayName("composed with a signal: whichever comes first wins, and the other is released")
    fun withASignal() {
        for (signalFirst in listOf(true, false)) {
            val m = run {
                actor("H") {
                    val a = seize(r)
                    val lost = a.interruptedTrigger()
                    val sig = signal.trigger()
                    val res = waitForFirst(lost, sig, timeout = 20.0)
                    log.add("${describe(res, mapOf(lost to "interrupted", sig to "signal"))}@$time waiting=${signal.waitingQ.size}")
                    delay(1.0, uses = emptyList())
                    release(a)
                }
                at(if (signalFirst) 2.0 else 4.0) { signal.signalAll() }
                at(if (signalFirst) 4.0 else 2.0) { r.fail() }
                at(10.0) { r.repair() }
            }
            val expected = if (signalFirst) "signal@2.0 waiting=0" else "interrupted@2.0 waiting=0"
            assertEquals(listOf(expected), m.log, "signal first = $signalFirst")
        }
    }

    @Test
    @DisplayName("composed with a seize trigger: whichever claims first wins, and a losing request leaves its queue with its wait recorded")
    fun withASeizeTrigger() {
        for (lostFirst in listOf(true, false)) {
            val m = run {
                actor("B") { val b = seize(other); delay(if (lostFirst) 6.0 else 2.0); release(b) }
                actor("H") {
                    val a = seize(r)
                    delay(1.0)
                    val lost = a.interruptedTrigger()
                    val take = other.seizeTrigger()
                    val res = waitForFirst(lost, take, timeout = 20.0)
                    log.add("${describe(res, mapOf(lost to "interrupted", take to "seized"))}@$time " +
                            "waiting=${other.waitingQ.size} waits=${other.myWaitingQ.timeInQ.withinReplicationStatistic.count.toInt()}")
                    if (res.firedBy(take)) release(res.valueOf(take))
                    release(a)
                }
                at(4.0) { r.fail() }
                at(5.0) { r.repair() }
            }
            // B's own seize records a wait of zero; H's losing or winning request records the second
            val expected = if (lostFirst) "interrupted@4.0 waiting=0 waits=2" else "seized@2.0 waiting=0 waits=2"
            assertEquals(listOf(expected), m.log, "interrupted first = $lostFirst")
        }
    }

    private class MessageScenario(parent: ModelElement, val messageAt: Double, val failAt: Double) : AgentModel(parent, "messages") {
        val r = ResourceWithQ(this, "R").apply { interruptRule = InterruptRule.RESUME }
        val log = mutableListOf<String>()

        inner class Worker : Agent("worker") {
            val run: KSLProcess = process(isDefaultProcess = true) {
                val a = seize(r)
                val lost = a.interruptedTrigger()
                val message = mailbox.trigger()
                val res = waitForFirst(lost, message, timeout = 20.0)
                log.add("${if (res.firedBy(lost)) "interrupted" else "message"}@$time")
                release(a)
            }
        }

        val worker = Worker()

        inner class Sender : Agent("sender") {
            val run: KSLProcess = process(isDefaultProcess = true) {
                delay(messageAt)
                sendMessage(AgentMessage.Inform(this@Sender, "call"), worker.mailbox)
            }
        }

        override fun initialize() {
            super.initialize()
            log.clear()
            activate(worker.run)
            activate(Sender().run)
            schedule({ _: ksl.simulation.KSLEvent<Nothing> -> if (!r.isFailed) r.fail() }, failAt)
            schedule({ _: ksl.simulation.KSLEvent<Nothing> -> if (r.isFailed) r.repair() }, failAt + 1.0)
        }
    }

    @Test
    @DisplayName("composed with a message: whichever comes first wins")
    fun withAMessage() {
        for ((messageAt, failAt, expected) in listOf(Triple(2.0, 4.0, "message@2.0"), Triple(4.0, 2.0, "interrupted@2.0"))) {
            val model = Model("TriggerMessage${System.nanoTime()}", autoCSVReports = false)
            val s = MessageScenario(model, messageAt, failAt)
            model.numberOfReplications = 1
            model.lengthOfReplication = 30.0
            model.simulate()
            assertEquals(listOf(expected), s.log)
        }
    }

    // ---------- reuse and released allocations ----------

    @Test
    @DisplayName("a trigger reused after a win and after a timeout behaves as new each time")
    fun reuse() {
        val m = run {
            actor("H") {
                val a = seize(r)
                val lost = a.interruptedTrigger()
                log.add(if (waitForFirst(lost, timeout = 2.0).timedOut) "timed out@$time" else "fired@$time")
                log.add(if (waitForFirst(lost, timeout = 10.0).timedOut) "timed out@$time" else "fired@$time")
                waitForFirst(a.restoredTrigger())
                log.add(if (waitForFirst(lost, timeout = 10.0).timedOut) "timed out@$time" else "fired@$time")
                release(a)
            }
            at(3.0) { r.fail() }
            at(6.0) { r.repair() }
        }
        assertEquals(listOf("timed out@2.0", "fired@3.0", "timed out@16.0"), m.log)
    }

    @Test
    @DisplayName("an allocation released while a wait is registered never fires it; the wait ends by its timeout")
    fun releasedWhileWaiting() {
        val m = run {
            lateinit var a: Allocation
            actor("H") { a = seize(r); delay(3.0); release(a) }
            actor("S") {
                delay(1.0)
                val res = waitForFirst(a.interruptedTrigger(), timeout = 10.0)
                log.add("${if (res.timedOut) "timed out" else "fired"}@$time")
            }
            actor("N") { delay(4.0); val b = seize(r); delay(10.0); release(b) }
            at(5.0) { r.fail() }
            at(6.0) { r.repair() }
        }
        assertEquals(listOf("timed out@11.0"), m.log)
    }

    @Test
    @DisplayName("waiting on a released allocation's trigger throws, in the same replication and in the next; the new allocation's works")
    fun releasedAllocationRefused() {
        val m = run(reps = 2) {
            actor("H") {
                val a = seize(r)
                val old = kept
                if (old != null) {
                    try {
                        waitForFirst(old, timeout = 1.0)
                        log.add("old trigger accepted")
                    } catch (e: IllegalArgumentException) {
                        log.add("old trigger refused")
                    }
                }
                kept = a.interruptedTrigger()
                val lost = a.interruptedTrigger()
                val cause = waitForFirst(lost).valueOf(lost)
                log.add("new@$time $cause")
                release(a)
                try {
                    waitForFirst(a.restoredTrigger(), timeout = 1.0)
                    log.add("released accepted")
                } catch (e: IllegalArgumentException) {
                    log.add("released refused")
                }
            }
            at(3.0) { r.fail() }
            at(4.0) { r.repair() }
        }
        assertEquals(listOf("old trigger refused", "new@3.0 FAILURE", "released refused"), m.log)
    }

    // ---------- loans ----------

    @Test
    @DisplayName("an allocation lent to two borrowers: the first return does not fire the restored trigger; the second does")
    fun twoBorrowers() {
        val m = run {
            lateinit var held: Allocation
            actor("L", priority = 9) { held = seize(r2, 2); delay(30.0); release(held) }
            actor("P1", priority = 1) { delay(2.0); val a = preempt(r2); delay(3.0); release(a) }
            actor("P2", priority = 1) { delay(3.0); val a = preempt(r2); delay(5.0); release(a) }
            actor("S") { delay(4.0); waitForFirst(held.restoredTrigger()); log.add("restored@$time") }
        }
        assertEquals(listOf("restored@8.0"), m.log)
    }

    @Test
    @DisplayName("a chain whose middle is terminated: the first lender's restored trigger fires at the last borrower's release")
    fun chainMiddleTerminated() {
        val m = run {
            lateinit var held: Allocation
            actor("A", priority = 9) { held = seize(r); delay(30.0); release(held) }
            val b = actor("B", priority = 5) { delay(2.0); val a = preempt(r); delay(20.0); release(a) }
            actor("C", priority = 1) { delay(3.0); val a = preempt(r); delay(5.0); release(a); log.add("C done@$time") }
            at(4.0) { b.terminateProcess() }
            actor("S") { delay(3.5); waitForFirst(held.restoredTrigger()); log.add("restored@$time") }
        }
        assertEquals(listOf("C done@8.0", "restored@8.0"), m.log)
    }

    // ---------- records and the trace ----------

    @Test
    @DisplayName("the allocation's records under reacting count from the units going missing until they return")
    fun recordsUnderReacting() {
        lateinit var a: Allocation
        run {
            actor("H") {
                a = seize(r)
                val res = delayOrInterrupt(10.0)
                if (res is DelayResult.Interrupted) {
                    waitForFirst(a.restoredTrigger())
                    delayOrInterrupt(res.timeRemaining)
                }
                release(a)
            }
            at(3.0) { r.fail() }
            at(8.0) { r.repair() }
        }
        assertEquals(1, a.timesInterrupted)
        assertEquals(5.0, a.timeInterrupted, 1e-12)
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("the composed form whose work completes records it as a wait that timed out")
    fun composedFormInTheTrace() {
        val sink = CollectingSink()
        lateinit var h: TModel.Actor
        run(sink = sink) {
            h = actor("H") {
                val a = seize(r)
                val res = waitForFirst(a.interruptedTrigger(), timeout = 6.0, suspensionName = "work")
                log.add("work ${if (res.timedOut) "done" else "lost"}@$time")
                release(a)
            }
        }
        assertTrue(sink.events.any { it is AnimationEvent.WaitTimedOut && it.entityId == h.id && it.suspensionName == "work" })
    }

    // ---------- the metamorphic check ----------

    @Test
    @DisplayName("reacting can imitate freezing: waiting for the units and going on gives the same results as a held delay")
    fun reactingImitatesFreezing() {
        val reacting = ReactingMetamorphicModels.Work { _, duration ->
            var res = delayOrInterrupt(duration)
            while (res is DelayResult.Interrupted) {
                waitForFirst(res.by.restoredTrigger())
                res = delayOrInterrupt(if (res.rule == InterruptRule.RESTART) res.duration else res.timeRemaining)
            }
        }
        for (variant in listOf("failures RESUME", "failures RESTART", "preemption RESUME", "preemption RESTART")) {
            assertEquals(
                ReactingMetamorphicModels.results(variant, ReactingMetamorphicModels.plainDelay),
                ReactingMetamorphicModels.results(variant, reacting),
                variant
            )
        }
    }
}
