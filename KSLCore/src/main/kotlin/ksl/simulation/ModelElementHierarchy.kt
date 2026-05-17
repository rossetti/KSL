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

package ksl.simulation

/**
 *  Shared helpers that walk the [ModelElement] parent chain.
 *
 *  Used by control-extraction code (`ksl.controls.Control`,
 *  `StringControl`, `JsonControl`) and by random-variable
 *  parameter extraction (`ksl.utilities.random.rvariable.parameters.
 *  RVParameterSetter`) to populate the hierarchy fields on their
 *  respective DTOs.  Module-internal — other modules should not
 *  depend on this helper directly; instead, consume the hierarchy
 *  fields already populated on the DTOs.
 *
 *  Lives in `ksl.simulation` so it has visibility into
 *  `ModelElement.parent` (also `internal`).
 */
internal object ModelElementHierarchy {

    /**
     *  Returns the parent of [element], or `null` when [element] is
     *  the Model itself.  Mirrors `ControlIfc.parentElementName`'s
     *  convention: a direct child of the Model reports the Model
     *  as its parent.
     */
    fun parentElement(element: ModelElement): ModelElement? = element.parent

    /**
     *  Walks the ancestor chain root-to-leaf and returns the names
     *  of ancestors strictly **above** [element] and **below** the
     *  Model itself.  Empty when [element] is a direct child of
     *  the Model.
     *
     *  Example: for a control attached to `Server` under
     *  `Subsystem` under the Model, returns `["Subsystem"]`.
     *  The Model is excluded; consumers can prepend `modelName`
     *  if they need a fully-qualified path.
     */
    fun elementPath(element: ModelElement): List<String> {
        // Walk from element's parent upward, stopping just before the Model.
        // The Model's parent is `null`, so any ancestor whose own `parent`
        // is `null` IS the Model and should not appear in the path.
        val acc = ArrayDeque<String>()
        var p: ModelElement? = element.parent
        while (p?.parent != null) {
            acc.addFirst(p.name)
            p = p.parent
        }
        return acc.toList()
    }
}
