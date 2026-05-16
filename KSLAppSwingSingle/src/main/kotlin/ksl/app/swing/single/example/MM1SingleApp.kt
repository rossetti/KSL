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

package ksl.app.swing.single.example

import ksl.app.swing.single.kslSingleApp
import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.controls.KSLJsonControl
import ksl.controls.KSLStringControl
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement

/**
 * Minimal runnable example of `kslSingleApp(...)` framing an M/M/1
 * queue.  Run from IntelliJ via right-click `main` → Run.
 *
 * Lives in `KSLAppSwingSingle` (not `KSLExamples`) to preserve the
 * module dependency direction: `KSLAppSwingSingle` already
 * implementation-depends on `KSLExamples` for reference models;
 * adding the reverse direction would create a cycle.  Future
 * Phase-6D examples that bundle their own models can live under
 * `ksl.examples.general.appsupport` once Phase-6D ships a
 * non-cyclic dependency story.
 */
fun main() = kslSingleApp(appName = "M/M/1 Queue") {
    modelBuilder(MM1Builder())
}

/**
 * Named [ModelBuilderIfc] that constructs the example M/M/1
 * model — a single-server queue using
 * [ksl.examples.book.appendixD.GIGcQueue].  Named (not an inline
 * lambda) per workflow-single.md §2 OQ 2 so the same compiled
 * class is consumable from the Scenario app via
 * `ModelReference.ByJar(jarPath, "ksl.app.swing.single.example.MM1Builder")`.
 */
class MM1Builder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("MM1")
        GIGcQueue(model, numServers = 1, name = "MM1Queue")
        // SyntheticControls exposes one example per control family
        // (numeric int / double / boolean, string with allowedValues,
        // free-form string, JSON List<Double>, JSON Map<String, Double>)
        // so the Control Overrides panel renders all three families
        // when MM1SingleApp launches.  Has no simulation effect.
        SyntheticControls(model, name = "DemoControls")
        model.numberOfReplications = 30
        model.lengthOfReplication = 500.0
        model.lengthOfReplicationWarmUp = 50.0
        if (experimentRunParameters != null) {
            model.changeRunParameters(experimentRunParameters)
        }
        return model
    }
}

/**
 * Synthetic model element that exposes one annotated property per
 * control family.  Used by [MM1SingleApp] to give the
 * `DefaultControlOverridesPanel` something to render even though
 * the M/M/1 model itself only exposes one numeric control
 * (`MM1Queue.numServers`).  The properties have no effect on
 * simulation execution.
 */
class SyntheticControls(parent: ModelElement, name: String) : ModelElement(parent, name) {

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        name = "fleetSize",
        lowerBound = 1.0,
        upperBound = 50.0,
        comment = "Demo numeric (Integer) control"
    )
    var fleetSize: Int = 3

    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = 0.0,
        upperBound = 1.0,
        comment = "Demo numeric (Double) control"
    )
    var utilisationTarget: Double = 0.85

    @set:KSLControl(
        controlType = ControlType.BOOLEAN,
        comment = "Demo boolean control rendered as a Default · Yes · No tri-state"
    )
    var verbose: Boolean = false

    @set:KSLStringControl(
        allowedValues = ["ROUND_ROBIN", "RANDOM", "PRIORITY"],
        comment = "Demo string control with allowedValues (renders as a combo box)"
    )
    var dispatchPolicy: String = "ROUND_ROBIN"

    @set:KSLStringControl(comment = "Demo free-form string control")
    var operatorTag: String = "alpha"

    @set:KSLJsonControl(comment = "Demo JSON list-of-double control")
    var sensorWeights: List<Double> = listOf(0.5, 0.3, 0.2)

    @set:KSLJsonControl(comment = "Demo JSON map-string-double control")
    var queueDepthBudget: Map<String, Double> =
        mapOf("urgent" to 5.0, "standard" to 20.0, "bulk" to 100.0)
}
