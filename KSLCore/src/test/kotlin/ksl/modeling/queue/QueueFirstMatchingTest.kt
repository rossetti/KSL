/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ksl.modeling.queue

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs [check] in a replication, on a queue holding ten items with mixed priorities. */
private class QueueHolder(
    parent: ModelElement,
    discipline: Queue.Discipline,
    private val check: (Queue<ModelElement.QObject>, List<ModelElement.QObject>) -> Unit
) : ModelElement(parent, "QueueHolder") {
    private val queue = Queue<ModelElement.QObject>(this, "Line", discipline)

    override fun initialize() {
        schedule(this::fill, 1.0)
    }

    private fun fill(event: ksl.simulation.KSLEvent<Nothing>) {
        val items = List(10) { i -> QObject().also { it.priority = 10 - i } }
        items.forEach { queue.enqueue(it) }
        check(queue, items)
        queue.clear()
    }
}

@DisplayName("Finding the first match in a queue")
class QueueFirstMatchingTest {

    private fun run(discipline: Queue.Discipline, check: (Queue<ModelElement.QObject>, List<ModelElement.QObject>) -> Unit) {
        val model = Model("FirstMatching${System.nanoTime()}", autoCSVReports = false)
        QueueHolder(model, discipline, check)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
    }

    private val orderedDisciplines = listOf(Queue.Discipline.FIFO, Queue.Discipline.LIFO, Queue.Discipline.RANKED)

    @Test
    @DisplayName("agrees with the first of the filtered list, under each ordered discipline")
    fun agreesWithFilteredList() {
        var checks = 0
        for (discipline in orderedDisciplines) {
            run(discipline) { queue, items ->
                val wanted = setOf(items[2], items[5], items[7])
                assertEquals(queue.filteredOrderedList { it in wanted }.first(), queue.firstMatching { it in wanted })
                checks++
            }
        }
        assertEquals(3, checks)
    }

    @Test
    @DisplayName("stops at the first match, under each ordered discipline")
    fun stopsAtTheFirstMatch() {
        var checks = 0
        for (discipline in orderedDisciplines) {
            run(discipline) { queue, _ ->
                var tested = 0
                val first = queue.orderedList().first()
                queue.firstMatching { tested++; it === first }
                assertEquals(1, tested, "$discipline tested the rest of the queue")
                checks++
            }
        }
        assertEquals(3, checks)
    }

    @Test
    @DisplayName("gives null when nothing matches")
    fun nullWhenNoMatch() {
        var checks = 0
        for (discipline in orderedDisciplines + Queue.Discipline.RANDOM) {
            run(discipline) { queue, _ ->
                assertNull(queue.firstMatching { false })
                assertTrue(queue.filteredOrderedList { false }.isEmpty())
                checks++
            }
        }
        assertEquals(4, checks)
    }

    @Test
    @DisplayName("a random queue gives a match from its current draw")
    fun randomDisciplineMatchesFromItsDraw() {
        var checks = 0
        run(Queue.Discipline.RANDOM) { queue, items ->
            val wanted = setOf(items[2], items[5])
            val found = queue.firstMatching { it in wanted }
            assertTrue(found in wanted)
            assertEquals(queue.filteredOrderedList { it in wanted }.first(), found, "the same draw")
            checks++
        }
        assertEquals(1, checks)
    }

    @Test
    @DisplayName("a random queue's next stays the same until the queue changes, and the item peeked is the one removed")
    fun randomDrawIsStableUntilTheQueueChanges() {
        var checks = 0
        run(Queue.Discipline.RANDOM) { queue, _ ->
            val peeked = queue.peekNext()
            assertEquals(peeked, queue.peekNext(), "a second look sees the same next")
            assertEquals(peeked, queue.firstMatching { true }, "the first match of everything is the next")
            assertEquals(peeked, queue.filteredOrderedList { true }.first(), "the ordered list starts with it")
            assertEquals(peeked, queue.removeNext(), "the item peeked is the one removed")
            val after = queue.peekNext()
            assertTrue(after != null && after != peeked)
            assertEquals(after, queue.peekNext())
            checks++
        }
        assertEquals(1, checks)
    }

    @Test
    @DisplayName("a random queue draws afresh in each replication, and any item can come first")
    fun randomDrawCoversEveryItem() {
        val firsts = mutableSetOf<Int>()
        val model = Model("RandomDraws${System.nanoTime()}", autoCSVReports = false)
        QueueHolder(model, Queue.Discipline.RANDOM) { queue, items -> firsts.add(items.indexOf(queue.peekNext())) }
        model.numberOfReplications = 200
        model.lengthOfReplication = 10.0
        model.simulate()
        assertEquals((0..9).toSet(), firsts, "every one of the ten came first in some replication")
    }

    @Test
    @DisplayName("an item removed and put straight back gets a fresh draw, not the order drawn before")
    fun randomDrawAfterRemoveAndReturn() {
        var samples = 0
        var nextAgain = 0
        val model = Model("RandomReturn${System.nanoTime()}", autoCSVReports = false)
        QueueHolder(model, Queue.Discipline.RANDOM) { queue, items ->
            queue.peekNext()
            val removed = queue.removeNext()!!
            // the item last in the queue's list goes back to the same place: the list looks as it did
            if (removed === items.last()) {
                queue.enqueue(removed)
                samples++
                if (queue.peekNext() === removed) nextAgain++
            }
        }
        model.numberOfReplications = 1000
        model.lengthOfReplication = 10.0
        model.simulate()
        assertTrue(samples >= 40, "enough cases: $samples")
        // with a fresh draw it comes next again one time in ten
        assertTrue(nextAgain < samples / 2, "it came next again $nextAgain times in $samples")
    }
}
