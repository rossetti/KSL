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

package ksl.animation.io

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.TraceFileReader
import java.nio.file.Path

/**
 * The loaded inputs for a replay: the optional [layout] (`.lay.json`), the trace [header], and the
 * full list of [events] (`.atf`). This is the raw material the headless replay model is built from.
 */
class AnimationSource(
    val layout: AnimationLayout?,
    val header: AnimationTraceHeader,
    val events: List<AnimationEvent>,
    /** Directory the layout was loaded from, used to resolve relative image references. */
    val baseDir: Path? = null
) {
    companion object {
        /**
         * Loads a source from a trace file and an optional layout file. The trace is read fully into
         * memory (suitable for a bounded replay/window); a renderer that needs to stream a huge trace
         * could instead consume [TraceFileReader.events] lazily.
         */
        fun load(layoutFile: Path?, traceFile: Path): AnimationSource {
            val layout = layoutFile?.let { AnimationLayout.read(it) } // .lay.json or .lay.toml
            val (header, events) = TraceFileReader.readAll(traceFile)
            return AnimationSource(layout, header, events, baseDir = layoutFile?.toAbsolutePath()?.parent)
        }
    }
}
