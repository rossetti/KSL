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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  The `waitForFirst` engine, independent of any real source: a test gate stands in for a mailbox,
 *  a signal or a blocking queue. Each gate keeps the registrations of the waits on it; opening it
 *  claims the earliest, as a source claims at the event that causes the trigger to fire.
 */
class WaitForFirstEngineTest {

    /** A stand-in for a source. [readyValue] makes its trigger able to fire at once. */
    private class Gate(val name: String) {
        val waiting = mutableListOf<GateRegistration>()
        val events = mutableListOf<String>()
        var readyValue: String? = null
        var registerCount = 0

        /** If set, the gate claims a wait as it registers, as a blocking queue does for a request it can fill. */
        var claimAtRegister: String? = null

        /** The state its trigger asks for when it is the only trigger. */
        var aloneState: AloneState = AloneState.ACTIVE

        /** Claims the earliest wait on this gate; returns whether a claim succeeded. */
        fun open(value: String): Boolean {
            val registration = waiting.firstOrNull() ?: return false
            return registration.group.claim(registration, value, ResumeSource.UNKNOWN_INTERNAL, ProcessModel.RESUME_PRIORITY)
        }

        fun trigger(): WaitTrigger<String> = GateTrigger(this)
    }

    private class GateTrigger(val gate: Gate) : WaitTrigger<String>() {
        override fun canFireNow(entity: ProcessModel.Entity): Boolean = gate.readyValue != null
        override fun fireNow(process: ProcessModel.Entity.ProcessCoroutine): String = gate.readyValue!!
        override fun register(group: WaitGroup): Registration {
            gate.registerCount++
            val registration = GateRegistration(this, group).also { gate.waiting.add(it) }
            gate.claimAtRegister?.let { group.claim(registration, it, ResumeSource.UNKNOWN_INTERNAL, ProcessModel.RESUME_PRIORITY) }
            return registration
        }
        override val aloneState: AloneState get() = gate.aloneState
        override fun toString(): String = "GateTrigger(${gate.name})"
    }

    private class GateRegistration(val gateTrigger: GateTrigger, group: WaitGroup) : Registration(gateTrigger, group) {
        override fun onReleaseAsLoser() {
            gateTrigger.gate.waiting.remove(this)
            gateTrigger.gate.events.add("loser")
        }

        override fun onReleaseOnTermination() {
            gateTrigger.gate.waiting.remove(this)
            gateTrigger.gate.events.add("terminated")
        }

        override suspend fun ProcessModel.Entity.ProcessCoroutine.afterWinningResume(): AfterResume {
            gateTrigger.gate.waiting.remove(this@GateRegistration)
            markReleased()
            gateTrigger.gate.events.add("winner")
            return AfterResume.Value(group.value)
        }
    }

    /**
     *  The subject waits for the first of gates A and B (or only A when [single]) with [timeout].
     *  [actions] run at their times.
     */
    private class EngineModel(
        parent: ModelElement,
        val single: Boolean = false,
        val timeout: Double = Double.POSITIVE_INFINITY,
        val actions: Map<Double, EngineModel.() -> Unit> = emptyMap(),
    ) : ProcessModel(parent, "engine") {
        val a = Gate("A")
        val b = Gate("B")
        val ta = a.trigger()
        val tb = b.trigger()
        lateinit var subject: Subject
        var result: WaitForFirstResult? = null
        var endTime = Double.NaN
        val observed = mutableListOf<String>()

        var afterDelay = Double.NaN

        inner class Subject : Entity("subject") {
            val p: KSLProcess = process {
                result = if (single) waitForFirst(ta, timeout = timeout, suspensionName = "w")
                else waitForFirst(ta, tb, timeout = timeout, suspensionName = "w")
                endTime = time
                delay(10.0)
                afterDelay = time
            }
        }

        override fun initialize() {
            subject = Subject()
            activate(subject.p)
            for ((t, action) in actions) {
                schedule({ _: KSLEvent<Nothing> -> action() }, t)
            }
        }

        fun schedule(action: (KSLEvent<Nothing>) -> Unit, t: Double) {
            schedule(EventActionIfc<Nothing> { action(it) }, t)
        }

        fun observeState() {
            observed.add("waitingForFirst=${subject.isWaitingForFirst}, type=${subject.currentSuspendType}, suspended=${subject.isSuspended}" +
                    ", signal=${subject.isWaitingForSignal}, receiving=${subject.isBlockedReceiving}")
        }
    }

    private fun run(build: (Model) -> EngineModel): EngineModel {
        val model = Model("Engine${System.nanoTime()}", autoCSVReports = false)
        val m = build(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("the second trigger fires: it wins with its value; the loser is released at the claim")
    fun secondTriggerWins() {
        var claimedAgain: Boolean? = null
        var aWaitingAfterClaim = -1
        val m = run {
            EngineModel(it, timeout = 20.0, actions = mapOf(
                1.0 to { observeState() },
                3.0 to {
                    b.open("hello")
                    aWaitingAfterClaim = a.waiting.size
                    claimedAgain = a.waiting.firstOrNull()?.group?.claim(null, null, ResumeSource.UNKNOWN_INTERNAL, 1) ?: false
                },
            ))
        }
        val r = assertNotNull(m.result)
        assertTrue(r.firedBy(m.tb))
        assertEquals("hello", r.valueOf(m.tb))
        assertEquals(3.0, r.timeWaited, 1e-12)
        assertEquals(3.0, m.endTime)
        assertEquals(0, aWaitingAfterClaim, "the losing registration leaves its source at the claim, not at the resume")
        assertEquals(false, claimedAgain)
        assertEquals(listOf("loser"), m.a.events)
        assertEquals(listOf("winner"), m.b.events)
        assertEquals(listOf("waitingForFirst=true, type=WAIT_FOR_FIRST, suspended=true, signal=false, receiving=false"), m.observed)
        assertFalse(m.subject.isWaitingForFirst)
        assertNull(m.subject.myWaitGroup)
    }

    @Test
    @DisplayName("a second claim at the same instant does nothing")
    fun doubleClaim() {
        val outcomes = mutableListOf<Boolean>()
        val m = run {
            EngineModel(it, actions = mapOf(3.0 to {
                val registration = b.waiting.first()
                outcomes.add(b.open("first"))
                outcomes.add(registration.group.claim(registration, "second", ResumeSource.UNKNOWN_INTERNAL, 1))
            }))
        }
        assertEquals(listOf(true, false), outcomes)
        assertEquals("first", m.result!!.valueOf(m.tb))
        assertEquals(3.0, m.endTime)
    }

    @Test
    @DisplayName("the timer wins: no trigger, both registrations released as losers, WAIT_FOR_FIRST state left")
    fun timeoutWithTriggers() {
        val m = run { EngineModel(it, timeout = 7.0) }
        val r = m.result!!
        assertTrue(r.timedOut)
        assertEquals(7.0, r.timeWaited, 1e-12)
        assertNull(r.valueOrNull(m.ta))
        assertEquals(listOf("loser"), m.a.events)
        assertEquals(listOf("loser"), m.b.events)
        assertTrue(m.subject.isProcessEnded)
    }

    @Test
    @DisplayName("fast path: a trigger that can fire at once wins without suspending or registering")
    fun fastPath() {
        val m = run {
            EngineModel(it, timeout = 5.0).also { e -> e.b.readyValue = "now" }
        }
        val r = m.result!!
        assertTrue(r.firedBy(m.tb))
        assertEquals("now", r.valueOf(m.tb))
        assertEquals(0.0, r.timeWaited)
        assertEquals(0.0, m.endTime)
        assertEquals(0, m.a.registerCount + m.b.registerCount, "nothing is registered on the fast path")
    }

    @Test
    @DisplayName("fast path: when several triggers can fire at once, the first in argument order wins")
    fun fastPathArgumentOrder() {
        val m = run {
            EngineModel(it).also { e -> e.a.readyValue = "a"; e.b.readyValue = "b" }
        }
        assertTrue(m.result!!.firedBy(m.ta))
        assertEquals("a", m.result!!.valueOf(m.ta))
    }

    @Test
    @DisplayName("termination during the wait releases every registration with no statistics and frees the entity")
    fun termination() {
        val m = run {
            EngineModel(it, timeout = 20.0, actions = mapOf(4.0 to { subject.terminateProcess() }))
        }
        assertNull(m.result)
        assertEquals(listOf("terminated"), m.a.events)
        assertEquals(listOf("terminated"), m.b.events)
        assertTrue(m.subject.isProcessEnded)
        assertNull(m.subject.myWaitGroup)
    }

    @Test
    @DisplayName("a single trigger leaves the entity active, suspended as a plain suspension")
    fun singleTriggerState() {
        val m = run {
            EngineModel(it, single = true, actions = mapOf(1.0 to { observeState() }, 2.0 to { a.open("x") }))
        }
        assertEquals(listOf("waitingForFirst=false, type=SUSPEND, suspended=true, signal=false, receiving=false"), m.observed)
        assertEquals("x", m.result!!.valueOf(m.ta))
    }

    @Test
    @DisplayName("a single trigger takes the state and suspend type its untimed form reports, and is active again after")
    fun singleTriggerAloneState() {
        val seen = mutableListOf<String>()
        for (state in AloneState.entries) {
            val m = run {
                EngineModel(it, single = true, timeout = 20.0, actions = mapOf(1.0 to { observeState() }, 2.0 to { a.open("x") }))
                    .also { e -> e.a.aloneState = state }
            }
            seen.add(m.observed.single())
            assertEquals("x", m.result!!.valueOf(m.ta))
            assertTrue(m.subject.isProcessEnded, "the state was returned to active, so the process could go on to its end")
        }
        assertEquals(
            listOf(
                "waitingForFirst=false, type=SUSPEND, suspended=true, signal=false, receiving=false",
                "waitingForFirst=false, type=WAIT_FOR_SIGNAL, suspended=true, signal=true, receiving=false",
                "waitingForFirst=false, type=WAIT_FOR_ITEMS, suspended=true, signal=false, receiving=true",
                "waitingForFirst=false, type=WAIT_FOR_ANY_ITEMS, suspended=true, signal=false, receiving=true",
                "waitingForFirst=false, type=SEIZE, suspended=true, signal=false, receiving=false",
            ),
            seen
        )
    }

    @Test
    @DisplayName("a claim while the wait registers ends it at once and leaves no resume pending for the next wait")
    fun claimDuringRegistration() {
        var pendingAfter: Boolean? = null
        val m = run {
            EngineModel(it, timeout = 5.0, actions = mapOf(0.5 to { pendingAfter = subject.hasPendingResume }))
                .also { e -> e.b.claimAtRegister = "at once" }
        }
        val r = m.result!!
        assertTrue(r.firedBy(m.tb))
        assertEquals("at once", r.valueOf(m.tb))
        assertEquals(0.0, r.timeWaited)
        assertEquals(listOf("loser"), m.a.events, "the other registration is released at the claim")
        assertEquals(listOf("winner"), m.b.events)
        assertEquals(false, pendingAfter, "no resume was scheduled for a wait that never suspended")
        assertEquals(10.0, m.afterDelay, "the next suspension, a delay, runs its full length")
    }

    /** The argument checks, made before anything else. */
    private class ChecksModel(parent: ModelElement, val call: suspend KSLProcessBuilder.(WaitTrigger<String>) -> Unit) :
        ProcessModel(parent, "checks") {
        val gate = Gate("G")
        var error: Throwable? = null

        inner class Subject : Entity("subject") {
            val p: KSLProcess = process {
                try {
                    call(gate.trigger())
                } catch (e: IllegalArgumentException) {
                    error = e
                }
            }
        }

        override fun initialize() {
            activate(Subject().p)
        }
    }

    private fun check(call: suspend KSLProcessBuilder.(WaitTrigger<String>) -> Unit): String {
        val model = Model("Checks${System.nanoTime()}", autoCSVReports = false)
        val m = ChecksModel(model, call)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        return assertNotNull(m.error, "the call must be rejected").message ?: ""
    }

    @Test
    @DisplayName("no triggers, the same trigger twice, and a bad timeout are rejected at the call")
    fun argumentChecks() {
        assertTrue(check { waitForFirst(emptyList()) }.contains("at least one"))
        assertTrue(check { t -> waitForFirst(t, t) }.contains("more than once"))
        assertTrue(check { t -> waitForFirst(t, timeout = -2.0) }.contains("-2.0"))
        assertTrue(check { t -> waitForFirst(t, timeout = Double.NaN) }.contains("NaN"))
    }
}
