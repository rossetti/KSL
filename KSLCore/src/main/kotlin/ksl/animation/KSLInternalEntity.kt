/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.animation

/**
 * Marks an entity class that the library itself creates for an element, such as the entity that runs a
 * resource's behavior. The animation inventory lists such a type as internal: it is not offered to a modeler as
 * one of the model's entity types, and a trace that shows it draws no nudge to declare it, but its events are
 * captured like any other, because what it does (seizing, releasing, moving a resource) is part of what a replay
 * shows.
 */
@Target(AnnotationTarget.CLASS)
internal annotation class KSLInternalEntity
