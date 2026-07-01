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

package ksl.app.swing.animation.app

import ksl.animation.AnimationLayout
import ksl.animation.BackgroundElement
import ksl.animation.BackgroundKind
import ksl.animation.BarDisplayElement
import ksl.animation.ClockDisplayElement
import ksl.animation.ConveyorLayoutElement
import ksl.animation.ElementLabel
import ksl.animation.ElementKind
import ksl.animation.HistogramDisplayElement
import ksl.animation.LayoutPoint
import ksl.animation.LayoutShape
import ksl.animation.MovableResourceLayoutElement
import ksl.animation.NetworkEdge
import ksl.animation.NetworkNode
import ksl.animation.ObjectClassDefinition
import ksl.animation.PathDefinition
import ksl.animation.PlotDisplayElement
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.animation.SpatialSpaceDescriptor
import ksl.animation.StorageLayoutElement
import ksl.animation.StorageStyle
import ksl.animation.StationLayoutElement
import ksl.animation.SummaryDisplayElement
import ksl.animation.ValueDisplayElement

/**
 * Pure, immutable editing transforms over an [AnimationLayout], keyed by an element's ([ElementKind],
 * name) — the same name space the inventory and capture spec use. The Layout-stage backbone (9F.2) and
 * editor UI (9F.3) build on these; keeping them as side-effect-free functions makes them unit-testable
 * without a controller or a display.
 *
 * Supported kinds are the positioned, single-representation process-view elements
 * ([SUPPORTED_LAYOUT_KINDS]): queues, resources, stations, and responses/counters (shown as value
 * read-outs). Movement glyphs (movable resources, agents, conveyors) and spatial frames need more than a
 * position and are added with the richer editing in a later stage. Calling a transform with an
 * unsupported kind throws [IllegalArgumentException].
 */
val SUPPORTED_LAYOUT_KINDS: Set<ElementKind> = setOf(
    ElementKind.QUEUE, ElementKind.RESOURCE, ElementKind.STATION, ElementKind.RESPONSE, ElementKind.COUNTER,
    ElementKind.MOVABLE_RESOURCE
)

/** How a response/counter is displayed (V5c). */
enum class ResponseDisplay { VALUE, BAR, PLOT, SUMMARY, HISTOGRAM }

/** All response/counter names placed across any display kind (value/bar/plot/summary/histogram). */
private fun AnimationLayout.responseNamesPlaced(): List<String> =
    (values.map { it.responseName } + bars.map { it.responseName } + plots.map { it.responseName } +
        summaries.map { it.responseName } + histograms.map { it.responseName }).distinct()

/** Names currently placed in this layout for [kind]. (Responses and counters span all display kinds.) */
fun AnimationLayout.placedNames(kind: ElementKind): List<String> = when (kind) {
    ElementKind.QUEUE -> queues.map { it.queueName }
    ElementKind.RESOURCE -> resources.map { it.resourceName }
    ElementKind.STATION -> stations.map { it.stationName }
    ElementKind.LOCATION -> locations.filter { it.position != null }.map { it.locationName }
    ElementKind.RESPONSE, ElementKind.COUNTER -> responseNamesPlaced()
    ElementKind.MOVABLE_RESOURCE -> movableResources.map { it.name }
    else -> throw IllegalArgumentException("Layout editing does not support kind $kind")
}

/** Whether an element of [kind] named [name] is currently placed. */
fun AnimationLayout.isPlaced(kind: ElementKind, name: String): Boolean = name in placedNames(kind)

/**
 * The position of the placed ([kind], [name]) element, or null when not placed — or, for movable resources,
 * always null: they have no fixed position (the renderer animates them at interpolated positions).
 */
fun AnimationLayout.positionOf(kind: ElementKind, name: String): LayoutPoint? = when (kind) {
    ElementKind.QUEUE -> queues.firstOrNull { it.queueName == name }?.position
    ElementKind.RESOURCE -> resources.firstOrNull { it.resourceName == name }?.position
    ElementKind.STATION -> stations.firstOrNull { it.stationName == name }?.position
    ElementKind.LOCATION -> locations.firstOrNull { it.locationName == name }?.position
    ElementKind.RESPONSE, ElementKind.COUNTER ->
        values.firstOrNull { it.responseName == name }?.position
            ?: bars.firstOrNull { it.responseName == name }?.position
            ?: plots.firstOrNull { it.responseName == name }?.position
            ?: summaries.firstOrNull { it.responseName == name }?.position
            ?: histograms.firstOrNull { it.responseName == name }?.position
    // Prefer the home-base's position when known (location first, then station), so the editor's selection/hit-box
    // tracks where the glyph is actually drawn (anchored to its home) rather than a vestigial parked position (10.8).
    ElementKind.MOVABLE_RESOURCE -> movableResources.firstOrNull { it.name == name }?.let { mr ->
        mr.homeBase?.let { hb ->
            locations.firstOrNull { it.locationName == hb }?.position ?: stations.firstOrNull { it.stationName == hb }?.position
        } ?: mr.position
    }
    else -> throw IllegalArgumentException("Layout editing does not support kind $kind")
}

/** Which display kind a placed response/counter uses, or null when not placed. */
fun AnimationLayout.responseDisplayOf(name: String): ResponseDisplay? = when {
    values.any { it.responseName == name } -> ResponseDisplay.VALUE
    bars.any { it.responseName == name } -> ResponseDisplay.BAR
    plots.any { it.responseName == name } -> ResponseDisplay.PLOT
    summaries.any { it.responseName == name } -> ResponseDisplay.SUMMARY
    histograms.any { it.responseName == name } -> ResponseDisplay.HISTOGRAM
    else -> null
}

/** A copy with the canvas resized (clamped to a sane minimum). */
fun AnimationLayout.withCanvasSize(width: Double, height: Double): AnimationLayout =
    copy(width = width.coerceAtLeast(100.0), height = height.coerceAtLeast(100.0))

/** A copy with the ([kind], [name]) element moved to ([x], [y]); unchanged when it is not placed. */
fun AnimationLayout.withElementMoved(kind: ElementKind, name: String, x: Double, y: Double): AnimationLayout {
    val p = LayoutPoint(x, y)
    return when (kind) {
        ElementKind.QUEUE -> copy(queues = queues.map { if (it.queueName == name) it.copy(position = p) else it })
        ElementKind.RESOURCE -> copy(resources = resources.map { if (it.resourceName == name) it.copy(position = p) else it })
        ElementKind.STATION -> copy(stations = stations.map { if (it.stationName == name) it.copy(position = p) else it })
        ElementKind.RESPONSE, ElementKind.COUNTER -> copy(
            values = values.map { if (it.responseName == name) it.copy(position = p) else it },
            bars = bars.map { if (it.responseName == name) it.copy(position = p) else it },
            plots = plots.map { if (it.responseName == name) it.copy(position = p) else it },
            summaries = summaries.map { if (it.responseName == name) it.copy(position = p) else it },
            histograms = histograms.map { if (it.responseName == name) it.copy(position = p) else it }
        )
        ElementKind.MOVABLE_RESOURCE -> copy(movableResources = movableResources.map { if (it.name == name) it.copy(position = p) else it })
        else -> throw IllegalArgumentException("Layout editing does not support kind $kind")
    }
}

/** A copy with the ([kind], [name]) element removed; unchanged when it is not placed. */
fun AnimationLayout.withElementRemoved(kind: ElementKind, name: String): AnimationLayout = when (kind) {
    ElementKind.QUEUE -> copy(queues = queues.filterNot { it.queueName == name })
    ElementKind.RESOURCE -> copy(resources = resources.filterNot { it.resourceName == name })
    ElementKind.STATION -> copy(stations = stations.filterNot { it.stationName == name })
    ElementKind.RESPONSE, ElementKind.COUNTER -> copy(
        values = values.filterNot { it.responseName == name },
        bars = bars.filterNot { it.responseName == name },
        plots = plots.filterNot { it.responseName == name },
        summaries = summaries.filterNot { it.responseName == name },
        histograms = histograms.filterNot { it.responseName == name }
    )
    ElementKind.MOVABLE_RESOURCE -> copy(movableResources = movableResources.filterNot { it.name == name })
    else -> throw IllegalArgumentException("Layout editing does not support kind $kind")
}

/** A copy showing response/counter [name] as the given [display] kind at ([x], [y]) (replacing any prior display). */
fun AnimationLayout.withResponseDisplay(name: String, display: ResponseDisplay, x: Double, y: Double, discrete: Boolean = false): AnimationLayout {
    val cleared = withElementRemoved(ElementKind.RESPONSE, name)
    val p = LayoutPoint(x, y)
    return when (display) {
        ResponseDisplay.VALUE -> cleared.copy(values = cleared.values + ValueDisplayElement(responseName = name, position = p))
        ResponseDisplay.BAR -> cleared.copy(bars = cleared.bars + BarDisplayElement(responseName = name, position = p))
        ResponseDisplay.PLOT -> cleared.copy(plots = cleared.plots + PlotDisplayElement(responseName = name, position = p))
        ResponseDisplay.SUMMARY -> cleared.copy(summaries = cleared.summaries + SummaryDisplayElement(responseName = name, position = p))
        // discrete = an integer-frequency histogram (the "frequency" display) vs a continuous histogram.
        ResponseDisplay.HISTOGRAM -> cleared.copy(histograms = cleared.histograms + HistogramDisplayElement(responseName = name, position = p, discrete = discrete))
    }
}

/** Whether response [name] is currently shown as a discrete (integer-frequency) histogram. */
fun AnimationLayout.responseHistogramIsDiscrete(name: String): Boolean =
    histograms.firstOrNull { it.responseName == name }?.discrete ?: false

/** A copy with bar [name]'s styling updated (max value, color, size) — chart styling parity with the DSL. */
fun AnimationLayout.withBarStyle(name: String, maxValue: Double, color: String, width: Double, height: Double): AnimationLayout =
    copy(bars = bars.map {
        if (it.responseName == name) it.copy(maxValue = maxValue, color = color, width = width.coerceAtLeast(4.0), height = height.coerceAtLeast(2.0)) else it
    })

/** A copy with plot [name]'s styling updated (color, rolling window, size). [window] = null shows the whole run. */
fun AnimationLayout.withPlotStyle(name: String, color: String, window: Double?, width: Double, height: Double): AnimationLayout =
    copy(plots = plots.map {
        if (it.responseName == name) it.copy(color = color, windowDuration = window, width = width.coerceAtLeast(4.0), height = height.coerceAtLeast(4.0)) else it
    })

/** A copy with histogram [name]'s styling updated (bin count, color, size, discrete/frequency form). */
fun AnimationLayout.withHistogramStyle(name: String, bins: Int, color: String, width: Double, height: Double, discrete: Boolean): AnimationLayout =
    copy(histograms = histograms.map {
        if (it.responseName == name) it.copy(bins = bins.coerceAtLeast(1), color = color, width = width.coerceAtLeast(4.0), height = height.coerceAtLeast(4.0), discrete = discrete) else it
    })

/** A copy with the value/summary read-out [name]'s decimal places updated. */
fun AnimationLayout.withValueDecimals(name: String, decimals: Int): AnimationLayout {
    val d = decimals.coerceIn(0, 6)
    return copy(
        values = values.map { if (it.responseName == name) it.copy(decimals = d) else it },
        summaries = summaries.map { if (it.responseName == name) it.copy(decimals = d) else it }
    )
}

/**
 * The placed element nearest to world point ([x], [y]) within [radius] world units, or null when none is
 * close enough. Used by the editor's drag-to-move hit-test; pure, so it is tested without a canvas.
 */
fun AnimationLayout.pickElement(x: Double, y: Double, radius: Double): Pair<ElementKind, String>? {
    var best: Pair<ElementKind, String>? = null
    var bestDistance = radius
    for (kind in SUPPORTED_LAYOUT_KINDS) {
        for (name in placedNames(kind)) {
            val p = positionOf(kind, name) ?: continue
            val d = kotlin.math.hypot(p.x - x, p.y - y)
            if (d <= bestDistance) { bestDistance = d; best = kind to name }
        }
    }
    return best
}

/** A copy with an agent state→color mapping set (V7); e.g. "Infected" -> "#d62728". */
fun AnimationLayout.withAgentStateColor(state: String, color: String): AnimationLayout =
    copy(agentStateColors = agentStateColors + (state to color))

/** A copy with the agent state-color mapping for [state] removed. */
fun AnimationLayout.withAgentStateColorRemoved(state: String): AnimationLayout =
    copy(agentStateColors = agentStateColors - state)

/** A copy with an entity process→color mapping set (10.1e); e.g. "Triage" -> "#ff7f0e". */
fun AnimationLayout.withProcessColor(process: String, color: String): AnimationLayout =
    copy(processColors = processColors + (process to color))

/** A copy with the process-color mapping for [process] removed. */
fun AnimationLayout.withProcessColorRemoved(process: String): AnimationLayout =
    copy(processColors = processColors - process)

/** A copy with a continuous space [name] of the given bounds (replaces any space of that name). */
fun AnimationLayout.withContinuousSpace(name: String, xMin: Double, xMax: Double, yMin: Double, yMax: Double, torus: Boolean = false): AnimationLayout =
    copy(spaces = spaces.filterNot { it.name == name } + SpatialSpaceDescriptor.Continuous(name, xMin, xMax, yMin, yMax, torus))

/** A copy with a grid space [name] of [cols]×[rows] cells of [cellSize] at ([originX],[originY]) (replaces any of that name). */
fun AnimationLayout.withGridSpace(name: String, cols: Int, rows: Int, cellSize: Double, originX: Double = 0.0, originY: Double = 0.0, torus: Boolean = false): AnimationLayout =
    copy(spaces = spaces.filterNot { it.name == name } + SpatialSpaceDescriptor.Grid(name, cols, rows, cellSize, originX, originY, torus))

/** A copy with a network space [name] of [nodes] and [edges] (replaces any space of that name). */
fun AnimationLayout.withNetworkSpace(name: String, nodes: List<NetworkNode>, edges: List<NetworkEdge>): AnimationLayout =
    copy(spaces = spaces.filterNot { it.name == name } + SpatialSpaceDescriptor.Network(name, nodes, edges))

/** A copy with the space [name] removed. */
fun AnimationLayout.withSpaceRemoved(name: String): AnimationLayout =
    copy(spaces = spaces.filterNot { it.name == name })

/** A copy with the given obstacle/cost overlays merged in, replacing any existing overlay of the same space (P5c/G2). */
fun AnimationLayout.withSpaceGeometryImported(specs: List<ksl.modeling.agent.GridGeometrySpec>): AnimationLayout {
    val names = specs.map { it.spaceName }.toSet()
    return copy(spaceGeometry = spaceGeometry.filterNot { it.spaceName in names } + specs)
}

/** A copy with the obstacle/cost overlay for [spaceName] removed (P5c/G2). */
fun AnimationLayout.withSpaceGeometryRemoved(spaceName: String): AnimationLayout =
    copy(spaceGeometry = spaceGeometry.filterNot { it.spaceName == spaceName })

/** A copy with conveyor [element] added/replaced (keyed by conveyorName) — 10.5d. */
fun AnimationLayout.withConveyorLayout(element: ConveyorLayoutElement): AnimationLayout =
    copy(conveyors = conveyors.filterNot { it.conveyorName == element.conveyorName } + element)

// ── Storages (#15): author a named delay / type holding area drawn by the renderer (no core change needed) ──

/** A copy with a storage for [suspensionName] added/replaced, spanning the rectangle at ([x],[y]) of [width]×[height]. */
fun AnimationLayout.withStorageAdded(
    suspensionName: String, x: Double, y: Double, width: Double, height: Double,
    style: StorageStyle, spacing: Double, maxShown: Int, capacity: Int, byType: Boolean, label: String?
): AnimationLayout = copy(storages = storages.filterNot { it.suspensionName == suspensionName } +
    StorageLayoutElement(suspensionName, LayoutPoint(x, y), style, width.coerceAtLeast(20.0), height.coerceAtLeast(16.0),
        growthDegrees = 0.0, spacing = spacing, capacity = capacity, maxShown = maxShown, byType = byType, label = label))

/** A copy with storage [suspensionName] moved to ([x],[y]). */
fun AnimationLayout.withStorageMoved(suspensionName: String, x: Double, y: Double): AnimationLayout =
    copy(storages = storages.map { if (it.suspensionName == suspensionName) it.copy(position = LayoutPoint(x, y)) else it })

/** A copy with storage [suspensionName] removed. */
fun AnimationLayout.withStorageRemoved(suspensionName: String): AnimationLayout =
    copy(storages = storages.filterNot { it.suspensionName == suspensionName })

/** A copy with storage [suspensionName]'s [style] set. */
fun AnimationLayout.withStorageStyle(suspensionName: String, style: StorageStyle): AnimationLayout =
    copy(storages = storages.map { if (it.suspensionName == suspensionName) it.copy(style = style) else it })

/** A copy with all of storage [suspensionName]'s editable properties replaced (the double-click editor, G6). */
fun AnimationLayout.withStorageProperties(
    suspensionName: String, x: Double, y: Double, style: StorageStyle, width: Double, height: Double,
    growthDegrees: Double, spacing: Double, maxShown: Int, capacity: Int, byType: Boolean, label: String?
): AnimationLayout = copy(storages = storages.map {
    if (it.suspensionName != suspensionName) it
    else it.copy(
        position = LayoutPoint(x, y), style = style, width = width.coerceAtLeast(20.0), height = height.coerceAtLeast(16.0),
        growthDegrees = growthDegrees, spacing = spacing.coerceAtLeast(1.0), maxShown = maxShown.coerceAtLeast(1),
        capacity = capacity.coerceAtLeast(0), byType = byType, label = label?.ifBlank { null }
    )
})

/** This element's label override, or null (defaults apply) — 10.8/C3. */
fun AnimationLayout.labelFor(kind: ElementKind, name: String): ElementLabel? =
    labels.firstOrNull { it.kind == kind && it.name == name }

/** A copy with the ([kind],[name]) label override set/replaced — 10.8/C3, batch 4 (name + value placement). */
fun AnimationLayout.withElementLabel(
    kind: ElementKind, name: String, text: String?, dx: Double, dy: Double, visible: Boolean,
    valueDx: Double, valueDy: Double, valueVisible: Boolean
): AnimationLayout =
    copy(labels = labels.filterNot { it.kind == kind && it.name == name } +
        ElementLabel(kind, name, text?.ifBlank { null }, dx, dy, visible, valueDx, valueDy, valueVisible))

/** A copy with the conveyor layout [name] removed — 10.5d. */
fun AnimationLayout.withConveyorRemoved(name: String): AnimationLayout =
    copy(conveyors = conveyors.filterNot { it.conveyorName == name })

/** A copy with the [waypoints] of conveyor [name]'s segment at [segmentIndex] replaced — 10.5d. */
fun AnimationLayout.withConveyorSegmentWaypoints(name: String, segmentIndex: Int, waypoints: List<LayoutPoint>): AnimationLayout =
    copy(conveyors = conveyors.map { c ->
        if (c.conveyorName != name || segmentIndex !in c.segments.indices) c
        else c.copy(segments = c.segments.mapIndexed { i, s -> if (i == segmentIndex) s.copy(waypoints = waypoints) else s })
    })

/** A copy with an object-class style for entity/agent [typeName] (replaces any existing style of that name). */
fun AnimationLayout.withObjectClass(
    typeName: String, shape: LayoutShape, color: String, size: Double, imageRef: String? = null
): AnimationLayout =
    copy(objectClasses = objectClasses.filterNot { it.typeName == typeName } +
        ObjectClassDefinition(typeName = typeName, shape = shape, color = color, size = size.coerceAtLeast(2.0), imageRef = imageRef))

/** A copy with the object-class style for [typeName] removed. */
fun AnimationLayout.withObjectClassRemoved(typeName: String): AnimationLayout =
    copy(objectClasses = objectClasses.filterNot { it.typeName == typeName })

/** A copy with a background image added spanning the rectangle ([x1],[y1])–([x2],[y2]). */
fun AnimationLayout.withBackgroundImage(imageRef: String, x1: Double, y1: Double, x2: Double, y2: Double): AnimationLayout =
    copy(background = background + BackgroundElement(
        kind = BackgroundKind.IMAGE, points = listOf(LayoutPoint(x1, y1), LayoutPoint(x2, y2)), imageRef = imageRef))

/** A copy with a background rectangle spanning ([x1],[y1])–([x2],[y2]) — e.g. a hand-drawn wall (G1). */
fun AnimationLayout.withBackgroundRect(x1: Double, y1: Double, x2: Double, y2: Double, color: String, strokeWidth: Double): AnimationLayout =
    copy(background = background + BackgroundElement(
        kind = BackgroundKind.RECT, points = listOf(LayoutPoint(x1, y1), LayoutPoint(x2, y2)),
        color = color, strokeWidth = strokeWidth.coerceAtLeast(0.5)))

/** A copy with a background line segment from ([x1],[y1]) to ([x2],[y2]) (G1). */
fun AnimationLayout.withBackgroundLine(x1: Double, y1: Double, x2: Double, y2: Double, color: String, strokeWidth: Double): AnimationLayout =
    copy(background = background + BackgroundElement(
        kind = BackgroundKind.LINE, points = listOf(LayoutPoint(x1, y1), LayoutPoint(x2, y2)),
        color = color, strokeWidth = strokeWidth.coerceAtLeast(0.5)))

/** A copy with a background text label [text] anchored at ([x],[y]) (G1). */
fun AnimationLayout.withBackgroundText(
    x: Double, y: Double, text: String, color: String, fontSize: Double = 12.0, fontFamily: String? = null
): AnimationLayout = copy(background = background + BackgroundElement(
    kind = BackgroundKind.TEXT, points = listOf(LayoutPoint(x, y)), text = text, color = color,
    fontSize = fontSize, fontFamily = fontFamily))

/** A copy with the background element at [index] removed (no-op when out of range). */
fun AnimationLayout.withBackgroundRemovedAt(index: Int): AnimationLayout =
    if (index !in background.indices) this else copy(background = background.filterIndexed { i, _ -> i != index })

/** A copy with every point of the background element at [index] translated by ([dx],[dy]) — drag-to-move a shape. */
fun AnimationLayout.withBackgroundMovedAt(index: Int, dx: Double, dy: Double): AnimationLayout =
    if (index !in background.indices) this
    else copy(background = background.mapIndexed { i, b ->
        if (i != index) b else b.copy(points = b.points.map { LayoutPoint(it.x + dx, it.y + dy) })
    })

/** A copy with the background element at [index] replaced by [element] (no-op when out of range) — shape editor. */
fun AnimationLayout.withBackgroundReplacedAt(index: Int, element: BackgroundElement): AnimationLayout =
    if (index !in background.indices) this
    else copy(background = background.mapIndexed { i, b -> if (i == index) element else b })

// ── Clock display widgets (model-less, index-keyed — mirrors the background-text widget above) ──────

/** A copy with a clock display ([label]/[format], size [fontSize]) anchored at ([x],[y]). */
fun AnimationLayout.withClock(
    x: Double, y: Double, label: String? = "Time", format: String = "0.0", fontSize: Double = 12.0
): AnimationLayout = copy(clocks = clocks + ClockDisplayElement(LayoutPoint(x, y), format, label, fontSize))

/** A copy with the clock at [index] removed (no-op when out of range). */
fun AnimationLayout.withClockRemovedAt(index: Int): AnimationLayout =
    if (index !in clocks.indices) this else copy(clocks = clocks.filterIndexed { i, _ -> i != index })

/** A copy with the clock at [index] translated by ([dx],[dy]) — canvas drag-to-move. */
fun AnimationLayout.withClockMovedAt(index: Int, dx: Double, dy: Double): AnimationLayout =
    if (index !in clocks.indices) this
    else copy(clocks = clocks.mapIndexed { i, c ->
        if (i != index) c else c.copy(position = LayoutPoint(c.position.x + dx, c.position.y + dy))
    })

/** A copy with the clock at [index] replaced by [element] (no-op when out of range) — clock editor / resize. */
fun AnimationLayout.withClockReplacedAt(index: Int, element: ClockDisplayElement): AnimationLayout =
    if (index !in clocks.indices) this
    else copy(clocks = clocks.mapIndexed { i, c -> if (i == index) element else c })

/** A copy with a path [name] of [points] (replaces any path of the same name). */
fun AnimationLayout.withPath(name: String, points: List<LayoutPoint>): AnimationLayout =
    copy(paths = paths.filterNot { it.name == name } + PathDefinition(name, points))

/** A copy with the path [name] removed. */
fun AnimationLayout.withPathRemoved(name: String): AnimationLayout =
    copy(paths = paths.filterNot { it.name == name })

/** A copy with the queue [name]'s drawing properties updated (direction, member spacing, max shown). */
fun AnimationLayout.withQueueProperties(name: String, growthDegrees: Double, spacing: Double, maxShown: Int): AnimationLayout =
    copy(queues = queues.map {
        if (it.queueName == name) it.copy(growthDegrees = growthDegrees, spacing = spacing.coerceAtLeast(1.0), maxShown = maxShown.coerceAtLeast(1)) else it
    })

/** A copy with the resource [name]'s glyph size updated. */
fun AnimationLayout.withResourceSize(name: String, size: Double): AnimationLayout =
    copy(resources = resources.map { if (it.resourceName == name) it.copy(size = size.coerceAtLeast(2.0)) else it })

/** A copy with the resource [name]'s live "busy/capacity" read-out toggled (P4). */
fun AnimationLayout.withResourceShowValue(name: String, show: Boolean): AnimationLayout =
    copy(resources = resources.map { if (it.resourceName == name) it.copy(showValue = show) else it })

/** A copy with the resource [name]'s per-state image refs set (null clears that state's image) — 10.7. */
fun AnimationLayout.withResourceImages(
    name: String, idle: String?, busy: String?, failed: String?, inactive: String?
): AnimationLayout = copy(resources = resources.map {
    if (it.resourceName == name) it.copy(idleImage = idle, busyImage = busy, failedImage = failed, inactiveImage = inactive) else it
})

/** A copy with a default-styled ([kind], [name]) element added at ([x], [y]); unchanged when already placed. */
fun AnimationLayout.withElementAdded(kind: ElementKind, name: String, x: Double, y: Double): AnimationLayout {
    require(kind in SUPPORTED_LAYOUT_KINDS) { "Layout editing does not support kind $kind" }
    if (isPlaced(kind, name)) return this
    val p = LayoutPoint(x, y)
    return when (kind) {
        // Editor default: tail ---- head, left to right (growthDegrees 180), showing up to 10 members.
        ElementKind.QUEUE -> copy(queues = queues + QueueLayoutElement(queueName = name, position = p, growthDegrees = 180.0, maxShown = 10))
        ElementKind.RESOURCE -> copy(resources = resources + ResourceLayoutElement(resourceName = name, position = p))
        ElementKind.STATION -> copy(stations = stations + StationLayoutElement(stationName = name, position = p, label = name))
        ElementKind.RESPONSE, ElementKind.COUNTER -> copy(values = values + ValueDisplayElement(responseName = name, position = p))
        ElementKind.MOVABLE_RESOURCE -> copy(movableResources = movableResources + MovableResourceLayoutElement(name = name, position = p))
        else -> throw IllegalArgumentException("Layout editing does not support kind $kind")
    }
}
