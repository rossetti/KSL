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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A wait's timer acts only on the wait it was set for. Every way a wait ends cancels its timer; the timer
 *  also checks, when it runs, that the entity is still in that wait. The check matters for a wait ended by
 *  termination, which was never claimed: a timer that ran on it would claim it, and schedule a resume for
 *  whatever the entity waits in next. The test loses the cancellation on purpose to reach the check.
 */
class StaleTimerTest {

    private class Reuse(parent: ModelElement) : ProcessModel(parent, "reuse") {
        val first = Signal(this, "First")
        val second = Signal(this, "Second")
        val log = mutableListOf<String>()
        lateinit var worker: Worker
        var timedGroup: WaitGroup? = null

        inner class Worker : Entity("worker") {
            val timed: KSLProcess = process("timed") {
                val r = waitForFirst(first.trigger(), timeout = 10.0)
                log.add("timed ended@$time timedOut=${r.timedOut}")
            }
            val next: KSLProcess = process("next") {
                waitFor(second)
                log.add("next ended@$time")
            }
        }

        override fun initialize() {
            log.clear()
            worker = Worker()
            activate(worker.timed)
            schedule({ _: KSLEvent<Nothing> -> timedGroup = worker.myWaitGroup }, 1.0)
            schedule({ _: KSLEvent<Nothing> ->
                worker.terminateProcess()
                // the cancellation of the timer is lost: the timer will run at its deadline, 10
                timedGroup!!.timerEvent!!.cancel = false
                activate(worker.next)
            }, 3.0)
            schedule({ _: KSLEvent<Nothing> -> second.signal(worker) }, 20.0)
        }
    }

    @Test
    @DisplayName("a timer whose wait was ended by termination does nothing when it runs: the entity's next wait is untouched")
    fun staleTimerAfterTermination() {
        val model = Model("StaleTimer${System.nanoTime()}", autoCSVReports = false)
        val m = Reuse(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        assertEquals(listOf("next ended@20.0"), m.log)
    }
}
