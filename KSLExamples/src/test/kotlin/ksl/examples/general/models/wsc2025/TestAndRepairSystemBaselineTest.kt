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
import kotlin.test.assertEquals

/**
 *  A baseline for [TestAndRepairSystem], recorded on `develop` at `c06710b0c` before the R1.8 pool wakeup
 *  rework.
 *
 *  The model's test workers are seized directly and are also members of the transport pool, and its
 *  diagnostic and repair workers each belong to two pools. Those are the lost-wakeup cases, so these numbers
 *  are wrong today (work in process grows through the run) and are expected to change when the wake routine
 *  lands. They are pinned so the change is seen; the rework replaces this test with a Little's Law check.
 */
class TestAndRepairSystemBaselineTest {

    @Test
    @DisplayName("Test-and-repair system before the pool wakeup rework (expected to change)")
    fun beforeTheWakeupRework() {
        val m = Model("TestAndRepairBaseline", autoCSVReports = false)
        val tr = TestAndRepairSystem(m, "TestAndRepair")
        m.numberOfReplications = 1
        m.lengthOfReplication = 52.0 * 5.0 * 2.0 * 480.0
        m.simulate()
        val wip = tr.numInSystem.acrossReplicationStatistic.average
        val timeInSystem = tr.systemTime.acrossReplicationStatistic.average
        println("TestAndRepairSystem: NumInSystem = $wip, TimeInSystem = $timeInSystem")
        assertEquals(NUM_IN_SYSTEM, wip, 1e-9)
        assertEquals(TIME_IN_SYSTEM, timeInSystem, 1e-9)
    }

    companion object {
        // Recorded on develop @ c06710b0c, before the R1.8 changes.
        const val NUM_IN_SYSTEM = 6243.047015133311
        const val TIME_IN_SYSTEM = 277.80116541321536
    }
}
