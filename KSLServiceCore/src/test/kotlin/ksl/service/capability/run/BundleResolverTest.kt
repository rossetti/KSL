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

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure bundle-resolution rules: newest-wins, true-duplicate collapse, deterministic ties. */
class BundleResolverTest {

    private fun key(i: Int, id: String, hash: String?, builtAt: Instant?, src: String?) =
        BundleKey(i, id, hash, builtAt, src)

    private fun resolveOne(keys: List<BundleKey>, id: String) =
        BundleResolver.resolve(keys).single { it.bundleId == id }

    @Test
    fun `empty input resolves to nothing`() {
        assertTrue(BundleResolver.resolve(emptyList()).isEmpty())
    }

    @Test
    fun `distinct bundleIds are all active with no shadows`() {
        val res = BundleResolver.resolve(
            listOf(
                key(0, "a", "h0", Instant.ofEpochSecond(1), "a.jar"),
                key(1, "b", "h1", Instant.ofEpochSecond(1), "b.jar"),
            ),
        )
        assertEquals(2, res.size)
        assertTrue(res.all { it.shadowedIndices.isEmpty() })
    }

    @Test
    fun `same bundleId resolves newest builtAt wins`() {
        val r = resolveOne(
            listOf(
                key(0, "a", "old", Instant.ofEpochSecond(100), "old.jar"),
                key(1, "a", "new", Instant.ofEpochSecond(200), "new.jar"),
            ),
            "a",
        )
        assertEquals(1, r.activeIndex, "the later builtAt (index 1) must win")
        assertEquals(listOf(0), r.shadowedIndices)
    }

    @Test
    fun `null builtAt loses to a timestamped copy`() {
        // e.g. a classpath bundle (null builtAt) vs a dropped, time-stamped jar.
        val r = resolveOne(
            listOf(
                key(0, "a", "cp", null, null),
                key(1, "a", "jar", Instant.ofEpochSecond(50), "a.jar"),
            ),
            "a",
        )
        assertEquals(1, r.activeIndex, "the timestamped jar overrides the null-builtAt copy")
        assertEquals(listOf(0), r.shadowedIndices)
    }

    @Test
    fun `equal builtAt breaks deterministically by source then hash`() {
        val t = Instant.ofEpochSecond(10)
        val a = BundleResolver.resolve(
            listOf(key(0, "a", "h2", t, "zzz.jar"), key(1, "a", "h1", t, "aaa.jar")),
        ).single()
        // "aaa.jar" sorts before "zzz.jar" → index 1 wins regardless of input order.
        assertEquals(1, a.activeIndex)
    }

    @Test
    fun `true duplicates collapse to one active`() {
        val t = Instant.ofEpochSecond(10)
        val r = resolveOne(
            listOf(
                key(0, "a", "samehash", t, "one.jar"),
                key(1, "a", "samehash", t, "two.jar"),
            ),
            "a",
        )
        assertEquals(1, r.shadowedIndices.size, "the identical copy is collapsed (shadowed)")
    }
}
