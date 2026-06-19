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

package ksl.service.capability.run

import ksl.app.config.RVParameterOverride
import ksl.controls.ControlData
import ksl.controls.ModelControlsExport
import ksl.simulation.ModelDescriptor
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 * Translates an agent's flat `input key → value` map — exactly the keys
 * `describe_model`'s input schema advertises — into the run substrate's two
 * override forms, routing each key by the model descriptor:
 *
 * - a key matching a numeric control's `keyName` becomes a control override
 *   (only `keyName` + `value` matter: `Controls.importAll` looks up by key and
 *   sets the value, ignoring all other `ControlData` fields);
 * - a key matching a random-variable parameter (`rvName.paramName`) becomes an
 *   `RVParameterOverride`;
 * - an unrecognized key is an error, so an agent that mistypes a control gets a
 *   clear message rather than a silently-ignored input.
 *
 * v1 handles the numeric surface (numeric controls + RV parameters), which is
 * what the catalog-led input schema nominates in practice. String/JSON control
 * overrides are a later addition (they need non-numeric values).
 */
object RunInputs {

    /** The bound override forms ready for [RunService.submitSingle]. */
    data class Bound(
        val controlOverrides: ModelControlsExport,
        val rvOverrides: List<RVParameterOverride>,
    )

    fun bind(descriptor: ModelDescriptor, inputs: Map<String, Double>): Bound {
        if (inputs.isEmpty()) {
            return Bound(ModelControlsExport(modelName = descriptor.modelName), emptyList())
        }
        val controlByKey: Map<String, ControlData> =
            descriptor.controls.numericControls.associateBy { it.keyName }
        val rvByKey = descriptor.rvParameterData.associateBy {
            "${it.rvName}${RVParameterSetter.rvParamConCatChar}${it.paramName}"
        }

        val controls = mutableListOf<ControlData>()
        val rvOverrides = mutableListOf<RVParameterOverride>()
        for ((key, value) in inputs) {
            val control = controlByKey[key]
            val rv = rvByKey[key]
            when {
                control != null -> controls.add(control.copy(value = value))
                rv != null -> rvOverrides.add(RVParameterOverride(rv.rvName, rv.paramName, value))
                else -> throw IllegalArgumentException(
                    "unknown input '$key'; valid numeric inputs are " +
                        (controlByKey.keys + rvByKey.keys).sorted(),
                )
            }
        }
        return Bound(
            controlOverrides = ModelControlsExport(modelName = descriptor.modelName, numericControls = controls),
            rvOverrides = rvOverrides,
        )
    }
}
