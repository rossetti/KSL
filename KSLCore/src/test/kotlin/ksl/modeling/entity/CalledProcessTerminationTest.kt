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
 *  A process that waits for another entity's process and is terminated ends that process with it, whether the
 *  called process has started or is still waiting to start.
 */
class CalledProcessTerminationTest {

    /**
     *  The boss waits at 0 for the worker's job, activated after [activationDelay]. The boss is terminated at 1.
     *  The job takes 3.
     */
    private class Shop(parent: ModelElement, val activationDelay: Double) : ProcessModel(parent, "Shop") {
        var jobStart = Double.NaN
        var jobEnd = Double.NaN
        var bossDone = false

        inner class Worker : Entity("Worker") {
            val job = process {
                jobStart = time
                delay(3.0)
                jobEnd = time
            }
        }

        val worker = Worker()

        private inner class Boss : Entity("Boss") {
            val p = process(isDefaultProcess = true) {
                waitFor(worker.job, timeUntilActivation = activationDelay)
                bossDone = true
            }
        }

        private val boss = Boss()

        override fun initialize() {
            activate(boss.p)
            schedule(::terminateBoss, 1.0)
        }

        private fun terminateBoss(event: KSLEvent<Nothing>) {
            boss.terminateProcess()
        }
    }

    private fun run(activationDelay: Double): Shop {
        val model = Model("CalledProcessTermination$activationDelay", autoCSVReports = false)
        val shop = Shop(model, activationDelay)
        model.lengthOfReplication = 20.0
        model.simulate()
        return shop
    }

    @Test
    @DisplayName("a called process not yet started is not run when its caller is terminated")
    fun calledProcessNotYetStartedIsNotRun() {
        val shop = run(activationDelay = 5.0)
        assertFalse(shop.bossDone)
        assertTrue(shop.jobStart.isNaN(), "the job started at ${shop.jobStart} with no one waiting for it")
        assertFalse(shop.worker.hasPendingProcess, "the worker still has the job pending")
        assertTrue(shop.worker.job.isCreated, "the job was never started")
    }

    @Test
    @DisplayName("a called process already started is terminated with its caller")
    fun calledProcessStartedIsTerminated() {
        val shop = run(activationDelay = 0.0)
        assertFalse(shop.bossDone)
        assertEquals(0.0, shop.jobStart)
        assertTrue(shop.jobEnd.isNaN(), "the job ran on to ${shop.jobEnd} after its caller was terminated")
        assertTrue(shop.worker.job.isTerminated)
    }
}
