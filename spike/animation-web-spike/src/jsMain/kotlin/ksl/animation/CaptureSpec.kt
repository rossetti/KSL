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

import kotlinx.serialization.Serializable

/**
 * Whether the animation trace captures **all** animatable elements (the default, today's behavior) or
 * only a **selected** subset (the `include`/`exclude` lists of a [CaptureSpec]). Part of Phase 9's
 * capture/presentation split: this is the *what to capture* control, separate from the layout.
 */
enum class CaptureMode { ALL, SELECTED }

/**
 * The kinds of animatable model elements a [CaptureSpec] can select and an animation inventory can
 * enumerate. Shared vocabulary between the capture configuration ([CaptureSpec]) and the renderer's
 * element bindings, so selection and layout key off one identifier space. (`SPACE` is an agent
 * projection's spatial space; station resources are reported as `RESOURCE`.)
 */
enum class ElementKind { QUEUE, RESOURCE, RESPONSE, COUNTER, STATION, NETWORK, AGENT, CONVEYOR, MOVABLE_RESOURCE, SPACE, ENTITY_TYPE, PROCESS, LOCATION }

/**
 * Identifies one animatable element by its [kind] and trace [name] (the name it emits under). Used in a
 * [CaptureSpec]'s `include`/`exclude` lists to select what enters the trace.
 */
@Serializable
data class ElementSelector(val kind: ElementKind, val name: String) {
    init {
        require(name.isNotBlank()) { "ElementSelector.name must be non-blank" }
    }
}

/**
 * The simulated-time interval `[startTime, endTime]` during which the animation trace records events
 * (the modeler's "start at X, stop at Y"). Enforced at capture time by the `WindowedAnimationSink`; a
 * full-state keyframe is emitted at [startTime] so the window's opening frame is correct (Phase 9B).
 * `null` (no window) means the whole run is captured.
 */
@Serializable
data class CaptureWindow(val startTime: Double, val endTime: Double) {
    init {
        require(startTime >= 0.0) { "captureWindow.startTime must be >= 0.0; was $startTime" }
        require(endTime >= startTime) { "captureWindow.endTime ($endTime) must be >= startTime ($startTime)" }
    }
}

/**
 * The animation **capture** configuration (Phase 9): *what* and *when* to record, separate from the
 * presentation/layout. Two axes:
 *  - **WHAT** — [mode] is [CaptureMode.ALL] (default; every animatable element, today's behavior) or
 *    [CaptureMode.SELECTED] (only [include], minus [exclude]).
 *  - **WHEN** — [captureWindow] bounds capture to `[startTime, endTime]`; `null` = whole run.
 *
 * Defaults reproduce current behavior exactly (capture-all, no window), so embedding this in
 * `TracingConfig` is additive. The selectors are *not* matched against a model here — that author-time
 * validation is Phase 9A.5; the attachment consumes this spec to register emitters selectively (9A.4)
 * and to compose the windowed sink + opening-frame snapshot (9B).
 */
@Serializable
data class CaptureSpec(
    val mode: CaptureMode = CaptureMode.ALL,
    val include: List<ElementSelector> = emptyList(),
    val exclude: List<ElementSelector> = emptyList(),
    val captureWindow: CaptureWindow? = null
) {
    /**
     * Whether the element identified by [kind] and [name] should be captured: in [CaptureMode.ALL]
     * everything is captured except [exclude]; in [CaptureMode.SELECTED] only [include] is captured,
     * minus [exclude]. (`exclude` always wins.) Used by the trace attachment to register emitters
     * selectively (9A.4).
     */
    fun captures(kind: ElementKind, name: String): Boolean {
        if (exclude.any { it.kind == kind && it.name == name }) return false
        return mode == CaptureMode.ALL || include.any { it.kind == kind && it.name == name }
    }
}
