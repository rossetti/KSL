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

import ksl.examples.general.agent.DroneDeliveryExample
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 *  Phase 6.6 smoke test for [DroneDeliveryExample]. Verifies the 3D
 *  agent stack end-to-end: drones receive orders, plan paths around
 *  no-fly zones via `VoxelGraph.shortestPath`, fly along 3D
 *  waypoints via `travelThrough3D`, recharge at the depot when
 *  battery is low, and stay tracked in the `ContinuousVolume`.
 */
class DroneDeliveryTest {

    @Test
    fun dronesDeliverPackagesAndRecharge() {
        val model = Model("DroneDeliverySmokeTest")
        val sys = DroneDeliveryExample(model, "delivery")
        // Tighter timing so the test runs in CI without slowing things
        // down: orders every 10 time units instead of the default 20,
        // 600-unit replication.
        sys.orderArrivalRV = ExponentialRV(
            10.0, streamNum = 7, streamProvider = model.streamProvider,
        )
        model.lengthOfReplication = 600.0
        model.numberOfReplications = 1
        model.simulate()

        // With ~60 orders arriving and ~3 drones each taking ~60 time
        // units per round trip plus periodic recharges, we expect 15+
        // completed deliveries within the 600-unit window. Allow slack
        // for queueing.
        val delivered = sys.numDeliveries.acrossReplicationStatistic.average
        assertTrue(
            delivered > 10.0,
            "expected at least 10 deliveries; got $delivered",
        )

        // At least one recharge cycle should have happened — drones
        // can only carry a couple of deliveries before needing the
        // depot. With 3 drones over 10+ deliveries and battery
        // draining at 0.012 per voxel × ~60 voxels per trip ≈ 0.72
        // per round trip, drones will need to recharge.
        val charges = sys.numCharges.acrossReplicationStatistic.average
        assertTrue(
            charges >= 1.0,
            "expected at least one recharge cycle; got $charges",
        )

        // Delivery time should be positive and bounded. A trip is
        // roughly 50 voxels × 10 m / 15 m/s ≈ 33s out + 5s unload + 33s
        // back ≈ 70s. Allow queueing inflation to ~5×.
        val avgDeliveryTime = sys.deliveryTime.acrossReplicationStatistic.average
        assertTrue(avgDeliveryTime > 0.0, "delivery time should be positive; got $avgDeliveryTime")
        assertTrue(
            avgDeliveryTime < 500.0,
            "delivery time should stay bounded; got $avgDeliveryTime",
        )

        // All drones should still be tracked in the 3D projection at
        // the end of the run.
        for (drone in sys.drones) {
            assertTrue(
                sys.space.positionOf(drone) != null,
                "drone ${drone.name} should still be tracked in airspace",
            )
        }
    }
}
