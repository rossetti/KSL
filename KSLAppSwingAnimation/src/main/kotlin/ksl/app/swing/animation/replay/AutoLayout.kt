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
import ksl.animation.AnimationLayout
import ksl.animation.ConveyorLayoutElement
import ksl.animation.LayoutPoint
import ksl.animation.LocationLayoutElement
import ksl.animation.MovableResourceLayoutElement
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.animation.NetworkStationLayoutElement
import ksl.animation.StorageLayoutElement
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds an [AnimationLayout] from what a trace contains, so a trace opened with no accompanying layout (an
 * opened trace, or the unified Auto Layout fallback) still animates instead of showing a blank canvas. A
 * single streaming pass mines the trace (see the trace accumulators): movable/transport resources become
 * `movableResource` glyphs seeded at their mined home position; the named locations they travel between become
 * station anchors at their real centroids (the renderer interpolates movers between those); and the canvas is
 * framed to the declared spatial space unioned with where movement actually happened (9F.6 / UX U3).
 *
 * Non-Cartesian models (distance-, network-, conveyor-based) emit `NaN` coordinates (see `LocationIfc.x`), so
 * they carry no extent or centroids; for those the layout uses flow-ordered columns of resources/queues, plus
 * network stations (from `StationEntered`) in a left-to-right flow lane and conveyors (from `ConveyorDefined`)
 * as straight belts spaced by cell index, with any remaining name-only mover locations on a ring. Response
 * statistics are not placed (a model can expose dozens, crushing the auto-grid).
 */
fun ReplayModel.autoLayout(events: List<AnimationEvent>, title: String? = null): AnimationLayout {
    // One streaming pass mines positions, named travel locations, movers, and agent states. Non-Cartesian
    // models emit NaN coordinates, which the accumulators skip — so observed/centroids/homes are populated
    // only for coordinate-based models (see TraceAccumulators).
    val extentAcc = ObservedExtent()
    val locationAcc = LocationCentroids()
    val moverAcc = MoverHomes()
    val stateAcc = AgentStateNames()
    val flowAcc = FlowOrder()
    val typeAcc = ObjectTypeNames()
    val stationFlowAcc = StationFlow()
    val conveyorAcc = ConveyorAnchors()
    val storageAcc = DelayStorages()
    StreamingTraceMiner(listOf(extentAcc, locationAcc, moverAcc, stateAcc, flowAcc, typeAcc, stationFlowAcc, conveyorAcc, storageAcc))
        .run(events.asSequence())

    val observed = extentAcc.result()
    val location = locationAcc.result()
    val mover = moverAcc.result()
    val flow = flowAcc.result()
    val stationFlow = stationFlowAcc.result()
    val conveyorAnchors = conveyorAcc.result()
    val storageKeys = storageAcc.result()

    // Agent-resources (MovableAgentResource/AgentResource) emit resource + queue events AND animate as agents, so
    // don't auto-place a static resource glyph + request queue for them (they'd double the moving-agent glyph). A
    // modeler can still add either from the editor's Resource/Queue tools. Their resource name == their agent name
    // (both agent.name); their default request queue is "<name>:Q".
    val staticResources = resourceNames.filterNot { it in agentNames }
    // Conveyor internal hold/access queues animate as part of the belt, so don't auto-place them either (still
    // editor-placeable). Match their stable name suffixes, scoped to a mined conveyor prefix to avoid false hits.
    val conveyorNames = conveyorAnchors.keys
    val conveyorQueueSuffixes = listOf(":ExitingHoldQ", ":RidingHoldQ", ":AccessingHoldQ", ":AccessQ")
    val staticQueues = queueNames
        .filterNot { qn -> agentNames.any { qn == "$it:Q" } }
        .filterNot { qn -> conveyorQueueSuffixes.any { qn.endsWith(it) } &&
            (conveyorNames.isEmpty() || conveyorNames.any { qn.startsWith("$it:") }) }
    // Auto-placed queues show a shorter run than a hand-authored queue (whose maxShown defaults to 25): a starter
    // layout shouldn't draw a long extent line for a queue that's usually short (Ex01).
    val autoQueueMaxShown = 10

    // Seed an editable, space-scaled object-class per discovered entity/agent type, so glyphs are sized to the
    // model (not the invisible default) and appearance becomes explicit, persisted layout data (C1).
    val objectTypes = typeAcc.result()
    val glyphSize = objectGlyphSize(effectiveSpaces)

    // Agent state colors from the trace, so agent-state coloring works in Quick view (P5): assign a palette to the
    // distinct states the trace reports (the renderer falls back to the type color for any unmapped state). Sort the
    // states first so the assignment is deterministic across runs, not dependent on trace first-seen order.
    val agentStateColors = stateAcc.result().sorted().mapIndexed { i, s -> s to PALETTE[i % PALETTE.size] }.associate { it }

    // Movers carry their mined home position where the trace had finite coordinates; name-only movers
    // (non-Cartesian) get no spot and animate by path.
    val movers = mover.names.sorted().map { MovableResourceLayoutElement(name = it, position = mover.homes[it]) }

    // Frame the canvas to the declared spatial space (grid/continuous), if any, unioned with where movement
    // actually happened — so agents/movers fill the view instead of clumping in a corner (P5).
    val spaceBox = effectiveSpaces.mapNotNull { spaceBounds(it) }
        .reduceOrNull { a, b -> a.createUnion(b) as java.awt.geom.Rectangle2D.Double }
    val frame = listOfNotNull(spaceBox, observed)
        .reduceOrNull { a, b -> a.createUnion(b) as java.awt.geom.Rectangle2D.Double }
    if (frame != null) {
        // Coordinate-aware layout: process elements go in a side strip scaled to the frame; named locations are
        // placed at their real centroids (the renderer interpolates movers between them).
        val margin = (maxOf(frame.width, frame.height) * 0.08).coerceAtLeast(1.0)
        val unit = (frame.height / 16.0).coerceAtLeast(0.5)   // element/glyph size relative to the frame
        val rowGap = unit * 1.8
        // Servers sit at the right of the strip; each queue's head is just to their left with members growing
        // further left, so a row reads "members -> head -> server" (fixes the server-left-of-its-queue-head look).
        val rowY = { i: Int -> frame.y + margin + i * rowGap }
        val qHeadX = frame.maxX + margin + unit * 8   // queue-head column (members fill the gap to its left)
        val resColX = qHeadX + unit * 3               // servers, to the right of their queue head
        val resourceRow = staticResources.sorted().withIndex().associate { (i, n) -> n to i }
        val resources = staticResources.sorted().mapIndexed { i, name ->
            ResourceLayoutElement(resourceName = name, position = LayoutPoint(resColX, rowY(i)), size = unit)
        }
        val placedQueues = mutableSetOf<String>()
        val queues = mutableListOf<QueueLayoutElement>()
        staticResources.sorted().forEach { r ->
            flow.queueOfResource[r]?.let { q ->
                queues += QueueLayoutElement(queueName = q, position = LayoutPoint(qHeadX, rowY(resourceRow.getValue(r))), growthDegrees = 180.0, spacing = unit * 0.7, maxShown = autoQueueMaxShown)
                placedQueues += q
            }
        }
        var extraRow = staticResources.size
        staticQueues.filter { it !in placedQueues }.sorted().forEach { q ->
            queues += QueueLayoutElement(queueName = q, position = LayoutPoint(qHeadX, rowY(extraRow++)), growthDegrees = 180.0, spacing = unit * 0.7, maxShown = autoQueueMaxShown)
        }
        // Named travel locations with a mined centroid become location anchors at their true positions (Phase 5:
        // these are locations, not network stations; the renderer interpolates movers between them).
        val locations = location.names.sorted().mapNotNull { name ->
            location.centroids[name]?.let { LocationLayoutElement(locationName = name, position = it, label = name) }
        }
        val rightExtent = if (queues.isEmpty() && resources.isEmpty()) frame.maxX + margin else resColX + unit * 3
        return AnimationLayout(
            title = title ?: "Replay",
            width = rightExtent.coerceAtLeast(frame.maxX + margin),
            height = (frame.maxY + margin),
            spaces = effectiveSpaces,
            agentStateColors = agentStateColors,
            resources = resources,
            queues = queues,
            locations = locations,
            movableResources = movers
        ).withSeededObjectClasses(objectTypes, glyphSize)
    }

    // No spatial space and no planar coordinates (non-Cartesian / process-view): resources in flow-ordered
    // columns (left to right by observed seize order) with each queue beside its server, and any named travel
    // locations placed on a ring so name-resolved movement / conveyors animate.
    val originX = 80.0; val originY = 80.0; val rowGap = 70.0; val columnGap = 240.0
    val firstColX = originX + 160.0
    // Group resources into flow-stage columns; resources never seized in the trace trail as a final column.
    val maxRank = flow.ranks.values.maxOrNull() ?: -1
    val byRank = staticResources.groupBy { flow.ranks[it] ?: (maxRank + 1) }.toSortedMap()
    val resources = mutableListOf<ResourceLayoutElement>()
    val queues = mutableListOf<QueueLayoutElement>()
    val placedQueues = mutableSetOf<String>()
    var maxRows = 1
    byRank.forEach { (rank, names) ->
        val colX = firstColX + rank * columnGap
        names.sorted().forEachIndexed { i, name ->
            val y = originY + i * rowGap
            resources += ResourceLayoutElement(resourceName = name, position = LayoutPoint(colX, y))
            // Place this resource's queue just to its left, growing back to the left so entities read queue -> server.
            flow.queueOfResource[name]?.let { q ->
                queues += QueueLayoutElement(queueName = q, position = LayoutPoint(colX - 90.0, y), growthDegrees = 180.0, maxShown = autoQueueMaxShown)
                placedQueues += q
            }
        }
        maxRows = maxOf(maxRows, names.size)
    }
    // Queues with no observed server (never seen in a SeizeQueued) fall back to a left column.
    staticQueues.filter { it !in placedQueues }.sorted().forEachIndexed { i, name ->
        queues += QueueLayoutElement(queueName = name, position = LayoutPoint(originX, originY + i * rowGap), growthDegrees = 180.0, maxShown = autoQueueMaxShown)
        maxRows = maxOf(maxRows, i + 1)
    }
    val lastRank = byRank.keys.maxOrNull() ?: 0

    // B1: network stations (from StationEntered) placed left-to-right by observed flow, in a lane below the
    // resources — the renderer draws station-network entities at these positions, so they finally render (07).
    var laneY = originY + maxRows * rowGap + 60.0
    val stationOrder = stationFlow.keys.sortedWith(compareBy({ stationFlow[it] ?: 0 }, { it }))
    val stationGap = 150.0
    val networkStations = if (stationOrder.isEmpty()) emptyList() else {
        val y = laneY; laneY += 90.0
        stationOrder.mapIndexed { i, name ->
            NetworkStationLayoutElement(stationName = name, position = LayoutPoint(firstColX + i * stationGap, y), label = name)
        }
    }

    // B2: conveyors (from ConveyorDefined) — each belt a straight horizontal line, anchors spaced by cell index
    // (not a meaningless ring), so the belt resolves and draws; a synthesized route adds color + direction arrows (08).
    val beltSpan = 600.0
    val conveyorAnchorPos = LinkedHashMap<String, LayoutPoint>()
    val conveyorElements = mutableListOf<ConveyorLayoutElement>()
    conveyorAnchors.forEach { (conveyorName, anchors) ->
        val y = laneY; laneY += 80.0
        val maxCell = (anchors.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        anchors.forEach { (loc, cell) ->
            conveyorAnchorPos.putIfAbsent(loc, LayoutPoint(firstColX + beltSpan * cell / maxCell, y))
        }
        conveyorElements += ConveyorLayoutElement(conveyorName = conveyorName, showDirection = true)
    }
    // Conveyor anchors are locations, not network stations (Phase 5); exclude any name that is a network station.
    val stationNameSet = stationOrder.toSet()
    val conveyorLocations = conveyorAnchorPos.filterKeys { it !in stationNameSet }
        .map { (loc, p) -> LocationLayoutElement(locationName = loc, position = p, label = loc) }

    val width = (maxOf(
        firstColX + lastRank * columnGap,
        firstColX + beltSpan,
        firstColX + (stationOrder.size - 1).coerceAtLeast(0) * stationGap
    ) + 320.0).coerceAtLeast(800.0)

    // D1: storages for named delays + entity-types with bare-delay activity, as a wrapping row below the lanes.
    val (storages, storageBottom) = layoutStorages(storageKeys, originX, laneY + 30.0, width)

    val height = (maxOf(storageBottom, laneY, originY + maxRows * rowGap) + 80.0).coerceAtLeast(400.0)

    // Remaining named travel locations (name-only SpatialElementMoved movers) not already placed as a network
    // station or a conveyor anchor: a ring, so name-resolved mover movement still animates.
    val placedNames = stationNameSet + conveyorAnchorPos.keys
    val ringNames = location.names.filter { it !in placedNames }.toSortedSet()
    // Coordinate-free travel locations on a ring (Phase 5: locations, not stations). A DistancesModel's MDS
    // positions later override these via withModelLocations (inventory positions are authoritative).
    val ringLocations = if (ringNames.isEmpty()) emptyList() else {
        val cx = width / 2.0; val cy = height / 2.0
        val radius = minOf(width, height) * 0.32
        ringNames.toList().mapIndexed { i, name ->
            val a = 2.0 * Math.PI * i / ringNames.size - Math.PI / 2.0 // first location at top
            LocationLayoutElement(locationName = name, position = LayoutPoint(cx + radius * cos(a), cy + radius * sin(a)), label = name)
        }
    }
    return AnimationLayout(
        title = title ?: "Replay",
        width = width,
        height = height,
        agentStateColors = agentStateColors,
        // No auto-placed clock: the clock is an opt-in element the user adds from the Layout palette.
        resources = resources,
        queues = queues,
        stations = networkStations,
        locations = conveyorLocations + ringLocations,
        conveyors = conveyorElements,
        storages = storages,
        movableResources = movers
    ).withSeededObjectClasses(objectTypes, glyphSize)
}

/** A standard categorical palette for assigning colors to agent states discovered in a trace (P5). */
private val PALETTE = listOf(
    "#1f77b4", "#d62728", "#2ca02c", "#ff7f0e", "#9467bd", "#8c564b", "#e377c2", "#7f7f7f", "#bcbd22", "#17becf"
)

/** The world bounding box of a spatial space descriptor (grid/continuous); null for spaces without planar bounds. */
private fun spaceBounds(s: ksl.animation.SpatialSpaceDescriptor): java.awt.geom.Rectangle2D.Double? = when (s) {
    is ksl.animation.SpatialSpaceDescriptor.Grid ->
        java.awt.geom.Rectangle2D.Double(s.originX, s.originY, s.cols * s.cellSize, s.rows * s.cellSize)
    is ksl.animation.SpatialSpaceDescriptor.Continuous ->
        java.awt.geom.Rectangle2D.Double(s.xMin, s.yMin, s.xMax - s.xMin, s.yMax - s.yMin)
    is ksl.animation.SpatialSpaceDescriptor.Network -> if (s.nodes.isEmpty()) null else {
        val xs = s.nodes.map { it.position.x }; val ys = s.nodes.map { it.position.y }
        java.awt.geom.Rectangle2D.Double(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
    }
    else -> null
}

/**
 * Lays storages out as a wrapping row of boxes from ([x0], [y0]) within [maxX]; returns the placed elements and
 * the bottom y they reach, so the caller can grow the canvas to fit them (D1).
 */
private fun layoutStorages(keys: List<String>, x0: Double, y0: Double, maxX: Double): Pair<List<StorageLayoutElement>, Double> {
    if (keys.isEmpty()) return emptyList<StorageLayoutElement>() to y0
    val w = 160.0; val h = 48.0; val gap = 24.0
    var x = x0; var y = y0
    val out = keys.map { key ->
        if (x + w > maxX && x > x0) { x = x0; y += h + gap } // wrap to the next row
        val e = StorageLayoutElement(suspensionName = key, position = LayoutPoint(x, y), width = w, height = h, label = key)
        x += w + gap
        e
    }
    return out to (y + h)
}
