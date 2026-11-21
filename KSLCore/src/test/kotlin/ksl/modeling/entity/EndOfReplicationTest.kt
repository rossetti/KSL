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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  What model code may do while a replication's remaining entities are terminated at its end: a termination hook
 *  or a finally block that wakes or activates an entity has its request dropped, and the run goes on to the next
 *  replication. Forty replications are run where the order of the clean-up can vary.
 */
class EndOfReplicationTest {

    private fun run(model: Model, reps: Int, length: Double): Throwable? {
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        return try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
    }

    private class RecoveryAtEnd(parent: ModelElement, val skipWhenEnding: Boolean) : ProcessModel(parent, "Recovery") {
        var recoveries = 0
        var hookSawEnding = mutableListOf<Boolean>()
        var activationScheduled = mutableListOf<Boolean>()

        inner class Worker : Entity() {
            val work: KSLProcess = process("work") { delay(100.0) }
            fun recovery(): KSLProcess = process("recovery") { delay(1.0); recoveries++ }
            override fun handleTerminatedProcess(terminatedProcess: KSLProcess) {
                hookSawEnding.add(isReplicationEnding)
                if (skipWhenEnding && isReplicationEnding) return
                activationScheduled.add(activate(recovery()).isScheduled)
            }
        }

        override fun initialize() {
            activate(Worker().work)
        }
    }

    @Test
    @DisplayName("a termination hook that activates a recovery process at the end of a replication has the activation dropped")
    fun recoveryHookAtTheEnd() {
        val model = Model("RecoveryAtEnd${System.nanoTime()}", autoCSVReports = false)
        val pm = RecoveryAtEnd(model, skipWhenEnding = false)
        val error = run(model, reps = 2, length = 50.0)
        assertNull(error, "the run stopped: $error")
        assertEquals(0, pm.recoveries)
        assertEquals(listOf(true, true), pm.hookSawEnding)
        assertEquals(listOf(false, false), pm.activationScheduled, "the activation event is not scheduled")
    }

    @Test
    @DisplayName("a termination hook can tell it runs at the end of a replication and skip its recovery")
    fun hookReadsTheFlag() {
        val model = Model("RecoverySkip${System.nanoTime()}", autoCSVReports = false)
        val pm = RecoveryAtEnd(model, skipWhenEnding = true)
        val error = run(model, reps = 2, length = 50.0)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf(true, true), pm.hookSawEnding)
        assertTrue(pm.activationScheduled.isEmpty())
    }

    /** An owner caught in a long delay at the end, whose finally block wakes a partner in one of five ways. */
    private class FinallyAtEnd(parent: ModelElement, val kind: Int) : ProcessModel(parent, "Finally$kind") {
        var sig: Signal? = Signal(this, "S$kind")
        var hq: HoldQueue? = HoldQueue(this, "HQ$kind")

        inner class Owner : Entity() {
            val b = Blockage("b")
            val partner = Partner(this)
            val p: KSLProcess = process("owner") {
                when (kind) {
                    1 -> { startBlockage(b); try { delay(1000.0) } finally { clearBlockage(b) } }
                    2 -> try { delay(1000.0) } finally { sig!!.signalAll() }
                    3 -> try { delay(1000.0) } finally { if (partner.s.isSuspended) resume(partner.s) }
                    4 -> try { delay(1000.0) } finally { if (hq!!.contains(partner)) hq!!.removeAndResume(partner) }
                    5 -> try { delay(1000.0) } finally { if (hq!!.contains(partner)) hq!!.removeAndImmediateResume(partner) }
                }
            }
        }

        inner class Partner(val owner: Owner) : Entity() {
            val s = Suspension("partner")
            val p: KSLProcess = process("partner") {
                when (kind) {
                    1 -> waitFor(owner.b)
                    2 -> waitFor(sig!!)
                    3 -> suspendFor(s)
                    4, 5 -> hold(hq!!)
                }
            }
        }

        override fun initialize() {
            val o = Owner()
            activate(o.p)
            activate(o.partner.p, 1.0)
        }
    }

    private fun finallyAtEnd(kind: Int, siblings: Boolean = false): Throwable? {
        val model = Model("FinallyAtEnd$kind${System.nanoTime()}", autoCSVReports = false)
        val pm = FinallyAtEnd(model, kind)
        if (siblings) {
            // made after the process model, so they are cleaned up after it
            pm.sig = Signal(model, "SiblingS$kind")
            pm.hq = HoldQueue(model, "SiblingHQ$kind")
        }
        return run(model, reps = 40, length = 10.0)
    }

    @Test
    @DisplayName("a finally block that clears a blockage at the end of a replication")
    fun finallyClearsABlockage() = assertNull(finallyAtEnd(1))

    @Test
    @DisplayName("a finally block that signals at the end of a replication")
    fun finallySignals() = assertNull(finallyAtEnd(2))

    @Test
    @DisplayName("a finally block that resumes a suspension at the end of a replication")
    fun finallyResumesASuspension() = assertNull(finallyAtEnd(3))

    @Test
    @DisplayName("a finally block that removes and resumes from a hold queue at the end of a replication")
    fun finallyRemovesAndResumes() = assertNull(finallyAtEnd(4))

    @Test
    @DisplayName("a finally block that removes and immediately resumes from a hold queue at the end of a replication")
    fun finallyRemovesAndImmediatelyResumes() = assertNull(finallyAtEnd(5))

    @Test
    @DisplayName("a finally block that signals a signal cleaned up after the process model")
    fun finallySignalsASiblingSignal() = assertNull(finallyAtEnd(2, siblings = true))

    @Test
    @DisplayName("a finally block that resumes from a hold queue cleaned up after the process model")
    fun finallyResumesFromASiblingQueue() = assertNull(finallyAtEnd(4, siblings = true))

    /** A helper whose hook terminates its owner, when the termination began at the owner. */
    private class HookTerminatesOwner(parent: ModelElement) : ProcessModel(parent, "HookOwner") {
        lateinit var owner: Owner
        var ownerHook = 0

        inner class Helper(val owner: Owner) : Entity() {
            val p: KSLProcess = process("helper") { delay(100.0) }
            override fun handleTerminatedProcess(terminatedProcess: KSLProcess) {
                owner.terminateProcess()
            }
        }

        inner class Owner : Entity() {
            val p: KSLProcess = process("owner") { waitFor(Helper(this@Owner).p) }
            override fun handleTerminatedProcess(terminatedProcess: KSLProcess) {
                ownerHook++
            }
        }

        override fun initialize() {
            owner = Owner()
            activate(owner.p)
            schedule({ _: KSLEvent<Nothing> -> owner.terminateProcess() }, 5.0)
        }
    }

    @Test
    @DisplayName("a hook that terminates an entity already being terminated does nothing more")
    fun hookTerminatesAnEntityBeingTerminated() {
        val model = Model("HookOwner${System.nanoTime()}", autoCSVReports = false)
        val pm = HookTerminatesOwner(model)
        val error = run(model, reps = 1, length = 50.0)
        assertNull(error, "the run stopped: $error")
        assertEquals(1, pm.ownerHook)
    }

    @Test
    @DisplayName("while events run, the flag is false")
    fun flagIsFalseDuringTheRun() {
        val model = Model("Flag${System.nanoTime()}", autoCSVReports = false)
        val seen = mutableListOf<Boolean>()
        object : ProcessModel(model, "FlagModel") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> seen.add(isReplicationEnding) }, 1.0)
            }
        }
        val error = run(model, reps = 1, length = 5.0)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf(false), seen)
    }

    private class LateActivation(parent: ModelElement, val inReplicationEnded: Boolean) : ProcessModel(parent, "Late") {
        inner class Worker : Entity() {
            val work: KSLProcess = process("work") { delay(1.0) }
        }

        override fun replicationEnded() {
            if (inReplicationEnded) activate(Worker().work)
        }

        fun activateNow() = activate(Worker().work)
    }

    @Test
    @DisplayName("outside the clean-up, scheduling after the replication's events have ended is still an error")
    fun lateActivationOutsideTheCleanUpThrows() {
        val inEnded = Model("LateEnded${System.nanoTime()}", autoCSVReports = false)
        LateActivation(inEnded, inReplicationEnded = true)
        val error = run(inEnded, reps = 1, length = 5.0)
        assertTrue(error != null, "an activation in replicationEnded() throws")
        val after = Model("LateAfter${System.nanoTime()}", autoCSVReports = false)
        val pm = LateActivation(after, inReplicationEnded = false)
        assertNull(run(after, reps = 1, length = 5.0))
        assertTrue(runCatching { pm.activateNow() }.isFailure, "an activation after the run throws")
    }
}
