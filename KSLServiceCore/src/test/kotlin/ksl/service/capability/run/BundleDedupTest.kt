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
import ksl.examples.general.appsupport.ManifestBundleFixtures
import org.junit.jupiter.api.Disabled
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MM1 = "ksl.examples.mm1"

/**
 * Registry-level de-duplication / newest-wins / conflict-surfacing over real
 * crafted jars (all declaring the MM1 bundle): the catalog shows one active entry
 * per bundleId, the newest build wins, removing it promotes the runner-up, and
 * true duplicates collapse — with the disclosure the design calls for.
 */
@Disabled(
    "Needs a deterministic manifest-jar builder: the production assembler " +
        "(ManifestBundleFixtures/BundleAssembler) stamps a unique Build-Time per build, so the " +
        "byte-identical and content-controlled-SHA premises here no longer hold. The dedup / " +
        "newest-wins core is covered by BundleResolverTest; re-enable with a deterministic packer.",
)
class BundleDedupTest {

    /** A real MM1 bundle jar (content varies by [marker]; builtAt set via file mtime). */
    private fun buildJar(dir: Path, name: String, marker: String, builtAt: Instant): Path {
        val jar = ManifestBundleFixtures.assembleManifestBundle(
            dir, name, MM1, MM1ModelBuilder::class.java
        ) { it.version = marker }
        Files.setLastModifiedTime(jar, FileTime.from(builtAt))
        return jar
    }

    private fun mm1(registry: BundleRegistry): BundleInfo? =
        registry.listBundles().firstOrNull { it.bundleId == MM1 }

    @Test
    fun `same bundleId from two jars resolves newest-wins with disclosure`() {
        val dir = Files.createTempDirectory("ksl-dedup")
        BundleRegistry.empty().use { registry ->
            val old = buildJar(dir, "old", "v1", Instant.ofEpochSecond(1_000))
            val new = buildJar(dir, "new", "v2", Instant.ofEpochSecond(2_000))
            registry.loadOrReplaceFromJar(old)
            registry.loadOrReplaceFromJar(new)

            // Exactly one catalog entry, and the newest jar is the active one.
            assertEquals(1, registry.listBundles().count { it.bundleId == MM1 }, "one active entry per bundleId")
            val info = mm1(registry)!!
            assertEquals("new.jar", info.source, "the newer build wins")
            assertEquals(1, info.shadowedCount)
            assertNotNull(info.notice, "the student-facing catalog hint is present")

            // The operator-facing conflict detail discloses the shadowed source.
            val conflict = registry.conflicts().single { it.bundleId == MM1 }
            assertEquals("new.jar", conflict.activeSource)
            assertEquals(listOf("old.jar"), conflict.shadowedSources)

            // Read paths resolve to the active bundle (model is reachable, once).
            assertTrue(registry.modelProvider().isModelProvided("MM1"))
            assertNotNull(registry.describeModel(MM1, "MM1"))
        }
    }

    @Test
    fun `removing the winner promotes the runner-up`() {
        val dir = Files.createTempDirectory("ksl-dedup-promote")
        BundleRegistry.empty().use { registry ->
            val old = buildJar(dir, "old", "v1", Instant.ofEpochSecond(1_000))
            val new = buildJar(dir, "new", "v2", Instant.ofEpochSecond(2_000))
            registry.loadOrReplaceFromJar(old)
            registry.loadOrReplaceFromJar(new)
            assertEquals("new.jar", mm1(registry)!!.source)

            registry.removeFromJar(new)

            val info = mm1(registry)
            assertNotNull(info, "the older copy is promoted, not lost")
            assertEquals("old.jar", info.source)
            assertEquals(0, info.shadowedCount)
            assertNull(info.notice)
            assertTrue(registry.conflicts().none { it.bundleId == MM1 })
        }
    }

    @Test
    fun `true duplicates collapse to a single active entry`() {
        val dir = Files.createTempDirectory("ksl-dedup-true")
        BundleRegistry.empty().use { registry ->
            // Identical content (same marker) under two names → identical SHA.
            val a = buildJar(dir, "copyA", "same", Instant.ofEpochSecond(1_000))
            val b = buildJar(dir, "copyB", "same", Instant.ofEpochSecond(1_000))
            registry.loadOrReplaceFromJar(a)
            registry.loadOrReplaceFromJar(b)

            assertEquals(1, registry.listBundles().count { it.bundleId == MM1 }, "duplicates collapse to one")
            assertEquals(1, mm1(registry)!!.shadowedCount)
        }
    }

    @Test
    fun `a single bundle has no shadow and no notice`() {
        val dir = Files.createTempDirectory("ksl-dedup-single")
        BundleRegistry.empty().use { registry ->
            registry.loadOrReplaceFromJar(buildJar(dir, "only", "v1", Instant.ofEpochSecond(1_000)))
            val info = mm1(registry)!!
            assertEquals(0, info.shadowedCount)
            assertNull(info.notice)
            assertEquals("only.jar", info.source)
        }
    }
}
