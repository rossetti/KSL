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

package ksl.app.swing.animation.view

import ksl.animation.AnimationLayout
import ksl.animation.BackgroundElement
import ksl.animation.BackgroundKind
import ksl.animation.BarDisplayElement
import ksl.animation.HistogramDisplayElement
import ksl.animation.LayoutPoint
import ksl.animation.LayoutShape
import ksl.animation.PlotDisplayElement
import ksl.animation.QueueLayoutElement
import ksl.animation.ResourceLayoutElement
import ksl.animation.SpatialSpaceDescriptor
import ksl.animation.StorageLayoutElement
import ksl.animation.StorageStyle
import ksl.animation.SummaryDisplayElement
import ksl.animation.ValueDisplayElement
import ksl.app.swing.animation.replay.ReplayModel
import ksl.app.swing.animation.replay.ResourceSnapshot
import ksl.app.swing.animation.replay.StorageMember
import ksl.app.swing.animation.replay.ResponseStats
import ksl.app.swing.animation.replay.WorldPoint
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import javax.swing.JPanel

/**
 * Paints the animation frame at [currentTime] for a loaded [replay]: the static layout (background,
 * spatial spaces), queues (members shown as dots), resources (colored by state), stations, and the
 * moving entities/agents at their interpolated positions, plus a clock.
 *
 * World coordinates are the layout's coordinate system; a fit-to-view [AffineTransform] (plus mouse
 * zoom/pan) maps them to screen pixels. Painting is offscreen-safe (works headless via paint into a
 * BufferedImage), which is how the renderer is tested without a display.
 */
class SimulationCanvas : JPanel() {

    var replay: ReplayModel? = null
        set(value) {
            field = value
            style = VisualStyle(value?.layout)
            imageCache.clear()
            zoom = 1.0; panX = 0.0; panY = 0.0
            repaint()
        }

    var currentTime: Double = 0.0
        set(value) {
            field = value
            repaint()
        }

    /** Whether to draw the object-class / agent-state legend in the top-right corner (8I.3a). */
    var showLegend: Boolean = true
        set(value) {
            field = value
            repaint()
        }

    private var style = VisualStyle(null)
    private var zoom = 1.0
    private var panX = 0.0
    private var panY = 0.0
    private val imageCache = HashMap<String, java.awt.image.BufferedImage?>()

    init {
        background = Color.WHITE
        isOpaque = true
        installMouseControls()
    }

    /**
     * The world rectangle the view frames: the layout's `[0,0,width,height]` unioned with the actual
     * coordinate extent of any coordinate-based movement (process entities, movable/transport resources).
     * For well-authored layouts the movement sits inside the canvas, so the union equals the layout bounds
     * and framing is unchanged; for continuous-space movers outside the canvas (Regime B) the box expands
     * (and may start below 0), and [worldTransform] offsets the origin so everything is framed on-screen.
     */
    private fun worldBounds(): Rectangle2D.Double {
        val w = replay?.layout?.width ?: 1000.0
        val h = replay?.layout?.height ?: 700.0
        val box = Rectangle2D.Double(0.0, 0.0, w, h)
        replay?.coordinateBounds()?.let { box.add(it) }
        return box
    }

    /** World->screen transform: fit [worldBounds] into the panel (with margin), then zoom/pan. */
    fun worldTransform(): AffineTransform {
        val b = worldBounds()
        val margin = 20.0
        val sx = (width - 2 * margin) / b.width.coerceAtLeast(1e-6)
        val sy = (height - 2 * margin) / b.height.coerceAtLeast(1e-6)
        val s = (minOf(sx, sy).coerceAtLeast(1e-6)) * zoom
        val tx = AffineTransform()
        tx.translate(margin + panX, margin + panY)
        tx.scale(s, s)
        tx.translate(-b.x, -b.y) // shift so the world box's corner maps to the top-left margin
        return tx
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val tx = worldTransform()
        // The grid is a spatial reference for authoring; draw it even with no loaded replay/layout so an
        // empty editor canvas still shows the coordinate frame.
        if (showGrid) drawGrid(g2, tx)
        drawEditHandles(g2, tx) // editor grab handles (independent of a loaded replay)
        val r = replay ?: return
        val layout = r.layout
        val t = currentTime

        r.effectiveSpaces.forEach { drawSpace(g2, tx, it) } // authored spaces, or trace-derived (8K.6a)
        if (showFlowField) r.flowFieldOverlays.forEach { drawFlowField(g2, tx, it) } // gradient heatmap (G11)
        layout?.spaceGeometry?.forEach { drawObstacles(g2, tx, it, r) } // model-extracted obstacles (P5a/G2)
        if (showPlannedPaths) drawPlannedPaths(g2, tx, r, t) // planned routes under the agents (G12)
        layout?.background?.forEach { drawBackground(g2, tx, it) }
        layout?.paths?.forEach { path -> drawPolyline(g2, tx, path.points, Color(0xb0, 0xb0, 0xb0), 1.0f) }
        for (cName in r.conveyorNames) drawBeltCells(g2, tx, r, cName, t) // belt occupancy overlay (8G.9)
        layout?.stations?.forEach {
            drawMarker(g2, tx, it.position, Color(0x55, 0x55, 0x55), "") // dot only; label honors overrides (C3)
            drawElementLabel(g2, layout, screen(tx, it.position), ksl.animation.ElementKind.STATION, it.stationName, it.label ?: it.stationName)
        }
        layout?.queues?.forEach { drawQueue(g2, tx, it, r, t) }
        layout?.storages?.forEach { drawStorage(g2, tx, it, r, t) } // entities in named delays (8K.4)
        layout?.resources?.forEach { drawResource(g2, tx, it, r, t) }
        layout?.bars?.forEach { drawBar(g2, tx, it, r.responseValueAt(it.responseName, t) ?: 0.0) }
        layout?.plots?.forEach { drawPlot(g2, tx, it, r.responseSamplesUpTo(it.responseName, t), t) }
        layout?.values?.forEach { drawValue(g2, tx, it, r.responseValueAt(it.responseName, t)) }
        layout?.summaries?.forEach { drawSummary(g2, tx, it, r.responseStatsAt(it.responseName, t)) }
        layout?.histograms?.forEach { h ->
            drawHistogram(g2, tx, h, r.responseSamplesUpTo(h.responseName, t).map { it.second })
        }

        // Moving entities: drawn *inside* their resource while in service, suppressed while waiting
        // in a queue (the queue's own dots represent them — no double-draw), otherwise at their
        // interpolated move position.
        for (e in r.entitiesAt(t)) {
            // Blocked at a conveyor entry: draw at the entry location with a red "waiting" ring (8G.8).
            val blockedLoc = r.entityBlockedLocationAt(e.id, t)
            if (blockedLoc != null) {
                val pos = layout?.stations?.firstOrNull { it.stationName == blockedLoc }?.position
                if (pos != null) {
                    val wp = WorldPoint(pos.x, pos.y)
                    val c = style.processColor(r.entityProcessAt(e.id, t)) ?: style.objectColor(e.typeName)
                    drawObject(g2, tx, wp, c, style.objectShape(e.typeName), style.objectSize(e.typeName), style.objectImageRef(e.typeName))
                    val s = screen(tx, wp)
                    val d = (style.objectSize(e.typeName) * scaleOf(tx)).coerceAtLeast(6.0) + 5.0
                    g2.color = Color.RED; g2.stroke = BasicStroke(2.0f)
                    g2.draw(Ellipse2D.Double(s.x - d / 2, s.y - d / 2, d, d))
                    continue
                }
            }
            // In service: drawn inside its resource's unit cell by drawResource (8A.2 + 8C.3), so skip here.
            if (r.entityServiceResourceAt(e.id, t) != null) continue
            if (r.entityQueueAt(e.id, t) != null) continue // represented by the queue's dots
            // In a named delay that has a placed storage: drawn by drawStorage, skip here (8K.4).
            val storageKey = r.entityStorageAt(e.id, t)
            if (storageKey != null && layout?.storages?.any { it.suspensionName == storageKey } == true) continue
            val p = r.entityPositionAt(e.id, t) ?: continue
            // Tint by the entity's current process when the layout defines a process color (10.1e),
            // otherwise fall back to the type color.
            val c = style.processColor(r.entityProcessAt(e.id, t)) ?: style.objectColor(e.typeName)
            drawObject(g2, tx, p, c, style.objectShape(e.typeName), style.objectSize(e.typeName), style.objectImageRef(e.typeName))
        }
        // Station-network entities (QObjects): placed at their current station's layout position
        // (name→position resolution). They have no type, so use a default "QObject" style. Multiple
        // at one station are fanned out slightly so they don't fully overlap (8G.2).
        if (layout?.stations?.isNotEmpty() == true) {
            val perStation = HashMap<String, Int>()
            for (id in r.networkEntitiesAt(t)) {
                val key = r.networkEntityTypeOf(id) ?: "QObject"
                val stationName = r.entityStationAt(id, t)
                val pos: WorldPoint = if (stationName != null) {
                    val sp = layout.stations.firstOrNull { it.stationName == stationName }?.position ?: continue
                    val k = perStation.getOrDefault(stationName, 0); perStation[stationName] = k + 1
                    WorldPoint(sp.x, sp.y + k * (style.objectSize(key) * 0.9)) // fan co-located entities
                } else {
                    // Between stations on a timed transfer: slide along the connector (8I.4).
                    r.networkEntityTransitAt(id, t) ?: continue
                }
                drawObject(g2, tx, pos, style.objectColor(key), style.objectShape(key), style.objectSize(key), style.objectImageRef(key))
            }
        }

        // Agents. Grid agents emit corner (col,row); center them on cells via a half-cell offset.
        val gridOff = gridDrawOffset(r.effectiveSpaces)
        val showHeading = gridOff == 0.0 // heading ticks only for continuous movers (8F.5)
        // First collect each present agent's drawn world position, then fan co-located ones apart (8I.3b).
        val drawnAgents = ArrayList<DrawnAgent>()
        for (name in r.agentNames) {
            if (!r.agentPresentAt(name, t)) continue // not yet spawned, or removed (8F.2)
            val p = r.agentPositionAt(name, t) ?: continue
            // Style agents by their type (like entities), falling back to the agent name when unknown.
            val key = r.agentTypeOf(name) ?: name
            // State-based color (8F.1) overrides the type color while the agent is in that state.
            val color = style.agentStateColor(r.agentStateAt(name, t)) ?: style.objectColor(key)
            // Wrap the (possibly seam-crossing) position into the toroidal bounds for drawing (8F.7).
            val drawn = (r.torusBounds?.wrap(WorldPoint(p.x, p.y, p.z)) ?: p).let { WorldPoint(it.x + gridOff, it.y + gridOff, it.z) }
            drawnAgents.add(DrawnAgent(key, color, drawn, if (showHeading) r.agentVelocityAt(name, t) else null))
        }
        // Buckets of agents sharing (nearly) the same spot are spread on a small ring so none is hidden.
        for ((_, group) in drawnAgents.groupBy { Math.round(it.pos.x) to Math.round(it.pos.y) }) {
            group.forEachIndexed { j, a ->
                val (ox, oy) = fanRingOffset(j, group.size, style.objectSize(a.key) * 0.6)
                val pos = WorldPoint(a.pos.x + ox, a.pos.y + oy, a.pos.z)
                drawObject(g2, tx, pos, a.color, style.objectShape(a.key), style.objectSize(a.key), style.objectImageRef(a.key))
                if (a.vel != null) drawHeadingTick(g2, tx, pos, a.vel, style.objectSize(a.key))
            }
        }
        if (showVectors) drawVectors(g2, tx, r, t, gridOff) // velocity/force arrows on top of the agents (G10)
        if (showMarkerPulses) drawMarkerPulses(g2, tx, r, t) // transient event highlights on top (G-animated)

        // Movable/transport resources: at their interpolated position while moving (8K.5), otherwise anchored to
        // their home-base station's layout position when known — so the static editor preview matches what the
        // replay shows for a coordinate-free spatial model (e.g. a DistancesModel, whose locations have no x/y) —
        // else their saved parked position (C4). A pool of movers homed at one station would stack perfectly, so
        // co-located movers are fanned out on a small ring, like agents, so each one is visible (10.8 follow-up).
        val drawnMovers = ArrayList<DrawnMover>()
        layout?.movableResources?.forEach { mr ->
            val p = r.spatialElementPositionAt(mr.name, t)
                ?: mr.homeBase?.let { hb -> layout.stations.firstOrNull { it.stationName == hb }?.position }
                    ?.let { WorldPoint(it.x, it.y, 0.0) }
                ?: mr.position?.let { WorldPoint(it.x, it.y, 0.0) }
                ?: return@forEach
            drawnMovers.add(DrawnMover(mr, r.moverStateAt(mr.name, t), p))
        }
        for ((_, group) in drawnMovers.groupBy { Math.round(it.pos.x) to Math.round(it.pos.y) }) {
            group.forEachIndexed { j, dm ->
                val mr = dm.mr; val ms = dm.ms
                val transporting = ms?.mode == ksl.animation.MoverMode.TRANSPORTING
                val (ox, oy) = fanRingOffset(j, group.size, mr.size * 0.7)
                val p = WorldPoint(dm.pos.x + ox, dm.pos.y + oy, dm.pos.z)
                val color = VisualStyle.parseColor((if (transporting) mr.busyColor else null) ?: mr.color)
                val image = (if (transporting) mr.busyImage else mr.idleImage) ?: mr.imageRef
                drawObject(g2, tx, p, color, mr.shape, mr.size, image)
                // While transporting, ring the mover and draw the carried entity's glyph on it, so "seized/carrying"
                // is obvious even when no busy color/image is configured (C2 / #8 follow-up).
                if (transporting) {
                    val s = screen(tx, p)
                    val d = (mr.size * scaleOf(tx)).coerceAtLeast(8.0)
                    g2.color = mr.busyColor?.let { VisualStyle.parseColor(it) } ?: Color(0xd6, 0x27, 0x28)
                    g2.draw(Ellipse2D.Double(s.x - d * 0.7, s.y - d * 0.7, d * 1.4, d * 1.4))
                    val key = ms!!.carriedEntityId?.let { r.entityTypeOf(it) } ?: ms.carriedEntityType ?: "QObject"
                    fillGlyph(g2, s.x, s.y, (mr.size * 0.55 * scaleOf(tx)).coerceAtLeast(4.0),
                        style.objectColor(key), style.objectShape(key), style.objectImageRef(key))
                }
                drawElementLabel(g2, layout, screen(tx, p), ksl.animation.ElementKind.MOVABLE_RESOURCE, mr.name, mr.label ?: mr.name)
            }
        }

        layout?.clocks?.forEach { clock ->
            val s = screen(tx, clock.position)
            g2.color = Color.BLACK
            val oldFont = g2.font
            val px = (clock.fontSize * scaleOf(tx)).toFloat().coerceAtLeast(4f) // size in layout units, scales with zoom
            g2.font = oldFont.deriveFont(px)
            g2.drawString("${clock.label ?: "Time"}: ${"%.1f".format(t)}", s.x.toFloat(), s.y.toFloat())
            g2.font = oldFont
        }
        if (showLegend) drawLegend(g2)
    }

    /** An agent resolved to its drawn world position, for the co-location fan-out (8I.3b). */
    private class DrawnAgent(val key: String, val color: Color, val pos: WorldPoint, val vel: WorldPoint?)

    /** A movable resource resolved to its drawn world position, for the co-location fan-out (10.8 follow-up). */
    private class DrawnMover(
        val mr: ksl.animation.MovableResourceLayoutElement,
        val ms: ksl.app.swing.animation.replay.MoverState?,
        val pos: WorldPoint
    )

    /**
     * Draws a legend box in the top-right listing each object-class type (its shape/color swatch) and
     * each agent state color, all from the layout (8I.3a). Painted in screen space, so it is unaffected
     * by zoom/pan. A no-op when the layout declares neither.
     */
    private fun drawLegend(g2: Graphics2D) {
        val names = style.objectClassNames()
        val states = style.agentStateColorEntries()
        if (names.isEmpty() && states.isEmpty()) return
        val rowH = 18
        val pad = 6
        val swatch = 12.0
        val fm = g2.fontMetrics
        var textW = 40
        for (n in names) textW = maxOf(textW, fm.stringWidth(n))
        for ((s, _) in states) textW = maxOf(textW, fm.stringWidth(s))
        val boxW = pad + swatch.toInt() + 6 + textW + pad
        val boxH = pad * 2 + (names.size + states.size) * rowH
        val x = (width - boxW - 8).coerceAtLeast(0)
        val y = 8
        g2.color = Color(255, 255, 255, 220)
        g2.fill(Rectangle2D.Double(x.toDouble(), y.toDouble(), boxW.toDouble(), boxH.toDouble()))
        g2.color = Color.GRAY
        g2.draw(Rectangle2D.Double(x.toDouble(), y.toDouble(), boxW.toDouble(), boxH.toDouble()))
        val sx = x + pad + swatch / 2
        val tx0 = (x + pad + swatch + 6).toFloat()
        var ry = y + pad + rowH / 2
        for (n in names) {
            fillGlyph(g2, sx, ry.toDouble(), swatch, style.objectColor(n), style.objectShape(n), style.objectImageRef(n))
            g2.color = Color.BLACK
            g2.drawString(n, tx0, (ry + 4).toFloat())
            ry += rowH
        }
        for ((s, c) in states) {
            g2.color = c
            g2.fill(Rectangle2D.Double(sx - swatch / 2, ry - swatch / 2, swatch, swatch))
            g2.color = Color.BLACK
            g2.drawString(s, tx0, (ry + 4).toFloat())
            ry += rowH
        }
    }

    // ── drawing helpers (world coordinates in, screen pixels out) ───────────────────────────────

    /**
     * Half-cell offset used to center grid agents on cells (grid projections emit corner `(col,row)`
     * coordinates). Returns 0 unless the layout is purely a single grid space, so continuous and
     * process-view layouts are unaffected.
     */
    private fun gridDrawOffset(spaces: List<SpatialSpaceDescriptor>): Double {
        val grids = spaces.filterIsInstance<SpatialSpaceDescriptor.Grid>()
        val continuous = spaces.filterIsInstance<SpatialSpaceDescriptor.Continuous>()
        return if (grids.size == 1 && continuous.isEmpty()) grids[0].cellSize / 2.0 else 0.0
    }

    private fun screen(tx: AffineTransform, p: LayoutPoint): Point2D = tx.transform(Point2D.Double(p.x, p.y), null)
    private fun screen(tx: AffineTransform, p: WorldPoint): Point2D = tx.transform(Point2D.Double(p.x, p.y), null)
    private fun scaleOf(tx: AffineTransform): Double = tx.scaleX

    /** Draws a short black tick from an agent's center in its heading direction (8F.5). */
    private fun drawHeadingTick(g2: Graphics2D, tx: AffineTransform, p: WorldPoint, v: WorldPoint, size: Double) {
        val mag = kotlin.math.hypot(v.x, v.y)
        if (mag <= 0.0) return
        val c = screen(tx, p)
        val len = (size * scaleOf(tx)).coerceAtLeast(6.0)
        // worldTransform scales x and y positively (no y-flip), so a world direction maps directly.
        val ex = c.x + v.x / mag * len
        val ey = c.y + v.y / mag * len
        g2.color = Color.BLACK
        g2.stroke = BasicStroke(1.5f)
        g2.drawLine(c.x.toInt(), c.y.toInt(), ex.toInt(), ey.toInt())
    }

    private fun drawObject(
        g2: Graphics2D, tx: AffineTransform, p: WorldPoint,
        color: Color, shape: LayoutShape, size: Double, imageRef: String? = null
    ) {
        val s = screen(tx, p)
        val d = (size * scaleOf(tx)).coerceAtLeast(3.0)
        fillGlyph(g2, s.x, s.y, d, color, shape, imageRef)
    }

    /**
     * Draws a glyph of [shape] centered at screen `(cx, cy)` with diameter [d] in [color]. TRIANGLE and
     * DIAMOND are real polygons; IMAGE draws [imageRef] (resolved + cached) centered, falling back to a
     * filled square when the image is missing/unreadable (8I.3c). The single place shapes are rendered,
     * so moving objects, queue members and in-service occupants all draw typed icons consistently.
     */
    private fun fillGlyph(
        g2: Graphics2D, cx: Double, cy: Double, d: Double, color: Color, shape: LayoutShape, imageRef: String?
    ) {
        val x = cx - d / 2
        val y = cy - d / 2
        when (shape) {
            LayoutShape.IMAGE -> {
                val img = imageRef?.let { loadImage(it) }
                if (img != null) {
                    g2.drawImage(img, x.toInt(), y.toInt(), d.toInt().coerceAtLeast(1), d.toInt().coerceAtLeast(1), null)
                } else {
                    g2.color = color
                    g2.fill(Rectangle2D.Double(x, y, d, d))
                }
            }
            LayoutShape.SQUARE -> { g2.color = color; g2.fill(Rectangle2D.Double(x, y, d, d)) }
            LayoutShape.TRIANGLE -> {
                g2.color = color
                val p = Path2D.Double()
                p.moveTo(cx, cy - d / 2); p.lineTo(cx + d / 2, cy + d / 2); p.lineTo(cx - d / 2, cy + d / 2); p.closePath()
                g2.fill(p)
            }
            LayoutShape.DIAMOND -> {
                g2.color = color
                val p = Path2D.Double()
                p.moveTo(cx, cy - d / 2); p.lineTo(cx + d / 2, cy); p.lineTo(cx, cy + d / 2); p.lineTo(cx - d / 2, cy); p.closePath()
                g2.fill(p)
            }
            LayoutShape.CIRCLE -> { g2.color = color; g2.fill(Ellipse2D.Double(x, y, d, d)) }
        }
    }

    private fun drawQueue(g2: Graphics2D, tx: AffineTransform, q: QueueLayoutElement, r: ReplayModel, t: Double) {
        val base = screen(tx, q.position)
        val scale = scaleOf(tx)
        val step = q.spacing * scale
        val dot = (8.0 * scale).coerceAtLeast(3.0)
        val members = r.queueMembersAt(q.queueName, t) // identified members (8C.2), if available
        val length = if (members.isNotEmpty()) members.size else r.queueLengthAt(q.queueName, t)
        val n = minOf(length, q.maxShown)
        // Head is q.position; the line extends away along growthDegrees (0deg = right, clockwise; 8I.6).
        val rad = Math.toRadians(q.growthDegrees)
        val dx = kotlin.math.cos(rad)
        val dy = kotlin.math.sin(rad)
        // Persistent "____|" glyph (P3): an extent line head→tail with a short head bar at the front, drawn in
        // both the layout editor and replay so an empty queue is still visible (replaces the old dot-only draw).
        val lineLen = step * q.maxShown.coerceAtLeast(1)
        val tailX = base.x + lineLen * dx; val tailY = base.y + lineLen * dy
        g2.color = Color(0x88, 0x88, 0x88)
        g2.draw(Line2D.Double(base.x, base.y, tailX, tailY)) // the line ("____")
        val bar = (12.0 * scale).coerceAtLeast(7.0)
        val px = -dy; val py = dx // unit perpendicular → the head bar ("|")
        g2.color = Color(0x33, 0x66, 0xcc)
        g2.draw(Line2D.Double(base.x - px * bar / 2, base.y - py * bar / 2, base.x + px * bar / 2, base.y + py * bar / 2))
        // Members sit along the line, growing from the head.
        for (i in 0 until n) {
            val cx = base.x + i * step * dx
            val cy = base.y + i * step * dy
            val id = members.getOrNull(i)
            val key = id?.let { r.entityTypeOf(it) ?: r.networkEntityTypeOf(it) }
            if (key != null) {
                fillGlyph(g2, cx, cy, dot, style.objectColor(key), style.objectShape(key), style.objectImageRef(key))
            } else {
                g2.color = Color(0x33, 0x66, 0xcc)
                g2.fill(Ellipse2D.Double(cx - dot / 2, cy - dot / 2, dot, dot))
            }
        }
        drawElementLabel(g2, r.layout, base, ksl.animation.ElementKind.QUEUE, q.queueName, q.queueName, "($length)")
    }

    /**
     * Draws a storage: the entities currently in a named delay, arranged by [StorageStyle] (8K.4).
     * PROGRESS_BELT drifts each member from entry to exit by its delay progress; other styles pack /
     * line up / pile them. Beyond `maxShown` (or for COUNT) it degrades to a count + capacity gauge.
     */
    private fun drawStorage(g2: Graphics2D, tx: AffineTransform, st: StorageLayoutElement, r: ReplayModel, t: Double) {
        val anchor = screen(tx, st.position)
        val scale = scaleOf(tx)
        val members = r.storageMembersAt(st.suspensionName, t)
        val count = members.size
        // Footprint box: draw the storage's region + label ALWAYS, so it's visible and selectable even when
        // empty (e.g. on the static Layout-tab preview, which has no replay members) — not just a bare "(0)".
        val bw = (st.width * scale).coerceAtLeast(12.0); val bh = (st.height * scale).coerceAtLeast(10.0)
        g2.color = Color(0x42, 0x85, 0xf4, 0x12); g2.fill(Rectangle2D.Double(anchor.x, anchor.y, bw, bh))
        g2.color = Color(0xbb, 0xbb, 0xbb); g2.stroke = BasicStroke(1f); g2.draw(Rectangle2D.Double(anchor.x, anchor.y, bw, bh))
        g2.color = Color.DARK_GRAY
        g2.drawString("${st.label ?: st.suspensionName} ($count)", anchor.x.toFloat(), (anchor.y - 6.0).toFloat())
        if (count == 0) return
        if (st.style == StorageStyle.COUNT || count > st.maxShown) {
            drawStorageBadge(g2, st, anchor, members, scale, r); return
        }
        val glyph = (10.0 * scale).coerceAtLeast(4.0)
        fun draw(m: StorageMember, cx: Double, cy: Double) {
            val key = r.entityTypeOf(m.entityId) ?: "QObject"
            if (st.byType) fillGlyph(g2, cx, cy, glyph, style.objectColor(key), style.objectShape(key), style.objectImageRef(key))
            else { g2.color = Color(0x33, 0x66, 0xcc); g2.fill(Ellipse2D.Double(cx - glyph / 2, cy - glyph / 2, glyph, glyph)) }
        }
        val rad = Math.toRadians(st.growthDegrees)
        val dx = kotlin.math.cos(rad); val dy = kotlin.math.sin(rad)
        when (st.style) {
            StorageStyle.PROGRESS_BELT -> {
                val len = st.width * scale
                g2.color = Color(0xcc, 0xcc, 0xcc); g2.stroke = BasicStroke(1f)
                g2.draw(Line2D.Double(anchor.x, anchor.y, anchor.x + len * dx, anchor.y + len * dy))
                for (m in members) {
                    val span = m.arrivalTime - m.startTime
                    val p = if (span > 0.0) ((t - m.startTime) / span).coerceIn(0.0, 1.0) else 0.0 // PACKED fallback: entry
                    draw(m, anchor.x + p * len * dx, anchor.y + p * len * dy)
                }
            }
            StorageStyle.LINE -> {
                val step = st.spacing * scale
                members.forEachIndexed { i, m -> draw(m, anchor.x + i * step * dx, anchor.y + i * step * dy) }
            }
            StorageStyle.PILE -> {
                val rpx = minOf(st.width, st.height) * 0.5 * scale
                for (m in members) {
                    val hsh = (m.entityId * 1103515245L + 12345L) and 0x7fffffffL
                    val ang = (hsh % 360) * Math.PI / 180.0
                    val rr = ((hsh / 360) % 100) / 100.0 * rpx
                    draw(m, anchor.x + rr * kotlin.math.cos(ang), anchor.y + rr * kotlin.math.sin(ang))
                }
            }
            else -> { // PACKED_REGION
                val cell = (glyph + st.spacing * 0.4 * scale).coerceAtLeast(glyph + 2.0)
                val cols = ((st.width * scale) / cell).toInt().coerceAtLeast(1)
                members.forEachIndexed { i, m ->
                    draw(m, anchor.x + (i % cols + 0.5) * cell, anchor.y + (i / cols + 0.5) * cell)
                }
            }
        }
    }

    /** The degraded storage view: a capacity gauge segmented by entity type + a count read-out (8K.4). */
    private fun drawStorageBadge(
        g2: Graphics2D, st: StorageLayoutElement, anchor: Point2D, members: List<StorageMember>, scale: Double, r: ReplayModel
    ) {
        val w = (st.width * scale).coerceAtLeast(40.0)
        val h = 14.0
        val x = anchor.x; val y = anchor.y
        g2.color = Color(0xee, 0xee, 0xee); g2.fill(Rectangle2D.Double(x, y, w, h))
        g2.color = Color.GRAY; g2.draw(Rectangle2D.Double(x, y, w, h))
        val count = members.size
        val denom = if (st.capacity > 0) st.capacity.toDouble() else count.toDouble().coerceAtLeast(1.0)
        var fx = x
        for ((key, n) in members.groupingBy { r.entityTypeOf(it.entityId) ?: "QObject" }.eachCount()) {
            val segW = w * (n / denom)
            g2.color = style.objectColor(key); g2.fill(Rectangle2D.Double(fx, y, segW, h)); fx += segW
        }
        g2.color = Color.BLACK
        val cap = if (st.capacity > 0) "/${st.capacity}" else ""
        g2.drawString("$count$cap", (x + w + 4).toFloat(), (y + h - 2).toFloat())
    }

    private fun drawResource(g2: Graphics2D, tx: AffineTransform, res: ResourceLayoutElement, r: ReplayModel, t: Double) {
        val s = screen(tx, res.position)
        val d = (res.size * scaleOf(tx)).coerceAtLeast(6.0)
        val snap = r.resourceStateAt(res.resourceName, t)
        val capacity = (snap?.capacity ?: 1).coerceAtLeast(1)
        val busy = snap?.busyUnits ?: 0
        val state = snap?.state
        val units = r.resourceUnitsAt(res.resourceName, t) // entity per occupied unit (8C.3)
        val failed = state != null && state.contains("Fail", ignoreCase = true)
        val inactive = state != null && state.contains("Inactive", ignoreCase = true)
        if (capacity <= 1) {
            // A per-state image (10.7) takes the cell; otherwise the colored square.
            drawResourceCell(g2, s.x, s.y, d, style.resourceImageRef(res, state), style.resourceColor(res, state))
            units.firstOrNull()?.let { drawUnitOccupant(g2, r, it, s.x, s.y, d) } // the entity in service
        } else {
            val unit = d
            val x0 = s.x - capacity * unit / 2
            for (i in 0 until capacity) {
                val unitBusy = !failed && !inactive && i < busy
                val image = when {
                    failed -> res.failedImage
                    inactive -> res.inactiveImage
                    unitBusy -> res.busyImage
                    else -> res.idleImage
                }
                val color = when {
                    failed -> VisualStyle.parseColor(res.failedColor)
                    inactive -> VisualStyle.parseColor(res.inactiveColor)
                    unitBusy -> VisualStyle.parseColor(res.busyColor)
                    else -> VisualStyle.parseColor(res.idleColor)
                }
                val cx = x0 + i * unit + unit / 2
                drawResourceCell(g2, cx, s.y, unit, image, color)
                units.getOrNull(i)?.let { drawUnitOccupant(g2, r, it, cx, s.y, unit) } // which entity (8C.3)
            }
        }
        // Optional live "busy/capacity" read-out (P4), shown via the element's value annotation when opted in.
        val valueText = if (res.showValue) "$busy/$capacity" else null
        drawElementLabel(g2, r.layout, s, ksl.animation.ElementKind.RESOURCE, res.resourceName, res.resourceName, valueText)
    }

    /** Draws one resource cell centered at ([cx],[cy]) of side [d]: the state [imageRef] if it loads, else [color] (10.7). */
    private fun drawResourceCell(g2: Graphics2D, cx: Double, cy: Double, d: Double, imageRef: String?, color: Color) {
        val img = imageRef?.let { loadImage(it) }
        val x = cx - d / 2; val y = cy - d / 2
        if (img != null) {
            g2.drawImage(img, x.toInt(), y.toInt(), d.toInt().coerceAtLeast(1), d.toInt().coerceAtLeast(1), null)
        } else {
            g2.color = color
            g2.fill(Rectangle2D.Double(x, y, d, d))
            g2.color = Color.BLACK
            g2.draw(Rectangle2D.Double(x, y, d, d))
        }
    }

    /** Draws the entity occupying a resource unit as its typed glyph, centered in the unit (8C.3). */
    private fun drawUnitOccupant(g2: Graphics2D, r: ReplayModel, id: Long, cx: Double, cy: Double, cell: Double) {
        val key = r.entityTypeOf(id) ?: r.networkEntityTypeOf(id) ?: "QObject"
        val gd = cell * 0.6
        fillGlyph(g2, cx, cy, gd, style.objectColor(key), style.objectShape(key), style.objectImageRef(key))
    }

    private fun drawValue(g2: Graphics2D, tx: AffineTransform, v: ValueDisplayElement, value: Double?) {
        val s = screen(tx, v.position)
        val label = v.label ?: v.responseName
        val shown = if (value == null) "—" else "%.${v.decimals.coerceIn(0, 6)}f".format(value)
        g2.color = Color.BLACK
        g2.drawString("$label: $shown", s.x.toFloat(), s.y.toFloat())
    }

    /** Draws a live histogram/frequency chart, binned in-viewer from the raw observed [values] (8D.1). */
    private fun drawHistogram(g2: Graphics2D, tx: AffineTransform, h: HistogramDisplayElement, values: List<Double>) {
        val s = screen(tx, h.position)
        val sc = scaleOf(tx)
        val w = h.width * sc
        val ht = h.height * sc
        val rect = Rectangle2D.Double(s.x, s.y, w, ht)
        g2.color = Color.WHITE; g2.fill(rect)
        g2.color = Color.DARK_GRAY; g2.stroke = BasicStroke(1.0f); g2.draw(rect)
        if (h.label != null) g2.drawString(h.label, s.x.toFloat(), (s.y - 2).toFloat())
        if (values.isEmpty()) return

        // Bin the values: by integer value when discrete, else into equal-width bins over [min,max].
        val counts: List<Int>
        if (h.discrete) {
            val tally = sortedMapOf<Int, Int>()
            for (v in values) tally.merge(Math.round(v).toInt(), 1, Int::plus)
            counts = tally.values.toList()
        } else {
            val lo = values.min(); val hi = values.max()
            val nb = h.bins.coerceAtLeast(1)
            val span = (hi - lo).takeIf { it > 0.0 } ?: 1.0
            val c = IntArray(nb)
            for (v in values) {
                val idx = (((v - lo) / span) * nb).toInt().coerceIn(0, nb - 1)
                c[idx]++
            }
            counts = c.toList()
        }
        val maxC = (counts.maxOrNull() ?: 1).coerceAtLeast(1)
        val barW = w / counts.size
        g2.color = VisualStyle.parseColor(h.color)
        for ((i, c) in counts.withIndex()) {
            val bh = ht * c / maxC
            g2.fill(Rectangle2D.Double(rect.x + i * barW, rect.y + ht - bh, (barW - 1).coerceAtLeast(1.0), bh))
        }
    }

    private fun drawSummary(g2: Graphics2D, tx: AffineTransform, sum: SummaryDisplayElement, stats: ResponseStats?) {
        val s = screen(tx, sum.position)
        val d = sum.decimals.coerceIn(0, 6)
        g2.color = Color.BLACK
        val line = g2.fontMetrics.height
        g2.drawString(sum.label ?: sum.responseName, s.x.toFloat(), s.y.toFloat())
        val body = if (stats == null) "—" else
            "n=%.0f  mean=%.${d}f  min=%.${d}f  max=%.${d}f".format(stats.count, stats.average, stats.min, stats.max)
        g2.drawString(body, s.x.toFloat(), (s.y + line).toFloat())
    }

    private fun drawBar(g2: Graphics2D, tx: AffineTransform, bar: BarDisplayElement, value: Double) {
        val s = screen(tx, bar.position)
        val sc = scaleOf(tx)
        val rect = Rectangle2D.Double(s.x, s.y, bar.width * sc, bar.height * sc)
        ChartRenderer.bar(g2, rect, value, bar.maxValue, VisualStyle.parseColor(bar.color), bar.label ?: bar.responseName)
    }

    private fun drawPlot(g2: Graphics2D, tx: AffineTransform, plot: PlotDisplayElement, samples: List<Pair<Double, Double>>, t: Double) {
        val s = screen(tx, plot.position)
        val sc = scaleOf(tx)
        val rect = Rectangle2D.Double(s.x, s.y, plot.width * sc, plot.height * sc)
        ChartRenderer.timeSeries(
            g2, rect, samples, currentTime = t, window = plot.windowDuration,
            yMax = null, color = VisualStyle.parseColor(plot.color), label = plot.label ?: plot.responseName
        )
    }

    /**
     * Draws a conveyor's cells along its (possibly routed) geometry — empty cells faint, occupied cells filled
     * (8G.9). An authored [ksl.animation.ConveyorLayoutElement] supplies the fill color/width and, when
     * showDirection is set, travel-direction arrows along the belt (10.5c).
     */
    private fun drawBeltCells(g2: Graphics2D, tx: AffineTransform, r: ReplayModel, name: String, t: Double) {
        val occ = r.conveyorOccupiedCellsAt(name, t)
        val route = r.layout?.conveyors?.firstOrNull { it.conveyorName == name }
        val empty = Color(0xdd, 0xdd, 0xdd)
        val full = route?.let { VisualStyle.parseColor(it.color) } ?: Color(0x88, 0x88, 0x88)
        val maxCell = r.conveyorMaxCellOf(name)
        val sz = ((route?.width ?: 8.0) * 0.5 * scaleOf(tx)).coerceIn(3.0, 14.0)
        for (cell in 0..maxCell) {
            val p = r.conveyorCellPosition(name, cell) ?: continue
            val s = screen(tx, p)
            val box = Rectangle2D.Double(s.x - sz / 2, s.y - sz / 2, sz, sz)
            if (cell in occ) { g2.color = full; g2.fill(box) } else { g2.color = empty; g2.draw(box) }
        }
        if (route?.showDirection == true && maxCell >= 2) {
            g2.color = full
            // Arrows every ~quarter of the belt point from a cell toward the next, showing travel direction.
            val step = (maxCell / 4).coerceAtLeast(1)
            var cell = step
            while (cell < maxCell) {
                val a = r.conveyorCellPosition(name, cell)
                val b = r.conveyorCellPosition(name, cell + 1)
                if (a != null && b != null) drawDirectionArrow(g2, screen(tx, a), screen(tx, b))
                cell += step
            }
        }
    }

    /** Draws a small arrowhead at [from] pointing toward [to] (conveyor travel direction, 10.5c). */
    private fun drawDirectionArrow(g2: Graphics2D, from: Point2D, to: Point2D) {
        val ang = kotlin.math.atan2(to.y - from.y, to.x - from.x)
        val len = 7.0
        val wing = Math.toRadians(150.0)
        val p = Path2D.Double()
        p.moveTo(from.x, from.y)
        p.lineTo(from.x + len * kotlin.math.cos(ang + wing), from.y + len * kotlin.math.sin(ang + wing))
        p.moveTo(from.x, from.y)
        p.lineTo(from.x + len * kotlin.math.cos(ang - wing), from.y + len * kotlin.math.sin(ang - wing))
        g2.draw(p)
    }

    private fun drawMarker(g2: Graphics2D, tx: AffineTransform, p: LayoutPoint, color: Color, label: String) {
        val s = screen(tx, p)
        g2.color = color
        g2.fill(Ellipse2D.Double(s.x - 4, s.y - 4, 8.0, 8.0))
        g2.color = Color.DARK_GRAY
        g2.drawString(label, (s.x + 6).toFloat(), s.y.toFloat())
    }

    /**
     * Draws an element's text label honoring the layout's per-element overrides (10.8/C3): an override can
     * retitle it, offset it from [anchor] (screen px), or hide it; absent an override, [defaultText] is drawn
     * just above the glyph. No text ⇒ nothing drawn.
     */
    private fun drawElementLabel(
        g2: Graphics2D, layout: AnimationLayout?, anchor: Point2D, kind: ksl.animation.ElementKind, name: String,
        defaultText: String?, value: String? = null
    ) {
        val lbl = layout?.labels?.firstOrNull { it.kind == kind && it.name == name }
        g2.color = Color.DARK_GRAY
        // Name label (independently shown/positioned).
        if (lbl?.visible != false) (lbl?.text ?: defaultText)?.takeIf { it.isNotBlank() }?.let {
            g2.drawString(it, (anchor.x + (lbl?.dx ?: 0.0)).toFloat(), (anchor.y + (lbl?.dy ?: -12.0)).toFloat())
        }
        // Live value/state annotation (e.g. a queue's count) — its own visibility + offset (batch 4).
        if (lbl?.valueVisible != false) value?.takeIf { it.isNotBlank() }?.let {
            g2.drawString(it, (anchor.x + (lbl?.valueDx ?: 0.0)).toFloat(), (anchor.y + (lbl?.valueDy ?: 14.0)).toFloat())
        }
    }

    private fun drawPolyline(g2: Graphics2D, tx: AffineTransform, pts: List<LayoutPoint>, color: Color, stroke: Float) {
        if (pts.size < 2) return
        g2.color = color
        g2.stroke = BasicStroke(stroke)
        for (i in 0 until pts.size - 1) {
            val a = screen(tx, pts[i]); val b = screen(tx, pts[i + 1])
            g2.drawLine(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt())
        }
    }

    private fun drawBackground(g2: Graphics2D, tx: AffineTransform, b: BackgroundElement) {
        val color = VisualStyle.parseColor(b.color)
        when (b.kind) {
            BackgroundKind.LINE, BackgroundKind.POLYLINE -> drawPolyline(g2, tx, b.points, color, b.strokeWidth.toFloat())
            BackgroundKind.RECT -> if (b.points.size >= 2) {
                val a = screen(tx, b.points[0]); val c = screen(tx, b.points[1])
                g2.color = color; g2.stroke = BasicStroke(b.strokeWidth.toFloat())
                g2.draw(Rectangle2D.Double(minOf(a.x, c.x), minOf(a.y, c.y), kotlin.math.abs(c.x - a.x), kotlin.math.abs(c.y - a.y)))
            }
            BackgroundKind.TEXT -> if (b.points.isNotEmpty() && b.text != null) {
                val s = screen(tx, b.points[0]); g2.color = color
                val baseFont = g2.font
                val family = b.fontFamily ?: baseFont.family
                val px = (b.fontSize * scaleOf(tx)).toFloat().coerceAtLeast(4f) // size in layout units, scales with zoom
                g2.font = java.awt.Font(family, java.awt.Font.PLAIN, 12).deriveFont(px)
                g2.drawString(b.text, s.x.toFloat(), s.y.toFloat())
                g2.font = baseFont
            }
            BackgroundKind.IMAGE -> if (b.points.size >= 2 && b.imageRef != null) {
                val img = loadImage(b.imageRef!!)
                if (img != null) {
                    val a = screen(tx, b.points[0]); val c = screen(tx, b.points[1])
                    val x = minOf(a.x, c.x); val y = minOf(a.y, c.y)
                    val w = kotlin.math.abs(c.x - a.x); val h = kotlin.math.abs(c.y - a.y)
                    g2.drawImage(img, x.toInt(), y.toInt(), w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1), null)
                }
            }
        }
    }

    /**
     * Loads (and caches) an image for a background [ref]. Absolute paths are used as-is; relative
     * paths resolve against the replay's [ReplayModel.baseDir] (the layout file's directory), then
     * the working directory. Returns null if it can't be read, so a missing image is silently skipped.
     */
    private fun loadImage(ref: String): java.awt.image.BufferedImage? = imageCache.getOrPut(ref) {
        runCatching {
            val direct = java.io.File(ref)
            val file = when {
                direct.isAbsolute -> direct
                replay?.baseDir != null -> replay!!.baseDir!!.resolve(ref).toFile()
                else -> direct
            }
            if (file.exists()) javax.imageio.ImageIO.read(file) else null
        }.getOrNull()
    }

    /**
     * Draws a grid obstacle overlay (filled blocked cells) for [spec] (P5a/G2). The cell→world transform is
     * resolved (§5 of the P5 design): a Grid space uses its display cell size/origin; an explicit physical
     * cellSize on the spec wins next; otherwise a Continuous space's cell size is derived from its bounds.
     */
    private fun drawObstacles(g2: Graphics2D, tx: AffineTransform, spec: ksl.modeling.agent.GridGeometrySpec, r: ReplayModel) {
        if (spec.blockedCells.isEmpty()) return
        // Match by name; else fall back to the sole space, else the first grid (the projection a model exports an
        // overlay for is often named differently from the authored space it's drawn on — e.g. "grid" vs "floor").
        val space = r.effectiveSpaces.firstOrNull { it.name == spec.spaceName }
            ?: r.effectiveSpaces.singleOrNull()
            ?: r.effectiveSpaces.firstOrNull { it is SpatialSpaceDescriptor.Grid }
        val ox: Double; val oy: Double; val cell: Double
        when {
            space is SpatialSpaceDescriptor.Grid -> { ox = space.originX; oy = space.originY; cell = space.cellSize }
            spec.cellSize != null -> { ox = spec.originX ?: 0.0; oy = spec.originY ?: 0.0; cell = spec.cellSize!! }
            space is SpatialSpaceDescriptor.Continuous -> { ox = space.xMin; oy = space.yMin; cell = (space.xMax - space.xMin) / spec.cols.coerceAtLeast(1) }
            else -> { ox = spec.originX ?: 0.0; oy = spec.originY ?: 0.0; cell = spec.cellSize ?: 1.0 }
        }
        g2.color = Color(0x44, 0x44, 0x44, 0x99) // semi-opaque dark, drawn behind the agents
        for (bc in spec.blockedCells) {
            val a = screen(tx, LayoutPoint(ox + bc.col * cell, oy + bc.row * cell))
            val b = screen(tx, LayoutPoint(ox + (bc.col + 1) * cell, oy + (bc.row + 1) * cell))
            g2.fill(Rectangle2D.Double(minOf(a.x, b.x), minOf(a.y, b.y), kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
        }
    }

    /**
     * Draws a flow-field gradient heatmap (G11): each reachable cell is shaded green (near the goal) → red
     * (far), semi-transparent so agents draw on top. The teaching value is seeing the gradient the agents
     * descend. The cell→world transform comes from the snapshot's own cellSize/origin.
     */
    private fun drawFlowField(g2: Graphics2D, tx: AffineTransform, ff: ksl.animation.AnimationEvent.FlowFieldDefined) {
        if (ff.cells.isEmpty() || ff.maxDistance <= 0.0) return
        for (c in ff.cells) {
            val f = (c.distance / ff.maxDistance).coerceIn(0.0, 1.0) // 0 = at goal, 1 = farthest
            val rr = (0x2c + f * (0xd6 - 0x2c)).toInt()
            val gg = (0xa0 + f * (0x27 - 0xa0)).toInt()
            val bb = (0x2c + f * (0x28 - 0x2c)).toInt()
            g2.color = Color(rr.coerceIn(0, 255), gg.coerceIn(0, 255), bb.coerceIn(0, 255), 0x66)
            val a = screen(tx, LayoutPoint(ff.originX + c.col * ff.cellSize, ff.originY + c.row * ff.cellSize))
            val b = screen(tx, LayoutPoint(ff.originX + (c.col + 1) * ff.cellSize, ff.originY + (c.row + 1) * ff.cellSize))
            g2.fill(Rectangle2D.Double(minOf(a.x, b.x), minOf(a.y, b.y), kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
        }
    }

    /**
     * Draws per-agent velocity (blue) and net-force (orange) arrows (G10), anchored at each agent's glyph.
     * Arrow length is proportional to magnitude (in world units) and clamped, so arrows stay legible.
     */
    private fun drawVectors(g2: Graphics2D, tx: AffineTransform, r: ReplayModel, t: Double, gridOff: Double) {
        for (name in r.agentsWithVectors) {
            if (!r.agentPresentAt(name, t)) continue
            val vec = r.agentVectorAt(name, t) ?: continue
            val p = r.agentPositionAt(name, t) ?: continue
            val drawn = (r.torusBounds?.wrap(WorldPoint(p.x, p.y, p.z)) ?: p).let { WorldPoint(it.x + gridOff, it.y + gridOff, it.z) }
            if (vec.vx.isFinite() && vec.vy.isFinite()) drawArrow(g2, tx, drawn, vec.vx, vec.vy, Color(0x15, 0x6e, 0xc8))
            if (vec.fx.isFinite() && vec.fy.isFinite()) drawArrow(g2, tx, drawn, vec.fx, vec.fy, Color(0xff, 0x7f, 0x0e))
        }
    }

    private fun drawArrow(g2: Graphics2D, tx: AffineTransform, from: WorldPoint, dx: Double, dy: Double, color: Color) {
        val mag = kotlin.math.hypot(dx, dy)
        if (mag < 1e-9) return
        val len = (mag * 1.0).coerceAtMost(8.0) // proportional, clamped to 8 world units
        val a = screen(tx, LayoutPoint(from.x, from.y))
        val b = screen(tx, LayoutPoint(from.x + dx / mag * len, from.y + dy / mag * len))
        g2.color = color; g2.stroke = BasicStroke(2f)
        g2.draw(Line2D.Double(a.x, a.y, b.x, b.y))
        val ang = kotlin.math.atan2(b.y - a.y, b.x - a.x); val hl = 6.0
        g2.draw(Line2D.Double(b.x, b.y, b.x - hl * kotlin.math.cos(ang - 0.4), b.y - hl * kotlin.math.sin(ang - 0.4)))
        g2.draw(Line2D.Double(b.x, b.y, b.x - hl * kotlin.math.cos(ang + 0.4), b.y - hl * kotlin.math.sin(ang + 0.4)))
    }

    /** Draws each agent's currently-planned route as a faded polyline (G12), under the agent glyphs. */
    private fun drawPlannedPaths(g2: Graphics2D, tx: AffineTransform, r: ReplayModel, t: Double) {
        if (r.agentsWithPaths.isEmpty()) return
        g2.color = Color(0x15, 0x6e, 0xc8, 0x99) // translucent blue route
        for (name in r.agentsWithPaths) {
            val pts = r.plannedPathAt(name, t) ?: continue
            if (pts.size >= 2) drawPolyline(g2, tx, pts.map { LayoutPoint(it.x, it.y) }, Color(0x15, 0x6e, 0xc8, 0x99), 2.0f)
        }
    }

    /**
     * Draws each live marker pulse (G-animated) as an expanding ring that fades out over its window: at a
     * pulse's `progress` (0→1) the ring grows from a small radius and its alpha drops to zero, giving a brief
     * "ping" at the location an event happened (e.g. a completed delivery).
     */
    private fun drawMarkerPulses(g2: Graphics2D, tx: AffineTransform, r: ReplayModel, t: Double) {
        val pulses = r.markerPulsesActiveAt(t)
        if (pulses.isEmpty()) return
        for (p in pulses) {
            val c = p.colorHex?.let { VisualStyle.parseColor(it) } ?: Color(0xff, 0x7f, 0x0e)
            val alpha = ((1.0 - p.progress) * 220).toInt().coerceIn(0, 255)
            val center = screen(tx, LayoutPoint(p.x, p.y))
            val radius = 6.0 + p.progress * 22.0 // pixels — grows as it fades
            g2.color = Color(c.red, c.green, c.blue, alpha)
            g2.stroke = BasicStroke(2.5f)
            g2.draw(Ellipse2D.Double(center.x - radius, center.y - radius, radius * 2, radius * 2))
            if (p.label != null && p.progress < 0.6) {
                g2.color = Color(c.red, c.green, c.blue, alpha)
                g2.drawString(p.label, (center.x + radius + 2).toFloat(), (center.y - radius).toFloat())
            }
        }
    }

    private fun drawSpace(g2: Graphics2D, tx: AffineTransform, space: SpatialSpaceDescriptor) {
        g2.color = Color(0xee, 0xee, 0xee)
        g2.stroke = BasicStroke(1.0f)
        when (space) {
            is SpatialSpaceDescriptor.Continuous -> {
                val a = screen(tx, LayoutPoint(space.xMin, space.yMin)); val b = screen(tx, LayoutPoint(space.xMax, space.yMax))
                val rect = Rectangle2D.Double(minOf(a.x, b.x), minOf(a.y, b.y), kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
                // A faint translucent fill + a slightly stronger border so a room / airspace reads as a region
                // rather than a single hairline outline (C2).
                g2.color = Color(0x42, 0x85, 0xf4, 0x14)
                g2.fill(rect)
                g2.color = Color(0xaa, 0xaa, 0xaa)
                g2.stroke = BasicStroke(1.5f)
                g2.draw(rect)
            }
            is SpatialSpaceDescriptor.Grid -> {
                for (c in 0..space.cols) {
                    val a = screen(tx, LayoutPoint(space.originX + c * space.cellSize, space.originY))
                    val b = screen(tx, LayoutPoint(space.originX + c * space.cellSize, space.originY + space.rows * space.cellSize))
                    g2.drawLine(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt())
                }
                for (rr in 0..space.rows) {
                    val a = screen(tx, LayoutPoint(space.originX, space.originY + rr * space.cellSize))
                    val b = screen(tx, LayoutPoint(space.originX + space.cols * space.cellSize, space.originY + rr * space.cellSize))
                    g2.drawLine(a.x.toInt(), a.y.toInt(), b.x.toInt(), b.y.toInt())
                }
            }
            is SpatialSpaceDescriptor.Network -> {
                val byId = space.nodes.associateBy { it.id }
                for (e in space.edges) {
                    val from = byId[e.from]?.position; val to = byId[e.to]?.position
                    if (from != null && to != null) drawPolyline(g2, tx, listOf(from, to), Color(0xcc, 0xcc, 0xcc), 1.0f)
                }
                // Faint node anchors without id labels: agents emitted at these slots draw their state colors
                // on top (G7); for a hand-authored network the dots + edges still convey the graph.
                space.nodes.forEach { drawMarker(g2, tx, it.position, Color(0xcc, 0xcc, 0xcc), "") }
            }
        }
    }

    private fun installMouseControls() {
        val ml = object : java.awt.event.MouseAdapter() {
            private var lastX = 0; private var lastY = 0
            override fun mousePressed(e: java.awt.event.MouseEvent) { lastX = e.x; lastY = e.y }
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                if (!panEnabled) return // edit mode owns drags (e.g. moving layout elements)
                panX += (e.x - lastX); panY += (e.y - lastY); lastX = e.x; lastY = e.y; repaint()
            }
            override fun mouseWheelMoved(e: java.awt.event.MouseWheelEvent) {
                zoomAt(if (e.wheelRotation < 0) 1.1 else 1.0 / 1.1, e.x.toDouble(), e.y.toDouble())
            }
        }
        addMouseListener(ml); addMouseMotionListener(ml); addMouseWheelListener(ml)
    }

    /**
     * Multiplies the zoom by [factor], clamped, keeping the world point under (sx, sy) fixed on screen
     * (cursor-anchored zoom), then repaints. The anchoring makes wheel/button zoom feel natural.
     */
    private fun zoomAt(factor: Double, sx: Double, sy: Double) {
        val before = screenToWorld(sx, sy)
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val after = screenToWorld(sx, sy)
        val s = worldTransform().scaleX
        panX += s * (after.x - before.x)
        panY += s * (after.y - before.y)
        repaint()
    }

    /** Zoom in/out about the canvas centre (for toolbar buttons). */
    fun zoomIn() = zoomAt(1.25, width / 2.0, height / 2.0)
    fun zoomOut() = zoomAt(1.0 / 1.25, width / 2.0, height / 2.0)

    /** Reset zoom and pan so the content is framed to fit (the default view). */
    fun resetView() { zoom = 1.0; panX = 0.0; panY = 0.0; repaint() }

    /** Current zoom factor (1.0 = fit-to-view). */
    val zoomLevel: Double get() = zoom

    /**
     * World positions at which to draw small "grab handles" (layout editing) so every placed element —
     * even an empty queue that otherwise draws nothing — is visible and draggable. Empty = no handles.
     */
    var editHandles: List<Point2D> = emptyList()
        set(value) { field = value; repaint() }

    /** World points of the currently selected elements, each drawn as a ring (B-polish / P4 multi-select). */
    var selectionHandles: List<Point2D> = emptyList()
        set(value) { field = value; repaint() }

    /** The live rubber-band selection rectangle in **screen** coordinates, or null when not marqueeing (P4). */
    var marqueeScreen: Rectangle2D? = null
        set(value) { field = value; repaint() }

    /** The selected element's draggable name/value text grips, in **screen** coordinates (batch-4 polish). */
    var labelGrips: List<Point2D> = emptyList()
        set(value) { field = value; repaint() }

    /** Queue rotation grips (at each queue's tail); drag one to rotate that queue's orientation (P3). */
    var queueRotateGrips: List<Point2D> = emptyList()
        set(value) { field = value; repaint() }

    /** A world-space rectangle drawn as a highlight outline — the selected storage's footprint (G6). */
    var highlightRectWorld: Rectangle2D? = null
        set(value) { field = value; repaint() }

    /** A world-space rectangle outlining the selected background shape (rect/line/text/image). */
    var shapeHighlightWorld: Rectangle2D? = null
        set(value) { field = value; repaint() }

    /** Resize grip(s) for the selected background shape (far corner of a rect/image); drag to set size. */
    var shapeResizeGrips: List<Point2D> = emptyList()
        set(value) { field = value; repaint() }

    /** A world-space rectangle outlining the selected clock display. */
    var clockHighlightWorld: Rectangle2D? = null
        set(value) { field = value; repaint() }

    /** Resize grip(s) for the selected storage (far corner); drag to set width/height (item 2). */
    var storageResizeGrips: List<Point2D> = emptyList()
        set(value) { field = value; repaint() }

    private fun drawEditHandles(g2: Graphics2D, tx: AffineTransform) {
        for (w in editHandles) {
            val p = tx.transform(w, null)
            g2.color = Color(0x15, 0x6e, 0xc8)
            g2.draw(Rectangle2D.Double(p.x - 4, p.y - 4, 8.0, 8.0))
        }
        for (w in storageResizeGrips) { // a small filled square at the storage's far corner — drag to resize
            val p = tx.transform(w, null)
            g2.color = Color(0xff, 0x7f, 0x0e)
            g2.fill(Rectangle2D.Double(p.x - 4, p.y - 4, 8.0, 8.0))
        }
        for (w in shapeResizeGrips) { // a small filled square at a shape's far corner — drag to resize (image/rect)
            val p = tx.transform(w, null)
            g2.color = Color(0x15, 0x6e, 0xc8)
            g2.fill(Rectangle2D.Double(p.x - 4, p.y - 4, 8.0, 8.0))
        }
        for (w in queueRotateGrips) { // a small circle at the queue tail the user drags to rotate the queue
            val p = tx.transform(w, null)
            g2.color = Color(0x15, 0x6e, 0xc8)
            g2.draw(Ellipse2D.Double(p.x - 5, p.y - 5, 10.0, 10.0))
        }
        for (w in selectionHandles) { // highlight ring around each selected element
            val p = tx.transform(w, null)
            g2.color = Color(0xff, 0x7f, 0x0e)
            g2.draw(Ellipse2D.Double(p.x - 9, p.y - 9, 18.0, 18.0))
        }
        highlightRectWorld?.let { wr -> // selected storage footprint (G6): an outline around the whole box
            val a = tx.transform(Point2D.Double(wr.minX, wr.minY), null)
            val b = tx.transform(Point2D.Double(wr.maxX, wr.maxY), null)
            g2.color = Color(0xff, 0x7f, 0x0e)
            g2.draw(Rectangle2D.Double(minOf(a.x, b.x), minOf(a.y, b.y), kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
        }
        shapeHighlightWorld?.let { wr -> // selected background shape: a dashed blue outline around its bounds
            val a = tx.transform(Point2D.Double(wr.minX, wr.minY), null)
            val b = tx.transform(Point2D.Double(wr.maxX, wr.maxY), null)
            g2.color = Color(0x15, 0x6e, 0xc8)
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(5f, 4f), 0f)
            g2.draw(Rectangle2D.Double(minOf(a.x, b.x), minOf(a.y, b.y), kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
            g2.stroke = BasicStroke(1f)
        }
        clockHighlightWorld?.let { wr -> // selected clock: a dashed blue outline around its bounds
            val a = tx.transform(Point2D.Double(wr.minX, wr.minY), null)
            val b = tx.transform(Point2D.Double(wr.maxX, wr.maxY), null)
            g2.color = Color(0x15, 0x6e, 0xc8)
            g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(5f, 4f), 0f)
            g2.draw(Rectangle2D.Double(minOf(a.x, b.x), minOf(a.y, b.y), kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y)))
            g2.stroke = BasicStroke(1f)
        }
        marqueeScreen?.let { // rubber-band selection box (screen space)
            g2.color = Color(0xff, 0x7f, 0x0e)
            g2.draw(it)
        }
        for (p in labelGrips) { // small handles to drag the selected element's name/value text (screen space)
            g2.color = Color(0xff, 0x7f, 0x0e)
            g2.fill(Rectangle2D.Double(p.x - 3, p.y - 3, 6.0, 6.0))
        }
    }

    /**
     * Whether built-in click-drag panning is active. The layout editor sets this false so a drag moves the
     * element under the cursor instead of panning the view (wheel-zoom stays available).
     */
    var panEnabled: Boolean = true

    /** Inverts [worldTransform] to map a screen point back to world (layout) coordinates. */
    fun screenToWorld(screenX: Double, screenY: Double): Point2D =
        runCatching { worldTransform().inverseTransform(Point2D.Double(screenX, screenY), null) }
            .getOrDefault(Point2D.Double(screenX, screenY))

    /** Draws a light coordinate grid with axis value labels — a spatial reference for layout authoring. */
    /** Whether to draw the flow-field gradient heatmap when the trace carries one (G11). Display gate. */
    var showFlowField: Boolean = true
        set(value) { field = value; repaint() }

    /** Whether to draw agents' planned routes when the trace carries them (G12). Display gate. */
    var showPlannedPaths: Boolean = true
        set(value) { field = value; repaint() }

    /** Whether to draw agents' velocity/force vector arrows when the trace carries them (G10). Display gate. */
    var showVectors: Boolean = true
        set(value) { field = value; repaint() }

    /** Whether to draw transient marker pulses when the trace carries them (G-animated). Display gate. */
    var showMarkerPulses: Boolean = true
        set(value) { field = value; repaint() }

    var showGrid: Boolean = false
        set(value) { field = value; repaint() }

    private fun drawGrid(g2: Graphics2D, tx: AffineTransform) {
        val b = worldBounds()
        val step = niceStep(maxOf(b.width, b.height) / 10.0)
        if (step <= 0.0) return
        val lineColor = Color(0xE6, 0xE6, 0xE6)
        val labelColor = Color(0x99, 0x99, 0x99)
        var x = Math.floor(b.x / step) * step
        while (x <= b.x + b.width) {
            val p0 = tx.transform(Point2D.Double(x, b.y), null); val p1 = tx.transform(Point2D.Double(x, b.y + b.height), null)
            g2.color = lineColor; g2.draw(Line2D.Double(p0.x, p0.y, p1.x, p1.y))
            g2.color = labelColor; g2.drawString(trimGrid(x), p0.x.toFloat() + 2f, (height - 4).toFloat())
            x += step
        }
        var y = Math.floor(b.y / step) * step
        while (y <= b.y + b.height) {
            val p0 = tx.transform(Point2D.Double(b.x, y), null); val p1 = tx.transform(Point2D.Double(b.x + b.width, y), null)
            g2.color = lineColor; g2.draw(Line2D.Double(p0.x, p0.y, p1.x, p1.y))
            g2.color = labelColor; g2.drawString(trimGrid(y), 2f, p0.y.toFloat() - 2f)
            y += step
        }
    }

    /** A tidy 1/2/5 × 10ⁿ step at or below [raw], so gridlines land on round coordinate values. */
    private fun niceStep(raw: Double): Double {
        if (raw <= 0.0 || raw.isNaN()) return 0.0
        val mag = Math.pow(10.0, Math.floor(Math.log10(raw)))
        val norm = raw / mag
        return when { norm >= 5.0 -> 5.0; norm >= 2.0 -> 2.0; else -> 1.0 } * mag
    }

    private fun trimGrid(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else "%.1f".format(v)

    companion object {
        private const val MIN_ZOOM = 0.1
        private const val MAX_ZOOM = 20.0

        /**
         * Offset for the [index]-th of [count] agents sharing a spot, placed on a ring of [radius] so
         * none is fully hidden (8I.3b). A single agent (count <= 1) is not moved.
         */
        fun fanRingOffset(index: Int, count: Int, radius: Double): Pair<Double, Double> {
            if (count <= 1) return 0.0 to 0.0
            val ang = 2.0 * Math.PI * index / count
            return radius * kotlin.math.cos(ang) to radius * kotlin.math.sin(ang)
        }
    }
}
