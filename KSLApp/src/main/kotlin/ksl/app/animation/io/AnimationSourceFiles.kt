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

package ksl.app.animation.io

import ksl.animation.AnimationLayout
import ksl.animation.TraceFileReader
import ksl.animation.read
import java.nio.file.Path

/**
 * File loading for [AnimationSource], kept apart from the source itself.
 *
 * `AnimationSource` is plain data so that it can be assembled wherever the replay layer runs, including
 * a browser that fetches its trace over HTTP. Reading files is platform-bound, so it lives here. This is
 * a same-package extension on the companion, so `AnimationSource.load(layoutFile, traceFile)` continues
 * to compile and mean what it did before.
 */

/**
 * Loads a source from a trace file and an optional layout file. The trace is read fully into memory
 * (suitable for a bounded replay/window); a caller that needs to stream a huge trace can instead consume
 * [TraceFileReader.events] lazily and construct an [AnimationSource] directly.
 *
 * The layout file's own directory becomes the source's asset base, so relative image references in the
 * layout resolve next to the layout rather than next to the working directory.
 */
fun AnimationSource.Companion.load(layoutFile: Path?, traceFile: Path): AnimationSource {
    val layout = layoutFile?.let { AnimationLayout.read(it) } // .lay.json or .lay.toml
    val (header, events) = TraceFileReader.readAll(traceFile)
    return AnimationSource(
        layout, header, events,
        assetBase = layoutFile?.toAbsolutePath()?.parent?.toString()
    )
}
