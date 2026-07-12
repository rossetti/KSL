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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ksl.app.config.RunConfiguration
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.dist.config.FitConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves [ConfigSchemaGenerator] turns the config documents' `@Serializable` descriptors
 * into real per-field JSON Schemas — in particular that sealed hierarchies surface as
 * `oneOf` with a `type` discriminator enum of the `@SerialName`s (the thing an agent
 * cannot otherwise discover), including the 2-level nesting (design → fraction, solver →
 * cooling schedule), that required-ness follows `isElementOptional`, and that a
 * `@TomlComment` becomes a field `description`.
 */
class ConfigSchemaGeneratorTest {

    private fun JsonObject.prop(name: String): JsonObject =
        this["properties"]!!.jsonObject[name]!!.jsonObject

    private fun JsonObject.requiredNames(): Set<String> =
        (this["required"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

    /** A oneOf variant's discriminator enum values; empty for a non-object variant (e.g. a nullable
     *  sealed field's `{type:"null"}` member, which carries no discriminator). */
    private fun JsonObject.discriminatorEnum(): List<String> =
        ((this["properties"] as? JsonObject)?.get("type") as? JsonObject)?.get("enum")
            ?.let { (it as JsonArray).map { e -> e.jsonPrimitive.content } } ?: emptyList()

    /** The set of discriminator values across a `oneOf`'s variants. */
    private fun JsonObject.variantTypes(): Set<String> =
        (this["oneOf"] as JsonArray).flatMap { it.jsonObject.discriminatorEnum() }.toSet()

    /** The variant object within a `oneOf` whose discriminator equals [type]. */
    private fun JsonObject.variant(type: String): JsonObject =
        (this["oneOf"] as JsonArray).map { it.jsonObject }.first { type in it.discriminatorEnum() }

    /** True when the field permits JSON null — a scalar `[T,"null"]` type union, or an `anyOf`
     *  with a `{type:"null"}` member (how a structural nullable, e.g. a nullable object, is emitted). */
    private fun JsonObject.allowsNull(): Boolean {
        (this["type"] as? JsonArray)?.let { arr -> if (arr.any { it.jsonPrimitive.content == "null" }) return true }
        (this["anyOf"] as? JsonArray)?.let { arr -> if (arr.any { it.jsonObject["type"]?.jsonPrimitive?.content == "null" }) return true }
        return false
    }

    @Test
    fun `experiment designSpec surfaces its sealed variants and nested fractions`() {
        val schema = ConfigSchemaGenerator.schemaFor(ExperimentConfiguration.serializer().descriptor)

        // required = exactly the fields with no default value.
        assertTrue(
            schema.requiredNames().containsAll(setOf("modelReference", "factors", "designSpec")),
            "top-level required should include the defaultless fields; got ${schema.requiredNames()}",
        )

        val design = schema.prop("designSpec")
        assertTrue("oneOf" in design, "designSpec should be a oneOf of its sealed variants")
        assertEquals(
            setOf("fullFactorial", "twoLevelFactorial", "centralComposite", "manual"),
            design.variantTypes(),
            "designSpec discriminator enum should be the four @SerialNames",
        )

        // 2-level nesting: twoLevelFactorial.fraction is itself a sealed oneOf.
        val fraction = design.variant("twoLevelFactorial").prop("fraction")
        assertTrue(
            fraction.variantTypes().containsAll(setOf("full", "half", "custom")),
            "nested Fraction variants should surface; got ${fraction.variantTypes()}",
        )
    }

    @Test
    fun `optimization solver surfaces the four solvers and nested cooling schedules`() {
        val schema = ConfigSchemaGenerator.schemaFor(OptimizationRunConfiguration.serializer().descriptor)

        val solver = schema.prop("solver")
        assertTrue(
            solver.variantTypes().containsAll(setOf("stochasticHillClimbing", "simulatedAnnealing", "crossEntropy", "rSpline")),
            "solver discriminator enum should include the four solver @SerialNames; got ${solver.variantTypes()}",
        )

        // 2-level nesting: simulatedAnnealing.coolingSchedule is a sealed oneOf.
        val cooling = solver.variant("simulatedAnnealing").prop("coolingSchedule")
        assertTrue(
            cooling.variantTypes().containsAll(setOf("linear", "exponential", "logarithmic")),
            "nested CoolingScheduleSpec variants should surface; got ${cooling.variantTypes()}",
        )
    }

    @Test
    fun `a TomlComment becomes a field description`() {
        val schema = ConfigSchemaGenerator.schemaFor(ExperimentConfiguration.serializer().descriptor)
        assertTrue(
            "description" in schema.prop("designSpec"),
            "designSpec carries a @TomlComment, so it should surface as a description",
        )
    }

    @Test
    fun `a list nested inside another list stays an array`() {
        // Regression (Defect B): the cycle guard keyed on serialName, but every Kotlin List
        // shares serialName "kotlin.collections.ArrayList". So a list-within-a-list
        // (ExperimentConfiguration.factors[].levels, RunConfiguration.scenarios[].
        // controlOverrides.numericControls) was falsely flagged as a cycle and truncated to an
        // opaque {type:object} — which a strict MCP client (Zod) then rejects as
        // "expected object, received array", blocking the very documents the *_template tools emit.

        // factors[].levels : List<Double> nested inside List<FactorSpec>
        val expSchema = ConfigSchemaGenerator.schemaFor(ExperimentConfiguration.serializer().descriptor)
        val levels = expSchema.prop("factors")["items"]!!.jsonObject.prop("levels")
        assertEquals(
            "array", levels["type"]?.jsonPrimitive?.content,
            "factors[].levels is a nested list; it must stay an array, not collapse to an object: $levels",
        )

        // scenarios[].controlOverrides.numericControls : List<ControlData> nested inside List<ScenarioSpec>
        val runSchema = ConfigSchemaGenerator.schemaFor(RunConfiguration.serializer().descriptor)
        val numericControls = runSchema.prop("scenarios")["items"]!!.jsonObject
            .prop("controlOverrides").prop("numericControls")
        assertEquals(
            "array", numericControls["type"]?.jsonPrimitive?.content,
            "scenarios[].controlOverrides.numericControls is a nested list; it must stay an array: $numericControls",
        )
        assertTrue(
            "properties" in numericControls["items"]!!.jsonObject,
            "the array items should be the ControlData object schema, not opaque",
        )
    }

    @Test
    fun `nullable fields are advertised as nullable`() {
        // Defect B, second facet: without emitted nullability a strict client rejects the null
        // that run_template legitimately produces ("expected string/number, received null").
        val runSchema = ConfigSchemaGenerator.schemaFor(RunConfiguration.serializer().descriptor)

        // A genuinely-nullable scalar optional (String?) — emitted as null when left unset.
        val outputDirectory = runSchema.prop("outputConfig").prop("outputDirectory")
        assertTrue(
            outputDirectory.allowsNull(),
            "outputConfig.outputDirectory is String?; it must advertise null: $outputDirectory",
        )

        // A nullable *object* (CaptureWindow?) must also advertise null — via anyOf, not a
        // ["object","null"] type union, which a strict json-schema→Zod client mishandles (it keeps
        // enforcing the object's `required` and drops the null branch). run_template always emits
        // tracingConfig.capture.captureWindow = null, so this blocks the whole run-config round-trip.
        val captureWindow = runSchema.prop("tracingConfig").prop("capture").prop("captureWindow")
        assertTrue(
            captureWindow.allowsNull() && "anyOf" in captureWindow,
            "captureWindow is CaptureWindow?; a nullable object must advertise null via anyOf: $captureWindow",
        )

        // Phase-1 synergy: ControlData's ±∞ bounds/value carry a nullable serializer descriptor and
        // the MCP transport sanitizes ±∞ -> null, so the schema must accept null there too.
        val controlItem = runSchema.prop("scenarios")["items"]!!.jsonObject
            .prop("controlOverrides").prop("numericControls")["items"]!!.jsonObject
        for (field in listOf("value", "lowerBound", "upperBound")) {
            assertTrue(
                controlItem.prop(field).allowsNull(),
                "ControlData.$field must advertise null (sanitized ±∞): ${controlItem.prop(field)}",
            )
        }

        // A non-nullable field stays single-typed (nullability is not applied indiscriminately).
        assertEquals(
            "string", controlItem.prop("keyName")["type"]?.jsonPrimitive?.content,
            "a non-nullable field must not gain a null union",
        )
    }

    @Test
    fun `all four document families generate as non-empty object schemas`() {
        val families = mapOf(
            "RunConfiguration" to RunConfiguration.serializer().descriptor,
            "ExperimentConfiguration" to ExperimentConfiguration.serializer().descriptor,
            "OptimizationRunConfiguration" to OptimizationRunConfiguration.serializer().descriptor,
            "FitConfiguration" to FitConfiguration.serializer().descriptor,
        )
        for ((name, descriptor) in families) {
            val schema = ConfigSchemaGenerator.schemaFor(descriptor)
            assertEquals("object", schema["type"]!!.jsonPrimitive.content, "$name should generate an object schema")
            assertTrue((schema["properties"] as JsonObject).isNotEmpty(), "$name should have properties")
        }
    }
}
