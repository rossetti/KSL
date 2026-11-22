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
import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.Signal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Rules of the entity guide's section on waiting for whichever comes first: one trigger serves several entities
 *  waiting at once; a signal trigger's `waitPriority` becomes the entity's priority and stays after the wait; a
 *  losing seize trigger with `recordRenegedWait = false` is left out of the queue's statistics; while a seize
 *  trigger's win waits for its units to be taken, a message goes to the next receiver and an item to the next
 *  request; and an items request at the head of a queue holds back the same wait's other items request.
 */
class EntityWaitForFirstRulesTest {

    /**
     *  R has one unit, held by H from 0 to 5. One seize trigger, made once, is passed to the waits of A (from 1) and
     *  B (from 2). A is served at 5 and holds for 3; B is served at 8.
     */
    @Test
    @DisplayName("one seize trigger used by two entities waiting at the same time serves each in turn")
    fun oneSeizeTriggerSeveralEntities() {
        val m = RuleModel("SharedSeizeTrigger")
        val r = ResourceWithQ(m, "R", capacity = 1)
        m.plan {
            val bed = r.seizeTrigger()
            actor("H") {
                val a = seize(r)
                delay(5.0)
                release(a)
            }
            for ((tag, from) in listOf("A" to 1.0, "B" to 2.0)) {
                actor(tag) {
                    delay(from)
                    val res = waitForFirst(bed, timeout = 100.0)
                    log.add("$tag ${if (res.firedBy(bed)) "seized" else "timed out"}@$time")
                    if (res.firedBy(bed)) {
                        delay(3.0)
                        release(res.valueOf(bed))
                    }
                }
            }
        }
        m.simulate(50.0)
        assertEquals(listOf("A seized@5.0", "B seized@8.0"), m.log)
    }

    /** One signal trigger, made once, is passed to the waits of A (from 1) and B (from 2); a `signalAll` at 5 ends both. */
    @Test
    @DisplayName("one signal trigger used by two entities waiting at the same time ends both waits")
    fun oneSignalTriggerSeveralEntities() {
        val m = RuleModel("SharedSignalTrigger")
        val signal = Signal(m, "S")
        m.plan {
            val alarm = signal.trigger()
            for ((tag, from) in listOf("A" to 1.0, "B" to 2.0)) {
                actor(tag) {
                    delay(from)
                    val res = waitForFirst(alarm, timeout = 100.0)
                    log.add("$tag ${if (res.firedBy(alarm)) "signalled" else "timed out"}@$time")
                }
            }
            at(5.0) { signal.signalAll() }
        }
        m.simulate(50.0)
        assertEquals(listOf("A signalled@5.0", "B signalled@5.0"), m.log)
    }

    /** W, of priority 5, waits for the first of a signal trigger with `waitPriority` 2 and a timeout; the signal at 3 ends it. */
    @Test
    @DisplayName("a signal trigger's waitPriority becomes the entity's priority and stays after the wait")
    fun signalTriggerWaitPriorityStays() {
        val m = RuleModel("SignalWaitPriority")
        val signal = Signal(m, "S")
        m.plan {
            actor("W", priority = 5) { w ->
                log.add("before=${w.priority}")
                val t = signal.trigger(waitPriority = 2)
                val res = waitForFirst(t, timeout = 50.0)
                log.add("signalled=${res.firedBy(t)} after=${w.priority}@$time")
                delay(1.0)
                log.add("later=${w.priority}")
            }
            at(3.0) { signal.signalAll() }
        }
        m.simulate(100.0)
        assertEquals(listOf("before=5", "signalled=true after=2@3.0", "later=2"), m.log)
    }

    /**
     *  R has one unit, held by H from 0 to 100. At 1, W waits for the first of a seize trigger of R and a signal
     *  trigger; the signal at 11 wins, and the seize request leaves R's queue after 10 time units. With
     *  `recordRenegedWait = true` that wait is one more observation of R's time in queue, of 10; with false it is
     *  not recorded.
     */
    private fun losingSeizeWaitRecorded(recordRenegedWait: Boolean): Pair<Int, Double> {
        val m = RuleModel("LosingSeizeTriggerStats")
        val r = ResourceWithQ(m, "R", capacity = 1)
        val signal = Signal(m, "S")
        var before = 0.0 to 0.0
        var after = 0.0 to 0.0
        m.plan {
            actor("H") {
                val a = seize(r)
                delay(100.0)
                release(a)
            }
            actor("W") {
                delay(1.0)
                waitForFirst(r.seizeTrigger(recordRenegedWait = recordRenegedWait), signal.trigger())
            }
            fun read(): Pair<Double, Double> =
                r.waitingQ.timeInQ.withinReplicationStatistic.let { it.count to it.weightedSum }
            at(0.5) { before = read() }
            at(11.0) { signal.signalAll() }
            at(20.0) { after = read() }
        }
        m.simulate(50.0)
        return (after.first - before.first).toInt() to (after.second - before.second)
    }

    @Test
    @DisplayName("a losing seize trigger's wait is recorded in the queue's time in queue by default")
    fun losingSeizeTriggerRecordedByDefault() {
        assertEquals(1 to 10.0, losingSeizeWaitRecorded(true))
    }

    @Test
    @DisplayName("a losing seize trigger with recordRenegedWait = false is left out of the queue's time in queue")
    fun losingSeizeTriggerLeftOutWhenAsked() {
        assertEquals(0 to 0.0, losingSeizeWaitRecorded(false))
    }

    /**
     *  R has one unit, held by H from 0 to 5. At 1, W waits for the first of a seize trigger of R and an items
     *  trigger of Q; at 2, V waits for an item of Q, behind W's request. At 5, H releases R, which wakes W's seize
     *  trigger, and in the same step sends an item to Q. W has not yet taken its unit, so Q passes over W's request
     *  and the item goes to V.
     */
    @Test
    @DisplayName("while a seize trigger's win waits for its units, an item goes to the next request")
    fun itemGoesToNextRequestWhileSeizeWinPending() {
        val m = RuleModel("PendingWinItem")
        val r = ResourceWithQ(m, "R", capacity = 1)
        val q = BlockingQueue<RuleModel.Item>(m, name = "Q")
        m.plan {
            actor("H") {
                val a = seize(r)
                delay(5.0)
                release(a)
                q.sendToChannel(Item("i1"))
            }
            actor("W") {
                delay(1.0)
                val seizeT = r.seizeTrigger()
                val itemsT = q.itemsTrigger(1)
                val res = waitForFirst(seizeT, itemsT)
                log.add("W ${if (res.firedBy(seizeT)) "seize" else "items"}@$time")
                if (res.firedBy(seizeT)) release(res.valueOf(seizeT))
            }
            actor("V") {
                delay(2.0)
                val items = waitForItems(q, 1)
                log.add("V got $items@$time")
            }
            at(6.0) { log.add("t6 requests=${q.requestQ.size} channel=${q.channelQ.size}") }
        }
        m.simulate(50.0)
        assertEquals(listOf("W seize@5.0", "V got [i1]@5.0", "t6 requests=0 channel=0"), m.log)
    }

    /**
     *  As above, with a mailbox in place of the queue: at 1, W waits for the first of a seize trigger of R and a
     *  trigger of the hub's mailbox; at 2, V waits on the same mailbox. At 5, H releases R and delivers a message.
     *  W's win is pending, so the message goes to V.
     */
    @Test
    @DisplayName("while a seize trigger's win waits for its units, a message goes to the next receiver")
    fun messageGoesToNextReceiverWhileSeizeWinPending() {
        val m = RuleModel("PendingWinMessage")
        val r = ResourceWithQ(m, "R", capacity = 1)
        val hub = m.Agent("hub")
        m.plan {
            actor("H") {
                val a = seize(r)
                delay(5.0)
                release(a)
                hub.mailbox.deliver(AgentMessage.Inform(hub, "m1"))
            }
            actor("W") {
                delay(1.0)
                val seizeT = r.seizeTrigger()
                val res = waitForFirst(seizeT, hub.mailbox.trigger())
                log.add("W ${if (res.firedBy(seizeT)) "seize" else "message"}@$time")
                if (res.firedBy(seizeT)) release(res.valueOf(seizeT))
            }
            actor("V") {
                delay(2.0)
                val msgT = hub.mailbox.trigger()
                val res = waitForFirst(msgT)
                log.add("V ${if (res.firedBy(msgT)) "message" else "other"}@$time")
            }
            at(6.0) { log.add("t6 pending=${hub.mailbox.size}") }
        }
        m.simulate(50.0)
        assertEquals(listOf("W seize@5.0", "V message@5.0", "t6 pending=0"), m.log)
    }

    /**
     *  Q holds no items. At 0, W waits for the first of an items trigger for a red item and one for a blue item, with a
     *  timeout of 10. A blue item arrives at 2. With the default selector the red request, at the head, holds back the
     *  blue request behind it, so the wait does not take the blue item and times out at 10. With
     *  `FirstFillableRequest` the blue request is served at 2.
     */
    private fun twoItemsTriggers(firstFillable: Boolean): List<String> {
        val m = RuleModel("TwoItemsTriggers")
        val q = BlockingQueue<RuleModel.Item>(m, name = "Q")
        if (firstFillable) q.receiverRequestSelector = q.FirstFillableRequest()
        m.plan {
            actor("W") {
                val red = q.itemsTrigger(1, { it.tag == "red" })
                val blue = q.itemsTrigger(1, { it.tag == "blue" })
                val res = waitForFirst(red, blue, timeout = 10.0)
                log.add("W ${if (res.timedOut) "timed out" else if (res.firedBy(blue)) "blue" else "red"}@$time")
            }
            at(2.0) { q.sendToChannel(Item("blue")) }
            at(11.0) { log.add("t11 channel=${q.channelQ.size} requests=${q.requestQ.size}") }
        }
        return m.simulate(50.0).log
    }

    @Test
    @DisplayName("default selector: the wait's red request at the head holds back its blue request, so the blue item is not taken")
    fun headRequestHoldsBackSameWaitsOtherRequest() {
        assertEquals(listOf("W timed out@10.0", "t11 channel=1 requests=0"), twoItemsTriggers(firstFillable = false))
    }

    @Test
    @DisplayName("FirstFillableRequest: the blue request is served when the blue item arrives")
    fun firstFillableServesTheBlueRequest() {
        assertEquals(listOf("W blue@2.0", "t11 channel=0 requests=0"), twoItemsTriggers(firstFillable = true))
    }
}
