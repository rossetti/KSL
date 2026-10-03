/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.server.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ksl.app.config.ExperimentRunOverrides
import ksl.controls.ModelControlsExport
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.ScenarioSpec
import ksl.service.capability.run.BundleRegistry
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A document-centric run can now be written the way `run_model` already accepts: a flat
 *  `{inputKey: value}` map, keyed as `describe_model` advertises.
 *
 *  What that replaces is the reason this exists. A control override in a document demands the whole
 *  ~14-field `ControlData` descriptor, so a two-scenario comparison differing in one number was about
 *  2 KB of copied boilerplate, and an `rvOverrides` entry's `{rvName, paramName, value}` shape appeared
 *  in no template and no tool description — the report's author got past it only by reading the source.
 *
 *  The decisive test is the first one: the short form and the long form must produce the *same run*, or
 *  the convenience is a second way of meaning something slightly different.
 */
class McpRunConfigInputsTest {

    private lateinit var registry: BundleRegistry
    private lateinit var tools: KslMcpTools

    @BeforeTest
    fun setUp() {
        registry = TestBundles.registry()
        tools = KslMcpTools(
            registry,
            ResultStore(Files.createTempDirectory("mcp-inputs")),
            ArtifactStore(Files.createTempDirectory("mcp-inputs-art")),
        )
    }

    @AfterTest
    fun tearDown() {
        tools.close()
        registry.close()
    }

    private val controlKey: String
        get() = registry.descriptorForModelId("MM1")!!.controls.numericControls.first().keyName

    private fun doc(controls: ModelControlsExport = ModelControlsExport(modelName = "")): String =
        RunConfigurationJson.encode(
            RunConfiguration(
                scenarios = listOf(
                    ScenarioSpec(
                        name = "only",
                        modelReference = ModelReference.ByProviderId("MM1"),
                        runOverrides = ExperimentRunOverrides(numberOfReplications = 2, lengthOfReplication = 500.0),
                        controlOverrides = controls,
                    ),
                ),
            ),
        )

    private suspend fun resultIdOf(arguments: kotlinx.serialization.json.JsonObject): String =
        tools.runConfig(arguments).structuredContent!!.jsonObject["resultId"]!!.jsonPrimitive.content

    @Test
    @DisplayName("the short inputs form and the full descriptor form are the same run")
    fun inputsMatchTheLongHandForm() = runBlocking {
        val descriptor = registry.descriptorForModelId("MM1")!!
        val control = descriptor.controls.numericControls.first()
        val value = control.value + 1.0

        // Longhand: the whole ControlData block, as the document has always required.
        val longHand = resultIdOf(buildJsonObject {
            put(
                "config",
                doc(
                    ModelControlsExport(
                        modelName = descriptor.modelName,
                        numericControls = listOf(control.copy(value = value)),
                    ),
                ),
            )
        })

        // Short form: one key and one number.
        val short = resultIdOf(buildJsonObject {
            put("config", doc())
            put("inputs", buildJsonObject { put(control.keyName, value) })
        })

        // resultId is the content hash of the configuration that ran, so equality here is the strongest
        // available statement that the two forms mean the same run -- not merely that both succeeded.
        assertEquals(longHand, short, "the two ways of saying it must produce one run")
    }

    @Test
    @DisplayName("inputs can differ per scenario, which is what the boilerplate was for")
    fun perScenarioInputs() = runBlocking {
        val two = RunConfigurationJson.encode(
            RunConfiguration(
                scenarios = listOf("low", "high").map {
                    ScenarioSpec(
                        name = it,
                        modelReference = ModelReference.ByProviderId("MM1"),
                        runOverrides = ExperimentRunOverrides(numberOfReplications = 2, lengthOfReplication = 500.0),
                    )
                },
            ),
        )
        val result = tools.runConfig(buildJsonObject {
            put("config", two)
            put("inputs", buildJsonObject {
                putJsonObjectCompat("low") { put(controlKey, 1.0) }
                putJsonObjectCompat("high") { put(controlKey, 2.0) }
            })
        })
        assertTrue(
            result.structuredContent?.jsonObject?.get("resultId") != null,
            "a per-scenario inputs map must run: ${result.content}"
        )
    }

    @Test
    @DisplayName("an unknown input key is refused by name, with the valid keys listed")
    fun unknownInputKeyIsNamed() = runBlocking {
        val result = tools.runConfig(buildJsonObject {
            put("config", doc())
            put("inputs", buildJsonObject { put("Nope.notAThing", 1.0) })
        })
        val text = result.content.joinToString("\n") {
            (it as? io.modelcontextprotocol.kotlin.sdk.types.TextContent)?.text ?: ""
        }
        assertTrue("Nope.notAThing" in text, "the refusal must quote the key: $text")
        assertTrue("only" in text, "and say which scenario it was for: $text")
    }

    @Test
    @DisplayName("inputs naming a scenario the config does not have is refused")
    fun unknownScenarioIsNamed() = runBlocking {
        val result = tools.runConfig(buildJsonObject {
            put("config", doc())
            put("inputs", buildJsonObject { putJsonObjectCompat("nosuch") { put(controlKey, 1.0) } })
        })
        val text = result.content.joinToString("\n") {
            (it as? io.modelcontextprotocol.kotlin.sdk.types.TextContent)?.text ?: ""
        }
        assertTrue("nosuch" in text && "'only'" in text, "name the typo and list the scenarios: $text")
    }
}

/** `putJsonObject` under a name, spelled out so the test reads the same on any kotlinx version. */
private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObjectCompat(
    key: String,
    build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    put(key, buildJsonObject(build))
}
