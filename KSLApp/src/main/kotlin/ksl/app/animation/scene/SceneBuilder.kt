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
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.animation.SpatialSpaceDescriptor
import ksl.app.animation.geom.BoundingBox
import ksl.app.animation.style.RgbaColor
import ksl.app.animation.style.VisualStyle
import ksl.app.animation.replay.ReplayModel
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

    init {
        // A layout-free trace has no declared glyph sizes, and the declared default suits a process-view
        // canvas rather than an agent space a hundred units across. Calibrating to the world extent keeps
        // such a trace readable (it is a no-op wherever the layout declares its object classes).
        style.calibrateTo(worldBounds())
    }

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
        if (options.showPlannedPaths && !static) layer("plannedPaths", DrawSpace.WORLD, plannedPathCommands(t))
        layer("background", DrawSpace.WORLD, backgroundCommands())
        layer("paths", DrawSpace.WORLD, pathCommands())
        layer("conveyors", DrawSpace.WORLD, conveyorCommands(t, static))
        layer("stations", DrawSpace.WORLD, stationCommands())
        layer("locations", DrawSpace.WORLD, locationCommands())
        layer("queues", DrawSpace.WORLD, queueCommands(t, static))
        layer("resources", DrawSpace.WORLD, resourceCommands(t, static))
        layer("displays", DrawSpace.WORLD, displayCommands(t, static))
        if (!static) {
            layer("entities", DrawSpace.WORLD, entityCommands(t))
            if (options.showStationContents) layer("stationContents", DrawSpace.WORLD, stationContentCommands(t))
            layer("agents", DrawSpace.WORLD, agentCommands(t))
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
                stroke = MARKER, strokeWidth = 1.5
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

    /** Value read-outs and bars. Plots, histograms, summaries and storages are not yet ported. */
    private fun displayCommands(t: Double, static: Boolean): List<DrawCmd> {
        val l = layout ?: return emptyList()
        val cmds = ArrayList<DrawCmd>()
        for (bar in l.bars) {
            val value = if (static) 0.0 else model.responseValueAt(bar.responseName, t) ?: 0.0
            val w = Extent.world(bar.width)
            val h = Extent.world(bar.height)
            cmds.add(DrawCmd.Rect(bar.position.x, bar.position.y, w, h, fill = RgbaColor.WHITE, stroke = RgbaColor.DARK_GRAY))
            val fraction = if (bar.maxValue > 0.0) (value / bar.maxValue).coerceIn(0.0, 1.0) else 0.0
            if (fraction > 0.0) {
                cmds.add(
                    DrawCmd.Rect(
                        bar.position.x, bar.position.y,
                        Extent.world(bar.width * fraction), h,
                        fill = RgbaColor.parse(bar.color)
                    )
                )
            }
            cmds.add(DrawCmd.Text(bar.position.x, bar.position.y - 3.0, bar.label ?: bar.responseName, RgbaColor.DARK_GRAY))
        }
        for (v in l.values) {
            val value = if (static) null else model.responseValueAt(v.responseName, t)
            val shown = value?.let { formatFixed(it, v.decimals.coerceIn(0, 6)) } ?: "—"
            cmds.add(DrawCmd.Text(v.position.x, v.position.y, "${v.label ?: v.responseName}: $shown", RgbaColor.BLACK))
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
        private val LEGEND_FILL = RgbaColor(255, 255, 255, 220)
    }
}
