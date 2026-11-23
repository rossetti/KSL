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

/**
 *  A delay held by several reasons at once goes on only when the last ends, and starts again in full if any of them
 *  asked for a restart, whichever ends last. A process interrupt of the restart form asks for a restart; that holds
 *  even when a failure or a preemption outlasts the interrupt.
 *
 *  In each case J seizes M at 0 and starts a delay of 10.
 */
class RestartIntentOverlapTest {

    private class Shop(parent: ModelElement, val setup: Shop.(Job) -> Unit) : ProcessModel(parent, "Shop") {
        val m = ResourceWithQ(this, "M")
        var completedAt = Double.NaN

        inner class Job : Entity("J") {
            val work: KSLProcess = process {
                val a = seize(m)
                delay(10.0, suspensionName = "work")
                completedAt = time
                release(a)
            }
        }

        inner class Actor(tag: String, prio: Int, body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process(isDefaultProcess = true, block = body)
        }

        fun actor(tag: String, prio: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, prio, body).also { activate(it.p) }

        override fun initialize() {
            val job = Job()
            job.priority = 9
            activate(job.work)
            setup(job)
        }
    }

    private fun run(setup: Shop.(Shop.Job) -> Unit): Double {
        val model = Model("RestartIntent${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return shop.completedAt
    }

    @Test
    @DisplayName("a restart interrupt from 2 to 4, a RESUME failure from 3 to 6: the delay starts in full at 6 and ends at 16")
    fun restartInterruptEndsBeforeAFailure() {
        val done = run { job ->
            actor("I") { delay(2.0); interruptDelayAndRestart(job.work, "work", 2.0) }
            actor("F") { delay(3.0); m.fail(InterruptRule.RESUME); delay(3.0); m.repair() }
        }
        assertEquals(16.0, done)
    }

    @Test
    @DisplayName("a RESUME failure from 1 to 5, a restart interrupt from 2 to 7: the delay starts in full at 7 and ends at 17")
    fun restartInterruptEndsAfterAFailure() {
        val done = run { job ->
            actor("F") { delay(1.0); m.fail(InterruptRule.RESUME); delay(4.0); m.repair() }
            actor("I") { delay(2.0); interruptDelayAndRestart(job.work, "work", 5.0) }
        }
        assertEquals(17.0, done)
    }

    @Test
    @DisplayName("a restart interrupt from 2 to 4, a RESUME preemption from 3 to 6: the delay starts in full at 6 and ends at 16")
    fun restartInterruptEndsBeforeAPreemption() {
        val done = run { job ->
            actor("I") { delay(2.0); interruptDelayAndRestart(job.work, "work", 2.0) }
            actor("U", prio = 1) { delay(3.0); val a = preempt(m, rule = InterruptRule.RESUME); delay(3.0); release(a) }
        }
        assertEquals(16.0, done)
    }

    @Test
    @DisplayName("the process form: an interrupting process from 2 to 4, a RESUME failure from 3 to 6: the delay ends at 16")
    fun processRestartInterruptEndsBeforeAFailure() {
        val done = run { job ->
            // not activated: the interrupt starts it, and it runs from 2 to 4
            val other = Actor("Other", 5) { delay(2.0) }
            actor("I") { delay(2.0); interruptDelayWithProcessAndRestart(job.work, "work", other.p) }
            actor("F") { delay(3.0); m.fail(InterruptRule.RESUME); delay(3.0); m.repair() }
        }
        assertEquals(16.0, done)
    }

    @Test
    @DisplayName("the interrupting process terminated at 3.5 during a RESUME failure from 3 to 6: the restart it asked for holds, and the delay ends at 16")
    fun interruptingProcessTerminatedDuringAFailure() {
        val done = run { job ->
            val interrupter = actor("I") { delay(2.0); interruptDelayAndRestart(job.work, "work", 10.0) }
            actor("F") { delay(3.0); m.fail(InterruptRule.RESUME); delay(3.0); m.repair() }
            actor("K") { delay(3.5); interrupter.terminateProcess() }
        }
        assertEquals(16.0, done)
    }

    @Test
    @DisplayName("a continue interrupt from 2 to 4, a RESUME failure from 3 to 6: the delay goes on with the time left, ending at 14")
    fun continueInterruptKeepsTheTimeLeft() {
        val done = run { job ->
            actor("I") { delay(2.0); interruptDelayAndContinue(job.work, "work", 2.0) }
            actor("F") { delay(3.0); m.fail(InterruptRule.RESUME); delay(3.0); m.repair() }
        }
        assertEquals(14.0, done)
    }
}
