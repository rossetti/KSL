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
import ksl.examples.book.chapter8.TandemQueueWithConveyors
import ksl.simulation.Model

/**
 * Example 8 — conveyors. A tandem line where parts **ride a conveyor** between an entry, two work
 * stations, and an exit (segments Enter→Station1→Station2→Exit). Validates the conveyor paradigm.
 *
 * Conveyor parts ARE `ProcessModel.Entity`s, so they get `EntityCreated` (+type "Part") — better
 * than station QObjects. But the conveyor **rides do not render** today: `ConveyorRideStarted`
 * carries only `fromLocation`/`toLocation` **names** (no coordinates, no duration), there is no
 * conveyor layout element (belt geometry), and the renderer consumes no `Conveyor*` events (plan
 * 8G). So what renders here is the worker resources, their queues (waiting parts as dots), and the
 * WIP responses; the belt itself is drawn only as a static authored path.
 */
object Example08ConveyorTandem {

    fun buildModel(): Model {
        val m = Model("ConveyorTandemModel")
        TandemQueueWithConveyors(m, name = "ConveyorTQ")
        m.numberOfReplications = 1
        m.lengthOfReplication = 120.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Tandem Queue with Conveyors (conveyor demo)"
        size(760.0, 360.0)
        clock(24.0, 32.0)

        objectClass("Part") { color = "#1f77b4"; size = 12.0 }

        // The conveyor belt path; the named locations are placed as stations so the renderer can map
        // a riding item's cell index to a position along the belt (8G.6).
        path("Conveyor", 100.0 to 120.0, 320.0 to 120.0, 520.0 to 120.0, 680.0 to 120.0)
        station("Enter", 100.0, 120.0, label = "Enter")
        station("Station1", 320.0, 120.0)
        station("Station2", 520.0, 120.0)
        station("Exit", 680.0, 120.0, label = "Exit")

        // Workers (process-view resources) with their queues.
        queue("worker1:Q", 300.0, 200.0)
        resource("worker1", 320.0, 200.0) { size = 30.0 }
        queue("worker2:Q", 500.0, 200.0)
        resource("worker2", 520.0, 200.0) { size = 30.0 }

        bar("ConveyorTQ:NumInSystem", 80.0, 300.0) { width = 280.0; height = 20.0; maxValue = 12.0; label = "Number in system" }
        plot("ConveyorTQ:NumInSystem", 420.0, 260.0) { width = 280.0; height = 80.0; label = "WIP over time" }
    }

}
