package ksl.app.swing.animation.examples

import ksl.app.animation.io.AnimationSource
import ksl.app.animation.io.load
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.replay.autoLayout
import ksl.app.swing.animation.view.SimulationCanvas
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.FileImageOutputStream
import kotlin.io.path.Path

/**
 * Doc tooling: renders a captured `.atf` trace to a **looping animated GIF** — the showcase images in the
 * repository README and in `docs/guides/apps/animation.md`. Not part of the app.
 *
 * GIF, and written with `javax.imageio`, for one reason: it adds nothing to build. An animation in the
 * README is worth having only if regenerating it is a single Gradle command on any machine that can already
 * build the project, and reaching for ffmpeg (or any encoder library) would make the docs depend on a
 * toolchain the repository does not otherwise need. The README's existing hero is a GIF too.
 *
 * The cost is real and worth stating: GIF carries **256 colours per frame**, so a smooth gradient — a
 * flow-field heatmap, say — will band. Models drawn in flat fills, which is most of them, are unaffected.
 * A model whose picture depends on a gradient is better served by a still PNG.
 *
 * System properties: -Dtrace=<path.atf> [-Dlayout=<path.lay.json>] [-Dout=<file.gif>] [-Dframes=N]
 *   [-Dw=<px>] [-Dh=<px>] [-Ddelay=<centiseconds>] [-Dfrom=<simTime>] [-Dto=<simTime>]
 */
fun main() {
    System.setProperty("java.awt.headless", "true")
    val traceFile = Path(System.getProperty("trace") ?: error("-Dtrace required"))
    val layoutFile = System.getProperty("layout")?.let { Path(it) }
    val frames = System.getProperty("frames")?.toIntOrNull() ?: 60
    val width = System.getProperty("w")?.toIntOrNull() ?: 900
    val height = System.getProperty("h")?.toIntOrNull() ?: 520
    // Centiseconds, because that is the unit a GIF frame delay is stored in. 8 is 12.5 frames a second,
    // which is smooth enough for an animation whose subject moves at walking pace.
    val delay = System.getProperty("delay")?.toIntOrNull() ?: 8
    val out = File(System.getProperty("out") ?: "animation.gif").apply { parentFile?.mkdirs() }

    val source = AnimationSource.load(layoutFile, traceFile)
    var replay = ReplayModel.build(source)
    if (replay.layout == null) {
        val auto = replay.autoLayout(source.events, source.header.description)
        replay = ReplayModel.build(AnimationSource(auto, source.header, source.events, assetBase = source.assetBase))
    }

    val canvas = SimulationCanvas()
    canvas.setSize(width, height)
    canvas.replay = replay
    canvas.showLegend = true

    // A window into the run, so an animation can skip a long empty tail without the model being re-run.
    val start = System.getProperty("from")?.toDoubleOrNull() ?: replay.timeRange.start
    val end = System.getProperty("to")?.toDoubleOrNull() ?: replay.timeRange.endInclusive

    GifSequence(out, delay).use { gif ->
        for (i in 0 until frames) {
            canvas.currentTime = if (frames <= 1) end else start + (end - start) * i / (frames - 1)
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            canvas.paint(g)
            g.dispose()
            gif.add(image)
        }
    }
    println("wrote $out  ($frames frames, ${width}x$height, ${delay * 10} ms/frame, ${out.length() / 1024} KB)")
}

/**
 * Writes a looping animated GIF one frame at a time, encoding only the part of each frame that changed.
 *
 * Two things here are fiddly and neither is optional.
 *
 * **Looping.** GIF has no "repeat" field. A viewer is told to loop by an application extension block that
 * Netscape defined in 1995 and everything has implemented since, and ImageIO exposes it only as a metadata
 * tree — hence the hand-assembly.
 *
 * **Frame differencing.** An animation of a simulation is mostly *still*: the caption, the bars' frames, the
 * idle stations and the acres of background are identical frame to frame, and only a handful of glyphs move.
 * Written naively, every frame carries the whole canvas and a 70-frame animation of a 20x20 grid comes to two
 * megabytes — too heavy to put at the top of a README. Writing each frame as just the rectangle that differs
 * from the one before, positioned with the image-descriptor offsets and composited with "doNotDispose", cuts
 * that by roughly four fifths for a typical model without touching what is drawn.
 */
private class GifSequence(file: File, private val delayCentiseconds: Int) : AutoCloseable {
    private val writer = ImageIO.getImageWritersByFormatName("gif").next()
    private val stream = FileImageOutputStream(file)
    private val params = writer.defaultWriteParam
    private var previous: BufferedImage? = null

    init {
        writer.output = stream
        writer.prepareWriteSequence(null)
    }

    fun add(image: BufferedImage) {
        val last = previous
        val patch = if (last == null) Rect(0, 0, image.width, image.height) else changedRegion(last, image)
        // An unchanged frame still has to occupy its slot in the timeline, so it becomes a single pixel.
        val region = patch ?: Rect(0, 0, 1, 1)
        val sub = image.getSubimage(region.x, region.y, region.width, region.height)
        writer.writeToSequence(IIOImage(sub, null, metadataFor(sub, region, first = last == null)), params)
        previous = image
    }

    override fun close() {
        writer.endWriteSequence()
        stream.close()
        writer.dispose()
    }

    private class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    /** The smallest rectangle covering every pixel that differs between [a] and [b], or null if none does. */
    private fun changedRegion(a: BufferedImage, b: BufferedImage): Rect? {
        var minX = a.width; var minY = a.height; var maxX = -1; var maxY = -1
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        return if (maxX < minX) null else Rect(minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    private fun metadataFor(image: BufferedImage, at: Rect, first: Boolean): javax.imageio.metadata.IIOMetadata {
        val meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), params)
        val format = meta.nativeMetadataFormatName
        val root = meta.getAsTree(format) as IIOMetadataNode

        child(root, "GraphicControlExtension").apply {
            // doNotDispose leaves the previous frame on screen for the next patch to be drawn over, which is
            // what makes a partial frame mean "this much changed" rather than "this is the whole picture".
            setAttribute("disposalMethod", "doNotDispose")
            setAttribute("userInputFlag", "FALSE")
            setAttribute("transparentColorFlag", "FALSE")
            setAttribute("delayTime", delayCentiseconds.toString())
            setAttribute("transparentColorIndex", "0")
        }
        child(root, "ImageDescriptor").apply {
            setAttribute("imageLeftPosition", at.x.toString())
            setAttribute("imageTopPosition", at.y.toString())
            setAttribute("imageWidth", at.width.toString())
            setAttribute("imageHeight", at.height.toString())
            setAttribute("interlaceFlag", "FALSE")
        }
        if (first) {
            // 0x01 0x00 0x00 is "loop forever"; a non-zero count would stop after that many passes.
            child(root, "ApplicationExtensions").appendChild(
                IIOMetadataNode("ApplicationExtension").apply {
                    setAttribute("applicationID", "NETSCAPE")
                    setAttribute("authenticationCode", "2.0")
                    userObject = byteArrayOf(0x1, 0x0, 0x0)
                }
            )
        }
        meta.setFromTree(format, root)
        return meta
    }

    /** The named child of [parent], creating it when the default metadata tree does not already carry one. */
    private fun child(parent: IIOMetadataNode, name: String): IIOMetadataNode {
        for (i in 0 until parent.length) {
            val node = parent.item(i) as IIOMetadataNode
            if (node.nodeName.equals(name, ignoreCase = true)) return node
        }
        return IIOMetadataNode(name).also { parent.appendChild(it) }
    }
}
