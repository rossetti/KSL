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
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Termination of an entity that a model has also put in a queue of its own, or that has started a blockage. */
class TerminationReviewFindingsTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val inSystem = Queue<Entity>(this, "InSystem")
        lateinit var blocker: Blocker
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()
        val actors = mutableMapOf<String, Entity>()

        inner class InQueue(tag: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                inSystem.enqueue(this@InQueue)
                delay(10.0)
                inSystem.remove(this@InQueue)
                log.add("${this@InQueue.name} done@$time")
            }
        }

        inner class Blocker(tag: String) : Entity(tag) {
            val blockage = Blockage("B")
            val p: KSLProcess = process(isDefaultProcess = true) {
                startBlockage(blockage)
                delay(10.0)
                clearBlockage(blockage)
            }
        }

        inner class Blocked(tag: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(1.0)
                waitFor(blocker.blockage)
                log.add("${this@Blocked.name} through@$time")
            }
        }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            log.clear()
            actors.clear()
            for (s in setups) s()
        }
    }

    private fun run(setup: Shop.() -> Unit): Pair<Shop, Throwable?> {
        val model = Model("TerminationFindings${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        shop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return shop to error
    }

    @Test
    @DisplayName("an entity terminated in a delay while it is also in a queue of the model's own: the delay is cancelled")
    fun delayCancelledForAQueuedEntity() {
        val (shop, error) = run {
            val e = InQueue("Q1").also { activate(it.p) }
            at(5.0) {
                e.terminateProcess()
                log.add("terminated@$time inQueue=${inSystem.contains(e)}")
            }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("terminated@5.0 inQueue=false"), shop.log)
    }

    @Test
    @DisplayName("an entity terminated while its blockage is active: the blockage ends and the entities blocked by it go on")
    fun blockageEndsAtTermination() {
        val (shop, error) = run {
            val b = Blocker("Bk").also { blocker = it; activate(it.p) }
            activate(Blocked("W").p)
            at(5.0) { b.terminateProcess() }
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("W through@5.0"), shop.log)
    }
}
