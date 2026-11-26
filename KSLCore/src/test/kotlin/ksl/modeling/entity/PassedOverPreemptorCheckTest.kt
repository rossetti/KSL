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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  The invariant check that no waiting preemptor is passed over, judged just before the claim order serves a request:
 *  it names a preemptor that a less urgent request behind it is about to pass, and stays silent otherwise.
 */
class PassedOverPreemptorCheckTest {

    // the setup frees a unit without an offer, which the checks run after every event would stop; this test calls the
    // one check it is about directly
    private var myInvariantsWereEnabled = false

    @BeforeEach
    fun disableInvariants() {
        myInvariantsWereEnabled = ResourceInvariants.enabled
        ResourceInvariants.enabled = false
    }

    @AfterEach
    fun restoreInvariants() {
        ResourceInvariants.enabled = myInvariantsWereEnabled
    }

    /**
     *  R has 2 units: L (priority 1) holds one, and the other is allocated directly to X, so it can be freed later
     *  without an offer. P (priority 2) asks to preempt both and, outranked by L, waits as a preemptor; W (of
     *  [wPriority]) then waits behind it. At 5, L's priority drops to 9, so its unit becomes lendable to P, and X's unit
     *  is freed with `deallocate`, which offers nothing; then the check judges W as if it were about to be served.
     */
    private class Shop(parent: ModelElement, val wPriority: Int) : ProcessModel(parent, "Shop") {
        val r = ResourceWithQ(this, "R", capacity = 2)
        var verdict: String? = "not judged"
        lateinit var l: Entity
        lateinit var x: Entity
        lateinit var xAllocation: Allocation

        override fun initialize() {
            l = object : Entity("L") {
                init { priority = 1 }
                val p = process { val a = seize(r); delay(20.0); release(a) }
            }.also { activate(it.p) }
            x = object : Entity("X") {}
            xAllocation = r.allocate(x, 1, r.myWaitingQ)
            activate(object : Entity("P") {
                init { priority = 2 }
                val p = process { delay(1.0); val a = preempt(r, 2, rule = InterruptRule.RESUME); delay(1.0); release(a) }
            }.p)
            activate(object : Entity("W") {
                init { priority = wPriority }
                val p = process { delay(2.0); val a = seize(r); delay(1.0); release(a) }
            }.p)
            schedule({ _: KSLEvent<Nothing> -> judge() }, 5.0)
        }

        private fun judge() {
            l.priority = 9
            r.deallocate(xAllocation)
            val w = r.myWaitingQ.first { it.entity.name == "W" }
            verdict = r.waitingPreemptorPassedOver(w, r.myWaitingQ)
        }
    }

    private fun verdict(wPriority: Int): String? {
        val model = Model("PassedOver${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, wPriority)
        model.lengthOfReplication = 5.5
        model.simulate()
        return shop.verdict
    }

    @Test
    @DisplayName("a less urgent request behind a waiting preemptor, about to take a unit the preemptor could use, passes it over")
    fun lessUrgentRequestPassesThePreemptor() {
        val verdict = assertNotNull(verdict(wPriority = 5))
        assertTrue("is passed over by request" in verdict, verdict)
    }

    @Test
    @DisplayName("a request more urgent than the waiting preemptor does not pass it over")
    fun moreUrgentRequestDoesNot() {
        assertNull(verdict(wPriority = 1))
    }
}
