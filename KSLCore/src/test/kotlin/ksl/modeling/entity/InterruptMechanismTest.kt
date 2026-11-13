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
import ksl.modeling.spatial.DistancesModel
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  The mechanism under failures and preemption, driven through the internal entry points by which units of
 *  an allocation go missing and come back: the freeze of a delay by a count of reasons, a delay begun
 *  frozen, the rules RESUME and RESTART, a delay that names what it uses, and the allocation's records.
 */
class InterruptMechanismTest {

    private class MModel(parent: ModelElement) : ProcessModel(parent, "mechanism") {
        private val dm = DistancesModel()
        val locA = dm.Location("A")
        val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = ConstantRV(10.0)
            spatialModel = dm
        }

        val r1 = ResourceWithQ(this, "R1")
        val r2 = ResourceWithQ(this, "R2")
        val p1 = ResourceWithQ(this, "P1")
        val p2 = ResourceWithQ(this, "P2")
        val pool = ResourcePoolWithQ(this, listOf(p1, p2), name = "Pool")
        val signal = Signal(this, "S")

        val log = mutableListOf<String>()
        val setups = mutableListOf<MModel.() -> Unit>()

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        /** At [t], runs [action] (a zero-time step in a driver of its own). */
        fun at(t: Double, action: () -> Unit) = actor("at$t") { delay(t); action() }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(reps: Int = 1, length: Double = 100.0, sink: AnimationSink? = null, setup: MModel.() -> Unit): MModel {
        val model = Model("Mechanism${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = MModel(model)
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

    private val resume = InterruptRule.RESUME
    private val restart = InterruptRule.RESTART

    // ---------- the rules ----------

    @Test
    @DisplayName("RESUME: the delay ends at its original end plus the time missing; the allocation records it")
    fun resumeRule() {
        lateinit var a: Allocation
        val m = run {
            actor("W") { a = seize(r1); delay(10.0, suspensionName = "work"); log.add("done@$time"); release(a) }
            at(3.0) { a.unitsGoMissing(1, 0, resume) }
            at(7.0) { a.unitsComeBack(1, 0) }
        }
        assertEquals(listOf("done@14.0"), m.log)
        assertEquals(1, a.timesInterrupted)
        assertEquals(4.0, a.timeInterrupted, 1e-12)
        LoansAndFreezesCheck.check(m.r1)
    }

    @Test
    @DisplayName("RESTART: the delay ends at the return plus its full duration")
    fun restartRule() {
        val m = run {
            lateinit var a: Allocation
            actor("W") { a = seize(r1); delay(10.0, suspensionName = "work"); log.add("done@$time"); release(a) }
            at(3.0) { a.unitsGoMissing(1, 0, restart) }
            at(7.0) { a.unitsComeBack(1, 0) }
        }
        assertEquals(listOf("done@17.0"), m.log)
    }

    @Test
    @DisplayName("FINISH takes nothing from a holder: the entry point refuses it")
    fun finishRefused() {
        var error: String? = null
        run {
            lateinit var a: Allocation
            actor("W") { a = seize(r1); delay(10.0); release(a) }
            at(3.0) {
                try { a.unitsGoMissing(1, 0, InterruptRule.FINISH) } catch (e: IllegalArgumentException) { error = e.message }
            }
        }
        assertNotNull(error)
    }

    @Test
    @DisplayName("two overlapping reasons, one asking for a restart: the delay restarts when the second ends, whichever asked")
    fun overlappingReasonsWithARestart() {
        for (restartFirst in listOf(true, false)) {
            val m = run {
                lateinit var a: Allocation
                lateinit var b: Allocation
                actor("W") {
                    a = seize(r1); b = seize(r2)
                    delay(10.0, suspensionName = "work"); log.add("done@$time")
                    release(a); release(b)
                }
                at(2.0) { a.unitsGoMissing(1, 0, if (restartFirst) restart else resume) }
                at(4.0) { b.unitsGoMissing(1, 0, if (restartFirst) resume else restart) }
                at(6.0) { a.unitsComeBack(1, 0) }
                at(9.0) { b.unitsComeBack(1, 0) }
            }
            assertEquals(listOf("done@19.0"), m.log, "restart first = $restartFirst")
        }
    }

    @Test
    @DisplayName("two allocations missing in turn and returning in either order: the delay resumes once, when the last returns")
    fun twoAllocationsReturnInEitherOrder() {
        for (aLast in listOf(true, false)) {
            val sink = CollectingSink()
            lateinit var w: MModel.Actor
            val m = run(sink = sink) {
                lateinit var a: Allocation
                lateinit var b: Allocation
                w = actor("W") {
                    a = seize(r1); b = seize(r2)
                    delay(10.0, suspensionName = "work"); log.add("done@$time")
                    release(a); release(b)
                }
                at(2.0) { a.unitsGoMissing(1, 0, resume) }
                at(4.0) { b.unitsGoMissing(1, 0, resume) }
                at(6.0) { if (aLast) b.unitsComeBack(1, 0) else a.unitsComeBack(1, 0) }
                at(9.0) { if (aLast) a.unitsComeBack(1, 0) else b.unitsComeBack(1, 0) }
            }
            // frozen at 2 with 8 left, back at 9
            assertEquals(listOf("done@17.0"), m.log, "a last = $aLast")
            val starts = sink.events.filter { it is AnimationEvent.DelayStarted && it.entityId == w.id }
            assertEquals(2, starts.size, "one start and one reschedule")
        }
    }

    // ---------- with a process interrupt ----------

    @Test
    @DisplayName("a process interrupt overlapping a resource reason: the resource reason ending last")
    fun processInterruptThenResourceEndsLast() {
        val m = run {
            lateinit var a: Allocation
            lateinit var w: MModel.Actor
            w = actor("W") { a = seize(r1); delay(10.0, suspensionName = "work"); log.add("done@$time"); release(a) }
            at(2.0) { a.unitsGoMissing(1, 0, resume) }
            actor("I") { delay(3.0); interruptDelayAndContinue(w.p, "work", interruptTime = 2.0) }
            at(8.0) { a.unitsComeBack(1, 0) }
        }
        // frozen at 2 with 8 left; the interrupt (3 to 5) ends first; the units come back at 8
        assertEquals(listOf("done@16.0"), m.log)
    }

    @Test
    @DisplayName("a process interrupt overlapping a resource reason: the process interrupt ending last")
    fun resourceThenProcessInterruptEndsLast() {
        val m = run {
            lateinit var a: Allocation
            lateinit var w: MModel.Actor
            w = actor("W") { a = seize(r1); delay(10.0, suspensionName = "work"); log.add("done@$time"); release(a) }
            at(2.0) { a.unitsGoMissing(1, 0, resume) }
            actor("I") { delay(3.0); interruptDelayAndContinue(w.p, "work", interruptTime = 4.0) }
            at(4.0) { a.unitsComeBack(1, 0) }
        }
        // frozen at 2 with 8 left; the units come back at 4; the interrupt (3 to 7) ends last and continues with the 8 left
        assertEquals(listOf("done@15.0"), m.log)
    }

    // ---------- a delay begun frozen, and delays that are not frozen ----------

    @Test
    @DisplayName("a holder suspended elsewhere when its units go missing is not woken; the delay it starts afterwards begins frozen")
    fun notInADelayThenStartsOne() {
        val sink = CollectingSink()
        lateinit var w: MModel.Actor
        val m = run(sink = sink) {
            lateinit var a: Allocation
            w = actor("W") {
                a = seize(r1)
                suspend("parked"); log.add("woken@$time")
                delay(10.0, suspensionName = "work"); log.add("done@$time"); release(a)
            }
            at(2.0) { a.unitsGoMissing(1, 0, resume) }
            at(5.0) { w.resumeProcess() }
            at(8.0) { a.unitsComeBack(1, 0) }
        }
        assertEquals(listOf("woken@5.0", "done@18.0"), m.log)
        val trace = sink.events.mapNotNull { if (it is AnimationEvent.DelayStarted && it.entityId == w.id) "${it.simTime} ends ${it.arrivalTime}" else null }
        assertEquals(listOf("5.0 ends 15.0", "8.0 ends 18.0"), trace)
    }

    @Test
    @DisplayName("a holder in a timed wait when its units go missing: the timeout keeps running")
    fun timedWaitKeepsRunning() {
        val m = run {
            lateinit var a: Allocation
            actor("W") {
                a = seize(r1)
                val r = waitForOrTimeout(signal, timeout = 6.0)
                log.add("wait over@$time timedOut=${r is WaitResult.TimedOut}")
                delay(2.0); log.add("done@$time"); release(a)
            }
            at(2.0) { a.unitsGoMissing(1, 0, resume) }
            at(10.0) { a.unitsComeBack(1, 0) }
        }
        assertEquals(listOf("wait over@6.0 timedOut=true", "done@12.0"), m.log)
    }

    @Test
    @DisplayName("a holder in a delay of a process called by another: the delay freezes and the caller resumes when it completes")
    fun delayInASubProcess() {
        val m = run {
            lateinit var a: Allocation
            val sub = Actor("S") { a = seize(r1); delay(10.0, suspensionName = "work"); log.add("sub done@$time"); release(a) }
            actor("C") { waitFor(sub.p); log.add("caller@$time") }
            at(3.0) { a.unitsGoMissing(1, 0, resume) }
            at(5.0) { a.unitsComeBack(1, 0) }
        }
        assertEquals(listOf("sub done@12.0", "caller@12.0"), m.log)
    }

    @Test
    @DisplayName("a moving holder finishes its move on time; the delay after it begins frozen")
    fun movementNotInterrupted() {
        val m = run {
            lateinit var a: Allocation
            actor("M") {
                entity.currentLocation = locA
                a = seize(r1)
                moveTo(locB, velocity = 10.0); log.add("arrived@$time")
                delay(5.0); log.add("done@$time"); release(a)
            }
            at(2.0) { a.unitsGoMissing(1, 0, resume) }
            at(12.0) { a.unitsComeBack(1, 0) }
        }
        assertEquals(listOf("arrived@10.0", "done@17.0"), m.log)
    }

    @Test
    @DisplayName("a yielding holder is not frozen")
    fun yieldNotInterrupted() {
        val m = run {
            lateinit var a: Allocation
            actor("Y") {
                a = seize(r1)
                delay(5.0, delayPriority = 1)
                yield(yieldPriority = KSLEvent.LOW_PRIORITY); log.add("after yield@$time")
                release(a)
            }
            at(5.0) { a.unitsGoMissing(1, 0, resume) }
            at(6.0) { a.unitsComeBack(1, 0) }
        }
        assertEquals(listOf("after yield@5.0"), m.log)
    }

    @Test
    @DisplayName("the interrupting process of an interrupt is itself a holder whose units go missing: the target waits for both")
    fun interrupterIsAFrozenHolder() {
        val m = run {
            lateinit var a: Allocation
            lateinit var w: MModel.Actor
            w = actor("W") { delay(10.0, suspensionName = "work"); log.add("target done@$time") }
            actor("I") {
                a = seize(r1)
                delay(2.0)
                interruptDelayAndContinue(w.p, "work", interruptTime = 4.0)
                log.add("interrupter done@$time"); release(a)
            }
            at(3.0) { a.unitsGoMissing(1, 0, resume) }
            at(5.0) { a.unitsComeBack(1, 0) }
        }
        // the interrupt delay 2 to 6 is frozen 3 to 5, ending at 8; the target, frozen at 2 with 8 left, ends at 16
        assertEquals(listOf("interrupter done@8.0", "target done@16.0"), m.log)
    }

    @Test
    @DisplayName("resumeProcess on a frozen holder is refused as it is for a holder in a delay")
    fun resumeProcessRefused() {
        fun attempt(frozen: Boolean): Pair<Boolean, String> {
            var can = false
            val msg = try {
                run {
                    lateinit var a: Allocation
                    lateinit var w: MModel.Actor
                    w = actor("W") { a = seize(r1); delay(10.0); release(a) }
                    if (frozen) at(2.0) { a.unitsGoMissing(1, 0, resume) }
                    at(3.0) { can = w.canResumeProcess; w.resumeProcess() }
                }
                "no error"
            } catch (e: IllegalStateException) {
                e.message ?: ""
            }
            return can to msg
        }
        val inDelay = attempt(false)
        val inFrozen = attempt(true)
        assertTrue(inDelay.second.contains("attempted to resume a DELAY"), inDelay.second)
        assertTrue(inFrozen.second.contains("attempted to resume a DELAY"), inFrozen.second)
        assertEquals(inDelay.first, inFrozen.first, "canResumeProcess")
    }

    // ---------- what a delay uses ----------

    @Test
    @DisplayName("an entity holding two allocations: units of either missing freeze a plain delay")
    fun plainDelayDependsOnEverything() {
        for (useB in listOf(false, true)) {
            val m = run {
                lateinit var a: Allocation
                lateinit var b: Allocation
                actor("W") {
                    a = seize(r1); b = seize(r2)
                    delay(10.0); log.add("done@$time"); release(a); release(b)
                }
                at(2.0) { (if (useB) b else a).unitsGoMissing(1, 0, resume) }
                at(5.0) { (if (useB) b else a).unitsComeBack(1, 0) }
            }
            assertEquals(listOf("done@13.0"), m.log, "b = $useB")
        }
    }

    @Test
    @DisplayName("with uses naming one allocation, only it freezes the delay; units of the other missing leave it running")
    fun usesNamesOne() {
        val m = run {
            lateinit var a: Allocation
            lateinit var b: Allocation
            actor("W") {
                a = seize(r1); b = seize(r2)
                delay(10.0, uses = listOf(a), suspensionName = "first"); log.add("first@$time")
                delay(10.0, uses = listOf(a), suspensionName = "second"); log.add("second@$time")
                release(a); release(b)
            }
            at(2.0) { b.unitsGoMissing(1, 0, resume) }
            at(4.0) { a.unitsGoMissing(1, 0, resume) }
            at(6.0) { a.unitsComeBack(1, 0) }
            at(30.0) { b.unitsComeBack(1, 0) }
        }
        // first: frozen 4 to 6 by a only; second: begun while b is missing, starts normally
        assertEquals(listOf("first@12.0", "second@22.0"), m.log)
    }

    @Test
    @DisplayName("with uses empty, no resource freezes the delay but a process interrupt does")
    fun usesEmpty() {
        val m = run {
            lateinit var a: Allocation
            lateinit var w: MModel.Actor
            w = actor("W") { a = seize(r1); delay(10.0, uses = emptyList(), suspensionName = "work"); log.add("done@$time"); release(a) }
            at(2.0) { a.unitsGoMissing(1, 0, resume) }
            actor("I") { delay(3.0); interruptDelayAndContinue(w.p, "work", interruptTime = 4.0) }
            at(20.0) { a.unitsComeBack(1, 0) }
        }
        assertEquals(listOf("done@14.0"), m.log)
    }

    @Test
    @DisplayName("uses naming an allocation the entity does not hold is an error")
    fun usesNotHeld() {
        var error: String? = null
        run {
            lateinit var other: Allocation
            actor("O") { other = seize(r2); delay(50.0); release(other) }
            actor("W") {
                val a = seize(r1)
                delay(1.0)
                try { delay(5.0, uses = listOf(a, other)) } catch (e: IllegalArgumentException) { error = e.message }
                release(a)
            }
        }
        val msg = assertNotNull(error)
        assertTrue(msg.contains("not allocated to it"), msg)
    }

    @Test
    @DisplayName("a pool allocation passed as its members: only those members freeze the delay")
    fun poolAllocationAsMembers() {
        val m = run {
            lateinit var pa: ResourcePoolAllocation
            lateinit var b: Allocation
            actor("W") {
                pa = seize(pool, 2); b = seize(r2)
                delay(10.0, uses = pa.allocations); log.add("done@$time")
                release(pa); release(b)
            }
            at(1.0) { b.unitsGoMissing(1, 0, resume) }
            at(2.0) { pa.allocations.first().unitsGoMissing(1, 0, resume) }
            at(5.0) { pa.allocations.first().unitsComeBack(1, 0) }
            at(50.0) { b.unitsComeBack(1, 0) }
        }
        assertEquals(listOf("done@13.0"), m.log)
    }

    // ---------- the end of a frozen holder ----------

    @Test
    @DisplayName("termination while frozen: nothing of the delay fires, and the units return to the resource")
    fun terminationWhileFrozen() {
        val m = run {
            lateinit var a: Allocation
            lateinit var w: MModel.Actor
            w = actor("W") { a = seize(r1); delay(10.0); log.add("done@$time"); release(a) }
            at(2.0) { a.unitsGoMissing(1, 0, resume) }
            at(4.0) { w.terminateProcess() }
            at(6.0) { a.unitsComeBack(1, 0); log.add("busy=${r1.numBusy} closed=${a.timeInterrupted}") }
            actor("N") { delay(8.0); val b = seize(r1); log.add("next@$time"); release(b) }
        }
        assertEquals(listOf("busy=0 closed=2.0", "next@8.0"), m.log)
    }

    @Test
    @DisplayName("frozen at the end of a replication: nothing of it fires in the next one")
    fun frozenAtTheEndOfAReplication() {
        val m = run(reps = 3, length = 50.0) {
            lateinit var a: Allocation
            actor("W") { a = seize(r1); delay(10.0); log.add("done@$time"); release(a) }
            at(2.0) { a.unitsGoMissing(1, 0, resume) }
            actor("Later") { delay(30.0); log.add("later@$time") }
        }
        assertEquals(listOf("later@30.0"), m.log)
    }
}
