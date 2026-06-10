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
 *
 * Also instantiates a small **synthetic-controls hierarchy**
 * (`SyntheticSubsystem` → `SyntheticDispatcher` / `SyntheticSensorBank`,
 * plus a peer `SyntheticBudgetHolder`) so the
 * `DefaultControlOverridesPanel` can demonstrate non-trivial
 * parent / element-path values across all three control families.
 * The synthetic elements have no effect on simulation execution.
 */
class MM1Builder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("MM1")
        GIGcQueue(model, numServers = 1, name = "MM1Queue")
        // Synthetic-controls hierarchy.  Two levels under `FleetSubsystem`
        // (Dispatcher, SensorBank) plus one direct child of the Model
        // (BudgetHolder) so the Control Overrides panel exercises both
        // the "nested" and "direct child of Model" cases for the
        // hierarchy fields (parentElementName / parentElementType /
        // elementPath).  None of these elements participate in the
        // simulation; their annotated properties exist solely to
        // populate the Control Overrides panel.
        val fleet = SyntheticSubsystem(model, "FleetSubsystem")
        SyntheticDispatcher(fleet, "Dispatcher")
        SyntheticSensorBank(fleet, "SensorBank")
        SyntheticBudgetHolder(model, "BudgetHolder")
        model.numberOfReplications = 30
        model.lengthOfReplication = 500.0
        model.lengthOfReplicationWarmUp = 50.0
        if (experimentRunParameters != null) {
            model.changeRunParameters(experimentRunParameters)
        }
//        println("HELLO from MM1Builder (stdout test)")
//        System.err.println("WORLD from MM1Builder (stderr test)")
        return model
    }
}

// ── Synthetic-controls fixture ─────────────────────────────────────────────
//
// All four classes below are file-top-level (not nested) because the
// controls reflection layer invokes property setters via
// `KCallable.call`, which cannot reach private nested classes' members.
// None of them have simulation-time behavior — they exist purely as
// demo fixtures for the Control Overrides panel.

/**
 * Container element that hosts the `Dispatcher` and `SensorBank`
 * demo elements.  Carries no controls of its own — its purpose is to
 * sit one level below the Model so its children's controls report
 * a non-empty `elementPath`.
 */
class SyntheticSubsystem(parent: ModelElement, name: String) : ModelElement(parent, name)

/**
 * Demo element under [SyntheticSubsystem] exposing the numeric-control
 * family (Integer / Double / Boolean) plus a string control with
 * `allowedValues`.  None of these properties affect simulation
 * execution.
 */
class SyntheticDispatcher(parent: ModelElement, name: String) : ModelElement(parent, name) {

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
}

/**
 * Demo element under [SyntheticSubsystem] exposing a free-form
 * string control and a JSON list-of-double control.  No simulation
 * effect.
 */
class SyntheticSensorBank(parent: ModelElement, name: String) : ModelElement(parent, name) {

    @set:KSLStringControl(comment = "Demo free-form string control")
    var operatorTag: String = "alpha"

    @set:KSLJsonControl(comment = "Demo JSON list-of-double control")
    var sensorWeights: List<Double> = listOf(0.5, 0.3, 0.2)
}

/**
 * Demo element attached directly to the Model (not nested under a
 * subsystem) so its single JSON control reports an empty
 * `elementPath` — the contrast case to the controls hanging off
 * [SyntheticDispatcher] / [SyntheticSensorBank].  No simulation effect.
 */
class SyntheticBudgetHolder(parent: ModelElement, name: String) : ModelElement(parent, name) {

    @set:KSLJsonControl(comment = "Demo JSON map-string-double control")
    var queueDepthBudget: Map<String, Double> =
        mapOf("urgent" to 5.0, "standard" to 20.0, "bulk" to 100.0)
}
