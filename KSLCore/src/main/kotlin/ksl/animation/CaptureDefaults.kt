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

import ksl.simulation.Model

/**
 * Implemented by a model element that owns statistics not worth animating unless someone asks for them:
 * bookkeeping a viewer would not plot, which nonetheless changes often enough to fill a trace. A capture of
 * everything leaves the statistics named in [notCapturedByDefault] out, and including one by name (in either
 * capture mode) puts it back. Excluding is unaffected.
 *
 * The guide path's counts of events scheduled and zones traversed are the case it exists for: in an animated
 * test-and-repair shop they were a third of the trace, and nothing on a canvas is drawn from them.
 */
interface NotCapturedByDefaultIfc {

    /** Names of this element's responses and counters that a capture of everything leaves out. */
    val notCapturedByDefault: Set<String>
}

/** Every statistic the model's elements name as not captured by default; see [NotCapturedByDefaultIfc]. */
fun Model.notCapturedByDefault(): Set<String> =
    animatableModelElements().filterIsInstance<NotCapturedByDefaultIfc>().flatMap { it.notCapturedByDefault }.toSet()

/**
 * Whether the response or counter [name] of [kind] is captured: as [CaptureSpec.captures] says, except that a
 * statistic in [offByDefault] is captured only when it is included by name.
 */
fun CaptureSpec.capturesStatistic(kind: ElementKind, name: String, offByDefault: Set<String>): Boolean =
    captures(kind, name) && (name !in offByDefault || include.any { it.kind == kind && it.name == name })
