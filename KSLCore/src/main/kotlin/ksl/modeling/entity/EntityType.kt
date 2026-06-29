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

package ksl.modeling.entity

import ksl.simulation.ModelElement
import kotlin.reflect.KClass

/**
 * A declared, animatable entity **type** (Phase 10.1a).
 *
 * `EntityType` is a no-behavior [ModelElement] whose [name] is the simple class name of a
 * [ProcessModel.Entity] subclass — exactly the label the engine emits in `EntityCreated.entityType`. Declaring
 * one surfaces the type to the animation inventory (editor pick-lists + author-time validation) *before any
 * run*, without affecting the simulation. Because it is a `ModelElement`, it flows through
 * `Model.getModelElements()` like every other animatable element, so the inventory and capture machinery find it
 * with no special-casing.
 *
 * Create instances with [entityType] rather than calling the constructor directly, so the name is derived from
 * the type and matches the trace by construction.
 *
 * @property entityClass the declared `Entity` subclass. Retained so a later phase can reflect this class's
 *   `@KSLAnimatedProcess` members rooted at it (Phase 10.1b); unused by 10.1a beyond carrying the type.
 */
class EntityType @PublishedApi internal constructor(
    parent: ProcessModel,
    name: String,
    val entityClass: KClass<out ProcessModel.Entity>
) : ModelElement(parent, name)

/**
 * Declares the entity subclass [E] as animatable, returning the [EntityType] element registered under this
 * [ProcessModel].
 *
 * The element's name is `E::class.simpleName`, which equals the `javaClass.simpleName` the engine emits in
 * `EntityCreated` — so it matches the trace **by construction** (no strings, no drift). Declaration is optional:
 * undeclared types still appear in the trace and can be styled after a run; declaring a type merely makes it
 * available in the inventory before a run.
 *
 * ```
 * class Clinic(parent: ModelElement) : ProcessModel(parent, "clinic") {
 *     val patient = entityType<Patient>()      // surfaces "Patient" to the animation inventory pre-run
 *     inner class Patient : Entity()
 * }
 * ```
 *
 * @throws IllegalArgumentException if [E] is anonymous (has no simple name)
 */
inline fun <reified E : ProcessModel.Entity> ProcessModel.entityType(): EntityType =
    EntityType(
        this,
        requireNotNull(E::class.simpleName) { "Cannot declare an anonymous entity class as an entityType" },
        E::class
    )
