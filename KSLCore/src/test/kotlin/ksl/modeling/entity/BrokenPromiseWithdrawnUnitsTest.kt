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
 *  A seize trigger's promise breaks, and the units it withdraws are offered on: the wait's other triggers are back in
 *  their places by then, so a request of the same wait ahead in line takes them.
 *
 *  W waits from 1 for the first of a pool seize (2 units of P, whose members are R1 and R2) and a seize of R2. C seizes
 *  R2 at 2, behind W's request. At 5 H releases R1 and R2, which wakes the pool trigger with both promised, and fails
 *  R1 in the same step. The pool trigger's promise breaks; R2 is offered on, and W's R2 request, ahead of C, takes it.
 */
class BrokenPromiseWithdrawnUnitsTest {

    private class Shop(parent: ModelElement, val separateQueues: Boolean) : ProcessModel(parent, "Shop") {
        val r1 = Resource(this, "R1", capacity = 1)
        val r2 = Resource(this, "R2", capacity = 1)
        val pool = ResourcePool(this, listOf(r1, r2), "P")
        val poolQ = RequestQ(this, "PoolQ")
        val r2Q = RequestQ(this, "R2Q")
        val log = mutableListOf<String>()

        inner class Actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true, block = body)
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = activate(Actor(tag, body).p)

        override fun initialize() {
            log.clear()
            val qForR2 = if (separateQueues) r2Q else poolQ
            actor("H") {
                val a1 = seize(r1, queue = poolQ)
                val a2 = seize(r2, queue = poolQ)
                delay(5.0)
                release(a1)
                release(a2)
                r1.fail()
            }
            actor("W") {
                delay(1.0)
                val poolTrigger = pool.seizeTrigger(2, queue = poolQ)
                val r2Trigger = r2.seizeTrigger(queue = qForR2)
                val res = waitForFirst(poolTrigger, r2Trigger, timeout = 50.0)
                log.add("W ${if (res.firedBy(r2Trigger)) "R2" else if (res.timedOut) "timeout" else "pool"}@$time")
                if (res.firedBy(r2Trigger)) release(res.valueOf(r2Trigger))
                if (res.firedBy(poolTrigger)) release(res.valueOf(poolTrigger))
            }
            actor("C") {
                delay(2.0)
                val a = seize(r2, queue = qForR2)
                log.add("C R2@$time")
                delay(30.0)
                release(a)
            }
        }
    }

    private fun run(separateQueues: Boolean): List<String> {
        val model = Model("BrokenPromiseWithdrawn${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, separateQueues)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        var error: Throwable? = null
        try {
            model.simulate()
        } catch (e: Throwable) {
            error = e
        }
        assertNull(error, "the run stopped: $error")
        return shop.log
    }

    @Test
    @DisplayName("all requests in one queue: the withdrawn unit goes to the wait's other request, ahead of C")
    fun oneQueue() {
        assertEquals("W R2@5.0", run(separateQueues = false).first())
    }

    @Test
    @DisplayName("the R2 requests in their own queue: the withdrawn unit goes to the wait's other request, ahead of C")
    fun separateQueues() {
        assertEquals("W R2@5.0", run(separateQueues = true).first())
    }
}
