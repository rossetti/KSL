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

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Finding H-15. Terminating a process used to clean up everything the entity was entangled *with*
 *  — allocations released, queue membership removed without statistics, delay event cancelled — and
 *  leave the entity's own state stale: still bound to the dead process, and still in whatever
 *  suspended state the exception unwound from. The result was an entity that could never run another
 *  process, while an entity whose process *completed* was reusable. Termination now releases the
 *  entity as well.
 *
 *  The core of this file is the suspension matrix. Termination unwinds the coroutine from an
 *  arbitrary suspension point, so it is not enough to check one kind: each way a process can be
 *  suspended leaves the entity in a different state, and every one of them has to end up somewhere
 *  that will accept a new process. The base `EntityState.processEnded()` still raises, so a
 *  suspension kind that lands somewhere unanticipated fails here loudly rather than transitioning
 *  quietly.
 *
 *  Each row asserts the same five things, and the last is the one that matters: the fresh process
 *  must actually **run to completion**, not merely be accepted by `activate`.
 */
/** No production accessor exposes the entity's state name, so the predicates are mapped here. */
private fun entityStateName(e: ProcessModel.Entity): String = when {
    e.isCreated -> "Created"
    e.isScheduled -> "Scheduled"
    e.isActive -> "Active"
    e.isWaitingForSignal -> "WaitingForSignal"
    e.isInHoldQueue -> "InHoldQueue"
    e.isWaitingForResource -> "WaitingForResource"
    e.isWaitingForConveyor -> "WaitingForConveyor"
    e.isProcessEnded -> "ProcessEnded"
    e.isBlockedSending -> "BlockedSending"
    e.isBlockedReceiving -> "BlockedReceiving"
    e.isWaitingForProcess -> "WaitForProcess"
    e.isBlockedUntilCompletion -> "BlockedUntilCompletion"
    e.isWaitingForBatch -> "WaitingForBatch"
    else -> "Unknown"
}

class TerminatedEntityReuseTest {

    // ── Shared harness ──────────────────────────────────────────────────────

    /**
     *  Suspends the subject through [body], terminates it at time 5, and immediately activates a
     *  fresh process on the same entity. Subclasses supply the suspension and whatever collaborators
     *  it needs.
     */
    private abstract class ReuseHarness(parent: ModelElement) : ProcessModel(parent, null) {

        /** The suspension under test. Must leave the subject suspended at time 5. */
        protected abstract val body: suspend KSLProcessBuilder.() -> Unit

        /** How the subject is terminated. Overridden by the supported-reneging variant. */
        protected open fun terminate() {
            subject.terminateProcess()
        }

        /** Collaborators that must exist or be running before the subject starts. */
        protected open fun prepare() {}

        lateinit var subject: Worker

        var stateBefore: String = "?"
        var boundAfter: Boolean = true
        var processEndedAfter: Boolean = false
        var suspendedAfter: Boolean = true
        var suspendTypeAfter: SuspendType = SuspendType.SUSPEND
        var recoveryCompleted: Boolean = false
        var activationFailure: String? = null

        inner class Worker : Entity() {
            val suspension = Suspension("S")
            val doomed = process("doomed") { body() }
            fun recovery(): KSLProcess = process("recovery") {
                delay(1.0)
                recoveryCompleted = true
            }
        }

        override fun initialize() {
            recoveryCompleted = false
            activationFailure = null
            prepare()
            subject = Worker()
            activate(subject.doomed)
            schedule(::terminateAndReuse, 5.0)
        }

        private fun terminateAndReuse(event: KSLEvent<Nothing>) {
            stateBefore = entityStateName(subject)
            terminate()
            boundAfter = subject.hasCurrentProcess
            processEndedAfter = subject.isProcessEnded
            suspendedAfter = subject.isSuspended
            suspendTypeAfter = subject.currentSuspendType
            try {
                activate(subject.recovery())
            } catch (e: IllegalStateException) {
                activationFailure = e.message
            }
        }
    }

    private fun <T : ProcessModel> run(name: String, factory: (Model) -> T): T {
        val m = Model(name)
        val p = factory(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return p
    }

    /** The five assertions every matrix row makes. */
    private fun assertReleasedAndReusable(h: ReuseHarness, expectedStateBefore: String) {
        assertEquals(expectedStateBefore, h.stateBefore, "entity state at the moment of termination")
        assertFalse(h.boundAfter, "termination must clear the entity's binding to the dead process")
        assertTrue(h.processEndedAfter, "the entity must be left in ProcessEnded")
        assertFalse(h.suspendedAfter, "a released entity is not suspended")
        assertEquals(SuspendType.NONE, h.suspendTypeAfter, "the dead process's suspension kind must be cleared")
        assertNull(h.activationFailure, "activating a fresh process must not fail")
        assertTrue(h.recoveryCompleted, "the fresh process must actually run to completion, not merely activate")
    }

    // ── Row 1 · delay ───────────────────────────────────────────────────────

    private class DelayRow(parent: ModelElement) : ReuseHarness(parent) {
        override val body: suspend KSLProcessBuilder.() -> Unit = { delay(100.0) }
    }

    @Test
    @DisplayName("H-15 matrix 1: terminated during delay")
    fun terminatedDuringDelayIsReusable() {
        assertReleasedAndReusable(run("h15delay") { DelayRow(it) }, "Scheduled")
    }

    // ── Row 2 · seize ───────────────────────────────────────────────────────

    private open class SeizeRow(parent: ModelElement) : ReuseHarness(parent) {
        val q = RequestQ(this, "Q")
        val res = ResourceWithQ(this, name = "R", capacity = 1, queue = q)

        inner class Holder : Entity() {
            val work = process("holder") {
                val a = seize(res, 1)
                delay(100.0)
                release(a)
            }
        }

        override fun prepare() {
            activate(Holder().work)
        }

        override val body: suspend KSLProcessBuilder.() -> Unit = {
            val a = seize(res, 1)
            release(a)
        }
    }

    /** The supported reneging path: the request is removed from its queue before the termination. */
    private class SeizeRenegeRow(parent: ModelElement) : SeizeRow(parent) {
        override fun terminate() {
            val request = q.find { it.entity == subject }
            requireNotNull(request) { "the subject's request should be waiting in the queue" }
            q.removeAndTerminate(request)
        }
    }

    @Test
    @DisplayName("H-15 matrix 2a: terminated waiting for a resource, via the bare terminateProcess()")
    fun terminatedWaitingForResourceIsReusable() {
        assertReleasedAndReusable(run("h15seize") { SeizeRow(it) }, "WaitingForResource")
    }

    @Test
    @DisplayName("H-15 matrix 2b: terminated waiting for a resource, via RequestQ.removeAndTerminate")
    fun renegedWaitingForResourceIsReusable() {
        assertReleasedAndReusable(run("h15renege") { SeizeRenegeRow(it) }, "WaitingForResource")
    }

    // ── Row 3 · hold ────────────────────────────────────────────────────────

    private class HoldRow(parent: ModelElement) : ReuseHarness(parent) {
        val hq = HoldQueue(this, "HQ")
        override val body: suspend KSLProcessBuilder.() -> Unit = { hold(hq) }
    }

    @Test
    @DisplayName("H-15 matrix 3: terminated in a hold queue")
    fun terminatedInHoldQueueIsReusable() {
        assertReleasedAndReusable(run("h15hold") { HoldRow(it) }, "InHoldQueue")
    }

    // ── Row 4 · waitFor(signal) ─────────────────────────────────────────────

    private class SignalRow(parent: ModelElement) : ReuseHarness(parent) {
        val signal = Signal(this, "Sig")
        override val body: suspend KSLProcessBuilder.() -> Unit = { waitFor(signal) }
    }

    @Test
    @DisplayName("H-15 matrix 4: terminated waiting for a signal")
    fun terminatedWaitingForSignalIsReusable() {
        assertReleasedAndReusable(run("h15signal") { SignalRow(it) }, "WaitingForSignal")
    }

    // ── Row 5 · blocked sending ─────────────────────────────────────────────

    private class BlockedSendRow(parent: ModelElement) : ReuseHarness(parent) {
        val bq = BlockingQueue<ModelElement.QObject>(this, capacity = 1, name = "BQ")

        inner class Filler : Entity() {
            val work = process("filler") { send(QObject("fill"), bq) }
        }

        override fun prepare() {
            activate(Filler().work)
        }

        override val body: suspend KSLProcessBuilder.() -> Unit = { send(QObject("blocked"), bq) }
    }

    @Test
    @DisplayName("H-15 matrix 5: terminated blocked sending to a full channel")
    fun terminatedBlockedSendingIsReusable() {
        assertReleasedAndReusable(run("h15send") { BlockedSendRow(it) }, "BlockedSending")
    }

    // ── Row 6 · blocked receiving ───────────────────────────────────────────

    private class BlockedReceiveRow(parent: ModelElement) : ReuseHarness(parent) {
        val bq = BlockingQueue<ModelElement.QObject>(this, name = "BQ")
        override val body: suspend KSLProcessBuilder.() -> Unit = { waitForItems(bq, 1) }
    }

    @Test
    @DisplayName("H-15 matrix 6: terminated blocked receiving from an empty channel")
    fun terminatedBlockedReceivingIsReusable() {
        assertReleasedAndReusable(run("h15receive") { BlockedReceiveRow(it) }, "BlockedReceiving")
    }

    // ── Row 7 · waitFor(process) ────────────────────────────────────────────

    private class WaitForProcessRow(parent: ModelElement) : ReuseHarness(parent) {
        lateinit var callee: Callee

        inner class Callee : Entity() {
            val work = process("callee") { delay(100.0) }
        }

        override fun prepare() {
            callee = Callee()
        }

        override val body: suspend KSLProcessBuilder.() -> Unit = { waitFor(callee.work) }
    }

    @Test
    @DisplayName("H-15 matrix 7: terminated waiting for another process")
    fun terminatedWaitingForProcessIsReusable() {
        assertReleasedAndReusable(run("h15waitProcess") { WaitForProcessRow(it) }, "WaitForProcess")
    }

    // ── Row 8 · blockUntilCompleted ─────────────────────────────────────────

    private class BlockUntilCompletedRow(parent: ModelElement) : ReuseHarness(parent) {
        lateinit var other: Other

        inner class Other : Entity() {
            val work = process("other") { delay(100.0) }
        }

        override fun prepare() {
            other = Other()
            activate(other.work)
        }

        override val body: suspend KSLProcessBuilder.() -> Unit = { blockUntilCompleted(other.work) }
    }

    @Test
    @DisplayName("H-15 matrix 8: terminated blocking until another process completes")
    fun terminatedBlockedUntilCompletionIsReusable() {
        assertReleasedAndReusable(run("h15blockUntil") { BlockUntilCompletedRow(it) }, "BlockedUntilCompletion")
    }

    // ── Row 9 · conveyor ────────────────────────────────────────────────────

    /**
     *  A conveyor request suspends through `hold()` on the conveyor's accessing hold queue, so the
     *  entity is `InHoldQueue`, not `WaitingForConveyor` — that state has no call sites at all.
     */
    private class ConveyorRow(parent: ModelElement) : ReuseHarness(parent) {
        private val entry = "Entry"
        private val exitLoc = "Exit"

        val conveyor: Conveyor = Conveyor.builder(this, "Conv")
            .conveyorType(Conveyor.Type.ACCUMULATING)
            .velocity(1.0)
            .cellSize(1)
            .maxCellsAllowed(1)
            .firstSegment(entry, exitLoc, 10)
            .build()

        inner class Occupier : Entity() {
            val work = process("occupier") {
                requestConveyor(conveyor = conveyor, entryLocation = entry, numCellsNeeded = 1)
                delay(100.0)
            }
        }

        override fun prepare() {
            activate(Occupier().work)
        }

        override val body: suspend KSLProcessBuilder.() -> Unit = {
            requestConveyor(conveyor = conveyor, entryLocation = entry, numCellsNeeded = 1)
        }
    }

    @Test
    @DisplayName("H-15 matrix 9: terminated waiting to access a conveyor")
    fun terminatedWaitingForConveyorIsReusable() {
        assertReleasedAndReusable(run("h15conveyor") { ConveyorRow(it) }, "InHoldQueue")
    }

    // ── Row 11 · suspendFor ─────────────────────────────────────────────────

    /**
     *  Neither `suspend` nor `suspendFor` transitions the entity state, so the entity is still
     *  `Active` while its coroutine is suspended. `Active` already implemented `processEnded()`,
     *  which is why this row needed no new transition.
     */
    private class SuspendForRow(parent: ModelElement) : ReuseHarness(parent) {
        override val body: suspend KSLProcessBuilder.() -> Unit = { suspendFor(subject.suspension) }
    }

    @Test
    @DisplayName("H-15 matrix 11: terminated in a generic suspension")
    fun terminatedInSuspendForIsReusable() {
        assertReleasedAndReusable(run("h15suspendFor") { SuspendForRow(it) }, "Active")
    }

    // ── Row 12 · waitFor(blockage) ──────────────────────────────────────────

    private class BlockageRow(parent: ModelElement) : ReuseHarness(parent) {
        lateinit var blocker: Blocker

        inner class Blocker : Entity() {
            val blk = Blockage("B")
            val work = process("blocker") {
                startBlockage(blk)
                delay(100.0)
                clearBlockage(blk)
            }
        }

        override fun prepare() {
            blocker = Blocker()
            activate(blocker.work)
        }

        override val body: suspend KSLProcessBuilder.() -> Unit = { waitFor(blocker.blk) }
    }

    @Test
    @DisplayName("H-15 matrix 12: terminated waiting on a blockage")
    fun terminatedWaitingOnBlockageIsReusable() {
        assertReleasedAndReusable(run("h15blockage") { BlockageRow(it) }, "BlockedUntilCompletion")
    }

    // ── Row 10 · batch ──────────────────────────────────────────────────────

    /**
     *  Batching needs the subject to be a `BatchingEntity`, which the shared harness cannot provide,
     *  so this row is built out separately with the same assertions.
     */
    private class BatchRow(parent: ModelElement) : ProcessModel(parent, null) {
        val bq = BatchQueue<Part>(this, defaultBatchSize = 2, name = "BatchQ")

        lateinit var subject: Part

        var stateBefore: String = "?"
        var boundAfter: Boolean = true
        var processEndedAfter: Boolean = false
        var suspendedAfter: Boolean = true
        var suspendTypeAfter: SuspendType = SuspendType.SUSPEND
        var recoveryCompleted: Boolean = false
        var activationFailure: String? = null

        inner class Part : BatchingEntity<Part>() {
            val doomed = process("doomed") {
                waitedForBatch(this@Part, bq, "TheBatch", 2)
            }

            fun recovery(): KSLProcess = process("recovery") {
                delay(1.0)
                recoveryCompleted = true
            }
        }

        override fun initialize() {
            recoveryCompleted = false
            activationFailure = null
            subject = Part()
            activate(subject.doomed)
            schedule(::terminateAndReuse, 5.0)
        }

        private fun terminateAndReuse(event: KSLEvent<Nothing>) {
            stateBefore = if (subject.isWaitingForBatch) "WaitingForBatch" else "Unexpected"
            subject.terminateProcess()
            boundAfter = subject.hasCurrentProcess
            processEndedAfter = subject.isProcessEnded
            suspendedAfter = subject.isSuspended
            suspendTypeAfter = subject.currentSuspendType
            try {
                activate(subject.recovery())
            } catch (e: IllegalStateException) {
                activationFailure = e.message
            }
        }
    }

    @Test
    @DisplayName("H-15 matrix 10: terminated waiting to form a batch")
    fun terminatedWaitingForBatchIsReusable() {
        val m = run("h15batch") { BatchRow(it) }
        assertEquals("WaitingForBatch", m.stateBefore)
        assertFalse(m.boundAfter, "termination must clear the entity's binding to the dead process")
        assertTrue(m.processEndedAfter, "the entity must be left in ProcessEnded")
        assertFalse(m.suspendedAfter, "a released entity is not suspended")
        assertEquals(SuspendType.NONE, m.suspendTypeAfter)
        assertNull(m.activationFailure)
        assertTrue(m.recoveryCompleted, "the fresh process must actually run to completion")
    }
}
