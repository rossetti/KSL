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
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  At a replication's end the executive stops taking events, and then the suspended entities are
 *  terminated: their releases and `finally` blocks run. Nothing the library does in answer may schedule
 *  an event then. Each model here was stopped by such a schedule; each now completes. The order in which
 *  entities are terminated follows identity hashes, so the models run many replications.
 */
class EndOfRunTest {

    private class Waiters(parent: ModelElement) : ProcessModel(parent, "waiters") {
        inner class Item : QObject()
        lateinit var q: BlockingQueue<Item>
        var served = 0

        inner class Receiver(val amount: Int, val wait: Double) : Entity() {
            val p: KSLProcess = process("receiver") { delay(wait); waitForItems(q, amount = amount); served++ }
        }

        override fun initialize() {
            served = 0
            activate(Receiver(2, 0.0).p)
            for (k in 1..6) activate(Receiver(1, 1.0).p)
            schedule({ _: KSLEvent<Nothing> -> q.sendToChannel(Item()) }, 2.0)
        }
    }

    @Test
    @DisplayName("receivers waiting at a blocking queue at the end: removing one does not resume the next")
    fun blockingQueueReceiversAtTheEnd() {
        val model = Model("EndBQ${System.nanoTime()}", autoCSVReports = false)
        val waiters = Waiters(model)
        // created after the model whose entities wait on it, so it is still full when they are terminated
        waiters.q = BlockingQueue(model, name = "SiblingBQ")
        model.numberOfReplications = 30
        model.lengthOfReplication = 20.0
        model.simulate()
        assertEquals(0, waiters.served)
    }

    private class Break(parent: ModelElement, val withFailures: Boolean, val repairInFinally: Boolean) : ProcessModel(parent, "break") {
        val r = ResourceWithQ(this, "R", capacity = 2)
        val sibling = RequestQ(this, "SiblingQ")
        var served = 0

        init {
            if (withFailures) r.failures(ConstantRV(5.0), ConstantRV(1.0), ResourceFailureBasis.BUSY_TIME)
            if (repairInFinally) {
                r.failures(ConstantRV(5.0), ConstantRV(1.0))
                r.behavior { try { r.fail(); delay(1_000.0) } finally { r.repair() } }
            } else {
                r.behavior { try { r.goOffShift(); delay(1_000.0) } finally { r.goOnShift() } }
            }
        }

        inner class Customer(val wait: Double) : Entity() {
            val p: KSLProcess = process("customer") { delay(wait); val a = seize(r, queue = sibling); delay(1_000.0); release(a); served++ }
        }

        override fun initialize() {
            served = 0
            for (k in 1..4) activate(Customer(k.toDouble()).p)
        }
    }

    private fun runBreak(withFailures: Boolean = false, repairInFinally: Boolean = false): Break {
        val model = Model("EndBreak${System.nanoTime()}", autoCSVReports = false)
        val b = Break(model, withFailures, repairInFinally)
        model.numberOfReplications = 20
        model.lengthOfReplication = 20.0
        model.simulate()
        return b
    }

    @Test
    @DisplayName("a behavior's break ended by goOnShift in a finally at the end: the restored units are offered to no one")
    fun goOnShiftInAFinallyAtTheEnd() {
        assertEquals(0, runBreak().served)
    }

    @Test
    @DisplayName("goOnShift in a finally at the end, with busy-time failures: the failure clock does not start")
    fun goOnShiftWithABusyClockAtTheEnd() {
        assertEquals(0, runBreak(withFailures = true).served)
    }

    @Test
    @DisplayName("a hand repair in a finally at the end, with a declared failure waiting for it: nothing is resumed")
    fun repairInAFinallyAtTheEnd() {
        assertEquals(0, runBreak(repairInFinally = true).served)
    }

    private class Breaker(parent: ModelElement) : ProcessModel(parent, "breaker") {
        val r = ResourceWithQ(this, "R", capacity = 2)

        init {
            r.failures(ConstantRV(5.0), ConstantRV(1.0), ResourceFailureBasis.BUSY_TIME)
            r.behavior { delay(1.0); try { r.goOffShift(); delay(1_000.0) } finally { r.goOnShift() } }
        }
    }

    private class Holder(parent: ModelElement, val r: ResourceWithQ) : ProcessModel(parent, "holder") {
        inner class Customer : Entity() {
            val p: KSLProcess = process("customer") { val a = seize(r); delay(1_000.0); release(a) }
        }

        override fun initialize() { activate(Customer().p) }
    }

    @Test
    @DisplayName("goOnShift in a finally at the end while a unit is still busy: the busy-time failure clock does not start")
    fun goOnShiftWithAUnitBusyAtTheEnd() {
        val model = Model("EndBusy${System.nanoTime()}", autoCSVReports = false)
        val breaker = Breaker(model)
        // its entities are terminated after the breaker's, so its unit is busy when the break ends
        Holder(model, breaker.r)
        model.numberOfReplications = 5
        model.lengthOfReplication = 20.0
        model.simulate()
        assertEquals(5, model.currentReplicationNumber)
    }

    private class LateWorker(parent: ModelElement) : ProcessModel(parent, "lateWorker") {
        val r = ResourceWithQ(this, "R")

        init {
            r.failures(ConstantRV(5.0), ConstantRV(1.0), ResourceFailureBasis.BUSY_TIME)
        }

        inner class Job : Entity() {
            val p: KSLProcess = process("job") { delay(8.0); val a = seize(r); delay(1.0); release(a) }
        }

        override fun initialize() { activate(Job().p) }
    }

    @Test
    @DisplayName("a busy-time failure due after the run's end: the clock stops at the release without cancelling it")
    fun busyClockStopsWithItsFailureDueAfterTheEnd() {
        val model = Model("EndClock${System.nanoTime()}", autoCSVReports = false)
        // busy from 8 to 9, so the failure 5 busy minutes away falls at 13, after the end at 10
        LateWorker(model)
        model.numberOfReplications = 3
        model.lengthOfReplication = 10.0
        model.simulate()
        assertEquals(3, model.currentReplicationNumber)
    }
}
