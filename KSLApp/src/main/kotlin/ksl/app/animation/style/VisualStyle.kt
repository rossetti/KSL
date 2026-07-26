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

package ksl.app.animation.style

import ksl.animation.AnimationLayout
import ksl.animation.LayoutShape
import ksl.animation.ObjectClassDefinition
import ksl.animation.ResourceLayoutElement

/**
 * Resolves how an animated object should look — its color, shape and size — from an [AnimationLayout],
 * supplying defaults wherever the layout is silent.
 *
 * Every renderer needs exactly these answers, so the rules live here rather than in any one of them. The
 * two matching rules are worth knowing because they are not plain equality:
 *
 *  - A **state** color (for an agent) and a **process** color (for an entity) match by exact name first,
 *    then by the *longest* declared key contained in the name. The longest-match tiebreak is what stops a
 *    key of "Informed" from also claiming a state called "Uninformed".
 *  - A **resource** color matches on substring, because a resource's reported state name is often
 *    qualified with the resource's own name (e.g. "Worker_Busy").
 *
 * An undeclared type gets a stable color from [RgbaColor.TAB10], assigned in first-seen order, so an
 * unstyled model still renders with distinguishable classes.
 *
 * NOTE: compiled for both the JVM and Kotlin/JS. Keep it free of JVM-only APIs.
 */
class VisualStyle(private val layout: AnimationLayout?) {

    private val objectClasses: Map<String, ObjectClassDefinition> =
        layout?.objectClasses?.associateBy { it.typeName } ?: emptyMap()

    private val agentStateColors: Map<String, String> = layout?.agentStateColors ?: emptyMap()

    private val processColors: Map<String, String> = layout?.processColors ?: emptyMap()

    private val assigned = HashMap<String, RgbaColor>()

    /** Color for an entity/agent type, from the layout or a stable auto-assigned palette color. */
    fun objectColor(typeName: String): RgbaColor {
        objectClasses[typeName]?.let { return RgbaColor.parse(it.color) }
        return assigned.getOrPut(typeName) { RgbaColor.TAB10[assigned.size % RgbaColor.TAB10.size] }
    }

    /** Drawing shape for an entity/agent type (defaults to a circle). */
    fun objectShape(typeName: String): LayoutShape = objectClasses[typeName]?.shape ?: LayoutShape.CIRCLE

    /** Drawing size (diameter, world units) for an entity/agent type. */
    fun objectSize(typeName: String): Double = objectClasses[typeName]?.size ?: DEFAULT_OBJECT_SIZE

    /** Image path for an entity/agent type whose shape is IMAGE, or null. */
    fun objectImageRef(typeName: String): String? = objectClasses[typeName]?.imageRef

    /** The declared object-class type names, for the legend. */
    fun objectClassNames(): List<String> = objectClasses.keys.toList()

    /** The agent state-color entries (state name to color), for the legend. */
    fun agentStateColorEntries(): List<Pair<String, RgbaColor>> =
        agentStateColors.map { it.key to RgbaColor.parse(it.value) }

    /** The process-color entries (process name to color), for the legend. */
    fun processColorEntries(): List<Pair<String, RgbaColor>> =
        processColors.map { it.key to RgbaColor.parse(it.value) }

    /**
     * Color for an entity currently in [process], or null when the layout declares no matching process
     * color — in which case the caller falls back to the type color.
     */
    fun processColor(process: String?): RgbaColor? = matchLongest(processColors, process)

    /**
     * Color for an agent in statechart [state], or null when the layout declares no matching state color
     * — in which case the caller falls back to the type color.
     */
    fun agentStateColor(state: String?): RgbaColor? = matchLongest(agentStateColors, state)

    /** Exact match first, then the longest declared key contained in [value]. */
    private fun matchLongest(colors: Map<String, String>, value: String?): RgbaColor? {
        if (value == null) return null
        val hex = colors.entries.firstOrNull { it.key.equals(value, ignoreCase = true) }?.value
            ?: colors.entries.filter { value.contains(it.key, ignoreCase = true) }
                .maxByOrNull { it.key.length }?.value
        return hex?.let { RgbaColor.parse(it) }
    }

    /**
     * Color for a resource in the given [state]. The reported state name may be resource-qualified (e.g.
     * "Worker_Busy"), so this matches on substring rather than equality.
     */
    fun resourceColor(element: ResourceLayoutElement, state: String?): RgbaColor = when {
        state == null -> RgbaColor.parse(element.idleColor)
        state.contains("Fail", ignoreCase = true) -> RgbaColor.parse(element.failedColor)
        state.contains("Inactive", ignoreCase = true) -> RgbaColor.parse(element.inactiveColor)
        state.contains("Busy", ignoreCase = true) -> RgbaColor.parse(element.busyColor)
        else -> RgbaColor.parse(element.idleColor)
    }

    /** The per-state image ref for [element] in [state], or null to fall back to [resourceColor]. */
    fun resourceImageRef(element: ResourceLayoutElement, state: String?): String? = when {
        state == null -> element.idleImage
        state.contains("Fail", ignoreCase = true) -> element.failedImage
        state.contains("Inactive", ignoreCase = true) -> element.inactiveImage
        state.contains("Busy", ignoreCase = true) -> element.busyImage
        else -> element.idleImage
    }

    companion object {
        /**
         * Diameter for a type the layout does not declare, matching [ObjectClassDefinition]'s own default
         * and therefore the desktop viewer.
         *
         * An earlier version scaled this to the world extent, because a trace replayed with no layout
         * drew agents a tenth of their space wide. That was treating a symptom: the real problem was that
         * no layout was being scaffolded, and a scaffold seeds every observed type with a size suited to
         * the frame. With scaffolding in place this fallback is reached only for a type that appears in
         * neither the layout nor the scaffold, and keeping it fixed means the two renderers cannot
         * disagree about how large anything is.
         */
        const val DEFAULT_OBJECT_SIZE: Double = 10.0
    }
}
