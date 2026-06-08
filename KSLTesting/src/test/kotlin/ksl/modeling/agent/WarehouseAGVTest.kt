/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.agent

import ksl.examples.general.agent.WarehouseAGVExample
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 *  Phase 4.6 smoke tests for [WarehouseAGVExample]: end-to-end
 *  Contract-Net bidding on a grid with rack obstacles, plus battery /
 *  charging follow-through.
 */
class WarehouseAGVTest {

    @Test
    fun warehouseAGVExampleCompletesTasksAndCharges() {
        val model = Model("WarehouseAGVSmokeTest")
        val sys = WarehouseAGVExample(model, "warehouse")
        // Faster task arrivals to force some queueing + battery turnover.
        sys.taskArrivalRV = ExponentialRV(2.5, streamNum = 7, streamProvider = model.streamProvider)
        // Per-state TWResponses on every AGV so we can check utilization.
        for (agv in sys.agvs) agv.collectPerformance()
        model.lengthOfReplication = 500.0
        model.numberOfReplications = 1
        model.simulate()

        // Tasks should have been completed end-to-end.
        val completed = sys.numTasksCompleted.acrossReplicationStatistic.average
        assertTrue(
            completed > 20.0,
            "expected at least 20 tasks completed; got $completed",
        )

        // The dispatcher should have broadcast at least as many CFPs.
        val broadcasts = sys.numCFPsBroadcast.acrossReplicationStatistic.average
        assertTrue(
            broadcasts >= completed,
            "CFP count ($broadcasts) should be at least as large as completed count ($completed)",
        )

        // Average task time should be positive and bounded. A task at
        // velocity 2.0 traverses at most ~60 cells (3-leg trip), giving
        // ~30 time units, plus 3.0 service. Allow generous queueing.
        val avgTaskTime = sys.taskCompletionTime.acrossReplicationStatistic.average
        assertTrue(avgTaskTime > 0.0, "avg task time should be positive; got $avgTaskTime")
        assertTrue(
            avgTaskTime < 500.0,
            "avg task time should be bounded; got $avgTaskTime",
        )

        // At least some charging events should have occurred — energyPerCell
        // is set such that an AGV expends ~0.4 of its battery on a typical
        // round trip, so within 500 time units we expect multiple charges.
        val charges = sys.numChargingEvents.acrossReplicationStatistic.average
        assertTrue(
            charges >= 1.0,
            "expected at least one charging event; got $charges",
        )

        // All AGVs should still be tracked in the projection (they never
        // leave the warehouse context).
        for (agv in sys.agvs) {
            assertTrue(
                sys.space.positionOf(agv) != null,
                "AGV ${agv.name} should still be tracked in the projection",
            )
        }
    }
}
