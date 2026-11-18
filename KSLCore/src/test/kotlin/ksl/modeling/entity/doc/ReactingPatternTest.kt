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

package ksl.modeling.entity.doc

import ksl.modeling.entity.InterruptRule
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Runs the entity guide's two examples of reacting to a failure or preemption ("...react to a failure or
 *  preemption"), as compiled in `EntityGuideSnippets.kt`, with a treatment of 10: the loop, which reacts to
 *  every interruption, and the form that reacts only to the first and finishes with a plain `delay`. Each case
 *  checks the time the patient finishes, which, with the time the doctor or room was away, shows that the
 *  treatment was done in full and no more.
 */
class ReactingPatternTest {

    private val defaultPriority = KSLEvent.DEFAULT_PRIORITY

    /** The two forms of the treatment: the loop, and reacting once. */
    private val forms = listOf(false, true)

    private fun name(once: Boolean) = if (once) "reacting once" else "the loop"

    private fun run(setup: HoldingAndReacting.() -> Unit): HoldingAndReacting {
        val model = Model("Reacting${System.nanoTime()}", autoCSVReports = false)
        val m = HoldingAndReacting(model, treatment = ConstantRV(10.0))
        m.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("a preemption between the restored trigger and the patient's resume: the loop waits again and loses no work")
    fun preemptionBetweenTheRestoreAndTheResume() {
        for (once in forms) {
            val log = mutableListOf<String>()
            val m = run {
                admit(once = once)
                at(1.0) { doctors.fail(InterruptRule.RESUME) }
                // the repair at an earlier event priority than the urgent entity, which preempts before the patient's
                // resume (priority 1) runs: the patient wakes to find its doctor taken again
                at(5.0, priority = -10) { doctors.repair() }
                urgent(doctors, n = 2, from = 5.0, hold = 3.0, rule = InterruptRule.RESUME, delayPriority = -5, seizePriority = -4, log = log)
            }
            // the doctor's units were back when the urgent entity looked, so the restored trigger had fired
            assertEquals(listOf("P checks@5.0 missing=false", "P got@5.0"), log, name(once))
            // 1 done, away 1 to 5 and 5 to 8, 9 more from 8
            assertEquals(listOf("finished@17.0 by Doctors"), m.finished, name(once))
        }
    }

    @Test
    @DisplayName("two interruptions in a row")
    fun twoInterruptions() {
        for (once in forms) {
            val m = run {
                admit(once = once)
                at(1.0) { doctors.fail(InterruptRule.RESUME) }
                at(5.0) { doctors.repair() }
                at(7.0) { doctors.fail(InterruptRule.RESUME) }
                at(8.0) { doctors.repair() }
            }
            // away 1 to 5 and 7 to 8: 10 of work and 5 away
            assertEquals(listOf("finished@15.0 by Doctors"), m.finished, name(once))
        }
    }

    @Test
    @DisplayName("a preemption under RESTART: the loop starts the treatment again in full; reacting once restarts what was left")
    fun restart() {
        for (once in forms) {
            val log = mutableListOf<String>()
            val m = run {
                admit(once = once)
                at(1.0) { doctors.fail(InterruptRule.RESUME) }
                at(5.0, priority = -10) { doctors.repair() }
                urgent(doctors, n = 2, from = 5.0, hold = 3.0, rule = InterruptRule.RESTART, delayPriority = -5, seizePriority = -4, log = log)
            }
            // taken again at 5 under RESTART. The loop starts all 10 again from 8; reacting once, the plain delay
            // restarts, and it holds the 9 that were left after the failure
            val expected = if (once) "finished@17.0 by Doctors" else "finished@18.0 by Doctors"
            assertEquals(listOf(expected), m.finished, name(once))
        }
    }

    @Test
    @DisplayName("the doctor not back within 15: the patient sees another doctor for the rest")
    fun timeoutTaken() {
        for (once in forms) {
            val m = run {
                admit(once = once)
                at(1.0) { doctors.fail(InterruptRule.RESUME) }
            }
            // 1 done; waits 1 to 16; 9 more with another doctor
            assertEquals(listOf("finished@25.0 by OtherDoctors"), m.finished, name(once))
        }
    }

    @Test
    @DisplayName("the room, not the doctor, taken: the patient waits for the room and keeps its doctor")
    fun roomTaken() {
        for (once in forms) {
            val m = run {
                admit(once = once)
                at(1.0, priority = defaultPriority) { rooms.fail(InterruptRule.RESUME) }
                at(5.0) { rooms.repair() }
            }
            assertEquals(listOf("finished@14.0 by Doctors"), m.finished, name(once))
        }
    }
}
