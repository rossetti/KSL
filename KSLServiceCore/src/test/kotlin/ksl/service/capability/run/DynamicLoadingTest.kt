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
import kotlin.test.assertTrue

/**
 * Proves the dynamic-catalog path (Phase 8.6): a bundle JAR dropped into the
 * watched directory is auto-detected and its models become resolvable with no
 * restart; deleting the JAR drops them. Reload uses the mutation-safe registry
 * with deferred classloader close.
 */
class DynamicLoadingTest {

    @Test
    fun `a dropped jar is picked up and a removed jar is dropped`() {
        val dir = Files.createTempDirectory("ksl-bundles")
        BundleRegistry.empty().use { registry ->
            val watcher = BundleDirectoryWatcher(registry, dir)
            val provider = RegistryModelProvider(registry)

            // Empty directory → nothing.
            watcher.scanOnce()
            assertTrue(registry.listBundles().isEmpty())

            // Drop a real bundle jar and rescan → MM1 appears and resolves.
            TestBundleBuilder.build(dir, "mm1", listOf(MM1Bundle::class.java))
            watcher.scanOnce()
            assertTrue(registry.listBundles().any { it.bundleId == "ksl.examples.mm1" }, "MM1 jar should be loaded")
            assertTrue(provider.isModelProvided("MM1"), "the dynamic provider must resolve the new model")

            // Remove the jar and rescan → MM1 is gone.
            Files.list(dir).use { it.filter { p -> p.toString().endsWith(".jar") }.toList() }.forEach { Files.delete(it) }
            watcher.scanOnce()
            assertTrue(registry.listBundles().none { it.bundleId == "ksl.examples.mm1" }, "removed jar should be dropped")
        }
    }

    @Test
    fun `an unchanged jar is not reloaded on rescan`() {
        val dir = Files.createTempDirectory("ksl-bundles-stable")
        BundleRegistry.empty().use { registry ->
            val watcher = BundleDirectoryWatcher(registry, dir)
            TestBundleBuilder.build(dir, "mm1", listOf(MM1Bundle::class.java))
            watcher.scanOnce()
            val firstHash = registry.knownSources().values.firstOrNull()
            watcher.scanOnce() // same bytes → no reload
            assertTrue(registry.listBundles().count { it.bundleId == "ksl.examples.mm1" } == 1, "no duplicate on rescan")
            assertTrue(registry.knownSources().values.firstOrNull() == firstHash)
        }
    }
}
