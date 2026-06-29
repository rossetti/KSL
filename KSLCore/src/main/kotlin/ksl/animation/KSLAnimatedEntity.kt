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

package ksl.animation

import java.lang.annotation.Inherited

/**
 * Marks a `ProcessModel.Entity` subclass as animatable, mirroring the `@KSLControl` idiom (Phase 10.1b).
 *
 * The type's animation identity is always the class's `simpleName` (it must equal the `EntityCreated.entityType`
 * the engine emits), so — unlike `@KSLControl` — this annotation carries **no name** field. Its sole knob is
 * [include]: set it `false` to exclude an otherwise-discovered type from the animation inventory.
 *
 * Discovery does not require this annotation (nested `Entity` subclasses and `entityType<T>()` registrations are
 * found regardless); the annotation is an explicit, idiomatic marker and the per-type opt-out.
 *
 * @property include whether to include this type in the animation inventory (default `true`)
 */
@MustBeDocumented
@Inherited
@Target(AnnotationTarget.CLASS)
annotation class KSLAnimatedEntity(
    val include: Boolean = true
)
