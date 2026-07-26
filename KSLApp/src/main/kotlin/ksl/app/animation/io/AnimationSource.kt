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

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader

/**
 * The loaded inputs for a replay: the optional [layout] (`.lay.json`), the trace [header], and the
 * full list of [events] (`.atf`). This is the raw material the headless replay model is built from.
 *
 * [assetBase] is what relative image references in the layout resolve against. It is a string rather
 * than a `java.nio.file.Path` so that a source can be assembled anywhere the replay layer runs — in a
 * browser the base is a URL prefix, not a directory. A JVM caller that has a directory should use
 * `AnimationSource.load`, which fills it in from the layout file's own location; a renderer converts it
 * back to whatever it needs at its own boundary.
 *
 * @param layout the presentation document, or null to replay against the trace alone
 * @param header the trace header (format version, base time unit)
 * @param events the trace's events, in time order
 * @param assetBase directory path or URL prefix that relative image references resolve against
 */
class AnimationSource(
    val layout: AnimationLayout?,
    val header: AnimationTraceHeader,
    val events: List<AnimationEvent>,
    val assetBase: String? = null
) {
    companion object
}
