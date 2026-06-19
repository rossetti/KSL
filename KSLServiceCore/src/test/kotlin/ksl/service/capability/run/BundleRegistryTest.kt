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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the registry against a real bundle's output. The `KSLExamples`
 * dogfood bundles are on the test classpath, so `fromClasspath()` discovers
 * the M/M/1 bundle and serves its descriptor — including the author-nominated
 * catalog the bundle declares via `curateCatalog` (MM1Bundle.kt).
 */
class BundleRegistryTest {

    @Test
    fun `discovers the MM1 example bundle from the classpath`() {
        BundleRegistry.fromClasspath().use { registry ->
            val mm1 = registry.listBundles().firstOrNull { it.bundleId == "ksl.examples.mm1" }
            assertNotNull(mm1, "expected the MM1 dogfood bundle on the test classpath")
            assertEquals("M/M/1 Queue Example", mm1.displayName)
            assertTrue("MM1" in mm1.modelIds)
        }
    }

    @Test
    fun `serves the MM1 descriptor with its nominated catalog`() {
        BundleRegistry.fromClasspath().use { registry ->
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")
            assertNotNull(descriptor, "expected a descriptor for MM1")
            assertEquals("MM1", descriptor.modelIdentifier)
            assertTrue(descriptor.responseNames.isNotEmpty())

            // MM1Bundle curates a catalog (3 inputs, 3 outputs) — the raw
            // material the SchemaTranslator will turn into a focused tool.
            val catalog = descriptor.catalog
            assertNotNull(catalog, "MM1 nominates a catalog via curateCatalog")
            assertTrue(catalog.nominatedInputs.isNotEmpty())
            assertTrue(catalog.nominatedOutputs.isNotEmpty())
        }
    }
}
