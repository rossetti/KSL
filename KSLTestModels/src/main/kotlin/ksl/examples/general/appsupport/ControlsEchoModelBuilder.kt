/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.appsupport

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.controls.KSLJsonControl
import ksl.controls.KSLStringControl
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement

/**
 * A tiny, deterministic model element exercising all three control families —
 * numeric ([KSLControl]), string ([KSLStringControl]), and JSON ([KSLJsonControl]) —
 * with a single [Response] that echoes a function of them. Because the echo depends
 * on every family, a test can prove an override of each actually reached the engine
 * (not merely that the run completed).
 *
 * The echo, recorded once per replication, is
 * `weights.sum() (+|-) offset` with the sign chosen by `mode`, so the defaults yield
 * `1.0` and, e.g., `weights=[2,3], mode=SUB, offset=2` yields `3.0` — a value that
 * differs from what any single un-applied override would produce.
 */
class ControlsEcho(parent: ModelElement, name: String) : ModelElement(parent, name) {

    @set:KSLControl(controlType = ControlType.DOUBLE, comment = "additive/subtractive offset applied to the weight sum")
    var offset: Double = 0.0

    @set:KSLStringControl(allowedValues = ["ADD", "SUB"], comment = "how the offset combines with the weight sum")
    var mode: String = "ADD"

    @set:KSLJsonControl(comment = "weights whose sum forms the echo base")
    var weights: List<Double> = listOf(1.0)

    private val myResult = Response(this, "result")
    val result: ResponseCIfc get() = myResult

    override fun replicationEnded() {
        val base = weights.sum()
        myResult.value = if (mode == "SUB") base - offset else base + offset
    }
}

/**
 * Named [ModelBuilderIfc] for [ControlsEcho]; the default modelId (class name minus
 * the `ModelBuilder` suffix) is `ControlsEcho`. It nominates no catalog, so
 * `describe_model` advertises the full numeric/string/JSON control surface — exactly
 * the fixture the flattened-run string/JSON override tests need.
 */
class ControlsEchoModelBuilder : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?,
    ): Model {
        // The child element name must differ from the Model's own name (a same-named
        // child collides as a duplicate ModelElement at the root).
        val model = Model("ControlsEcho", autoCSVReports = false)
        ControlsEcho(model, "echo")
        // The echo is deterministic, so a few identical replications give a finite
        // across-replication statistic (zero variance) rather than a single-sample NaN.
        model.numberOfReplications = 3
        model.lengthOfReplication = 1.0
        return model
    }
}
