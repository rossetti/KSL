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

package ksl.examples.general.models.wsc2025

import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 *  [TestAndRepairSystem] obeys Little's Law: the time-average number of parts in the system equals the
 *  arrival rate times the mean time in the system.
 *
 *  Its test workers are seized directly and are also members of the transport pool, and its diagnostic and
 *  repair workers each belong to two pools. Before R1.8 a worker released by one route was never offered to
 *  the parts waiting for it by another, so work in process grew through the run and almost nothing left:
 *  6243 parts in the system on average against a Little's Law figure of 13.9. Since R1.8 freed units are
 *  offered to every request that may want them, and the two figures agree.
 */
class TestAndRepairSystemLittlesLawTest {

    @Test
    @DisplayName("Test-and-repair system obeys Little's Law")
    fun littlesLaw() {
        val m = Model("TestAndRepairLittle", autoCSVReports = false)
        val tr = TestAndRepairSystem(m, "TestAndRepair")
        m.numberOfReplications = 1
        m.lengthOfReplication = 52.0 * 5.0 * 2.0 * 480.0
        m.simulate()
        val l = tr.numInSystem.acrossReplicationStatistic.average
        val w = tr.systemTime.acrossReplicationStatistic.average
        val lambdaW = w / MEAN_TIME_BETWEEN_ARRIVALS
        println("TestAndRepairSystem: NumInSystem = $l, TimeInSystem = $w, lambda * W = $lambdaW")
        assertTrue(abs(l - lambdaW) / lambdaW <= 0.05, "L = $l against lambda * W = $lambdaW")
    }

    companion object {
        /** The model's arrival process is exponential with this mean. */
        const val MEAN_TIME_BETWEEN_ARRIVALS = 20.0
    }
}
