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
 *  A signal sent from `replicationEnded()` while entities wait for it: the replication has ended, so it resumes
 *  nothing, and the next replication starts with an empty hold queue.
 */
class SignalAtEndOfReplicationTest {

    private class Shop(parent: ModelElement, val useWaitForFirst: Boolean) : ProcessModel(parent, "Shop") {
        val signal = Signal(this, "S")
        val log = mutableListOf<String>()

        inner class Waiter : Entity("W") {
            val p: KSLProcess = process(isDefaultProcess = true) {
                if (useWaitForFirst) waitForFirst(signal.trigger()) else waitFor(signal)
                log.add("resumed")
            }
        }

        override fun initialize() {
            log.add("start r=${model.currentReplicationNumber} waiting=${signal.waitingQ.size}")
            activate(Waiter().p)
            activate(Waiter().p)
        }

        override fun replicationEnded() {
            signal.signalFirst()
            signal.signalAll()
        }
    }

    private fun run(useWaitForFirst: Boolean): Pair<Shop, Throwable?> {
        val model = Model("SignalAtEnd${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, useWaitForFirst)
        model.numberOfReplications = 2
        model.lengthOfReplication = 10.0
        val error = try {
            model.simulate(); null
        } catch (e: Throwable) {
            e
        }
        return shop to error
    }

    @Test
    @DisplayName("a signal sent in replicationEnded to entities in waitFor resumes nothing")
    fun waitForSignal() {
        val (shop, error) = run(useWaitForFirst = false)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("start r=1 waiting=0", "start r=2 waiting=0"), shop.log)
    }

    @Test
    @DisplayName("a signal sent in replicationEnded to entities in waitForFirst resumes nothing")
    fun waitForFirstSignal() {
        val (shop, error) = run(useWaitForFirst = true)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("start r=1 waiting=0", "start r=2 waiting=0"), shop.log)
    }
}
