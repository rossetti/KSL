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
    val animationTraceFile: String? = null,
    val captureLevel: CaptureLevel = CaptureLevel.MINIMAL,
    val flushEveryNEvents: Int = 1000
)
