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

package ksl.utilities.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observable single + multi selection model used by the editor's
 * master pane (scenario workflow §6) and any other list-or-table
 * surface that wants standard click semantics:
 *
 *  - **Click** → [select] — replaces the selection with the
 *    clicked item; sets the anchor for future range extends.
 *  - **⌘ / Ctrl-click** → [toggle] — adds or removes the item;
 *    resets the anchor.
 *  - **Shift-click** → [extend] — selects the range between the
 *    current anchor and the target within the provided ordered
 *    list.  When no anchor is set, falls back to [select].
 *
 * Generic in `T`: any equatable selection-element type works.
 * Scenarios identify rows by name or by `ScenarioId`; bundle-ref
 * panels identify rows by `bundleId`.
 *
 * Survives data mutations through [pruneRemoved]: when the
 * underlying list drops items, the editor passes the removed set
 * to drop them from the selection in one shot.
 */
class SelectionState<T : Any> {

    private val mySelection: MutableStateFlow<List<T>> = MutableStateFlow(emptyList())
    private var myAnchor: T? = null

    /** Observable selection, in the order items were added. */
    val selection: StateFlow<List<T>> = mySelection.asStateFlow()

    /** The most recently anchored item, or `null` when the selection was cleared. */
    val anchor: T? get() = myAnchor

    /**
     * Replaces the selection with just [item] and sets the anchor.
     * Standard click semantics.
     */
    fun select(item: T) {
        myAnchor = item
        mySelection.value = listOf(item)
    }

    /**
     * Toggles whether [item] is selected and resets the anchor to
     * [item] (or to null if the item was deselected to empty).
     * Standard ⌘ / Ctrl-click semantics.
     */
    fun toggle(item: T) {
        val current = mySelection.value
        if (item in current) {
            val next = current - item
            myAnchor = if (next.isEmpty()) null else next.last()
            mySelection.value = next
        } else {
            myAnchor = item
            mySelection.value = current + item
        }
    }

    /**
     * Selects the contiguous range between the current anchor and
     * [target] within [orderedItems].  When no anchor is set or
     * either endpoint isn't in the ordered list, falls back to
     * [select].  The anchor stays put so subsequent shift-clicks
     * re-extend from the same starting point.
     */
    fun extend(target: T, orderedItems: List<T>) {
        val a = myAnchor ?: return select(target)
        val anchorIdx = orderedItems.indexOf(a)
        val targetIdx = orderedItems.indexOf(target)
        if (anchorIdx < 0 || targetIdx < 0) return select(target)
        val lo = minOf(anchorIdx, targetIdx)
        val hi = maxOf(anchorIdx, targetIdx)
        mySelection.value = orderedItems.subList(lo, hi + 1).toList()
        // anchor intentionally preserved
    }

    /** Empties the selection and clears the anchor. */
    fun clear() {
        myAnchor = null
        mySelection.value = emptyList()
    }

    /**
     * Replaces the selection wholesale.  The anchor is set to the
     * last item in [items], or null when [items] is empty.
     */
    fun set(items: List<T>) {
        myAnchor = items.lastOrNull()
        mySelection.value = items.toList()
    }

    /** Whether [item] is currently selected. */
    fun isSelected(item: T): Boolean = item in mySelection.value

    /**
     * Drops every entry in [removed] from the selection in one
     * shot.  When the anchor is among the removed items, falls
     * back to the most recent surviving selection (or null if the
     * selection emptied).  Called by editors when their underlying
     * list mutates.
     */
    fun pruneRemoved(removed: Collection<T>) {
        if (removed.isEmpty()) return
        val current = mySelection.value
        val removedSet = removed.toSet()
        val survivors = current.filter { it !in removedSet }
        if (survivors.size == current.size) {
            // selection unaffected, but anchor might be
            if (myAnchor in removedSet) myAnchor = survivors.lastOrNull()
            return
        }
        mySelection.value = survivors
        if (myAnchor in removedSet) myAnchor = survivors.lastOrNull()
    }
}
