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

package ksl.app.swing.animation.view

import ksl.animation.AnimationLayout
import ksl.animation.LayoutShape
import ksl.animation.ObjectClassDefinition
import ksl.animation.ResourceLayoutElement
import java.awt.Color

/**
 * Resolves the visual appearance (colors, shapes, sizes) for animated objects from the layout's
 * [ObjectClassDefinition]s and [ResourceLayoutElement]s, with sensible defaults when the layout is
 * silent. Pure (no Swing painting), so the mapping logic stays testable.
 */
class VisualStyle(layout: AnimationLayout?) {

    private val objectClasses: Map<String, ObjectClassDefinition> =
        layout?.objectClasses?.associateBy { it.typeName } ?: emptyMap()

    private val agentStateColors: Map<String, String> = layout?.agentStateColors ?: emptyMap()

    private val processColors: Map<String, String> = layout?.processColors ?: emptyMap()

    private val defaultPalette = listOf(
        Color(0x1f77b4), Color(0xff7f0e), Color(0x2ca02c), Color(0xd62728),
        Color(0x9467bd), Color(0x8c564b), Color(0xe377c2), Color(0x17becf)
    )
    private val assigned = HashMap<String, Color>()

    /** Color for an entity/agent type, from the layout or a stable auto-assigned palette color. */
    fun objectColor(typeName: String): Color {
        objectClasses[typeName]?.let { return parseColor(it.color) }
        return assigned.getOrPut(typeName) { defaultPalette[assigned.size % defaultPalette.size] }
    }

    /** Drawing shape for an entity/agent type (defaults to a circle). */
    fun objectShape(typeName: String): LayoutShape = objectClasses[typeName]?.shape ?: LayoutShape.CIRCLE

    /** Drawing size (diameter, world units) for an entity/agent type. */
    fun objectSize(typeName: String): Double = objectClasses[typeName]?.size ?: 10.0

    /** Image path for an entity/agent type when its shape is IMAGE, or null (8I.3c). */
    fun objectImageRef(typeName: String): String? = objectClasses[typeName]?.imageRef

    /** The declared object-class type names, for the legend (8I.3a). */
    fun objectClassNames(): List<String> = objectClasses.keys.toList()

    /** The agent state-color entries (state name → color), for the legend (8I.3a). */
    fun agentStateColorEntries(): List<Pair<String, Color>> = agentStateColors.map { it.key to parseColor(it.value) }

    /** The process-color entries (process name → color), for the legend (10.1e). */
    fun processColorEntries(): List<Pair<String, Color>> = processColors.map { it.key to parseColor(it.value) }

    /**
     * Color for an entity currently in [process], or null if the layout defines no matching process color
     * (then the caller falls back to the type color). Matches by exact name first, then the longest containing
     * substring (10.1e), the entity analogue of [agentStateColor]; e.g. a current process "Triage" matches a
     * `processColors` entry keyed "Triage".
     */
    fun processColor(process: String?): Color? {
        if (process == null) return null
        val hex = processColors.entries.firstOrNull { it.key.equals(process, ignoreCase = true) }?.value
            ?: processColors.entries.filter { process.contains(it.key, ignoreCase = true) }
                .maxByOrNull { it.key.length }?.value
        return hex?.let { parseColor(it) }
    }

    /**
     * Color for an agent in statechart [state], or null if the layout defines no matching state
     * color (then the caller falls back to the type color). Matches by exact name first, then the longest
     * containing substring (8F.1), so a specific state like "Uninformed" is never captured by a shorter key
     * ("Informed") and a leaf state like "Working" still matches an `agentStateColor("Working", …)` entry.
     */
    fun agentStateColor(state: String?): Color? {
        if (state == null) return null
        val hex = agentStateColors.entries.firstOrNull { it.key.equals(state, ignoreCase = true) }?.value
            ?: agentStateColors.entries.filter { state.contains(it.key, ignoreCase = true) }
                .maxByOrNull { it.key.length }?.value
        return hex?.let { parseColor(it) }
    }

    /**
     * Color for a resource in the given [state]. The state name may be resource-qualified
     * (e.g. "Worker_Busy"), so this matches on substring rather than equality.
     */
    fun resourceColor(element: ResourceLayoutElement, state: String?): Color = when {
        state == null -> parseColor(element.idleColor)
        state.contains("Fail", ignoreCase = true) -> parseColor(element.failedColor)
        state.contains("Inactive", ignoreCase = true) -> parseColor(element.inactiveColor)
        state.contains("Busy", ignoreCase = true) -> parseColor(element.busyColor)
        else -> parseColor(element.idleColor)
    }

    /** The per-state image ref for [element] in [state] (10.7), or null to fall back to [resourceColor]. */
    fun resourceImageRef(element: ResourceLayoutElement, state: String?): String? = when {
        state == null -> element.idleImage
        state.contains("Fail", ignoreCase = true) -> element.failedImage
        state.contains("Inactive", ignoreCase = true) -> element.inactiveImage
        state.contains("Busy", ignoreCase = true) -> element.busyImage
        else -> element.idleImage
    }

    companion object {
        /** Parses a "#rrggbb" (or "#aarrggbb") hex color; falls back to gray on malformed input. */
        fun parseColor(hex: String): Color = runCatching {
            val s = hex.removePrefix("#")
            when (s.length) {
                6 -> Color(s.substring(0, 2).toInt(16), s.substring(2, 4).toInt(16), s.substring(4, 6).toInt(16))
                8 -> Color(
                    s.substring(2, 4).toInt(16), s.substring(4, 6).toInt(16),
                    s.substring(6, 8).toInt(16), s.substring(0, 2).toInt(16)
                )
                else -> Color.GRAY
            }
        }.getOrDefault(Color.GRAY)
    }
}
