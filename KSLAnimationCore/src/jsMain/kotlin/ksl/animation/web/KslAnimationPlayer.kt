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

package ksl.animation.web

import ksl.app.animation.geom.ViewTransform
import ksl.app.animation.scene.SceneOptions
import ksl.app.animation.scene.SceneRenderer
import ksl.app.animation.scene.Viewport
import ksl.app.animation.style.RgbaColor
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.replay.autoLayout
import ksl.app.animation.scene.SceneBuilder
import ksl.app.swing.animation.playback.PlaybackController
import kotlin.js.Date
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.WheelEvent

/** How a player is configured, mirroring the `data-ksl-*` attributes an embedding page can set. */
internal data class PlayerOptions(
    val autoPlay: Boolean = false,
    val showTransport: Boolean = true,
    val showLegend: Boolean = true,
    val loop: Boolean = true,
    /** Simulated time units per real second; null fits the whole run into [fitSeconds]. */
    val speed: Double? = null,
    /** How long the whole run should take to watch when no speed is given. Matches the desktop viewer. */
    val fitSeconds: Double = PlaybackController.DEFAULT_TARGET_SECONDS,
    val assetBase: String? = null,
    val background: RgbaColor = RgbaColor.WHITE
)

/**
 * Plays a KSL animation trace in a browser.
 *
 * A player owns the pieces that are inherently about *this* page — a canvas, a transport bar, the
 * animation frame callback, the mouse — and delegates everything else. What the animation contains comes
 * from a [SceneBuilder]; when it should be comes from [PlaybackController], the same clock the desktop
 * viewer uses. So playback semantics (speed in simulated time per real second, looping, in/out focus)
 * cannot drift between the two viewers.
 */
internal class KslAnimationPlayer(
    private val container: HTMLElement,
    private val options: PlayerOptions = PlayerOptions()
) {

    private val canvas = document.createElement("canvas") as HTMLCanvasElement
    private val ctx get() = canvas.getContext("2d") as CanvasRenderingContext2D
    private val transport = if (options.showTransport) TransportBar(container) else null

    private var model: ReplayModel? = null
    private var builder: SceneBuilder? = null
    private var surface: Canvas2dSurface? = null
    private var view: ViewTransform = ViewTransform(0.0, 0.0, 1.0)
    private var viewport: Viewport? = null
    private var controller = PlaybackController()
    private var loop: AnimationLoop? = null
    private var images: ImageCache? = null

    private var zoom = 1.0

    // The user's pan is held as a DELTA from the fitted, centered position rather than as an absolute
    // offset. Keeping it relative is what lets the animation stay centered when the window is resized:
    // the centering is recomputed for the new viewport and whatever dragging the user did rides on top.
    private var userPanX = 0.0
    private var userPanY = 0.0
    private var basePanX = 0.0
    private var basePanY = 0.0

    // What the container measured when the canvas was last sized, so the observer can ignore the
    // notifications that do not actually change anything.
    private var lastContainerWidth = -1
    private var lastContainerHeight = -1

    init {
        canvas.style.width = "100%"
        canvas.style.height = "100%"
        canvas.style.display = "block"
        container.appendChild(canvas)
        transport?.attachAfter(canvas)
        installPointerControls()
        observeContainerSize()
    }

    // ── loading ─────────────────────────────────────────────────────────────────────────────────────

    fun load(traceUrl: String, layoutUrl: String?) {
        transport?.showStatus("loading…")
        TraceLoader(onProgress = { transport?.showProgress(it) }).load(
            traceUrl, layoutUrl, options.assetBase,
            onDone = { adopt(it) },
            onError = { transport?.showStatus("error: $it") }
        )
    }

    fun loadInline(traceText: String, layoutJson: String?) {
        transport?.showStatus("loading…")
        TraceLoader(onProgress = { transport?.showProgress(it) }).loadInline(
            traceText, layoutJson, options.assetBase,
            onDone = { adopt(it) },
            onError = { transport?.showStatus("error: $it") }
        )
    }

    private fun adopt(source: AnimationSource) {
        val replay = scaffoldIfNeeded(ReplayModel.build(source), source)
        model = replay
        images = ImageCache(source.assetBase)
        builder = SceneBuilder(replay, SceneOptions(showLegend = options.showLegend))

        controller = PlaybackController(replay.timeRange).also { c ->
            val span = replay.timeRange.endInclusive - replay.timeRange.start
            // The same tidy rate the desktop would pick, so the two viewers open a run at one speed and the
            // transport bar can show it as one of its listed values rather than an odd fraction.
            c.speed = options.speed ?: PlaybackController.autoSpeedFor(span, options.fitSeconds)
            c.loop = options.loop
            c.addTimeListener { t ->
                transport?.showTime(t, c)
                render(t)
            }
        }
        transport?.bind(controller)
        // The view controls are the discoverable way back from a lost zoom, and the only way at all for a
        // reader with no wheel and no wish to pinch.
        transport?.bindView(
            zoomIn = { zoomBy(ZOOM_STEP) },
            zoomOut = { zoomBy(1.0 / ZOOM_STEP) },
            fit = { resetView() }
        )

        zoom = 1.0; userPanX = 0.0; userPanY = 0.0
        resize()
        transport?.showStatus(describe(replay))

        loop?.stop()
        loop = AnimationLoop(controller).also { it.start() }
        if (options.autoPlay) controller.play() else render(replay.timeRange.start)
        transport?.showTime(controller.currentTime, controller)
    }

    /**
     * Scaffolds a layout when the trace arrived without one, and rebuilds the replay against it.
     *
     * A layout is what says where a queue is drawn, where its server sits, which color an entity class
     * takes. A trace carries none of that for a process-view model — positions only exist for things that
     * physically move — so replaying a queueing model with no layout draws an empty canvas. The desktop
     * viewer has always scaffolded one in this situation; doing the same here is what lets someone drop a
     * bare `.atf` into a page and see their model rather than nothing.
     *
     * A spatial model would render either way, since its coordinates ride in the trace, but it still
     * benefits: the scaffold declares object classes, so agents get stable colors and sensible sizes.
     */
    private fun scaffoldIfNeeded(replay: ReplayModel, source: AnimationSource): ReplayModel {
        if (replay.layout != null) return replay
        val scaffold = replay.autoLayout(source.events, source.header.description)
        return ReplayModel.build(AnimationSource(scaffold, source.header, source.events, source.assetBase))
    }

    private fun describe(replay: ReplayModel): String {
        val parts = ArrayList<String>()
        if (replay.entityCount > 0) parts.add("${replay.entityCount} entities")
        if (replay.agentNames.isNotEmpty()) parts.add("${replay.agentNames.size} agents")
        if (replay.queueNames.isNotEmpty()) parts.add("${replay.queueNames.size} queues")
        if (replay.resourceNames.isNotEmpty()) parts.add("${replay.resourceNames.size} resources")
        val label = replay.header.description ?: "animation"
        return if (parts.isEmpty()) label else "$label — " + parts.joinToString(", ")
    }

    // ── rendering ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Sizes the backing store to the device's pixel ratio rather than to CSS pixels, so the animation is
     * not soft on a high-density display, and re-fits the view to the world.
     */
    /**
     * Re-fits whenever the *container* changes size, by any route.
     *
     * A `window` resize listener only catches one of the ways that happens. A page can grow or shrink the
     * box with a class change, a collapsing sidebar, a font that arrives late, a details element opening —
     * and the canvas is sized in pixels at fit time, so it simply kept its old dimensions while the box
     * around it moved. `ResizeObserver` watches the element rather than the window and covers all of it,
     * the window included.
     *
     * The guard is not an optimisation. `resize` draws, drawing can change layout in principle, and an
     * observer that reacts to its own effects is the classic way to get a loop the browser then reports as
     * "ResizeObserver loop completed with undelivered notifications".
     */
    private fun observeContainerSize() {
        val onChange: () -> Unit = {
            val w = container.clientWidth
            val h = container.clientHeight
            if (w != lastContainerWidth || h != lastContainerHeight) {
                lastContainerWidth = w
                lastContainerHeight = h
                resize()
            }
        }
        // Written as a self-contained JS factory taking the element and the callback, rather than naming
        // Kotlin locals inside a js() string: what a local is called after compilation is not this file's
        // business, and a captured name that survives today can stop surviving.
        val attach = js(
            """(function (el, fn) {
                   if (typeof ResizeObserver === 'undefined') { window.addEventListener('resize', fn); return; }
                   new ResizeObserver(function () { fn(); }).observe(el);
               })"""
        )
        attach(container, onChange)
    }

    fun resize() {
        val cssWidth = container.clientWidth.toDouble().coerceAtLeast(1.0)
        val cssHeight = (container.clientHeight.toDouble() - (transport?.heightPx ?: 0.0)).coerceAtLeast(1.0)
        val ratio = window.devicePixelRatio.takeIf { it > 0.0 } ?: 1.0
        canvas.width = (cssWidth * ratio).toInt()
        canvas.height = (cssHeight * ratio).toInt()
        canvas.style.height = "${cssHeight}px"
        ctx.setTransform(ratio, 0.0, 0.0, ratio, 0.0, 0.0)

        viewport = Viewport(cssWidth, cssHeight)
        surface = Canvas2dSurface(ctx, cssWidth, cssHeight, images ?: ImageCache(null))
        builder?.let { b ->
            // Reserve the legend's column so it cannot land on the layout's rightmost element — but never
            // more than a modest share of the canvas. The legend's width is in pixels and does not shrink
            // with the viewport, so on a narrow canvas it was eating half the space and the animation fitted
            // into what was left: a 470px-wide box gave a 240px animation with a 230px legend beside it,
            // drawn tiny in the middle of a tall empty frame. Past this cap the legend simply overlays the
            // top-right corner, which is the convention the desktop viewer already uses.
            val legend = if (options.showLegend) b.legendFootprint()?.widthPx ?: 0.0 else 0.0
            val reserved = legend.coerceAtMost(cssWidth * MAX_LEGEND_SHARE)
            val fitted = ViewTransform.fit(b.worldBounds(), (cssWidth - reserved).coerceAtLeast(1.0), cssHeight)
            basePanX = fitted.panX
            basePanY = fitted.panY
            view = fitted.withZoomPan(zoom, basePanX + userPanX, basePanY + userPanY)
            publishAspect(b)
        }
        render(controller.currentTime)
    }

    /**
     * Tells the page what shape this animation wants, as a `--ksl-aspect` custom property on the container.
     *
     * A box of a fixed height — `70vh`, say — has no idea what is going into it, so a wide model in a tall
     * window fitted to the width and left a third of the frame empty below it. The player cannot simply
     * take the height it wants: the container belongs to the page, which drew the border and chose the
     * spacing. So it publishes the ratio and lets the page decide, which a stylesheet can act on with
     * `aspect-ratio: var(--ksl-aspect)` and a `max-height` to keep it from dominating a large screen.
     *
     * It is the **world's** ratio, deliberately, not an exact ratio for the whole control. The legend and
     * the transport bar are fixed pixel sizes, so the control's true aspect depends on how large it is —
     * there is no single number, and solving for one produced arithmetic nobody could check. The world's
     * ratio leaves a modest, honest margin instead of a third of an empty frame, and the page's
     * `max-height` still governs how big the thing gets.
     *
     * Stable by construction: world bounds do not change with the container, so re-fitting cannot make the
     * ratio drift and set the observer oscillating.
     */
    private fun publishAspect(b: SceneBuilder) {
        val world = b.worldBounds()
        val width = world.maxX - world.minX
        val height = world.maxY - world.minY
        if (width <= 0.0 || height <= 0.0) return
        container.style.setProperty("--ksl-aspect", "${round2(width)} / ${round2(height)}")
    }

    private fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0

    private fun render(t: Double) {
        val b = builder ?: return
        val s = surface ?: return
        SceneRenderer.render(b.build(t, viewport), s, view, options.background)
    }

    // ── view controls ───────────────────────────────────────────────────────────────────────────────

    /**
     * Binds navigation to *pointer* events rather than to mouse events.
     *
     * One set of handlers then covers a mouse, a finger and a pen, which is what lets a page from the
     * animation pack be navigated on a tablet: the transport bar is ordinary HTML and always worked by
     * touch, but drag-to-pan and pinch-to-zoom silently did nothing while the canvas listened only for
     * `mousedown`/`mousemove`. The gesture arithmetic itself is in [PointerGestures].
     *
     * **The page's scroll wins by default.** This canvas lives inside a document a reader scrolls, not in
     * an application window it owns, and an earlier version of this method got that backwards: it set
     * `touch-action: none` and canceled every wheel event, so a finger swipe or a trackpad scroll over the
     * animation zoomed it instead of moving the page. There was no way past the animation and, since the
     * only reset was an undiscoverable double-click, no way back to a sensible view either. A canvas
     * embedded in a page must ask for gestures, not take them.
     *
     * So: `touch-action: pan-y` leaves vertical scrolling to the browser and keeps the rest; the wheel
     * zooms only while ctrl or ⌘ is held, which is also how browsers report a trackpad pinch, so pinching
     * still zooms on a laptop. Two fingers pinch and pan on a touchscreen. Nothing that a reader would do
     * by accident changes the view, and [TransportBar]'s **−**, **+** and **Fit** are there for anyone who
     * would rather not gesture at all.
     *
     * Pointer capture keeps a drag alive when the finger or cursor leaves the canvas — the job the old
     * window-level `mousemove` and `mouseup` listeners were doing by hand.
     */
    private fun installPointerControls() {
        // pan-y, not none: a one-finger vertical swipe is how a reader scrolls the page, and it must keep
        // working over the animation. Everything else -- horizontal drags, two-finger pinch -- still
        // reaches the handlers below.
        canvas.style.setProperty("touch-action", "pan-y")
        val gestures = PointerGestures()

        canvas.addEventListener("wheel", { event ->
            val wheel = event as WheelEvent
            // Only an explicit zoom gesture. A bare wheel or two-finger scroll is left to the browser, so
            // the page scrolls; browsers deliver a trackpad pinch as ctrl+wheel, so that still zooms.
            if (!wheel.ctrlKey && !wheel.metaKey) return@addEventListener
            event.preventDefault()
            val factor = if (wheel.deltaY < 0) ZOOM_STEP else 1.0 / ZOOM_STEP
            val rect = canvas.getBoundingClientRect()
            adopt(view.zoomedAbout(factor, wheel.clientX - rect.left, wheel.clientY - rect.top))
            render(controller.currentTime)
        })
        canvas.addEventListener("pointerdown", { event ->
            val e = event.asDynamic()
            gestures.down(e.pointerId as Int, e.clientX as Double, e.clientY as Double, now())
            // Throws if the browser no longer considers the pointer active. Dragging still works without
            // capture — it just stops at the canvas edge — so this must not abort the rest of the handler.
            runCatching { canvas.asDynamic().setPointerCapture(e.pointerId) }
            canvas.style.cursor = "grabbing"
        })
        canvas.addEventListener("pointermove", { event ->
            val e = event.asDynamic()
            val change = gestures.move(e.pointerId as Int, e.clientX as Double, e.clientY as Double)
                ?: return@addEventListener
            apply(change)
        })
        canvas.addEventListener("pointerup", { event ->
            val e = event.asDynamic()
            val doubleTapped = gestures.up(e.pointerId as Int, e.clientX as Double, e.clientY as Double, now())
            if (!gestures.isActive) canvas.style.cursor = "default"
            if (doubleTapped) resetView()
        })
        canvas.addEventListener("pointercancel", { event ->
            gestures.cancel(event.asDynamic().pointerId as Int)
            if (!gestures.isActive) canvas.style.cursor = "default"
        })
        // A mouse still gets its double-click through the browser's own event; a finger gets the same
        // reset from the double tap [PointerGestures] recognizes. Resetting twice is harmless.
        canvas.addEventListener("dblclick", { resetView() })
    }

    private fun apply(change: PointerGestures.Change) {
        userPanX += change.panXPx
        userPanY += change.panYPx
        view = view.withZoomPan(zoom, basePanX + userPanX, basePanY + userPanY)
        if (change.zoomFactor != 1.0) {
            val rect = canvas.getBoundingClientRect()
            adopt(view.zoomedAbout(change.zoomFactor, change.focusXPx - rect.left, change.focusYPx - rect.top))
        }
        render(controller.currentTime)
    }

    /**
     * Takes [next] as the current view, storing its pan back as a delta from the fitted position.
     *
     * Zooming about a point solves for an *absolute* pan that holds that point still, so adopting the
     * result verbatim would fold the fitted centering into the user's offset and a later resize would
     * re-center on top of it. Splitting it back out is what keeps the two separable.
     */
    private fun adopt(next: ViewTransform) {
        view = next
        zoom = next.zoom
        userPanX = next.panX - basePanX
        userPanY = next.panY - basePanY
    }

    /** Wall-clock milliseconds, for recognizing a double tap. */
    private fun now(): Double = Date.now()

    /**
     * Zooms about the middle of the canvas by [factor].
     *
     * About the center rather than a cursor, because these back the transport bar's **−** and **+**, which
     * are pressed from somewhere else entirely — and on a touchscreen there is no cursor to zoom about at
     * all. The wheel keeps zooming about the pointer, where a focus point genuinely exists.
     */
    fun zoomBy(factor: Double) {
        val rect = canvas.getBoundingClientRect()
        adopt(view.zoomedAbout(factor, rect.width / 2.0, rect.height / 2.0))
        render(controller.currentTime)
    }

    fun resetView() {
        zoom = 1.0; userPanX = 0.0; userPanY = 0.0
        resize()
    }

    internal companion object {
        const val ZOOM_STEP = 1.15

        /**
         * The most of the canvas width the legend may claim before the animation is fitted beside it.
         *
         * The legend is drawn at a fixed pixel width, so on a narrow canvas reserving all of it left the
         * animation a sliver: a 470px box gave a 240px animation and a 230px legend. Past this share the
         * legend overlays the corner instead, which is what the desktop viewer does anyway.
         */
        const val MAX_LEGEND_SHARE = 0.28
    }
}

/**
 * Drives the playback clock from the browser's animation frame callback.
 *
 * The delta is clamped because a browser stops these callbacks for a hidden tab: without the clamp, a run
 * returned to after a minute elsewhere would jump a minute of simulated time in a single frame. Clamping
 * makes a backgrounded animation pause and resume rather than teleport.
 */
internal class AnimationLoop(
    private val controller: PlaybackController,
    private val maxDeltaSeconds: Double = 0.25
) {
    private var running = false
    private var last = 0.0

    fun start() {
        if (running) return
        running = true
        last = window.performance.now()
        schedule()
    }

    fun stop() {
        running = false
    }

    private fun schedule() {
        window.requestAnimationFrame { now ->
            if (!running) return@requestAnimationFrame
            val delta = ((now - last) / 1000.0).coerceIn(0.0, maxDeltaSeconds)
            last = now
            if (controller.isPlaying) controller.advanceBy(delta)
            schedule()
        }
    }
}
