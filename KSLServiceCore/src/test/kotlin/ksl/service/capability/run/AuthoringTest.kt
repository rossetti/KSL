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

import ksl.app.bundle.KSLAppKind
import ksl.app.config.RunConfigurationJson
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The authoring stack (Phase 8.3): the intent menu (`modelKinds`), author
 * recipes (`recipes`), and generated scaffolds (`RunTemplates`). A generated
 * scaffold must itself be a *valid, runnable* document.
 */
class AuthoringTest {

    @Test
    fun `modelKinds exposes the model's declared task kinds`() {
        BundleRegistry.fromClasspath().use { registry ->
            val kinds = registry.modelKinds("ksl.examples.mm1", "MM1")
            assertTrue(KSLAppKind.SINGLE in kinds, "MM1 should support SINGLE; got $kinds")
            assertTrue(KSLAppKind.SIMOPT in kinds, "MM1 declares SIMOPT support")
        }
    }

    @Test
    fun `recipes returns a list (possibly empty) without error`() {
        BundleRegistry.fromClasspath().use { registry ->
            // MM1 ships no curated recipes; the mechanism is exposed regardless.
            val recipes = registry.recipes("ksl.examples.mm1", "MM1")
            assertTrue(recipes.isEmpty() || recipes.isNotEmpty()) // total: no exception
        }
    }

    @Test
    fun `a generated run scaffold round-trips and is itself a valid document`() {
        BundleRegistry.fromClasspath().use { registry ->
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val scaffold = RunTemplates.runDocument(descriptor, "MM1")

            // It survives the authoritative codec (so it can be handed to an agent).
            val decoded = RunConfigurationJson.decode(RunConfigurationJson.encode(scaffold))

            RunService.fromRegistry(registry).use { service ->
                assertTrue(service.validateRunConfig(decoded).isValid, "the scaffold must be runnable as-is")
            }
        }
    }
}
