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
import kotlinx.serialization.encodeToString

/**
 * The first record of every animation trace (`.atf`) file. It identifies the
 * format generation and records run-level context the renderer needs before it
 * begins interpreting [AnimationEvent] records.
 *
 * The header is written as the first line of the file by the trace writer
 * (Phase 0.5) and is distinguishable from event lines because it has no `"event"`
 * discriminator field. A reader consumes line 1 as the header, checks
 * [formatVersion] against the version it understands, then reads the remaining
 * lines as events.
 *
 * @property formatVersion the `.atf` format generation; see [AnimationEvent.FORMAT_VERSION]
 * @property baseTimeUnit the model's base time unit (e.g. "MINUTE"), so the
 *           renderer can label times; `null` if unknown at write time
 * @property kslVersion the KSL version that produced the trace, for diagnostics; may be `null`
 * @property description an optional free-text label for the trace (e.g. the model name)
 */
@Serializable
data class AnimationTraceHeader(
    val formatVersion: Int = AnimationEvent.FORMAT_VERSION,
    val baseTimeUnit: String? = null,
    val kslVersion: String? = null,
    val description: String? = null
) {
    /** Serializes this header to a single-line JSON string (the first `.atf` record). */
    fun encodeToLine(): String = AnimationEvent.format.encodeToString(this)

    companion object {
        /** Parses the first `.atf` record [line] back into a header. */
        fun decodeFromLine(line: String): AnimationTraceHeader =
            AnimationEvent.format.decodeFromString(line)
    }
}
