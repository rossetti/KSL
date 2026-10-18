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

package ksl.modeling.variable

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  A [HistoricalVariable] with the default STOP option that runs out of data. The class documents that the
 *  current replication stops and no further replications run. Before R1.8 the default stop action called
 *  `Model.endSimulation()` from inside the replication, which throws, so the run ended unfinished with no
 *  statistics.
 */
class HistoricalVariableStopTest {

    /** Schedules an event after each value read; the data run out after three values. */
    private class Ticker(parent: ModelElement, private val tba: GetValueIfc) : ModelElement(parent, "Ticker") {
        val count = Counter(this, "Ticks")
        var lastTickTime = Double.NaN

        override fun initialize() {
            schedule(this::tick, tba)
        }

        private fun tick(event: KSLEvent<Nothing>) {
            count.increment()
            lastTickTime = time
            schedule(this::tick, tba)
        }
    }

    @Test
    fun runningOutOfDataEndsTheReplicationAndTheExperiment() {
        val m = Model("HistoricalStop", autoCSVReports = false)
        val h = HistoricalVariable(m, doubleArrayOf(1.0, 2.0, 3.0), "tba")
        val ticker = Ticker(m, h)
        m.lengthOfReplication = 100.0
        m.numberOfReplications = 2
        val error = runCatching { m.simulate() }.exceptionOrNull()
        assertNull(error, "the run must not fail: ${error?.message}")
        assertEquals(1, m.currentReplicationNumber, "no further replication runs")
        assertTrue(m.stoppedByCondition, "the experiment ends with the stopping condition met")
        assertEquals(6.0, ticker.lastTickTime, 0.0, "the last tick is at 1 + 2 + 3")
        assertEquals(1.0, ticker.count.acrossReplicationStatistic.count, 0.0, "one replication's statistics")
        assertEquals(3.0, ticker.count.acrossReplicationStatistic.average, 0.0, "three ticks were counted")
    }
}
