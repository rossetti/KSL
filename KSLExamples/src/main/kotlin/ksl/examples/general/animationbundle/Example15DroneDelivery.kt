/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.animationbundle

import ksl.animation.AnimationLayout
import ksl.animation.animation
import ksl.animation.animationInventory
import ksl.examples.general.agent.DroneDeliveryExample
import ksl.simulation.Model

/**
 * Example 15 — 3D drone delivery, **flattened to 2D** (G8). Drones fly a 300×300×100 m airspace
 * (a `ContinuousVolume`), routing around no-fly zones via a `VoxelGraph`. The app is 2D, so the 3D
 * positions are projected onto the x–y plane (altitude/`z` is carried in the trace but ignored when
 * drawing). This exercises the G8 work: `ContinuousVolume` now emits `AgentPositionChanged` and a
 * flattened backdrop, so the model animates at all (previously it emitted nothing).
 *
 * The no-fly zones are 3D voxel obstacles in the `VoxelGraph`; they are flattened to a 2D footprint (a cell
 * is blocked if any layer there is blocked) and drawn via the grid-obstacle overlay — the voxel analog of
 * P5's grid-obstacle extraction. The demo shows the drones flying over the airspace, around the no-fly
 * footprints, with live delivery read-outs. The depot (where drones pick up) and the four delivery
 * (drop-off) points are marked, read from the model and projected to x–y. With the "Capture pulses"
 * overlay enabled (off by default), a transient ring flashes at a drop-off when a delivery completes there
 * (G-animated), so the otherwise-static markers come alive during Replay.
 */
object Example15DroneDelivery {

    // The model's world is gridCols(30) × voxelSize(10) = 300 m on each side (see DroneDeliveryExample).
    private const val WORLD = 300.0

    // The model instance built by buildModel, so buildLayout can read its depot/delivery points (they aren't
    // model elements, so they can't be reached from the Model). Valid for the sequential buildModel→buildLayout
    // flow the bundle/gallery use.
    private var built: DroneDeliveryExample? = null

    fun buildModel(): Model {
        val m = Model("DroneDeliveryModel")
        built = DroneDeliveryExample(m, "drones")
        m.numberOfReplications = 1
        m.lengthOfReplication = 400.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Drone Delivery (3D agent demo, flattened to 2D)"
        size(WORLD, WORLD + 40.0) // airspace footprint + a strip for read-outs
        clock(10.0, WORLD + 28.0)

        objectClass("Drone") { color = "#1f77b4"; size = 6.0 }
        continuousSpace("airspace", xMin = 0.0, xMax = WORLD, yMin = 0.0, yMax = WORLD)

        // No-fly zones: the model's 3D voxel obstacles, flattened to a 2D footprint (the voxel analog of P5).
        model.animationInventory().spaces.mapNotNull { it.geometry }.forEach { gridGeometry(it) }

        // The depot (drone pickup) and the delivery (drop-off) points, from the model — projected to x–y.
        built?.let { d ->
            val depot = d.voxelCenter(d.depot)
            station("Depot", depot.x, depot.y, label = "Depot (pickup)")
            d.deliveryPoints.forEachIndexed { i, v ->
                val c = d.voxelCenter(v)
                station("drop-$i", c.x, c.y, label = "Drop ${i + 1}")
            }
        }

        // Live delivery read-outs along the bottom strip.
        value("NumIdleDrones", 10.0, WORLD + 12.0, label = "Idle drones", decimals = 0)
        value("NumDeliveries", 110.0, WORLD + 12.0, label = "Deliveries", decimals = 0)
        value("NumCharges", 210.0, WORLD + 12.0, label = "Charges", decimals = 0)
    }

}
