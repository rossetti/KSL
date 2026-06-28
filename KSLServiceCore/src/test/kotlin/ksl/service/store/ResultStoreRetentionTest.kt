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

package ksl.service.store

import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the disk retention cap (Phase 8 §9): once more than `maxDiskEntries`
 * results exist, the oldest are evicted down to the cap; a `0` cap disables
 * eviction.
 *
 * Determinism: eviction runs inside [ResultStore.put] and ranks by file
 * last-modified time. We stamp each entry to a small, strictly-increasing age
 * *immediately after* its put — well below the real "now" timestamp the
 * just-written entry carries — so the entry being written is never the eviction
 * victim, and each subsequent put evicts the current oldest in insertion order.
 */
class ResultStoreRetentionTest {

    private fun putAndAge(store: ResultStore, dir: Path, id: String, age: Long) {
        store.put(StoredResult(id, ResultKind.RUN, Clock.System.now(), JsonPrimitive(id), JsonPrimitive(id)))
        Files.setLastModifiedTime(dir.resolve(id), FileTime.fromMillis(age))
    }

    private fun diskEntryCount(dir: Path): Int =
        Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.count().toInt() }

    @Test
    fun `oldest entries are evicted down to the cap`() {
        val dir = Files.createTempDirectory("rs-retention")
        val store = ResultStore(dir, maxMemoryBytes = 64L * 1024 * 1024, maxDiskEntries = 3)

        // a (oldest) .. g (newest); each put past the cap drops the current oldest.
        listOf("a", "b", "c", "d", "e", "f", "g").forEachIndexed { i, id ->
            putAndAge(store, dir, id, age = 1_000L + i)
        }

        assertEquals(3, diskEntryCount(dir), "disk must be capped at 3")
        listOf("a", "b", "c", "d").forEach { assertNull(store.get(it), "'$it' should be evicted") }
        listOf("e", "f", "g").forEach { assertNotNull(store.get(it), "'$it' should survive") }
    }

    @Test
    fun `a payload larger than the memory budget stays retrievable from disk`() {
        val dir = Files.createTempDirectory("rs-bigpayload")
        val store = ResultStore(dir, maxMemoryBytes = 8, maxDiskEntries = 0) // tiny memory budget
        val big = JsonPrimitive("x".repeat(1000)) // far exceeds the 8-byte budget
        store.put(StoredResult("big", ResultKind.RUN, Clock.System.now(), JsonPrimitive("req"), big))
        val got = store.get("big")
        assertNotNull(got, "an over-budget payload must still be retrievable from disk")
        assertEquals(big, got.payload)
    }

    @Test
    fun `a zero cap disables eviction`() {
        val dir = Files.createTempDirectory("rs-unbounded")
        val store = ResultStore(dir, maxMemoryBytes = 64L * 1024 * 1024, maxDiskEntries = 0, maxDiskBytes = 0)
        repeat(20) { store.put(StoredResult("id-$it", ResultKind.RUN, Clock.System.now(), JsonPrimitive("x"), JsonPrimitive("x"))) }
        assertEquals(20, diskEntryCount(dir))
    }

    @Test
    fun `the disk byte cap evicts oldest until under the byte budget`() {
        val dir = Files.createTempDirectory("rs-bytecap")
        val cap = 3500L
        val payload = JsonPrimitive("x".repeat(1000)) // ~1 KB per result.json
        // Count cap off, byte cap on: bytes are the sole eviction driver.
        val store = ResultStore(dir, maxMemoryBytes = 64L * 1024 * 1024, maxDiskEntries = 0, maxDiskBytes = cap)
        listOf("a", "b", "c", "d", "e", "f").forEachIndexed { i, id ->
            store.put(StoredResult(id, ResultKind.RUN, Clock.System.now(), JsonPrimitive("req"), payload))
            Files.setLastModifiedTime(dir.resolve(id), FileTime.fromMillis(1_000L + i))
        }
        val total = Files.walk(dir).use { w ->
            w.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum()
        }
        assertTrue(total <= cap, "byte cap must hold the cache at/under $cap bytes; was $total")
        assertNotNull(store.get("f"), "the newest entry must survive")
        assertNull(store.get("a"), "the oldest entry must be evicted under the byte cap")
    }

    @Test
    fun `the family index is not counted as an entry and orphaned family files are pruned on eviction`() {
        val dir = Files.createTempDirectory("rs-family")
        val store = ResultStore(dir, maxMemoryBytes = 64L * 1024 * 1024, maxDiskEntries = 2)
        putAndAge(store, dir, "old", age = 1_000L)
        store.indexFamily("fam", size = 10, resultId = "old")
        assertEquals(mapOf(10 to "old"), store.familyMembers("fam"), "member present while its result is retained")

        // Two more puts evict "old" (cap = 2); the family then has no live member.
        putAndAge(store, dir, "b", age = 2_000L)
        putAndAge(store, dir, "c", age = 3_000L)

        assertNull(store.get("old"), "'old' should be evicted")
        val realEntries = Files.list(dir).use { s ->
            s.filter { Files.isRegularFile(it.resolve("result.json")) }.count()
        }
        assertEquals(2L, realEntries, "only real result entries (not the _family index) count toward the cap")
        assertTrue(store.familyMembers("fam").isEmpty(), "an evicted member is no longer reported")
        assertTrue(
            Files.notExists(dir.resolve("_family").resolve("fam.json")),
            "an orphaned family index file should be pruned after its result is evicted",
        )
    }
}
