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
import ksl.animation.ResourceLayoutElement
import ksl.animation.style.RgbaColor
import java.awt.Color

/**
 * The Swing view of [ksl.animation.style.VisualStyle]: identical answers, expressed as `java.awt.Color`.
 *
 * The resolution rules themselves — palette assignment, the exact-then-longest-substring match for agent
 * states and processes, the substring match for resource states — now live in the shared style, so the
 * desktop canvas, the headless image renderer and a browser renderer cannot disagree about what a model
 * looks like. This class only converts the result at the toolkit boundary, which is why every existing
 * call site in `SimulationCanvas` is unchanged.
 */
class VisualStyle(layout: AnimationLayout?) {

    private val shared = ksl.animation.style.VisualStyle(layout)

    fun objectColor(typeName: String): Color = shared.objectColor(typeName).toAwt()

    fun objectShape(typeName: String): LayoutShape = shared.objectShape(typeName)

    fun objectSize(typeName: String): Double = shared.objectSize(typeName)

    fun objectImageRef(typeName: String): String? = shared.objectImageRef(typeName)

    fun objectClassNames(): List<String> = shared.objectClassNames()

    fun agentStateColorEntries(): List<Pair<String, Color>> =
        shared.agentStateColorEntries().map { it.first to it.second.toAwt() }

    fun processColorEntries(): List<Pair<String, Color>> =
        shared.processColorEntries().map { it.first to it.second.toAwt() }

    fun processColor(process: String?): Color? = shared.processColor(process)?.toAwt()

    fun agentStateColor(state: String?): Color? = shared.agentStateColor(state)?.toAwt()

    fun resourceColor(element: ResourceLayoutElement, state: String?): Color =
        shared.resourceColor(element, state).toAwt()

    fun resourceImageRef(element: ResourceLayoutElement, state: String?): String? =
        shared.resourceImageRef(element, state)

    companion object {
        /** Parses a "#rrggbb" (or "#aarrggbb") hex color; falls back to gray on malformed input. */
        fun parseColor(hex: String): Color = RgbaColor.parse(hex).toAwt()
    }
}

/** This color as an AWT color — the conversion at the Swing boundary. */
fun RgbaColor.toAwt(): Color = Color(r, g, b, a)
