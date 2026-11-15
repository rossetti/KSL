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

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.sendMessage
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A request woken with a promise can find, when it resumes, that the units promised to it are gone: the
 *  capacity was cut in the same instant. It withdraws its promise and waits again. The units it withdrew
 *  are then free, and must be offered to the requests behind it, as when a waiting request leaves. A seize
 *  trigger of `waitForFirst` waits again for everything its wait was waiting for, for the time left.
 */
class BrokenPromiseTest {

    private class Cut(parent: ModelElement, rule: CapacityChangeRule) : ProcessModel(parent, "cut") {
        val r = ResourceWithQ(this, "R", capacity = 2)
        val served = mutableMapOf<String, Double>()

        init {
            r.capacityChangeRule = rule
        }

        inner class Holder : Entity() {
            val p: KSLProcess = process("holder") {
                val a = seize(r, 2)
                delay(5.0)
                release(a)
                r.changeCapacity(r.CapacityChangeNotice(capacity = 1, duration = Double.POSITIVE_INFINITY))
            }
        }

        inner class Customer(val tag: String, val amount: Int, val arrive: Double) : Entity() {
            val p: KSLProcess = process("customer") {
                delay(arrive)
                val a = seize(r, amount)
                served[tag] = time
                delay(1.0)
                release(a)
            }
        }

        override fun initialize() {
            served.clear()
            activate(Holder().p)
            activate(Customer("R1", 2, 1.0).p)
            activate(Customer("R2", 1, 2.0).p)
        }
    }

    @Test
    @DisplayName("a promise broken by a capacity cut in the same instant: the withdrawn unit goes to the request behind")
    fun withdrawnUnitsAreOfferedOn() {
        val model = Model("Broken${System.nanoTime()}", autoCSVReports = false)
        val m = Cut(model, CapacityChangeRule.IGNORE)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        // R1 needs two units and the capacity is now one: it is never served
        assertEquals(mapOf("R2" to 5.0), m.served)
    }

    private class Either(parent: ModelElement, val signalAt: Double?) : ProcessModel(parent, "either") {
        val r = ResourceWithQ(this, "R", capacity = 2)
        val signal = Signal(this, "Sig")
        val outcome = mutableListOf<String>()

        inner class Holder : Entity() {
            val p: KSLProcess = process("holder") {
                val a = seize(r, 2)
                delay(5.0)
                release(a)
                r.changeCapacity(r.CapacityChangeNotice(capacity = 1, duration = Double.POSITIVE_INFINITY))
            }
        }

        inner class Waiter : Entity() {
            val p: KSLProcess = process("waiter") {
                delay(1.0)
                val take = r.seizeTrigger(amountNeeded = 2)
                val sig = signal.trigger()
                val res = waitForFirst(take, sig, timeout = 50.0)
                outcome.add(
                    when {
                        res.timedOut -> "timed out@$time waited=${res.timeWaited}"
                        res.firedBy(sig) -> "signal@$time waited=${res.timeWaited}"
                        else -> "seized@$time"
                    }
                )
                outcome.add("queue=${r.waitingQ.size} signalQ=${signal.waitingQ.size}")
            }
        }

        override fun initialize() {
            outcome.clear()
            activate(Holder().p)
            val w = Waiter()
            activate(w.p)
            signalAt?.let { t -> schedule({ _: ksl.simulation.KSLEvent<Nothing> -> signal.signal(w) }, t) }
        }
    }

    private fun runEither(signalAt: Double?): List<String> {
        val model = Model("Either${System.nanoTime()}", autoCSVReports = false)
        val m = Either(model, signalAt)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return m.outcome
    }

    @Test
    @DisplayName("a seize trigger whose promise broke waits again for the wait's other triggers too: a later signal ends the wait")
    fun rewaitIncludesTheOtherTriggers() {
        assertEquals(listOf("signal@10.0 waited=9.0", "queue=0 signalQ=0"), runEither(signalAt = 10.0))
    }

    @Test
    @DisplayName("a seize trigger whose promise broke, and nothing else happening: the wait times out at its original deadline")
    fun rewaitKeepsTheDeadline() {
        assertEquals(listOf("timed out@51.0 waited=50.0", "queue=0 signalQ=0"), runEither(signalAt = null))
    }

    private class Messages(parent: ModelElement) : AgentModel(parent, "messages") {
        val r = ResourceWithQ(this, "R", capacity = 2)
        val outcome = mutableListOf<String>()

        inner class Worker : Agent("worker") {
            val run: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                val take = r.seizeTrigger(amountNeeded = 2)
                val message = mailbox.trigger()
                val res = waitForFirst(take, message, timeout = 50.0)
                outcome.add(if (res.firedBy(message)) "message@$time waited=${res.timeWaited}" else "other@$time")
                outcome.add("queue=${r.waitingQ.size}")
            }
        }

        val worker = Worker()

        inner class Holder : Agent("holder") {
            val run: KSLProcess = process(isDefaultProcess = true) {
                val a = seize(r, 2)
                delay(5.0)
                release(a)
                r.changeCapacity(r.CapacityChangeNotice(capacity = 1, duration = Double.POSITIVE_INFINITY))
                // in the instant between the worker's wake and its resume
                sendMessage(AgentMessage.Inform(this@Holder, "call"), worker.mailbox)
            }
        }

        override fun initialize() {
            super.initialize()
            outcome.clear()
            activate(worker.run)
            activate(Holder().run)
        }
    }

    @Test
    @DisplayName("a seize trigger whose promise broke, with a message already waiting for the wait's other trigger: the wait ends at once with it")
    fun rewaitEndsAtOnceOnAWaitingMessage() {
        val model = Model("Messages${System.nanoTime()}", autoCSVReports = false)
        val m = Messages(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        assertEquals(listOf("message@5.0 waited=4.0", "queue=0"), m.outcome)
    }

    private class CutThenOffShift(parent: ModelElement) : ProcessModel(parent, "cutThenOff") {
        val r = ResourceWithQ(this, "R", capacity = 2)
        val seen = mutableListOf<String>()

        inner class Holder : Entity() {
            val p: KSLProcess = process("holder") {
                val a = seize(r, 2)
                delay(5.0)
                release(a)
                r.changeCapacity(r.CapacityChangeNotice(capacity = 1, duration = Double.POSITIVE_INFINITY))
            }
        }

        inner class Big : Entity() {
            val p: KSLProcess = process("big") { delay(1.0); seizeOrTimeout(r, amountNeeded = 2, timeout = 10.0) }
        }

        inner class Late : Entity() {
            val p: KSLProcess = process("late") { delay(21.0); val a = seize(r); seen.add("served@$time"); release(a) }
        }

        override fun initialize() {
            seen.clear()
            activate(Holder().p)
            activate(Big().p)
            activate(Late().p)
            schedule({ _: ksl.simulation.KSLEvent<Nothing> -> seen.add("pending=${r.isPendingCapacityChange}") }, 6.0)
            schedule({ _: ksl.simulation.KSLEvent<Nothing> -> r.goOffShift(); seen.add("off cap=${r.capacity}") }, 20.0)
        }
    }

    @Test
    @DisplayName("an IGNORE cut over promised units: when the promise breaks, the withdrawn units complete the cut, and a later shift's end applies at once")
    fun withdrawnUnitsCompleteAPendingCut() {
        val model = Model("CutOff${System.nanoTime()}", autoCSVReports = false)
        val m = CutThenOffShift(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 40.0
        model.simulate()
        // nothing is served off shift
        assertEquals(listOf("pending=false", "off cap=0"), m.seen)
    }
}
