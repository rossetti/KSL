package spike

import ksl.animation.AnimationLayout
import ksl.animation.BackgroundKind
import ksl.animation.LayoutShape
import ksl.animation.geom.BoundingBox
import ksl.app.animation.replay.ReplayModel
import org.w3c.dom.CanvasRenderingContext2D
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * PHASE S SPIKE — a miniature of the plan's `SceneBuilder -> DrawCmd -> DrawSurface` pipeline (§4.1),
 * built to test whether that vocabulary is the right one before Phase 2 commits to it.
 *
 * Finding recorded in the plan: the five primitives below covered every element the spike rendered
 * (background, queues with members, resources with state colour, entity glyphs, agents, clock, legend).
 * `Glyph` earning its place as a distinct command — rather than being desugared into Circle/Rect at
 * build time — is the main vocabulary result: shape choice is per-object-class layout data, so keeping
 * it symbolic lets the surface decide, and lets an image ref fall back to a shape without the builder
 * knowing whether the image loaded.
 */

enum class DrawSpace { WORLD, SCREEN }

sealed interface DrawCmd {
    data class Polyline(val pts: List<Pair<Double, Double>>, val color: String, val width: Double) : DrawCmd
    data class Circle(val cx: Double, val cy: Double, val r: Double, val fill: String?, val stroke: String? = null) : DrawCmd
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double, val fill: String?, val stroke: String? = null) : DrawCmd
    data class Glyph(val cx: Double, val cy: Double, val size: Double, val shape: LayoutShape, val fill: String) : DrawCmd
    data class Text(val x: Double, val y: Double, val text: String, val color: String, val sizePx: Double = 11.0) : DrawCmd
}

data class Layer(val name: String, val space: DrawSpace, val cmds: List<DrawCmd>)

data class Scene(val layers: List<Layer>, val worldBounds: BoundingBox, val simTime: Double) {
    val commandCount: Int get() = layers.sumOf { it.cmds.size }
}

/** World->screen fit transform. The spike's stand-in for `ViewTransform`. */
class ViewTransform(private val minX: Double, private val minY: Double, val scale: Double, private val margin: Double = 16.0) {
    fun sx(wx: Double) = margin + (wx - minX) * scale
    fun sy(wy: Double) = margin + (wy - minY) * scale
    fun len(v: Double) = v * scale

    companion object {
        fun fit(b: BoundingBox, w: Double, h: Double, margin: Double = 16.0): ViewTransform {
            val s = minOf(
                (w - 2 * margin) / b.width.coerceAtLeast(1e-6),
                (h - 2 * margin) / b.height.coerceAtLeast(1e-6)
            ).coerceAtLeast(1e-6)
            return ViewTransform(b.minX, b.minY, s, margin)
        }
    }
}

interface DrawSurface {
    fun clear(color: String)
    fun beginLayer(space: DrawSpace, view: ViewTransform)
    fun draw(cmd: DrawCmd)
    fun endLayer()
}

/** The whole web renderer: one adapter from DrawCmd to CanvasRenderingContext2D. */
class Canvas2dSurface(
    private val ctx: CanvasRenderingContext2D,
    private val widthPx: Double,
    private val heightPx: Double
) : DrawSurface {

    private var view: ViewTransform? = null
    private var space: DrawSpace = DrawSpace.SCREEN

    override fun clear(color: String) {
        ctx.fillStyle = color
        ctx.fillRect(0.0, 0.0, widthPx, heightPx)
    }

    override fun beginLayer(space: DrawSpace, view: ViewTransform) {
        this.space = space
        this.view = view
        ctx.save()
    }

    override fun endLayer() {
        ctx.restore()
    }

    private fun x(v: Double) = if (space == DrawSpace.WORLD) view!!.sx(v) else v
    private fun y(v: Double) = if (space == DrawSpace.WORLD) view!!.sy(v) else v
    private fun l(v: Double) = if (space == DrawSpace.WORLD) view!!.len(v) else v

    override fun draw(cmd: DrawCmd) {
        when (cmd) {
            is DrawCmd.Polyline -> {
                if (cmd.pts.size < 2) return
                ctx.beginPath()
                ctx.strokeStyle = cmd.color
                ctx.lineWidth = cmd.width
                ctx.moveTo(x(cmd.pts[0].first), y(cmd.pts[0].second))
                for (i in 1 until cmd.pts.size) ctx.lineTo(x(cmd.pts[i].first), y(cmd.pts[i].second))
                ctx.stroke()
            }
            is DrawCmd.Circle -> {
                ctx.beginPath()
                ctx.arc(x(cmd.cx), y(cmd.cy), l(cmd.r).coerceAtLeast(0.5), 0.0, 2 * PI)
                cmd.fill?.let { ctx.fillStyle = it; ctx.fill() }
                cmd.stroke?.let { ctx.strokeStyle = it; ctx.lineWidth = 1.0; ctx.stroke() }
            }
            is DrawCmd.Rect -> {
                val rx = x(cmd.x); val ry = y(cmd.y); val rw = l(cmd.w); val rh = l(cmd.h)
                cmd.fill?.let { ctx.fillStyle = it; ctx.fillRect(rx, ry, rw, rh) }
                cmd.stroke?.let { ctx.strokeStyle = it; ctx.lineWidth = 1.0; ctx.strokeRect(rx, ry, rw, rh) }
            }
            is DrawCmd.Glyph -> {
                val cx = x(cmd.cx); val cy = y(cmd.cy); val d = l(cmd.size).coerceAtLeast(2.0)
                ctx.fillStyle = cmd.fill
                when (cmd.shape) {
                    LayoutShape.CIRCLE -> { ctx.beginPath(); ctx.arc(cx, cy, d / 2, 0.0, 2 * PI); ctx.fill() }
                    LayoutShape.SQUARE, LayoutShape.IMAGE -> ctx.fillRect(cx - d / 2, cy - d / 2, d, d)
                    LayoutShape.TRIANGLE -> {
                        ctx.beginPath(); ctx.moveTo(cx, cy - d / 2)
                        ctx.lineTo(cx + d / 2, cy + d / 2); ctx.lineTo(cx - d / 2, cy + d / 2)
                        ctx.closePath(); ctx.fill()
                    }
                    LayoutShape.DIAMOND -> {
                        ctx.beginPath(); ctx.moveTo(cx, cy - d / 2); ctx.lineTo(cx + d / 2, cy)
                        ctx.lineTo(cx, cy + d / 2); ctx.lineTo(cx - d / 2, cy)
                        ctx.closePath(); ctx.fill()
                    }
                }
            }
            is DrawCmd.Text -> {
                ctx.fillStyle = cmd.color
                ctx.font = "${cmd.sizePx}px sans-serif"
                ctx.fillText(cmd.text, x(cmd.x), y(cmd.y))
            }
        }
    }
}

/** Executes a Scene into a surface. Shared by every renderer in the real design. */
fun renderScene(scene: Scene, surface: DrawSurface, view: ViewTransform, background: String = "#ffffff") {
    surface.clear(background)
    for (layer in scene.layers) {
        surface.beginLayer(layer.space, view)
        for (cmd in layer.cmds) surface.draw(cmd)
        surface.endLayer()
    }
}

/**
 * Builds a Scene from the replay state at a time — the spike's `SceneBuilder`. Covers the subset the
 * Phase 2 "middle" scope needs to prove out: background, queues with identified members, resources with
 * state colour and occupants, entity glyphs, agents with state colour, and the clock.
 */
class MiniSceneBuilder(private val model: ReplayModel) {

    private val layout: AnimationLayout? = model.layout
    private val palette = listOf("#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b", "#e377c2", "#17becf")
    private val assigned = HashMap<String, String>()

    /** Stands in for VisualStyle.objectColor (which needs the RgbaColor port — gotcha G6). */
    private fun objectColor(typeName: String?): String {
        if (typeName == null) return "#1f77b4"
        layout?.objectClasses?.firstOrNull { it.typeName == typeName }?.let { return it.color }
        return assigned.getOrPut(typeName) { palette[assigned.size % palette.size] }
    }

    private fun objectShape(typeName: String?): LayoutShape =
        layout?.objectClasses?.firstOrNull { it.typeName == typeName }?.shape ?: LayoutShape.CIRCLE

    private fun objectSize(typeName: String?): Double =
        layout?.objectClasses?.firstOrNull { it.typeName == typeName }?.size ?: 10.0

    /**
     * SPIKE FINDING — this is subtler than it looks, and getting it wrong is very visible.
     *
     * The naive version (union the declared layout rect with the motion bounds) crams an agent model
     * into a corner: a continuous space spanning ~30 world units unioned with the *default* 1000x700
     * canvas fits the 1000x700 box, leaving the agents in 3% of the viewport. A trace with no layout at
     * all is the worst case, since the declared rect is then pure default.
     *
     * Both existing renderers already solve this independently — `AnimationLayoutRenderer.worldBounds()`
     * has the "content tiny relative to canvas => fit content" heuristic, and `SimulationCanvas` has its
     * own variant. A third renderer re-deriving it got it wrong. That is direct evidence for putting
     * `worldBounds()` in the shared SceneBuilder (decision S6/D5) rather than per surface.
     */
    fun worldBounds(): BoundingBox {
        // Content: everything actually drawn — motion, placed elements, and space backdrops.
        var content: BoundingBox? = model.coordinateBounds()
        model.effectiveSpaces.forEach { sp ->
            val b = when (sp) {
                is ksl.animation.SpatialSpaceDescriptor.Continuous -> BoundingBox(sp.xMin, sp.yMin, sp.xMax, sp.yMax)
                is ksl.animation.SpatialSpaceDescriptor.Grid -> BoundingBox(
                    sp.originX, sp.originY,
                    sp.originX + sp.cols * sp.cellSize, sp.originY + sp.rows * sp.cellSize
                )
                is ksl.animation.SpatialSpaceDescriptor.Network -> BoundingBox.of(sp.nodes.asSequence().map { it.position.x to it.position.y })
            }
            content = BoundingBox.union(content, b)
        }
        layout?.let { l ->
            val pts = ArrayList<Pair<Double, Double>>()
            l.queues.forEach { pts.add(it.position.x to it.position.y) }
            l.resources.forEach { pts.add(it.position.x to it.position.y) }
            l.stations.forEach { pts.add(it.position.x to it.position.y) }
            l.locations.forEach { loc -> loc.position?.let { pts.add(it.x to it.y) } }
            l.background.forEach { b -> b.points.forEach { pts.add(it.x to it.y) } }
            content = BoundingBox.union(content, BoundingBox.of(pts.asSequence()))
        }

        // No layout at all => the trace is the only authority; fit what it contains.
        val declared = layout?.let { BoundingBox(0.0, 0.0, it.width, it.height) }
            ?: return content?.grown(1.0) ?: BoundingBox(0.0, 0.0, 1000.0, 700.0)

        val c = content ?: return declared
        // Content fills a reasonable share of the authored canvas => honour the author's whitespace.
        return if (c.width >= 0.2 * declared.width && c.height >= 0.2 * declared.height) declared.union(c)
        else c.grown(1.0)
    }

    fun build(t: Double): Scene {
        val layers = ArrayList<Layer>()

        // ── background ────────────────────────────────────────────────────────────────────────────
        val bg = ArrayList<DrawCmd>()
        layout?.background?.forEach { b ->
            val pts = b.points.map { it.x to it.y }
            when (b.kind) {
                BackgroundKind.LINE, BackgroundKind.POLYLINE -> bg.add(DrawCmd.Polyline(pts, b.color, b.strokeWidth))
                BackgroundKind.RECT -> if (pts.size >= 2) bg.add(
                    DrawCmd.Rect(
                        minOf(pts[0].first, pts[1].first), minOf(pts[0].second, pts[1].second),
                        kotlin.math.abs(pts[1].first - pts[0].first), kotlin.math.abs(pts[1].second - pts[0].second),
                        fill = null, stroke = b.color
                    )
                )
                BackgroundKind.TEXT -> if (pts.isNotEmpty() && b.text != null) bg.add(
                    DrawCmd.Text(pts[0].first, pts[0].second, b.text!!, b.color, b.fontSize)
                )
                BackgroundKind.IMAGE -> Unit // G8: needs an asset base + ImageCache
            }
        }
        layers.add(Layer("background", DrawSpace.WORLD, bg))

        // ── spatial space backdrops (grid / continuous), for the agent traces ─────────────────────
        val spaceCmds = ArrayList<DrawCmd>()
        model.effectiveSpaces.forEach { sp ->
            when (sp) {
                is ksl.animation.SpatialSpaceDescriptor.Continuous ->
                    spaceCmds.add(DrawCmd.Rect(sp.xMin, sp.yMin, sp.xMax - sp.xMin, sp.yMax - sp.yMin, fill = null, stroke = "#aaaaaa"))
                is ksl.animation.SpatialSpaceDescriptor.Grid -> {
                    for (c in 0..sp.cols) spaceCmds.add(
                        DrawCmd.Polyline(
                            listOf(
                                (sp.originX + c * sp.cellSize) to sp.originY,
                                (sp.originX + c * sp.cellSize) to (sp.originY + sp.rows * sp.cellSize)
                            ), "#eeeeee", 1.0
                        )
                    )
                    for (r in 0..sp.rows) spaceCmds.add(
                        DrawCmd.Polyline(
                            listOf(
                                sp.originX to (sp.originY + r * sp.cellSize),
                                (sp.originX + sp.cols * sp.cellSize) to (sp.originY + r * sp.cellSize)
                            ), "#eeeeee", 1.0
                        )
                    )
                }
                is ksl.animation.SpatialSpaceDescriptor.Network -> {
                    val byId = sp.nodes.associateBy { it.id }
                    sp.edges.forEach { e ->
                        val a = byId[e.from]?.position; val b = byId[e.to]?.position
                        if (a != null && b != null) spaceCmds.add(DrawCmd.Polyline(listOf(a.x to a.y, b.x to b.y), "#dddddd", 1.0))
                    }
                }
            }
        }
        layers.add(Layer("spaces", DrawSpace.WORLD, spaceCmds))

        // ── queues: extent line, head bar, identified members ─────────────────────────────────────
        val queueCmds = ArrayList<DrawCmd>()
        layout?.queues?.forEach { q ->
            val rad = q.growthDegrees * PI / 180.0
            val dx = cos(rad); val dy = sin(rad)
            val runLen = q.spacing * q.maxShown.coerceAtLeast(1)
            queueCmds.add(
                DrawCmd.Polyline(
                    listOf(q.position.x to q.position.y, (q.position.x + runLen * dx) to (q.position.y + runLen * dy)),
                    "#888888", 1.0
                )
            )
            val members = model.queueMembersAt(q.queueName, t)
            members.forEachIndexed { i, id ->
                if (i >= q.maxShown) return@forEachIndexed
                val type = model.entityTypeOf(id)
                queueCmds.add(
                    DrawCmd.Glyph(
                        q.position.x + i * q.spacing * dx, q.position.y + i * q.spacing * dy,
                        objectSize(type), objectShape(type), objectColor(type)
                    )
                )
            }
            val len = model.queueLengthAt(q.queueName, t)
            queueCmds.add(DrawCmd.Text(q.position.x, q.position.y - 10.0, "${q.queueName} ($len)", "#444444"))
        }
        layers.add(Layer("queues", DrawSpace.WORLD, queueCmds))

        // ── resources: state colour + per-unit occupants ───────────────────────────────────────────
        val resCmds = ArrayList<DrawCmd>()
        layout?.resources?.forEach { r ->
            val snap = model.resourceStateAt(r.resourceName, t)
            val state = snap?.state
            val fill = when {
                state == null -> r.idleColor
                state.contains("Fail", true) -> r.failedColor
                state.contains("Inactive", true) -> r.inactiveColor
                state.contains("Busy", true) -> r.busyColor
                else -> r.idleColor
            }
            resCmds.add(DrawCmd.Rect(r.position.x - r.size / 2, r.position.y - r.size / 2, r.size, r.size, fill, "#000000"))
            resCmds.add(DrawCmd.Text(r.position.x, r.position.y - r.size / 2 - 4.0, r.resourceName, "#444444"))
            snap?.let { resCmds.add(DrawCmd.Text(r.position.x, r.position.y + r.size / 2 + 12.0, "${it.busyUnits}/${it.capacity}", "#666666")) }
            model.resourceUnitsAt(r.resourceName, t).forEachIndexed { i, id ->
                val type = model.entityTypeOf(id)
                resCmds.add(DrawCmd.Glyph(r.position.x, r.position.y, objectSize(type) * 0.7, objectShape(type), objectColor(type)))
            }
        }
        layers.add(Layer("resources", DrawSpace.WORLD, resCmds))

        // ── entities in free space (interpolated positions) ─────────────────────────────────────────
        val entCmds = ArrayList<DrawCmd>()
        for (e in model.entitiesAt(t)) {
            if (model.entityQueueAt(e.id, t) != null) continue      // drawn in its queue
            if (model.entityServiceResourceAt(e.id, t) != null) continue // drawn in the resource
            val p = model.entityPositionAt(e.id, t) ?: continue
            if (p.x.isNaN() || p.y.isNaN()) continue
            entCmds.add(DrawCmd.Glyph(p.x, p.y, objectSize(e.typeName), objectShape(e.typeName), objectColor(e.typeName)))
        }
        layers.add(Layer("entities", DrawSpace.WORLD, entCmds))

        // ── agents (ABM traces): position + statechart colour ──────────────────────────────────────
        val agentCmds = ArrayList<DrawCmd>()
        for (name in model.agentNames) {
            if (!model.agentPresentAt(name, t)) continue
            val p = model.agentPositionAt(name, t) ?: continue
            if (p.x.isNaN() || p.y.isNaN()) continue
            val state = model.agentStateAt(name, t)
            val stateColor = state?.let { s ->
                layout?.agentStateColors?.entries?.firstOrNull { it.key.equals(s, true) }?.value
                    ?: layout?.agentStateColors?.entries?.filter { s.contains(it.key, true) }?.maxByOrNull { it.key.length }?.value
            }
            val type = model.agentTypeOf(name)
            agentCmds.add(DrawCmd.Glyph(p.x, p.y, objectSize(type), objectShape(type), stateColor ?: objectColor(type)))
        }
        layers.add(Layer("agents", DrawSpace.WORLD, agentCmds))

        // ── clock + legend: SCREEN space, so they stay zoom-independent ────────────────────────────
        val chrome = ArrayList<DrawCmd>()
        layout?.clocks?.forEach { c ->
            chrome.add(DrawCmd.Text(20.0, 24.0, "${c.label ?: "Time"}: ${fmt(t)}", "#000000", 14.0))
        }
        layout?.objectClasses?.forEachIndexed { i, oc ->
            val y = 48.0 + i * 18.0
            chrome.add(DrawCmd.Glyph(28.0, y - 4.0, 12.0, oc.shape, oc.color))
            chrome.add(DrawCmd.Text(40.0, y, oc.typeName, "#333333"))
        }
        layers.add(Layer("chrome", DrawSpace.SCREEN, chrome))

        return Scene(layers, worldBounds(), t)
    }

    private fun fmt(v: Double): String {
        val r = kotlin.math.round(v * 10.0) / 10.0
        return r.toString()
    }
}
