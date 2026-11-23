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

import ksl.modeling.entity.Allocation
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourceInvariants
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.Signal
import ksl.modeling.entity.WaitTrigger
import ksl.simulation.ModelElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 *  Cases of the entity guide's advice to check a model with `-Dksl.checkInvariants=true`: with the checks on, each
 *  kind of accounting the guide lists (units busy, lent, reserved, and delays held) is caught after the event that
 *  breaks it, with a message naming the time, the resource and what is out of balance; and a `waitForFirst` request
 *  left behind by an ended wait is caught in a resource pool's queue and in a queue given to a plain resource.
 */
class EntityGotchasCasesTest {

    private val wasEnabled = ResourceInvariants.enabled

    @AfterEach
    fun restore() {
        ResourceInvariants.enabled = wasEnabled
    }

    /** A resource of 2 units whose busy count a subclass can put out of step with its allocations. */
    private class Breakable(parent: ModelElement) : ResourceWithQ(parent, "R", capacity = 2) {
        fun countOneMoreBusy() {
            numBusy += 1
        }
    }

    /** Builds a model with `build` and runs it for 20 with the checks on, and returns the messages of the error that stopped it. */
    private fun messagesOf(build: () -> RuleModel): String {
        ResourceInvariants.enabled = true
        val m = build()
        val error = assertFailsWith<RuntimeException> { m.simulate(20.0) }
        return generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message ?: "" }
    }

    /** H holds one of R's 2 units from 0 to 10; at 5, R counts one more unit busy than is allocated. */
    @Test
    @DisplayName("units busy out of step with the allocations are caught after the event that broke them")
    fun busyCaught() {
        val messages = messagesOf {
            RuleModel("BrokenBusy").also { m ->
                val r = Breakable(m)
                m.plan {
                    actor("H") { val a = seize(r); delay(10.0); release(a) }
                    at(5.0) { r.countOneMoreBusy() }
                }
            }
        }
        assertContains(messages, "Invariant broken at time 5.0 on resource R")
        assertContains(messages, "2 units busy, but 1 are allocated and 0 lent")
    }

    /** H holds R's unit from 0 to 10; at 5 its allocation is marked as missing a unit by loan, with no loan made. */
    @Test
    @DisplayName("units missing by loan with no loan recorded are caught after the event that broke them")
    fun lentCaught() {
        val messages = messagesOf {
            RuleModel("BrokenLent").also { m ->
                val r = ResourceWithQ(m, "R")
                m.plan {
                    lateinit var held: Allocation
                    actor("H") { held = seize(r); delay(10.0); release(held) }
                    at(5.0) { held.unitsGoMissing(0, 1, InterruptRule.RESUME) }
                }
            }
        }
        assertContains(messages, "Invariant broken at time 5.0 on resource R")
        assertContains(messages, "units missing by loan, but 0 are lent")
    }

    /** H holds R's unit from 0 and works 10; at 5 its delay is marked as held by its allocation, which has no units missing. */
    @Test
    @DisplayName("a delay held by an allocation with no units missing is caught after the event that broke it")
    fun heldCaught() {
        val messages = messagesOf {
            RuleModel("BrokenHeld").also { m ->
                val r = ResourceWithQ(m, "R")
                m.plan {
                    lateinit var held: Allocation
                    val h = actor("H") { held = seize(r); delay(10.0); release(held) }
                    at(5.0) { h.myFrozenBy.add(held) }
                }
            }
        }
        assertContains(messages, "Invariant broken at time 5.0 on resource R")
        assertContains(messages, "which has no units missing")
    }

    /**
     *  R has 2 units. A (priority 9) holds one from 0 to 10. At 1, P (priority 1) preempts both under `FINISH`: it is
     *  promised the free unit and still needs 1. At 5 its reservation is marked as needing none, so the units it
     *  holds no longer match what it should.
     */
    @Test
    @DisplayName("a reservation holding other than its promised units is caught after the event that broke it")
    fun reservedCaught() {
        val messages = messagesOf {
            RuleModel("BrokenReserved").also { m ->
                val r = ResourceWithQ(m, "R", capacity = 2)
                m.plan {
                    actor("A", priority = 9) { val a = seize(r); delay(10.0); release(a) }
                    actor("P", priority = 1) { delay(1.0); val a = preempt(r, 2, rule = InterruptRule.FINISH); delay(1.0); release(a) }
                    at(5.0) { r.reservations.single().stillNeeded = 0 }
                }
            }
        }
        assertContains(messages, "Invariant broken at time 5.0 on resource R")
        assertContains(messages, "holds 1 units, not 2")
    }

    /**
     *  W waits for the first of the seize trigger `setUp` makes and a signal, while the units it asks for are held until 10. At 5
     *  its wait is closed without withdrawing its request, as a registration left out of its wait would be. Returns
     *  the messages of the error that stops the run.
     */
    private fun leftBehind(name: String, setUp: (RuleModel) -> WaitTrigger<*>, hold: RuleModel.() -> Unit, request: () -> RequestQ): String =
        messagesOf {
            RuleModel(name).also { m ->
                val seizeT = setUp(m)
                val signal = Signal(m, "Go")
                m.plan {
                    hold()
                    actor("W") {
                        delay(1.0)
                        waitForFirst(seizeT, signal.trigger())
                    }
                    at(5.0) { request().peekFirst()!!.registration!!.group.closeOnTermination() }
                }
            }
        }

    /** A pool of P1 and P2, both held until 10; W's request waits in the pool's queue. */
    @Test
    @DisplayName("a pool's seize request left in its queue after its wait ended is caught")
    fun poolRequestOfAnEndedWaitCaught() {
        lateinit var pool: ResourcePoolWithQ
        lateinit var members: List<ResourceWithQ>
        val messages = leftBehind(
            "LeftBehindPool",
            setUp = { m ->
                members = listOf(ResourceWithQ(m, "P1"), ResourceWithQ(m, "P2"))
                pool = ResourcePoolWithQ(m, members, name = "Pool")
                pool.seizeTrigger()
            },
            hold = { for (p in members) actor("H${p.name}") { val a = seize(p); delay(10.0); release(a) } },
            request = { pool.myWaitingQ }
        )
        assertContains(messages, "at time 5.0")
        assertContains(messages, "waits in Pool:Q for a wait that has already ended")
    }

    /** A plain resource R, held until 10, with W's seize trigger waiting in a queue the model gives it, OwnQ. */
    @Test
    @DisplayName("a plain resource's seize request left in the queue given to it, after its wait ended, is caught")
    fun plainResourceRequestOfAnEndedWaitCaught() {
        lateinit var r: Resource
        lateinit var q: RequestQ
        val messages = leftBehind(
            "LeftBehindPlain",
            setUp = { m ->
                r = Resource(m, "R")
                q = RequestQ(m, "OwnQ")
                r.seizeTrigger(queue = q)
            },
            hold = { actor("H") { val a = seize(r, queue = q); delay(10.0); release(a) } },
            request = { q }
        )
        assertContains(messages, "at time 5.0")
        assertContains(messages, "waits in OwnQ for a wait that has already ended")
    }
}
