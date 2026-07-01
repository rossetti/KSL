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

import kotlinx.serialization.Serializable
import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.ContinuousProjection
import ksl.modeling.agent.ContinuousVolume
import ksl.modeling.agent.GridGeometrySpec
import ksl.modeling.agent.GridProjection
import ksl.modeling.agent.VoxelProjection
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.EntityType
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Resource
import ksl.modeling.queue.Queue
import ksl.modeling.spatial.MovableResource
import ksl.modeling.station.NetworkEgress
import ksl.modeling.station.NetworkIngress
import ksl.modeling.station.SResource
import ksl.modeling.station.Station
import ksl.modeling.variable.TWResponse
import ksl.modeling.station.StationNetwork
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.isSubclassOf

/** A spatial space derived from an agent projection (its name + dimensions), for the layout side (9A.3). */
@Serializable
data class SpaceInfo(
    val name: String,
    val kind: SpaceKind,
    val cols: Int? = null,
    val rows: Int? = null,
    val xMin: Double? = null,
    val xMax: Double? = null,
    val yMin: Double? = null,
    val yMax: Double? = null,
    val torus: Boolean = false,
    /** Obstacle/cost geometry extracted from a graph the modeler linked via `Context.attachGeometry` (P5a/G2). */
    val geometry: GridGeometrySpec? = null
) {
    @Serializable
    enum class SpaceKind { GRID, CONTINUOUS }
}

/** An animatable process of an entity type (10.1b): its trace [name] and whether it is animated/captured. */
@Serializable
data class ProcessInfo(val name: String, val include: Boolean = true)

/**
 * A queue's reporting intent (P5/C1), read pre-run from the model's [ksl.modeling.queue.Queue]. [reporting]
 * mirrors `defaultReportingOption` and [waitTimeStats] mirrors `waitTimeStatOption`. The editor uses these as
 * a hint: non-reporting queues (e.g. a movable resource's internal `:HomeBaseQ`) are captured but not
 * auto-placed.
 */
@Serializable
data class QueueInfo(val name: String, val reporting: Boolean = true, val waitTimeStats: Boolean = true)

/** One chained segment of a conveyor (10.5a): its named [entryLocation]→[exitLocation] anchors and cell length. */
@Serializable
data class SegmentInfo(val entryLocation: String, val exitLocation: String, val lengthCells: Int)

/**
 * A conveyor's structure exposed pre-run from the built [ksl.modeling.entity.Conveyor] (10.5a): its [cellSize],
 * whether it is [accumulating], and its ordered, chained [segments]. Lets the editor route the belt against the
 * stations/locations its segments connect before any run.
 */
@Serializable
data class ConveyorInfo(
    val name: String,
    val cellSize: Int,
    val accumulating: Boolean,
    val segments: List<SegmentInfo> = emptyList()
)

/**
 * An animatable entity/agent **type** (10.1b): its [typeName] (the class `simpleName`, matching
 * `EntityCreated.entityType`) and the [processes] declared on it (from `@KSLAnimatedProcess`, or — when none are
 * annotated — a best-effort list of its `KSLProcess`-valued properties, keyed by property name).
 */
@Serializable
data class EntityTypeInfo(
    val typeName: String,
    val processes: List<ProcessInfo> = emptyList(),
    /** Whether the type is animated/captured; `false` from `@KSLAnimatedEntity(include=false)` (10.1d). */
    val include: Boolean = true
)

/**
 * A named spatial location (landmark / point of interest) an agent context declared via `Context.location(...)`
 * (G1): its [name] and, when the coordinates are known, its ([x], [y]) — so auto-layout can place it. A
 * coordinate-free location (e.g. a `DistancesModel` name) has null [x]/[y] and is positioned later (MDS).
 */
@Serializable
data class LocationInfo(val name: String, val x: Double? = null, val y: Double? = null)

/**
 * The animatable elements a model exposes (9A.3): the structural model elements, plus spatial spaces
 * (from agent projections) and location names (from any `DistancesModel`). Built from a *probe* model —
 * no simulation — and used to drive the editor pick-lists and author-time validation (9A.5), so capture
 * selection and layout key off one identifier space ([ElementKind]).
 *
 * **Not included (runtime, not structural):** entity/agent **type** names (transient `QObject`s; types
 * appear only in the trace) and storage `suspensionName`s. Those are validated against a produced trace
 * (8K.2b), not the inventory.
 */
@Serializable
data class AnimationInventory(
    val queues: List<String> = emptyList(),
    val queueInfos: List<QueueInfo> = emptyList(),
    val resources: List<String> = emptyList(),          // process Resource (incl. ResourceWithQ) + station SResource
    val movableResources: List<String> = emptyList(),
    val movableHomeBases: Map<String, String> = emptyMap(),
    val responses: List<String> = emptyList(),
    val timeWeightedResponses: List<String> = emptyList(), // subset of [responses] backed by a TWResponse
    val counters: List<String> = emptyList(),
    val stations: List<String> = emptyList(),
    val networks: List<String> = emptyList(),
    val conveyors: List<String> = emptyList(),
    val conveyorInfos: List<ConveyorInfo> = emptyList(),
    val agentModels: List<String> = emptyList(),        // agent-capture granularity (individual agents are runtime)
    val spaces: List<SpaceInfo> = emptyList(),
    val locations: List<String> = emptyList(),           // named spatial locations (DistancesModel + agent Context.location)
    val entityTypes: List<EntityTypeInfo> = emptyList(),  // entity/agent types + their processes (10.1a/10.1b)
    val locationInfos: List<LocationInfo> = emptyList()  // named locations with positions where known (G1)
) {
    /** True when [responseName] is backed by a time-weighted response (`TWResponse`) rather than a tally. */
    fun isTimeWeighted(responseName: String): Boolean = responseName in timeWeightedResponses

    /** Whether queue [name] is a reporting queue (default true); false ⇒ captured but not auto-placed (P5). */
    fun queueReports(name: String): Boolean = queueInfos.firstOrNull { it.name == name }?.reporting ?: true

    /** The element names of the given [kind], for matching a [CaptureSpec] selector or layout binding. */
    fun namesOf(kind: ElementKind): List<String> = when (kind) {
        ElementKind.QUEUE -> queues
        ElementKind.RESOURCE -> resources
        ElementKind.MOVABLE_RESOURCE -> movableResources
        ElementKind.RESPONSE -> responses
        ElementKind.COUNTER -> counters
        ElementKind.STATION -> stations
        ElementKind.NETWORK -> networks
        ElementKind.CONVEYOR -> conveyors
        ElementKind.AGENT -> agentModels
        ElementKind.SPACE -> spaces.map { it.name }
        ElementKind.ENTITY_TYPE -> entityTypes.map { it.typeName }
        // The PROCESS vocabulary is the composite "Type.process" identity (10.1d), disambiguating
        // same-named processes across types via the entityId -> type join.
        ElementKind.PROCESS -> entityTypes.flatMap { t -> t.processes.map { "${t.typeName}.${it.name}" } }
        ElementKind.LOCATION -> locations
    }
}

/** Whether [cls] is included for animation (a `@KSLAnimatedEntity(include=false)` opts it out). Defensive. */
private fun entityIncluded(cls: KClass<out ProcessModel.Entity>): Boolean =
    try { cls.findAnnotation<KSLAnimatedEntity>()?.include ?: true } catch (_: Throwable) { true }

/**
 * The animatable processes declared on entity class [cls] (10.1b): the `@KSLAnimatedProcess`-annotated
 * properties (using each annotation's `name`, defaulting to the property name, and its `include` flag); or — if
 * none are annotated — a best-effort fallback of the class's declared `KSLProcess`-valued properties, keyed by
 * property name (approximate: the property name may differ from the trace process name). `declaredMemberProperties`
 * (not `memberProperties`) is used so inherited framework process accessors are not mistaken for declarations.
 */
private fun processesOf(cls: KClass<out ProcessModel.Entity>): List<ProcessInfo> = try {
    val annotated = cls.declaredMemberProperties.mapNotNull { p ->
        p.findAnnotation<KSLAnimatedProcess>()?.let { a -> ProcessInfo(a.name.ifBlank { p.name }, a.include) }
    }
    annotated.ifEmpty {
        cls.declaredMemberProperties
            .filter { it.returnType.classifier == KSLProcess::class }
            .map { ProcessInfo(it.name, include = true) }
    }
} catch (_: Throwable) {
    emptyList()
}

/**
 * Enumerates this model's animatable elements (9A.3) by walking [Model.getModelElements] (structural
 * elements), each element's `spatialModel` (DistancesModel locations), and agent projections (spaces).
 * Reads only — no simulation. Safe to call on a probe model built from a bundle.
 */
/**
 * The [ElementKind] of a structural model element (the single source of truth shared by the inventory
 * extractor and the trace attachment's selective emitter registration, so they can't drift). Returns
 * `null` for elements that aren't directly selectable here — e.g. `Response`/`Counter`, which are taken
 * from the model's curated lists. `MovableResource` is matched before `Resource` (it is a `Resource`).
 */
fun elementKindOf(e: ModelElement): ElementKind? = when (e) {
    is EntityType -> ElementKind.ENTITY_TYPE
    is Queue<*> -> ElementKind.QUEUE
    is MovableResource -> ElementKind.MOVABLE_RESOURCE
    is Resource -> ElementKind.RESOURCE
    is SResource -> ElementKind.RESOURCE
    is Conveyor -> ElementKind.CONVEYOR
    is StationNetwork -> ElementKind.NETWORK
    is Station -> ElementKind.STATION
    // Network boundary ports (source/sink/transfer/ingress/NHPP-source) are nodes too, but extend ModelElement
    // rather than Station — classify them as placeable stations so the layout editor lists them (G3). They get
    // no station emitter below (they are not `is Station`); their flow is traced via the StationNetwork.
    is NetworkIngress -> ElementKind.STATION
    is NetworkEgress -> ElementKind.STATION
    is AgentModel -> ElementKind.AGENT
    else -> null
}

/**
 * The model's elements in parent-child order, for animation emitter registration (e.g. the trace
 * attachment). A public, animation-scoped view over the engine-internal element list, so a downstream
 * module can register emitters without `Model.getModelElements()` itself becoming public API.
 */
fun Model.animatableModelElements(): List<ModelElement> = getModelElements()

fun Model.animationInventory(): AnimationInventory {
    val queues = LinkedHashSet<String>()
    val queueInfos = mutableListOf<QueueInfo>()
    val resources = LinkedHashSet<String>()
    val movableResources = LinkedHashSet<String>()
    val movableHomeBases = LinkedHashMap<String, String>() // mover name -> home-base location name (10.8/C5)
    val stations = LinkedHashSet<String>()
    val networks = LinkedHashSet<String>()
    val conveyors = LinkedHashSet<String>()
    val conveyorInfos = mutableListOf<ConveyorInfo>()
    val agentModels = LinkedHashSet<String>()
    val spaces = LinkedHashMap<String, SpaceInfo>()
    val locations = LinkedHashSet<String>()
    val locationInfos = LinkedHashMap<String, LocationInfo>()
    // Entity types are collected as KClasses (keyed by simpleName) so their @KSLAnimatedProcess members can be
    // reflected (10.1b); EntityType elements are handled here rather than in the when below.
    val entityClasses = LinkedHashMap<String, KClass<out ProcessModel.Entity>>()

    for (e in getModelElements()) {
        when (elementKindOf(e)) {
            ElementKind.QUEUE -> {
                queues += e.name
                val q = e as ksl.modeling.queue.Queue<*>
                queueInfos += QueueInfo(q.name, reporting = q.defaultReportingOption, waitTimeStats = q.waitTimeStatOption)
            }
            ElementKind.MOVABLE_RESOURCE -> {
                movableResources += e.name
                (e as MovableResource).let { it.initialHomeBase ?: it.homeBase }?.name?.let { movableHomeBases[e.name] = it }
            }
            ElementKind.RESOURCE -> resources += e.name
            ElementKind.CONVEYOR -> {
                conveyors += e.name
                val conv = e as Conveyor
                conveyorInfos += ConveyorInfo(
                    name = conv.name, cellSize = conv.cellSize, accumulating = conv.accumulating,
                    segments = conv.segments.map { SegmentInfo(it.entryCell.location ?: "", it.exitCell.location ?: "", it.cells.size) }
                )
            }
            ElementKind.NETWORK -> networks += e.name
            ElementKind.STATION -> stations += e.name
            ElementKind.AGENT -> {
                agentModels += e.name
                for (ctx in (e as AgentModel).animationContexts()) {
                    for (p in ctx.projections) when (p) {
                        is GridProjection<*> ->
                            spaces.putIfAbsent(p.name, SpaceInfo(p.name, SpaceInfo.SpaceKind.GRID, cols = p.columns, rows = p.rows, torus = p.torus,
                                geometry = ctx.geometries[p.name]?.toSpec(p.name)))
                        is ContinuousProjection<*> ->
                            spaces.putIfAbsent(p.name, SpaceInfo(p.name, SpaceInfo.SpaceKind.CONTINUOUS,
                                xMin = p.xRange.start, xMax = p.xRange.endInclusive, yMin = p.yRange.start, yMax = p.yRange.endInclusive, torus = p.torus,
                                geometry = ctx.geometries[p.name]?.toSpec(p.name)))
                        // 3D spaces flattened to their x–y (col/row) footprint (G8); obstacles flatten too (voxel no-fly zones).
                        is ContinuousVolume<*> ->
                            spaces.putIfAbsent(p.name, SpaceInfo(p.name, SpaceInfo.SpaceKind.CONTINUOUS,
                                xMin = p.xRange.start, xMax = p.xRange.endInclusive, yMin = p.yRange.start, yMax = p.yRange.endInclusive, torus = p.torus,
                                geometry = ctx.geometries[p.name]?.toSpec(p.name)))
                        is VoxelProjection<*> ->
                            spaces.putIfAbsent(p.name, SpaceInfo(p.name, SpaceInfo.SpaceKind.GRID, cols = p.columns, rows = p.rows, torus = p.torus))
                        else -> {} // Network projections have no drawn backdrop yet
                    }
                    // Named locations (landmarks / points of interest) declared on the context (G1): carry their
                    // positions so auto-layout can place them without any hand-authored layout.
                    for ((locName, pt) in ctx.namedLocations) {
                        locations += locName
                        locationInfos.putIfAbsent(locName, LocationInfo(locName, pt.x, pt.y))
                    }
                }
            }
            else -> {} // RESPONSE/COUNTER come from the curated lists below; SPACE handled per-projection
        }
        // Any SpatialModel's named locations contribute (10.1g): DistancesModel exposes its distance-table
        // locations; other spatial models can override SpatialModel.namedLocations. Generalizes the former
        // DistancesModel-only path (8H/8K.6) without depending on a concrete spatial-model type.
        e.spatialModel?.namedLocations?.forEach { locations += it.name }
    }

    // Entity types (10.1a/b): declared entityType<T>() registrations give the KClass directly; a best-effort
    // reflection fallback adds nested Entity subclasses of each ProcessModel, so an undeclared model still
    // surfaces its types. Keyed by simpleName (== EntityCreated.entityType). Skips the framework base Entity and
    // swallows reflection failures so the probe never breaks.
    for (et in getModelElements().filterIsInstance<EntityType>()) entityClasses.putIfAbsent(et.name, et.entityClass)
    for (pm in getModelElements().filterIsInstance<ProcessModel>()) {
        try {
            for (nested in pm::class.nestedClasses) {
                if (nested == ProcessModel.Entity::class) continue
                if (!nested.isAbstract && nested.isSubclassOf(ProcessModel.Entity::class)) {
                    @Suppress("UNCHECKED_CAST")
                    val k = nested as KClass<out ProcessModel.Entity>
                    k.simpleName?.let { entityClasses.putIfAbsent(it, k) }
                }
            }
        } catch (_: Throwable) {
            // reflection unsupported for this ProcessModel's class — skip it
        }
    }
    // Keep all discovered types (10.1d): a @KSLAnimatedEntity(include=false) is recorded with include=false
    // rather than dropped, so the manifest can drive capture exclusion and the editor can show/toggle it.
    val entityTypes = entityClasses
        .map { (name, cls) -> EntityTypeInfo(name, processesOf(cls), include = entityIncluded(cls)) }

    return AnimationInventory(
        queues = queues.toList(),
        queueInfos = queueInfos.toList(),
        resources = resources.toList(),
        movableResources = movableResources.toList(),
        movableHomeBases = movableHomeBases.toMap(),
        responses = responses.map { it.name },
        timeWeightedResponses = responses.filterIsInstance<TWResponse>().map { it.name },
        counters = counters.map { it.name },
        stations = stations.toList(),
        networks = networks.toList(),
        conveyors = conveyors.toList(),
        conveyorInfos = conveyorInfos.toList(),
        agentModels = agentModels.toList(),
        spaces = spaces.values.toList(),
        locations = locations.toList(),
        entityTypes = entityTypes,
        locationInfos = locationInfos.values.toList()
    )
}
