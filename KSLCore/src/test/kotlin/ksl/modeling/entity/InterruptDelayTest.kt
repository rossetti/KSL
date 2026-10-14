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
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Interrupted delays.
 */
class InterruptDelayTest {

    /**
     *  The target finished a delay named "work" at 2 and its process has ended. At 5 another process asks
     *  to interrupt "work". The target is not in that delay, so the interrupt is misuse.
     *
     *  On `develop` at `c06710b0c` this fails with `IllegalArgumentException: The post interrupt
     *  delay time must be >= 0.0`: the stale delay event gives a negative time remaining before the target's
     *  state is checked.
     */
    private class IdleTargetAfterEarlierDelay(parent: ModelElement) : ProcessModel(parent, null) {
        private lateinit var target: Worker

        inner class Worker : Entity() {
            val script: KSLProcess = process { delay(2.0, suspensionName = "work") }
        }

        inner class Interrupter : Entity() {
            val script: KSLProcess = process {
                delay(5.0)
                interruptDelayAndContinue(target.script, "work", 1.0)
            }
        }

        override fun initialize() {
            target = Worker()
            activate(target.script)
            activate(Interrupter().script)
        }
    }

    @Test
    @Disabled("Enabled with the R1.8 interrupted-delay fix")
    @DisplayName("Interrupting a target that is not in the named delay is an error that names its state")
    fun interruptOutsideTheNamedDelayIsAnError() {
        val m = Model("IdleTarget", autoCSVReports = false)
        IdleTargetAfterEarlierDelay(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        val error = runCatching { m.simulate() }.exceptionOrNull()
        assertNotNull(error, "the misuse must stop the run")
        val cause = generateSequence(error) { it.cause }.last()
        assertIs<IllegalStateException>(cause, "got ${cause::class.simpleName}: ${cause.message}")
        assertTrue(cause.message!!.contains("isInDelay"), "the message points to the check: ${cause.message}")
    }
}
