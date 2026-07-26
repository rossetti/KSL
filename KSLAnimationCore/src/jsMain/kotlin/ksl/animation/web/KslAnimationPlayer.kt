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
    val fitSeconds: Double = 20.0,
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

    // The user's pan is held as a DELTA from the fitted, centred position rather than as an absolute
    // offset. Keeping it relative is what lets the animation stay centred when the window is resized:
    // the centring is recomputed for the new viewport and whatever dragging the user did rides on top.
    private var userPanX = 0.0
    private var userPanY = 0.0
    private var basePanX = 0.0
    private var basePanY = 0.0

    init {
        canvas.style.width = "100%"
        canvas.style.height = "100%"
        canvas.style.display = "block"
        container.appendChild(canvas)
        transport?.attachAfter(canvas)
        installMouseControls()
        window.addEventListener("resize", { resize() })
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
            c.speed = options.speed ?: if (span > 0.0) span / options.fitSeconds else 1.0
            c.loop = options.loop
            c.addTimeListener { t ->
                transport?.showTime(t, c)
                render(t)
            }
        }
        transport?.bind(controller)

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
     * A layout is what says where a queue is drawn, where its server sits, which colour an entity class
     * takes. A trace carries none of that for a process-view model — positions only exist for things that
     * physically move — so replaying a queueing model with no layout draws an empty canvas. The desktop
     * viewer has always scaffolded one in this situation; doing the same here is what lets someone drop a
     * bare `.atf` into a page and see their model rather than nothing.
     *
     * A spatial model would render either way, since its coordinates ride in the trace, but it still
     * benefits: the scaffold declares object classes, so agents get stable colours and sensible sizes.
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
            // Reserve the legend's column so it cannot land on the layout's rightmost element.
            val reserved = if (options.showLegend) b.legendFootprint()?.widthPx ?: 0.0 else 0.0
            val fitted = ViewTransform.fit(b.worldBounds(), (cssWidth - reserved).coerceAtLeast(1.0), cssHeight)
            basePanX = fitted.panX
            basePanY = fitted.panY
            view = fitted.withZoomPan(zoom, basePanX + userPanX, basePanY + userPanY)
        }
        render(controller.currentTime)
    }

    private fun render(t: Double) {
        val b = builder ?: return
        val s = surface ?: return
        SceneRenderer.render(b.build(t, viewport), s, view, options.background)
    }

    // ── view controls ───────────────────────────────────────────────────────────────────────────────

    private fun installMouseControls() {
        var dragging = false
        var lastX = 0.0
        var lastY = 0.0

        canvas.addEventListener("wheel", { event ->
            event.preventDefault()
            val wheel = event as WheelEvent
            val factor = if (wheel.deltaY < 0) ZOOM_STEP else 1.0 / ZOOM_STEP
            val rect = canvas.getBoundingClientRect()
            view = view.zoomedAbout(factor, wheel.clientX - rect.left, wheel.clientY - rect.top)
            // zoomedAbout solves for an absolute pan that keeps the cursor's world point fixed; store it
            // back as a delta so the centring stays separable.
            zoom = view.zoom
            userPanX = view.panX - basePanX
            userPanY = view.panY - basePanY
            render(controller.currentTime)
        })
        canvas.addEventListener("mousedown", { event ->
            val e = event.asDynamic()
            dragging = true; lastX = e.clientX as Double; lastY = e.clientY as Double
            canvas.style.cursor = "grabbing"
        })
        window.addEventListener("mouseup", {
            dragging = false
            canvas.style.cursor = "default"
        })
        window.addEventListener("mousemove", { event ->
            if (!dragging) return@addEventListener
            val e = event.asDynamic()
            val x = e.clientX as Double
            val y = e.clientY as Double
            userPanX += x - lastX; userPanY += y - lastY
            lastX = x; lastY = y
            view = view.withZoomPan(zoom, basePanX + userPanX, basePanY + userPanY)
            render(controller.currentTime)
        })
        canvas.addEventListener("dblclick", { resetView() })
    }

    fun resetView() {
        zoom = 1.0; userPanX = 0.0; userPanY = 0.0
        resize()
    }

    private companion object {
        const val ZOOM_STEP = 1.15
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
