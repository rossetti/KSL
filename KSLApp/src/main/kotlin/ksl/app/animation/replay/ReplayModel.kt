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
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutPoint
import ksl.animation.NetworkEdge
import ksl.animation.NetworkNode
import ksl.animation.SpatialSpaceDescriptor
import ksl.app.animation.geom.BoundingBox
import ksl.app.animation.io.AnimationSource

/** A resource's state at a point in time. */
data class ResourceSnapshot(val state: String, val busyUnits: Int, val capacity: Int)

/** The bounds of a toroidal (edge-wrapping) space, for wrap-aware agent motion/drawing (8F.7). */
data class TorusBounds(val xMin: Double, val yMin: Double, val width: Double, val height: Double) {
    private fun mod(a: Double, m: Double) = if (m <= 0.0) a else ((a % m) + m) % m
    /** Wraps [p] into the toroidal bounds. */
    fun wrap(p: WorldPoint): WorldPoint = WorldPoint(xMin + mod(p.x - xMin, width), yMin + mod(p.y - yMin, height), p.z)
}

/** A response's within-replication statistics at a point in time (NaN where unavailable). */
data class ResponseStats(val count: Double, val average: Double, val min: Double, val max: Double)

/** What an entity is doing, so it can be drawn in a queue or inside a resource rather than in free space. */
enum class EntityActivityKind { IN_QUEUE, IN_SERVICE, FREE }

/** An entity occupying a storage at a point in time, with the delay window for progress (8K.4). */
data class StorageMember(val entityId: Long, val startTime: Double, val arrivalTime: Double)

/** A network entity's transit between two stations over [tStart, tEnd] (8I.4); [via] carries an authored path's
 *  intermediate waypoints when one exists (else empty = straight line). */
data class TransitSegment(
    val tStart: Double, val from: WorldPoint, val tEnd: Double, val to: WorldPoint,
    val via: List<WorldPoint> = emptyList()
)

/** Bookkeeping for an entity's currently-open delay, so [DelayEnded] can find its storage key (8K.4). */
internal data class OpenDelay(val key: String, val member: StorageMember)

/** An entity's current association with a resource/queue (from seize/allocate/release events). */
data class EntityActivity(
    val kind: EntityActivityKind,
    val resourceName: String = "",
    val queueName: String = ""
)

/** An entity's existence over the run (positions are added in a later step). */
class EntityTrack(val id: Long, val typeName: String, val createTime: Double) {
    /** When the entity left (disposed/terminated); [Double.POSITIVE_INFINITY] = still present at trace end. */
    var endTime: Double = Double.POSITIVE_INFINITY

    fun existsAt(t: Double): Boolean = t >= createTime && t <= endTime
}

/**
 * A station-network entity (a `QObject`, not a `ProcessModel.Entity`, so it has no `EntityCreated`).
 * Existence spans `EnteredNetwork → ExitedNetwork`; [station] holds the name of the station it is
 * currently at (empty between stations), used to place it at that station's layout position (8G.2).
 */
class NetworkEntityTrack(val id: Long, val createTime: Double) {
    var endTime: Double = Double.POSITIVE_INFINITY
    val station: StepTimeline<String> = StepTimeline()

    /** The QObject's integer type id and resolved class name (8G.1), set at EnteredNetwork. */
    var typeId: Int = 1
    var typeName: String? = null

    fun existsAt(t: Double): Boolean = t >= createTime && t <= endTime
}

/**
 * Maps a conveyor cell index to a world position by linearly interpolating between named anchor
 * cells (from [AnimationEvent.ConveyorDefined], resolved against the layout's station positions).
 * Lets the renderer place a conveyed item from its cell index along the belt geometry (8G.6).
 */
/**
 * Maps a conveyor cell index to a world position. Each chained segment (between consecutive named anchors)
 * carries a polyline `entry → waypoints → exit`; a cell in that segment's range is placed along the polyline
 * **by arc length** (10.5c). With no authored route, a segment is the straight `entry → exit` line — identical
 * to the previous anchor-to-anchor interpolation.
 */
/** A movable/transport resource's animation state at an instant (10.8/C2): why it's moving and what it carries. */
data class MoverState(val mode: ksl.animation.MoverMode, val carriedEntityId: Long?, val carriedEntityType: String?)

class ConveyorGeometry private constructor(private val segments: List<Seg>) {
    private class Seg(val startCell: Int, val endCell: Int, val poly: List<WorldPoint>)

    val isEmpty: Boolean get() = segments.isEmpty()

    fun positionAt(cell: Int): WorldPoint? {
        if (segments.isEmpty()) return null
        if (cell <= segments.first().startCell) return segments.first().poly.first()
        if (cell >= segments.last().endCell) return segments.last().poly.last()
        val seg = segments.firstOrNull { cell in it.startCell..it.endCell } ?: return null
        val span = seg.endCell - seg.startCell
        return pointAlongPolyline(seg.poly, if (span <= 0) 0.0 else (cell - seg.startCell).toDouble() / span)
    }

    companion object {
        /** Builds geometry from cell-indexed named anchors and an optional authored [route] (its waypoints). */
        fun build(anchors: List<Triple<String, Int, WorldPoint>>, route: ksl.animation.ConveyorLayoutElement?): ConveyorGeometry {
            val sorted = anchors.sortedBy { it.second }
            val segs = ArrayList<Seg>()
            for (i in 0 until sorted.size - 1) {
                val (locA, cellA, pA) = sorted[i]
                val (locB, cellB, pB) = sorted[i + 1]
                val wps = route?.segments?.firstOrNull { it.entryLocation == locA && it.exitLocation == locB }
                    ?.waypoints?.map { WorldPoint(it.x, it.y, 0.0) } ?: emptyList()
                segs.add(Seg(cellA, cellB, listOf(pA) + wps + listOf(pB)))
            }
            if (segs.isEmpty() && sorted.size == 1) sorted[0].let { segs.add(Seg(it.second, it.second, listOf(it.third))) }
            return ConveyorGeometry(segs)
        }
    }
}

/**
 * A time-queryable model of a run, built once from an [AnimationSource] by indexing the trace into
 * timelines. Every query ([queueLengthAt], [resourceStateAt], [responseValueAt], [entitiesAt]) is
 * O(log n) in the number of samples, so the renderer can seek to any time instantly (smooth
 * scrubbing) and play forward by repeated querying. The model is immutable after [build], so the
 * Swing layer reads it from the EDT without locking.
 *
 * Entity positions are not yet modeled here; they are added with the position interpolator in the
 * next step.
 */
class ReplayModel(
    val layout: AnimationLayout?,
    val header: AnimationTraceHeader,
    val timeRange: ClosedRange<Double>,
    private val entities: Map<Long, EntityTrack>,
    private val queues: Map<String, StepTimeline<Int>>,
    private val queueMembers: Map<String, StepTimeline<List<Long>>>,
    private val resourceQueueMembers: Map<String, StepTimeline<List<Long>>>,
    private val resources: Map<String, StepTimeline<ResourceSnapshot>>,
    private val resourceUnits: Map<String, StepTimeline<List<Long>>>,
    private val responses: Map<String, StepTimeline<Double>>,
    private val responseStats: Map<String, StepTimeline<ResponseStats>>,
    private val entityMotion: Map<Long, MotionTrack>,
    private val spatialMotion: Map<String, MotionTrack>,
    private val agentMotion: Map<String, MotionTrack>,
    private val agentTypes: Map<String, String>,
    private val entityActivity: Map<Long, StepTimeline<EntityActivity>>,
    private val entityProcess: Map<Long, StepTimeline<String>>,
    private val networkEntities: Map<Long, NetworkEntityTrack>,
    private val networkTransit: Map<Long, List<TransitSegment>>,
    private val agentFirstSeen: Map<String, Double>,
    private val agentRemovedAt: Map<String, Double>,
    private val agentState: Map<String, StepTimeline<String>>,
    private val derivedSpaces: List<SpatialSpaceDescriptor>,
    private val storageMembers: Map<String, StepTimeline<List<StorageMember>>>,
    private val entityStorage: Map<Long, StepTimeline<String>>,
    private val conveyorBlocked: Map<Long, StepTimeline<String>>,
    private val conveyorGeom: Map<String, ConveyorGeometry>,
    private val conveyorMaxCell: Map<String, Int>,
    private val conveyorOccupied: Map<String, StepTimeline<Set<Int>>>,
    /** Per movable/transport resource: its mode + carried entity over time (10.8/C2). */
    private val moverStates: Map<String, StepTimeline<MoverState>>,
    /** The toroidal space bounds, if any, for wrap-aware agent drawing (8F.7). */
    val torusBounds: TorusBounds? = null,
    /** Where relative image references resolve against: a directory path, or a URL prefix in a browser. */
    val assetBase: String? = null,
    /** Flow-field gradient overlays captured in the trace (G11); empty unless the overlay was enabled. */
    val flowFieldOverlays: List<AnimationEvent.FlowFieldDefined> = emptyList(),
    /** Per-agent planned routes over time (G12); empty unless the overlay was enabled. */
    private val plannedPaths: Map<String, StepTimeline<List<WorldPoint>>> = emptyMap(),
    /** Per-agent sampled velocity/force vectors over time (G10); empty unless the overlay was enabled. */
    private val agentVectors: Map<String, StepTimeline<AnimationEvent.AgentVectorSampled>> = emptyMap(),
    /** Transient location highlights (G-animated); empty unless the overlay was enabled. */
    private val markerPulses: List<AnimationEvent.MarkerPulsed> = emptyList()
) {
    /** Spaces to draw as the backdrop: the layout's authored spaces, or — if it declares none — the
     *  spaces derived from the trace's `SpaceDefined` events (8K.6a). */
    val effectiveSpaces: List<SpatialSpaceDescriptor>
        get() = layout?.spaces?.takeIf { it.isNotEmpty() } ?: derivedSpaces

    val queueNames: Set<String> get() = queues.keys
    val resourceNames: Set<String> get() = resources.keys
    val responseNames: Set<String> get() = responses.keys
    val entityCount: Int get() = entities.size

    /** Queue length at [t] (0 before the first observation). */
    fun queueLengthAt(name: String, t: Double): Int = queues[name]?.valueAt(t) ?: 0

    /**
     * The identified members (entity ids) of a queue at [t], in arrival order (8C.2), or empty. For a
     * resource's `RequestQ` the seize-stream membership (entity ids, 8I.1b) takes precedence over the
     * `QObjectEnqueued` membership (which would carry the Request's id, rendering anonymous).
     */
    fun queueMembersAt(name: String, t: Double): List<Long> =
        resourceQueueMembers[name]?.valueAt(t) ?: queueMembers[name]?.valueAt(t) ?: emptyList()

    /** An entity's type name (from EntityCreated; null for un-typed station QObjects). */
    fun entityTypeOf(id: Long): String? = entities[id]?.typeName

    /** The entity's current process at [t] (from ProcessActivated; null between processes or unknown) (10.1e). */
    fun entityProcessAt(id: Long, t: Double): String? =
        entityProcess[id]?.valueAt(t)?.takeIf { it.isNotEmpty() }

    /**
     * A single label for what the entity is doing at [t] (10.1e Tier 2), by precedence: the current named
     * process, else in-service `"service:<resource>"`, else waiting `"queue:<queue>"`, else `"delay:<key>"`,
     * else `"idle"`. Aggregates the existing activity queries so styling/legends key off one string.
     */
    fun entityActivityLabelAt(id: Long, t: Double): String =
        entityProcessAt(id, t)
            ?: entityServiceResourceAt(id, t)?.let { "service:$it" }
            ?: entityQueueAt(id, t)?.let { "queue:$it" }
            ?: entityStorageAt(id, t)?.let { "delay:$it" }
            ?: "idle"

    /** Resource state at [t], or null if unknown at [t]. */
    fun resourceStateAt(name: String, t: Double): ResourceSnapshot? = resources[name]?.valueAt(t)

    /** The entity id occupying each busy unit of a resource at [t], in allocation order (8C.3). */
    fun resourceUnitsAt(name: String, t: Double): List<Long> = resourceUnits[name]?.valueAt(t) ?: emptyList()

    /** Most recent observed value of a response/counter at [t], or null if none yet. */
    fun responseValueAt(name: String, t: Double): Double? = responses[name]?.valueAt(t)

    /** The series of samples of [name] up to [t] (for plotting). */
    fun responseSamplesUpTo(name: String, t: Double): List<Pair<Double, Double>> =
        responses[name]?.samplesUpTo(t) ?: emptyList()

    /** The response's within-replication statistics at [t] (D11), or null if none observed yet (8A.4). */
    fun responseStatsAt(name: String, t: Double): ResponseStats? = responseStats[name]?.valueAt(t)

    /** Entities that exist at [t]. */
    fun entitiesAt(t: Double): List<EntityTrack> = entities.values.filter { it.existsAt(t) }

    /** The interpolated position of an entity (by id) at [t], or null if it has no moves. */
    fun entityPositionAt(id: Long, t: Double): WorldPoint? = entityMotion[id]?.positionAt(t)

    /** The interpolated position of a movable/transport resource (by name) at [t], or null (8K.5). */
    fun spatialElementPositionAt(name: String, t: Double): WorldPoint? = spatialMotion[name]?.positionAt(t)

    /** The movable resource [name]'s mode + carried entity at time [t] (10.8/C2), or null if unknown/at rest. */
    fun moverStateAt(name: String, t: Double): MoverState? = moverStates[name]?.valueAt(t)

    /** Names of the movable/transport resources that moved in this trace. */
    val spatialElementNames: Set<String> get() = spatialMotion.keys

    /**
     * Bounding box (world/layout coordinates) of all coordinate-based movement — process entities and
     * movable/transport resources — or null when there is none. The renderer unions this with the layout
     * bounds so continuous-space movers whose coordinates fall outside the authored canvas (Regime B) are
     * still framed on-screen. Agents are excluded — they have their own space-aware (grid/torus) placement.
     */
    fun coordinateBounds(): BoundingBox? {
        var box: BoundingBox? = null
        for (track in entityMotion.values.asSequence() + spatialMotion.values.asSequence()) {
            box = BoundingBox.union(box, track.bounds())
        }
        return box
    }

    /** The interpolated position of an agent (by name) at [t], or null if it has no samples. */
    fun agentPositionAt(name: String, t: Double): WorldPoint? = agentMotion[name]?.positionAt(t)

    /**
     * Approximate agent velocity (world units / time) at [t] by central difference on its motion
     * track, or null if it has no samples or is effectively stationary (for heading display, 8F.5).
     */
    fun agentVelocityAt(name: String, t: Double, h: Double = 1e-3): WorldPoint? {
        val track = agentMotion[name] ?: return null
        val a = track.positionAt(t - h) ?: return null
        val b = track.positionAt(t + h) ?: return null
        val vx = (b.x - a.x) / (2 * h)
        val vy = (b.y - a.y) / (2 * h)
        return if (vx * vx + vy * vy < 1e-9) null else WorldPoint(vx, vy)
    }

    /** Names of agents that have recorded positions. */
    val agentNames: Set<String> get() = agentMotion.keys

    /** The agent's type (its class name from [AnimationEvent.AgentRegistered]), or null if unknown. */
    fun agentTypeOf(name: String): String? = agentTypes[name]

    /** The agent's current statechart leaf state at [t] (from AgentStateEntered), or null (8F.1). */
    fun agentStateAt(name: String, t: Double): String? =
        agentState[name]?.valueAt(t)?.takeIf { it.isNotEmpty() }

    /** The route planned for agent [name] active at time [t], or null — the G12 overlay. */
    fun plannedPathAt(name: String, t: Double): List<WorldPoint>? = plannedPaths[name]?.valueAt(t)

    /** Agents that have any planned route in this trace (G12). */
    val agentsWithPaths: Set<String> get() = plannedPaths.keys

    /** The sampled velocity/force vectors for agent [name] at time [t], or null — the G10 overlay. */
    fun agentVectorAt(name: String, t: Double): AnimationEvent.AgentVectorSampled? = agentVectors[name]?.valueAt(t)

    /** Agents that have any sampled velocity/force vectors in this trace (G10). */
    val agentsWithVectors: Set<String> get() = agentVectors.keys

    /** A marker pulse active at a replay instant, with its [progress] (0 at fire → 1 at fade-out) — G-animated. */
    data class ActivePulse(
        val x: Double, val y: Double, val progress: Double, val label: String?, val colorHex: String?
    )

    /** Whether this trace carries any marker-pulse highlights (G-animated). */
    val hasMarkerPulses: Boolean get() = markerPulses.isNotEmpty()

    /**
     * The marker pulses "live" at time [t] — those whose window `[simTime, simTime + holdTime]` contains [t]
     * — each with a 0→1 progress the renderer uses to expand and fade the ring (G-animated).
     */
    fun markerPulsesActiveAt(t: Double): List<ActivePulse> {
        if (markerPulses.isEmpty()) return emptyList()
        val out = ArrayList<ActivePulse>()
        for (p in markerPulses) {
            val hold = if (p.holdTime > 0.0) p.holdTime else 1.0
            val dt = t - p.simTime
            if (dt < 0.0 || dt > hold) continue
            out.add(ActivePulse(p.x, p.y, (dt / hold).coerceIn(0.0, 1.0), p.label, p.colorHex))
        }
        return out
    }

    /**
     * Whether an agent should be drawn at [t]: present from its first recorded position until it is
     * removed ([AnimationEvent.AgentRemoved]). Stops departed agents from ghosting at their last
     * position (8F.2), and keeps a not-yet-spawned agent hidden.
     */
    fun agentPresentAt(name: String, t: Double): Boolean {
        val seen = agentFirstSeen[name] ?: return false
        if (t < seen) return false
        val removed = agentRemovedAt[name]
        return removed == null || t < removed
    }

    /** The resource an entity is in service at [t] (allocated, not yet released), or null. */
    fun entityServiceResourceAt(id: Long, t: Double): String? =
        entityActivity[id]?.valueAt(t)?.takeIf { it.kind == EntityActivityKind.IN_SERVICE }?.resourceName

    /** The queue an entity is waiting in at [t] (seize enqueued, awaiting allocation), or null. */
    fun entityQueueAt(id: Long, t: Double): String? =
        entityActivity[id]?.valueAt(t)?.takeIf { it.kind == EntityActivityKind.IN_QUEUE }?.queueName

    /** The members of the storage [key] (a suspension or process name) at [t], for drawing (8K.4). */
    fun storageMembersAt(key: String, t: Double): List<StorageMember> =
        storageMembers[key]?.valueAt(t) ?: emptyList()

    /** The storage key an entity is currently delaying under at [t] (suspension/process name), or null (8K.4). */
    fun entityStorageAt(id: Long, t: Double): String? =
        entityStorage[id]?.valueAt(t)?.takeIf { it.isNotEmpty() }

    /** Ids of station-network entities present at [t] (EnteredNetwork..ExitedNetwork). */
    fun networkEntitiesAt(t: Double): List<Long> =
        networkEntities.values.filter { it.existsAt(t) }.map { it.id }

    /** The station a network entity is currently at, or null if between stations / unknown (8G.2). */
    fun entityStationAt(id: Long, t: Double): String? =
        networkEntities[id]?.station?.valueAt(t)?.takeIf { it.isNotEmpty() }

    /** A network entity's class name for styling (8G.1), or null when no class is registered. */
    fun networkEntityTypeOf(id: Long): String? = networkEntities[id]?.typeName

    /** A network entity's interpolated position while between stations on a timed transfer, else null (8I.4). */
    fun networkEntityTransitAt(id: Long, t: Double): WorldPoint? {
        val seg = networkTransit[id]?.firstOrNull { t >= it.tStart && t < it.tEnd } ?: return null
        val f = ((t - seg.tStart) / (seg.tEnd - seg.tStart)).coerceIn(0.0, 1.0)
        if (seg.via.isEmpty()) {
            return WorldPoint(
                seg.from.x + f * (seg.to.x - seg.from.x),
                seg.from.y + f * (seg.to.y - seg.from.y),
                seg.from.z + f * (seg.to.z - seg.from.z)
            )
        }
        return pointAlongPolyline(listOf(seg.from) + seg.via + seg.to, f)
    }

    /** The conveyor entry location an entity is blocked at (waiting to board) at [t], else null (8G.8). */
    fun entityBlockedLocationAt(id: Long, t: Double): String? =
        conveyorBlocked[id]?.valueAt(t)?.takeIf { it.isNotEmpty() }

    /** Names of conveyors that declared their geometry (8G.6/8G.9). */
    val conveyorNames: Set<String> get() = conveyorGeom.keys

    /** The world position of cell [cell] on conveyor [name], or null if unknown. */
    fun conveyorCellPosition(name: String, cell: Int): WorldPoint? = conveyorGeom[name]?.positionAt(cell)

    /** The highest cell index on conveyor [name] (its belt length in cells). */
    fun conveyorMaxCellOf(name: String): Int = conveyorMaxCell[name] ?: 0

    /** The set of occupied cell indices on conveyor [name] at [t] (8G.9), derived from item moves. */
    fun conveyorOccupiedCellsAt(name: String, t: Double): Set<Int> = conveyorOccupied[name]?.valueAt(t) ?: emptySet()

    companion object {
        /** Builds the model by a single pass over the source's events, indexing into timelines. */
        fun build(source: AnimationSource): ReplayModel {
            val entities = LinkedHashMap<Long, EntityTrack>()
            val queues = LinkedHashMap<String, StepTimeline<Int>>()
            val queueMembers = LinkedHashMap<String, StepTimeline<List<Long>>>()
            val runningQueueMembers = HashMap<String, MutableList<Long>>()
            // Typed resource-queue members from the seize stream (8I.1b): entity ids, not Request ids.
            val resourceQueueMembers = LinkedHashMap<String, StepTimeline<List<Long>>>()
            val runningSeizeQueue = HashMap<String, MutableList<Long>>()
            val entityWaitingQueue = HashMap<Long, String>() // entity -> the queue it is waiting in
            val resources = LinkedHashMap<String, StepTimeline<ResourceSnapshot>>()
            val resourceUnits = LinkedHashMap<String, StepTimeline<List<Long>>>()
            val runningResourceUnits = HashMap<String, MutableList<Long>>()
            val responses = LinkedHashMap<String, StepTimeline<Double>>()
            val responseStats = LinkedHashMap<String, StepTimeline<ResponseStats>>()
            val entityMotion = LinkedHashMap<Long, MotionTrack>()
            val spatialMotion = LinkedHashMap<String, MotionTrack>() // movable/transport resources (8K.5)
            val moverStates = LinkedHashMap<String, StepTimeline<MoverState>>() // mover mode + carried entity (10.8/C2)
            val agentMotion = LinkedHashMap<String, MotionTrack>()
            val agentTypes = LinkedHashMap<String, String>()
            val entityActivity = LinkedHashMap<Long, StepTimeline<EntityActivity>>()
            val entityProcess = LinkedHashMap<Long, StepTimeline<String>>() // entity -> current process ("" = none)
            val networkEntities = LinkedHashMap<Long, NetworkEntityTrack>()
            // Inter-station transit segments (8I.4): when a transfer spans time, slide the entity along
            // the connector. lastStationExit remembers where/when the entity left, to close a segment.
            val networkTransit = LinkedHashMap<Long, MutableList<TransitSegment>>()
            val lastStationExit = HashMap<Long, Pair<String, Double>>() // entity -> (station, exitTime)
            val agentFirstSeen = LinkedHashMap<String, Double>()
            val agentRemovedAt = LinkedHashMap<String, Double>()
            val agentState = LinkedHashMap<String, StepTimeline<String>>()
            // Spaces derived from the trace (8K.6a), used as a backdrop when the layout declares none.
            val derivedSpaces = LinkedHashMap<String, SpatialSpaceDescriptor>()
            val flowFieldOverlays = ArrayList<AnimationEvent.FlowFieldDefined>() // G11: captured gradient snapshots
            val plannedPaths = LinkedHashMap<String, StepTimeline<List<WorldPoint>>>() // G12: per-agent routes over time
            val agentVectors = LinkedHashMap<String, StepTimeline<AnimationEvent.AgentVectorSampled>>() // G10: per-agent vectors
            val markerPulses = ArrayList<AnimationEvent.MarkerPulsed>() // G-animated: transient location highlights
            // Storages (8K.4): entities currently in a named delay, keyed by the delay's suspensionName
            // or — for unnamed delays — the entity's type name (a stable, shared default key).
            val storageMembers = LinkedHashMap<String, StepTimeline<List<StorageMember>>>()
            val entityStorage = LinkedHashMap<Long, StepTimeline<String>>() // entity -> current storage key ("" = none)
            val runningStorage = HashMap<String, MutableList<StorageMember>>()
            val openDelay = HashMap<Long, OpenDelay>() // entity -> its open delay, to find the key on DelayEnded
            // Last position sample per agent, to turn consecutive samples into interpolation segments.
            val lastAgentSample = HashMap<String, Triple<Double, Double, Double>>()
            var tMin = Double.POSITIVE_INFINITY
            var tMax = Double.NEGATIVE_INFINITY

            // For name-based moves (e.g. DistancesModel, whose coordinates are NaN), resolve the
            // move's endpoints against the layout's named positions. A move endpoint / conveyor anchor is a
            // *location*, so resolve location-first, then fall back to a station (keeps legacy layouts that
            // stored those places as stations working — L1 / disentanglement Phase 2).
            val anchorResolver = AnchorResolver.from(source.layout)

            // Toroidal space bounds (8F.7), for wrap-correcting agent segments and drawing.
            val torus: TorusBounds? = source.layout?.spaces?.firstNotNullOfOrNull { sp ->
                when (sp) {
                    is SpatialSpaceDescriptor.Continuous ->
                        if (sp.torus) TorusBounds(sp.xMin, sp.yMin, sp.xMax - sp.xMin, sp.yMax - sp.yMin) else null
                    is SpatialSpaceDescriptor.Grid ->
                        if (sp.torus) TorusBounds(sp.originX, sp.originY, sp.cols * sp.cellSize, sp.rows * sp.cellSize) else null
                    else -> null
                }
            }
            fun resolvePoint(x: Double, y: Double, z: Double, name: String?): Triple<Double, Double, Double> {
                // Prefer the named location's placed layout position, so moving a location on the canvas moves where
                // the animation actually goes (the marker stays connected to the motion). Fall back to the raw trace
                // coordinate for moves with no named/placed location. A location's default position is its mined
                // centroid (== the trace coordinate), so an unedited layout renders identically.
                name?.let { anchorResolver.resolve(it) }?.let { return Triple(it.x, it.y, it.z) }
                return Triple(x, y, z)
            }

            // Adjusts [x1] so the move from [x0] takes the short way around a torus of span [w].
            fun wrapTowards(x0: Double, x1: Double, w: Double?): Double {
                if (w == null || w <= 0.0) return x1
                val dx = x1 - x0
                return when {
                    dx > w / 2 -> x1 - w
                    dx < -w / 2 -> x1 + w
                    else -> x1
                }
            }

            // Conveyor cell-index -> world position geometry, and the last belt sample per item, to
            // turn the per-cell ConveyorItemMoved stream into interpolated motion (8G.5/8G.7).
            val conveyorGeom = HashMap<String, ConveyorGeometry>()
            val lastConveyorSample = HashMap<Long, Pair<Double, WorldPoint>>()
            val conveyorBlocked = LinkedHashMap<Long, StepTimeline<String>>()
            val currentlyBlocked = HashSet<Long>()
            // Per-conveyor belt occupancy (8G.9), derived from the item-move stream.
            val conveyorMaxCell = HashMap<String, Int>()
            val conveyorOccupied = LinkedHashMap<String, StepTimeline<Set<Int>>>()
            val perConveyorOccupied = HashMap<String, MutableSet<Int>>()
            val itemCell = HashMap<Long, Pair<String, Int>>() // entity -> (conveyor, current cell)

            for (event in source.events) {
                if (event.simTime < tMin) tMin = event.simTime
                if (event.simTime > tMax) tMax = event.simTime
                when (event) {
                    is AnimationEvent.EntityCreated ->
                        entities[event.entityId] = EntityTrack(event.entityId, event.entityType, event.simTime)
                    is AnimationEvent.EntityDisposed ->
                        entities[event.entityId]?.let { it.endTime = event.simTime }
                    is AnimationEvent.EntityTerminated ->
                        entities[event.entityId]?.let { it.endTime = event.simTime }
                    is AnimationEvent.QueueLengthChanged ->
                        queues.getOrPut(event.queueName) { StepTimeline() }.add(event.simTime, event.length)
                    is AnimationEvent.QObjectEnqueued -> {
                        val members = runningQueueMembers.getOrPut(event.queueName) { ArrayList() }
                        members.add(event.entityId)
                        queueMembers.getOrPut(event.queueName) { StepTimeline() }.add(event.simTime, members.toList())
                    }
                    is AnimationEvent.QObjectDequeued -> {
                        val members = runningQueueMembers.getOrPut(event.queueName) { ArrayList() }
                        members.remove(event.entityId)
                        queueMembers.getOrPut(event.queueName) { StepTimeline() }.add(event.simTime, members.toList())
                    }
                    is AnimationEvent.ResourceStateChanged ->
                        resources.getOrPut(event.resourceName) { StepTimeline() }
                            .add(event.simTime, ResourceSnapshot(event.state, event.busyUnits, event.capacity))
                    is AnimationEvent.ResponseObserved -> {
                        responses.getOrPut(event.responseName) { StepTimeline() }.add(event.simTime, event.value)
                        if (!event.count.isNaN()) {
                            responseStats.getOrPut(event.responseName) { StepTimeline() }
                                .add(event.simTime, ResponseStats(event.count, event.average, event.min, event.max))
                        }
                    }
                    is AnimationEvent.MoveStarted -> {
                        val (fx, fy, fz) = resolvePoint(event.fromX, event.fromY, event.fromZ, event.fromLocationName)
                        val (tx, ty, tz) = resolvePoint(event.toX, event.toY, event.toZ, event.toLocationName)
                        val via = anchorResolver.pathBetween(event.fromLocationName, event.toLocationName) ?: emptyList()
                        entityMotion.getOrPut(event.entityId) { MotionTrack() }
                            .add(MotionSegment(event.simTime, event.arrivalTime, fx, fy, fz, tx, ty, tz, via))
                    }
                    is AnimationEvent.SpatialElementMoved -> {
                        // A movable/transport resource moving (8K.5); same interpolation + name resolution as entities.
                        val (fx, fy, fz) = resolvePoint(event.fromX, event.fromY, event.fromZ, event.fromLocationName)
                        val (tx, ty, tz) = resolvePoint(event.toX, event.toY, event.toZ, event.toLocationName)
                        val via = anchorResolver.pathBetween(event.fromLocationName, event.toLocationName) ?: emptyList()
                        spatialMotion.getOrPut(event.name) { MotionTrack() }
                            .add(MotionSegment(event.simTime, event.arrivalTime, fx, fy, fz, tx, ty, tz, via))
                        moverStates.getOrPut(event.name) { StepTimeline() }
                            .add(event.simTime, MoverState(event.mode, event.carriedEntityId, event.carriedEntityType))
                    }
                    is AnimationEvent.SpatialElementMoveCompleted ->
                        moverStates.getOrPut(event.name) { StepTimeline() }
                            .add(event.simTime, MoverState(ksl.animation.MoverMode.EMPTY, null, null)) // at rest, not carrying
                    is AnimationEvent.AgentPositionChanged -> {
                        if (event.agentName !in agentFirstSeen) agentFirstSeen[event.agentName] = event.simTime
                        val prev = lastAgentSample[event.agentName]
                        if (prev != null) {
                            // On a torus, take the shortest path across the seam (8F.7).
                            val x1 = wrapTowards(prev.second, event.x, torus?.width)
                            val y1 = wrapTowards(prev.third, event.y, torus?.height)
                            agentMotion.getOrPut(event.agentName) { MotionTrack() }.add(
                                MotionSegment(prev.first, event.simTime, prev.second, prev.third, 0.0, x1, y1, event.z)
                            )
                        } else {
                            // First sample: seed a zero-duration rest segment so a never-moving agent (e.g. a
                            // network node placed once) still has a drawable position (G7). A later sample
                            // appends the real motion segment, which wins for t past it.
                            agentMotion.getOrPut(event.agentName) { MotionTrack() }.add(
                                MotionSegment(event.simTime, event.simTime, event.x, event.y, event.z, event.x, event.y, event.z)
                            )
                        }
                        // Store the raw (in-bounds) sample as the next segment's start.
                        lastAgentSample[event.agentName] = Triple(event.simTime, event.x, event.y)
                    }
                    is AnimationEvent.AgentRegistered ->
                        agentTypes[event.agentName] = event.agentType
                    is AnimationEvent.AgentRemoved ->
                        agentRemovedAt[event.agentName] = event.simTime
                    is AnimationEvent.AgentStateEntered ->
                        agentState.getOrPut(event.agentName) { StepTimeline() }.add(event.simTime, event.stateName)
                    is AnimationEvent.SpaceDefined -> {
                        derivedSpaces[event.name] = when (event.kind) {
                            "Grid" -> SpatialSpaceDescriptor.Grid(event.name, event.cols, event.rows, event.cellSize, torus = event.torus)
                            else -> SpatialSpaceDescriptor.Continuous(event.name, event.xMin, event.xMax, event.yMin, event.yMax, event.torus)
                        }
                    }
                    is AnimationEvent.NetworkDefined -> {
                        derivedSpaces[event.name] = SpatialSpaceDescriptor.Network(
                            event.name,
                            nodes = event.nodes.map { NetworkNode(it.id, LayoutPoint(it.x, it.y)) },
                            edges = event.edges.map { NetworkEdge(it.from, it.to, it.weight) }
                        )
                    }
                    is AnimationEvent.FlowFieldDefined -> flowFieldOverlays.add(event) // G11 heatmap snapshot
                    is AnimationEvent.PlannedPath -> plannedPaths.getOrPut(event.agentName) { StepTimeline() }
                        .add(event.simTime, event.points.map { WorldPoint(it.x, it.y, 0.0) }) // G12 route
                    is AnimationEvent.AgentVectorSampled -> agentVectors.getOrPut(event.agentName) { StepTimeline() }
                        .add(event.simTime, event) // G10 velocity/force sample
                    is AnimationEvent.MarkerPulsed -> markerPulses.add(event) // G-animated transient highlight
                    is AnimationEvent.ConveyorDefined -> {
                        // A conveyor anchor is a place that may be authored as a location or a network station
                        // (explicit dual-kind resolution — distinct from move endpoints, which are strictly locations).
                        val anchors = event.anchorLocations.zip(event.anchorCells)
                            .mapNotNull { (loc, cell) ->
                                (anchorResolver.resolve(loc) ?: anchorResolver.station(loc))?.let { Triple(loc, cell, it) }
                            }
                        val route = source.layout?.conveyors?.firstOrNull { it.conveyorName == event.conveyorName }
                        conveyorGeom[event.conveyorName] = ConveyorGeometry.build(anchors, route) // arc-length routing (10.5c)
                        conveyorMaxCell[event.conveyorName] = event.anchorCells.maxOrNull() ?: 0
                    }
                    is AnimationEvent.ConveyorEntryBlocked -> {
                        conveyorBlocked.getOrPut(event.entityId) { StepTimeline() }.add(event.simTime, event.entryLocation)
                        currentlyBlocked.add(event.entityId)
                    }
                    is AnimationEvent.ConveyorItemMoved -> {
                        if (currentlyBlocked.remove(event.entityId)) {
                            conveyorBlocked[event.entityId]?.add(event.simTime, "") // boarded — no longer blocked
                        }
                        // Belt occupancy (8G.9): the item vacates its old cell and occupies the new one.
                        val occ = perConveyorOccupied.getOrPut(event.conveyorName) { HashSet() }
                        itemCell[event.entityId]?.let { if (it.first == event.conveyorName) occ.remove(it.second) }
                        occ.add(event.cellIndex)
                        itemCell[event.entityId] = event.conveyorName to event.cellIndex
                        conveyorOccupied.getOrPut(event.conveyorName) { StepTimeline() }.add(event.simTime, occ.toSet())
                        val pos = conveyorGeom[event.conveyorName]?.positionAt(event.cellIndex)
                        if (pos != null) {
                            val prev = lastConveyorSample[event.entityId]
                            if (prev != null && prev.first < event.simTime) {
                                entityMotion.getOrPut(event.entityId) { MotionTrack() }.add(
                                    MotionSegment(
                                        prev.first, event.simTime,
                                        prev.second.x, prev.second.y, prev.second.z,
                                        pos.x, pos.y, pos.z
                                    )
                                )
                            }
                            lastConveyorSample[event.entityId] = event.simTime to pos
                        }
                    }
                    is AnimationEvent.ConveyorExited -> {
                        lastConveyorSample.remove(event.entityId) // end this ride's belt samples
                        itemCell.remove(event.entityId)?.let { (conv, cell) ->
                            perConveyorOccupied[conv]?.let { it.remove(cell); conveyorOccupied[conv]?.add(event.simTime, it.toSet()) }
                        }
                    }
                    is AnimationEvent.SeizeQueued -> {
                        entityActivity.getOrPut(event.entityId) { StepTimeline() }
                            .add(event.simTime, EntityActivity(EntityActivityKind.IN_QUEUE, event.resourceName, event.queueName))
                        // Typed resource-queue members (8I.1b): the seize stream carries the *entity* id,
                        // unlike QObjectEnqueued (which carries the RequestQ's Request id).
                        entityWaitingQueue[event.entityId] = event.queueName
                        val mem = runningSeizeQueue.getOrPut(event.queueName) { ArrayList() }
                        mem.add(event.entityId)
                        resourceQueueMembers.getOrPut(event.queueName) { StepTimeline() }.add(event.simTime, mem.toList())
                    }
                    is AnimationEvent.SeizeAllocated -> {
                        entityActivity.getOrPut(event.entityId) { StepTimeline() }
                            .add(event.simTime, EntityActivity(EntityActivityKind.IN_SERVICE, event.resourceName))
                        // The entity left its waiting queue when allocated (8I.1b).
                        entityWaitingQueue.remove(event.entityId)?.let { qn ->
                            runningSeizeQueue[qn]?.let { it.remove(event.entityId); resourceQueueMembers.getOrPut(qn) { StepTimeline() }.add(event.simTime, it.toList()) }
                        }
                        // Per-unit identity (8C.3): the entity occupies amountAllocated unit slots.
                        val units = runningResourceUnits.getOrPut(event.resourceName) { ArrayList() }
                        repeat(event.amountAllocated) { units.add(event.entityId) }
                        resourceUnits.getOrPut(event.resourceName) { StepTimeline() }.add(event.simTime, units.toList())
                    }
                    is AnimationEvent.Released -> {
                        entityActivity.getOrPut(event.entityId) { StepTimeline() }
                            .add(event.simTime, EntityActivity(EntityActivityKind.FREE))
                        // Defensive: if the entity somehow left without a SeizeAllocated (balk/cancel), drop
                        // it from any waiting queue too (8I.1b).
                        entityWaitingQueue.remove(event.entityId)?.let { qn ->
                            runningSeizeQueue[qn]?.let { it.remove(event.entityId); resourceQueueMembers.getOrPut(qn) { StepTimeline() }.add(event.simTime, it.toList()) }
                        }
                        val units = runningResourceUnits.getOrPut(event.resourceName) { ArrayList() }
                        repeat(event.amountReleased) { units.remove(event.entityId) }
                        resourceUnits.getOrPut(event.resourceName) { StepTimeline() }.add(event.simTime, units.toList())
                    }
                    is AnimationEvent.EnteredNetwork ->
                        networkEntities.getOrPut(event.entityId) { NetworkEntityTrack(event.entityId, event.simTime) }
                            .also { it.typeId = event.qObjectType; it.typeName = event.qObjectTypeName }
                    is AnimationEvent.ExitedNetwork ->
                        networkEntities[event.entityId]?.let { it.endTime = event.simTime }
                    is AnimationEvent.StationEntered -> {
                        networkEntities.getOrPut(event.entityId) { NetworkEntityTrack(event.entityId, event.simTime) }
                            .station.add(event.simTime, event.stationName)
                        // If the entity left another station earlier and this arrival is later, it was in
                        // transit over [exitTime, now]; record a segment between the two station positions (8I.4).
                        lastStationExit.remove(event.entityId)?.let { (fromName, exitTime) ->
                            val from = anchorResolver.station(fromName); val to = anchorResolver.station(event.stationName)
                            if (from != null && to != null && event.simTime > exitTime) {
                                val via = anchorResolver.pathBetween(fromName, event.stationName) ?: emptyList()
                                networkTransit.getOrPut(event.entityId) { ArrayList() }
                                    .add(TransitSegment(exitTime, from, event.simTime, to, via))
                            }
                        }
                    }
                    is AnimationEvent.StationExited -> {
                        networkEntities[event.entityId]?.station?.add(event.simTime, "")
                        lastStationExit[event.entityId] = event.stationName to event.simTime
                    }
                    is AnimationEvent.DelayStarted -> {
                        // Key by the delay's name, or fall back to the entity's type name (8K.4).
                        val key = event.suspensionName
                            ?: entities[event.entityId]?.typeName
                            ?: "(delay)"
                        val member = StorageMember(event.entityId, event.simTime, event.arrivalTime)
                        openDelay[event.entityId] = OpenDelay(key, member)
                        runningStorage.getOrPut(key) { ArrayList() }.add(member)
                        storageMembers.getOrPut(key) { StepTimeline() }.add(event.simTime, runningStorage[key]!!.toList())
                        entityStorage.getOrPut(event.entityId) { StepTimeline() }.add(event.simTime, key)
                    }
                    is AnimationEvent.DelayEnded -> {
                        openDelay.remove(event.entityId)?.let { open ->
                            runningStorage[open.key]?.let { list ->
                                list.removeAll { it.entityId == event.entityId }
                                storageMembers.getOrPut(open.key) { StepTimeline() }.add(event.simTime, list.toList())
                            }
                            entityStorage.getOrPut(event.entityId) { StepTimeline() }.add(event.simTime, "")
                        }
                    }
                    is AnimationEvent.ProcessActivated ->
                        entityProcess.getOrPut(event.entityId) { StepTimeline() }.add(event.simTime, event.processName)
                    is AnimationEvent.ProcessCompleted ->
                        entityProcess.getOrPut(event.entityId) { StepTimeline() }.add(event.simTime, "")
                    else -> {
                        // Other events (seize/hold/signal/conveyor, sampled positions, station) are
                        // consumed in later steps. They still contribute to the time range above.
                    }
                }
            }
            if (tMin > tMax) {
                tMin = 0.0
                tMax = 0.0
            }
            return ReplayModel(
                source.layout, source.header, tMin..tMax,
                entities, queues, queueMembers, resourceQueueMembers, resources, resourceUnits, responses, responseStats, entityMotion,
                spatialMotion, agentMotion, agentTypes, entityActivity, entityProcess, networkEntities, networkTransit, agentFirstSeen, agentRemovedAt, agentState,
                derivedSpaces.values.toList(),
                storageMembers, entityStorage,
                conveyorBlocked, conveyorGeom, conveyorMaxCell, conveyorOccupied, moverStates, torus, source.assetBase,
                flowFieldOverlays = flowFieldOverlays, plannedPaths = plannedPaths, agentVectors = agentVectors,
                markerPulses = markerPulses
            )
        }
    }
}
