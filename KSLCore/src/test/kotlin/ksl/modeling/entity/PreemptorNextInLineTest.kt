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

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A waiting preemptor and freed units: at each step of the offer the preemptor is compared with the request the
 *  units would go to next. It goes ahead of a less urgent one, waits for one as urgent or more, and once the offer
 *  is over takes what it can, from the free units left and from the holders it outranks.
 */
class PreemptorNextInLineTest {

    private class Shop(parent: ModelElement, capacity: Int, poolOfR: Boolean = false) : ProcessModel(parent, "Shop") {
        val r = ResourceWithQ(this, "R", capacity = capacity)
        val s = ResourceWithQ(this, "S", capacity = 2)
        val pool = if (poolOfR) ResourcePoolWithQ(this, listOf(s, r), name = "Pool") else null
        val otherQ = RequestQ(this, "OtherQ")
        val thirdQ = RequestQ(this, "ThirdQ")
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()

        inner class Actor(tag: String, prio: Int, body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process(isDefaultProcess = true, block = body)
        }

        fun actor(tag: String, prio: Int, body: suspend KSLProcessBuilder.() -> Unit) = activate(Actor(tag, prio, body).p)

        override fun initialize() {
            log.clear()
            for (setup in setups) setup()
        }
    }

    private fun run(capacity: Int, poolOfR: Boolean = false, configure: Shop.() -> Unit = {}, setup: Shop.() -> Unit): Shop {
        val model = Model("NextInLine${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, capacity, poolOfR)
        shop.configure()
        shop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        assertNull(error, "the run stopped: $error")
        return shop
    }

    @Test
    @DisplayName("a preemptor that waits for a more urgent request next in line takes what it can once that request is served")
    fun takesWhatItCanAfterTheMoreUrgentRequest() {
        // R has 3: L (least urgent) holds 1, H holds 2 until 10. U, more urgent than P, waits for 1; P waits to preempt 2
        val shop = run(capacity = 3) {
            actor("L", 10) { val a = seize(r); delay(50.0); release(a) }
            actor("H", 1) { val a = seize(r, 2); delay(10.0); release(a) }
            actor("U", 1) { delay(1.0); val a = seize(r); log.add("U got@$time"); delay(5.0); release(a) }
            actor("P", 2) { delay(2.0); val a = preempt(r, 2, rule = InterruptRule.RESUME); log.add("P got@$time"); delay(5.0); release(a) }
        }
        assertEquals(listOf("U got@10.0", "P got@10.0"), shop.log)
    }

    @Test
    @DisplayName("a preemptor goes ahead of a less urgent request next in line, though a more urgent one waits behind it")
    fun goesAheadOfTheLessUrgentRequestNextInLine() {
        // R has 2, held by H until 10. L1, less urgent than P, waits first; U, more urgent, second; then P
        val shop = run(capacity = 2) {
            actor("H", 1) { val a = seize(r, 2); delay(10.0); release(a) }
            actor("L1", 9) { delay(1.0); val a = seize(r); log.add("L1 got@$time"); delay(5.0); release(a) }
            actor("U", 1) { delay(2.0); val a = seize(r); log.add("U got@$time"); delay(5.0); release(a) }
            actor("P", 2) { delay(3.0); val a = preempt(r, 1, rule = InterruptRule.RESUME); log.add("P got@$time"); delay(5.0); release(a) }
        }
        assertEquals(listOf("P got@10.0", "L1 got@10.0", "U got@15.0"), shop.log)
    }

    @Test
    @DisplayName("a preemptor compared with a pool's request next in line goes ahead of it, and is never left beside free units")
    fun poolRequestNextInLine() {
        // R has 4, the only member P's pool uses here is R (S is held throughout). L holds 1, H 3 until 10. A pool
        // request for 2 waits, then Q for 3 of R, more urgent than X, then X to preempt 2
        val shop = run(capacity = 4, poolOfR = true) {
            actor("S holder", 1) { val a = seize(s, 2); delay(90.0); release(a) }
            actor("L", 10) { val a = seize(r); delay(80.0); release(a) }
            actor("H", 1) { val a = seize(r, 3); delay(10.0); release(a) }
            actor("Pool", 9) { delay(1.0); val a = seize(pool!!, 2); log.add("Pool got@$time"); delay(70.0); release(a) }
            actor("Q", 1) { delay(2.0); val a = seize(r, 3); log.add("Q got@$time"); delay(1.0); release(a) }
            actor("X", 2) { delay(3.0); val a = preempt(r, 2, rule = InterruptRule.RESUME); log.add("X got@$time"); delay(5.0); release(a) }
        }
        assertEquals("X got@10.0", shop.log.first(), "${shop.log}")
    }

    @Test
    @DisplayName("under a notification rule of the model's own, a preemptor is compared with each request in turn too")
    fun eachRequestInTurnUnderTheModelsOwnRule() {
        // R has 2, held by H until 10. A, more urgent than P, waits in R's queue; B, less urgent, in another; P in a third
        val shop = run(capacity = 2, configure = { r.initialRequestQueueNotificationRule = RegistrationOrderNotificationRule }) {
            actor("H", 1) { val a = seize(r, 2); delay(10.0); release(a) }
            actor("A", 1) { delay(1.0); val a = seize(r); log.add("A got@$time"); delay(5.0); release(a) }
            actor("B", 5) { delay(2.0); val a = seize(r, queue = thirdQ); log.add("B got@$time"); delay(1.0); release(a) }
            actor("P", 2) { delay(3.0); val a = preempt(r, 1, queue = otherQ, rule = InterruptRule.RESUME); log.add("P got@$time"); delay(1.0); release(a) }
        }
        assertEquals(listOf("A got@10.0", "P got@10.0", "B got@11.0"), shop.log)
    }

    @Test
    @DisplayName("a preemptor that gave way to a reservation takes from a holder it outranks once the reservation is served")
    fun takesFromAHolderAfterAReservationIsServed() {
        // R has 2: L (least urgent) holds 1 until 80, H 1 until 5. F reserves L's unit under FINISH at 1. R fails
        // from 3 to 6, and P, waiting from 4, gives way to F's reservation at the repair; then it takes L's unit
        val shop = run(capacity = 2) {
            actor("L", 10) { val a = seize(r); delay(80.0); release(a) }
            actor("H", 1) { val a = seize(r); delay(5.0); release(a) }
            actor("F", 1) { delay(1.0); val a = preempt(r, 1, rule = InterruptRule.FINISH); log.add("F got@$time"); delay(50.0); release(a) }
            actor("P", 2) { delay(4.0); val a = preempt(r, 1, rule = InterruptRule.RESUME); log.add("P got@$time"); delay(1.0); release(a) }
            actor("Breaker", 1) { delay(3.0); r.fail(InterruptRule.FINISH); delay(3.0); r.repair() }
        }
        assertEquals(listOf("F got@6.0", "P got@6.0"), shop.log)
    }

    @Test
    @DisplayName("under a notification rule of the model's own, a more urgent request in a queue not yet offered does not hold a preemptor back")
    fun ownRuleComparesWithTheQueueBeingOffered() {
        // R has 1, held by H until 10. L, less urgent than P, waits in R's own queue, offered first; X, more urgent,
        // in a queue offered later; P preempts through another
        val shop = run(capacity = 1, configure = { r.initialRequestQueueNotificationRule = RegistrationOrderNotificationRule }) {
            actor("H", 1) { val a = seize(r); delay(10.0); release(a) }
            actor("L", 9) { delay(1.0); val a = seize(r); log.add("L got@$time"); delay(5.0); release(a) }
            actor("X", 0) { delay(2.0); val a = seize(r, queue = thirdQ); log.add("X got@$time"); delay(5.0); release(a) }
            actor("P", 2) { delay(3.0); val a = preempt(r, 1, queue = otherQ, rule = InterruptRule.RESUME); log.add("P got@$time"); delay(5.0); release(a) }
        }
        assertEquals(listOf("P got@10.0", "L got@15.0", "X got@20.0"), shop.log)
    }
}
