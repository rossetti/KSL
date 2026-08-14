package ksl.server.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the `config` argument schema shared by every document tool (run / optimization /
 * experiment / fit, in their `*_config`, `validate_*`, and `preview_*` forms).
 *
 * The regression these tests exist for: the schema used to offer object-or-string as a `type`
 * array while ALSO carrying sibling `properties`/`required`. A json-schema to Zod converter keeps
 * the object branch of such a union and silently drops the rest, so the documented "paste a .toml
 * string" path was rejected by client-side input validation before reaching the server — which has
 * always accepted strings. `anyOf` survives that conversion; a type array does not.
 */
class ConfigDocSchemaTest {

    private val families = listOf(
        "RunConfiguration" to "run_template",
        "OptimizationRunConfiguration" to "optimization_template",
        "ExperimentConfiguration" to "experiment_template",
        "FitConfiguration" to "fit_template",
    )

    private fun configNode(documentType: String): JsonObject =
        KslMcpServer.configDocumentInputSchema(documentType).properties!!["config"]!!.jsonObject

    @Test
    @DisplayName("config offers exactly an object branch and a string branch via anyOf")
    fun configOffersBothBranches() {
        for ((documentType, _) in families) {
            val config = configNode(documentType)
            val anyOf = config["anyOf"] as? JsonArray
            assertNotNull(anyOf, "$documentType: config must use anyOf")
            assertEquals(2, anyOf.size, "$documentType: expected an object branch and a string branch")
            val types = anyOf.map { it.jsonObject["type"]?.jsonPrimitive?.content }
            assertTrue("object" in types, "$documentType: no object branch")
            assertTrue("string" in types, "$documentType: no string branch — the TOML/JSON string path is gone")
        }
    }

    @Test
    @DisplayName("config carries no top-level type key, so no type union can be dropped in conversion")
    fun configHasNoTypeUnion() {
        for ((documentType, _) in families) {
            assertNull(
                configNode(documentType)["type"],
                "$documentType: a `type` at the config node is the regression this test guards",
            )
        }
    }

    @Test
    @DisplayName("the object branch carries the generated per-field shape, not an opaque object")
    fun objectBranchCarriesGeneratedShape() {
        for ((documentType, _) in families) {
            val objectBranch = (configNode(documentType)["anyOf"] as JsonArray)
                .map { it.jsonObject }
                .first { it["type"]?.jsonPrimitive?.content == "object" }
            val properties = objectBranch["properties"]?.jsonObject
            assertNotNull(properties, "$documentType: object branch lost its generated properties")
            assertTrue(properties.isNotEmpty(), "$documentType: generated properties are empty")
        }
    }

    @Test
    @DisplayName("the string branch is bare, so nothing constrains it back into an object")
    fun stringBranchIsBare() {
        for ((documentType, _) in families) {
            val stringBranch = (configNode(documentType)["anyOf"] as JsonArray)
                .map { it.jsonObject }
                .first { it["type"]?.jsonPrimitive?.content == "string" }
            assertEquals(
                setOf("type"),
                stringBranch.keys,
                "$documentType: the string branch must carry nothing but its type",
            )
        }
    }

    @Test
    @DisplayName("each family's description names its own scaffold tool")
    fun descriptionNamesTheRightTemplate() {
        for ((documentType, template) in families) {
            val description = configNode(documentType)["description"]!!.jsonPrimitive.content
            assertTrue(template in description, "$documentType: description should point at $template")
            assertTrue("TOML" in description, "$documentType: description should still advertise the string form")
        }
    }

    @Test
    @DisplayName("control-bearing families warn that an override carries the full control descriptor")
    fun controlFamiliesCarryTheOverrideWarning() {
        for (documentType in listOf("RunConfiguration", "OptimizationRunConfiguration")) {
            val description = configNode(documentType)["description"]!!.jsonPrimitive.content
            assertTrue("FULL control descriptor" in description, "$documentType: missing the override guidance")
            assertTrue("rvOverride" in description, "$documentType: missing the rvOverride-not-control rule")
        }
        // The experiment and fit documents have no controlOverrides, so the advice would be noise.
        for (documentType in listOf("ExperimentConfiguration", "FitConfiguration")) {
            val description = configNode(documentType)["description"]!!.jsonPrimitive.content
            assertTrue("rvOverride" !in description, "$documentType: control advice does not apply here")
        }
    }
}
