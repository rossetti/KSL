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

import ksl.examples.general.appsupport.MM1Bundle
import ksl.service.capability.run.support.TestBundleBuilder
import java.nio.file.Files
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
        val dir = Files.createTempDirectory("ksl-bundles-version")
        BundleRegistry.empty().use { registry ->
            registry.loadOrReplaceFromJar(TestBundleBuilder.build(dir, "mm1", listOf(MM1Bundle::class.java)))
            val salt1 = registry.versionSaltFor(listOf("MM1"))
            assertTrue(salt1.isNotBlank(), "a jar-loaded model should have a content-based version token")

            // Byte-identical rebuild (deterministic jar) -> same content hash -> same salt.
            registry.loadOrReplaceFromJar(TestBundleBuilder.build(dir, "mm1", listOf(MM1Bundle::class.java)))
            assertEquals(salt1, registry.versionSaltFor(listOf("MM1")), "an identical jar must not change the salt")

            // Rebuild with different bytes -> new content hash -> new salt -> invalidation.
            val rebuilt = TestBundleBuilder.build(dir, "mm1", listOf(MM1Bundle::class.java), mapOf("BUILD.txt" to "v2".toByteArray()))
            registry.loadOrReplaceFromJar(rebuilt)
            assertNotEquals(salt1, registry.versionSaltFor(listOf("MM1")), "a rebuilt jar must change the salt")
        }
    }
}
