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

package ksl.app.swing.common.editor

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 *  Tests for [CatalogLabels.featuredFirst] — the Phase B prioritization helper.
 *  It must never drop items (only reorder), float nominated items to the top in
 *  catalog priority order, and keep the non-featured tail in its given order.
 */
class CatalogLabelsTest {

    @Test
    fun `no priority keys returns the list unchanged`() {
        val items = listOf("a", "b", "c")
        val result = CatalogLabels.featuredFirst(items, emptyList()) { it }
        assertSame(items, result, "Empty priority must be a no-op (same instance).")
    }

    @Test
    fun `nominated items float to the top in priority order, rest keep order`() {
        val items = listOf("a", "b", "c", "d")
        val result = CatalogLabels.featuredFirst(items, listOf("c", "a")) { it }
        assertEquals(listOf("c", "a", "b", "d"), result)
    }

    @Test
    fun `priority keys not present in the list are ignored`() {
        val items = listOf("a", "b")
        val result = CatalogLabels.featuredFirst(items, listOf("x", "b")) { it }
        assertEquals(listOf("b", "a"), result)
    }

    @Test
    fun `never drops items - the full set is preserved`() {
        val items = listOf("a", "b", "c", "d", "e")
        val result = CatalogLabels.featuredFirst(items, listOf("e", "b")) { it }
        assertEquals(items.toSet(), result.toSet())
        assertEquals(items.size, result.size)
        assertEquals(listOf("e", "b", "a", "c", "d"), result)
    }

    @Test
    fun `works with a key extractor over objects`() {
        data class Item(val name: String, val n: Int)
        val items = listOf(Item("a", 1), Item("b", 2), Item("c", 3))
        val result = CatalogLabels.featuredFirst(items, listOf("c")) { it.name }
        assertEquals(listOf("c", "a", "b"), result.map { it.name })
    }
}
