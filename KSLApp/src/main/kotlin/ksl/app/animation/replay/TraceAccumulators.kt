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
import ksl.animation.LayoutPoint
import ksl.animation.MoverMode
import ksl.app.animation.geom.BoundingBox

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
 * The world bounding box over the FINITE coordinates carried by move events — `SpatialElementMoved` (movers) and
 * `MoveStarted` (process entities). Non-Cartesian spatial models (distance-, network-, great-circle-based) emit
 * `NaN` coordinates (see `LocationIfc.x`), so those are skipped and [result] is null when the trace carries no
 * planar coordinate at all.
 */
class ObservedExtent : TraceAccumulator<BoundingBox?> {
    private var minX = Double.POSITIVE_INFINITY
    private var minY = Double.POSITIVE_INFINITY
    private var maxX = Double.NEGATIVE_INFINITY
    private var maxY = Double.NEGATIVE_INFINITY
    private var any = false

    override fun accept(event: AnimationEvent) {
        when (event) {
            is AnimationEvent.SpatialElementMoved -> { include(event.fromX, event.fromY); include(event.toX, event.toY) }
            is AnimationEvent.MoveStarted -> { include(event.fromX, event.fromY); include(event.toX, event.toY) }
            else -> {}
        }
    }

    private fun include(x: Double, y: Double) {
        if (!x.isFinite() || !y.isFinite()) return
        any = true
        minX = minOf(minX, x); maxX = maxOf(maxX, x)
        minY = minOf(minY, y); maxY = maxOf(maxY, y)
    }

    override fun result(): BoundingBox? =
        if (!any) null else BoundingBox(minX, minY, maxX, maxY)
}

/**
 * Collects every named travel location (from `SpatialElementMoved` (movers) and `MoveStarted` (process entities)
 * location names, and `ConveyorDefined` anchors) and the running centroid of each one that reported FINITE
 * coordinates. Names with only non-Cartesian (`NaN`) coordinates are still listed but have no centroid, so the
 * caller falls back to a crude placement for them.
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
            is AnimationEvent.MoveStarted -> { // process entities move via MoveStarted (movers via SpatialElementMoved)
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
            first.getOrPut(event.name) { LayoutPoint(event.fromX, event.fromY) }
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
                val i = visits[event.entityId] ?: 0
                index.getOrPut(event.resourceName) { Avg() }.let { it.sum += i; it.n++ }
                visits[event.entityId] = i + 1
            }
            is AnimationEvent.SeizeQueued -> queueOf.getOrPut(event.resourceName) { event.queueName }
            is AnimationEvent.EntityDisposed -> visits.remove(event.entityId)
            else -> {}
        }
    }

    override fun result(): FlowOrderResult =
        FlowOrderResult(index.mapValues { (_, a) -> kotlin.math.round(a.sum / a.n).toInt() }, queueOf)
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
                val i = visits[event.entityId] ?: 0
                index.getOrPut(event.stationName) { Avg() }.let { it.sum += i; it.n++ }
                visits[event.entityId] = i + 1
            }
            is AnimationEvent.ExitedNetwork -> visits.remove(event.entityId)
            else -> {}
        }
    }

    override fun result(): Map<String, Int> = index.mapValues { (_, a) -> kotlin.math.round(a.sum / a.n).toInt() }
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
            byConveyor.getOrPut(event.conveyorName) { event.anchorLocations.zip(event.anchorCells) }
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

/**
 * Ranks named travel locations by the average position they hold in entities' movement sequences, so a layout
 * can tell which end of a venue the process starts at.
 *
 * This is [FlowOrder] for locations. The two existing flow accumulators cover resources (by seize order) and
 * network stations (by entry order), and neither answers the question a coordinate-free spatial model asks:
 * MDS places its locations faithfully but only up to rotation and reflection, so *which* end is the entrance
 * is not recoverable from the placement. It is recoverable from the trace, and this is where it comes from.
 *
 * Only entity movement counts. A mover's own travel would rank almost every location the same, because a
 * transporter goes wherever its next job is rather than following the process.
 *
 * Cycle-safe and bounded on the same terms as [FlowOrder]: an average index blends a re-entrant flow, and the
 * per-entity counter is evicted on `EntityDisposed`.
 */
class LocationFlow : TraceAccumulator<Map<String, Int>> {
    private class Avg { var sum = 0.0; var n = 0 }

    private val visits = HashMap<Long, Int>()         // entityId -> locations visited so far
    private val index = LinkedHashMap<String, Avg>()  // location -> average visit index

    override fun accept(event: AnimationEvent) {
        when (event) {
            is AnimationEvent.MoveStarted -> {
                val i = visits[event.entityId] ?: 0
                // The origin of a first move is where the entity started, so it ranks ahead of the
                // destination; afterwards only destinations advance the count.
                if (i == 0) event.fromLocationName?.let { index.getOrPut(it) { Avg() }.let { a -> a.sum += 0.0; a.n++ } }
                event.toLocationName?.let { index.getOrPut(it) { Avg() }.let { a -> a.sum += (i + 1); a.n++ } }
                visits[event.entityId] = i + 1
            }
            is AnimationEvent.EntityDisposed -> visits.remove(event.entityId)
            else -> {}
        }
    }

    override fun result(): Map<String, Int> = index.mapValues { (_, a) -> kotlin.math.round(a.sum / a.n).toInt() }
}

/**
 * The station-to-station routes **entities** actually travelled, as distinct undirected pairs in the order
 * first seen, so a layout can draw the connections between the places it has placed.
 *
 * Without these a coordinate-free model draws as elements floating in white space with nothing to say how one
 * is reached from another. Every pair here is a move that happened, so drawing them invents nothing.
 *
 * Movers are excluded for the same reason as in [LocationFlow]: a transporter travels wherever its next job
 * is, so including `SpatialElementMoved` makes the route graph nearly complete and says nothing about how the
 * system is routed.
 */
class EntityRoutes : TraceAccumulator<List<Pair<String, String>>> {
    private val seen = LinkedHashSet<Pair<String, String>>()

    override fun accept(event: AnimationEvent) {
        if (event !is AnimationEvent.MoveStarted) return
        val a = event.fromLocationName ?: return
        val b = event.toLocationName ?: return
        if (a == b) return
        seen.add(if (a <= b) a to b else b to a)   // undirected: drawing both ways only doubles the line
    }

    override fun result(): List<Pair<String, String>> = seen.toList()
}

/**
 * Each resource's capacity, as reported by the run.
 *
 * A resource draws as one cell per unit of capacity in a row centred on its position, so its half-width is
 * `capacity * size / 2`. A layout that assumes a single cell tucks a multi-server resource's queue head under
 * its own block. Capacity is a property of the run rather than of the model structure, so the trace is where
 * it comes from.
 */
class ResourceCapacities : TraceAccumulator<Map<String, Int>> {
    private val capacity = LinkedHashMap<String, Int>()

    override fun accept(event: AnimationEvent) {
        if (event is AnimationEvent.ResourceStateChanged) {
            // A resource's capacity can be changed by a schedule; the widest it ever gets is what has to fit.
            capacity[event.resourceName] = maxOf(capacity[event.resourceName] ?: 1, event.capacity.coerceAtLeast(1))
        }
    }

    override fun result(): Map<String, Int> = capacity
}

/**
 * The longest each queue ever got.
 *
 * A queue's extent line is `spacing * maxShown`, so a generous default advertises a capacity that is never
 * reached and often draws the longest line on the screen. The observed peak is the honest bound.
 */
class QueuePeaks : TraceAccumulator<Map<String, Int>> {
    private val peak = LinkedHashMap<String, Int>()

    override fun accept(event: AnimationEvent) {
        if (event is AnimationEvent.QueueLengthChanged) {
            peak[event.queueName] = maxOf(peak[event.queueName] ?: 0, event.length)
        }
    }

    override fun result(): Map<String, Int> = peak
}

/**
 * Which named location each resource is seized at — the answer to "where on the floor is this machine".
 *
 * A model that both moves entities around a venue and makes them seize resources holds this relationship
 * only implicitly: nothing in its structure says the resource `Test1` lives at the location `TestStation1`.
 * A generated layout that does not recover it draws the machines in a column off to one side while the
 * places they belong to sit somewhere else entirely, and the picture stops being a floor plan.
 *
 * The trace does know. An entity arrives somewhere and then seizes something, so the location it most
 * recently reached when it seized a given resource is where that resource is. The most frequently observed
 * location wins, which tolerates a stray seize by an entity that had wandered.
 *
 * Bounded: one location per entity in flight, evicted on `EntityDisposed`.
 */
class ResourceLocations : TraceAccumulator<Map<String, String>> {
    private val at = HashMap<Long, String>()                          // entityId -> location last reached
    private val pending = HashMap<Long, MutableList<String>>()        // seizes made before any move was seen
    private val tally = LinkedHashMap<String, MutableMap<String, Int>>() // resource -> location -> count

    private fun credit(resource: String, location: String) {
        // Not Map.merge: this file is compiled for Kotlin/JS as well as the JVM, and merge is JVM-only.
        val counts = tally.getOrPut(resource) { HashMap() }
        counts[location] = (counts[location] ?: 0) + 1
    }

    override fun accept(event: AnimationEvent) {
        when (event) {
            is AnimationEvent.MoveStarted -> {
                // The first station of a process is never reached by a move -- an entity is created there and
                // seizes before it goes anywhere -- so at seize time its whereabouts are not yet known. The
                // origin of its first move says where it had been, which is what settles those seizes. Without
                // this the first machine in every flow is the one left stranded off the floor plan.
                if (event.entityId !in at) {
                    event.fromLocationName?.let { origin ->
                        pending.remove(event.entityId)?.forEach { credit(it, origin) }
                    }
                }
                event.toLocationName?.let { at[event.entityId] = it }
            }
            is AnimationEvent.SeizeQueued -> {
                val loc = at[event.entityId]
                if (loc != null) credit(event.resourceName, loc)
                else pending.getOrPut(event.entityId) { ArrayList() }.add(event.resourceName)
            }
            is AnimationEvent.EntityDisposed -> { at.remove(event.entityId); pending.remove(event.entityId) }
            else -> {}
        }
    }

    override fun result(): Map<String, String> =
        tally.mapNotNull { (resource, counts) ->
            counts.maxByOrNull { it.value }?.let { resource to it.key }
        }.toMap()
}
