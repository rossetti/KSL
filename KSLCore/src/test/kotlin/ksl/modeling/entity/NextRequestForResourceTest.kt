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
 * A seize that finds free units asks the queue for the next request it can serve. That search stops at
 * the first one, under each ordered discipline.
 *
 * One of two units is held for the whole run, and nine entities wait for two units each. A tenth
 * arrives wanting one, and the free unit is its to take.
 */
@DisplayName("The next request for a resource")
class NextRequestForResourceTest {

    /** Counts how many times a waiting request's resource is compared with this one. */
    private class CountingResource(parent: ModelElement) : Resource(parent, "Server", 2) {
        var comparisons = 0
        override fun equals(other: Any?): Boolean {
            comparisons++
            return super.equals(other)
        }

        override fun hashCode(): Int = super.hashCode()
    }

    private class Shop(parent: ModelElement, discipline: Queue.Discipline) : ProcessModel(parent, "Shop") {
        val server = CountingResource(this)
        val line = RequestQ(this, "Line", discipline)
        var servedAt = Double.NaN
        var waitingWhenServed = -1
        var comparisonsBySeize = 0

        inner class Holder : Entity() {
            val p: KSLProcess = process {
                seize(server, 1, queue = line)
                delay(1000.0)
            }
        }

        inner class Waiter : Entity() {
            val p: KSLProcess = process {
                delay(1.0)
                seize(server, 2, queue = line)
            }
        }

        inner class Late : Entity() {
            val p: KSLProcess = process {
                delay(5.0)
                val before = server.comparisons
                seize(server, 1, queue = line)
                comparisonsBySeize = server.comparisons - before
                servedAt = time
                waitingWhenServed = line.size
                delay(1000.0)
            }
        }

        override fun initialize() {
            activate(Holder().p)
            repeat(9) { activate(Waiter().p) }
            activate(Late().p)
        }
    }

    private fun run(discipline: Queue.Discipline): Shop {
        val m = Model("NextRequest${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(m, discipline)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("the arriving entity is served at once, under every discipline")
    fun servedAtOnce() {
        for (d in listOf(Queue.Discipline.FIFO, Queue.Discipline.LIFO, Queue.Discipline.RANKED, Queue.Discipline.RANDOM)) {
            val s = run(d)
            assertEquals(5.0, s.servedAt, 0.0, "$d")
            assertEquals(9, s.waitingWhenServed, "$d: the nine asking for two units should still be waiting")
        }
    }

    @Test
    @DisplayName("the search stops at the first request it can serve")
    fun stopsAtTheFirstMatch() {
        // Last in, so first in the search: FIFO has to look past the nine, and LIFO doesn't.
        val fifo = run(Queue.Discipline.FIFO).comparisonsBySeize
        val lifo = run(Queue.Discipline.LIFO).comparisonsBySeize
        assertTrue(fifo >= 10, "FIFO should compare all ten requests, but compared $fifo")
        assertEquals(fifo - 9, lifo, "LIFO looked past the first request it could serve")
    }
}
