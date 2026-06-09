/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.modeling.station

import ksl.simulation.ModelElement

/**
 *  Configures a freshly created child QObject for a given parent. The child
 *  itself is constructed by the [ForkStation] (since `QObject` is an inner class
 *  of `ModelElement` and must be created in model-element context); this hook
 *  marks/types it, attaches a value object, etc. Called once per spawned child.
 *  Mirrors the source-station `marking` idiom.
 */
fun interface ChildFactoryIfc {
    fun configureChild(parent: ModelElement.QObject, child: ModelElement.QObject)
}

/** Returns how many children to spawn for a parent. */
fun interface ChildCountIfc {
    fun countFor(parent: ModelElement.QObject): Int
}
