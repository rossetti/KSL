package ksl.app.swing.animation.examples

import ksl.app.animation.io.AnimationSource
import ksl.app.animation.io.load
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.replay.autoLayout
import ksl.app.swing.animation.view.SimulationCanvas
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.Path

/**
 * Renders one still per animation for the published gallery's cards.
 *
 * It lives in this module because this module owns the canvas. The site generator is in KSLExamples, which
 * must not depend on the desktop app, so the two run as separate tasks over the same folders and the root
 * build orders them.
 *
 * **Which frame.** Not the last one. The final frame is a good poster for some models and a bad one for
 * others: the warehouse ends with a full web of routes, the pharmacy ends nearly empty, and the epidemic
 * ends uniformly green because the epidemic is over. A frame from the middle of the run catches a model
 * while something is still happening.
 *
 * Within that band the frame is chosen by **what is in it**: [CANDIDATES] instants are rendered and the
 * busiest wins, measured as how much of the image is not blank. Picking one at random was the first
 * attempt, and it caught the pharmacy at t=165.7 with an empty queue and both servers idle — a truthful
 * picture of a moment nobody would choose to advertise. A low-utilisation model spends much of its run
 * looking like nothing is happening.
 *
 * Deterministic either way, which matters more than it sounds: a genuinely random frame would give a
 * different picture on every rebuild, so an unrelated regeneration would rewrite fifteen binary files and
 * fill the site's history with churn nobody asked for.
 *
 * System properties: `-Dtraces=<dir> -Dlayouts=<dir> -Dout=<dir>` `[-Dw=<px>] [-Dh=<px>]`
 */
fun main() {
    System.setProperty("java.awt.headless", "true")
    val tracesDir = File(System.getProperty("traces") ?: error("-Dtraces required"))
    val layoutsDir = File(System.getProperty("layouts") ?: error("-Dlayouts required"))
    val outDir = File(System.getProperty("out") ?: error("-Dout required")).apply { mkdirs() }
    val w = System.getProperty("w")?.toIntOrNull() ?: 900
    val h = System.getProperty("h")?.toIntOrNull() ?: 620

    val traces = tracesDir.listFiles { f: File -> f.name.endsWith(".atf") }?.sortedBy { it.name }
        ?: error("no traces in $tracesDir")
    require(traces.isNotEmpty()) { "no .atf traces in $tracesDir — capture them first" }

    for (trace in traces) {
        val modelId = trace.name.removeSuffix(".atf")
        val layout = File(layoutsDir, "$modelId.lay.toml").takeIf { it.isFile }
        val source = AnimationSource.load(layout?.toPath()?.let { Path(it.toString()) }, Path(trace.path))
        var replay = ReplayModel.build(source)
        if (replay.layout == null) {
            val auto = replay.autoLayout(source.events, source.header.description)
            replay = ReplayModel.build(AnimationSource(auto, source.header, source.events, source.assetBase))
        }

        val canvas = SimulationCanvas()
        canvas.setSize(w, h)
        canvas.replay = replay
        canvas.showLegend = true

        val t0 = replay.timeRange.start
        val t1 = replay.timeRange.endInclusive

        var best: BufferedImage? = null
        var bestInk = -1
        var bestTime = t0
        for (i in 0 until CANDIDATES) {
            val fraction = MIDDLE_FROM + (MIDDLE_TO - MIDDLE_FROM) * i / (CANDIDATES - 1)
            val t = t0 + (t1 - t0) * fraction
            canvas.currentTime = t
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            canvas.paint(g)
            g.dispose()
            val ink = inkOf(image)
            if (ink > bestInk) {
                bestInk = ink
                best = image
                bestTime = t
            }
        }

        val out = File(outDir, "$modelId.png")
        ImageIO.write(autoCrop(best!!, margin = 16), "png", out)
        println("  %-30s t=%.1f of %.1f  %d KB".format(modelId, bestTime, t1, out.length() / 1024))
    }
    println("wrote ${traces.size} poster(s) to $outDir")
}

/**
 * How much of an image is not blank — the stand-in for "is anything happening here".
 *
 * Crude on purpose. It cannot tell a busy queue from a large legend, and it does not need to: every
 * candidate for a given model carries the same furniture, so the differences between them are the model.
 */
private fun inkOf(img: BufferedImage): Int {
    var ink = 0
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            val rgb = img.getRGB(x, y)
            val r = (rgb shr 16) and 0xff
            val g = (rgb shr 8) and 0xff
            val b = rgb and 0xff
            if (r < 245 || g < 245 || b < 245) ink++
        }
    }
    return ink
}

/** The band a poster is taken from: past the opening, before things wind down. */
internal const val MIDDLE_FROM = 0.40
internal const val MIDDLE_TO = 0.70

/** How many instants in that band to try. Enough to skip an idle moment, few enough to stay quick. */
private const val CANDIDATES = 7
