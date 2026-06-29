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
import ksl.examples.general.models.station.StationNetworkMultiClass
import ksl.simulation.Model

/**
 * Example 10 — multi-class station network (8G.1). Two customer classes (`TypeA`, `TypeB`) arrive
 * and share one server. Station entities are plain `QObject`s, but each carries a `qObjectType` and
 * the network resolves it to a class name, which `EnteredNetwork` now reports — so the renderer can
 * style the two classes differently (`TypeA` blue, `TypeB` orange) instead of with one generic
 * style. Demonstrates that station entities can be typed without touching `QObject` (Option A/3).
 */
object Example10MultiClassStation {

    private const val NET = "MC:Net"

    fun buildModel(): Model {
        val m = Model("MultiClassStationModel")
        StationNetworkMultiClass(m, "MC")
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        return m
    }

    fun buildLayout(model: Model): AnimationLayout = model.animation {
        title = "Multi-Class Station Network (8G.1 typed station entities)"
        size(760.0, 360.0)
        clock(24.0, 32.0)

        // The two QObject classes get distinct styling, keyed by the class name the network resolves.
        objectClass("TypeA") { color = "#1f77b4"; size = 13.0 }
        objectClass("TypeB") { color = "#ff7f0e"; size = 13.0 }

        line(120.0 to 110.0, 380.0 to 180.0, color = "#cccccc")
        line(120.0 to 250.0, 380.0 to 180.0, color = "#cccccc")
        line(440.0 to 180.0, 660.0 to 180.0, color = "#cccccc")
        station("$NET:ArrivalsA", 120.0, 110.0, label = "Arrivals A")
        station("$NET:ArrivalsB", 120.0, 250.0, label = "Arrivals B")

        queue("$NET:Server:Q", 320.0, 180.0)
        resource("$NET:Server:R", 400.0, 180.0) { size = 30.0 }
        station("$NET:Server", 400.0, 180.0)

        station("$NET:Exit", 660.0, 180.0, label = "Exit")

        bar("$NET:NumInSystem", 80.0, 300.0) { width = 280.0; height = 20.0; maxValue = 12.0; label = "Number in system" }
        value("$NET:TypeA:NumCompleted", 420.0, 290.0, label = "Type A done", decimals = 0)
        value("$NET:TypeB:NumCompleted", 420.0, 315.0, label = "Type B done", decimals = 0)
    }

}
