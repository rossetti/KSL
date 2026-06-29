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

package ksl.app.config

import kotlinx.serialization.Serializable
import ksl.animation.CaptureSpec
import net.peanuuutz.tomlkt.TomlComment

/**
 * Animation trace capture settings embedded in a [RunConfiguration].
 *
 * When [animationTraceFile] is `null` (the default), tracing is disabled and the
 * `AnimationTraceAttachment` is never created — zero overhead.
 *
 * @property animationTraceFile file-system path for the trace output file; `null` disables tracing
 * @property capture            what/when to capture (Phase 9); the default captures everything, no window
 * @property flushEveryNEvents  how often (in events) the trace writer should flush its buffer to disk
 */
@Serializable
data class TracingConfig(
    @TomlComment(
        "Optional filesystem path for the animation trace output file.\n" +
        "When omitted (the default), tracing is disabled and the trace\n" +
        "attachment is never created — zero runtime overhead.  Provide\n" +
        "a path to enable tracing for this document."
    )
    val animationTraceFile: String? = null,

    @TomlComment(
        "What and when to capture (Phase 9). 'mode' = ALL (default, every\n" +
        "animatable element) or SELECTED (only the include list, minus\n" +
        "exclude).  Optional 'captureWindow' = [startTime, endTime] bounds\n" +
        "capture in simulated time.  Defaults capture everything, no window."
    )
    val capture: CaptureSpec = CaptureSpec(),

    @TomlComment(
        "Integer (positive). How often (in observed events) the trace\n" +
        "writer flushes its buffer to disk.  Lower values trade I/O\n" +
        "overhead for crash-survivability of the trace.  Default: 1000."
    )
    val flushEveryNEvents: Int = 1000,

    @TomlComment(
        "Opt-in 'agent debugging / teaching' overlays (G10-G12): velocity/force\n" +
        "vectors, the flow-field gradient, and planned routes.  All off by\n" +
        "default (zero capture cost).  Records internal computation that drives\n" +
        "agent behavior; the viewer has separate show/hide toggles."
    )
    val overlays: ksl.animation.OverlaySpec = ksl.animation.OverlaySpec.OFF
) {
    init {
        require(animationTraceFile == null || animationTraceFile.isNotBlank()) {
            "animationTraceFile must be non-blank when non-null"
        }
        require(flushEveryNEvents > 0) {
            "flushEveryNEvents must be > 0; was $flushEveryNEvents"
        }
    }
}
