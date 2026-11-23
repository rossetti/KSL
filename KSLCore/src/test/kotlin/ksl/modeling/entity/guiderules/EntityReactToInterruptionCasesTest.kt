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

import ksl.modeling.agent.AgentSignal
import ksl.modeling.entity.Allocation
import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.Signal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Cases of the entity guide's section on reacting to a failure or preemption: an allocation's
 *  `interruptedTrigger` fires at once with `PREEMPTION` when units are already lent; its `restoredTrigger` waits
 *  until units missing for both causes are all back; the restored trigger composes with an items trigger, either
 *  winning; and the interrupted trigger composes with an agent signal, and with a signal in a supervisor's wait.
 */
class EntityReactToInterruptionCasesTest {

    /**
     *  R has one unit. L (priority 9) seizes it at 0 and works 5 that no resource can hold. At 2, P (priority 1)
     *  preempts it under `RESUME` for 10. At 5, L waits on its interrupted trigger: the unit is already lent, so it
     *  fires at once with `PREEMPTION`. L then waits on its restored trigger, which fires at P's release, 12.
     */
    @Test
    @DisplayName("the interrupted trigger fires at once with PREEMPTION when units are already lent; the restored trigger at the return")
    fun interruptedAtOnceByPreemption() {
        val m = RuleModel("InterruptedAtOncePreemption")
        val r = ResourceWithQ(m, "R")
        m.plan {
            actor("L", priority = 9) {
                val a = seize(r)
                delay(5.0, uses = emptyList())
                val lost = a.interruptedTrigger()
                log.add("interrupted@$time ${waitForFirst(lost).valueOf(lost)}")
                waitForFirst(a.restoredTrigger())
                log.add("restored@$time")
                release(a)
            }
            actor("P", priority = 1) {
                delay(2.0)
                val p = preempt(r, rule = InterruptRule.RESUME)
                delay(10.0)
                release(p)
            }
        }
        m.simulate(50.0)
        assertEquals(listOf("interrupted@5.0 PREEMPTION", "restored@12.0"), m.log)
    }

    /**
     *  R has 2 units, both held by L (priority 9) from 0. At 2, P (priority 1) preempts one under `RESUME` for 10. At
     *  3 R fails under `RESUME`, taking L's other unit and holding P's work; it is repaired at 5. L waits on its
     *  restored trigger from 2.5. The repair brings back the unit the failure took, but the lent one is still out,
     *  so the trigger does not fire; it fires when P, held 3 to 5, releases at 14 and all of L's units are back.
     */
    @Test
    @DisplayName("the restored trigger fires only when all units are back: a repair while units are still lent does not fire it")
    fun restoredWaitsForBothCauses() {
        val m = RuleModel("RestoredBothCauses")
        val r = ResourceWithQ(m, "R", capacity = 2)
        m.plan {
            lateinit var held: Allocation
            actor("L", priority = 9) {
                held = seize(r, 2)
                delay(2.5, uses = emptyList())
                waitForFirst(held.restoredTrigger())
                log.add("restored@$time missing=${held.hasMissingUnits}")
                release(held)
            }
            actor("P", priority = 1) {
                delay(2.0)
                val p = preempt(r, rule = InterruptRule.RESUME)
                delay(10.0)
                release(p)
            }
            at(3.0) { r.fail(InterruptRule.RESUME) }
            at(5.0) { r.repair() }
            at(6.0) { log.add("t6 missing=${held.hasMissingUnits}") }
        }
        m.simulate(50.0)
        assertEquals(listOf("t6 missing=true", "restored@14.0 missing=false"), m.log)
    }

    /**
     *  H seizes R at 0; R fails under `RESUME` at 1. At 2, H waits for the first of its restored trigger and an item
     *  of Q. When the item comes at 4 and the repair at 6, the item wins; when the repair comes at 4 and the item at
     *  6, the restored trigger wins, the items request leaves Q's request queue, and the item stays in the channel.
     */
    @Test
    @DisplayName("the restored trigger composes with an items trigger: whichever comes first wins")
    fun restoredComposedWithItems() {
        for (itemFirst in listOf(true, false)) {
            val m = RuleModel("RestoredOrItems")
            val r = ResourceWithQ(m, "R")
            val q = BlockingQueue<RuleModel.Item>(m, name = "Q")
            m.plan {
                actor("H") {
                    val a = seize(r)
                    delay(2.0, uses = emptyList())
                    val back = a.restoredTrigger()
                    val item = q.itemsTrigger(1)
                    val res = waitForFirst(back, item, timeout = 50.0)
                    log.add("${if (res.firedBy(back)) "restored" else if (res.firedBy(item)) "items" else "timed out"}@$time")
                    release(a)
                }
                at(1.0) { r.fail(InterruptRule.RESUME) }
                at(if (itemFirst) 4.0 else 6.0) { q.sendToChannel(Item("i1")) }
                at(if (itemFirst) 6.0 else 4.0) { r.repair() }
                at(7.0) { log.add("t7 requests=${q.requestQ.size} channel=${q.channelQ.size}") }
            }
            m.simulate(50.0)
            val expected = if (itemFirst) listOf("items@4.0", "t7 requests=0 channel=0")
            else listOf("restored@4.0", "t7 requests=0 channel=1")
            assertEquals(expected, m.log, "item first = $itemFirst")
        }
    }

    /**
     *  H seizes R at 0 and at 1 waits for the first of its interrupted trigger and an agent signal's trigger. When the
     *  signal is fired at 2 and R fails at 4, the signal wins; when R fails at 2 and the signal is fired at 4, the
     *  interrupted trigger wins with `FAILURE`, and the agent signal has no subscriber left.
     */
    @Test
    @DisplayName("the interrupted trigger composes with an agent signal: whichever comes first wins")
    fun interruptedComposedWithAgentSignal() {
        for (signalFirst in listOf(true, false)) {
            val m = RuleModel("InterruptedOrAgentSignal")
            val r = ResourceWithQ(m, "R")
            val call = AgentSignal("Call")
            m.plan {
                actor("H") {
                    val a = seize(r)
                    delay(1.0)
                    val lost = a.interruptedTrigger()
                    val called = call.trigger()
                    val res = waitForFirst(lost, called, timeout = 50.0)
                    log.add(
                        (if (res.firedBy(lost)) "interrupted ${res.valueOf(lost)}" else if (res.firedBy(called)) "called" else "timed out") +
                                "@$time subscribers=${call.numSubscribers}"
                    )
                    delay(1.0, uses = emptyList())
                    release(a)
                }
                at(if (signalFirst) 2.0 else 4.0) { call.fire() }
                at(if (signalFirst) 4.0 else 2.0) { r.fail(InterruptRule.RESUME) }
                at(10.0) { r.repair() }
            }
            m.simulate(50.0)
            val expected = if (signalFirst) "called@2.0 subscribers=0" else "interrupted FAILURE@2.0 subscribers=0"
            assertEquals(listOf(expected), m.log, "signal first = $signalFirst")
        }
    }

    /**
     *  L (priority 9) holds R from 0 to 10. At 1, S, a supervisor that holds nothing, waits for the first of L's
     *  interrupted trigger and a signal, with a time limit. At 3, P (priority 1) preempts R under `RESUME`: S's wait
     *  ends with `PREEMPTION`, and S leaves the signal's queue.
     */
    @Test
    @DisplayName("a supervisor waits on another entity's interrupted trigger composed with a signal, and is told of a preemption")
    fun supervisorWaitsOnInterruptedTrigger() {
        val m = RuleModel("SupervisorInterrupted")
        val r = ResourceWithQ(m, "R")
        val signal = Signal(m, "S")
        m.plan {
            lateinit var held: Allocation
            actor("L", priority = 9) {
                held = seize(r)
                delay(10.0)
                release(held)
            }
            actor("S") {
                delay(1.0)
                val lost = held.interruptedTrigger()
                val sig = signal.trigger()
                val res = waitForFirst(lost, sig, timeout = 50.0)
                log.add(
                    (if (res.firedBy(lost)) "told ${res.valueOf(lost)}" else if (res.firedBy(sig)) "signal" else "timed out") +
                            "@$time signalQ=${signal.waitingQ.size}"
                )
            }
            actor("P", priority = 1) {
                delay(3.0)
                val p = preempt(r, rule = InterruptRule.RESUME)
                delay(2.0)
                release(p)
            }
        }
        m.simulate(50.0)
        assertEquals(listOf("told PREEMPTION@3.0 signalQ=0"), m.log)
    }
}
