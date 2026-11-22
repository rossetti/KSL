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

package ksl.modeling.entity.guiderules

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 *  Rules of the entity guide's section on what runs at the end of a replication: an entity still suspended when
 *  the events end is terminated, and its `finally` blocks run, then its termination hook
 *  (`handleTerminatedProcess`), then any `afterTermination` callback, in that order; and an entity whose
 *  activation is still pending when a replication ends cannot be activated in the next replication.
 */
class EntityEndOfReplicationRulesTest {

    /** J seizes R and waits in a delay of 100, with a `finally` block, and records its termination hook. */
    private class EndShop(parent: ModelElement, val terminateAt: Double?) : ProcessModel(parent, "EndShop") {
        val r = ResourceWithQ(this, "R")
        val log = mutableListOf<String>()

        inner class Job : Entity("J") {
            val script: KSLProcess = process("job") {
                val a = seize(r)
                try {
                    delay(100.0)
                    release(a)
                } finally {
                    log.add("finally")
                }
            }

            override fun handleTerminatedProcess(terminatedProcess: KSLProcess) {
                log.add("handleTerminatedProcess")
            }
        }

        override fun initialize() {
            log.clear()
            val job = Job()
            activate(job.script)
            if (terminateAt != null) {
                schedule({ _: KSLEvent<Nothing> -> job.terminateProcess { log.add("afterTermination") } }, terminateAt)
            }
        }
    }

    private fun runEndShop(terminateAt: Double?): List<String> {
        val model = Model("EndOfReplicationOrder${System.nanoTime()}", autoCSVReports = false)
        val shop = EndShop(model, terminateAt)
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        return shop.log
    }

    @Test
    @DisplayName("an entity suspended at the end of a replication: its finally block runs, then its termination hook")
    fun finallyThenTerminationHookAtTheEnd() {
        assertEquals(listOf("finally", "handleTerminatedProcess"), runEndShop(terminateAt = null))
    }

    @Test
    @DisplayName("a termination with an afterTermination callback: finally, then the termination hook, then the callback")
    fun finallyThenHookThenAfterTermination() {
        assertEquals(listOf("finally", "handleTerminatedProcess", "afterTermination"), runEndShop(terminateAt = 5.0))
    }

    /**
     *  In the first replication (length 100) an entity kept in a property is activated for time 200, after the end.
     *  In the second, `initialize` tries to activate it again: the attempt is refused.
     */
    private class PendingShop(parent: ModelElement) : ProcessModel(parent, "PendingShop") {
        var kept: Job? = null
        var secondActivationError: Throwable? = null
        var secondActivationTried = false

        inner class Job : Entity("K") {
            val script: KSLProcess = process("kept") { delay(1.0) }
        }

        override fun initialize() {
            val k = kept
            if (k == null) {
                val job = Job()
                kept = job
                activate(job.script, timeUntilActivation = 200.0)
            } else {
                secondActivationTried = true
                secondActivationError = runCatching { activate(k.script) }.exceptionOrNull()
            }
        }
    }

    @Test
    @DisplayName("an entity whose activation is pending at the end of a replication cannot be activated in the next")
    fun pendingActivationCannotBeReactivated() {
        val model = Model("PendingActivation${System.nanoTime()}", autoCSVReports = false)
        val shop = PendingShop(model)
        model.numberOfReplications = 2
        model.lengthOfReplication = 100.0
        model.simulate()
        assertEquals(true, shop.secondActivationTried)
        val error = assertNotNull(shop.secondActivationError, "activating the kept entity in the second replication should be refused")
        assertIs<IllegalStateException>(error, "refused as an activation from an illegal state: $error")
    }
}
