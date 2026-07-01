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

import ksl.modeling.entity.BlockingQueue
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ResourceCIfc
import ksl.modeling.entity.ResourcePoolCIfc
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.entity.ResourceWithQCIfc
import ksl.modeling.entity.Signal
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MoveableResourceCIfc
import ksl.modeling.station.Station
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model

/** Restricts implicit receivers so nested animation-DSL blocks don't capture the wrong scope. */
@DslMarker
annotation class AnimationDsl

/**
 * Authors an [AnimationLayout] for this model with a DSL:
 *
 * ```kotlin
 * val layout = model.animation {
 *     title = "Pharmacy"
 *     size(800.0, 500.0)
 *     objectClass("Customer") { color = "#1f77b4"; shape = LayoutShape.CIRCLE }
 *     queue("PharmacistQ", 300.0, 250.0) { growthDegrees = 90.0 } // grows downward
 *     resource("Pharmacist", 360.0, 250.0)
 *     bar("WIP", 20.0, 400.0) { maxValue = 30.0 }
 *     clock(20.0, 20.0)
 * }
 * layout.writeToFile(Path.of("pharmacy.lay.json"))
 * ```
 *
 * The DSL only describes *intent* (positions, colors, displays); the emitters that produce the
 * trace are registered automatically by the animation controller. The layout keys to the trace
 * by the same names/types that appear in events. The model's base time unit is recorded for the
 * renderer.
 */
fun Model.animation(block: AnimationBuilder.() -> Unit): AnimationLayout {
    val builder = AnimationBuilder().apply { baseTimeUnit = this@animation.baseTimeUnit.name }
    builder.block()
    return builder.build()
}

/** The top-level animation-layout DSL builder. See [animation]. */
@AnimationDsl
class AnimationBuilder {
    var title: String? = null
    var baseTimeUnit: String? = null
    private var width: Double = 1000.0
    private var height: Double = 700.0

    private val objectClasses = mutableListOf<ObjectClassDefinition>()
    private val background = mutableListOf<BackgroundElement>()
    private val paths = mutableListOf<PathDefinition>()
    private val queues = mutableListOf<QueueLayoutElement>()
    private val resources = mutableListOf<ResourceLayoutElement>()
    private val stations = mutableListOf<StationLayoutElement>()
    private val locations = mutableListOf<LocationLayoutElement>()
    private val bars = mutableListOf<BarDisplayElement>()
    private val plots = mutableListOf<PlotDisplayElement>()
    private val clocks = mutableListOf<ClockDisplayElement>()
    private val spaces = mutableListOf<SpatialSpaceDescriptor>()
    private val spaceGeometry = mutableListOf<ksl.modeling.agent.GridGeometrySpec>()
    private val values = mutableListOf<ValueDisplayElement>()
    private val agentStateColors = mutableMapOf<String, String>()
    private val summaries = mutableListOf<SummaryDisplayElement>()
    private val histograms = mutableListOf<HistogramDisplayElement>()
    private val storages = mutableListOf<StorageLayoutElement>()
    private val movableResources = mutableListOf<MovableResourceLayoutElement>()

    /** Sets the drawing canvas size. */
    fun size(width: Double, height: Double) {
        this.width = width
        this.height = height
    }

    /** Defines the visual template for an entity/agent type (matches its trace type name). */
    fun objectClass(typeName: String, block: ObjectClassBuilder.() -> Unit = {}) {
        objectClasses.add(ObjectClassBuilder(typeName).apply(block).build())
    }

    /** Places a queue (by trace name). */
    fun queue(name: String, x: Double, y: Double, block: QueueBuilder.() -> Unit = {}) {
        queues.add(QueueBuilder(name, LayoutPoint(x, y)).apply(block).build())
    }

    /** Places a queue by passing the queue itself; its trace name is taken from [queue] (8K.1). */
    fun queue(queue: QueueCIfc<*>, x: Double, y: Double, block: QueueBuilder.() -> Unit = {}) =
        queue(queue.name, x, y, block)

    /** Places a resource (by trace name). */
    fun resource(name: String, x: Double, y: Double, block: ResourceBuilder.() -> Unit = {}) {
        resources.add(ResourceBuilder(name, LayoutPoint(x, y)).apply(block).build())
    }

    /** Places a resource by passing the resource itself; its trace name is taken from [resource] (8K.1). */
    fun resource(resource: ResourceCIfc, x: Double, y: Double, block: ResourceBuilder.() -> Unit = {}) =
        resource(resource.name, x, y, block)

    /**
     * Composes a `ResourceWithQ`: a resource at `(x, y)` plus its waiting queue, auto-named
     * `"$name:Q"` (KSL's convention). The queue **head** is placed [queueGap] units from the resource
     * along [growthDegrees] and the line extends further that way, so the front of the line is nearest
     * the server (8B.1/8I.6). [growthDegrees]: 0° = right, clockwise (default 180° = line extends left,
     * head at the resource). Saves authoring the resource and queue separately and keeping them in sync.
     */
    fun resourceWithQ(
        name: String, x: Double, y: Double,
        growthDegrees: Double = 180.0,
        queueGap: Double = 60.0,
        block: ResourceBuilder.() -> Unit = {}
    ) {
        resource(name, x, y, block)
        val (qx, qy) = queueHead(x, y, growthDegrees, queueGap)
        queue("$name:Q", qx, qy) { this.growthDegrees = growthDegrees }
    }

    /**
     * Composes a `ResourceWithQ` by passing the resource itself (8K.1). The resource trace name and its
     * **actual** waiting-queue name are taken from [resource] (so a custom queue name stays correct,
     * 8K.1b); placement matches the name-based [resourceWithQ].
     */
    fun resourceWithQ(
        resource: ResourceWithQCIfc, x: Double, y: Double,
        growthDegrees: Double = 180.0,
        queueGap: Double = 60.0,
        block: ResourceBuilder.() -> Unit = {}
    ) {
        resource(resource.name, x, y, block)
        val (qx, qy) = queueHead(x, y, growthDegrees, queueGap)
        queue(resource.waitingQ.name, qx, qy) { this.growthDegrees = growthDegrees }
    }

    /** The queue head: [queueGap] from `(x, y)` along [growthDegrees] (0° = right, clockwise). */
    private fun queueHead(x: Double, y: Double, growthDegrees: Double, queueGap: Double): Pair<Double, Double> {
        val rad = Math.toRadians(growthDegrees)
        return (x + queueGap * kotlin.math.cos(rad)) to (y + queueGap * kotlin.math.sin(rad))
    }

    /**
     * Declares a movable/transport resource to animate (8K.5). It has **no fixed position** — the
     * renderer draws it at its interpolated location from `SpatialElementMoved` events, including empty
     * repositioning. Keyed by the resource's trace [name].
     */
    fun movableResource(name: String, block: MovableResourceBuilder.() -> Unit = {}) {
        movableResources.add(MovableResourceBuilder(name).apply(block).build())
    }

    /** Declares a movable resource by passing it directly; its trace name is taken from it (8K.1/8K.5). */
    fun movableResource(resource: MoveableResourceCIfc, block: MovableResourceBuilder.() -> Unit = {}) =
        movableResource(resource.name, block)

    /** Places a station (by trace name). */
    fun station(name: String, x: Double, y: Double, label: String? = null) {
        stations.add(StationLayoutElement(name, LayoutPoint(x, y), label))
    }

    /** Places a named spatial location (a `LocationIfc`) at ([x], [y]) — a move endpoint / conveyor anchor /
     *  landmark. Distinct from a flow-network `station(...)`; drawn with its own glyph. */
    fun location(name: String, x: Double, y: Double, label: String? = null) {
        locations.add(LocationLayoutElement(name, LayoutPoint(x, y), label))
    }
    /** Declares a named location without a position (placed later, or proposed by MDS auto-layout). */
    fun location(name: String, label: String? = null) {
        locations.add(LocationLayoutElement(name, null, label))
    }

    /** Places a station by passing the station itself; its trace name is taken from [station] (8K.1). */
    fun station(station: Station, x: Double, y: Double, label: String? = null) =
        station(station.name, x, y, label)

    /**
     * Places a `location(...)` for every location of a [DistancesModel] at coordinates derived from its
     * distance matrix via classical MDS (8K.6b) — so a coordinate-free distance model is laid out without
     * hand-picking positions. Coordinates fit the canvas with [margin] padding; MDS orientation is arbitrary
     * (rotation/reflection-invariant). The location names match the move events' location names, so entity /
     * movable-resource moves resolve against these markers (8H.3).
     */
    fun placeLocations(distancesModel: DistancesModel, margin: Double = 60.0) {
        for ((name, p) in distancesModel.proposeCoordinates(width, height, margin)) location(name, p.x, p.y, label = name)
    }

    /** MDS-places a [DistancesModel]'s named places as `station(...)` markers. Deprecated: these are locations,
     *  not network stations — use `placeLocations` (its markers are read back with a location accessor, not
     *  `stationPosition`). Kept station-emitting so existing layouts that read positions back still work. */
    @Deprecated("Distance-model places are locations, not network stations; prefer placeLocations.")
    fun placeStations(distancesModel: DistancesModel, margin: Double = 60.0) {
        for ((name, p) in distancesModel.proposeCoordinates(width, height, margin)) station(name, p.x, p.y, label = name)
    }

    // ── Process-construct helpers (8K.3) ──────────────────────────────────────────────────────────
    // Each composes existing layout elements bound to the trace names these constructs already emit.

    /**
     * Places a `BlockingQueue`'s waiting items: the **channel** queue (`"$name:ChannelQ"`, items parked
     * waiting to be received) at `(x, y)`. Optionally also the sender (`:SenderQ`) and request
     * (`:RequestQ`) queues, stacked [gap] units below (8K.3). Binds by KSL's naming convention.
     */
    fun blockingQueue(
        name: String, x: Double, y: Double, growthDegrees: Double = 0.0,
        showSender: Boolean = false, showRequest: Boolean = false, gap: Double = 40.0,
        block: QueueBuilder.() -> Unit = {}
    ) {
        queue("$name:ChannelQ", x, y) { this.growthDegrees = growthDegrees; block() }
        var oy = y
        if (showSender) { oy += gap; queue("$name:SenderQ", x, oy) { this.growthDegrees = growthDegrees } }
        if (showRequest) { oy += gap; queue("$name:RequestQ", x, oy) { this.growthDegrees = growthDegrees } }
    }

    /** Places a `BlockingQueue` by passing it directly; the queue trace names are read from [bq] (8K.3). */
    fun blockingQueue(
        bq: BlockingQueue<*>, x: Double, y: Double, growthDegrees: Double = 0.0,
        showSender: Boolean = false, showRequest: Boolean = false, gap: Double = 40.0,
        block: QueueBuilder.() -> Unit = {}
    ) {
        queue(bq.channelQ.name, x, y) { this.growthDegrees = growthDegrees; block() }
        var oy = y
        if (showSender) { oy += gap; queue(bq.senderQ.name, x, oy) { this.growthDegrees = growthDegrees } }
        if (showRequest) { oy += gap; queue(bq.requestQ.name, x, oy) { this.growthDegrees = growthDegrees } }
    }

    /** Places a `Signal`'s waiting set: its hold queue (`"$name:HoldQ"`) at `(x, y)` (8K.3). */
    fun signal(name: String, x: Double, y: Double, growthDegrees: Double = 0.0, block: QueueBuilder.() -> Unit = {}) =
        queue("$name:HoldQ", x, y) { this.growthDegrees = growthDegrees; block() }

    /** Places a `Signal` by passing it directly; the hold-queue trace name is read from [signal] (8K.3). */
    fun signal(signal: Signal, x: Double, y: Double, growthDegrees: Double = 0.0, block: QueueBuilder.() -> Unit = {}) =
        queue(signal.waitingQ.name, x, y) { this.growthDegrees = growthDegrees; block() }

    /** Places a `HoldQueue` (a `Queue`) by trace name — readable sugar for [queue] (8K.3). */
    fun holdQueue(name: String, x: Double, y: Double, block: QueueBuilder.() -> Unit = {}) =
        queue(name, x, y, block)

    /** Places a `HoldQueue` by passing it directly; its trace name is read from [holdQueue] (8K.3). */
    fun holdQueue(holdQueue: HoldQueue, x: Double, y: Double, block: QueueBuilder.() -> Unit = {}) =
        queue(holdQueue.name, x, y, block)

    /**
     * Places each member of a `ResourcePool` as its own resource glyph (the pool itself is not a
     * `Resource`), laid out from `(x, y)` along [growthDegrees] with [unitGap] spacing; each member
     * animates its own busy/idle state. When [withBusyValue] is true, also shows the pool's busy-count
     * (`numBusyUnits`, `"$pool:NumBusy"`) just above the row (8K.3). Object-only: member names come from
     * the pool.
     */
    fun resourcePool(
        pool: ResourcePoolCIfc, x: Double, y: Double,
        unitGap: Double = 36.0, growthDegrees: Double = 0.0, withBusyValue: Boolean = true,
        block: ResourceBuilder.() -> Unit = {}
    ) {
        val rad = Math.toRadians(growthDegrees)
        val ux = kotlin.math.cos(rad)
        val uy = kotlin.math.sin(rad)
        pool.resources.forEachIndexed { i, r ->
            resource(r.name, x + i * unitGap * ux, y + i * unitGap * uy, block)
        }
        if (withBusyValue) value(pool.numBusyUnits.name, x, y - unitGap, label = "busy")
    }

    /**
     * Places a `ResourcePoolWithQ`: its member units (via [resourcePool]) plus the pool's waiting queue
     * (`"$pool:Q"`) whose head leads to the units, using the 8I.6 head placement (8K.3).
     */
    fun resourcePoolWithQ(
        pool: ResourcePoolWithQ, x: Double, y: Double,
        queueGap: Double = 60.0, queueGrowthDegrees: Double = 180.0,
        unitGap: Double = 36.0, unitGrowthDegrees: Double = 0.0, withBusyValue: Boolean = true,
        block: ResourceBuilder.() -> Unit = {}
    ) {
        resourcePool(pool, x, y, unitGap, unitGrowthDegrees, withBusyValue, block)
        val (qx, qy) = queueHead(x, y, queueGrowthDegrees, queueGap)
        queue(pool.waitingQ.name, qx, qy) { this.growthDegrees = queueGrowthDegrees }
    }

    /** Binds a live bar to a response/counter (by trace name). */
    fun bar(responseName: String, x: Double, y: Double, block: BarBuilder.() -> Unit = {}) {
        bars.add(BarBuilder(responseName, LayoutPoint(x, y)).apply(block).build())
    }

    /** Binds a live bar by passing the response/counter itself; its name is taken from it (8K.1). */
    fun bar(response: ResponseCIfc, x: Double, y: Double, block: BarBuilder.() -> Unit = {}) =
        bar(response.name, x, y, block)

    /** Binds a live bar by passing the counter itself; its name is taken from it (8K.1). */
    fun bar(counter: CounterCIfc, x: Double, y: Double, block: BarBuilder.() -> Unit = {}) =
        bar(counter.name, x, y, block)

    /** Binds a live time-series plot to a response/counter (by trace name). */
    fun plot(responseName: String, x: Double, y: Double, block: PlotBuilder.() -> Unit = {}) {
        plots.add(PlotBuilder(responseName, LayoutPoint(x, y)).apply(block).build())
    }

    /** Binds a live plot by passing the response/counter itself; its name is taken from it (8K.1). */
    fun plot(response: ResponseCIfc, x: Double, y: Double, block: PlotBuilder.() -> Unit = {}) =
        plot(response.name, x, y, block)

    /** Binds a live plot by passing the counter itself; its name is taken from it (8K.1). */
    fun plot(counter: CounterCIfc, x: Double, y: Double, block: PlotBuilder.() -> Unit = {}) =
        plot(counter.name, x, y, block)

    /**
     * Displays a labeled numeric readout of a response/counter value (no bar) — the display
     * primitive that [bar] composes. Bound by trace [responseName].
     */
    fun value(responseName: String, x: Double, y: Double, label: String? = null, decimals: Int = 1) {
        values.add(ValueDisplayElement(responseName, LayoutPoint(x, y), label, decimals))
    }

    /** Displays a response's value by passing the response itself; its name is taken from it (8K.1). */
    fun value(response: ResponseCIfc, x: Double, y: Double, label: String? = null, decimals: Int = 1) =
        value(response.name, x, y, label, decimals)

    /** Displays a counter's value by passing the counter itself; its name is taken from it (8K.1). */
    fun value(counter: CounterCIfc, x: Double, y: Double, label: String? = null, decimals: Int = 1) =
        value(counter.name, x, y, label, decimals)

    /**
     * Displays the live within-replication statistics (count, mean, min, max) of a response,
     * emitted by the engine (D11). Bound by trace [responseName].
     */
    fun summary(responseName: String, x: Double, y: Double, label: String? = null, decimals: Int = 2) {
        summaries.add(SummaryDisplayElement(responseName, LayoutPoint(x, y), label, decimals))
    }

    /** Within-replication statistics summary by passing the response itself; name taken from it (8K.1). */
    fun summary(response: ResponseCIfc, x: Double, y: Double, label: String? = null, decimals: Int = 2) =
        summary(response.name, x, y, label, decimals)

    /**
     * A live histogram of a response's observed values, binned **in the viewer** from the raw value
     * stream (D12). [bins] equal-width bins over the observed range.
     */
    fun histogram(responseName: String, x: Double, y: Double, bins: Int = 10, block: HistogramBuilder.() -> Unit = {}) {
        histograms.add(HistogramBuilder(responseName, LayoutPoint(x, y), bins, discrete = false).apply(block).build())
    }

    /** A live histogram by passing the response itself; its name is taken from it (8K.1). */
    fun histogram(response: ResponseCIfc, x: Double, y: Double, bins: Int = 10, block: HistogramBuilder.() -> Unit = {}) =
        histogram(response.name, x, y, bins, block)

    /**
     * A live integer-frequency chart of a response's observed values, tallied **in the viewer** from
     * the raw value stream (D12) — for integer-valued responses.
     */
    fun frequency(responseName: String, x: Double, y: Double, block: HistogramBuilder.() -> Unit = {}) {
        histograms.add(HistogramBuilder(responseName, LayoutPoint(x, y), bins = 10, discrete = true).apply(block).build())
    }

    /** A live integer-frequency chart by passing the response itself; its name is taken from it (8K.1). */
    fun frequency(response: ResponseCIfc, x: Double, y: Double, block: HistogramBuilder.() -> Unit = {}) =
        frequency(response.name, x, y, block)

    /** A live integer-frequency chart by passing the counter itself; its name is taken from it (8K.1). */
    fun frequency(counter: CounterCIfc, x: Double, y: Double, block: HistogramBuilder.() -> Unit = {}) =
        frequency(counter.name, x, y, block)

    /**
     * A holding area showing entities currently in a delay (8K.4). [name] matches a delay's
     * `suspensionName`; for **unnamed** delays, bind to the entity's **type name** instead (the renderer
     * keys those by type — a stable, shared default). Default style is a progress belt (entities drift
     * from entry to exit as the delay elapses). See [StorageBuilder] for style/capacity/layout options.
     */
    fun storage(name: String, x: Double, y: Double, block: StorageBuilder.() -> Unit = {}) {
        storages.add(StorageBuilder(name, LayoutPoint(x, y)).apply(block).build())
    }

    /** Alias for [storage] (8K.4) — reads naturally for synchronization-style waits. */
    fun holdingArea(name: String, x: Double, y: Double, block: StorageBuilder.() -> Unit = {}) =
        storage(name, x, y, block)

    /** Adds a clock display. */
    fun clock(x: Double, y: Double, label: String? = "Time", format: String = "0.0", fontSize: Double = 12.0) {
        clocks.add(ClockDisplayElement(LayoutPoint(x, y), format, label, fontSize))
    }

    /**
     * Colors agents whose current statechart state name contains [state] (case-insensitive) with
     * [color] (e.g. `agentStateColor("Working", "#2ca02c")`). State styling overrides the agent's
     * type color while it is in that state (8F.1).
     */
    fun agentStateColor(state: String, color: String) {
        agentStateColors[state] = color
    }

    /** Adds a static line/poly-line. */
    fun line(vararg points: Pair<Double, Double>, color: String = "#000000", strokeWidth: Double = 1.0) {
        val pts = points.map { LayoutPoint(it.first, it.second) }
        background.add(BackgroundElement(if (pts.size > 2) BackgroundKind.POLYLINE else BackgroundKind.LINE, pts, color = color, strokeWidth = strokeWidth))
    }

    /** Adds a static rectangle from one corner to the opposite corner. */
    fun rect(x1: Double, y1: Double, x2: Double, y2: Double, color: String = "#000000", strokeWidth: Double = 1.0) {
        background.add(BackgroundElement(BackgroundKind.RECT, listOf(LayoutPoint(x1, y1), LayoutPoint(x2, y2)), color = color, strokeWidth = strokeWidth))
    }

    /** Adds static text. */
    fun text(text: String, x: Double, y: Double, color: String = "#000000") {
        background.add(BackgroundElement(BackgroundKind.TEXT, listOf(LayoutPoint(x, y)), text = text, color = color))
    }

    /**
     * Adds a background image drawn into the world rectangle from top-left `(x1, y1)` to
     * bottom-right `(x2, y2)`. [path] is resolved relative to the layout file's directory (absolute
     * paths are used as-is).
     */
    fun image(path: String, x1: Double, y1: Double, x2: Double, y2: Double) {
        background.add(
            BackgroundElement(BackgroundKind.IMAGE, listOf(LayoutPoint(x1, y1), LayoutPoint(x2, y2)), imageRef = path)
        )
    }

    /** Defines a named movement path from a list of (x, y) points. */
    fun path(name: String, vararg points: Pair<Double, Double>) {
        paths.add(PathDefinition(name, points.map { LayoutPoint(it.first, it.second) }))
    }

    /**
     * Defines a path through already-placed stations by name, reusing their positions (8B.3) so the
     * belt/route geometry isn't re-authored. The referenced stations must be declared first.
     */
    fun pathThrough(name: String, vararg stationNames: String) {
        val pts = stationNames.map { sn ->
            stations.firstOrNull { it.stationName == sn }?.position
                ?: error("pathThrough('$name'): station '$sn' is not defined yet")
        }
        paths.add(PathDefinition(name, pts))
    }

    /**
     * Defines a **functional** path between two anchors (network stations or locations): the waypoints are the
     * intermediate points, and at replay a move between the two anchors follows the polyline. When bidirectional
     * (the default), the reverse move reuses the waypoints reversed. See `PathDefinition`. Use `AnchorRef.location`
     * / `AnchorRef.station` for the endpoints.
     */
    fun pathBetween(
        name: String,
        from: AnchorRef,
        to: AnchorRef,
        vararg waypoints: Pair<Double, Double>,
        bidirectional: Boolean = true
    ) {
        paths.add(
            PathDefinition(
                name,
                waypoints.map { LayoutPoint(it.first, it.second) },
                from = from,
                to = to,
                bidirectional = bidirectional
            )
        )
    }

    /** The position of a previously-placed station, for positioning elements relative to it (8B.3). */
    fun stationPosition(stationName: String): Pair<Double, Double> {
        val p = stations.firstOrNull { it.stationName == stationName }?.position
            ?: error("stationPosition('$stationName'): station is not defined")
        return p.x to p.y
    }

    /** Describes a continuous (Euclidean) agent space with the given bounds ([torus] wraps edges). */
    fun continuousSpace(name: String, xMin: Double, xMax: Double, yMin: Double, yMax: Double, torus: Boolean = false) {
        spaces.add(SpatialSpaceDescriptor.Continuous(name, xMin, xMax, yMin, yMax, torus))
    }

    /** Describes a grid agent space ([torus] wraps edges). */
    fun gridSpace(name: String, cols: Int, rows: Int, cellSize: Double, originX: Double = 0.0, originY: Double = 0.0, torus: Boolean = false) {
        spaces.add(SpatialSpaceDescriptor.Grid(name, cols, rows, cellSize, originX, originY, torus))
    }

    /** Authors grid obstacle/cost geometry for the space named in [spec] (P5a/G2). */
    fun gridGeometry(spec: ksl.modeling.agent.GridGeometrySpec) {
        spaceGeometry.add(spec)
    }

    /** Authors obstacles/costs for [spaceName] by extracting them from [graph] — the model-driven walls (P5a/G2). */
    fun obstaclesFrom(spaceName: String, graph: ksl.modeling.agent.GridGraph, originX: Double? = null, originY: Double? = null, cellSize: Double? = null) {
        spaceGeometry.add(graph.toSpec(spaceName, originX, originY, cellSize))
    }

    /** Describes a network space of nodes and edges. */
    fun networkSpace(name: String, block: NetworkSpaceBuilder.() -> Unit) {
        spaces.add(NetworkSpaceBuilder(name).apply(block).build())
    }

    fun build(): AnimationLayout = AnimationLayout(
        title, baseTimeUnit, width, height,
        objectClasses, background, paths, queues, resources, stations, bars, plots, clocks, spaces, values,
        agentStateColors, summaries, histograms, storages, movableResources,
        spaceGeometry = spaceGeometry, locations = locations
    )
}

/** Sub-builder for a [MovableResourceLayoutElement] (8K.5). */
@AnimationDsl
class MovableResourceBuilder(private val name: String) {
    var shape: LayoutShape = LayoutShape.SQUARE
    var color: String = "#8c564b"
    var size: Double = 16.0
    var imageRef: String? = null
    var label: String? = null
    fun build() = MovableResourceLayoutElement(name, shape, color, size, imageRef, label)
}

/** Sub-builder for a [StorageLayoutElement] (8K.4). */
@AnimationDsl
class StorageBuilder(private val suspensionName: String, private val position: LayoutPoint) {
    var style: StorageStyle = StorageStyle.PROGRESS_BELT
    var width: Double = 160.0
    var height: Double = 48.0
    /** Belt/line growth direction: 0° = right, clockwise (90° = down). */
    var growthDegrees: Double = 0.0
    var spacing: Double = 14.0
    /** Capacity for the gauge shown when the storage degrades past [maxShown]; 0 = unbounded. */
    var capacity: Int = 0
    var maxShown: Int = 30
    var byType: Boolean = true
    var label: String? = null
    fun build() = StorageLayoutElement(suspensionName, position, style, width, height, growthDegrees, spacing, capacity, maxShown, byType, label)
}

/** Sub-builder for an [ObjectClassDefinition]. */
@AnimationDsl
class ObjectClassBuilder(private val typeName: String) {
    var shape: LayoutShape = LayoutShape.CIRCLE
    var color: String = "#1f77b4"
    var size: Double = 10.0
    var imageRef: String? = null
    var label: String? = null
    fun build() = ObjectClassDefinition(typeName, shape, color, size, imageRef, label)
}

/** Sub-builder for a [QueueLayoutElement]. */
@AnimationDsl
class QueueBuilder(private val name: String, private val position: LayoutPoint) {
    /** Direction the line grows from its head: 0° = right, clockwise (90° = down, 180° = left, 270° = up). */
    var growthDegrees: Double = 0.0
    var spacing: Double = 12.0
    var maxShown: Int = 25
    fun build() = QueueLayoutElement(name, position, growthDegrees, spacing, maxShown)
}

/** Sub-builder for a [ResourceLayoutElement]. */
@AnimationDsl
class ResourceBuilder(private val name: String, private val position: LayoutPoint) {
    var size: Double = 20.0
    var idleColor: String = "#2ca02c"
    var busyColor: String = "#d62728"
    var failedColor: String = "#7f7f7f"
    var inactiveColor: String = "#cccccc"
    fun build() = ResourceLayoutElement(name, position, size, idleColor, busyColor, failedColor, inactiveColor)
}

/** Sub-builder for a [BarDisplayElement]. */
@AnimationDsl
class BarBuilder(private val responseName: String, private val position: LayoutPoint) {
    var width: Double = 120.0
    var height: Double = 20.0
    var maxValue: Double = 100.0
    var color: String = "#1f77b4"
    var label: String? = null
    fun build() = BarDisplayElement(responseName, position, width, height, maxValue, color, label)
}

/** Sub-builder for a [PlotDisplayElement]. */
@AnimationDsl
class PlotBuilder(private val responseName: String, private val position: LayoutPoint) {
    var width: Double = 220.0
    var height: Double = 110.0
    var windowDuration: Double? = null
    var color: String = "#1f77b4"
    var label: String? = null
    fun build() = PlotDisplayElement(responseName, position, width, height, windowDuration, color, label)
}

/** Sub-builder for a [HistogramDisplayElement]. */
@AnimationDsl
class HistogramBuilder(
    private val responseName: String,
    private val position: LayoutPoint,
    private var bins: Int,
    private val discrete: Boolean
) {
    var width: Double = 220.0
    var height: Double = 120.0
    var color: String = "#1f77b4"
    var label: String? = null
    fun bins(n: Int) { bins = n }
    fun build() = HistogramDisplayElement(responseName, position, width, height, bins, discrete, color, label)
}

/** Sub-builder for a [SpatialSpaceDescriptor.Network]. */
@AnimationDsl
class NetworkSpaceBuilder(private val name: String) {
    private val nodes = mutableListOf<NetworkNode>()
    private val edges = mutableListOf<NetworkEdge>()
    fun node(id: String, x: Double, y: Double) { nodes.add(NetworkNode(id, LayoutPoint(x, y))) }
    fun edge(from: String, to: String, weight: Double = 1.0) { edges.add(NetworkEdge(from, to, weight)) }
    fun build() = SpatialSpaceDescriptor.Network(name, nodes, edges)
}
