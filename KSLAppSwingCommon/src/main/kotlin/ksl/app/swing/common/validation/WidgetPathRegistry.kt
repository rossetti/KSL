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

package ksl.app.swing.common.validation

import ksl.app.validation.PathParser
import javax.swing.JComponent

/**
 * Per-editor map from `FieldError.path` to the widget(s) rendering
 * that path.  Editable widgets register their path on construction
 * (typically through `FieldErrorMarker.attach`); the registry powers
 * `JumpToErrorAction` and any other "scroll to this error" affordance.
 *
 * One registry instance per open document; the editor view-model
 * owns it and calls [clear] on document close.
 *
 * Multiple widgets may register the same path (e.g. the compact and
 * the expanded forms of a control row).  All return from [findAt];
 * [findOne] returns the most-recently-registered.
 *
 * Not thread-safe — all calls expected on the Swing EDT.
 */
class WidgetPathRegistry {

    private val myEntries: MutableMap<String, MutableList<JComponent>> = linkedMapOf()

    /** Registers [component] under [path]; idempotent for the same pair. */
    fun register(path: String, component: JComponent) {
        val list = myEntries.getOrPut(path) { mutableListOf() }
        if (!list.contains(component)) list.add(component)
    }

    /**
     * Removes [component] from every path entry it appears under.
     * Empty path entries are dropped so [paths] reflects current state.
     */
    fun unregister(component: JComponent) {
        val emptyPaths = mutableListOf<String>()
        for ((path, components) in myEntries) {
            components.remove(component)
            if (components.isEmpty()) emptyPaths.add(path)
        }
        for (path in emptyPaths) myEntries.remove(path)
    }

    /** Removes every entry.  Call on document close. */
    fun clear() {
        myEntries.clear()
    }

    /** Widgets registered at exactly [path], in registration order; empty when none. */
    fun findAt(path: String): List<JComponent> =
        myEntries[path]?.toList() ?: emptyList()

    /** The most-recently-registered widget at [path], or null when none. */
    fun findOne(path: String): JComponent? =
        myEntries[path]?.lastOrNull()

    /** Widgets registered at or below [prefix], grouped by exact path. */
    fun findAtOrBelow(prefix: String): Map<String, List<JComponent>> =
        myEntries
            .filterKeys { PathParser.isAtOrBelow(prefix, it) }
            .mapValues { (_, v) -> v.toList() }

    /** All registered paths in registration order. */
    fun paths(): List<String> = myEntries.keys.toList()
}
