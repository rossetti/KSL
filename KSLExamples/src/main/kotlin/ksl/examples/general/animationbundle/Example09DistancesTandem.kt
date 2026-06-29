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
import ksl.examples.book.chapter8.TandemQueueWithUnconstrainedMovement
import ksl.simulation.Model

/**
 * Example 9 — movement on a `DistancesModel` (8H.3). Customers walk Enter → Station1 → Station2 →
 * Exit, but the model's spatial model is a [ksl.modeling.spatial.DistancesModel]: it has named
 * locations and pairwise distances but **no coordinates**, so its `MoveStarted` events carry `NaN`
 * positions. The author supplies coordinates once by placing the **named locations** as `station()`
 * markers; the renderer resolves each move's `fromLocationName`/`toLocationName` against them and
 * interpolates — so a coordinate-free model animates without rewriting the distance model.
 */
object Example09DistancesTandem {

    private const val NAME = "WalkTQ"

    fun buildModel(): Model {
        val m = Model("DistancesTandemModel")
        TandemQueueWithUnconstrainedMovement(m, NAME)
        m.numberOfReplications = 1
        m.lengthOfReplication = 80.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Tandem Queue on a DistancesModel (8H.3 name resolution)"
        size(760.0, 320.0)
        clock(24.0, 32.0)

        objectClass("Customer") { color = "#1f77b4"; size = 14.0 }

        // The model's DistancesModel has no coordinates; we place its named locations here, and the
        // renderer resolves moves (which carry only location names + NaN coords) against these.
        // x-spacing roughly honors the model's 60:30:60 distances.
        path("walk", 80.0 to 160.0, 320.0 to 160.0, 440.0 to 160.0, 680.0 to 160.0)
        station("Enter", 80.0, 160.0, label = "Enter")
        station("Station1", 320.0, 160.0)
        station("Station2", 440.0, 160.0)
        station("Exit", 680.0, 160.0, label = "Exit")

        // Workers co-located with their stations (entities in service render inside the resource).
        resource("worker1", 320.0, 160.0) { size = 28.0 }
        queue("worker1:Q", 320.0, 210.0)
        resource("worker2", 440.0, 160.0) { size = 28.0 }
        queue("worker2:Q", 440.0, 210.0)

        bar("$NAME:NumInSystem", 80.0, 270.0) { width = 280.0; height = 20.0; maxValue = 12.0; label = "Number in system" }
    }

}
