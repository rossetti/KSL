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
import ksl.modeling.agent.AgentSignal
import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.CapacityChangeRule
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.Signal
import ksl.modeling.entity.WaitTrigger
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Cases of the entity guide's section on waiting for whichever comes first: the next `fire()` of an `AgentSignal`
 *  ends every process waiting on it, alone, with a time limit or beside other triggers, and only those already
 *  waiting; an items trigger, a mailbox trigger and a pool's seize trigger each serve several entities waiting at
 *  once; a win by an agent signal or by an allocation's trigger withdraws the other registrations, each recorded
 *  as its source records an ending wait; a promise broken by a shift's end leaves every trigger in place for the
 *  time left, with each wait recorded once; and the limits on the triggers a wait may take.
 */
class EntityWaitForFirstCasesTest {

    /**
     *  One trigger of the agent signal Call is made once and used by A (alone), B (with a time limit) and C (beside a
     *  signal trigger), all waiting from 0. The fire at 4 ends all three. D begins its wait with the same trigger at
     *  5, after that fire, and is ended by the next one, at 6. Nothing is left subscribed.
     */
    @Test
    @DisplayName("the next fire of an agent signal ends every process waiting on its trigger, in any form of wait, and only those")
    fun agentSignalFireEndsEveryWaiter() {
        val m = RuleModel("AgentSignalAll")
        val call = AgentSignal("Call")
        val other = Signal(m, "Other")
        var subscribersAfter = -1
        m.plan {
            val t = call.trigger()
            actor("A") { waitForFirst(t); log.add("A@$time") }
            actor("B") { val r = waitForFirst(t, timeout = 20.0); log.add("B ${if (r.timedOut) "timed out" else "call"}@$time") }
            actor("C") { val r = waitForFirst(other.trigger(), t); log.add("C ${if (r.firedBy(t)) "call" else "other"}@$time") }
            actor("D") { delay(5.0); waitForFirst(t); log.add("D@$time") }
            at(4.0) { call.fire() }
            at(6.0) { call.fire() }
            at(7.0) { subscribersAfter = call.numSubscribers }
        }
        m.simulate(50.0)
        assertEquals(listOf("A@4.0", "B call@4.0", "C call@4.0", "D@6.0"), m.log)
        assertEquals(0, subscribersAfter)
    }

    /**
     *  One items trigger of Q is made once and used by A (from 0) and B (from 1). Items come at 2 and 3: A takes the
     *  first and B the second. The same with an any-items trigger.
     */
    @Test
    @DisplayName("one items trigger, and one any-items trigger, serve several entities waiting at once")
    fun itemsTriggerSharedBySeveralEntities() {
        for (anyItems in listOf(false, true)) {
            val m = RuleModel("SharedItemsTrigger")
            val q = BlockingQueue<RuleModel.Item>(m, name = "Q")
            m.plan {
                val t: WaitTrigger<List<RuleModel.Item>> = if (anyItems) q.anyItemsTrigger({ true }) else q.itemsTrigger(1)
                for ((tag, from) in listOf("A" to 0.0, "B" to 1.0)) {
                    actor(tag) {
                        delay(from)
                        val r = waitForFirst(t, timeout = 50.0)
                        log.add("$tag ${r.valueOrNull(t)}@$time")
                    }
                }
                at(2.0) { q.sendToChannel(Item("i1")) }
                at(3.0) { q.sendToChannel(Item("i2")) }
            }
            m.simulate(60.0)
            assertEquals(listOf("A [i1]@2.0", "B [i2]@3.0"), m.log, "any items = $anyItems")
        }
    }

    /** One trigger of the hub's mailbox is made once and used by A (from 0) and B (from 1). Messages at 2 and 3 go one to each. */
    @Test
    @DisplayName("one mailbox trigger serves several entities waiting at once")
    fun mailboxTriggerSharedBySeveralEntities() {
        val m = RuleModel("SharedMailboxTrigger")
        val hub = m.Agent("hub")
        m.plan {
            val t = hub.mailbox.trigger()
            for ((tag, from) in listOf("A" to 0.0, "B" to 1.0)) {
                actor(tag) {
                    delay(from)
                    val r = waitForFirst(t, timeout = 50.0)
                    log.add("$tag ${if (r.firedBy(t)) "message" else "timed out"}@$time")
                }
            }
            at(2.0) { hub.mailbox.deliver(AgentMessage.Inform(hub, "m1")) }
            at(3.0) { hub.mailbox.deliver(AgentMessage.Inform(hub, "m2")) }
        }
        m.simulate(60.0)
        assertEquals(listOf("A message@2.0", "B message@3.0"), m.log)
    }

    /**
     *  A pool of P1 and P2. H1 holds P1 from 0 to 5 and H2 holds P2 from 0 to 8. One seize trigger of the pool is made
     *  once and used by A (from 1) and B (from 2), each holding what it gets for 10: A is served at 5, B at 8.
     */
    @Test
    @DisplayName("one seize trigger of a resource pool serves several entities waiting at once")
    fun poolSeizeTriggerSharedBySeveralEntities() {
        val m = RuleModel("SharedPoolTrigger")
        val p1 = ResourceWithQ(m, "P1")
        val p2 = ResourceWithQ(m, "P2")
        val pool = ResourcePoolWithQ(m, listOf(p1, p2), name = "Pool")
        m.plan {
            for ((res, hold) in listOf(p1 to 5.0, p2 to 8.0)) {
                actor("H${res.name}") {
                    val a = seize(res)
                    delay(hold)
                    release(a)
                }
            }
            val t = pool.seizeTrigger()
            for ((tag, from) in listOf("A" to 1.0, "B" to 2.0)) {
                actor(tag) {
                    delay(from)
                    val r = waitForFirst(t, timeout = 50.0)
                    val a = r.valueOrNull(t)
                    log.add("$tag ${if (a != null) "seized" else "timed out"}@$time")
                    if (a != null) {
                        delay(10.0)
                        release(a)
                    }
                }
            }
        }
        m.simulate(60.0)
        assertEquals(listOf("A seized@5.0", "B seized@8.0"), m.log)
    }

    /**
     *  R is held by H from 0 to 100. At 1, W waits for the first of a seize trigger of R, a trigger of Sig, an items
     *  trigger of Q, a trigger of the hub's mailbox, and a winner: an agent signal fired at 4, or the interrupted
     *  trigger of W's own unit of X, which fails at 4. At the win every other registration leaves at once: W is gone
     *  from Sig's queue, R's queue, Q's request queue and the mailbox, and the three queues each record one wait of 3.
     *  (R's queue also records H's wait of 0.) A message and an item sent later in the instant are not taken by W.
     */
    @Test
    @DisplayName("a win by an agent signal or by an allocation's trigger withdraws the other registrations, each recorded as its source records a wait")
    fun othersWithdrawnAtAnAgentSignalOrAllocationWin() {
        for (winner in listOf("agent signal", "interrupted")) {
            val m = RuleModel("OthersWithdrawn")
            val r = ResourceWithQ(m, "R")
            val x = ResourceWithQ(m, "X")
            val sig = Signal(m, "Sig")
            val q = BlockingQueue<RuleModel.Item>(m, name = "Q")
            val hub = m.Agent("hub")
            val call = AgentSignal("Call")
            m.plan {
                actor("H") {
                    val a = seize(r)
                    delay(100.0)
                    release(a)
                }
                actor("W") {
                    val held = seize(x)
                    delay(1.0)
                    val win = if (winner == "agent signal") call.trigger() else held.interruptedTrigger()
                    val res = waitForFirst(r.seizeTrigger(), sig.trigger(), q.itemsTrigger(1), hub.mailbox.trigger(), win)
                    log.add("W ${if (res.firedBy(win)) winner else "other"}@$time")
                    delay(10.0, uses = emptyList())
                    release(held)
                }
                at(4.0) {
                    if (winner == "agent signal") call.fire() else x.fail(InterruptRule.RESUME)
                    hub.mailbox.deliver(AgentMessage.Inform(hub, "late"))
                    q.sendToChannel(Item("late"))
                }
                at(5.0) {
                    fun stat(st: ksl.utilities.statistic.WeightedStatisticIfc) = "${st.count}/${st.weightedSum}"
                    log.add(
                        "t5 sigQ=${sig.waitingQ.size}:${stat(sig.waitingQ.timeInQ.withinReplicationStatistic)} " +
                                "rQ=${r.waitingQ.size}:${stat(r.waitingQ.timeInQ.withinReplicationStatistic)} " +
                                "itemsQ=${q.requestQ.size}:${stat(q.requestQ.timeInQ.withinReplicationStatistic)} " +
                                "mailbox=${hub.mailbox.size} channel=${q.channelQ.size}"
                    )
                }
                at(20.0) { if (x.isFailed) x.repair() }
            }
            m.simulate(50.0)
            assertEquals(
                listOf("W $winner@4.0", "t5 sigQ=0:1.0/3.0 rQ=0:2.0/3.0 itemsQ=0:1.0/3.0 mailbox=1 channel=1"),
                m.log,
                "winner $winner"
            )
        }
    }

    /**
     *  R has one unit, held by H from 0 to 5. At 1, W waits up to 20 for the first of a seize trigger of R and a
     *  trigger of Sig. At 5 H releases R, which wakes W's seize trigger with the unit promised, and in the same step
     *  takes R off shift, so the promised unit goes away. W's wait goes on with both triggers in place, until its
     *  original deadline: the signal at 10 ends it (`ending` "signal"); with no signal, R back on shift at 15 lets the
     *  seize trigger win ("on shift"); with neither, it times out at 21 ("none"). Each wait is recorded once, for its
     *  whole length: the wait in Sig's queue, and W's request in R's queue beside H's wait of 0.
     */
    private fun promiseBrokenByShiftEnd(rule: CapacityChangeRule, ending: String): List<String> {
        val m = RuleModel("ShiftBreaksPromise")
        val r = ResourceWithQ(m, "R")
        r.capacityChangeRule = rule
        val sig = Signal(m, "Sig")
        m.plan {
            actor("H") {
                val a = seize(r)
                delay(5.0)
                release(a)
                r.goOffShift()
            }
            actor("W") {
                delay(1.0)
                val seizeT = r.seizeTrigger()
                val sigT = sig.trigger()
                val res = waitForFirst(seizeT, sigT, timeout = 20.0)
                val what = if (res.firedBy(seizeT)) "seize" else if (res.firedBy(sigT)) "signal" else "timed out"
                log.add("W $what@$time waited=${res.timeWaited}")
                if (res.firedBy(seizeT)) release(res.valueOf(seizeT))
            }
            when (ending) {
                "signal" -> at(10.0) { sig.signalAll() }
                "on shift" -> at(15.0) { r.goOnShift() }
            }
            at(22.0) {
                fun stat(st: ksl.utilities.statistic.WeightedStatisticIfc) = "${st.count}/${st.weightedSum}"
                log.add(
                    "t22 sigQ=${sig.waitingQ.size}:${stat(sig.waitingQ.timeInQ.withinReplicationStatistic)} " +
                            "rQ=${r.waitingQ.size}:${stat(r.waitingQ.timeInQ.withinReplicationStatistic)}"
                )
            }
        }
        return m.simulate(50.0).log
    }

    /**
     *  Under the default `IGNORE` rule, going off shift takes the promised unit at once, so the promise breaks. (Under
     *  `WAIT` a promised unit is not idle: the shift's end waits for it and the promise holds, so there is no broken
     *  promise to test.)
     */
    @Test
    @DisplayName("a promise broken by a shift's end: the wait goes on with every trigger in place for the time left, each wait recorded once")
    fun promiseBrokenByAShiftsEnd() {
        val rule = CapacityChangeRule.IGNORE
        assertEquals(
            listOf("W signal@10.0 waited=9.0", "t22 sigQ=0:1.0/9.0 rQ=0:2.0/9.0"),
            promiseBrokenByShiftEnd(rule, "signal"),
            "signal"
        )
        assertEquals(
            listOf("W seize@15.0 waited=14.0", "t22 sigQ=0:1.0/14.0 rQ=0:2.0/14.0"),
            promiseBrokenByShiftEnd(rule, "on shift"),
            "back on shift"
        )
        assertEquals(
            listOf("W timed out@21.0 waited=20.0", "t22 sigQ=0:1.0/20.0 rQ=0:2.0/20.0"),
            promiseBrokenByShiftEnd(rule, "none"),
            "nothing"
        )
    }

    /**
     *  At the call, a wait is refused when it has two triggers of the same signal (even made with different
     *  priorities), or the same seize or items trigger twice.
     */
    @Test
    @DisplayName("limits: two triggers of one signal, and the same seize or items trigger twice, are refused at the call")
    fun limitsOfEachKind() {
        val m = RuleModel("WaitLimits")
        val r = ResourceWithQ(m, "R")
        val sig = Signal(m, "Sig")
        val q = BlockingQueue<RuleModel.Item>(m, name = "Q")
        val errors = mutableListOf<String>()
        m.plan {
            actor("W") {
                try {
                    waitForFirst(sig.trigger(), sig.trigger(waitPriority = 1), timeout = 1.0)
                    errors.add("accepted")
                } catch (e: IllegalArgumentException) {
                    errors.add(e.message ?: "")
                }
                val seizeT = r.seizeTrigger()
                try {
                    waitForFirst(seizeT, seizeT, timeout = 1.0)
                    errors.add("accepted")
                } catch (e: IllegalArgumentException) {
                    errors.add(e.message ?: "")
                }
                val itemsT = q.itemsTrigger(1)
                try {
                    waitForFirst(itemsT, itemsT, timeout = 1.0)
                    errors.add("accepted")
                } catch (e: IllegalArgumentException) {
                    errors.add(e.message ?: "")
                }
            }
        }
        m.simulate(10.0)
        assertEquals(3, errors.size, errors.toString())
        assertEquals(true, errors[0].contains("at most one signal trigger"), errors[0])
        assertEquals(true, errors[1].contains("more than once"), errors[1])
        assertEquals(true, errors[2].contains("more than once"), errors[2])
    }
}
