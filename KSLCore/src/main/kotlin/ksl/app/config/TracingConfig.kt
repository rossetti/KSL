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
import net.peanuuutz.tomlkt.TomlComment

/**
 * Animation trace capture settings embedded in a [RunConfiguration].
 *
 * When [animationTraceFile] is `null` (the default), tracing is disabled and the
 * `AnimationTraceAttachment` (Phase 7) is never created — zero overhead.
 *
 * The [CaptureLevel] taxonomy is a placeholder until Phase 7, where the full set
 * of observable events in `ksl.modeling` will be inventoried and classified.
 *
 * @property animationTraceFile file-system path for the trace output file; `null` disables tracing
 * @property captureLevel       how much detail to capture; see [CaptureLevel]
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
        "How much detail to capture when tracing is enabled.  Allowed\n" +
        "values: 'NONE', 'MINIMAL', 'STANDARD', 'DETAILED'.\n" +
        "Default: 'MINIMAL'.  Has no effect when animationTraceFile is\n" +
        "omitted."
    )
    val captureLevel: CaptureLevel = CaptureLevel.MINIMAL,

    @TomlComment(
        "Integer (positive). How often (in observed events) the trace\n" +
        "writer flushes its buffer to disk.  Lower values trade I/O\n" +
        "overhead for crash-survivability of the trace.  Default: 1000."
    )
    val flushEveryNEvents: Int = 1000
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
