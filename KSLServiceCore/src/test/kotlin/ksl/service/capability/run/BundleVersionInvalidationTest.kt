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

import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.service.capability.run.support.DeterministicBundleJar
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Proves bundle-version cache invalidation (Phase 8 §9): the result-cache
 * version salt is derived from the providing bundle's content hash, so a
 * *rebuilt* jar (same model id, new bytes) changes the salt — and thus the cache
 * key — while an identical rebuild does not. This is what stops a reloaded model
 * from being served a stale cached result.
 */
class BundleVersionInvalidationTest {

    @Test
    fun `a rebuilt bundle jar changes the version salt, an identical one does not`() {
        BundleRegistry.empty().use { registry ->
            fun assemble(version: String): Path = DeterministicBundleJar.build(
                Files.createTempDirectory("ksl-bundles-version"), "mm1", "ksl.examples.mm1",
                MM1ModelBuilder::class.java, version = version,
            )

            registry.loadOrReplaceFromJar(assemble("v1"))
            val salt1 = registry.versionSaltFor(listOf("MM1"))
            assertTrue(salt1.isNotBlank(), "a jar-loaded model should have a content-based version token")

            // An identical rebuild -> same content hash -> same salt.
            registry.loadOrReplaceFromJar(assemble("v1"))
            assertEquals(salt1, registry.versionSaltFor(listOf("MM1")), "an identical jar must not change the salt")

            // A different build -> new content hash -> new salt -> invalidation.
            registry.loadOrReplaceFromJar(assemble("v2"))
            assertNotEquals(salt1, registry.versionSaltFor(listOf("MM1")), "a rebuilt jar must change the salt")
        }
    }
}
