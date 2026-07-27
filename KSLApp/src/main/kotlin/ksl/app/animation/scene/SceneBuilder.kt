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

package ksl.app.animation.scene

import ksl.animation.AnimationLayout
import ksl.animation.BackgroundElement
import ksl.animation.BackgroundKind
import ksl.animation.ElementKind
import ksl.animation.LayoutPoint
import ksl.animation.LayoutShape
import ksl.animation.MoverMode
import ksl.animation.MovableResourceLayoutElement
import ksl.animation.HistogramDisplayElement
import ksl.animation.PlotDisplayElement
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.animation.SpatialSpaceDescriptor
import ksl.animation.StorageStyle
import ksl.app.animation.geom.BoundingBox
import ksl.app.animation.style.RgbaColor
import ksl.app.animation.style.VisualStyle
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.replay.StorageMember
import ksl.app.animation.replay.WorldPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns the replay state at an instant into a [Scene] — the single answer to "what does this animation
 * look like right now".
 *
 * This is where the drawing *decisions* live: which elements appear, in what order they stack, how a
 * queue's members are laid out along its growth angle, that an entity in service is drawn inside its
 * resource rather than in free space, that co-located agents are fanned onto a small ring so none is
 * hidden. Those rules were previously spread through a Swing canvas and partly duplicated in a headless
 * image renderer; keeping them here means every surface agrees, and a test can assert them without a
 * display.
 *
 * Layer order is back-to-front and mirrors the desktop canvas's paint order, so the two can be compared
 * one layer at a time.
 *
 * @param model the indexed replay to query
 * @param options which layers to include
 * @param style visual resolution; defaults to the model's own layout
 */
class SceneBuilder(
    private val model: ReplayModel,
    private val options: SceneOptions = SceneOptions(),
    private val style: VisualStyle = VisualStyle(model.layout)
) {

    private val layout: AnimationLayout? = model.layout

    /**
     * The world rectangle a view should frame.
     *
     * The naive rule — union the layout's declared canvas with the extent of actual movement — fails badly
     * for spatial models: a continuous space a hundred units across, unioned with the default canvas of a
     * thousand by seven hundred, fits the canvas and leaves the agents in a corner a few percent of the
     * viewport wide. A trace with no layout at all is the worst case, since the canvas is then pure
     * default. So when the content occupies only a small share of the declared canvas, the content wins;
     * otherwise the declared canvas wins, which preserves whatever whitespace the author arranged.
     */
    fun worldBounds(): BoundingBox {
        val content = contentBounds()
        val declared = layout?.let { BoundingBox(0.0, 0.0, it.width, it.height) }
        if (declared == null) return content?.grown(contentMargin(content)) ?: DEFAULT_WORLD
        val c = content ?: return declared
        val fillsCanvas = c.width >= CANVAS_SHARE * declared.width && c.height >= CANVAS_SHARE * declared.height
        return if (fillsCanvas) declared.union(c) else c.grown(contentMargin(c))
    }

    /** A small margin so glyphs at the very edge of the content are not clipped. */
    private fun contentMargin(b: BoundingBox): Double =
        (sqrt(b.width * b.width + b.height * b.height) * 0.02).coerceAtLeast(1e-9)

    /** Everything actually drawn: motion extents, space backdrops, and placed layout elements. */
    private fun contentBounds(): BoundingBox? {
        var box: BoundingBox? = model.coordinateBounds()
        for (space in model.effectiveSpaces) box = BoundingBox.union(box, spaceBounds(space))
        layout?.let { l ->
            val pts = ArrayList<Pair<Double, Double>>()
            fun add(p: LayoutPoint?) { if (p != null) pts.add(p.x to p.y) }
            l.queues.forEach { q ->
                add(q.position)
                val rad = q.growthDegrees.toRadians()
                val run = q.spacing * q.maxShown.coerceAtLeast(1)
                pts.add((q.position.x + run * cos(rad)) to (q.position.y + run * sin(rad)))
            }
            l.resources.forEach { add(it.position) }
            l.stations.forEach { add(it.position) }
            l.locations.forEach { add(it.position) }
            l.movableResources.forEach { add(moverAtRest(it)) }
            l.storages.forEach { pts.add(it.position.x to it.position.y); pts.add((it.position.x + it.width) to (it.position.y + it.height)) }
            l.bars.forEach { pts.add(it.position.x to it.position.y); pts.add((it.position.x + it.width) to (it.position.y + it.height)) }
            l.clocks.forEach { add(it.position) }
            l.values.forEach { add(it.position) }
            l.background.forEach { b -> b.points.forEach { add(it) } }
            l.paths.forEach { p -> l.pathPolyline(p).forEach { add(it) } }
            box = BoundingBox.union(box, BoundingBox.of(pts.asSequence()))
        }
        return box
    }

    private fun spaceBounds(space: SpatialSpaceDescriptor): BoundingBox? = when (space) {
        is SpatialSpaceDescriptor.Continuous -> BoundingBox(space.xMin, space.yMin, space.xMax, space.yMax)
        is SpatialSpaceDescriptor.Grid -> BoundingBox(
            space.originX, space.originY,
            space.originX + space.cols * space.cellSize,
            space.originY + space.rows * space.cellSize
        )
        is SpatialSpaceDescriptor.Network -> BoundingBox.of(space.nodes.asSequence().map { it.position.x to it.position.y })
    }

    /**
     * A grid obstacle overlay's blocked cells, filled behind everything that moves.
     *
     * The wall in an evacuation model is not decoration — it is the constraint the whole run is about, and
     * without it a crowd funnelling towards nothing is unreadable. The cells come from the model's own
     * geometry, extracted into the layout, so this draws what the model actually blocks.
     *
     * Resolving a cell to the world takes three tries, in the order that respects what the author declared: a
     * grid space the overlay names owns the mapping; failing that an explicit cell size on the overlay itself;
     * failing that a continuous space's own bounds divided into the overlay's columns. Matching by name is
     * loose on purpose — the projection a model exports is often named differently from the space it is drawn
     * on ("grid" against "floor") — so a sole space, or the only grid, will do.
     */
    private fun obstacleCommands(): List<DrawCmd> {
        val l = layout ?: return emptyList()
        val cmds = ArrayList<DrawCmd>()
        for (spec in l.spaceGeometry) {
            if (spec.blockedCells.isEmpty()) continue
            val grid = cellFrame(spec) ?: continue
            for (blocked in spec.blockedCells) {
                cmds.add(
                    DrawCmd.Rect(
                        grid.originX + blocked.col * grid.cell, grid.originY + blocked.row * grid.cell,
                        Extent.world(grid.cell), Extent.world(grid.cell), fill = OBSTACLE
                    )
                )
            }
        }
        return cmds
    }

    /**
     * Ground that is passable but costly, shaded in proportion to what it costs.
     *
     * A grid can say more than "wall" or "floor": a cell carries a traversal cost, and a route weighs that
     * cost against distance. Undrawn, an agent taking the long way round looks like a bug — the reason is
     * real and is in the model, just invisible. Shading it is what turns "why did it go that way" into
     * something a reader can see.
     *
     * Only cells above the default cost of 1.0 are drawn, so ordinary floor stays clean, and the shade is
     * relative to the costliest cell present rather than absolute: what matters is which ground an agent is
     * avoiding, not the arithmetic.
     */
    private fun terrainCommands(): List<DrawCmd> {
        val l = layout ?: return emptyList()
        val cmds = ArrayList<DrawCmd>()
        for (spec in l.spaceGeometry) {
            val costly = spec.cellCosts.filter { it.cost > 1.0 }
            if (costly.isEmpty()) continue
            val grid = cellFrame(spec) ?: continue
            val dearest = costly.maxOf { it.cost }
            for (c in costly) {
                // Across the range present, so a floor with one slow patch still shows it.
                val share = if (dearest > 1.0) (c.cost - 1.0) / (dearest - 1.0) else 1.0
                val alpha = (TERRAIN_MIN_ALPHA + share * (TERRAIN_MAX_ALPHA - TERRAIN_MIN_ALPHA)).toInt()
                cmds.add(
                    DrawCmd.Rect(
                        grid.originX + c.col * grid.cell, grid.originY + c.row * grid.cell,
                        Extent.world(grid.cell), Extent.world(grid.cell),
                        fill = TERRAIN.copy(a = alpha)
                    )
                )
            }
        }
        return cmds
    }

    private class CellFrame(val originX: Double, val originY: Double, val cell: Double)

    /**
     * Where a grid overlay's cell (0,0) sits and how big a cell is.
     *
     * Three tries, in the order that respects what the author declared: a grid space the overlay names owns
     * the mapping; failing that an explicit cell size on the overlay itself; failing that a continuous
     * space's bounds divided into the overlay's columns. Matching by name is loose on purpose — the
     * projection a model exports is often named differently from the space it is drawn on ("grid" against
     * "floor") — so a sole space, or the only grid, will do.
     */
    private fun cellFrame(spec: ksl.modeling.agent.GridGeometrySpec): CellFrame? {
        val space = model.effectiveSpaces.firstOrNull { it.name == spec.spaceName }
            ?: model.effectiveSpaces.singleOrNull()
            ?: model.effectiveSpaces.firstOrNull { it is SpatialSpaceDescriptor.Grid }
        return when {
            space is SpatialSpaceDescriptor.Grid -> CellFrame(space.originX, space.originY, space.cellSize)
            spec.cellSize != null -> CellFrame(spec.originX ?: 0.0, spec.originY ?: 0.0, spec.cellSize!!)
            space is SpatialSpaceDescriptor.Continuous ->
                CellFrame(space.xMin, space.yMin, (space.xMax - space.xMin) / spec.cols.coerceAtLeast(1))
            else -> CellFrame(spec.originX ?: 0.0, spec.originY ?: 0.0, spec.cellSize ?: 1.0)
        }
    }

    /** The static skeleton only — every element at rest, with no replay state. */
    fun buildStatic(viewport: Viewport? = null): Scene = build(STATIC_TIME, static = true, viewport = viewport)

    /**
     * The frame at simulated time [t]. [viewport] is only needed for edge-anchored screen chrome (the
     * legend); without it the legend falls back to the top-left corner.
     */
    fun build(t: Double, viewport: Viewport? = null): Scene = build(t, static = false, viewport = viewport)

    private fun build(t: Double, static: Boolean, viewport: Viewport? = null): Scene {
        val layers = ArrayList<Layer>()
        fun layer(name: String, space: DrawSpace, cmds: List<DrawCmd>) {
            if (cmds.isNotEmpty()) layers.add(Layer(name, space, cmds))
        }

        layer("spaces", DrawSpace.WORLD, spaceCommands())
        layer("terrain", DrawSpace.WORLD, terrainCommands())
        layer("obstacles", DrawSpace.WORLD, obstacleCommands())
        if (options.showFlowField && !static) layer("flowField", DrawSpace.WORLD, flowFieldCommands())
        if (options.showPlannedPaths && !static) layer("plannedPaths", DrawSpace.WORLD, plannedPathCommands(t))
        layer("background", DrawSpace.WORLD, backgroundCommands())
        layer("paths", DrawSpace.WORLD, pathCommands())
        layer("conveyors", DrawSpace.WORLD, conveyorCommands(t, static))
        layer("stations", DrawSpace.WORLD, stationCommands())
        layer("locations", DrawSpace.WORLD, locationCommands())
        layer("queues", DrawSpace.WORLD, queueCommands(t, static))
        layer("storages", DrawSpace.WORLD, storageCommands(t, static))
        layer("resources", DrawSpace.WORLD, resourceCommands(t, static))
        layer("displays", DrawSpace.WORLD, displayCommands(t, static))
        if (!static) {
            layer("entities", DrawSpace.WORLD, entityCommands(t))
            if (options.showStationContents) layer("stationContents", DrawSpace.WORLD, stationContentCommands(t))
            layer("agents", DrawSpace.WORLD, agentCommands(t))
            if (options.showVectors) layer("vectors", DrawSpace.WORLD, vectorCommands(t))
        }
        layer("movers", DrawSpace.WORLD, moverCommands(t, static))
        if (options.showMarkerPulses && !static) layer("pulses", DrawSpace.WORLD, pulseCommands(t))
        layer("labels", DrawSpace.WORLD, labelCommands(t, static))
        layer("clock", DrawSpace.WORLD, clockCommands(t, static))
        if (options.showLegend) layer("legend", DrawSpace.SCREEN, legendCommands(viewport))

        return Scene(layers, worldBounds(), t)
    }

    // ── spatial context ─────────────────────────────────────────────────────────────────────────────

    private fun spaceCommands(): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        for (space in model.effectiveSpaces) {
            when (space) {
                is SpatialSpaceDescriptor.Continuous -> cmds.add(
                    // A faint fill plus a slightly stronger border, so a room or airspace reads as a region
                    // rather than a hairline outline.
                    DrawCmd.Rect(
                        x = space.xMin, y = space.yMin,
                        width = Extent.world(space.xMax - space.xMin),
                        height = Extent.world(space.yMax - space.yMin),
                        fill = SPACE_FILL, stroke = SPACE_BORDER, strokeWidth = 1.5
                    )
                )
                is SpatialSpaceDescriptor.Grid -> {
                    val right = space.originX + space.cols * space.cellSize
                    val bottom = space.originY + space.rows * space.cellSize
                    for (c in 0..space.cols) {
                        val x = space.originX + c * space.cellSize
                        cmds.add(DrawCmd.Polyline(listOf(x to space.originY, x to bottom), GRID_LINE))
                    }
                    for (r in 0..space.rows) {
                        val y = space.originY + r * space.cellSize
                        cmds.add(DrawCmd.Polyline(listOf(space.originX to y, right to y), GRID_LINE))
                    }
                }
                is SpatialSpaceDescriptor.Network -> {
                    val byId = space.nodes.associateBy { it.id }
                    for (e in space.edges) {
                        val from = byId[e.from]?.position ?: continue
                        val to = byId[e.to]?.position ?: continue
                        cmds.add(DrawCmd.Polyline(listOf(from.x to from.y, to.x to to.y), NETWORK_LINE))
                    }
                    // Faint node anchors with no id labels: agents emitted at these slots draw their state
                    // colors on top, and for a hand-authored network the dots and edges still convey the graph.
                    for (node in space.nodes) {
                        cmds.add(DrawCmd.Circle(node.position.x, node.position.y, MARKER_RADIUS, fill = NETWORK_LINE))
                    }
                }
            }
        }
        return cmds
    }

    private fun backgroundCommands(): List<DrawCmd> =
        layout?.background?.mapNotNull { backgroundCommand(it) } ?: emptyList()

    private fun backgroundCommand(b: BackgroundElement): DrawCmd? {
        val color = RgbaColor.parse(b.color)
        return when (b.kind) {
            BackgroundKind.LINE, BackgroundKind.POLYLINE ->
                if (b.points.size < 2) null
                else DrawCmd.Polyline(b.points.map { it.x to it.y }, color, b.strokeWidth)
            BackgroundKind.RECT ->
                if (b.points.size < 2) null else {
                    val a = b.points[0]
                    val c = b.points[1]
                    DrawCmd.Rect(
                        x = minOf(a.x, c.x), y = minOf(a.y, c.y),
                        width = Extent.world(abs(c.x - a.x)), height = Extent.world(abs(c.y - a.y)),
                        stroke = color, strokeWidth = b.strokeWidth
                    )
                }
            BackgroundKind.TEXT ->
                if (b.points.isEmpty() || b.text == null) null
                else DrawCmd.Text(
                    b.points[0].x, b.points[0].y, b.text!!, color,
                    size = Extent.world(b.fontSize, minPx = 4.0), family = b.fontFamily
                )
            BackgroundKind.IMAGE ->
                if (b.points.size < 2 || b.imageRef == null) null else {
                    val a = b.points[0]
                    val c = b.points[1]
                    DrawCmd.Image(
                        x = minOf(a.x, c.x), y = minOf(a.y, c.y),
                        width = Extent.world(abs(c.x - a.x)), height = Extent.world(abs(c.y - a.y)),
                        ref = b.imageRef!!
                    )
                }
        }
    }

    /**
     * Authored paths. A functional path's endpoints bracket its waypoints, so an endpoints-only path is
     * visible at rest rather than only while something is moving along it.
     */
    private fun pathCommands(): List<DrawCmd> {
        val l = layout ?: return emptyList()
        return l.paths.mapNotNull { path ->
            val poly = l.pathPolyline(path)
            if (poly.size < 2) null else DrawCmd.Polyline(poly.map { it.x to it.y }, PATH_LINE)
        }
    }

    // ── conveyors ───────────────────────────────────────────────────────────────────────────────────

    /** Belt cells along the routed geometry: occupied cells filled, empty cells outlined. */
    private fun conveyorCommands(t: Double, static: Boolean): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        for (name in model.conveyorNames) {
            val route = layout?.conveyors?.firstOrNull { it.conveyorName == name }
            val full = route?.let { RgbaColor.parse(it.color) } ?: BELT_FULL
            val occupied = if (static) emptySet() else model.conveyorOccupiedCellsAt(name, t)
            val maxCell = model.conveyorMaxCellOf(name)
            val side = Extent.world((route?.width ?: DEFAULT_BELT_WIDTH) * 0.5, minPx = 3.0)
            for (cell in 0..maxCell) {
                val p = model.conveyorCellPosition(name, cell) ?: continue
                val half = (route?.width ?: DEFAULT_BELT_WIDTH) * 0.25
                if (cell in occupied) {
                    cmds.add(DrawCmd.Rect(p.x - half, p.y - half, side, side, fill = full))
                } else {
                    cmds.add(DrawCmd.Rect(p.x - half, p.y - half, side, side, stroke = BELT_EMPTY))
                }
            }
            if (route?.showDirection == true && maxCell >= 2) {
                val step = (maxCell / 4).coerceAtLeast(1)
                var cell = step
                while (cell < maxCell) {
                    val a = model.conveyorCellPosition(name, cell)
                    val b = model.conveyorCellPosition(name, cell + 1)
                    if (a != null && b != null) {
                        cmds.add(DrawCmd.ArrowHead(a.x, a.y, b.x - a.x, b.y - a.y, full))
                    }
                    cell += step
                }
            }
        }
        return cmds
    }

    // ── placement markers ───────────────────────────────────────────────────────────────────────────

    /** A network station: a filled dot. */
    private fun stationCommands(): List<DrawCmd> =
        layout?.stations?.map { DrawCmd.Circle(it.position.x, it.position.y, MARKER_RADIUS, fill = MARKER) }
            ?: emptyList()

    /** A spatial location: an open square, deliberately distinct from a station's filled dot. */
    private fun locationCommands(): List<DrawCmd> =
        layout?.locations?.mapNotNull { loc ->
            val p = loc.position ?: return@mapNotNull null // unplaced -> nothing to draw
            DrawCmd.Rect(
                p.x, p.y, LOCATION_SIDE, LOCATION_SIDE,
                stroke = MARKER, strokeWidth = 1.5, centered = true
            )
        } ?: emptyList()

    // ── queues ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * A queue as a persistent "line with a head bar", plus its identified members growing from the head.
     * Drawing the extent even when the queue is empty keeps it visible and selectable while authoring.
     */
    private fun queueCommands(t: Double, static: Boolean): List<DrawCmd> {
        val l = layout ?: return emptyList()
        val cmds = ArrayList<DrawCmd>()
        for (q in l.queues) {
            val rad = q.growthDegrees.toRadians()
            val dx = cos(rad)
            val dy = sin(rad)
            if (options.showQueueExtents) {
                val run = q.spacing * q.maxShown.coerceAtLeast(1)
                cmds.add(
                    DrawCmd.Polyline(
                        listOf(q.position.x to q.position.y, (q.position.x + run * dx) to (q.position.y + run * dy)),
                        QUEUE_LINE
                    )
                )
                // The head bar sits perpendicular to the growth direction, marking the front of the line.
                val px = -dy
                val py = dx
                val half = QUEUE_HEAD_BAR / 2
                cmds.add(
                    DrawCmd.Polyline(
                        listOf(
                            (q.position.x - px * half) to (q.position.y - py * half),
                            (q.position.x + px * half) to (q.position.y + py * half)
                        ),
                        QUEUE_HEAD, width = 1.5
                    )
                )
            }
            if (static) continue
            val members = model.queueMembersAt(q.queueName, t)
            val length = if (members.isNotEmpty()) members.size else model.queueLengthAt(q.queueName, t)
            for (i in 0 until minOf(length, q.maxShown)) {
                val cx = q.position.x + i * q.spacing * dx
                val cy = q.position.y + i * q.spacing * dy
                val id = members.getOrNull(i)
                val key = id?.let { model.entityTypeOf(it) ?: model.networkEntityTypeOf(it) }
                if (key != null) {
                    cmds.add(glyphFor(key, cx, cy, QUEUE_DOT_SIZE))
                } else {
                    // Length known but membership not identified: an anonymous dot still shows the queue filling.
                    cmds.add(DrawCmd.Circle(cx, cy, Extent.world(QUEUE_DOT_SIZE / 2, minPx = 1.5), fill = QUEUE_HEAD))
                }
            }
        }
        return cmds
    }

    // ── resources ───────────────────────────────────────────────────────────────────────────────────

    /**
     * A resource as one cell per unit of capacity, colored by state, with the entity occupying each busy
     * unit drawn inside it. A single-capacity resource is one cell, which is the common case.
     */
    private fun resourceCommands(t: Double, static: Boolean): List<DrawCmd> {
        val l = layout ?: return emptyList()
        val cmds = ArrayList<DrawCmd>()
        for (res in l.resources) {
            val snap = if (static) null else model.resourceStateAt(res.resourceName, t)
            val capacity = (snap?.capacity ?: 1).coerceAtLeast(1)
            val busy = snap?.busyUnits ?: 0
            val state = snap?.state
            val units = if (static) emptyList() else model.resourceUnitsAt(res.resourceName, t)
            val failed = state?.contains("Fail", ignoreCase = true) == true
            val inactive = state?.contains("Inactive", ignoreCase = true) == true

            if (capacity <= 1) {
                cmds.add(resourceCell(res, res.position.x, res.position.y, res.size, style.resourceImageRef(res, state), style.resourceColor(res, state)))
                units.firstOrNull()?.let { cmds.add(unitOccupant(it, res.position.x, res.position.y, res.size)) }
            } else {
                // Units laid out in a row centered on the element's position.
                val x0 = res.position.x - capacity * res.size / 2
                for (i in 0 until capacity) {
                    val unitBusy = !failed && !inactive && i < busy
                    val image = when {
                        failed -> res.failedImage
                        inactive -> res.inactiveImage
                        unitBusy -> res.busyImage
                        else -> res.idleImage
                    }
                    val color = when {
                        failed -> RgbaColor.parse(res.failedColor)
                        inactive -> RgbaColor.parse(res.inactiveColor)
                        unitBusy -> RgbaColor.parse(res.busyColor)
                        else -> RgbaColor.parse(res.idleColor)
                    }
                    val cx = x0 + i * res.size + res.size / 2
                    cmds.add(resourceCell(res, cx, res.position.y, res.size, image, color))
                    units.getOrNull(i)?.let { cmds.add(unitOccupant(it, cx, res.position.y, res.size)) }
                }
            }
        }
        return cmds
    }

    private fun resourceCell(
        res: ResourceLayoutElement, cx: Double, cy: Double, side: Double, imageRef: String?, color: RgbaColor
    ): DrawCmd {
        val half = side / 2
        return if (imageRef != null) {
            DrawCmd.Image(cx - half, cy - half, Extent.world(side, minPx = 6.0), Extent.world(side, minPx = 6.0), imageRef)
        } else {
            DrawCmd.Rect(
                cx - half, cy - half,
                Extent.world(side, minPx = 6.0), Extent.world(side, minPx = 6.0),
                fill = color, stroke = RgbaColor.BLACK
            )
        }
    }

    /** The entity in a busy unit, drawn as its typed glyph inside the cell. */
    private fun unitOccupant(id: Long, cx: Double, cy: Double, cell: Double): DrawCmd {
        val key = model.entityTypeOf(id) ?: model.networkEntityTypeOf(id) ?: DEFAULT_TYPE
        return glyphFor(key, cx, cy, cell * 0.6)
    }

    // ── entities ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Entities in free space, at their interpolated positions.
     *
     * An entity already represented somewhere else is skipped so it is never drawn twice: in service it
     * appears inside its resource's unit cell, waiting it appears as one of its queue's members, and in a
     * placed storage it appears in that storage.
     */
    private fun entityCommands(t: Double): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        for (e in model.entitiesAt(t)) {
            // Blocked at a conveyor entry: draw at the entry, ringed, so the blockage is visible.
            val blockedLoc = model.entityBlockedLocationAt(e.id, t)
            if (blockedLoc != null) {
                val pos = layout?.stations?.firstOrNull { it.stationName == blockedLoc }?.position
                    ?: layout?.locations?.firstOrNull { it.locationName == blockedLoc }?.position
                if (pos != null) {
                    val size = style.objectSize(e.typeName)
                    cmds.add(glyphFor(e.typeName, pos.x, pos.y, size, processColorOf(e.id, t)))
                    cmds.add(
                        DrawCmd.Circle(
                            pos.x, pos.y, Extent.world(size * 0.75, minPx = 5.5),
                            stroke = RgbaColor.RED, strokeWidth = 2.0
                        )
                    )
                    continue
                }
            }
            if (model.entityServiceResourceAt(e.id, t) != null) continue
            if (model.entityQueueAt(e.id, t) != null) continue
            val storageKey = model.entityStorageAt(e.id, t)
            if (storageKey != null && layout?.storages?.any { it.suspensionName == storageKey } == true) continue
            val p = model.entityPositionAt(e.id, t) ?: continue
            if (p.x.isNaN() || p.y.isNaN()) continue
            cmds.add(glyphFor(e.typeName, p.x, p.y, style.objectSize(e.typeName), processColorOf(e.id, t)))
        }
        return cmds
    }

    /** Tint by the entity's current process when the layout declares one, else by its type. */
    private fun processColorOf(id: Long, t: Double): RgbaColor? = style.processColor(model.entityProcessAt(id, t))

    /**
     * Station-network QObjects at their current station, or sliding along the connector while in transit.
     * Several at one station would coincide exactly, so they are stacked apart.
     */
    private fun stationContentCommands(t: Double): List<DrawCmd> {
        val l = layout ?: return emptyList()
        if (l.stations.isEmpty()) return emptyList()
        val cmds = ArrayList<DrawCmd>()
        val perStation = HashMap<String, Int>()
        for (id in model.networkEntitiesAt(t)) {
            val key = model.networkEntityTypeOf(id) ?: DEFAULT_TYPE
            val stationName = model.entityStationAt(id, t)
            val pos: WorldPoint = if (stationName != null) {
                val sp = l.stations.firstOrNull { it.stationName == stationName }?.position ?: continue
                val k = perStation.getOrElse(stationName) { 0 }
                perStation[stationName] = k + 1
                WorldPoint(sp.x, sp.y + k * style.objectSize(key) * 0.9)
            } else {
                model.networkEntityTransitAt(id, t) ?: continue
            }
            cmds.add(glyphFor(key, pos.x, pos.y, style.objectSize(key)))
        }
        return cmds
    }

    // ── agents ──────────────────────────────────────────────────────────────────────────────────────

    /**
     * Agents at their sampled positions, colored by statechart state when the layout maps one.
     *
     * Two corrections are applied. A grid projection reports the cell corner, so agents are nudged by half
     * a cell to sit in the middle of their cell. And agents that coincide are fanned onto a small ring, so
     * a crowd at one spot reads as a crowd instead of a single glyph.
     */
    private fun agentCommands(t: Double): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        val gridOffset = gridDrawOffset()
        val showHeading = options.showHeadings && gridOffset == 0.0 // headings only make sense for continuous movers
        val drawn = ArrayList<DrawnAgent>()
        for (name in model.agentNames) {
            if (!model.agentPresentAt(name, t)) continue
            val p = model.agentPositionAt(name, t) ?: continue
            if (p.x.isNaN() || p.y.isNaN()) continue
            val key = model.agentTypeOf(name) ?: name
            val color = style.agentStateColor(model.agentStateAt(name, t)) ?: style.objectColor(key)
            val wrapped = model.torusBounds?.wrap(WorldPoint(p.x, p.y, p.z)) ?: p
            drawn.add(
                DrawnAgent(
                    key, color,
                    WorldPoint(wrapped.x + gridOffset, wrapped.y + gridOffset, wrapped.z),
                    if (showHeading) model.agentVelocityAt(name, t) else null
                )
            )
        }
        for ((_, group) in drawn.groupBy { round(it.pos.x) to round(it.pos.y) }) {
            group.forEachIndexed { j, a ->
                val size = style.objectSize(a.key)
                val (ox, oy) = fanRingOffset(j, group.size, size * 0.6)
                val cx = a.pos.x + ox
                val cy = a.pos.y + oy
                cmds.add(
                    DrawCmd.Glyph(cx, cy, Extent.world(size, minPx = 3.0), style.objectShape(a.key), a.color, style.objectImageRef(a.key))
                )
                a.velocity?.let { v ->
                    val mag = hypot(v.x, v.y)
                    if (mag > 0.0) {
                        val len = size
                        cmds.add(
                            DrawCmd.Polyline(
                                listOf(cx to cy, (cx + v.x / mag * len) to (cy + v.y / mag * len)),
                                RgbaColor.BLACK, width = 1.5
                            )
                        )
                    }
                }
            }
        }
        return cmds
    }

    private class DrawnAgent(
        val key: String,
        val color: RgbaColor,
        val pos: WorldPoint,
        val velocity: WorldPoint?
    )

    /**
     * Half a cell, used to center agents on grid cells because a grid projection reports the corner.
     * Zero unless the space is purely a single grid, so continuous and process-view layouts are unaffected.
     */
    private fun gridDrawOffset(): Double {
        val spaces = model.effectiveSpaces
        val grids = spaces.filterIsInstance<SpatialSpaceDescriptor.Grid>()
        val continuous = spaces.filterIsInstance<SpatialSpaceDescriptor.Continuous>()
        return if (grids.size == 1 && continuous.isEmpty()) grids[0].cellSize / 2.0 else 0.0
    }

    // ── movable resources ───────────────────────────────────────────────────────────────────────────

    /**
     * Movable/transport resources at their interpolated position while moving, else anchored to their home
     * base, else at their parked position. While transporting, the mover is ringed and the carried entity
     * is drawn on it, so "carrying" reads even when no busy color or image is configured.
     */
    private fun moverCommands(t: Double, static: Boolean): List<DrawCmd> {
        val l = layout ?: return emptyList()
        val cmds = ArrayList<DrawCmd>()
        val drawn = ArrayList<Triple<MovableResourceLayoutElement, WorldPoint, Boolean>>()
        for (mr in l.movableResources) {
            val moving = if (static) null else model.spatialElementPositionAt(mr.name, t)
            val p = moving ?: moverAtRest(mr)?.let { WorldPoint(it.x, it.y, 0.0) } ?: continue
            if (p.x.isNaN() || p.y.isNaN()) continue
            val transporting = !static && model.moverStateAt(mr.name, t)?.mode == MoverMode.TRANSPORTING
            drawn.add(Triple(mr, p, transporting))
        }
        for ((_, group) in drawn.groupBy { round(it.second.x) to round(it.second.y) }) {
            group.forEachIndexed { j, (mr, pos, transporting) ->
                val (ox, oy) = fanRingOffset(j, group.size, mr.size * 0.7)
                val cx = pos.x + ox
                val cy = pos.y + oy
                val color = RgbaColor.parse((if (transporting) mr.busyColor else null) ?: mr.color)
                val image = (if (transporting) mr.busyImage else mr.idleImage) ?: mr.imageRef
                cmds.add(DrawCmd.Glyph(cx, cy, Extent.world(mr.size, minPx = 3.0), mr.shape, color, image))
                if (transporting) {
                    val ringColor = mr.busyColor?.let { RgbaColor.parse(it) } ?: MOVER_BUSY_RING
                    cmds.add(DrawCmd.Circle(cx, cy, Extent.world(mr.size * 0.7, minPx = 5.6), stroke = ringColor, strokeWidth = 1.0))
                    val ms = model.moverStateAt(mr.name, t)
                    val key = ms?.carriedEntityId?.let { model.entityTypeOf(it) } ?: ms?.carriedEntityType ?: DEFAULT_TYPE
                    cmds.add(glyphFor(key, cx, cy, mr.size * 0.55))
                }
            }
        }
        return cmds
    }

    /** A mover's at-rest position: its home base's placed position, else its parked position. */
    private fun moverAtRest(mr: MovableResourceLayoutElement): LayoutPoint? =
        mr.homeBase?.let { hb ->
            layout?.locations?.firstOrNull { it.locationName == hb }?.position
                ?: layout?.stations?.firstOrNull { it.stationName == hb }?.position
        } ?: mr.position

    // ── overlays carried by the trace ───────────────────────────────────────────────────────────────

    private fun plannedPathCommands(t: Double): List<DrawCmd> {
        if (model.agentsWithPaths.isEmpty()) return emptyList()
        val cmds = ArrayList<DrawCmd>()
        for (name in model.agentsWithPaths) {
            val pts = model.plannedPathAt(name, t) ?: continue
            if (pts.size >= 2) cmds.add(DrawCmd.Polyline(pts.map { it.x to it.y }, PLANNED_PATH, width = 2.0))
        }
        return cmds
    }

    /** A pulse expands and fades over its window, giving a brief ping where something happened. */
    private fun pulseCommands(t: Double): List<DrawCmd> {
        if (!model.hasMarkerPulses) return emptyList()
        val cmds = ArrayList<DrawCmd>()
        for (p in model.markerPulsesActiveAt(t)) {
            val base = p.colorHex?.let { RgbaColor.parse(it) } ?: PULSE_DEFAULT
            val color = base.withAlpha(((1.0 - p.progress) * 220).toInt())
            cmds.add(
                DrawCmd.Ring(p.x, p.y, Extent.px(PULSE_MIN_RADIUS + p.progress * PULSE_GROWTH), color, strokeWidth = 2.5)
            )
            if (p.label != null && p.progress < 0.6) {
                cmds.add(DrawCmd.Text(p.x, p.y, p.label!!, color))
            }
        }
        return cmds
    }

    // ── read-outs and text ──────────────────────────────────────────────────────────────────────────

    /** The layout's live read-outs: bars, plots, histograms, summaries and plain values. */
    private fun displayCommands(t: Double, static: Boolean): List<DrawCmd> {
        val l = layout ?: return emptyList()
        val cmds = ArrayList<DrawCmd>()
        for (bar in l.bars) {
            val value = if (static) 0.0 else model.responseValueAt(bar.responseName, t) ?: 0.0
            val barScale = scaleOf(bar)
            val w = Extent.world(bar.width)
            val h = Extent.world(bar.height)
            cmds.add(DrawCmd.Rect(bar.position.x, bar.position.y, w, h, fill = RgbaColor.WHITE, stroke = RgbaColor.DARK_GRAY))
            val fraction = if (barScale > 0.0) (value / barScale).coerceIn(0.0, 1.0) else 0.0
            if (fraction > 0.0) {
                cmds.add(
                    DrawCmd.Rect(
                        bar.position.x, bar.position.y,
                        Extent.world(bar.width * fraction), h,
                        fill = RgbaColor.parse(bar.color)
                    )
                )
            }
            // The caption carries the value, not just the name. A bar alone says "about two thirds of the
            // way along" and leaves the reader to guess the scale; the desktop canvas has always spelled the
            // number out, and a bar that reads differently in the two viewers is the kind of drift this
            // renderer exists to avoid.
            cmds.add(
                DrawCmd.Text(
                    bar.position.x, bar.position.y - 3.0,
                    "${bar.label ?: bar.responseName}: ${formatFixed(value, 1)}",
                    RgbaColor.DARK_GRAY
                )
            )
        }
        for (plot in l.plots) {
            cmds.addAll(plotCommands(plot, t, static))
        }
        for (h in l.histograms) {
            cmds.addAll(histogramCommands(h, t, static))
        }
        for (sum in l.summaries) {
            val stats = if (static) null else model.responseStatsAt(sum.responseName, t)
            val d = sum.decimals.coerceIn(0, 6)
            cmds.add(DrawCmd.Text(sum.position.x, sum.position.y, sum.label ?: sum.responseName, RgbaColor.BLACK))
            val body = if (stats == null) "—" else
                "n=${formatFixed(stats.count, 0)}  mean=${formatFixed(stats.average, d)}  " +
                    "min=${formatFixed(stats.min, d)}  max=${formatFixed(stats.max, d)}"
            // Offset in pixels, not world units: a second line of text must sit one line below the first
            // whatever the zoom, or the two collide when zoomed out and separate when zoomed in.
            cmds.add(DrawCmd.Text(sum.position.x, sum.position.y, body, RgbaColor.BLACK, screenOffsetY = TEXT_LINE))
        }
        for (v in l.values) {
            val value = if (static) null else model.responseValueAt(v.responseName, t)
            val shown = value?.let { formatFixed(it, v.decimals.coerceIn(0, 6)) } ?: "—"
            cmds.add(DrawCmd.Text(v.position.x, v.position.y, "${v.label ?: v.responseName}: $shown", RgbaColor.BLACK))
        }
        return cmds
    }

    /**
     * A response plotted against time inside its framed box.
     *
     * The x-axis spans the plot's own window ending at the current time when it declares one, else the
     * samples' whole span — so a windowed plot scrolls while an unwindowed one accumulates. The y-axis
     * auto-scales to what has been observed, because a response's range is rarely known in advance.
     */
    /**
     * The value a bar's fill is measured against: the authored maximum, or — when none was authored — the
     * largest value the run produced.
     *
     * A bar's scale is the one number about it that cannot be chosen without seeing the data, and the
     * consequences of getting it wrong are silent: a bar scaled far above what a response reaches barely
     * moves, and one scaled below it sits pinned full and looks broken. The element's default of 100 is a
     * number picked against no particular response, so a bar dropped on a canvas is wrong more often than
     * not. A non-positive maximum therefore means "fit this run" rather than the "never fills" it used to,
     * which was not a state anyone wanted.
     */
    private fun scaleOf(bar: ksl.animation.BarDisplayElement): Double =
        if (bar.maxValue > 0.0) bar.maxValue else model.responseMax(bar.responseName) ?: 0.0

    private fun plotCommands(plot: PlotDisplayElement, t: Double, static: Boolean): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        val w = Extent.world(plot.width)
        val h = Extent.world(plot.height)
        cmds.add(DrawCmd.Rect(plot.position.x, plot.position.y, w, h, fill = RgbaColor.WHITE, stroke = RgbaColor.DARK_GRAY))
        cmds.add(
            DrawCmd.Text(plot.position.x, plot.position.y, plot.label ?: plot.responseName,
                RgbaColor.DARK_GRAY, screenOffsetY = -3.0)
        )
        if (static) return cmds
        val samples = model.responseSamplesUpTo(plot.responseName, t)
        if (samples.isEmpty()) return cmds

        val tMax = t
        val tMin = plot.windowDuration?.let { t - it } ?: samples.first().first
        val tSpan = (tMax - tMin).takeIf { it > 0.0 } ?: 1.0
        val vMax = samples.maxOf { it.second }.takeIf { it > 0.0 } ?: 1.0
        val visible = samples.filter { it.first >= tMin }
        if (visible.size < 2) return cmds

        val points = visible.map { (time, value) ->
            val fx = ((time - tMin) / tSpan).coerceIn(0.0, 1.0)
            val fy = (value / vMax).coerceIn(0.0, 1.0)
            (plot.position.x + fx * plot.width) to (plot.position.y + plot.height - fy * plot.height)
        }
        cmds.add(DrawCmd.Polyline(points, RgbaColor.parse(plot.color), width = 1.5))
        return cmds
    }

    /**
     * A histogram of a response's observed values, binned here rather than carried in the trace.
     *
     * Binning in the viewer is deliberate: the trace records every observation, so a viewer can re-bin at
     * will, and shipping pre-binned snapshots would have frozen a choice the reader should own. `discrete`
     * tallies by integer value instead, for a response that counts things.
     */
    private fun histogramCommands(h: HistogramDisplayElement, t: Double, static: Boolean): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        cmds.add(
            DrawCmd.Rect(h.position.x, h.position.y, Extent.world(h.width), Extent.world(h.height),
                fill = RgbaColor.WHITE, stroke = RgbaColor.DARK_GRAY)
        )
        cmds.add(
            DrawCmd.Text(h.position.x, h.position.y, h.label ?: h.responseName,
                RgbaColor.DARK_GRAY, screenOffsetY = -3.0)
        )
        if (static) return cmds
        val values = model.responseSamplesUpTo(h.responseName, t).map { it.second }
        if (values.isEmpty()) return cmds

        val counts: List<Int> = if (h.discrete) {
            val tally = HashMap<Int, Int>()
            for (v in values) tally[round(v).toInt()] = (tally[round(v).toInt()] ?: 0) + 1
            tally.keys.sorted().map { tally.getValue(it) }
        } else {
            val lo = values.min()
            val hi = values.max()
            val bins = h.bins.coerceAtLeast(1)
            val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
            val c = IntArray(bins)
            for (v in values) c[(((v - lo) / span) * bins).toInt().coerceIn(0, bins - 1)]++
            c.toList()
        }
        val maxCount = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
        val barWidth = h.width / counts.size
        val color = RgbaColor.parse(h.color)
        counts.forEachIndexed { i, count ->
            val barHeight = h.height * count / maxCount
            cmds.add(
                DrawCmd.Rect(
                    h.position.x + i * barWidth, h.position.y + h.height - barHeight,
                    Extent.world((barWidth * 0.9).coerceAtLeast(0.0)), Extent.world(barHeight),
                    fill = color
                )
            )
        }
        return cmds
    }

    /**
     * A flow field as a gradient heatmap: green where an agent is close to its goal, red where it is far,
     * translucent so the agents read on top.
     *
     * The teaching value is seeing the gradient the agents are descending — which is why this is drawn at
     * all rather than left as an internal detail. It is a one-time snapshot the model opted into emitting,
     * so an ordinary trace simply has none.
     */
    private fun flowFieldCommands(): List<DrawCmd> {
        val cmds = ArrayList<DrawCmd>()
        for (field in model.flowFieldOverlays) {
            if (field.cells.isEmpty() || field.maxDistance <= 0.0) continue
            for (cell in field.cells) {
                val f = (cell.distance / field.maxDistance).coerceIn(0.0, 1.0)
                cmds.add(
                    DrawCmd.Rect(
                        x = field.originX + cell.col * field.cellSize,
                        y = field.originY + cell.row * field.cellSize,
                        width = Extent.world(field.cellSize),
                        height = Extent.world(field.cellSize),
                        fill = gradientColor(f)
                    )
                )
            }
        }
        return cmds
    }

    /** Green (at the goal) through to red (farthest), at the overlay's fixed translucency. */
    private fun gradientColor(f: Double): RgbaColor = RgbaColor(
        (0x2c + f * (0xd6 - 0x2c)).toInt().coerceIn(0, 255),
        (0xa0 + f * (0x27 - 0xa0)).toInt().coerceIn(0, 255),
        (0x2c + f * (0x28 - 0x2c)).toInt().coerceIn(0, 255),
        FIELD_ALPHA
    )

    /**
     * Per-agent velocity (blue) and net steering force (orange) arrows, anchored at each agent's glyph.
     *
     * Length is proportional to magnitude but clamped, because an unclamped arrow on a fast agent covers
     * the model. Sampled at the capture's own rate, so this is only ever as dense as the run chose.
     */
    private fun vectorCommands(t: Double): List<DrawCmd> {
        if (model.agentsWithVectors.isEmpty()) return emptyList()
        val gridOffset = gridDrawOffset()
        val cmds = ArrayList<DrawCmd>()
        for (name in model.agentsWithVectors) {
            if (!model.agentPresentAt(name, t)) continue
            val sample = model.agentVectorAt(name, t) ?: continue
            val p = model.agentPositionAt(name, t) ?: continue
            val wrapped = model.torusBounds?.wrap(WorldPoint(p.x, p.y, p.z)) ?: p
            val cx = wrapped.x + gridOffset
            val cy = wrapped.y + gridOffset
            if (sample.vx.isFinite() && sample.vy.isFinite()) arrow(cmds, cx, cy, sample.vx, sample.vy, VELOCITY)
            if (sample.fx.isFinite() && sample.fy.isFinite()) arrow(cmds, cx, cy, sample.fx, sample.fy, FORCE)
        }
        return cmds
    }

    /** A shaft plus a head, the head in pixels so it stays legible at any zoom. */
    /**
     * The longest an overlay arrow may be drawn, as a share of the world rather than as an absolute length.
     *
     * A fixed clamp cannot work across models: eight world units is a reasonable arrow in a hundred-unit
     * flock and a third of the way across a twenty-five-metre room. In the crowd model, where forty jammed
     * pedestrians all push hard at once, that produced forty arrows longer than the crowd itself and the
     * model disappeared underneath them.
     */
    private val maxArrowWorld: Double by lazy {
        val world = worldBounds()
        (maxOf(world.width, world.height) * ARROW_SHARE_OF_WORLD).coerceAtLeast(1e-6)
    }

    private fun arrow(into: ArrayList<DrawCmd>, x: Double, y: Double, dx: Double, dy: Double, color: RgbaColor) {
        val mag = hypot(dx, dy)
        if (mag < 1e-9) return
        val len = mag.coerceAtMost(maxArrowWorld)
        val ex = x + dx / mag * len
        val ey = y + dy / mag * len
        into.add(DrawCmd.Polyline(listOf(x to y, ex to ey), color, width = 2.0))
        into.add(DrawCmd.ArrowHead(ex, ey, dx, dy, color, length = Extent.px(6.0), width = 2.0))
    }

    /**
     * A storage: the entities currently inside a named delay, arranged by the element's style.
     *
     * The footprint and label are drawn even when it is empty, so the element stays visible and selectable
     * while a layout is being authored rather than collapsing to a bare count. Past `maxShown` — or for the
     * COUNT style — it degrades to a count and a capacity gauge, because a hundred glyphs in a small box
     * conveys less than the number does.
     */
    private fun storageCommands(t: Double, static: Boolean): List<DrawCmd> {
        val l = layout ?: return emptyList()
        val cmds = ArrayList<DrawCmd>()
        for (st in l.storages) {
            val members = if (static) emptyList() else model.storageMembersAt(st.suspensionName, t)
            cmds.add(
                DrawCmd.Rect(
                    st.position.x, st.position.y,
                    Extent.world(st.width, minPx = 12.0), Extent.world(st.height, minPx = 10.0),
                    fill = STORAGE_FILL, stroke = STORAGE_BORDER
                )
            )
            cmds.add(
                DrawCmd.Text(
                    st.position.x, st.position.y, "${st.label ?: st.suspensionName} (${members.size})",
                    RgbaColor.DARK_GRAY, screenOffsetY = -6.0
                )
            )
            if (members.isEmpty()) continue

            if (st.style == StorageStyle.COUNT || members.size > st.maxShown) {
                // Degraded view: a proportional gauge instead of a crowd of glyphs.
                val fraction = if (st.capacity > 0) (members.size.toDouble() / st.capacity).coerceIn(0.0, 1.0) else 1.0
                cmds.add(
                    DrawCmd.Rect(
                        st.position.x, st.position.y,
                        Extent.world(st.width * fraction), Extent.world(st.height * 0.35),
                        fill = QUEUE_HEAD
                    )
                )
                continue
            }

            val rad = st.growthDegrees.toRadians()
            val dx = cos(rad)
            val dy = sin(rad)
            fun glyph(member: StorageMember, x: Double, y: Double) {
                val key = model.entityTypeOf(member.entityId) ?: DEFAULT_TYPE
                if (st.byType) cmds.add(glyphFor(key, x, y, STORAGE_GLYPH))
                else cmds.add(DrawCmd.Circle(x, y, Extent.world(STORAGE_GLYPH / 2, minPx = 2.0), fill = QUEUE_HEAD))
            }
            when (st.style) {
                // Each member drifts from entry to exit as its delay elapses, so progress is visible.
                StorageStyle.PROGRESS_BELT -> {
                    cmds.add(
                        DrawCmd.Polyline(
                            listOf(st.position.x to st.position.y,
                                (st.position.x + st.width * dx) to (st.position.y + st.width * dy)),
                            BELT_EMPTY
                        )
                    )
                    for (m in members) {
                        val span = m.arrivalTime - m.startTime
                        val progress = if (span > 0.0) ((t - m.startTime) / span).coerceIn(0.0, 1.0) else 0.0
                        glyph(m, st.position.x + progress * st.width * dx, st.position.y + progress * st.width * dy)
                    }
                }
                StorageStyle.LINE ->
                    members.forEachIndexed { i, m ->
                        glyph(m, st.position.x + i * st.spacing * dx, st.position.y + i * st.spacing * dy)
                    }
                // Jittered by a hash of the entity id, so a pile looks like a pile and does not shimmer
                // between frames the way a random offset would.
                StorageStyle.PILE -> {
                    // Scattered about the middle of the region, not about its corner. A storage's position is
                    // its top-left, so scattering about the position put half of every pile outside its own box.
                    val cx = st.position.x + st.width / 2
                    val cy = st.position.y + st.height / 2
                    val radius = minOf(st.width, st.height) * 0.5
                    for (m in members) {
                        val h = (m.entityId * 1103515245L + 12345L) and 0x7fffffffL
                        val angle = (h % 360).toDouble() * PI / 180.0
                        val rr = ((h / 360) % 100).toDouble() / 100.0 * radius
                        glyph(m, cx + rr * cos(angle), cy + rr * sin(angle))
                    }
                }
                else -> { // PACKED_REGION
                    val cell = (STORAGE_GLYPH + st.spacing * 0.4).coerceAtLeast(STORAGE_GLYPH + 1.0)
                    val cols = (st.width / cell).toInt().coerceAtLeast(1)
                    members.forEachIndexed { i, m ->
                        glyph(m, st.position.x + (i % cols + 0.5) * cell, st.position.y + (i / cols + 0.5) * cell)
                    }
                }
            }
        }
        return cmds
    }

    /** Per-element name and value labels, honoring the layout's overrides. */
    private fun labelCommands(t: Double, static: Boolean): List<DrawCmd> {
        val l = layout ?: return emptyList()
        val cmds = ArrayList<DrawCmd>()
        fun label(kind: ElementKind, name: String, anchor: LayoutPoint, defaultText: String?, value: String?) {
            val override = l.labels.firstOrNull { it.kind == kind && it.name == name }
            if (override?.visible != false) {
                (override?.text ?: defaultText)?.takeIf { it.isNotBlank() }?.let {
                    cmds.add(labelText(anchor, override?.dx ?: 0.0, override?.dy ?: DEFAULT_LABEL_DY, it))
                }
            }
            if (override?.valueVisible != false) {
                value?.takeIf { it.isNotBlank() }?.let {
                    cmds.add(labelText(anchor, override?.valueDx ?: 0.0, override?.valueDy ?: DEFAULT_VALUE_DY, it))
                }
            }
        }
        l.stations.forEach { label(ElementKind.STATION, it.stationName, it.position, it.label ?: it.stationName, null) }
        l.locations.forEach { loc ->
            loc.position?.let { label(ElementKind.LOCATION, loc.locationName, it, loc.label ?: loc.locationName, null) }
        }
        l.queues.forEach { q ->
            val length = if (static) 0 else {
                val members = model.queueMembersAt(q.queueName, t)
                if (members.isNotEmpty()) members.size else model.queueLengthAt(q.queueName, t)
            }
            label(ElementKind.QUEUE, q.queueName, q.position, q.queueName, "($length)")
        }
        l.resources.forEach { res ->
            val snap = if (static) null else model.resourceStateAt(res.resourceName, t)
            val value = if (res.showValue) "${snap?.busyUnits ?: 0}/${(snap?.capacity ?: 1).coerceAtLeast(1)}" else null
            label(ElementKind.RESOURCE, res.resourceName, res.position, res.resourceName, value)
        }
        l.movableResources.forEach { mr ->
            val p = (if (static) null else model.spatialElementPositionAt(mr.name, t))
                ?.let { LayoutPoint(it.x, it.y) } ?: moverAtRest(mr) ?: return@forEach
            label(ElementKind.MOVABLE_RESOURCE, mr.name, p, mr.label ?: mr.name, null)
        }
        return cmds
    }

    /**
     * A label anchored at a world position but offset by screen pixels, so it keeps a constant gap from
     * its glyph at any zoom. The offset cannot be folded into the world coordinate, so it is carried as a
     * screen-space nudge that the surface applies — expressed here by placing the text at the world point
     * and letting the pixel offset ride on the extent-free coordinates.
     */
    private fun labelText(anchor: LayoutPoint, dx: Double, dy: Double, text: String): DrawCmd =
        DrawCmd.Text(anchor.x, anchor.y, text, RgbaColor.DARK_GRAY, screenOffsetX = dx, screenOffsetY = dy)

    private fun clockCommands(t: Double, static: Boolean): List<DrawCmd> {
        val l = layout ?: return emptyList()
        val shown = if (static) 0.0 else t
        return l.clocks.map { clock ->
            DrawCmd.Text(
                clock.position.x, clock.position.y,
                "${clock.label ?: "Time"}: ${formatFixed(shown, 1)}",
                RgbaColor.BLACK,
                size = Extent.world(clock.fontSize, minPx = 4.0)
            )
        }
    }

    /**
     * The legend: a swatch and a name per declared object class and agent state, in a boxed panel.
     *
     * It sits in the top-right when the viewport is known, which is where the desktop viewer puts it and
     * which keeps it clear of the clock and the value read-outs authors tend to place top-left. Without a
     * viewport it falls back to the top-left, since the right edge is then unknown.
     *
     * The panel width is estimated from the character count rather than measured, because a scene has no
     * font metrics — it does not know which surface will draw it. Slightly generous is the safe direction:
     * the text sits inside the box either way.
     */
    /**
     * The screen-space room the legend needs, or null when there is nothing to legend.
     *
     * Published so a viewer can *reserve* this column before fitting the world into its panel. Without
     * that, the legend — which is drawn last, in screen space, in the corner — lands on top of whatever
     * the layout happens to put on its right edge, and a scaffolded layout puts the servers exactly
     * there. Reserving costs a little drawing area and guarantees nothing is hidden.
     */
    fun legendFootprint(): Viewport? {
        val classes = style.objectClassNames()
        val states = style.agentStateColorEntries()
        if (classes.isEmpty() && states.isEmpty()) return null
        val labels = classes + states.map { it.first }
        val widest = labels.maxOf { it.length }
        val textWidth = (widest * LEGEND_CHAR_WIDTH).coerceAtLeast(40.0)
        return Viewport(
            widthPx = LEGEND_PAD * 2 + LEGEND_SWATCH + 6 + textWidth + LEGEND_MARGIN * 2,
            heightPx = LEGEND_PAD * 2 + labels.size * LEGEND_ROW + LEGEND_MARGIN * 2
        )
    }

    private fun legendCommands(viewport: Viewport?): List<DrawCmd> {
        val classes = style.objectClassNames()
        val states = style.agentStateColorEntries()
        if (classes.isEmpty() && states.isEmpty()) return emptyList()

        val labels = classes + states.map { it.first }
        val widest = labels.maxOf { it.length }
        val textWidth = (widest * LEGEND_CHAR_WIDTH).coerceAtLeast(40.0)
        val boxWidth = LEGEND_PAD * 2 + LEGEND_SWATCH + 6 + textWidth
        val boxHeight = LEGEND_PAD * 2 + labels.size * LEGEND_ROW
        val boxLeft = viewport?.let { (it.widthPx - boxWidth - LEGEND_MARGIN).coerceAtLeast(0.0) } ?: LEGEND_MARGIN

        val cmds = ArrayList<DrawCmd>()
        cmds.add(
            DrawCmd.Rect(
                boxLeft, LEGEND_TOP, Extent.px(boxWidth), Extent.px(boxHeight),
                fill = LEGEND_FILL, stroke = RgbaColor.GRAY
            )
        )
        val swatchCenter = boxLeft + LEGEND_PAD + LEGEND_SWATCH / 2
        val textLeft = boxLeft + LEGEND_PAD + LEGEND_SWATCH + 6
        var y = LEGEND_TOP + LEGEND_PAD + LEGEND_ROW / 2
        for (name in classes) {
            cmds.add(
                DrawCmd.Glyph(
                    swatchCenter, y, Extent.px(LEGEND_SWATCH),
                    style.objectShape(name), style.objectColor(name), style.objectImageRef(name)
                )
            )
            cmds.add(DrawCmd.Text(textLeft, y + 4, name, RgbaColor.BLACK))
            y += LEGEND_ROW
        }
        for ((state, color) in states) {
            cmds.add(
                DrawCmd.Rect(
                    swatchCenter - LEGEND_SWATCH / 2, y - LEGEND_SWATCH / 2,
                    Extent.px(LEGEND_SWATCH), Extent.px(LEGEND_SWATCH), fill = color
                )
            )
            cmds.add(DrawCmd.Text(textLeft, y + 4, state, RgbaColor.BLACK))
            y += LEGEND_ROW
        }
        return cmds
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    private fun glyphFor(typeName: String, cx: Double, cy: Double, size: Double, colorOverride: RgbaColor? = null): DrawCmd =
        DrawCmd.Glyph(
            cx, cy, Extent.world(size, minPx = 3.0),
            style.objectShape(typeName),
            colorOverride ?: style.objectColor(typeName),
            style.objectImageRef(typeName)
        )

    private fun Double.toRadians(): Double = this * PI / 180.0

    /** Positions [count] co-located items evenly on a small ring so none is hidden behind another. */
    private fun fanRingOffset(index: Int, count: Int, radius: Double): Pair<Double, Double> {
        if (count <= 1) return 0.0 to 0.0
        val angle = 2.0 * PI * index / count
        return radius * cos(angle) to radius * sin(angle)
    }

    /** Fixed-point formatting without platform number formatting, so the JVM and the browser agree. */
    private fun formatFixed(value: Double, decimals: Int): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "Inf" else "-Inf"
        var factor = 1.0
        repeat(decimals) { factor *= 10.0 }
        val scaled = round(value * factor)
        val negative = scaled < 0
        val digits = abs(scaled).toLong().toString().padStart(decimals + 1, '0')
        val whole = digits.dropLast(decimals).ifEmpty { "0" }
        val frac = if (decimals > 0) "." + digits.takeLast(decimals) else ""
        return (if (negative) "-" else "") + whole + frac
    }

    companion object {
        private const val DEFAULT_TYPE = "QObject"
        private const val STATIC_TIME = 0.0
        private const val DEFAULT_BELT_WIDTH = 8.0
        private const val QUEUE_DOT_SIZE = 8.0
        private const val STORAGE_GLYPH = 10.0

        /** One line of text, in pixels — a second line must sit a fixed gap below the first at any zoom. */
        private const val TEXT_LINE = 13.0

        /** Arrow length is proportional to magnitude but clamped, or a fast agent's arrow covers the model. */
        /** An arrow at full stretch spans this share of the world's larger side. */
        private const val ARROW_SHARE_OF_WORLD = 0.06

        /** The heatmap's fixed translucency, so agents stay readable on top of it. */
        private const val FIELD_ALPHA = 0x44

        /** Blocked cells: dark and semi-opaque, so the grid beneath still reads and agents draw on top. */
        private val OBSTACLE = RgbaColor(0x44, 0x44, 0x44, 0x99)

        /** Costly ground: amber, so it is not mistaken for a wall or for the flow field's green-to-red. */
        private val TERRAIN = RgbaColor(0xd9, 0x8c, 0x1f)
        private const val TERRAIN_MIN_ALPHA = 36.0
        private const val TERRAIN_MAX_ALPHA = 130.0
        private const val QUEUE_HEAD_BAR = 12.0
        private const val DEFAULT_LABEL_DY = -12.0
        private const val DEFAULT_VALUE_DY = 14.0
        private const val PULSE_MIN_RADIUS = 6.0
        private const val PULSE_GROWTH = 22.0
        private const val LEGEND_MARGIN = 8.0
        private const val LEGEND_TOP = 8.0
        private const val LEGEND_ROW = 18.0
        private const val LEGEND_SWATCH = 12.0
        private const val LEGEND_PAD = 6.0

        /** Approximate advance width per character at the default label size, for sizing the panel. */
        private const val LEGEND_CHAR_WIDTH = 7.0

        /** Content occupying less than this share of the declared canvas is framed on its own. */
        private const val CANVAS_SHARE = 0.2

        private val DEFAULT_WORLD = BoundingBox(0.0, 0.0, 1000.0, 700.0)

        private val MARKER_RADIUS = Extent.px(4.0)
        private val LOCATION_SIDE = Extent.px(10.0)

        private val MARKER = RgbaColor(0x55, 0x55, 0x55)
        private val QUEUE_LINE = RgbaColor(0x88, 0x88, 0x88)
        private val QUEUE_HEAD = RgbaColor(0x33, 0x66, 0xcc)
        private val PATH_LINE = RgbaColor(0xb0, 0xb0, 0xb0)
        private val GRID_LINE = RgbaColor(0xee, 0xee, 0xee)
        private val NETWORK_LINE = RgbaColor(0xcc, 0xcc, 0xcc)
        private val SPACE_FILL = RgbaColor(0x42, 0x85, 0xf4, 0x14)
        private val SPACE_BORDER = RgbaColor(0xaa, 0xaa, 0xaa)
        private val BELT_EMPTY = RgbaColor(0xdd, 0xdd, 0xdd)
        private val BELT_FULL = RgbaColor(0x88, 0x88, 0x88)
        private val MOVER_BUSY_RING = RgbaColor(0xd6, 0x27, 0x28)
        private val PLANNED_PATH = RgbaColor(0x15, 0x6e, 0xc8, 0x99)
        private val PULSE_DEFAULT = RgbaColor(0xff, 0x7f, 0x0e)
        private val STORAGE_FILL = RgbaColor(0x42, 0x85, 0xf4, 0x12)
        private val STORAGE_BORDER = RgbaColor(0xbb, 0xbb, 0xbb)
        private val VELOCITY = RgbaColor(0x15, 0x6e, 0xc8)
        private val FORCE = RgbaColor(0xff, 0x7f, 0x0e)
        private val LEGEND_FILL = RgbaColor(255, 255, 255, 220)
    }
}
