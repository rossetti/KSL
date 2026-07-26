package spike

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import ksl.app.swing.animation.playback.PlaybackController
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.xhr.XMLHttpRequest

/**
 * PHASE S SPIKE — DISPOSABLE. Loads a real KSL `.atf` trace + `.lay.json` in the browser, builds the
 * REAL `ReplayModel`, and plays it on a canvas driven by the REAL `PlaybackController`.
 *
 * Everything below the `spike.*` package is unmodified KSL code (bar the Phase-1 edits the plan already
 * specifies), which is the point: it tests the Path B premise that the animation core compiles and runs
 * on Kotlin/JS without a parallel implementation.
 */

private const val TRACES = "traces/"

private class Timing {
    var fetchMs = 0.0
    var parseMs = 0.0
    var buildMs = 0.0
    var events = 0
    var frameMs = 0.0
    var sceneCmds = 0
}

fun main() {
    document.addEventListener("DOMContentLoaded", {
        val select = document.getElementById("trace") as HTMLInputElement
        loadAndRun(select.value)
        (document.getElementById("load") as HTMLButtonElement).addEventListener("click", {
            loadAndRun((document.getElementById("trace") as HTMLInputElement).value)
        })
    })
}

private fun status(msg: String) {
    (document.getElementById("status") as HTMLElement).textContent = msg
}

private fun get(url: String, onDone: (String?) -> Unit) {
    val xhr = XMLHttpRequest()
    xhr.open("GET", url, true)
    xhr.onload = {
        onDone(if (xhr.status.toInt() == 200) xhr.responseText else null)
    }
    xhr.onerror = { onDone(null) }
    xhr.send()
}

private fun loadAndRun(name: String) {
    val timing = Timing()
    status("fetching $name.atf …")
    val t0 = window.performance.now()

    get("$TRACES$name.lay.json") { layoutText ->
        get("$TRACES$name.atf") { traceText ->
            if (traceText == null) {
                status("FAILED to fetch $TRACES$name.atf")
                return@get
            }
            timing.fetchMs = window.performance.now() - t0

            // ── parse: the .atf is JSON Lines — header line, then one event per line ──────────────
            val tParse = window.performance.now()
            val lines = traceText.split("\n").filter { it.isNotBlank() }
            val header: AnimationTraceHeader = AnimationTraceHeader.decodeFromLine(lines.first())
            val events = ArrayList<AnimationEvent>(lines.size)
            for (i in 1 until lines.size) events.add(AnimationEvent.decodeFromLine(lines[i]))
            timing.parseMs = window.performance.now() - tParse
            timing.events = events.size

            val layout: AnimationLayout? = layoutText?.let { AnimationLayout.fromJson(it) }

            // ── build the REAL ReplayModel ────────────────────────────────────────────────────────
            val tBuild = window.performance.now()
            val model = ReplayModel.build(AnimationSource(layout, header, events))
            timing.buildMs = window.performance.now() - tBuild

            run(model, timing, name)
        }
    }
}

private fun run(model: ReplayModel, timing: Timing, name: String) {
    val canvas = document.getElementById("canvas") as HTMLCanvasElement
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D

    // Device-pixel-ratio aware sizing, so text is crisp on a retina display.
    val dpr = window.devicePixelRatio
    val cssW = canvas.clientWidth.toDouble()
    val cssH = canvas.clientHeight.toDouble()
    canvas.width = (cssW * dpr).toInt()
    canvas.height = (cssH * dpr).toInt()
    ctx.scale(dpr, dpr)

    val builder = MiniSceneBuilder(model)
    val surface = Canvas2dSurface(ctx, cssW, cssH)
    val view = ViewTransform.fit(builder.worldBounds(), cssW, cssH)

    val controller = PlaybackController(model.timeRange)
    controller.speed = (model.timeRange.endInclusive - model.timeRange.start) / 12.0 // whole run in ~12s
    controller.loop = true

    val scrubber = document.getElementById("scrub") as HTMLInputElement
    val playBtn = document.getElementById("play") as HTMLButtonElement

    fun drawAt(t: Double) {
        val tf = window.performance.now()
        val scene = builder.build(t)
        renderScene(scene, surface, view)
        timing.frameMs = window.performance.now() - tf
        timing.sceneCmds = scene.commandCount
    }

    controller.addTimeListener { t ->
        scrubber.valueAsNumber = controller.fraction() * 1000.0
        drawAt(t)
    }

    playBtn.addEventListener("click", {
        controller.togglePlay()
        playBtn.textContent = if (controller.isPlaying) "Pause" else "Play"
    })
    scrubber.addEventListener("input", {
        controller.pause()
        playBtn.textContent = "Play"
        controller.seekFraction(scrubber.valueAsNumber / 1000.0)
    })

    // ── the animation loop: rAF delta -> PlaybackController.advanceBy, with the G11 clamp ─────────
    var last = window.performance.now()
    fun frame(now: Double) {
        val deltaSeconds = ((now - last) / 1000.0).coerceAtMost(0.25) // G11: backgrounded-tab clamp
        last = now
        if (controller.isPlaying) controller.advanceBy(deltaSeconds)
        window.requestAnimationFrame { frame(it) }
    }
    window.requestAnimationFrame { frame(it) }

    drawAt(model.timeRange.start)
    reportMetrics(model, timing, name)

    controller.play()
    playBtn.textContent = "Pause"
}

/**
 * The spike's real output: the measurements the plan's Phase S gate asks for. In particular the
 * `Long`-keyed query cost, which is gotcha G10 / decision D7 — Kotlin/JS compiles `Long` to `BigInt`
 * and the replay layer keys several maps by `entityId: Long`.
 */
private fun reportMetrics(model: ReplayModel, timing: Timing, name: String) {
    val span = model.timeRange.endInclusive - model.timeRange.start

    // Hammer the Long-keyed and String-keyed query paths at 200 sample times.
    val samples = 200
    val tQuery = window.performance.now()
    var touched = 0
    for (i in 0 until samples) {
        val t = model.timeRange.start + span * i / samples
        for (e in model.entitiesAt(t)) {
            model.entityPositionAt(e.id, t)          // Map<Long, MotionTrack>
            model.entityActivityLabelAt(e.id, t)     // Map<Long, StepTimeline<..>> x3
            touched++
        }
        for (q in model.queueNames) model.queueMembersAt(q, t)
        for (r in model.resourceNames) model.resourceStateAt(r, t)
        for (a in model.agentNames) model.agentPositionAt(a, t)
    }
    val queryMs = window.performance.now() - tQuery

    val out = """
        trace            $name
        events           ${timing.events}
        time range       ${model.timeRange.start} .. ${model.timeRange.endInclusive}
        entities         ${model.entityCount}
        agents           ${model.agentNames.size}
        queues           ${model.queueNames.size}   resources ${model.resourceNames.size}   responses ${model.responseNames.size}
        ---
        fetch            ${timing.fetchMs.r()} ms
        parse (JSONL)    ${timing.parseMs.r()} ms   (${(timing.events / timing.parseMs.coerceAtLeast(0.001) * 1000).r()} events/s)
        ReplayModel.build ${timing.buildMs.r()} ms
        ---
        scene build+draw ${timing.frameMs.r()} ms/frame   (${timing.sceneCmds} draw commands)
        query storm      ${queryMs.r()} ms for $samples sample times, $touched Long-keyed entity lookups
                         => ${(queryMs / samples.toDouble()).r()} ms per sample time
    """.trimIndent()
    (document.getElementById("metrics") as HTMLElement).textContent = out
    status("playing $name — ${timing.events} events, ${model.entityCount} entities, ${model.agentNames.size} agents")
    println(out)
}

private fun Double.r(): String {
    val v = kotlin.math.round(this * 100.0) / 100.0
    return v.toString()
}
