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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import ksl.app.config.RVParameterOverride
import ksl.controls.ControlData
import ksl.controls.JsonControlData
import ksl.controls.ModelControlsExport
import ksl.controls.StringControlData
import ksl.simulation.ModelDescriptor
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 * Translates an agent's flat `input key -> value` map — exactly the keys
 * `describe_model`'s input schema advertises — into the run substrate's override
 * forms, routing each key by the model descriptor:
 *
 * - a key matching a numeric control's `keyName` becomes a numeric control override
 *   (its value must be a JSON number);
 * - a key matching a string control's `keyName` becomes a string control override
 *   (its value is taken as text);
 * - a key matching a JSON control's `keyName` becomes a JSON control override (its
 *   value is a JSON-encoded string, or any JSON element, carried through verbatim);
 * - a key matching a random-variable parameter (`rvName.paramName`) becomes an
 *   [RVParameterOverride] (numeric);
 * - an unrecognized key is an error, so an agent that mistypes an input gets a clear
 *   message rather than a silently-ignored value.
 *
 * Only a key's `keyName` and value are load-bearing: `Controls.importAll` looks each
 * key up in the model and applies the value, ignoring the other DTO fields. Routing
 * is by key — a `keyName` is unique across the three control families — so a value's
 * JSON kind never selects the family; it only has to be well-typed for that family.
 */
object RunInputs {

    /** The bound override forms ready for [RunService.submitSingle]. */
    data class Bound(
        val controlOverrides: ModelControlsExport,
        val rvOverrides: List<RVParameterOverride>,
    )

    /**
     * Routes `inputs` (each value kept as a [JsonElement] so its kind survives) to
     * numeric / string / JSON control overrides and RV-parameter overrides against
     * `descriptor`. Throws `IllegalArgumentException` on an unknown key, or on a value
     * whose kind is wrong for its control family (e.g. a non-numeric numeric control).
     */
    fun bind(descriptor: ModelDescriptor, inputs: Map<String, JsonElement>): Bound {
        if (inputs.isEmpty()) {
            return Bound(ModelControlsExport(modelName = descriptor.modelName), emptyList())
        }
        val numericByKey: Map<String, ControlData> =
            descriptor.controls.numericControls.associateBy { it.keyName }
        val stringByKey: Map<String, StringControlData> =
            descriptor.controls.stringControls.associateBy { it.keyName }
        val jsonByKey: Map<String, JsonControlData> =
            descriptor.controls.jsonControls.associateBy { it.keyName }
        val rvByKey = descriptor.rvParameterData.associateBy {
            "${it.rvName}${RVParameterSetter.rvParamConCatChar}${it.paramName}"
        }

        val numericControls = mutableListOf<ControlData>()
        val stringControls = mutableListOf<StringControlData>()
        val jsonControls = mutableListOf<JsonControlData>()
        val rvOverrides = mutableListOf<RVParameterOverride>()
        for ((key, value) in inputs) {
            val numeric = numericByKey[key]
            val string = stringByKey[key]
            val jsonControl = jsonByKey[key]
            val rv = rvByKey[key]
            when {
                numeric != null -> numericControls.add(numeric.copy(value = numericValue(key, value)))
                string != null -> stringControls.add(string.copy(value = stringValue(value)))
                jsonControl != null -> jsonControls.add(jsonControl.copy(jsonValue = jsonText(value)))
                rv != null -> rvOverrides.add(RVParameterOverride(rv.rvName, rv.paramName, numericValue(key, value)))
                else -> throw IllegalArgumentException(
                    "unknown input '$key'; valid inputs are " +
                        (numericByKey.keys + stringByKey.keys + jsonByKey.keys + rvByKey.keys).sorted(),
                )
            }
        }
        return Bound(
            controlOverrides = ModelControlsExport(
                modelName = descriptor.modelName,
                numericControls = numericControls,
                stringControls = stringControls,
                jsonControls = jsonControls,
            ),
            rvOverrides = rvOverrides,
        )
    }

    /** A numeric input's value: a JSON number (or a numeric string); errors otherwise. */
    private fun numericValue(key: String, value: JsonElement): Double =
        (value as? JsonPrimitive)?.doubleOrNull
            ?: throw IllegalArgumentException("input '$key' must be a number")

    /** A string input's value: a JSON string's contents, or a scalar's literal text. */
    private fun stringValue(value: JsonElement): String =
        (value as? JsonPrimitive)?.content ?: value.toString()

    /**
     * A JSON input's value as JSON text: a JSON string is already the encoded value,
     * so its contents pass through; any other element is serialized to compact JSON.
     * The target `JsonControlIfc` validates and parses the text when it is applied.
     */
    private fun jsonText(value: JsonElement): String {
        val primitive = value as? JsonPrimitive
        return if (primitive != null && primitive.isString) primitive.content else value.toString()
    }
}
