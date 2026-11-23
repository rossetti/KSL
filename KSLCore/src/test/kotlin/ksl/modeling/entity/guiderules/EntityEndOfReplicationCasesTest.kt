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

import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.Signal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Cases of the entity guide's section on what runs at the end of a replication: an entity suspended in any kind of
 *  wait when the replication's events end is terminated, and its process never goes past the suspension, while its
 *  `finally` block runs.
 */
class EntityEndOfReplicationCasesTest {

    /**
     *  The replication lasts 10. Each actor below is suspended at its end in a different way: a delay; a hold queue;
     *  a seize of a resource that H holds; a signal; a mixed wait; a blocking queue receive; a send to a full blocking
     *  queue; a suspension; a timed wait for a signal; a delay of 5 begun at 0 but held since 1 by a `RESUME` failure
     *  that is never repaired; and a wait for another process to complete. Each writes "past" after its suspension
     *  and "finally" in its `finally` block. Every one is terminated: each `finally` runs, and none goes past.
     */
    @Test
    @DisplayName("every suspended entity, in each kind of wait, is terminated at the end: its finally runs and it never goes past the suspension")
    fun everyKindOfSuspensionIsTerminated() {
        val m = RuleModel("EndOfReplicationKinds")
        val r = ResourceWithQ(m, "R")
        val broken = ResourceWithQ(m, "Broken")
        val holdQ = HoldQueue(m, "HoldQ")
        val signal = Signal(m, "Sig")
        val other = Signal(m, "Other")
        val empty = BlockingQueue<RuleModel.Item>(m, name = "Empty")
        val full = BlockingQueue<RuleModel.Item>(m, capacity = 1, name = "Full")
        val seen = mutableListOf<String>()
        m.plan {
            fun waiter(tag: String, body: suspend KSLProcessBuilder.(RuleModel.Actor) -> Unit) = actor(tag) { self ->
                try {
                    body(self)
                    seen.add("$tag past")
                } finally {
                    seen.add("$tag finally")
                }
            }
            actor("H") {
                val a = seize(r)
                delay(1000.0)
                release(a)
            }
            full.sendToChannel(Item("filler"))
            at(1.0) { broken.fail(InterruptRule.RESUME) }
            val longDelay = waiter("delay") { delay(1000.0) }
            waiter("hold") { hold(holdQ) }
            waiter("seize") { delay(0.5); val a = seize(r); release(a) }
            waiter("signal") { waitFor(signal) }
            waiter("mixed") { waitForFirst(other.trigger(), empty.itemsTrigger(1)) }
            waiter("receive") { waitForItems(empty, 1) }
            waiter("send") { send(Item("extra"), full) }
            waiter("suspension") { self -> suspendFor(self.Suspension("parked")) }
            waiter("timed") { waitForOrTimeout(signal, 1000.0) }
            waiter("held delay") { val a = seize(broken); delay(5.0); release(a) }
            waiter("process") { blockUntilCompleted(longDelay.script) }
        }
        m.simulate(10.0)
        val tags = listOf(
            "delay", "hold", "seize", "signal", "mixed", "receive", "send", "suspension", "timed", "held delay", "process"
        )
        assertEquals(emptyList(), seen.filter { it.endsWith(" past") }, "no process goes past its suspension")
        assertEquals(tags.map { "$it finally" }.sorted(), seen.filter { it.endsWith(" finally") }.sorted())
    }
}
