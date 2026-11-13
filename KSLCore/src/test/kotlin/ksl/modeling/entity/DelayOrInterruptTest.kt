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
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  A delay that reports an interruption: it completes as a delay does, or ends at the moment units it depends
 *  on go missing, to a failure or a preemption, and says which allocation, why, and how much time was left.
 */
class DelayOrInterruptTest {

    private class RModel(parent: ModelElement) : ProcessModel(parent, "reporting") {
        val r = ResourceWithQ(this, "R")
        val r2 = ResourceWithQ(this, "R2", capacity = 2)
        val other = ResourceWithQ(this, "Other")
        val p1 = ResourceWithQ(this, "P1")
        val p2 = ResourceWithQ(this, "P2")
        val pool = ResourcePoolWithQ(this, listOf(p1, p2), name = "Pool")

        val log = mutableListOf<String>()
        val results = mutableListOf<DelayResult>()
        val setups = mutableListOf<RModel.() -> Unit>()

        init {
            for (res in listOf(r, r2, other, p1, p2)) res.interruptRule = InterruptRule.RESUME
        }

        inner class Actor(val tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        fun at(t: Double, priority: Int = ProcessModel.DELAY_PRIORITY, action: () -> Unit) =
            actor("at$t") { delay(t, delayPriority = priority); action() }

        /** Records a result and when it came. */
        fun note(tag: String, result: DelayResult, time: Double) {
            results.add(result)
            log.add("$tag ${if (result is DelayResult.Completed) "completed" else "interrupted"}@$time")
        }

        override fun initialize() {
            log.clear()
            results.clear()
            for (s in setups) s()
        }

        override fun replicationEnded() {
            LoansAndFreezesCheck.check(r, r2, other, p1, p2)
        }
    }

    private fun run(reps: Int = 1, length: Double = 100.0, sink: AnimationSink? = null,
                    configure: RModel.() -> Unit = {}, setup: RModel.() -> Unit): RModel {
        val model = Model("Reporting${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = RModel(model)
        m.configure()
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return m
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    // ---------- completed and interrupted ----------

    @Test
    @DisplayName("no interruption: Completed, with its duration and time elapsed, ending when a delay would")
    fun completed() {
        val m = run {
            actor("W") { val a = seize(r); note("W", delayOrInterrupt(10.0), time); release(a) }
            actor("D") { val a = seize(other); delay(10.0); log.add("D done@$time"); release(a) }
        }
        assertEquals(listOf("W completed@10.0", "D done@10.0"), m.log)
        val c = assertIs<DelayResult.Completed>(m.results.single())
        assertEquals(10.0, c.duration)
        assertEquals(10.0, c.timeElapsed)
    }

    @Test
    @DisplayName("a failure mid-delay under RESUME and RESTART: Interrupted at that instant, with what was done and left")
    fun failureMidDelay() {
        for (rule in listOf(InterruptRule.RESUME, InterruptRule.RESTART)) {
            lateinit var a: Allocation
            val m = run {
                actor("W") { a = seize(r); note("W", delayOrInterrupt(10.0), time); release(a) }
                at(3.0) { r.fail(rule) }
                at(5.0) { r.repair() }
            }
            assertEquals(listOf("W interrupted@3.0"), m.log, "rule $rule")
            val i = assertIs<DelayResult.Interrupted>(m.results.single())
            assertEquals(10.0, i.duration)
            assertEquals(3.0, i.timeElapsed)
            assertEquals(7.0, i.timeRemaining)
            assertEquals(3.0, i.timeDone)
            assertSame(a, i.by)
            assertEquals(InterruptCause.FAILURE, i.cause)
            assertEquals(rule, i.rule)
        }
    }

    @Test
    @DisplayName("a preemption: Interrupted with the cause PREEMPTION")
    fun preemption() {
        val m = run {
            actor("L", priority = 9) { val a = seize(r); note("L", delayOrInterrupt(10.0), time); release(a) }
            actor("P", priority = 1) { delay(4.0); val a = preempt(r); delay(1.0); release(a) }
        }
        assertEquals(listOf("L interrupted@4.0"), m.log)
        assertEquals(InterruptCause.PREEMPTION, assertIs<DelayResult.Interrupted>(m.results.single()).cause)
    }

    @Test
    @DisplayName("already interrupted at the call: returns at once, with nothing done and no delay events")
    fun alreadyInterrupted() {
        val sink = CollectingSink()
        lateinit var w: RModel.Actor
        val m = run(sink = sink) {
            w = actor("W") {
                val a = seize(r)
                delay(2.0, uses = emptyList())
                note("W", delayOrInterrupt(5.0, suspensionName = "late"), time)
                log.add("hasMissingUnits=${a.hasMissingUnits}")
                release(a)
            }
            at(1.0) { r.fail() }
            at(9.0) { r.repair() }
        }
        assertEquals(listOf("W interrupted@2.0", "hasMissingUnits=true"), m.log)
        val i = assertIs<DelayResult.Interrupted>(m.results.single())
        assertEquals(0.0, i.timeDone)
        assertEquals(5.0, i.timeRemaining)
        assertEquals(0.0, i.timeElapsed)
        assertTrue(sink.events.none { it is AnimationEvent.DelayStarted && it.entityId == w.id && it.suspensionName == "late" })
    }

    @Test
    @DisplayName("uses limits what can end it: another allocation's units going missing are not reported; empty is never ended")
    fun usesLimitsTheReport() {
        val m = run {
            actor("W") {
                val a = seize(r)
                val b = seize(other)
                note("W1", delayOrInterrupt(10.0, uses = listOf(a)), time)
                note("W2", delayOrInterrupt(10.0, uses = emptyList()), time)
                release(a); release(b)
            }
            at(3.0) { other.fail() }
            at(5.0) { other.repair() }
            at(12.0) { r.fail() }
            at(14.0) { r.repair() }
        }
        assertEquals(listOf("W1 completed@10.0", "W2 completed@20.0"), m.log)
    }

    @Test
    @DisplayName("a pool's members passed as uses: the member that fails is the one reported")
    fun poolMembers() {
        lateinit var pa: ResourcePoolAllocation
        val m = run {
            actor("W") { pa = seize(pool, 2); note("W", delayOrInterrupt(10.0, uses = pa.allocations), time); release(pa) }
            at(3.0) { p2.fail() }
            at(4.0) { p2.repair() }
        }
        assertEquals(listOf("W interrupted@3.0"), m.log)
        assertEquals("P2", assertIs<DelayResult.Interrupted>(m.results.single()).by.myResource.name)
    }

    @Test
    @DisplayName("one failure hitting two allocations of the entity: the first in the resource's order is reported")
    fun firstAllocationWins() {
        lateinit var a1: Allocation
        lateinit var a2: Allocation
        val m = run {
            actor("W") {
                a1 = seize(r2); a2 = seize(r2)
                note("W", delayOrInterrupt(10.0), time)
                log.add("other missing=${a2.hasMissingUnits}")
                release(a1); release(a2)
            }
            at(3.0) { r2.fail() }
            at(4.0) { r2.repair() }
        }
        assertEquals(listOf("W interrupted@3.0", "other missing=true"), m.log)
        assertSame(a1, assertIs<DelayResult.Interrupted>(m.results.single()).by)
    }

    // ---------- with process interrupts ----------

    @Test
    @DisplayName("held by a process interrupt, then units go missing: reported at once, and the interrupt's reschedule does nothing")
    fun processInterruptThenReport() {
        lateinit var w: RModel.Actor
        val m = run {
            w = actor("W") {
                val a = seize(r)
                note("W", delayOrInterrupt(10.0, suspensionName = "work"), time)
                LoansAndFreezesCheck.checkEntity(entity)
                delay(30.0, uses = emptyList(), suspensionName = "after")
                log.add("after done@$time")
                release(a)
            }
            actor("I") { delay(2.0); interruptDelayAndContinue(w.p, "work", interruptTime = 5.0); log.add("interrupt over@$time") }
            at(4.0) { r.fail() }
            at(6.0) { r.repair() }
        }
        assertEquals(listOf("W interrupted@4.0", "interrupt over@7.0", "after done@34.0"), m.log)
        val i = assertIs<DelayResult.Interrupted>(m.results.single())
        assertEquals(8.0, i.timeRemaining)
        assertEquals(4.0, i.timeElapsed)
    }

    @Test
    @DisplayName("reported, then a process interrupt aimed at it: an error, as for a process not in that delay")
    fun reportThenProcessInterrupt() {
        lateinit var w: RModel.Actor
        var error: String? = null
        val m = run {
            w = actor("W") {
                val a = seize(r)
                note("W", delayOrInterrupt(10.0, suspensionName = "work"), time)
                delay(30.0, uses = emptyList(), suspensionName = "after")
                release(a)
            }
            at(2.0) { r.fail() }
            actor("I") {
                delay(3.0)
                try {
                    interruptDelayAndContinue(w.p, "work", interruptTime = 5.0)
                } catch (e: IllegalStateException) {
                    error = e.message
                }
            }
            at(6.0) { r.repair() }
        }
        assertEquals(listOf("W interrupted@2.0"), m.log)
        assertNotNull(error)
    }

    @Test
    @DisplayName("the stale interrupt: one that held a reported delay does not touch the next delay another holds")
    fun staleInterrupt() {
        lateinit var w: RModel.Actor
        val m = run {
            w = actor("W") {
                val a = seize(r)
                note("W", delayOrInterrupt(10.0, suspensionName = "work"), time)
                delay(20.0, uses = emptyList(), suspensionName = "second")
                log.add("second done@$time")
                release(a)
            }
            actor("A") { delay(2.0); interruptDelayAndContinue(w.p, "work", interruptTime = 10.0); log.add("A over@$time") }
            at(3.0) { r.fail() }
            actor("B") { delay(5.0); interruptDelayAndContinue(w.p, "second", interruptTime = 3.0) }
            at(30.0) { r.repair() }
        }
        // second: 3 to 5 runs, held 5 to 8 by B, then 18 left: ends at 26; A's end at 12 changes nothing
        assertEquals(listOf("W interrupted@3.0", "A over@12.0", "second done@26.0"), m.log)
    }

    @Test
    @DisplayName("units going missing at the instant the delay's end is due: Completed, no report")
    fun noReportAtTheEndInstant() {
        val m = run {
            actor("W") { val a = seize(r); note("W", delayOrInterrupt(10.0), time); delay(1.0, uses = emptyList()); release(a) }
            at(10.0, priority = ProcessModel.DELAY_PRIORITY - 1) { r.fail() }
            at(20.0) { r.repair() }
        }
        assertEquals(listOf("W completed@10.0"), m.log)
    }

    @Test
    @DisplayName("time remaining after a process interrupt's long post time, and after a restart: the time left, and never a negative time done")
    fun timeRemainingAfterProcessInterrupts() {
        lateinit var w: RModel.Actor
        val m1 = run {
            w = actor("W") { val a = seize(r); note("W", delayOrInterrupt(10.0, suspensionName = "work"), time); release(a) }
            actor("I") { delay(3.0); interruptDelay(w.p, "work", 2.0, postInterruptDelayTime = 50.0) }
            at(10.0) { r.fail() }
            at(11.0) { r.repair() }
        }
        val i1 = assertIs<DelayResult.Interrupted>(m1.results.single())
        assertEquals(45.0, i1.timeRemaining)
        assertEquals(0.0, i1.timeDone)
        val m2 = run {
            w = actor("W") { val a = seize(r); note("W", delayOrInterrupt(10.0, suspensionName = "work"), time); release(a) }
            actor("I") { delay(3.0); interruptDelayAndRestart(w.p, "work", 2.0) }
            at(7.0) { r.fail() }
            at(8.0) { r.repair() }
        }
        val i2 = assertIs<DelayResult.Interrupted>(m2.results.single())
        assertEquals(8.0, i2.timeRemaining)
        assertEquals(2.0, i2.timeDone)
    }

    // ---------- termination, replications, other constructs ----------

    @Test
    @DisplayName("termination between the report and the resume, and a report at the end of a replication: nothing fires afterwards")
    fun terminationAndReplicationEnd() {
        val m = run(reps = 2, length = 20.0) {
            val w = actor("W") { val a = seize(r); note("W", delayOrInterrupt(10.0), time); release(a) }
            at(3.0) { r.fail(); w.terminateProcess(); log.add("terminated") }
            at(4.0) { r.repair() }
            actor("X") { val a = seize(other); note("X", delayOrInterrupt(30.0), time); release(a) }
            at(20.0) { other.fail() }
            actor("Y") { delay(5.0); log.add("Y@$time") }
        }
        // an event at the replication's end time still runs, so X is told at 20; the log is cleared at each
        // start, and the last replication is as the first: nothing of either carries over
        assertEquals(listOf("terminated", "Y@5.0", "X interrupted@20.0"), m.log)
    }

    @Test
    @DisplayName("a resource's behavior holding its own unit in delayOrInterrupt is told of its failure and decides")
    fun behaviorDecides() {
        val m = run(configure = {
            r.behavior {
                val a = seize(r)
                val res = delayOrInterrupt(10.0)
                if (res is DelayResult.Interrupted) log.add("behavior told at $time, ${res.cause}") else log.add("behavior done")
                release(a)
            }
        }) {
            at(4.0) { r.fail() }
            at(6.0) { r.repair() }
        }
        assertEquals(listOf("behavior told at 4.0, FAILURE"), m.log)
    }

    @Test
    @DisplayName("delayOrInterrupt in a process another entity waits on: that process decides, and its caller resumes when it completes")
    fun calledProcess() {
        val m = run {
            val s = Actor("S", 5) {
                val a = seize(r)
                val res = delayOrInterrupt(10.0)
                log.add("S ${if (res is DelayResult.Interrupted) "told" else "done"}@$time")
                release(a)
            }
            actor("C") { waitFor(s.p); log.add("caller@$time") }
            at(3.0) { r.fail() }
            at(4.0) { r.repair() }
        }
        assertEquals(listOf("S told@3.0", "caller@3.0"), m.log)
    }

    @Test
    @DisplayName("the allocation's records under reporting count from the units going missing until they return")
    fun recordsUnderReporting() {
        lateinit var a: Allocation
        val m = run {
            actor("W") {
                a = seize(r)
                note("W", delayOrInterrupt(10.0), time)
                delay(10.0, uses = emptyList())
                release(a)
            }
            at(3.0) { r.fail() }
            at(8.0) { r.repair() }
        }
        assertEquals(listOf("W interrupted@3.0"), m.log)
        assertEquals(1, a.timesInterrupted)
        assertEquals(5.0, a.timeInterrupted, 1e-12)
    }

    @Test
    @DisplayName("warm-up and simulating the same model twice: each run is the same")
    fun warmUpAndSimulatingTwice() {
        val model = Model("ReportingTwice${System.nanoTime()}", autoCSVReports = false)
        val m = RModel(model)
        m.setups.add {
            actor("W") { val a = seize(r); note("W", delayOrInterrupt(10.0), time); release(a) }
            at(13.0) { r.fail() }
            actor("V") { delay(12.0); val a = seize(r); note("V", delayOrInterrupt(10.0), time); release(a) }
            at(15.0) { r.repair() }
        }
        model.numberOfReplications = 2
        model.lengthOfReplication = 50.0
        model.lengthOfReplicationWarmUp = 5.0
        model.simulate()
        val first = m.log.toList()
        model.simulate()
        assertEquals(listOf("W completed@10.0", "V interrupted@13.0"), first)
        assertEquals(first, m.log)
    }

    // ---------- the trace ----------

    @Test
    @DisplayName("the trace: an interrupted delay shows DelayStarted, DelayEnded and DelayInterrupted; a completed one DelayStarted and DelayEnded")
    fun trace() {
        val sink = CollectingSink()
        lateinit var w: RModel.Actor
        lateinit var v: RModel.Actor
        run(sink = sink) {
            w = actor("W") { val a = seize(r); delayOrInterrupt(10.0, suspensionName = "work"); release(a) }
            v = actor("V") { val a = seize(other); delayOrInterrupt(4.0, suspensionName = "quick"); release(a) }
            at(3.0) { r.fail() }
            at(5.0) { r.repair() }
        }
        fun of(id: Long) = sink.events.mapNotNull {
            when (it) {
                is AnimationEvent.DelayStarted -> if (it.entityId == id) "started@${it.simTime}" else null
                is AnimationEvent.DelayEnded -> if (it.entityId == id) "ended@${it.simTime}" else null
                is AnimationEvent.DelayInterrupted -> if (it.entityId == id) "interrupted@${it.simTime} ${it.resourceName} ${it.cause}" else null
                else -> null
            }
        }
        assertEquals(listOf("started@0.0", "ended@3.0", "interrupted@3.0 R FAILURE"), of(w.id))
        assertEquals(listOf("started@0.0", "ended@4.0"), of(v.id))
        val interrupted = sink.events.filterIsInstance<AnimationEvent.DelayInterrupted>()
        val file = Files.createTempFile("interrupted", ".atf")
        try {
            Files.writeString(file, AnimationTraceHeader().encodeToLine() + "\n" + interrupted.joinToString("\n") { AnimationEvent.encodeToLine(it) } + "\n")
            assertEquals(interrupted, TraceFileReader.readAll(file).second)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    // ---------- the metamorphic check ----------

    @Test
    @DisplayName("with no interruptions, and with failures under FINISH, delayOrInterrupt gives the same results as delay")
    fun sameResultsAsDelayWhenNothingIsReported() {
        val reporting = ReactingMetamorphicModels.Work { _, duration ->
            val res = delayOrInterrupt(duration)
            check(res is DelayResult.Completed) { "expected only completed work, got $res" }
        }
        for (variant in listOf("none", "failures FINISH")) {
            assertEquals(
                ReactingMetamorphicModels.results(variant, ReactingMetamorphicModels.plainDelay),
                ReactingMetamorphicModels.results(variant, reporting),
                variant
            )
        }
    }
}
