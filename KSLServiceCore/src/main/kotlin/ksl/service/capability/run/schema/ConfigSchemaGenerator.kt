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

@file:OptIn(ExperimentalSerializationApi::class)

package ksl.service.capability.run.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.peanuuutz.tomlkt.TomlComment

/**
 * Generates a per-field JSON Schema from a configuration document's `@Serializable`
 * descriptor, so the MCP document tools can advertise the real shape of a
 * `RunConfiguration` / `ExperimentConfiguration` / `OptimizationRunConfiguration` /
 * `FitConfiguration` — including the sealed-variant `type` discriminators (design kinds,
 * solver families, cooling schedules) an agent otherwise cannot discover from the opaque
 * `object | string` blob.
 *
 * ## Containment of the experimental API
 * Enumerating a sealed type's concrete variants uses kotlinx-serialization descriptor
 * APIs marked `@ExperimentalSerializationApi`. All such usage is confined to this object
 * (file-level opt-in), so the opt-in — and any churn from a future kotlinx bump (pinned
 * at 1.9.0) — is localized to this one file.
 *
 * ## What it captures, and what it does not
 * It is **structural**: field names/types, sealed `oneOf` variants with their `@SerialName`
 * discriminators, required-ness (from `isElementOptional`), and each field's `@TomlComment`
 * as a `description`. It does **not** express Kotlin `init`-block invariants (finite bounds,
 * ordering constraints, unique names) nor default *values* (kotlinx exposes optionality,
 * not the default). So a schema-valid document can still fail to decode — the `validate_*`
 * tools remain the real gate; this is shape guidance.
 */
object ConfigSchemaGenerator {

    /** Depth ceiling: config types are far shallower, so this only bounds pathological nesting. */
    private const val MAX_DEPTH = 12

    /** The JSON Schema (a `{type:object, …}` node) for the document described by [descriptor]. */
    fun schemaFor(descriptor: SerialDescriptor): JsonObject = build(descriptor, emptySet(), 0)

    private fun build(descriptor: SerialDescriptor, ancestors: Set<String>, depth: Int): JsonObject {
        val schema = buildKind(descriptor, ancestors, depth)
        // A nullable field/element permits JSON null on the wire, so advertise it — otherwise a
        // schema-validating client rejects the null the server legitimately emits: optional fields
        // left unset, and (because Phase-1's ControlData serializers carry a nullable descriptor)
        // the ±∞ bounds/value the MCP transport sanitizes to null. Without this, run_template's own
        // output fails re-ingestion at a strict client before it ever reaches the decode fix.
        return if (descriptor.isNullable) asNullable(schema) else schema
    }

    private fun buildKind(descriptor: SerialDescriptor, ancestors: Set<String>, depth: Int): JsonObject {
        // Cycle / depth guard: a genuinely self-referential named type, or pathological nesting,
        // collapses to an opaque object rather than recursing without bound. Only *named
        // structural* types (class / object / sealed) enter `ancestors`; collections deliberately
        // do NOT. Every Kotlin `List<T>` shares one serialName ("kotlin.collections.ArrayList")
        // and every `Map` likewise, so tracking a collection container would falsely flag a list
        // nested inside another list (scenarios[].controlOverrides.numericControls, factors[].levels)
        // as a cycle and truncate it to an opaque object — which strict MCP clients then reject.
        // A real cycle can only close through a named type, and those are still tracked below.
        if (depth > MAX_DEPTH || descriptor.serialName in ancestors) return buildJsonObject { put("type", "object") }
        return when (val kind = descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> objectSchema(descriptor, ancestors + descriptor.serialName, depth)
            PolymorphicKind.SEALED -> sealedSchema(descriptor, ancestors + descriptor.serialName, depth)
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", build(descriptor.getElementDescriptor(0), ancestors, depth + 1))
            }
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", build(descriptor.getElementDescriptor(1), ancestors, depth + 1))
            }
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { for (i in 0 until descriptor.elementsCount) add(descriptor.getElementName(i)) }
            }
            is PrimitiveKind -> primitiveSchema(kind)
            else -> buildJsonObject { put("type", "object") } // CONTEXTUAL / open polymorphic: opaque
        }
    }

    /**
     * Widen a built schema so it also permits JSON `null`. A typed node grows its `type` into a
     * `[type, "null"]` union; a sealed `oneOf` gains a `{type:"null"}` variant; anything else falls
     * back to `anyOf`. Zod and other JSON-Schema validators read all three as "nullable".
     */
    private fun asNullable(schema: JsonObject): JsonObject {
        val type = schema["type"]
        return when {
            // Already carries a null option — leave as-is (defensive).
            type is JsonArray && type.any { it.jsonPrimitive.contentOrNull == "null" } -> schema
            // Sealed oneOf: the discriminated union simply gains an explicit null variant.
            "oneOf" in schema -> JsonObject(
                schema + ("oneOf" to JsonArray((schema["oneOf"] as JsonArray) + buildJsonObject { put("type", "null") })),
            )
            // A bare scalar (single string `type`, no `properties`/`items`) widens to the compact
            // `[T, "null"]` union, which strict clients read correctly for scalars.
            type is JsonPrimitive && type.isString && "properties" !in schema && "items" !in schema ->
                JsonObject(schema + ("type" to JsonArray(listOf(type, JsonPrimitive("null")))))
            // A structural node (object with `properties`, or array with `items`) wraps in
            // `anyOf: [schema, {type:null}]`. A json-schema→Zod converter mishandles an
            // `["object","null"]` union that still carries `properties`/`required` — it keeps
            // enforcing the object and drops the null branch — but reads `anyOf` cleanly.
            else -> buildJsonObject { putJsonArray("anyOf") { add(schema); add(buildJsonObject { put("type", "null") }) } }
        }
    }

    private fun objectSchema(descriptor: SerialDescriptor, path: Set<String>, depth: Int): JsonObject = buildJsonObject {
        put("type", "object")
        val required = mutableListOf<String>()
        putJsonObject("properties") {
            for (i in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(i)
                val fieldSchema = build(descriptor.getElementDescriptor(i), path, depth + 1)
                put(name, withDescription(fieldSchema, tomlComment(descriptor.getElementAnnotations(i))))
                if (!descriptor.isElementOptional(i)) required += name
            }
        }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }

    /**
     * A sealed type → `{oneOf:[…variants…]}`. A sealed descriptor is `{type, value}`; the
     * concrete variants are the elements of `value`, keyed by their `@SerialName`.
     */
    private fun sealedSchema(descriptor: SerialDescriptor, path: Set<String>, depth: Int): JsonObject = buildJsonObject {
        val discriminator = descriptor.getElementName(0) // kotlinx default is "type"
        val valueDescriptor = descriptor.getElementDescriptor(1)
        putJsonArray("oneOf") {
            for (i in 0 until valueDescriptor.elementsCount) {
                add(variantSchema(discriminator, valueDescriptor.getElementName(i), valueDescriptor.getElementDescriptor(i), path, depth))
            }
        }
    }

    /** One sealed variant: its object schema with the discriminator property (enum = its @SerialName) injected first. */
    private fun variantSchema(
        discriminator: String,
        serialName: String,
        variantDescriptor: SerialDescriptor,
        path: Set<String>,
        depth: Int,
    ): JsonObject {
        val obj = build(variantDescriptor, path, depth + 1)
        val variantProps = obj["properties"] as? JsonObject
        val variantRequired = (obj["required"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject(discriminator) { putJsonArray("enum") { add(serialName) } }
                variantProps?.forEach { (k, v) -> put(k, v) }
            }
            putJsonArray("required") { add(discriminator); variantRequired.forEach { add(it) } }
        }
    }

    private fun primitiveSchema(kind: PrimitiveKind): JsonObject = buildJsonObject {
        when (kind) {
            PrimitiveKind.BOOLEAN -> put("type", "boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> put("type", "integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> put("type", "number")
            PrimitiveKind.CHAR, PrimitiveKind.STRING -> put("type", "string")
        }
    }

    private fun tomlComment(annotations: List<Annotation>): String? =
        annotations.filterIsInstance<TomlComment>().firstOrNull()?.text?.takeIf { it.isNotBlank() }

    private fun withDescription(schema: JsonObject, description: String?): JsonObject =
        if (description == null) schema else JsonObject(schema + ("description" to JsonPrimitive(description)))
}
