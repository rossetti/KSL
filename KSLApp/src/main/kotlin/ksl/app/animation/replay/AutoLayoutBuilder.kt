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

import ksl.animation.AnchorRef
import ksl.animation.AnimationEvent
import ksl.animation.AnimationInventory
import ksl.animation.AnimationLayout
import ksl.animation.ConveyorLayoutElement
import ksl.animation.LayoutPoint
import ksl.animation.LocationLayoutElement
import ksl.animation.SegmentRoute
import ksl.animation.SpaceInfo
import ksl.animation.SpatialSpaceDescriptor
import ksl.animation.animationInventory
import ksl.app.animation.io.AnimationSource
import ksl.animation.scaffoldLayout
import ksl.simulation.Model

/**
 * Which source a generated auto-layout prefers.
 *  - [AUTO] — the richest available: a produced trace when it carries real (Cartesian) positions, otherwise
 *    the static model scaffold (whose MDS placement is the faithful source for coordinate-free models).
 *  - [MODEL] — the static model scaffold only.
 *
 * Relocated to KSLCore so the desktop animation app and the headless server share one auto-layout builder.
 */
enum class AutoLayoutSource { AUTO, MODEL }

/**
 * The faithful auto-layout for this model: mine [trace] (observed extent, location centroids, mover homes,
 * flow order, entity/agent types, conveyor anchors, storages) when one is supplied, [source] allows it, and it
 * carries real coordinates; otherwise the static model scaffold. In both cases stamp the model's faithful
 * geometry (obstacle maps / grid-graph), seeded object classes, located anchors, and mover homes — the static
 * detail a trace cannot carry. Always falls through to the scaffold.
 *
 * The scaffold path wins only for a coordinate-free spatial-mover model (a true `DistancesModel`): it emits NaN
 * positions, so the trace yields only a crude ring while the scaffold's MDS placement is faithful. Every other
 * model class — process / station / conveyor (no movers) and agent models (their declared space frames them) —
 * renders from the richer trace, even without planar coordinates.
 *
 * Headless (no Swing). The desktop app's `AnimationAppController.buildAutoLayout` and the MCP `auto_layout`
 * tool both delegate here, so the two produce identical layouts.
 */
fun Model.buildAutoLayout(
    trace: AnimationSource? = null,
    source: AutoLayoutSource = AutoLayoutSource.AUTO,
): AnimationLayout {
    val inventory = animationInventory()
    val fromTrace = if (source == AutoLayoutSource.AUTO && trace != null) traceAutoLayout(trace, inventory) else null
    return fromTrace ?: scaffoldAutoLayout(inventory)
}

/** The scaffold path: the static model scaffold, finished with the model-derived overlays (spaces + geometry,
 *  seeded object classes, located anchors, then mover homes last so movers anchor to the final positions). */
private fun Model.scaffoldAutoLayout(inventory: AnimationInventory): AnimationLayout =
    scaffoldLayout()
        .withScaffoldedSpaces(inventory)
        .withModelObjectClasses(inventory)
        .withModelLocations(inventory) // finalize location positions (MDS) first...
        .withModelConveyorRoutes(inventory) // ...route each conveyor's belt over its placed anchor locations...
        .withMoverPositionsAtHome(inventory) // ...so movers anchor to the final home-location position

/** The trace path: mine the trace, then stamp the model's faithful geometry + located anchors. Returns null to
 *  defer to the scaffold for a coordinate-free spatial-mover model (a true DistancesModel). */
private fun traceAutoLayout(trace: AnimationSource, inventory: AnimationInventory): AnimationLayout? {
    // One pass: planar extent (finite mover coords) + whether the model has any movers / agents.
    val extentAcc = ObservedExtent()
    var hasMovers = false
    var hasAgents = false
    for (event in trace.events) {
        extentAcc.accept(event)
        when (event) {
            is AnimationEvent.SpatialElementMoved -> hasMovers = true
            is AnimationEvent.AgentPositionChanged -> hasAgents = true
            else -> {}
        }
    }
    // Defer to the scaffold ONLY for a coordinate-free spatial-mover model (true DistancesModel): MDS beats the
    // trace's crude ring. Process/station/conveyor (no movers) and agent models render from the trace.
    if (extentAcc.result() == null && hasMovers && !hasAgents) return null
    return ReplayModel.build(trace).autoLayout(trace.events, trace.header.description)
        .withModelGeometry(inventory)
        .withModelLocations(inventory)
        .withModelConveyorRoutes(inventory)
}

// ── Model-derived overlays (moved verbatim from AnimationAppController; keyed off the inventory) ─────────────

/** Stamps the model's faithful space geometry (obstacle maps / grid-graph costs) from the inventory — the
 *  static source a trace can't carry. Shared by the scaffold and trace paths. */
internal fun AnimationLayout.withModelGeometry(inventory: AnimationInventory): AnimationLayout =
    withSpaceGeometry(inventory.spaces.mapNotNull { it.geometry })

/** Seeds an editable object-class per discovered (process) entity type, sized to the layout's spaces. Agent
 *  types aren't structural (they appear only in a trace), so the scaffold seeds entity types from the inventory;
 *  the trace path additionally seeds agent types it observes. */
internal fun AnimationLayout.withModelObjectClasses(inventory: AnimationInventory): AnimationLayout =
    withSeededObjectClasses(
        inventory.entityTypes.filter { it.include && !it.isAgent }.map { it.typeName },
        objectGlyphSize(spaces),
    )

/** Stamps a `LocationLayoutElement` for each model-declared named location with known coordinates (an agent
 *  `Context.location(...)`, finite `LocationIfc` coords, or a `DistancesModel`'s MDS placement). Inventory
 *  positions are authoritative: override a same-named layout location's position and add any that are absent.
 *  Coordinate-free names (null position) are left for MDS placement. */
fun AnimationLayout.withModelLocations(inventory: AnimationInventory): AnimationLayout {
    val known = inventory.locationInfos.mapNotNull { li ->
        val x = li.x
        val y = li.y // local vals: a cross-module nullable prop won't smart-cast
        if (x != null && y != null) li.name to LayoutPoint(x, y) else null
    }.toMap()
    if (known.isEmpty()) return this
    val have = locations.map { it.locationName }.toSet()
    val overridden = locations.map { loc -> known[loc.locationName]?.let { loc.copy(position = it) } ?: loc }
    val added = known.filterKeys { it !in have }.map { (name, p) -> LocationLayoutElement(name, p) }
    return copy(locations = overridden + added)
}

/** Routes each conveyor's belt from the model's `AnimationInventory.conveyorInfos`: fills an existing conveyor
 *  element's empty `segments` (the trace path creates the element but no route) and adds a routed element for any
 *  conveyor the layout lacks (the scaffold path creates none), so the static preview can draw the belt over its
 *  placed entry→exit anchor locations. Authored segments are kept; unnamed anchors are skipped. */
internal fun AnimationLayout.withModelConveyorRoutes(inventory: AnimationInventory): AnimationLayout {
    if (inventory.conveyorInfos.isEmpty()) return this
    val infoByName = inventory.conveyorInfos.associateBy { it.name }
    fun route(name: String): List<SegmentRoute> =
        infoByName[name]?.segments.orEmpty()
            .filter { it.entryLocation.isNotEmpty() && it.exitLocation.isNotEmpty() }
            .map { SegmentRoute(it.entryLocation, it.exitLocation) }
    val present = conveyors.map { it.conveyorName }.toSet()
    val filled = conveyors.map { c ->
        if (c.segments.isNotEmpty()) c
        else route(c.conveyorName).takeIf { it.isNotEmpty() }?.let { c.copy(segments = it) } ?: c
    }
    val added = infoByName.keys.filterNot { it in present }
        .mapNotNull { name -> route(name).takeIf { it.isNotEmpty() }?.let { ConveyorLayoutElement(conveyorName = name, segments = it, showDirection = true) } }
    return if (added.isEmpty() && filled == conveyors) this else copy(conveyors = filled + added)
}

/** Anchors each scaffolded movable resource at its home-base location's placed position (filling `homeBase`
 *  from the inventory when absent), so it renders on the static Layout tab — the scaffold otherwise declares
 *  movers with no position, leaving them visible only during replay. */
internal fun AnimationLayout.withMoverPositionsAtHome(inventory: AnimationInventory): AnimationLayout {
    if (movableResources.isEmpty()) return this
    // A mover's home base is a location (anchorPosition falls back to a station for legacy layouts); park
    // homeless movers somewhere.
    val fallback = locations.firstOrNull { it.position != null }?.position ?: stations.firstOrNull()?.position
    return copy(
        movableResources = movableResources.map { mr ->
            if (mr.position != null) return@map mr // already positioned
            val hb = mr.homeBase ?: inventory.movableHomeBases[mr.name]
            val pos = hb?.let { anchorPosition(AnchorRef.location(it)) } ?: fallback
            mr.copy(homeBase = hb ?: mr.homeBase, position = pos)
        },
    )
}

/** Ensures an agent model's space(s) are placed in the scaffolded layout (grid agents emit (col,row) cells;
 *  without the space the fit collapses them into a blob), then brings the model-linked obstacles/costs along via
 *  the shared geometry overlay. */
internal fun AnimationLayout.withScaffoldedSpaces(inventory: AnimationInventory): AnimationLayout {
    if (spaces.isNotEmpty() || inventory.spaces.isEmpty()) return this
    return copy(spaces = inventory.spaces.map { it.toDescriptor(width, height) }).withModelGeometry(inventory)
}

private fun SpaceInfo.toDescriptor(w: Double, h: Double): SpatialSpaceDescriptor =
    when (kind) {
        SpaceInfo.SpaceKind.GRID -> {
            val c = (cols ?: 1).coerceAtLeast(1)
            val r = (rows ?: 1).coerceAtLeast(1)
            val cell = minOf(w * 0.7 / c, h * 0.7 / r).coerceAtLeast(6.0)
            SpatialSpaceDescriptor.Grid(name, c, r, cell, torus = torus)
        }
        SpaceInfo.SpaceKind.CONTINUOUS -> SpatialSpaceDescriptor.Continuous(
            name, xMin ?: 0.0, xMax ?: w, yMin ?: 0.0, yMax ?: h, torus,
        )
    }
