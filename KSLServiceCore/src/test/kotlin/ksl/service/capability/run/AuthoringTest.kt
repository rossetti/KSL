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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import ksl.app.bundle.KSLAppKind
import ksl.app.config.RunConfigurationJson
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The authoring stack (Phase 8.3): the intent menu (`modelKinds`) and the
 * generated scaffolds (`RunTemplates`). A generated scaffold must itself be a
 * *valid, runnable* document.
 */
class AuthoringTest {

    @Test
    fun `modelKinds exposes the model's declared task kinds`() {
        TestBundles.registry().use { registry ->
            val kinds = registry.modelKinds("ksl.examples.mm1", "MM1")
            assertTrue(KSLAppKind.SINGLE in kinds, "MM1 should support SINGLE; got $kinds")
            assertTrue(KSLAppKind.SIMOPT in kinds, "MM1 declares SIMOPT support")
        }
    }

    @Test
    fun `a generated run scaffold round-trips and is itself a valid document`() {
        TestBundles.registry().use { registry ->
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val scaffold = RunTemplates.runDocument(descriptor, "MM1")

            // It survives the authoritative codec. NOTE: Infinity <-> +/-inf is symmetric under the
            // codec, so this does NOT exercise the MCP wire-sanitized null form — see the
            // sanitization round-trip test below, which is what run_template -> run_config actually hits.
            val decoded = RunConfigurationJson.decode(RunConfigurationJson.encode(scaffold))

            RunService.fromRegistry(registry).use { service ->
                assertTrue(service.validateRunConfig(decoded).isValid, "the scaffold must be runnable as-is")
            }
        }
    }

    @Test
    fun `a run scaffold survives the MCP wire-sanitization round-trip`() {
        TestBundles.registry().use { registry ->
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val scaffold = RunTemplates.runDocument(descriptor, "MM1")

            // The MCP transport sanitizes the encoded document's non-finite doubles (an unbounded
            // control's bounds = +/-inf) to null before an agent ever sees it in structuredContent.
            // That null form is exactly what run_template -> run_config re-ingests, and what the
            // decode(encode(...)) test above never covers. Assert the sanitized form re-ingests.
            val sanitized = sanitizeNonFinite(wireJson.parseToJsonElement(RunConfigurationJson.encode(scaffold)))
            val decoded = RunConfigurationJson.decode(sanitized.toString())

            RunService.fromRegistry(registry).use { service ->
                assertTrue(service.validateRunConfig(decoded).isValid, "the wire-sanitized scaffold must re-ingest and be runnable")
            }
        }
    }

    private val wireJson = Json { allowSpecialFloatingPointValues = true }

    /** Mirrors the MCP server's sanitizeNonFinite: every non-finite numeric primitive becomes null,
     *  exactly as run_template's structuredContent is produced. */
    private fun sanitizeNonFinite(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.mapValues { sanitizeNonFinite(it.value) })
        is JsonArray -> JsonArray(el.map(::sanitizeNonFinite))
        is JsonPrimitive -> if (!el.isString && el.doubleOrNull?.isFinite() == false) JsonNull else el
        else -> el
    }
}
