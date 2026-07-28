package ksl.app.swing.animation.examples

import ksl.app.animation.io.AnimationSource
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.replay.autoLayout
import ksl.app.swing.animation.view.SimulationCanvas
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.Path
import ksl.app.animation.io.load

/**
 * Doc-tooling helper: renders real frames of the SimulationCanvas from a captured `.atf` trace
 * (and optional layout) to PNGs, headless. Used to produce the animation visuals in the
 * `docs/guides/apps/animation.md` guide. Not part of the app.
 *
 * System properties: -Dtrace=<path.atf> [-Dlayout=<path.lay.json|.toml>] [-Dframes=N]
 *   [-Dout=<dir>] [-Dw=<px>] [-Dh=<px>]
 */
fun main() {
    System.setProperty("java.awt.headless", "true")
    val traceFile = Path(System.getProperty("trace") ?: error("-Dtrace required"))
    val layoutFile = System.getProperty("layout")?.let { Path(it) }
    val n = System.getProperty("frames")?.toIntOrNull() ?: 12
    val w = System.getProperty("w")?.toIntOrNull() ?: 1100
    val h = System.getProperty("h")?.toIntOrNull() ?: 760
    val outDir = File(System.getProperty("out") ?: "frames").apply { mkdirs() }

    val source = AnimationSource.load(layoutFile, traceFile)
    var replay = ReplayModel.build(source)
    if (replay.layout == null) {
        val auto = replay.autoLayout(source.events, source.header.description)
        replay = ReplayModel.build(AnimationSource(auto, source.header, source.events, assetBase = source.assetBase))
    }

    val canvas = SimulationCanvas()
    canvas.setSize(w, h)
    canvas.replay = replay
    canvas.showLegend = true

    val t0 = replay.timeRange.start
    val t1 = replay.timeRange.endInclusive
    println("trace=$traceFile  events=${source.events.size}  timeRange=$t0..$t1")
    for (i in 0 until n) {
        val t = if (n <= 1) t1 else t0 + (t1 - t0) * i / (n - 1)
        canvas.currentTime = t
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        canvas.paint(g)
        g.dispose()
        val out = File(outDir, "frame_%03d.png".format(i))
        ImageIO.write(autoCrop(image, margin = 24), "png", out)
        println("wrote ${out.name}  t=%.1f".format(t))
    }
}

/** Trim uniform white borders so the drawn content fills the frame (keeps a [margin] of padding). */
internal fun autoCrop(img: BufferedImage, margin: Int): BufferedImage {
    val white = -0x1  // 0xFFFFFFFF (opaque white)
    var minX = img.width; var minY = img.height; var maxX = -1; var maxY = -1
    for (y in 0 until img.height) for (x in 0 until img.width) {
        if ((img.getRGB(x, y) and 0xFFFFFF) != (white and 0xFFFFFF)) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }
    }
    if (maxX < minX || maxY < minY) return img // all white
    val x0 = (minX - margin).coerceAtLeast(0)
    val y0 = (minY - margin).coerceAtLeast(0)
    val x1 = (maxX + margin).coerceAtMost(img.width - 1)
    val y1 = (maxY + margin).coerceAtMost(img.height - 1)
    return img.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
}
