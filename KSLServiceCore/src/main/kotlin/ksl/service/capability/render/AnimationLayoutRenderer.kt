/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.service.capability.render

import ksl.animation.AnchorRef
import ksl.animation.AnimationLayout
import ksl.animation.ConveyorLayoutElement
import ksl.animation.ElementKind
import ksl.animation.LayoutPoint
import ksl.animation.LayoutShape
import ksl.animation.MovableResourceLayoutElement
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renders an [AnimationLayout] to a **static preview** image that reproduces the desktop animation app's
 * *static editor canvas* — the paused, pre-replay arrangement the app draws from the layout alone (the
 * persistent "skeleton" every element has, minus any live/replay overlay). It is the propose → render →
 * look → revise loop's "look" step, and a way to polish an app-authored layout, without running a replay.
 *
 * A fresh, headless `Graphics2D` renderer: it depends only on the layout data (KSLCore) and `java.desktop`
 * (a JDK built-in that renders fine headless), so it adds no dependency on the Swing animation module. It
 * deliberately mirrors `SimulationCanvas`'s static drawing (same coordinate transform, colors, glyph
 * conventions) so the preview matches the app; live entity motion, interactivity, editor grips, spaces,
 * backgrounds and images are out of scope here (the last three arrive in later phases).
 */
object AnimationLayoutRenderer {

    private const val MARGIN = 20.0

    // Neutral chrome — fixed grays matching SimulationCanvas (no theme / dark mode; only element fills follow
    // authored colors).
    private val MARKER = Color(0x55, 0x55, 0x55)            // network-station dot + spatial-location square
    private val QUEUE_LINE = Color(0x88, 0x88, 0x88)
    private val QUEUE_HEAD = Color(0x33, 0x66, 0xcc)
    private val PATH = Color(0xb0, 0xb0, 0xb0)
    private val STORAGE_FILL = Color(0x42, 0x85, 0xf4, 0x12)
    private val STORAGE_BORDER = Color(0xbb, 0xbb, 0xbb)
    private val CONVEYOR_FALLBACK = Color(0x88, 0x88, 0x88)

    /** Renders [layout] to a [BufferedImage] sized to the layout's own canvas (clamped to a sane range). */
    fun renderToImage(layout: AnimationLayout): BufferedImage {
        val w = layout.width.toInt().coerceIn(200, 4000)
        val h = layout.height.toInt().coerceIn(200, 4000)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)

        val tx = Transform.fit(worldBounds(layout), w, h)

        // Back-to-front, matching SimulationCanvas's static skeleton order.
        drawPaths(g, tx, layout)
        drawConveyors(g, tx, layout)
        drawStations(g, tx, layout)
        drawLocations(g, tx, layout)
        drawQueues(g, tx, layout)
        drawStorages(g, tx, layout)
        drawResources(g, tx, layout)
        drawMovables(g, tx, layout)
        drawDisplays(g, tx, layout)

        drawLegend(g, layout, w)
        drawTitle(g, layout)

        g.dispose()
        return img
    }

    /** Renders [layout] to a PNG at [path]. */
    fun renderToPng(layout: AnimationLayout, path: Path) {
        ImageIO.write(renderToImage(layout), "png", path.toFile())
    }

    // ── world → screen transform (fit-to-view of the world box + a 20px margin; no Y-flip) ──────────────

    private class Transform(private val minX: Double, private val minY: Double, val scale: Double) {
        fun x(wx: Double): Double = MARGIN + (wx - minX) * scale
        fun y(wy: Double): Double = MARGIN + (wy - minY) * scale
        fun p(pt: LayoutPoint): Point2D.Double = Point2D.Double(x(pt.x), y(pt.y))
        fun len(v: Double): Double = v * scale

        companion object {
            fun fit(b: Rectangle2D.Double, w: Int, h: Int): Transform {
                val sx = (w - 2 * MARGIN) / b.width.coerceAtLeast(1e-6)
                val sy = (h - 2 * MARGIN) / b.height.coerceAtLeast(1e-6)
                val s = minOf(sx, sy).coerceAtLeast(1e-6)
                return Transform(b.x, b.y, s)
            }
        }
    }

    /**
     * The world bounding box: the layout rect (0,0,width,height) grown to include every placed element
     * (and its extent), so out-of-range or coordinate-free layouts aren't clipped — the static analogue of
     * the app unioning the layout rect with the replay's coordinate bounds.
     */
    private fun worldBounds(layout: AnimationLayout): Rectangle2D.Double {
        val box = Rectangle2D.Double(0.0, 0.0, layout.width.coerceAtLeast(1.0), layout.height.coerceAtLeast(1.0))
        fun add(p: LayoutPoint?) { if (p != null) box.add(p.x, p.y) }
        fun addXY(x: Double, y: Double) = box.add(x, y)
        layout.resources.forEach { add(it.position) }
        layout.queues.forEach { q ->
            add(q.position)
            val rad = Math.toRadians(q.growthDegrees)
            val len = q.spacing * q.maxShown.coerceAtLeast(1)
            addXY(q.position.x + len * cos(rad), q.position.y + len * sin(rad))
        }
        layout.storages.forEach { addXY(it.position.x, it.position.y); addXY(it.position.x + it.width, it.position.y + it.height) }
        layout.stations.forEach { add(it.position) }
        layout.locations.forEach { add(it.position) }
        layout.movableResources.forEach { add(moverAtRest(layout, it)) }
        layout.paths.forEach { p -> layout.pathPolyline(p).forEach { add(it) } }
        layout.conveyors.forEach { c -> conveyorRoutes(layout, c).forEach { seg -> seg.forEach { add(it) } } }
        layout.bars.forEach { addXY(it.position.x, it.position.y); addXY(it.position.x + it.width, it.position.y + it.height) }
        layout.plots.forEach { addXY(it.position.x, it.position.y); addXY(it.position.x + it.width, it.position.y + it.height) }
        layout.histograms.forEach { addXY(it.position.x, it.position.y); addXY(it.position.x + it.width, it.position.y + it.height) }
        layout.values.forEach { add(it.position) }
        layout.summaries.forEach { add(it.position) }
        layout.clocks.forEach { add(it.position) }
        return box
    }

    // ── elements ────────────────────────────────────────────────────────────────────────────────────────

    private fun drawPaths(g: Graphics2D, tx: Transform, layout: AnimationLayout) {
        for (path in layout.paths) drawPolyline(g, layout.pathPolyline(path), tx, PATH, 1.0f)
    }

    /**
     * Draws each authored conveyor belt as a thick polyline through its segments' resolved anchors + waypoints,
     * with travel-direction arrows when `showDirection`. (The app draws conveyor cells from the replay; at rest
     * the authored route is the faithful source.)
     */
    private fun drawConveyors(g: Graphics2D, tx: Transform, layout: AnimationLayout) {
        for (c in layout.conveyors) {
            val color = parseColor(c.color)
            val beltPx = tx.len(c.width).coerceIn(2.0, 14.0).toFloat()
            for (route in conveyorRoutes(layout, c)) {
                drawPolyline(g, route, tx, color, beltPx)
                if (c.showDirection) {
                    for (i in 0 until route.size - 1) directionArrow(g, tx.p(route[i]), tx.p(route[i + 1]), color)
                }
            }
            val mid = conveyorRoutes(layout, c).firstOrNull()?.let { it[it.size / 2] }
            if (mid != null && c.label != null) {
                g.color = Color.DARK_GRAY
                val s = tx.p(mid)
                g.drawString(c.label!!, (s.x + 4).toFloat(), (s.y - 4).toFloat())
            }
        }
    }

    private fun drawStations(g: Graphics2D, tx: Transform, layout: AnimationLayout) {
        for (st in layout.stations) {
            val c = tx.p(st.position)
            g.color = MARKER
            g.fill(Ellipse2D.Double(c.x - 4, c.y - 4, 8.0, 8.0))
            label(g, layout, c, ElementKind.STATION, st.stationName, st.label ?: st.stationName, null)
        }
    }

    private fun drawLocations(g: Graphics2D, tx: Transform, layout: AnimationLayout) {
        for (loc in layout.locations) {
            val p = loc.position ?: continue // unplaced (no MDS yet) → nothing to draw
            val c = tx.p(p)
            g.color = MARKER
            g.stroke = BasicStroke(1.5f)
            g.draw(Rectangle2D.Double(c.x - 5, c.y - 5, 10.0, 10.0)) // open square: distinct from a station's filled dot
            label(g, layout, c, ElementKind.LOCATION, loc.locationName, loc.label ?: loc.locationName, null)
        }
    }

    private fun drawQueues(g: Graphics2D, tx: Transform, layout: AnimationLayout) {
        for (q in layout.queues) {
            val base = tx.p(q.position) // the head (front of the line)
            val rad = Math.toRadians(q.growthDegrees)
            val dx = cos(rad); val dy = sin(rad)
            val lineLen = tx.len(q.spacing) * q.maxShown.coerceAtLeast(1)
            g.color = QUEUE_LINE
            g.stroke = BasicStroke(1f)
            g.draw(Line2D.Double(base.x, base.y, base.x + lineLen * dx, base.y + lineLen * dy)) // the extent "____"
            val bar = tx.len(12.0).coerceAtLeast(7.0)
            val px = -dy; val py = dx // unit perpendicular → the head bar "|"
            g.color = QUEUE_HEAD
            g.stroke = BasicStroke(1.5f)
            g.draw(Line2D.Double(base.x - px * bar / 2, base.y - py * bar / 2, base.x + px * bar / 2, base.y + py * bar / 2))
            label(g, layout, base, ElementKind.QUEUE, q.queueName, q.queueName, "(0)")
        }
    }

    private fun drawStorages(g: Graphics2D, tx: Transform, layout: AnimationLayout) {
        for (st in layout.storages) {
            val a = tx.p(st.position) // top-left of the footprint
            val bw = tx.len(st.width).coerceAtLeast(12.0)
            val bh = tx.len(st.height).coerceAtLeast(10.0)
            g.color = STORAGE_FILL
            g.fill(Rectangle2D.Double(a.x, a.y, bw, bh))
            g.color = STORAGE_BORDER
            g.stroke = BasicStroke(1f)
            g.draw(Rectangle2D.Double(a.x, a.y, bw, bh))
            g.color = Color.DARK_GRAY
            g.drawString("${st.label ?: st.suspensionName} (0)", a.x.toFloat(), (a.y - 6.0).toFloat())
        }
    }

    private fun drawResources(g: Graphics2D, tx: Transform, layout: AnimationLayout) {
        for (res in layout.resources) {
            val c = tx.p(res.position) // center
            val d = tx.len(res.size).coerceAtLeast(6.0)
            val box = Rectangle2D.Double(c.x - d / 2, c.y - d / 2, d, d)
            g.color = parseColor(res.idleColor) // at rest = idle
            g.fill(box)
            g.color = Color.BLACK
            g.stroke = BasicStroke(1f)
            g.draw(box)
            val value = if (res.showValue) "0/1" else null // busy/capacity at rest
            label(g, layout, c, ElementKind.RESOURCE, res.resourceName, res.resourceName, value)
        }
    }

    private fun drawMovables(g: Graphics2D, tx: Transform, layout: AnimationLayout) {
        // Resolve each mover's at-rest position, then fan co-located movers onto a small ring so a pool homed at
        // one station doesn't stack into a single glyph (mirrors the app's fan-out).
        val placed = layout.movableResources.mapNotNull { mr -> moverAtRest(layout, mr)?.let { mr to it } }
        placed.groupBy { Math.round(it.second.x) to Math.round(it.second.y) }.forEach { (_, group) ->
            group.forEachIndexed { j, (mr, pos) ->
                val (ox, oy) = fanRingOffset(j, group.size, mr.size * 0.7)
                val c = tx.p(LayoutPoint(pos.x + ox, pos.y + oy))
                val d = tx.len(mr.size).coerceAtLeast(3.0)
                fillGlyph(g, c.x, c.y, d, parseColor(mr.color), mr.shape) // idle color/shape (image → P3)
                label(g, layout, c, ElementKind.MOVABLE_RESOURCE, mr.name, mr.label ?: mr.name, null)
            }
        }
    }

    /** Displays: bars / plots / histograms as sized framed boxes; values / summaries / clocks as text (empty at rest). */
    private fun drawDisplays(g: Graphics2D, tx: Transform, layout: AnimationLayout) {
        for (bar in layout.bars) framedBox(g, tx.p(bar.position), tx.len(bar.width), tx.len(bar.height), bar.label ?: bar.responseName)
        for (plot in layout.plots) framedBox(g, tx.p(plot.position), tx.len(plot.width), tx.len(plot.height), plot.label ?: plot.responseName)
        for (h in layout.histograms) framedBox(g, tx.p(h.position), tx.len(h.width), tx.len(h.height), h.label ?: h.responseName)
        for (v in layout.values) {
            val s = tx.p(v.position)
            g.color = Color.BLACK
            g.drawString("${v.label ?: v.responseName}: —", s.x.toFloat(), s.y.toFloat())
        }
        for (sum in layout.summaries) {
            val s = tx.p(sum.position)
            g.color = Color.BLACK
            g.drawString(sum.label ?: sum.responseName, s.x.toFloat(), s.y.toFloat())
            g.drawString("—", s.x.toFloat(), (s.y + g.fontMetrics.height).toFloat())
        }
        for (clock in layout.clocks) {
            val s = tx.p(clock.position)
            val old = g.font
            g.font = old.deriveFont(tx.len(clock.fontSize).toFloat().coerceAtLeast(4f))
            g.color = Color.BLACK
            g.drawString("${clock.label ?: "Time"}: 0.0", s.x.toFloat(), s.y.toFloat())
            g.font = old
        }
    }

    /**
     * A legend box in the top-right listing each object class (its shape/color swatch + type name) and each
     * agent state color, drawn in screen space (zoom-independent), from the layout alone. A no-op when the
     * layout declares neither.
     */
    private fun drawLegend(g: Graphics2D, layout: AnimationLayout, canvasW: Int) {
        val classes = layout.objectClasses
        val states = layout.agentStateColors.entries.toList()
        if (classes.isEmpty() && states.isEmpty()) return
        val rowH = 18; val pad = 6; val swatch = 12.0
        val fm = g.fontMetrics
        var textW = 40
        classes.forEach { textW = maxOf(textW, fm.stringWidth(it.typeName)) }
        states.forEach { textW = maxOf(textW, fm.stringWidth(it.key)) }
        val boxW = pad + swatch.toInt() + 6 + textW + pad
        val boxH = pad * 2 + (classes.size + states.size) * rowH
        val x = (canvasW - boxW - 8).coerceAtLeast(0)
        val y = 8
        g.color = Color(255, 255, 255, 220)
        g.fill(Rectangle2D.Double(x.toDouble(), y.toDouble(), boxW.toDouble(), boxH.toDouble()))
        g.color = Color.GRAY
        g.stroke = BasicStroke(1f)
        g.draw(Rectangle2D.Double(x.toDouble(), y.toDouble(), boxW.toDouble(), boxH.toDouble()))
        val sx = x + pad + swatch / 2
        val tx0 = (x + pad + swatch + 6).toFloat()
        var ry = y + pad + rowH / 2
        for (oc in classes) {
            fillGlyph(g, sx, ry.toDouble(), swatch, parseColor(oc.color), oc.shape)
            g.color = Color.BLACK
            g.drawString(oc.typeName, tx0, (ry + 4).toFloat())
            ry += rowH
        }
        for ((state, hex) in states) {
            g.color = parseColor(hex)
            g.fill(Rectangle2D.Double(sx - swatch / 2, ry - swatch / 2, swatch, swatch))
            g.color = Color.BLACK
            g.drawString(state, tx0, (ry + 4).toFloat())
            ry += rowH
        }
    }

    /** A small title tag (a standalone-preview affordance; the app itself draws no title on the canvas). */
    private fun drawTitle(g: Graphics2D, layout: AnimationLayout) {
        val title = layout.title ?: return
        g.color = Color.DARK_GRAY
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
        g.drawString(title, 10, 18)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
    }

    // ── drawing helpers ───────────────────────────────────────────────────────────────────────────────

    /** Draws a glyph of [shape] centered at screen `(cx, cy)` with diameter [d]. IMAGE falls back to a square (P3 loads it). */
    private fun fillGlyph(g: Graphics2D, cx: Double, cy: Double, d: Double, color: Color, shape: LayoutShape) {
        val x = cx - d / 2; val y = cy - d / 2
        g.color = color
        when (shape) {
            LayoutShape.SQUARE, LayoutShape.IMAGE -> g.fill(Rectangle2D.Double(x, y, d, d))
            LayoutShape.CIRCLE -> g.fill(Ellipse2D.Double(x, y, d, d))
            LayoutShape.TRIANGLE -> {
                val p = Path2D.Double()
                p.moveTo(cx, cy - d / 2); p.lineTo(cx + d / 2, cy + d / 2); p.lineTo(cx - d / 2, cy + d / 2); p.closePath()
                g.fill(p)
            }
            LayoutShape.DIAMOND -> {
                val p = Path2D.Double()
                p.moveTo(cx, cy - d / 2); p.lineTo(cx + d / 2, cy); p.lineTo(cx, cy + d / 2); p.lineTo(cx - d / 2, cy); p.closePath()
                g.fill(p)
            }
        }
    }

    private fun framedBox(g: Graphics2D, at: Point2D, w: Double, h: Double, caption: String) {
        val rect = Rectangle2D.Double(at.x, at.y, w.coerceAtLeast(8.0), h.coerceAtLeast(6.0))
        g.color = Color.WHITE
        g.fill(rect)
        g.color = Color.DARK_GRAY
        g.stroke = BasicStroke(1f)
        g.draw(rect)
        g.drawString(caption, at.x.toFloat(), (at.y - 3.0).toFloat())
    }

    private fun drawPolyline(g: Graphics2D, pts: List<LayoutPoint>, tx: Transform, color: Color, stroke: Float) {
        if (pts.size < 2) return
        g.color = color
        g.stroke = BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (i in 0 until pts.size - 1) {
            val a = tx.p(pts[i]); val b = tx.p(pts[i + 1])
            g.draw(Line2D.Double(a.x, a.y, b.x, b.y))
        }
    }

    private fun directionArrow(g: Graphics2D, from: Point2D, to: Point2D, color: Color) {
        val ang = kotlin.math.atan2(to.y - from.y, to.x - from.x)
        val mx = (from.x + to.x) / 2; val my = (from.y + to.y) / 2
        val len = 7.0; val wing = Math.toRadians(150.0)
        g.color = color
        g.stroke = BasicStroke(1f)
        val p = Path2D.Double()
        p.moveTo(mx, my); p.lineTo(mx + len * cos(ang + wing), my + len * sin(ang + wing))
        p.moveTo(mx, my); p.lineTo(mx + len * cos(ang - wing), my + len * sin(ang - wing))
        g.draw(p)
    }

    /**
     * Draws an element's text label honoring the layout's per-element overrides ([AnimationLayout.labels]):
     * an override can retitle it, offset it from [anchor] (screen px), or hide it; absent an override,
     * [defaultText] is drawn just above the glyph and [value] just below. Blank/null text ⇒ nothing.
     */
    private fun label(
        g: Graphics2D, layout: AnimationLayout, anchor: Point2D, kind: ElementKind, name: String,
        defaultText: String?, value: String?,
    ) {
        val lbl = layout.labels.firstOrNull { it.kind == kind && it.name == name }
        g.color = Color.DARK_GRAY
        if (lbl?.visible != false) (lbl?.text ?: defaultText)?.takeIf { it.isNotBlank() }?.let {
            g.drawString(it, (anchor.x + (lbl?.dx ?: 0.0)).toFloat(), (anchor.y + (lbl?.dy ?: -12.0)).toFloat())
        }
        if (lbl?.valueVisible != false) value?.takeIf { it.isNotBlank() }?.let {
            g.drawString(it, (anchor.x + (lbl?.valueDx ?: 0.0)).toFloat(), (anchor.y + (lbl?.valueDy ?: 14.0)).toFloat())
        }
    }

    /** A mover's at-rest world position: its home-base (location, else network station) position, else its parked [position]. */
    private fun moverAtRest(layout: AnimationLayout, mr: MovableResourceLayoutElement): LayoutPoint? =
        mr.homeBase?.let { hb ->
            layout.locations.firstOrNull { it.locationName == hb }?.position
                ?: layout.stations.firstOrNull { it.stationName == hb }?.position
        } ?: mr.position

    /** Each conveyor segment as a world polyline (entry anchor → waypoints → exit anchor); segments with an unplaced anchor are dropped. */
    private fun conveyorRoutes(layout: AnimationLayout, c: ConveyorLayoutElement): List<List<LayoutPoint>> =
        c.segments.mapNotNull { seg ->
            val from = layout.anchorPosition(AnchorRef.location(seg.entryLocation))
            val to = layout.anchorPosition(AnchorRef.location(seg.exitLocation))
            if (from == null || to == null) null else listOf(from) + seg.waypoints + listOf(to)
        }

    private fun fanRingOffset(i: Int, n: Int, radius: Double): Pair<Double, Double> {
        if (n <= 1) return 0.0 to 0.0
        val ang = 2.0 * PI * i / n
        return radius * cos(ang) to radius * sin(ang)
    }

    /** Parses a "#rrggbb" (or "#aarrggbb") hex color; falls back to gray on malformed input (matches VisualStyle). */
    private fun parseColor(hex: String): Color = runCatching {
        val s = hex.removePrefix("#")
        when (s.length) {
            6 -> Color(s.substring(0, 2).toInt(16), s.substring(2, 4).toInt(16), s.substring(4, 6).toInt(16))
            8 -> Color(
                s.substring(2, 4).toInt(16), s.substring(4, 6).toInt(16),
                s.substring(6, 8).toInt(16), s.substring(0, 2).toInt(16),
            )
            else -> Color.GRAY
        }
    }.getOrDefault(Color.GRAY)
}
