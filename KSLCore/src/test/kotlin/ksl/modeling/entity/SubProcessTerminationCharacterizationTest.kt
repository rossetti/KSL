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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Pins today's rule for `waitFor(process)`: terminating the caller terminates the sub-process it
 *  waits on, and terminating the sub-process terminates its caller. Each leaves the queue it was
 *  waiting in, without statistics.
 */
class SubProcessTerminationCharacterizationTest {

    /**
     *  A caller entity waits on a sub-process run by a second entity. The sub-process holds in
     *  queue SUB; the caller is suspended in its waitFor. At time 5 one of them is terminated.
     */
    private class CallerModel(parent: ModelElement, val terminateCaller: Boolean) :
        ProcessModel(parent, "subProcess") {

        val subQ = HoldQueue(this, "SUB")
        val log = mutableListOf<String>()
        lateinit var caller: Caller
        lateinit var worker: Worker
        var callerEnded = false
        var workerEnded = false
        var subQSizeAfter = -1
        var subQTimeCount = Double.NaN

        inner class Worker : Entity("worker") {
            val sub: KSLProcess = process("sub") {
                hold(subQ)
                log.add("sub continued@$time")
            }

            override fun handleTerminatedProcess(process: KSLProcess) {
                log.add("worker terminated@$time")
            }
        }

        inner class Caller : Entity("caller") {
            val main: KSLProcess = process("main") {
                waitFor(worker.sub)
                log.add("caller continued@$time")
            }

            override fun handleTerminatedProcess(process: KSLProcess) {
                log.add("caller terminated@$time")
            }
        }

        override fun initialize() {
            log.clear()
            worker = Worker()
            caller = Caller()
            activate(caller.main)
            schedule(::terminate, 5.0)
            schedule(::check, 6.0)
        }

        private fun terminate(event: KSLEvent<Nothing>) {
            if (terminateCaller) caller.terminateProcess() else worker.terminateProcess()
        }

        private fun check(event: KSLEvent<Nothing>) {
            callerEnded = caller.isProcessEnded
            workerEnded = worker.isProcessEnded
            subQSizeAfter = subQ.size
        }

        override fun replicationEnded() {
            subQTimeCount = subQ.timeInQ.withinReplicationStatistic.count
        }
    }

    private fun run(terminateCaller: Boolean): CallerModel {
        val model = Model("SubProcess${System.nanoTime()}", autoCSVReports = false)
        val cm = CallerModel(model, terminateCaller)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        return cm
    }

    @Test
    @DisplayName("terminating the caller terminates the sub-process, which leaves its queue")
    fun terminatingCallerTerminatesSubProcess() {
        val m = run(terminateCaller = true)
        assertTrue(m.log.containsAll(listOf("caller terminated@5.0", "worker terminated@5.0")), "log was ${m.log}")
        assertFalse(m.log.any { it.contains("continued") }, "neither process may continue; log was ${m.log}")
        assertTrue(m.callerEnded && m.workerEnded)
        assertEquals(0, m.subQSizeAfter)
        assertEquals(0.0, m.subQTimeCount, "a terminated wait is not observed")
    }

    @Test
    @DisplayName("terminating the sub-process terminates its caller")
    fun terminatingSubProcessTerminatesCaller() {
        val m = run(terminateCaller = false)
        assertTrue(m.log.containsAll(listOf("caller terminated@5.0", "worker terminated@5.0")), "log was ${m.log}")
        assertFalse(m.log.any { it.contains("continued") }, "neither process may continue; log was ${m.log}")
        assertTrue(m.callerEnded && m.workerEnded)
        assertEquals(0, m.subQSizeAfter)
        assertEquals(0.0, m.subQTimeCount, "a terminated wait is not observed")
    }
}
