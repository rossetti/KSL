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
import ksl.animation.GuidedPathLayoutElement

/*
 * Replay of the vehicle events: guide paths, guided transporters, closures, fleet assignments and carried
 * loads. Compiled for the browser as well as the JVM (KSLAnimationCore lists this file), so nothing here may
 * reach for java.* or reflection.
 */

/**
 * A guide path's drawable geometry, from its [AnimationEvent.GuidedPathDefined] placed through the layout's
 * guided path element: a network point (x, y, z) is drawn at offset + scale × (x, y) + z × floorOffsetPerZ.
 * [key] is the owning space's name (the network's name for a trace recorded before spaces were named).
 */
class GuidedPathGeometry(
    val key: String,
    val definition: AnimationEvent.GuidedPathDefined,
    val style: GuidedPathLayoutElement?
) {
    private val points: Map<String, WorldPoint>
    private val placeAliases: Map<String, String>
    private val links: Map<String, ksl.animation.GuidedPathLinkDef> = definition.links.associateBy { it.name }

    init {
        val pts = LinkedHashMap<String, WorldPoint>()
        val aliases = HashMap<String, String>()
        for (i in definition.intersections) {
            pts[i.name] = place(i.x, i.y, i.z)
            for (a in i.aliases) aliases[a] = i.name
        }
        points = pts
        placeAliases = aliases
    }

    private fun place(x: Double, y: Double, z: Double): WorldPoint {
        val scale = style?.scale ?: 1.0
        val ox = style?.offset?.x ?: 0.0
        val oy = style?.offset?.y ?: 0.0
        val floor = style?.floorOffsetPerZ
        return WorldPoint(
            ox + scale * x + z * (floor?.x ?: 0.0),
            oy + scale * y + z * (floor?.y ?: 0.0),
            z
        )
    }

    /** The drawn position of the intersection [name], or null when the path has no such intersection. */
    fun intersectionPoint(name: String): WorldPoint? = points[name]

    /** Intersection names with their drawn positions, in definition order. */
    val intersectionPoints: Map<String, WorldPoint> get() = points

    /** A place named by an intersection or by one of its station aliases, resolved to where it is drawn. */
    fun placePoint(name: String): WorldPoint? = points[name] ?: placeAliases[name]?.let { points[it] }

    /** Links with an opposed twin: another link joining the same two intersections the other way. */
    private val opposed: Set<String> = definition.links.filter { l ->
        definition.links.any { it.name != l.name && it.from == l.to && it.to == l.from }
    }.map { it.name }.toSet()

    /**
     * The two drawn ends of link [name], from its begin end to its end, or null when either end is unknown.
     *
     * Two one-way links joining the same intersections in opposite directions are two lanes of one aisle, and
     * drawn on their shared centre line they would look like one lane with carts passing through each other.
     * Each is drawn a little to its own side of the centre line instead, as a road draws its lanes, and the
     * carts on it follow it. The shift is a fraction of a zone, so the two lanes read as a pair.
     */
    fun linkEnds(name: String): Pair<WorldPoint, WorldPoint>? {
        val link = links[name] ?: return null
        val a = points[link.from] ?: return null
        val b = points[link.to] ?: return null
        if (name !in opposed) return a to b
        val length = kotlin.math.sqrt(dist2(a, b))
        if (length <= 0.0) return a to b
        val shift = minOf(length / link.numZones.coerceAtLeast(1) * LANE_SHARE_OF_ZONE, length * LANE_SHARE_OF_LINK)
        // Perpendicular to the direction of travel, so the twin, running the other way, lands on the other side.
        val nx = (b.y - a.y) / length * shift
        val ny = -(b.x - a.x) / length * shift
        return WorldPoint(a.x + nx, a.y + ny, a.z) to WorldPoint(b.x + nx, b.y + ny, b.z)
    }

    /**
     * Where a transporter's front stands once it has entered zone [zoneName], coming from [previous].
     *
     * A move is reported when the front has fully entered a zone, so on a link zone k of n the front is at the
     * zone's far boundary: the fraction k over n along the link travelling from its begin end, (k - 1) over n
     * travelling back. The far boundary is whichever of the two is further from where the front last was; with no
     * previous position the zone's midpoint is used. An intersection zone is the intersection itself.
     */
    fun frontPoint(zoneName: String, linkName: String?, zoneIndex: Int, previous: WorldPoint?): WorldPoint? {
        points[zoneName]?.let { return it }
        val name = linkName ?: zoneName.substringBeforeLast(".Zone", "")
        val link = links[name] ?: return null
        val (a, b) = linkEnds(name) ?: return null
        val n = link.numZones.coerceAtLeast(1)
        val k = (if (zoneIndex > 0) zoneIndex else zoneName.substringAfterLast(".Zone").toIntOrNull() ?: 1)
            .coerceIn(1, n)
        fun at(f: Double) = WorldPoint(a.x + f * (b.x - a.x), a.y + f * (b.y - a.y), a.z + f * (b.z - a.z))
        if (previous == null) return at((k - 0.5) / n)
        val ahead = at(k.toDouble() / n)
        val behind = at((k - 1.0) / n)
        return if (dist2(ahead, previous) >= dist2(behind, previous)) ahead else behind
    }

    /**
     * A floor separation for a path that climbs, so its floors are not drawn on top of each other when no
     * layout says where they go. Returns the offset per unit of height, which lifts each floor up the canvas by
     * enough that it clears the one below, paired with the overall offset that moves the lower floors down to
     * make room, so the top floor stays where the trace put it and nothing is lifted off the canvas. Null for
     * a flat path. Computed from the trace's own coordinates, before any placement.
     */
    fun suggestedFloorOffset(): Pair<ksl.animation.LayoutPoint, ksl.animation.LayoutPoint>? {
        val pts = definition.intersections
        val levels = pts.map { it.z }.distinct().sorted()
        if (levels.size < 2) return null
        val step = levels.zipWithNext { a, b -> b - a }.filter { it > 0.0 }.minOrNull() ?: return null
        // Every floor is drawn across the whole path's height, so that is what one floor must clear.
        val floorHeight = pts.maxOf { it.y } - pts.minOf { it.y }
        val width = pts.maxOf { it.x } - pts.minOf { it.x }
        val clearance = (if (floorHeight > 0.0) floorHeight * 1.35 else width * 0.35).coerceAtLeast(1.0)
        val perZ = -clearance / step
        val lift = ksl.animation.LayoutPoint(0.0, -perZ * (levels.last() - levels.first()))
        return ksl.animation.LayoutPoint(0.0, perZ) to lift
    }

    /** The average drawn length of one zone over the path's links, or null when no link has a drawable length. */
    fun meanZoneLength(): Double? {
        val lengths = definition.links.mapNotNull { l ->
            val (a, b) = linkEnds(l.name) ?: return@mapNotNull null
            kotlin.math.sqrt(dist2(a, b)) / l.numZones.coerceAtLeast(1)
        }.filter { it > 0.0 }
        return if (lengths.isEmpty()) null else lengths.sum() / lengths.size
    }

    private companion object {
        /** How far a lane of an opposed pair sits from the shared centre line, as a share of one zone. */
        const val LANE_SHARE_OF_ZONE = 0.18
        /** And never more than this share of the link, so a short link's lanes stay close. */
        const val LANE_SHARE_OF_LINK = 0.08
    }

    private fun dist2(p: WorldPoint, q: WorldPoint): Double =
        (p.x - q.x) * (p.x - q.x) + (p.y - q.y) * (p.y - q.y) + (p.z - q.z) * (p.z - q.z)
}

/**
 * A guided transporter's state at an instant: its [state] name, whether something outside it [halted] it,
 * and while blocked, why ([blockReason]) and for what ([awaitedZoneName]).
 */
data class TransporterSnapshot(
    val state: String,
    val halted: Boolean = false,
    val blockReason: String? = null,
    val awaitedZoneName: String? = null
) {
    /** True while the transporter is standing still by its own account: idle, halted, loading, unloading or blocked. */
    val isStill: Boolean get() = halted || state in STILL_STATES

    companion object {
        /** The transporter states in which it does not move. */
        val STILL_STATES: Set<String> = setOf("IDLE", "LOADING", "UNLOADING", "BLOCKED")
    }
}

/** One closure on a guide path: [holderName] has its [zoneNames] [state] (RESERVED or HELD). */
data class GuidePathClosure(
    val holderName: String,
    val zoneNames: List<String>,
    val state: String,
    val holderKind: String? = null
)

/**
 * A vehicle's current task: carrying [loadEntityId] (if any) from [origin] to [destination], with those places
 * resolved to drawn positions where the trace and layout allow ([originPoint], [destinationPoint]).
 */
data class VehicleAssignment(
    val taskId: Long,
    val origin: String,
    val destination: String,
    val originPoint: WorldPoint?,
    val destinationPoint: WorldPoint?,
    val taskKind: String? = null,
    val loadEntityId: Long? = null
)

/** What a fleet vehicle is doing as its fleet sees it, and its battery's charge (NaN when it has none). */
data class FleetVehicleSnapshot(val state: String, val stateOfCharge: Double = Double.NaN)

/** A guided transporter's front positions as it entered zones, kept so a multi-zone vehicle can be drawn along the path. */
internal class TransporterTrail {
    val times = ArrayList<Double>()
    val points = ArrayList<WorldPoint>()
}

/**
 * Everything the vehicle events contribute to a replay, built incrementally by [GuidedPathReplayBuilder] and then
 * read through [ReplayModel].
 */
class VehicleReplay internal constructor(
    val guidePaths: Map<String, GuidedPathGeometry>,
    private val transporterSpace: Map<String, String>,
    private val transporterDefs: Map<String, ksl.animation.GuidedTransporterDef>,
    private val transporterMotion: Map<String, MotionTrack>,
    private val transporterTrails: Map<String, TransporterTrail>,
    private val transporterStates: Map<String, StepTimeline<TransporterSnapshot>>,
    private val closures: Map<String, StepTimeline<List<GuidePathClosure>>>,
    private val assignments: Map<String, StepTimeline<VehicleAssignment?>>,
    private val fleetStates: Map<String, StepTimeline<FleetVehicleSnapshot>>,
    private val loadVehicle: Map<Long, StepTimeline<String>>,
    private val vehicleLoads: Map<String, StepTimeline<List<Long>>>,
    /** The fleet systems (dispatching AGV or free-path fleets) the trace mentions. */
    val fleetSystemNames: Set<String> = emptySet(),
    /** Fleet vehicles and their moving bodies, by every name the trace uses for them. */
    val fleetVehicleNames: Set<String> = emptySet()
) {
    /** The guided transporters that appear in the trace. */
    val transporterNames: Set<String> get() = transporterMotion.keys + transporterDefs.keys

    /** The guide path (by its key) transporter [name] runs on, or null. */
    fun transporterGuidePath(name: String): String? = transporterSpace[name]

    /** Transporter [name]'s definition (its length in zones, capacity and owning vehicle), or null. */
    fun transporterDef(name: String): ksl.animation.GuidedTransporterDef? = transporterDefs[name]

    /** Where transporter [name]'s front is at [t], or null before it is first placed. */
    fun transporterPositionAt(name: String, t: Double): WorldPoint? = transporterMotion[name]?.positionAt(t)

    /**
     * The transporter's body at [t] as a polyline from its front back along the path it came by, covering up to
     * [zones] zone entries (its length in zones by default). A one-zone vehicle yields its front and the point it
     * was last at, which is enough to orient a glyph along the path.
     */
    fun transporterBodyAt(name: String, t: Double, zones: Int = transporterDefs[name]?.lengthInZones ?: 1): List<WorldPoint> {
        val front = transporterPositionAt(name, t) ?: return emptyList()
        val trail = transporterTrails[name] ?: return listOf(front)
        var i = upperIndex(trail.times, t)
        val body = ArrayList<WorldPoint>()
        body.add(front)
        // The most recent entry at or before t is where the front was last reported; skip it when the front is
        // still there, so the body always reaches back at least one zone.
        var taken = 0
        while (i >= 0 && taken < zones.coerceAtLeast(1)) {
            val p = trail.points[i]
            if (p != body.last()) {
                body.add(p)
                taken++
            }
            i--
        }
        return body
    }

    private fun upperIndex(times: List<Double>, t: Double): Int {
        var lo = 0
        var hi = times.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** Transporter [name]'s state at [t], or null before the first. */
    fun transporterStateAt(name: String, t: Double): TransporterSnapshot? = transporterStates[name]?.valueAt(t)

    /** The closures in effect on guide path [key] at [t]; released ones are gone. */
    fun closuresAt(key: String, t: Double): List<GuidePathClosure> = closures[key]?.valueAt(t) ?: emptyList()

    /** The task vehicle [bodyName] (the name its movement events carry) is committed to at [t], or null. */
    fun assignmentAt(bodyName: String, t: Double): VehicleAssignment? = assignments[bodyName]?.valueAt(t)

    /** Vehicle bodies that were ever assigned a task. */
    val assignedVehicles: Set<String> get() = assignments.keys

    /** Fleet vehicle [bodyName]'s state at [t], or null when it is not a fleet vehicle or nothing is known yet. */
    fun fleetStateAt(bodyName: String, t: Double): FleetVehicleSnapshot? = fleetStates[bodyName]?.valueAt(t)

    /** The vehicle body entity [entityId] is aboard at [t], or null when it is not aboard one. */
    fun vehicleCarryingAt(entityId: Long, t: Double): String? =
        loadVehicle[entityId]?.valueAt(t)?.takeIf { it.isNotEmpty() }

    /** The entities aboard vehicle [bodyName] at [t], in boarding order. */
    fun loadsAboardAt(bodyName: String, t: Double): List<Long> = vehicleLoads[bodyName]?.valueAt(t) ?: emptyList()

    /** Vehicle bodies that ever carried a load. */
    val loadCarryingVehicles: Set<String> get() = vehicleLoads.keys

    /**
     * Whether [queueName] is one of the hold queues a guide path space or a fleet keeps for its loads (awaiting
     * pickup, riding, driving, awaiting boarding, in transit). Those loads are drawn aboard their vehicle or at
     * the place they wait, so the queue itself is bookkeeping rather than something to place on a canvas.
     */
    fun isVehicleHoldQueue(queueName: String): Boolean {
        val owner = queueName.substringBeforeLast(':', "")
        if (owner.isEmpty() || (owner !in guidePaths && owner !in fleetSystemNames)) return false
        return queueName.substringAfterLast(':') in HOLD_QUEUE_NAMES
    }

    /**
     * Whether [queueName] belongs to the vehicle machinery rather than to the model: a hold queue for loads, or
     * a queue a fleet, its dispatcher, a vehicle or a vehicle's body keeps for itself (availability, task
     * board, idle dispatchers, out of service, a body's request queue, a home base). Such a queue is named by
     * its owner followed by a colon. Its members are the fleet's own control agents or bookkeeping, which a
     * viewer should not see queueing beside the model's parts.
     */
    fun isVehicleInternalQueue(queueName: String): Boolean {
        if (isVehicleHoldQueue(queueName)) return true
        var cut = queueName.lastIndexOf(':')
        while (cut > 0) {
            val owner = queueName.substring(0, cut)
            if (owner in guidePaths || owner in fleetSystemNames || owner in fleetVehicleNames ||
                owner in transporterNames) return true
            cut = queueName.lastIndexOf(':', cut - 1)
        }
        return false
    }

    val isEmpty: Boolean
        get() = guidePaths.isEmpty() && transporterMotion.isEmpty() && assignments.isEmpty() &&
            fleetStates.isEmpty() && vehicleLoads.isEmpty()

    companion object {
        private val HOLD_QUEUE_NAMES = setOf(
            "AwaitingPickupHoldQ", "RidingHoldQ", "DrivingHoldQ", "AwaitingBoardingHoldQ", "InTransitHoldQ"
        )

        val EMPTY: VehicleReplay = VehicleReplay(
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
            emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap()
        )
    }
}

/**
 * Turns the vehicle events of a trace, in time order, into a [VehicleReplay].
 *
 * Transporter motion is the part that needs care. A move is reported when a front has entered a zone, and
 * nothing is reported while a transporter waits, so interpolating straight from one entry to the next would
 * slide a blocked cart slowly across the gap and hide exactly the congestion a viewer is looking for. Instead
 * the travel into a zone starts at the later of the previous entry and the moment the transporter last stopped
 * standing still (idle, halted, loading, unloading or blocked), and the cart holds its position until then.
 *
 * [placeResolver] resolves a free-path place name (an assignment's origin or destination with no guide path)
 * to a drawn position.
 */
internal class GuidedPathReplayBuilder(
    private val layout: AnimationLayout?,
    private val placeResolver: (String) -> WorldPoint?
) {
    private val guidePaths = LinkedHashMap<String, GuidedPathGeometry>()
    private val networkKeys = HashMap<String, String>() // network name -> key, for events that carry no spaceName
    private val transporterSpace = LinkedHashMap<String, String>()
    private val transporterDefs = LinkedHashMap<String, ksl.animation.GuidedTransporterDef>()
    private val transporterMotion = LinkedHashMap<String, MotionTrack>()
    private val transporterTrails = LinkedHashMap<String, TransporterTrail>()
    private val transporterStates = LinkedHashMap<String, StepTimeline<TransporterSnapshot>>()
    private val closures = LinkedHashMap<String, StepTimeline<List<GuidePathClosure>>>()
    private val openClosures = HashMap<String, LinkedHashMap<String, GuidePathClosure>>()
    private val assignments = LinkedHashMap<String, StepTimeline<VehicleAssignment?>>()
    private val openTask = HashMap<String, Long>() // body -> task it is committed to
    private val bodyOfVehicle = HashMap<String, String>() // fleet vehicle name -> body name
    private val fleetStates = LinkedHashMap<String, StepTimeline<FleetVehicleSnapshot>>()
    private val loadVehicle = LinkedHashMap<Long, StepTimeline<String>>()
    private val vehicleLoads = LinkedHashMap<String, StepTimeline<List<Long>>>()
    private val aboard = HashMap<String, MutableList<Long>>()
    private val fleetSystems = LinkedHashSet<String>()
    private val fleetVehicles = LinkedHashSet<String>()

    // Per transporter: where and when its front was last reported, and when its current travel began.
    private val lastFront = HashMap<String, Pair<Double, WorldPoint>>()
    private val travelStart = HashMap<String, Double>()
    private val still = HashMap<String, Boolean>()

    private fun keyOf(spaceName: String?, networkName: String?): String? =
        spaceName ?: networkName?.let { networkKeys[it] ?: it }

    /** Consumes [event] when it is a vehicle event, returning whether it was one. */
    fun accept(event: AnimationEvent): Boolean {
        when (event) {
            is AnimationEvent.GuidedPathDefined -> define(event)
            is AnimationEvent.GuidedTransporterMoved -> moved(event)
            is AnimationEvent.GuidedTransporterStateChanged -> stateChanged(event)
            is AnimationEvent.GuidedPathClosureChanged -> closureChanged(event)
            is AnimationEvent.AgvAssignmentMade -> assignmentMade(event)
            is AnimationEvent.AgvAssignmentEnded -> assignmentEnded(event)
            is AnimationEvent.FleetVehicleStateChanged -> {
                fleetSystems.add(event.systemName)
                fleetVehicles.add(event.vehicleName)
                event.bodyName?.let { fleetVehicles.add(it) }
                val body = event.bodyName ?: bodyOfVehicle[event.vehicleName] ?: event.vehicleName
                event.bodyName?.let { bodyOfVehicle[event.vehicleName] = it }
                fleetStates.getOrPut(body) { StepTimeline() }
                    .add(event.simTime, FleetVehicleSnapshot(event.state, event.stateOfCharge))
            }
            is AnimationEvent.VehicleLoadBoarded -> load(event.simTime, event.entityId, event.bodyName ?: event.vehicleName, true)
            is AnimationEvent.VehicleLoadAlighted -> load(event.simTime, event.entityId, event.bodyName ?: event.vehicleName, false)
            else -> return false
        }
        return true
    }

    /**
     * A definition is stated at each replication's start and restated when a capture window opens, and in both
     * cases the transporters, closures and loads that follow it are restated too. So a definition is where the
     * path's running state is forgotten: a cart is placed afresh rather than slid from where the last
     * replication left it, and a closure or load the restatement does not repeat is gone.
     */
    private fun define(event: AnimationEvent.GuidedPathDefined) {
        val key = event.spaceName ?: event.networkName
        networkKeys[event.networkName] = key
        if (openClosures.remove(key) != null) closures[key]?.add(event.simTime, emptyList())
        for ((name, space) in transporterSpace) {
            if (space != key) continue
            lastFront.remove(name)
            travelStart.remove(name)
            still.remove(name)
            aboard[name]?.let { loads ->
                for (id in loads) loadVehicle[id]?.add(event.simTime, "")
                loads.clear()
                vehicleLoads[name]?.add(event.simTime, emptyList())
            }
        }
        val style = layout?.guidedPaths?.firstOrNull { it.spaceName == key }
        guidePaths[key] = GuidedPathGeometry(key, event, style)
        for (d in event.transporters) {
            transporterDefs[d.name] = d
            transporterSpace[d.name] = key
            d.vehicleName?.let { bodyOfVehicle[it] = d.name }
        }
    }

    private fun moved(event: AnimationEvent.GuidedTransporterMoved) {
        val key = keyOf(event.spaceName, event.networkName) ?: return
        val geom = guidePaths[key] ?: return
        val name = event.transporterName
        transporterSpace.putIfAbsentCompat(name, key)
        val previous = lastFront[name]
        val point = geom.frontPoint(event.zoneName, event.linkName, event.zoneIndex, previous?.second) ?: return
        val track = transporterMotion.getOrPut(name) { MotionTrack() }
        if (previous == null) {
            track.add(MotionSegment(event.simTime, event.simTime, point.x, point.y, point.z, point.x, point.y, point.z))
        } else {
            val start = maxOf(previous.first, travelStart[name] ?: previous.first).coerceAtMost(event.simTime)
            val p = previous.second
            track.add(MotionSegment(start, event.simTime, p.x, p.y, p.z, point.x, point.y, point.z))
        }
        lastFront[name] = event.simTime to point
        if (still[name] != true) travelStart[name] = event.simTime
        val trail = transporterTrails.getOrPut(name) { TransporterTrail() }
        trail.times.add(event.simTime)
        trail.points.add(point)
    }

    private fun stateChanged(event: AnimationEvent.GuidedTransporterStateChanged) {
        val name = event.transporterName
        keyOf(event.spaceName, event.networkName)?.let { transporterSpace.putIfAbsentCompat(name, it) }
        val snap = TransporterSnapshot(event.state, event.halted, event.blockReason, event.awaitedZoneName)
        transporterStates.getOrPut(name) { StepTimeline() }.add(event.simTime, snap)
        val wasStill = still[name] ?: true
        still[name] = snap.isStill
        // Leaving a stillness is when travel into the next zone begins.
        if (wasStill && !snap.isStill) travelStart[name] = event.simTime
    }

    private fun closureChanged(event: AnimationEvent.GuidedPathClosureChanged) {
        val key = keyOf(event.spaceName, event.networkName) ?: return
        val open = openClosures.getOrPut(key) { LinkedHashMap() }
        // A holder can close more than one set at once; the set of zones identifies which.
        val id = event.holderName + "|" + event.zoneNames.joinToString(",")
        if (event.state == "RELEASED") {
            open.remove(id)
        } else {
            open[id] = GuidePathClosure(event.holderName, event.zoneNames, event.state, event.holderKind)
        }
        closures.getOrPut(key) { StepTimeline() }.add(event.simTime, open.values.toList())
    }

    private fun resolvePlace(name: String, networkName: String?): WorldPoint? {
        if (networkName != null) {
            val key = keyOf(null, networkName)
            guidePaths[key]?.placePoint(name)?.let { return it }
        }
        for (g in guidePaths.values) g.placePoint(name)?.let { return it }
        return placeResolver(name)
    }

    private fun assignmentMade(event: AnimationEvent.AgvAssignmentMade) {
        fleetSystems.add(event.systemName)
        fleetVehicles.add(event.vehicleName)
        event.bodyName?.let { fleetVehicles.add(it) }
        val body = event.bodyName ?: bodyOfVehicle[event.vehicleName] ?: event.vehicleName
        bodyOfVehicle[event.vehicleName] = body
        // A task given to this vehicle while another was open replaces it; a task re-given to another
        // vehicle (a revocation) is closed on the vehicle that lost it.
        for ((other, task) in openTask.entries.toList()) {
            if (task == event.taskId && other != body) {
                openTask.remove(other)
                assignments.getOrPut(other) { StepTimeline() }.add(event.simTime, null)
            }
        }
        openTask[body] = event.taskId
        assignments.getOrPut(body) { StepTimeline() }.add(
            event.simTime,
            VehicleAssignment(
                event.taskId, event.origin, event.destination,
                resolvePlace(event.origin, event.networkName), resolvePlace(event.destination, event.networkName),
                event.taskKind, event.loadEntityId
            )
        )
    }

    private fun assignmentEnded(event: AnimationEvent.AgvAssignmentEnded) {
        val body = bodyOfVehicle[event.vehicleName] ?: event.vehicleName
        if (openTask[body] != event.taskId) return
        openTask.remove(body)
        assignments.getOrPut(body) { StepTimeline() }.add(event.simTime, null)
    }

    private fun load(t: Double, entityId: Long, body: String, boarded: Boolean) {
        val list = aboard.getOrPut(body) { ArrayList() }
        if (boarded) {
            // Boarding one vehicle means leaving any other: a restated snapshot must not leave a ghost aboard.
            for ((other, loads) in aboard) {
                if (other != body && loads.remove(entityId)) {
                    vehicleLoads.getOrPut(other) { StepTimeline() }.add(t, loads.toList())
                }
            }
            if (entityId !in list) list.add(entityId)
            loadVehicle.getOrPut(entityId) { StepTimeline() }.add(t, body)
        } else {
            list.remove(entityId)
            loadVehicle.getOrPut(entityId) { StepTimeline() }.add(t, "")
        }
        vehicleLoads.getOrPut(body) { StepTimeline() }.add(t, list.toList())
    }

    /** The replay built from every event accepted so far. */
    fun build(): VehicleReplay = VehicleReplay(
        guidePaths, transporterSpace, transporterDefs, transporterMotion, transporterTrails, transporterStates,
        closures, assignments, fleetStates, loadVehicle, vehicleLoads, fleetSystems, fleetVehicles
    )
}

/** `putIfAbsent` without the JVM's `Map.putIfAbsent`, which the Kotlin/JS build does not have. */
private fun <K, V> MutableMap<K, V>.putIfAbsentCompat(key: K, value: V) {
    if (key !in this) this[key] = value
}
