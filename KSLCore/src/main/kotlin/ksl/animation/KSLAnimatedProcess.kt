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

/**
 * Marks a process-valued property of a `ProcessModel.Entity` subclass as animatable, mirroring the
 * `@KSLControl` idiom (Phase 10.1b). Applied to the `val` that holds a `process("…"){ }` result:
 *
 * ```
 * @KSLAnimatedProcess(name = "Triage")                  val visit  = process("Triage") { … }
 * @KSLAnimatedProcess(name = "Rework", include = false) val rework = process("Rework") { … }  // not animated
 * ```
 *
 * A process's animation identity is the **name string** passed to `process(...)` (what `ProcessActivated`
 * carries), which is decoupled from the Kotlin property name. So [name] carries that trace-matching string;
 * when blank it defaults to the property name. [include] is the per-process capture/animation on/off switch.
 *
 * Like `@KSLControl`, this has default (`RUNTIME`) retention so it is reflectable, and discovery reads it from
 * the declaring class's properties without constructing an instance or running the model.
 *
 * @property name the process name as it appears in the trace; blank ⇒ use the property name
 * @property include whether to animate/capture this process (default `true`)
 */
@MustBeDocumented
@Target(AnnotationTarget.PROPERTY)
annotation class KSLAnimatedProcess(
    val name: String = "",
    val include: Boolean = true
)
