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
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourceInvariants
import ksl.modeling.entity.ResourceWithQ
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 *  A rule of the entity guide's gotchas: with `-Dksl.checkInvariants=true` every resource checks its accounting
 *  after each event, including units lent, units reserved and delays held, and a broken check stops the run with a
 *  message saying what is out of balance. Each case breaks one part of that accounting on purpose, at time 5, in an
 *  otherwise correct model.
 */
class EntityGotchasRulesTest {

    private val wasEnabled = ResourceInvariants.enabled

    @AfterEach
    fun restore() {
        ResourceInvariants.enabled = wasEnabled
    }

    private fun failureMessages(build: () -> RuleModel): String {
        ResourceInvariants.enabled = true
        val m = build()
        val error = assertFailsWith<Throwable> { m.simulate(100.0) }
        return generateSequence(error) { it.cause }.joinToString(" | ") { it.message ?: "" }
    }

    /** L (priority 9) holds R; P (priority 1) preempts its unit at 2 under RESUME. At 5 the loan's record is dropped. */
    @Test
    @DisplayName("a unit lent by a preemption but no longer recorded as lent is caught")
    fun lentAccountingIsChecked() {
        val messages = failureMessages {
            RuleModel("BrokenLoan").apply {
                val r = ResourceWithQ(this, "R", capacity = 1)
                plan {
                    actor("L", 9) { val a = seize(r); delay(50.0); release(a) }
                    actor("P", 1) { delay(2.0); val a = preempt(r, rule = InterruptRule.RESUME); delay(10.0); release(a) }
                    at(5.0) { (r.loans as MutableList<Resource.Loan>).clear() }
                }
            }
        }
        assertContains(messages, "1 units busy, but 2 are allocated and 0 lent")
    }

    /**
     *  R has 2 units; L (priority 9) holds one. P (priority 1) preempts 2 at 1 under FINISH: it reserves the free unit
     *  and waits for L's. At 5 the reservation is changed to need nothing more, though it holds only one unit.
     */
    @Test
    @DisplayName("a reservation holding fewer units than its record says is caught")
    fun reservedAccountingIsChecked() {
        val messages = failureMessages {
            RuleModel("BrokenReservation").apply {
                val r = ResourceWithQ(this, "R", capacity = 2)
                plan {
                    actor("L", 9) { val a = seize(r); delay(50.0); release(a) }
                    actor("P", 1) { delay(1.0); val a = preempt(r, 2, rule = InterruptRule.FINISH); delay(10.0); release(a) }
                    at(5.0) { r.reservations.first().stillNeeded = 0 }
                }
            }
        }
        assertContains(messages, "holds 1 units, not 2")
    }

    /** H holds R and is in a delay from 0 to 50. At 5 its delay is recorded as held by its own allocation, which has no unit missing. */
    @Test
    @DisplayName("a delay recorded as held by an allocation with no unit missing is caught")
    fun heldDelayAccountingIsChecked() {
        val messages = failureMessages {
            RuleModel("BrokenHold").apply {
                val r = ResourceWithQ(this, "R", capacity = 1)
                var holding: Allocation? = null
                plan {
                    val h = actor("H") { val a = seize(r); holding = a; delay(50.0); release(a) }
                    at(5.0) { h.myFrozenBy.add(holding!!) }
                }
            }
        }
        assertContains(messages, "held by")
    }
}
