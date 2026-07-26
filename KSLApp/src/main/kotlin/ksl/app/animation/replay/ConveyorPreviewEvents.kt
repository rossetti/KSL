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

package ksl.app.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.ConveyorInfo

/*
 * Kept apart from ReplayModel deliberately.
 *
 * This synthesizes conveyor-structure events from a *model's* inventory so the layout editor can preview
 * a belt before any run exists. That makes it an authoring helper, not part of replay: the only callers
 * are the desktop editor and its tests, and a replay renderer never reaches for it.
 *
 * It matters where it lives because ReplayModel is compiled for the browser as well, and this function's
 * ConveyorInfo parameter comes from AnimationInventory -- a type that walks a built Model by reflection.
 * Leaving it in the same file would drag that dependency into a web build for the benefit of code the web
 * never runs.
 */
/**
 * Synthesizes [AnimationEvent.ConveyorDefined] events from the inventory's conveyor structure ([infos]) so the
 * static Layout-tab preview can draw the belt cells without a trace (E2). Each conveyor's chained segments become
 * ordered anchor locations at cumulative cell indices — the same shape the runtime emits — so the existing
 * ConveyorDefined handler resolves those anchors against the layout's placed locations/stations and builds the
 * belt geometry. Conveyors whose anchor places aren't placed in the layout simply resolve to nothing (no belt).
 */
fun conveyorDefinedEvents(infos: List<ConveyorInfo>): List<AnimationEvent.ConveyorDefined> = infos.mapNotNull { info ->
    if (info.segments.isEmpty()) return@mapNotNull null
    val locs = ArrayList<String>()
    val cells = ArrayList<Int>()
    var cell = 0
    info.segments.forEachIndexed { i, seg ->
        if (i == 0) { locs.add(seg.entryLocation); cells.add(0) }
        cell += seg.lengthCells
        locs.add(seg.exitLocation); cells.add(cell)
    }
    AnimationEvent.ConveyorDefined(simTime = 0.0, conveyorName = info.name, anchorLocations = locs, anchorCells = cells)
}
