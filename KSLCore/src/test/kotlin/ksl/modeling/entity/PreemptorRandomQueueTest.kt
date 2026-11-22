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

import ksl.modeling.queue.Queue
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A waiting preemptor in a random request queue is compared with the request the freed units then go to: the one
 *  drawn for them, not another draw. It gives way to a more urgent request only when that request is served, and it
 *  goes ahead of a less urgent one.
 */
class PreemptorRandomQueueTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "RandomQ") {
        val r = ResourceWithQ(this, "R", queue = RequestQ(this, "R:Q", Queue.Discipline.RANDOM))
        val seizedAt = linkedMapOf<String, Double>()
        val perReplication = mutableListOf<String>()

        inner class Actor(tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, priority: Int, body: suspend KSLProcessBuilder.() -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        init {
            r.interruptRule = InterruptRule.RESUME
        }

        override fun initialize() {
            seizedAt.clear()
            // holds R until 10; it outranks the preemptor, so the preemptor waits
            actor("H", 3) { val a = seize(r); delay(10.0); release(a) }
            actor("P", 5) { delay(1.0); val a = preempt(r); seizedAt["P"] = time; delay(10.0); release(a) }
            actor("A", 1) { delay(2.0); val a = seize(r); seizedAt["A"] = time; delay(10.0); release(a) }
            actor("B", 9) { delay(2.0); val a = seize(r); seizedAt["B"] = time; delay(10.0); release(a) }
        }

        override fun replicationEnded() {
            perReplication.add("preempted=${r.numTimesPreempted} order=${seizedAt.keys.joinToString("")}")
        }
    }

    @Test
    @DisplayName("a waiting preemptor in a random queue never gives way to a request and then sees a less urgent one served")
    fun comparedWithTheRequestServed() {
        val model = Model("RandomQ${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        model.numberOfReplications = 200
        model.lengthOfReplication = 100.0
        model.simulate()
        val bad = shop.perReplication.withIndex().filter { (_, s) -> !s.startsWith("preempted=0") || s.indexOf('B') < s.indexOf('P') }
        assertEquals(emptyList(), bad.take(5), "B is served only after P, and nobody is preempted (${bad.size} of 200 wrong)")
        // both orders the draw allows occur: A first, or P first
        val orders = shop.perReplication.map { it.substringAfter("order=") }.toSet()
        assertTrue(orders.containsAll(setOf("APB", "PAB")), "the draws gave $orders")
    }
}
