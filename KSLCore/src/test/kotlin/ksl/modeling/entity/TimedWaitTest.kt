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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  The mechanism of a wait with a time limit, exercised through `suspendForOrTimeout`, which has no
 *  trigger kinds of its own: the holder of the suspension is one claim and the timer the other.
 */
class TimedWaitTest {

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    /**
     *  The subject starts at [startAt], waits on its suspension with [timeout], and records the
     *  result. The holder resumes the suspension at [resumeAt] (if not null), at [holderPriority].
     */
    private class HolderModel(
        parent: ModelElement,
        val startAt: Double = 0.0,
        val timeout: Double = 10.0,
        val resumeAt: Double? = null,
        val holderPriority: Int = KSLEvent.DEFAULT_PRIORITY,
        val timeoutPriority: Int = ProcessModel.TIMEOUT_PRIORITY,
        val terminateAt: Double? = null,
        val lateResumeAt: Double? = null,
    ) : ProcessModel(parent, "holder") {

        lateinit var subject: Subject
        val results = mutableListOf<WaitResult<Unit>>()
        val endTimes = mutableListOf<Double>()
        val holderErrors = mutableListOf<String>()
        var timerEventSeen: KSLEvent<WaitGroup>? = null
        var timerSeenAtStart = false
        var startError: String? = null

        inner class Subject : Entity("subject") {
            val suspension = Suspension("gate")
            val p: KSLProcess = process("wait") {
                if (startAt > 0.0) delay(startAt)
                try {
                    val r = suspendForOrTimeout(suspension, timeout, timeoutPriority)
                    results.add(r)
                    endTimes.add(time)
                } catch (e: IllegalArgumentException) {
                    startError = e.message
                }
            }
        }

        override fun initialize() {
            results.clear()
            endTimes.clear()
            holderErrors.clear()
            subject = Subject()
            activate(subject.p)
            resumeAt?.let { schedule(::holderResumes, it, priority = holderPriority) }
            lateResumeAt?.let { schedule(::holderResumes, it) }
            terminateAt?.let { schedule(::terminate, it) }
            schedule(::look, startAt + 0.5)
        }

        private fun look(event: KSLEvent<Nothing>) {
            timerEventSeen = subject.myWaitGroup?.timerEvent
            timerSeenAtStart = subject.myWaitGroup != null
        }

        private fun holderResumes(event: KSLEvent<Nothing>) {
            try {
                subject.suspension.resume()
            } catch (e: IllegalArgumentException) {
                holderErrors.add(e.message ?: "")
            }
        }

        private fun terminate(event: KSLEvent<Nothing>) {
            subject.terminateProcess()
        }
    }

    private fun run(
        replications: Int = 1,
        length: Double = 100.0,
        sink: CollectingSink? = null,
        build: (Model) -> HolderModel,
    ): HolderModel {
        val model = Model("TimedWait${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = build(model)
        model.numberOfReplications = replications
        model.lengthOfReplication = length
        model.simulate()
        return m
    }

    /** Runs [block] with the process model's logger at TRACE, and returns what it logged. */
    private fun captureTrace(block: () -> Unit): List<String> {
        val logger = LoggerFactory.getLogger(ProcessModel::class.java.name) as Logger
        val before = logger.level
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        logger.level = Level.TRACE
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            logger.level = before
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }

    @Test
    @DisplayName("resumed before the deadline: satisfied, the time waited, and the timer cancelled")
    fun resumedBeforeDeadline() {
        val m = run { HolderModel(it, timeout = 10.0, resumeAt = 3.0) }
        val r = m.results.single()
        assertFalse(r.timedOut)
        assertEquals(Unit, r.value)
        assertEquals(3.0, r.timeWaited, 1e-12)
        assertEquals(listOf(3.0), m.endTimes)
        val timer = assertNotNull(m.timerEventSeen)
        assertTrue(timer.cancel, "the timer must be cancelled when something else ends the wait")
    }

    @Test
    @DisplayName("not resumed: timed out at exactly start + timeout; value throws; valueOrNull is null")
    fun notResumed() {
        val m = run { HolderModel(it, startAt = 2.0, timeout = 10.0) }
        val r = m.results.single()
        assertTrue(r.timedOut)
        assertEquals(10.0, r.timeWaited, 1e-12)
        assertEquals(listOf(12.0), m.endTimes)
        assertNull(r.valueOrNull())
        assertTrue(runCatching { r.value }.exceptionOrNull() is IllegalStateException)
        assertFalse(m.subject.suspension.isSuspended)
        assertFalse(m.subject.suspension.isResumed)
        assertFalse(m.subject.suspension.isAbandoned)
    }

    @Test
    @DisplayName("resume and deadline at one instant: the resume wins at default priorities")
    fun sameInstantDefaultPriorities() {
        val m = run { HolderModel(it, timeout = 10.0, resumeAt = 10.0) }
        val r = m.results.single()
        assertFalse(r.timedOut)
        assertEquals(10.0, r.timeWaited, 1e-12)
        assertEquals(emptyList(), m.holderErrors)
    }

    @Test
    @DisplayName("resume and deadline at one instant: an earlier timeout priority times out, and the holder's resume then fails")
    fun sameInstantEarlierTimeoutPriority() {
        val m = run {
            HolderModel(it, timeout = 10.0, resumeAt = 10.0, timeoutPriority = KSLEvent.DEFAULT_PRIORITY - 1)
        }
        val r = m.results.single()
        assertTrue(r.timedOut)
        assertEquals(1, m.holderErrors.size, "the holder's resume after the timeout must fail")
        assertTrue(m.holderErrors.single().contains("timed out"), m.holderErrors.single())
        assertEquals(1, m.endTimes.size, "never a double resume")
    }

    @Test
    @DisplayName("a zero timeout suspends, so an event at the same instant can still satisfy it")
    fun zeroTimeout() {
        val satisfied = run {
            HolderModel(it, startAt = 5.0, timeout = 0.0, resumeAt = 5.0, holderPriority = KSLEvent.LOW_PRIORITY - 100)
        }
        assertFalse(satisfied.results.single().timedOut)
        assertEquals(0.0, satisfied.results.single().timeWaited)
        val timedOut = run { HolderModel(it, startAt = 5.0, timeout = 0.0) }
        assertTrue(timedOut.results.single().timedOut)
        assertEquals(listOf(5.0), timedOut.endTimes)
    }

    @Test
    @DisplayName("an infinite timeout schedules no timer")
    fun infiniteTimeout() {
        val m = run { HolderModel(it, timeout = Double.POSITIVE_INFINITY, resumeAt = 50.0) }
        assertTrue(m.timerSeenAtStart)
        assertNull(m.timerEventSeen)
        assertEquals(50.0, m.results.single().timeWaited, 1e-12)
    }

    @Test
    @DisplayName("a negative or NaN timeout is rejected, naming the value")
    fun badTimeouts() {
        val negative = run { HolderModel(it, timeout = -1.0) }
        assertTrue(negative.startError!!.contains("-1.0"), negative.startError)
        val nan = run { HolderModel(it, timeout = Double.NaN) }
        assertTrue(nan.startError!!.contains("NaN"), nan.startError)
    }

    @Test
    @DisplayName("termination during the wait: nothing fires afterwards; the suspension is abandoned")
    fun terminationDuringWait() {
        val sink = CollectingSink()
        lateinit var m: HolderModel
        val log = captureTrace { m = run(sink = sink) { HolderModel(it, timeout = 10.0, terminateAt = 4.0) } }
        assertTrue(m.results.isEmpty())
        assertTrue(m.subject.suspension.isAbandoned)
        assertTrue(m.timerEventSeen!!.cancel, "termination must cancel the timer")
        assertTrue(sink.events.none { it is AnimationEvent.WaitTimedOut })
        assertTrue(log.none { it.contains("EVENT : TIMEOUT") || it.contains("TIMEOUT IGNORED") })
        assertNull(m.subject.myWaitGroup)
    }

    @Test
    @DisplayName("a wait open at the end of a replication: its timer is never on the calendar and nothing reaches the next replication")
    fun openAtEndOfReplication() {
        val sink = CollectingSink()
        val m = run(replications = 2, length = 20.0, sink = sink) { HolderModel(it, timeout = 50.0) }
        assertTrue(m.results.isEmpty(), "neither replication's wait can end")
        assertFalse(m.timerEventSeen!!.isScheduled, "a deadline past the end of the run is never placed on the calendar")
        assertTrue(sink.events.none { it is AnimationEvent.WaitTimedOut })
    }

    @Test
    @DisplayName("a timeout is traced with its source and emits WaitTimedOut once")
    fun timeoutTraceAndAnimation() {
        val sink = CollectingSink()
        lateinit var m: HolderModel
        val log = captureTrace { m = run(sink = sink) { HolderModel(it, timeout = 10.0) } }
        val timedOut = sink.events.filterIsInstance<AnimationEvent.WaitTimedOut>()
        assertEquals(1, timedOut.size)
        assertEquals(10.0, timedOut.single().simTime)
        assertEquals(m.subject.id, timedOut.single().entityId)
        assertEquals("gate", timedOut.single().suspensionName)
        assertTrue(log.any { it.contains("source=TIMEOUT") }, "the scheduled resume names its source")
    }

    /** The holder calls resumeProcess() on the subject instead of resuming the suspension. */
    private class ExternalResumeModel(parent: ModelElement, val timed: Boolean) : ProcessModel(parent, "external") {
        lateinit var subject: Subject
        var error: String? = null
        var canResumeDuring: Boolean? = null
        var result: WaitResult<Unit>? = null
        var endTime = Double.NaN

        inner class Subject : Entity("subject") {
            val suspension = Suspension("gate")
            val p: KSLProcess = process {
                if (timed) {
                    result = suspendForOrTimeout(suspension, 10.0)
                } else {
                    suspendFor(suspension)
                }
                endTime = time
            }
        }

        override fun initialize() {
            subject = Subject()
            activate(subject.p)
            schedule(::poke, 2.0)
            if (timed) schedule(::release, 4.0)
        }

        private fun poke(event: KSLEvent<Nothing>) {
            canResumeDuring = subject.canResumeProcess
            try {
                subject.resumeProcess()
            } catch (e: IllegalStateException) {
                error = e.message
            }
        }

        private fun release(event: KSLEvent<Nothing>) {
            subject.suspension.resume()
        }
    }

    private fun runExternal(timed: Boolean): ExternalResumeModel {
        val model = Model("External${System.nanoTime()}", autoCSVReports = false)
        val m = ExternalResumeModel(model, timed)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("resumeProcess() on an entity in a timed wait throws at the call and leaves the wait alone")
    fun externalResumeOfTimedWait() {
        val m = runExternal(timed = true)
        assertEquals(false, m.canResumeDuring)
        assertTrue(m.error!!.contains("canResumeProcess"), m.error)
        assertFalse(m.result!!.timedOut)
        assertEquals(4.0, m.endTime, "the wait was ended by its holder, not by the refused resume")
    }

    @Test
    @DisplayName("an untimed wait can be resumed externally as before, and canResumeProcess says so")
    fun externalResumeOfUntimedWait() {
        val m = runExternal(timed = false)
        assertEquals(true, m.canResumeDuring)
        assertNull(m.error)
        assertEquals(2.0, m.endTime)
    }

    /** One suspension used for a timed wait that times out, then again timed, then untimed. */
    private class ReuseModel(parent: ModelElement) : ProcessModel(parent, "reuse") {
        lateinit var subject: Subject
        val log = mutableListOf<String>()

        inner class Subject : Entity("subject") {
            val suspension = Suspension("gate")
            val p: KSLProcess = process {
                val first = suspendForOrTimeout(suspension, 5.0)
                log.add("first timedOut=${first.timedOut}@$time")
                val second = suspendForOrTimeout(suspension, 5.0)
                log.add("second timedOut=${second.timedOut}@$time")
                suspendFor(suspension)
                log.add("third@$time")
            }
        }

        override fun initialize() {
            subject = Subject()
            activate(subject.p)
            schedule(::release, 7.0)
            schedule(::release, 9.0)
        }

        private fun release(event: KSLEvent<Nothing>) {
            if (subject.suspension.isSuspended) subject.suspension.resume() else log.add("not suspended@$time")
        }
    }

    @Test
    @DisplayName("a suspension that timed out can be waited on again, timed and untimed")
    fun suspensionReuse() {
        val model = Model("Reuse${System.nanoTime()}", autoCSVReports = false)
        val m = ReuseModel(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        assertEquals(listOf("first timedOut=true@5.0", "second timedOut=false@7.0", "third@9.0"), m.log)
    }

    /** Another process tries to interrupt a delay of a process that is in a timed wait. */
    private class InterruptModel(parent: ModelElement) : ProcessModel(parent, "interrupt") {
        lateinit var subject: Subject
        var error: Throwable? = null
        var result: WaitResult<Unit>? = null

        inner class Subject : Entity("subject") {
            val suspension = Suspension("gate")
            val p: KSLProcess = process("waiting") {
                result = suspendForOrTimeout(suspension, 10.0)
            }
        }

        inner class Interrupter : Entity("interrupter") {
            val p: KSLProcess = process {
                delay(2.0)
                try {
                    interruptDelay(subject.p, "service", 1.0, postInterruptDelayTime = 1.0)
                } catch (e: IllegalStateException) {
                    error = e
                } catch (e: IllegalArgumentException) {
                    error = e
                }
            }
        }

        override fun initialize() {
            subject = Subject()
            activate(subject.p)
            activate(Interrupter().p)
        }
    }

    @Test
    @DisplayName("interruptDelay aimed at a process in a timed wait throws; the wait is untouched")
    fun interruptDelayOfTimedWait() {
        val model = Model("Interrupt${System.nanoTime()}", autoCSVReports = false)
        val m = InterruptModel(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        assertNotNull(m.error, "the interrupt must be refused")
        assertTrue(m.result!!.timedOut, "the timed wait ran to its deadline")
        assertEquals(10.0, m.result!!.timeWaited, 1e-12)
    }
}
