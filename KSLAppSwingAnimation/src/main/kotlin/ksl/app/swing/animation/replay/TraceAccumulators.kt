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

package ksl.app.swing.animation.replay

import ksl.animation.AnimationEvent
import ksl.animation.LayoutPoint
import ksl.animation.MoverMode
import java.awt.geom.Rectangle2D

/**
 * The named locations seen in a trace ([names], including non-Cartesian ones and conveyor anchors) and
 * the centroid of every location that reported FINITE coordinates ([centroids]).
 */
data class LocationSummary(val names: Set<String>, val centroids: Map<String, LayoutPoint>)

/**
 * The movers seen in a trace ([names]) and the at-rest position of every mover that reported FINITE
 * coordinates ([homes]).
 */
data class MoverSummary(val names: Set<String>, val homes: Map<String, LayoutPoint>)

/**
 * The world bounding box over the FINITE coordinates carried by `SpatialElementMoved` events. Non-Cartesian
 * spatial models (distance-, network-, great-circle-based) emit `NaN` coordinates (see `LocationIfc.x`), so
 * those are skipped and [result] is null when the trace carries no planar coordinate at all.
 */
class ObservedExtent : TraceAccumulator<Rectangle2D.Double?> {
    private var minX = Double.POSITIVE_INFINITY
    private var minY = Double.POSITIVE_INFINITY
    private var maxX = Double.NEGATIVE_INFINITY
    private var maxY = Double.NEGATIVE_INFINITY
    private var any = false

    override fun accept(event: AnimationEvent) {
        if (event is AnimationEvent.SpatialElementMoved) {
            include(event.fromX, event.fromY)
            include(event.toX, event.toY)
        }
    }

    private fun include(x: Double, y: Double) {
        if (!x.isFinite() || !y.isFinite()) return
        any = true
        minX = minOf(minX, x); maxX = maxOf(maxX, x)
        minY = minOf(minY, y); maxY = maxOf(maxY, y)
    }

    override fun result(): Rectangle2D.Double? =
        if (!any) null else Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY)
}

/**
 * Collects every named travel location (from `SpatialElementMoved` location names and `ConveyorDefined`
 * anchors) and the running centroid of each one that reported FINITE coordinates. Names with only
 * non-Cartesian (`NaN`) coordinates are still listed but have no centroid, so the caller falls back to a
 * crude placement for them.
 */
class LocationCentroids : TraceAccumulator<LocationSummary> {
    private class Mean { var sx = 0.0; var sy = 0.0; var n = 0 }

    private val names = LinkedHashSet<String>()
    private val acc = LinkedHashMap<String, Mean>()

    override fun accept(event: AnimationEvent) {
        when (event) {
            is AnimationEvent.SpatialElementMoved -> {
                add(event.fromLocationName, event.fromX, event.fromY)
                add(event.toLocationName, event.toX, event.toY)
            }
            is AnimationEvent.ConveyorDefined -> names.addAll(event.anchorLocations)
            else -> {}
        }
    }

    private fun add(name: String?, x: Double, y: Double) {
        if (name == null) return
        names.add(name)
        if (x.isFinite() && y.isFinite()) {
            val m = acc.getOrPut(name) { Mean() }
            m.sx += x; m.sy += y; m.n++
        }
    }

    override fun result(): LocationSummary =
        LocationSummary(names, acc.mapValues { (_, m) -> LayoutPoint(m.sx / m.n, m.sy / m.n) })
}

/**
 * Collects every mover seen and its at-rest position: the destination of a `RETURNING_HOME` move if the
 * trace shows one, otherwise the mover's first finite position. Movers with only non-Cartesian (`NaN`)
 * coordinates are still listed but have no home, so they animate by name/path without a seeded glyph spot.
 */
class MoverHomes : TraceAccumulator<MoverSummary> {
    private val names = LinkedHashSet<String>()
    private val first = LinkedHashMap<String, LayoutPoint>()
    private val home = LinkedHashMap<String, LayoutPoint>()

    override fun accept(event: AnimationEvent) {
        if (event !is AnimationEvent.SpatialElementMoved) return
        names.add(event.name)
        if (event.fromX.isFinite() && event.fromY.isFinite()) {
            first.putIfAbsent(event.name, LayoutPoint(event.fromX, event.fromY))
        }
        if (event.mode == MoverMode.RETURNING_HOME && event.toX.isFinite() && event.toY.isFinite()) {
            home[event.name] = LayoutPoint(event.toX, event.toY)
        }
    }

    override fun result(): MoverSummary =
        MoverSummary(names, names.mapNotNull { n -> (home[n] ?: first[n])?.let { n to it } }.toMap())
}

/** The distinct agent states a trace reports, in first-seen order, for assigning the color palette. */
class AgentStateNames : TraceAccumulator<List<String>> {
    private val states = LinkedHashSet<String>()

    override fun accept(event: AnimationEvent) {
        if (event is AnimationEvent.AgentStateEntered) states.add(event.stateName)
    }

    override fun result(): List<String> = states.toList()
}

/**
 * The distinct **animatable** object types a trace reports: every entity type (`EntityCreated.entityType`)
 * plus the agent types that actually draw — i.e. agents that report a position via `AgentPositionChanged`. A
 * registered-but-static control agent (e.g. a dispatcher / order-generator that never moves) is excluded,
 * since it never appears on screen. These are the keys the renderer styles glyphs by, so auto-layout seeds
 * and the editor lists only genuinely animatable types (C1 / G4).
 */
class ObjectTypeNames : TraceAccumulator<Set<String>> {
    private val entityTypes = LinkedHashSet<String>()
    private val agentTypeOf = HashMap<String, String>() // agentName -> type (from AgentRegistered)
    private val movedAgents = LinkedHashSet<String>()    // agentNames that reported a position

    override fun accept(event: AnimationEvent) {
        when (event) {
            is AnimationEvent.EntityCreated -> entityTypes.add(event.entityType)
            is AnimationEvent.AgentRegistered -> agentTypeOf[event.agentName] = event.agentType
            is AnimationEvent.AgentPositionChanged -> movedAgents.add(event.agentName)
            else -> {}
        }
    }

    override fun result(): Set<String> {
        val out = LinkedHashSet(entityTypes)
        for (name in movedAgents) agentTypeOf[name]?.let { out.add(it) }
        return out
    }
}

/**
 * Resources ordered by observed process flow: [ranks] (0 = most upstream, ties = parallel servers in the
 * same stage) and [queueOfResource], each resource's queue name where the trace showed one.
 */
data class FlowOrderResult(val ranks: Map<String, Int>, val queueOfResource: Map<String, String>)

/**
 * Ranks resources by the average position they hold in entities' seize sequences, so a layout can place them
 * left-to-right in flow order rather than alphabetically. Using the average index (rather than an edge graph
 * with a longest-path rank) is cycle-safe: a re-entrant flow simply blends. Bounded: O(resources) running
 * stats plus an O(WIP) per-entity counter, evicted on `EntityDisposed` (entities with no dispose event —
 * e.g. station-network QObjects — leave a bounded residue but never affect the ranking).
 */
class FlowOrder : TraceAccumulator<FlowOrderResult> {
    private class Avg { var sum = 0.0; var n = 0 }

    private val visits = HashMap<Long, Int>()              // entityId -> seizes so far
    private val index = LinkedHashMap<String, Avg>()       // resource -> average visit index
    private val queueOf = LinkedHashMap<String, String>()  // resource -> its queue (from SeizeQueued)

    override fun accept(event: AnimationEvent) {
        when (event) {
            is AnimationEvent.SeizeAllocated -> {
                val i = visits.getOrDefault(event.entityId, 0)
                index.getOrPut(event.resourceName) { Avg() }.let { it.sum += i; it.n++ }
                visits[event.entityId] = i + 1
            }
            is AnimationEvent.SeizeQueued -> queueOf.putIfAbsent(event.resourceName, event.queueName)
            is AnimationEvent.EntityDisposed -> visits.remove(event.entityId)
            else -> {}
        }
    }

    override fun result(): FlowOrderResult =
        FlowOrderResult(index.mapValues { (_, a) -> Math.round(a.sum / a.n).toInt() }, queueOf)
}

/**
 * Network stations ranked by the average position they hold in entities' `StationEntered` sequences, so a
 * layout can place them left-to-right in observed flow order. Mirrors [FlowOrder] for the station network:
 * cycle-safe (the average index blends a re-entrant flow), bounded O(stations) plus an O(WIP) per-entity
 * counter evicted on `ExitedNetwork`.
 */
class StationFlow : TraceAccumulator<Map<String, Int>> {
    private class Avg { var sum = 0.0; var n = 0 }

    private val visits = HashMap<Long, Int>()         // entityId -> stations entered so far
    private val index = LinkedHashMap<String, Avg>()  // station -> average entry index

    override fun accept(event: AnimationEvent) {
        when (event) {
            is AnimationEvent.StationEntered -> {
                val i = visits.getOrDefault(event.entityId, 0)
                index.getOrPut(event.stationName) { Avg() }.let { it.sum += i; it.n++ }
                visits[event.entityId] = i + 1
            }
            is AnimationEvent.ExitedNetwork -> visits.remove(event.entityId)
            else -> {}
        }
    }

    override fun result(): Map<String, Int> = index.mapValues { (_, a) -> Math.round(a.sum / a.n).toInt() }
}

/**
 * Each conveyor's anchor locations paired with their cell indices (from `ConveyorDefined`), so a layout can
 * lay the belt out as a straight line spaced by cell index rather than scattering the anchors on a ring. The
 * first definition seen per conveyor wins.
 */
class ConveyorAnchors : TraceAccumulator<Map<String, List<Pair<String, Int>>>> {
    private val byConveyor = LinkedHashMap<String, List<Pair<String, Int>>>()

    override fun accept(event: AnimationEvent) {
        if (event is AnimationEvent.ConveyorDefined) {
            byConveyor.putIfAbsent(event.conveyorName, event.anchorLocations.zip(event.anchorCells))
        }
    }

    override fun result(): Map<String, List<Pair<String, Int>>> = byConveyor
}

/**
 * Storage keys to auto-place from the trace's delays (D1): every named delay (`DelayStarted.suspensionName`),
 * plus every entity type that shows a *bare* (unnamed) delay — EXCEPT types whose entities are ever seized,
 * whose bare delays are the service phase of a seize-delay-release and are already drawn inside the resource
 * glyph (a type-storage there would double-draw them). The keys match `ReplayModel`'s storage keying
 * (`suspensionName ?: entityType`), so each placed storage binds to the trace's members. Named delays are
 * always kept: naming a delay is an intentional "show this as a storage" signal.
 */
class DelayStorages : TraceAccumulator<List<String>> {
    private val entityType = HashMap<Long, String>()
    private val named = LinkedHashSet<String>()
    private val bareTypes = LinkedHashSet<String>()
    private val seizedTypes = HashSet<String>()
    private var hasAgents = false

    override fun accept(event: AnimationEvent) {
        when (event) {
            is AnimationEvent.EntityCreated -> entityType[event.entityId] = event.entityType
            is AnimationEvent.SeizeAllocated -> entityType[event.entityId]?.let { seizedTypes.add(it) }
            is AnimationEvent.AgentPositionChanged -> hasAgents = true
            is AnimationEvent.DelayStarted -> {
                val name = event.suspensionName
                if (name != null) named.add(name) else entityType[event.entityId]?.let { bareTypes.add(it) }
            }
            else -> {}
        }
    }

    // In an agent/continuous model a bare delay is a movement/steering step, not a holding area, so a
    // by-type storage there just double-draws the moving agents — drop bare-type storages when the trace
    // has agents. Named delays are always kept (naming is intentional).
    override fun result(): List<String> =
        (named + (if (hasAgents) emptySet() else bareTypes - seizedTypes)).toList()
}
