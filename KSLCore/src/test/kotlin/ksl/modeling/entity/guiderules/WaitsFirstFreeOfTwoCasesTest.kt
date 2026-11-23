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

package ksl.modeling.entity.guiderules

import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ResourcePoolAllocation
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.Signal
import ksl.modeling.entity.WaitForFirstResult
import ksl.modeling.entity.WaitTrigger
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  The rules of the waits tutorial's case 3, "First free of two" (`ksl-waits-tutorial.md`), checked case by case:
 *
 *  - a patient waiting in both FIFO queues is taken by whichever provider frees first, the longest waiter first;
 *  - `waitForFirst` places the request in both queues and waits for the first of the triggers or the timeout,
 *    whichever trigger that is;
 *  - when both triggers can fire as the wait begins, the first in argument order wins, for any kinds of trigger
 *    that can be ready then; when only one can, or both become ready at different moments, the order plays no part;
 *  - one `waitForFirst` accepts seize triggers for resources and pools, signal, items and message triggers in any
 *    combination, and any of them can end it.
 */
class WaitsFirstFreeOfTwoCasesTest {

    /** Two providers, a pool of two rooms, a signal, a blocking queue and a mailbox, with triggers held as properties. */
    private class Clinic(parent: ModelElement) : AgentModel(parent, "Clinic") {
        val doctor = ResourceWithQ(this, "Doctor")
        val nurse = ResourceWithQ(this, "Nurse")
        val roomA = ResourceWithQ(this, "RoomA")
        val roomB = ResourceWithQ(this, "RoomB")
        val rooms = ResourcePoolWithQ(this, listOf(roomA, roomB), name = "Rooms")
        val alarm = Signal(this, "Alarm")
        val tray = BlockingQueue<Kit>(this, name = "Tray")
        val log = mutableListOf<String>()
        val setups = mutableListOf<Clinic.() -> Unit>()

        inner class Desk : Agent("desk")

        val desk = Desk()

        val byDoctor = doctor.seizeTrigger()
        val byNurse = nurse.seizeTrigger()
        val byRoom = rooms.seizeTrigger()
        val byAlarm = alarm.trigger()
        val byKit = tray.itemsTrigger()
        val byMessage = desk.mailbox.trigger()

        inner class Kit : QObject()

        inner class Actor(tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit): Actor =
            Actor(tag, body).also { activate(it.p) }

        /** Holds one unit of [r] from 0 to [until]. */
        fun holder(tag: String, r: ResourceWithQ, until: Double) = actor(tag) {
            val a = seize(r)
            delay(until)
            release(a)
        }

        fun name(r: WaitForFirstResult): String = when (r.trigger) {
            byDoctor -> "doctor"
            byNurse -> "nurse"
            byRoom -> "room"
            byAlarm -> "alarm"
            byKit -> "kit"
            byMessage -> "message"
            null -> "timeout"
            else -> "other"
        }

        /** Waits for the first of [triggers] from [start], logs which and when, holds what it seized for [hold]. */
        fun patient(tag: String, start: Double, triggers: List<WaitTrigger<*>>, timeout: Double = 30.0, hold: Double = 10.0) =
            actor(tag) {
                delay(start)
                val r = waitForFirst(triggers, timeout = timeout)
                log.add("$tag:${name(r)}@$time")
                val doc = if (byDoctor in triggers) r.valueOrNull(byDoctor) else null
                val np = if (byNurse in triggers) r.valueOrNull(byNurse) else null
                val room: ResourcePoolAllocation? = if (byRoom in triggers) r.valueOrNull(byRoom) else null
                if (doc != null || np != null || room != null) delay(hold)
                doc?.let { release(it) }
                np?.let { release(it) }
                room?.let { release(it) }
            }

        fun sendKit() = tray.sendToChannel(Kit())
        fun sendMessage() = desk.mailbox.deliver(AgentMessage.Inform(desk, "m"))

        fun at(t: Double, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t)
        }

        /** How many requests or waiters each source holds. */
        fun waiting(): String =
            "doctor=${doctor.waitingQ.size} nurse=${nurse.waitingQ.size} rooms=${rooms.waitingQ.size} " +
                    "alarm=${alarm.waitingQ.size} tray=${tray.requestQ.size}"

        override fun initialize() {
            super.initialize()
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(length: Double = 60.0, setup: Clinic.() -> Unit): Clinic {
        val model = Model("FirstFree${System.nanoTime()}", autoCSVReports = false)
        val c = Clinic(model)
        c.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        model.simulate()
        return c
    }

    // ---------- whichever provider frees first, longest waiter first ----------

    /**
     *  The doctor is busy until `doctorFree`, the nurse practitioner until `nurseFree`. P1 waits for both from 1, P2
     *  from 2. The first provider to free takes P1, who has waited longest, and the other takes P2 when it frees.
     */
    private fun twoPatients(doctorFree: Double, nurseFree: Double): List<String> = run {
        holder("X", doctor, doctorFree)
        holder("Y", nurse, nurseFree)
        patient("P1", 1.0, listOf(byDoctor, byNurse))
        patient("P2", 2.0, listOf(byDoctor, byNurse))
    }.log

    @Test
    @DisplayName("the doctor frees first: it takes the longest waiter, and the nurse practitioner the next when it frees")
    fun doctorFreesFirst() {
        assertEquals(listOf("P1:doctor@5.0", "P2:nurse@8.0"), twoPatients(doctorFree = 5.0, nurseFree = 8.0))
    }

    @Test
    @DisplayName("the nurse practitioner frees first: it takes the longest waiter, and the doctor the next when it frees")
    fun nurseFreesFirst() {
        assertEquals(listOf("P1:nurse@5.0", "P2:doctor@8.0"), twoPatients(doctorFree = 8.0, nurseFree = 5.0))
    }

    // ---------- a request in both queues, and the first of the triggers or the timeout ----------

    /**
     *  W waits from 1, with a limit of 10, for the doctor (busy until `doctorFree`) or the nurse practitioner (busy until
     *  `nurseFree`). At 3 its request waits in both queues; just after the wait ends, in neither.
     */
    private fun firstOfThree(doctorFree: Double, nurseFree: Double): List<String> = run {
        holder("X", doctor, doctorFree)
        holder("Y", nurse, nurseFree)
        patient("W", 1.0, listOf(byDoctor, byNurse), timeout = 10.0, hold = 0.0)
        at(3.0) { log.add("during: ${waiting()}") }
        at(minOf(doctorFree, nurseFree, 11.0) + 0.5) { log.add("after: ${waiting()}") }
    }.log

    private val inBoth = "during: doctor=1 nurse=1 rooms=0 alarm=0 tray=0"
    private val inNeither = "after: doctor=0 nurse=0 rooms=0 alarm=0 tray=0"

    @Test
    @DisplayName("the request waits in both queues; the trigger passed first ends the wait, and the other request leaves")
    fun firstTriggerEndsTheWait() {
        assertEquals(listOf(inBoth, "W:doctor@5.0", inNeither), firstOfThree(doctorFree = 5.0, nurseFree = 8.0))
    }

    @Test
    @DisplayName("the request waits in both queues; the trigger passed second ends the wait, and the other request leaves")
    fun secondTriggerEndsTheWait() {
        assertEquals(listOf(inBoth, "W:nurse@5.0", inNeither), firstOfThree(doctorFree = 8.0, nurseFree = 5.0))
    }

    @Test
    @DisplayName("the request waits in both queues; the timeout ends the wait, and both requests leave")
    fun timeoutEndsTheWait() {
        assertEquals(listOf(inBoth, "W:timeout@11.0", inNeither), firstOfThree(doctorFree = 40.0, nurseFree = 40.0))
    }

    // ---------- a tie as the wait begins: argument order ----------

    @Test
    @DisplayName("both providers free as the wait begins: the first in argument order wins, in either order")
    fun bothSeizeTriggersReady() {
        assertEquals(listOf("W:doctor@1.0"), run { patient("W", 1.0, listOf(byDoctor, byNurse)) }.log)
        assertEquals(listOf("W:nurse@1.0"), run { patient("W", 1.0, listOf(byNurse, byDoctor)) }.log)
    }

    @Test
    @DisplayName("an item and a message both waiting as the wait begins: the first in argument order wins, the other is kept")
    fun itemsAndMessageReady() {
        fun scenario(kitFirst: Boolean): List<String> = run {
            at(0.0) { sendKit(); sendMessage() }
            actor("W") {
                delay(1.0)
                val r = waitForFirst(if (kitFirst) listOf(byKit, byMessage) else listOf(byMessage, byKit), timeout = 30.0)
                log.add("W:${name(r)}@$time kitsLeft=${tray.channelQ.size} messagesLeft=${desk.mailbox.size}")
            }
        }.log
        assertEquals(listOf("W:kit@1.0 kitsLeft=0 messagesLeft=1"), scenario(kitFirst = true))
        assertEquals(listOf("W:message@1.0 kitsLeft=1 messagesLeft=0"), scenario(kitFirst = false))
    }

    @Test
    @DisplayName("a free provider and a waiting item as the wait begins: the first in argument order wins, the other is untouched")
    fun seizeAndItemsReady() {
        fun scenario(doctorFirst: Boolean): List<String> = run {
            at(0.0) { sendKit() }
            actor("W") {
                delay(1.0)
                val r = waitForFirst(if (doctorFirst) listOf(byDoctor, byKit) else listOf(byKit, byDoctor), timeout = 30.0)
                log.add("W:${name(r)}@$time doctorBusy=${doctor.numBusy} kitsLeft=${tray.channelQ.size}")
                r.valueOrNull(byDoctor)?.let { release(it) }
            }
        }.log
        assertEquals(listOf("W:doctor@1.0 doctorBusy=1 kitsLeft=1"), scenario(doctorFirst = true))
        assertEquals(listOf("W:kit@1.0 doctorBusy=0 kitsLeft=0"), scenario(doctorFirst = false))
    }

    // ---------- no tie: argument order plays no part ----------

    @Test
    @DisplayName("only one provider free as the wait begins: it wins, whatever the argument order")
    fun onlyOneFree() {
        for (order in listOf(listOf("doctor", "nurse"), listOf("nurse", "doctor"))) {
            val onlyNurse = run {
                holder("X", doctor, 20.0)
                patient("W", 1.0, order.map { if (it == "doctor") byDoctor else byNurse })
            }.log
            assertEquals(listOf("W:nurse@1.0"), onlyNurse, "only the nurse practitioner free, order $order")
            val onlyDoctor = run {
                holder("Y", nurse, 20.0)
                patient("W", 1.0, order.map { if (it == "doctor") byDoctor else byNurse })
            }.log
            assertEquals(listOf("W:doctor@1.0"), onlyDoctor, "only the doctor free, order $order")
        }
    }

    @Test
    @DisplayName("both providers free at different moments: the first to free wins, whatever the argument order")
    fun freeAtDifferentMoments() {
        for (order in listOf(listOf("doctor", "nurse"), listOf("nurse", "doctor"))) {
            for ((doctorFree, nurseFree) in listOf(4.0 to 6.0, 6.0 to 4.0)) {
                val log = run {
                    holder("X", doctor, doctorFree)
                    holder("Y", nurse, nurseFree)
                    patient("W", 1.0, order.map { if (it == "doctor") byDoctor else byNurse })
                }.log
                val expected = if (doctorFree < nurseFree) "W:doctor@4.0" else "W:nurse@4.0"
                assertEquals(listOf(expected), log, "order $order, doctor free at $doctorFree, nurse at $nurseFree")
            }
        }
    }

    // ---------- any mix of kinds ----------

    /**
     *  W waits from 1, with a limit of 10, for the first of the doctor (busy until 20 unless `doctorFree`), the alarm,
     *  a kit and a message. `cause` makes one of them happen at 3. Just after, every other registration is gone.
     */
    private fun allFour(cause: String): List<String> = run {
        holder("X", doctor, if (cause == "doctor") 3.0 else 20.0)
        patient("W", 1.0, listOf(byDoctor, byAlarm, byKit, byMessage), timeout = 10.0, hold = 0.0)
        at(3.0) {
            when (cause) {
                "alarm" -> alarm.signal()
                "kit" -> sendKit()
                "message" -> sendMessage()
            }
        }
        at(3.5) { log.add(waiting()) }
    }.log

    @Test
    @DisplayName("a seize, a signal, items and a message in one wait: each can end it, and the others are withdrawn")
    fun allFourKindsInOneWait() {
        val after = "doctor=0 nurse=0 rooms=0 alarm=0 tray=0"
        for (cause in listOf("doctor", "alarm", "kit", "message")) {
            assertEquals(listOf("W:$cause@3.0", after), allFour(cause), "cause $cause")
        }
    }

    @Test
    @DisplayName("with no cause, the four-kind wait times out, and until then each registration is in place")
    fun allFourKindsTimeOut() {
        assertEquals(listOf("doctor=1 nurse=0 rooms=0 alarm=1 tray=1", "W:timeout@11.0"), allFour("none"))
    }

    @Test
    @DisplayName("a pool's seize trigger with a signal, items and a message: a pool member freed ends the wait")
    fun poolSeizeTriggerInAMix() {
        fun scenario(cause: String): List<String> = run {
            holder("A", roomA, if (cause == "room") 3.0 else 20.0)
            holder("B", roomB, 20.0)
            patient("W", 1.0, listOf(byRoom, byAlarm, byKit, byMessage), timeout = 10.0, hold = 0.0)
            at(3.0) {
                when (cause) {
                    "alarm" -> alarm.signal()
                    "kit" -> sendKit()
                    "message" -> sendMessage()
                }
            }
            at(2.0) { log.add("during: ${waiting()}") }
            at(3.5) { log.add("after: ${waiting()}") }
        }.log
        val during = "during: doctor=0 nurse=0 rooms=1 alarm=1 tray=1"
        val after = "after: doctor=0 nurse=0 rooms=0 alarm=0 tray=0"
        for (cause in listOf("room", "alarm", "kit", "message")) {
            assertEquals(listOf(during, "W:$cause@3.0", after), scenario(cause), "cause $cause")
        }
    }
}
