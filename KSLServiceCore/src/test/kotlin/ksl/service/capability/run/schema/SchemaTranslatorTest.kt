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

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ksl.service.capability.run.BundleRegistry
import ksl.service.capability.run.TestBundles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the [SchemaTranslator] against the real MM1 bundle's descriptor.
 * MM1 nominates a small catalog (3 inputs, 3 outputs) via `curateCatalog`, so
 * this exercises both the catalog-led path (the agent-tool payoff) and the
 * degradation-safe full-surface fallback.
 */
class SchemaTranslatorTest {

    @Test
    fun `catalog-led input schema surfaces exactly the nominated inputs in order`() {
        TestBundles.registry().use { registry ->
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")
            assertNotNull(descriptor)
            val catalog = descriptor.catalog
            assertNotNull(catalog, "MM1 nominates a catalog")

            val schema = SchemaTranslator.inputSchema(descriptor)
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)

            val props = schema["properties"]!!.jsonObject
            // Exactly the nominated inputs, in the author's priority order.
            assertEquals(catalog.nominatedInputs.map { it.key }, props.keys.toList())
            // Every property carries a type — a well-formed schema entry.
            props.values.forEach { assertTrue(it.jsonObject.containsKey("type")) }
        }
    }

    @Test
    fun `no-catalog fallback emits the full control surface`() {
        TestBundles.registry().use { registry ->
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val stripped = descriptor.copy(catalog = null)

            val props = SchemaTranslator.inputSchema(stripped)["properties"]!!.jsonObject
            // Full surface includes every numeric control by key name.
            val numericKeys = descriptor.controls.numericControls.map { it.keyName }
            assertTrue(numericKeys.isNotEmpty(), "MM1 declares at least the numServers control")
            assertTrue(props.keys.containsAll(numericKeys))
        }
    }

    @Test
    fun `output schema leads with nominated outputs`() {
        TestBundles.registry().use { registry ->
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val nominated = descriptor.catalog!!.nominatedOutputs.map { it.name }

            val props = SchemaTranslator.outputSchema(descriptor)["properties"]!!.jsonObject
            assertEquals(nominated, props.keys.toList())
        }
    }
}
