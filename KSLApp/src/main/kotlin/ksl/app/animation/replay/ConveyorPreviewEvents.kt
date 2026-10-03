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

/**
 * Synthesizes the events that draw a model's guide paths before any run exists: one
 * [AnimationEvent.GuidedPathDefined] per guide path in the inventory ([infos]), carrying the geometry the runtime
 * emits, and each transporter placed at its home base when that names a place on the path. The layout editor's
 * preview and the server's layout image both draw from these, through the same replay and scene as a real trace,
 * so a guide path looks the same before a run as during one. A transporter with no home base on the path is
 * left unplaced rather than guessed.
 */
fun guidedPathPreviewEvents(infos: List<ksl.animation.GuidedPathInfo>): List<AnimationEvent> {
    val out = ArrayList<AnimationEvent>()
    for (info in infos) {
        out += AnimationEvent.GuidedPathDefined(
            simTime = 0.0, networkName = info.networkName, intersections = info.intersections,
            links = info.links, transporters = info.transporters, spaceName = info.spaceName
        )
        val byPlace = HashMap<String, String>()
        for (i in info.intersections) {
            byPlace[i.name] = i.name
            for (a in i.aliases) byPlace.putIfAbsent(a, i.name)
        }
        for (t in info.transporters) {
            val at = t.homeBase?.let { byPlace[it] } ?: continue
            out += AnimationEvent.GuidedTransporterMoved(
                simTime = 0.0, transporterName = t.name, networkName = info.networkName,
                zoneName = at, spaceName = info.spaceName
            )
        }
    }
    return out
}

/**
 * The styling a guide path is given when a layout has none for it: the defaults, plus the floor separation
 * auto-layout gives a path that climbs. The layout editor opens its Guide Path form on this, so accepting the
 * form unchanged draws what a scaffolded layout would.
 */
fun suggestedGuidedPathLayout(info: ksl.animation.GuidedPathInfo): ksl.animation.GuidedPathLayoutElement {
    val definition = AnimationEvent.GuidedPathDefined(
        simTime = 0.0, networkName = info.networkName, intersections = info.intersections,
        links = info.links, transporters = info.transporters, spaceName = info.spaceName
    )
    val floors = GuidedPathGeometry(info.spaceName, definition, null).suggestedFloorOffset()
        ?: return ksl.animation.GuidedPathLayoutElement(info.spaceName)
    return ksl.animation.GuidedPathLayoutElement(info.spaceName, offset = floors.second, floorOffsetPerZ = floors.first)
}
