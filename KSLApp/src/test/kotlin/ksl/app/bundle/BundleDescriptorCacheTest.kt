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

package ksl.app.bundle

import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.simulation.ModelDescriptor
import org.junit.jupiter.api.DisplayName
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the retention added to [BundleDescriptorCache]: the per-JAR cache
 * directories are bounded (oldest pruned past `maxEntries`) and a stale-schema
 * entry is deleted the moment a read discovers it — so the cache cannot grow
 * without bound as JARs are rebuilt.
 */
class BundleDescriptorCacheTest {

    // A real descriptor (MM1) — immutable data, reused across writes.
    private val descriptor: ModelDescriptor by lazy { MM1ModelBuilder().build(null, null).modelDescriptor() }

    private fun writeAndAge(cache: BundleDescriptorCache, root: Path, sha: String, age: Long) {
        cache.write(sha, "MM1", descriptor)
        Files.setLastModifiedTime(root.resolve(sha), FileTime.fromMillis(age))
    }

    private fun dirCount(root: Path): Long =
        Files.list(root).use { s -> s.filter { Files.isDirectory(it) }.count() }

    @Test
    @DisplayName("write prunes to the most-recent maxEntries per-JAR cache dirs")
    fun writePrunesOldestEntries() {
        val root = Files.createTempDirectory("bdc-prune")
        val cache = BundleDescriptorCache(root, maxEntries = 3)

        // sha0 (oldest) .. sha4 (newest); each write past the cap drops the current oldest.
        (0..4).forEach { i -> writeAndAge(cache, root, "sha$i", age = 1_000L + i) }

        assertEquals(3L, dirCount(root), "cache must be capped at 3 per-JAR dirs")
        assertNull(cache.read("sha0", "MM1"), "the oldest JAR cache should be pruned")
        assertNull(cache.read("sha1", "MM1"), "the next-oldest JAR cache should be pruned")
        assertNotNull(cache.read("sha4", "MM1"), "the newest JAR cache should survive and read back")
    }

    @Test
    @DisplayName("a schema-version mismatch on read deletes the stale cache entry")
    fun staleSchemaEntryDeletedOnRead() {
        val root = Files.createTempDirectory("bdc-stale")
        val cache = BundleDescriptorCache(root)
        val dir = root.resolve("deadbeef")
        Files.createDirectories(dir)
        // schemaVersion 1 != current; the descriptor file content is irrelevant (read
        // fails at the schema check before decoding it).
        Files.writeString(dir.resolve("meta.json"), """{"cacheSchemaVersion":1,"writtenAt":"2020-01-01T00:00:00Z"}""")
        Files.writeString(dir.resolve("MM1.json"), "{}")

        assertNull(cache.read("deadbeef", "MM1"), "a stale-schema entry must read as a miss")
        assertTrue(Files.notExists(dir), "a stale-schema entry must be deleted on read")
    }

    @Test
    @DisplayName("a zero maxEntries disables pruning")
    fun zeroMaxEntriesDisablesPruning() {
        val root = Files.createTempDirectory("bdc-unbounded")
        val cache = BundleDescriptorCache(root, maxEntries = 0)
        (0..5).forEach { cache.write("sha$it", "MM1", descriptor) }
        assertEquals(6L, dirCount(root), "a zero cap must retain every entry")
    }
}
