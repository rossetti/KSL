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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  Legitimate models that the invariant checks must accept: a queue whose discipline the model switches during the
 *  replication, and a holder whose priority the model changes in the instant a request is woken.
 */
class InvariantChecksLegitimateModelsTest {

    private var wasEnabled = false

    @BeforeEach
    fun enableInvariants() {
        wasEnabled = ResourceInvariants.enabled
        ResourceInvariants.enabled = true
    }

    @AfterEach
    fun restoreInvariants() {
        ResourceInvariants.enabled = wasEnabled
    }

    private class Shop(parent: ModelElement, val setup: Shop.() -> Unit) : ProcessModel(parent, "Shop") {
        val signal = Signal(this, "S")
        val r3 = ResourceWithQ(this, "R3", capacity = 3)
        val log = mutableListOf<String>()

        inner class Actor(tag: String, prio: Int, body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process(isDefaultProcess = true, block = body)
        }

        fun actor(tag: String, prio: Int, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, prio, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) = actor("at$t", 5) { delay(t); action() }

        override fun initialize() {
            log.clear()
            setup()
        }
    }

    private fun run(setup: Shop.() -> Unit): List<String> {
        val model = Model("LegitimateModels${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, setup)
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
    @DisplayName("a signal's queue switched from RANKED to FIFO during the replication keeps its order and passes the checks")
    fun signalQueueSwitchedToFifo() {
        val log = run {
            signal.waitingQ.currentDiscipline = Queue.Discipline.RANKED
            actor("A", 5) { delay(1.0); waitFor(signal); log.add("A@$time") }
            actor("B", 1) { delay(2.0); waitFor(signal); log.add("B@$time") }
            at(3.0) { signal.waitingQ.currentDiscipline = Queue.Discipline.FIFO }
            at(4.0) { signal.signalAll() }
        }
        assertEquals(setOf("A@4.0", "B@4.0"), log.toSet())
    }

    @Test
    @DisplayName("a holder made less urgent in the instant a request is rightly woken ahead of a waiting preemptor passes the checks")
    fun holderDemotedInTheInstantOfAWake() {
        val log = run {
            lateinit var h1: Shop.Actor
            // H1 and H2 hold all three units; P cannot preempt them, so H2's freed unit goes to A
            h1 = actor("H1", 1) { val x = seize(r3, 2); delay(50.0); release(x) }
            actor("H2", 1) {
                val x = seize(r3, 1)
                delay(5.0)
                release(x)
                h1.priority = 10
            }
            actor("P", 5) { delay(1.0); val x = preempt(r3, 2, rule = InterruptRule.RESUME); log.add("P@$time"); release(x) }
            actor("A", 5) { delay(2.0); val x = seize(r3, 1); log.add("A@$time"); delay(1.0); release(x) }
        }
        assertEquals("A@5.0", log.first())
    }
}
