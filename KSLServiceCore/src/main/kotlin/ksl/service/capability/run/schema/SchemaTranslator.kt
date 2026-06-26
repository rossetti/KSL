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

package ksl.service.capability.run.schema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import ksl.simulation.ModelDescriptor
import ksl.simulation.NominatedInputKind
import ksl.utilities.random.rvariable.parameters.RVParameterData
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 * Translates a [ModelDescriptor] into a JSON Schema for a model's run arguments
 * and its result shape — the bridge that makes a bundled model an agent-callable
 * tool (strategic plan §5.4, realizing Phase 6 §9.2).
 *
 * The translation is **structural and per-descriptor**, not per-model: one
 * implementation serves every bundle the registry knows.
 *
 * When the model ships a nominated [ksl.simulation.ModelCatalog], the schema is
 * **catalog-led**: only the nominated inputs appear, in the author's priority
 * order, with display names / units as annotations — turning a forty-control
 * model into a clean handful of tool arguments. Absent a catalog, the *full*
 * control + RV-parameter surface is emitted, so the translator is always
 * degradation-safe.
 */
object SchemaTranslator {

    /** JSON Schema describing the model's settable run arguments. */
    fun inputSchema(descriptor: ModelDescriptor): JsonObject {
        val numeric = descriptor.controls.numericControls.associateBy { it.keyName }
        val strings = descriptor.controls.stringControls.associateBy { it.keyName }
        val jsons = descriptor.controls.jsonControls.associateBy { it.keyName }
        val rvByKey = descriptor.rvParameterData.associateBy { rvKey(it) }

        val nominated = descriptor.catalog?.nominatedInputs.orEmpty()
        val properties: Map<String, JsonObject> = if (nominated.isNotEmpty()) {
            // Catalog-led: nominated inputs only, in priority order.
            buildMap {
                for (nom in nominated) {
                    val title = nom.displayName ?: nom.key
                    val prop = when (nom.kind) {
                        NominatedInputKind.NUMERIC_CONTROL -> numeric[nom.key]
                            ?.let { numberProp(title, nom.description, nom.unit, it.lowerBound, it.upperBound) }
                        NominatedInputKind.STRING_CONTROL -> strings[nom.key]
                            ?.let { stringProp(title, nom.description, nom.unit, it.allowedValues) }
                        NominatedInputKind.JSON_CONTROL -> jsons[nom.key]
                            ?.let { jsonProp(title, nom.description, nom.unit, it.typeHint) }
                        NominatedInputKind.RV_PARAMETER -> rvByKey[nom.key]
                            ?.let { numberProp(title, nom.description, nom.unit, null, null) }
                    } ?: numberProp(title, nom.description, nom.unit, null, null) // key unresolved: emit a plain number
                    put(nom.key, prop)
                }
            }
        } else {
            // Full surface: every numeric / string / JSON control and RV parameter.
            buildMap {
                descriptor.controls.numericControls.forEach {
                    put(it.keyName, numberProp(it.keyName, it.comment.ifBlank { null }, null, it.lowerBound, it.upperBound))
                }
                descriptor.controls.stringControls.forEach {
                    put(it.keyName, stringProp(it.keyName, it.comment.ifBlank { null }, null, it.allowedValues))
                }
                descriptor.controls.jsonControls.forEach {
                    put(it.keyName, jsonProp(it.keyName, it.comment.ifBlank { null }, null, it.typeHint))
                }
                descriptor.rvParameterData.forEach {
                    put(rvKey(it), numberProp(rvKey(it), null, null, null, null))
                }
            }
        }

        return buildJsonObject {
            put("type", "object")
            put("title", descriptor.modelName)
            putJsonObject("properties") { properties.forEach { (k, v) -> put(k, v) } }
        }
    }

    /** JSON Schema describing the model's result shape (nominated outputs first,
     *  else all response names). */
    fun outputSchema(descriptor: ModelDescriptor): JsonObject {
        val nominated = descriptor.catalog?.nominatedOutputs.orEmpty()
        val properties: Map<String, JsonObject> = if (nominated.isNotEmpty()) {
            buildMap {
                nominated.forEach { put(it.name, numberProp(it.displayName ?: it.name, it.description, it.unit, null, null)) }
            }
        } else {
            buildMap { descriptor.responseNames.forEach { put(it, numberProp(it, null, null, null, null)) } }
        }
        return buildJsonObject {
            put("type", "object")
            put("title", "${descriptor.modelName} outputs")
            putJsonObject("properties") { properties.forEach { (k, v) -> put(k, v) } }
        }
    }

    private fun rvKey(d: RVParameterData): String =
        "${d.rvName}${RVParameterSetter.rvParamConCatChar}${d.paramName}"

    private fun numberProp(title: String, description: String?, unit: String?, lower: Double?, upper: Double?): JsonObject =
        buildJsonObject {
            put("type", "number")
            put("title", title)
            descriptionLine(description, unit)?.let { put("description", it) }
            if (lower != null && lower.isFinite()) put("minimum", lower)
            if (upper != null && upper.isFinite()) put("maximum", upper)
        }

    private fun stringProp(title: String, description: String?, unit: String?, allowed: List<String>): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("title", title)
            descriptionLine(description, unit)?.let { put("description", it) }
            if (allowed.isNotEmpty()) putJsonArray("enum") { allowed.forEach { add(it) } }
        }

    private fun jsonProp(title: String, description: String?, unit: String?, typeHint: String): JsonObject =
        buildJsonObject {
            put("type", "string")
            put("title", title)
            val note = listOfNotNull(description?.takeIf { it.isNotBlank() }, "JSON-encoded ($typeHint)").joinToString(" ")
            put("description", descriptionLine(note, unit) ?: note)
        }

    private fun descriptionLine(description: String?, unit: String?): String? {
        val parts = listOfNotNull(
            description?.takeIf { it.isNotBlank() },
            unit?.takeIf { it.isNotBlank() }?.let { "unit: $it" },
        )
        return parts.joinToString(" ").ifBlank { null }
    }
}
