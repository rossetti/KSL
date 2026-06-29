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
import ksl.examples.general.models.station.StationNetworkTandemQueue
import ksl.simulation.Model

/**
 * Example 7 — station (flow-network) package. A two-station tandem queue built with `StationNetwork`.
 * Validates the station paradigm and surfaces its gaps. Element names are network-qualified
 * (network "TQ:Net"): stations `TQ:Net:Station1`, internal SResource `…:R`, queue `…:Q`.
 *
 * What renders: the static station markers, the per-station queues (dots), the SResource state
 * (box color), and the network response bars. What does **not**: the entities flowing **between**
 * stations — station entities are plain `QObject`s with no `EntityCreated`/type and the
 * `StationEntered/Exited` events aren't consumed by the renderer yet (see plan 8G). So this is a
 * structural/queue/resource animation, not entity flow.
 */
object Example07StationTandem {

    private const val NET = "TQ:Net"

    fun buildModel(): Model {
        val m = Model("StationTandemModel")
        StationNetworkTandemQueue(m, "TQ")
        m.numberOfReplications = 1
        m.lengthOfReplication = 300.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Station Network Tandem Queue (station demo)"
        size(760.0, 360.0)
        clock(24.0, 32.0)

        objectClass("QObject") { color = "#1f77b4"; size = 12.0 }

        // Flow left to right: Arrivals -> Station1 -> Station2 -> Exit.
        line(120.0 to 180.0, 660.0 to 180.0, color = "#bbbbbb")
        station("$NET:Arrivals", 120.0, 180.0, label = "Arrivals")

        queue("$NET:Station1:Q", 260.0, 180.0)
        resource("$NET:Station1:R", 320.0, 180.0) { size = 30.0 }
        // Station markers (named to match StationEntered) so entities at the station render here (8G.2).
        station("$NET:Station1", 320.0, 180.0)

        queue("$NET:Station2:Q", 460.0, 180.0)
        resource("$NET:Station2:R", 520.0, 180.0) { size = 30.0 }
        station("$NET:Station2", 520.0, 180.0)

        station("$NET:Exit", 660.0, 180.0, label = "Exit")

        bar("$NET:NumInSystem", 80.0, 300.0) { width = 280.0; height = 20.0; maxValue = 12.0; label = "Number in system" }
        plot("$NET:NumInSystem", 420.0, 260.0) { width = 280.0; height = 80.0; label = "WIP over time" }
    }

}
