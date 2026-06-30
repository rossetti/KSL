package ksl.app.swing.animation.view

import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.BackgroundElement
import ksl.animation.BackgroundKind
import ksl.animation.LayoutPoint
import ksl.animation.ResourceLayoutElement
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies 8A.1: a background image referenced by a *relative* path is resolved against the layout
 * directory ([ReplayModel.baseDir]) and drawn into its world rectangle beneath the other elements.
 */
class BackgroundImageTest {

    @TempDir
    lateinit var tempRoot: Path

    @Test
    fun `relative background image is resolved against baseDir and drawn`() {
        val dir = Files.createTempDirectory(tempRoot, "ksl-bg")
        // A distinctly-colored background image (teal) written into the layout directory.
        val teal = Color(0x00, 0x80, 0x80)
        val bg = BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB)
        bg.createGraphics().apply { color = teal; fillRect(0, 0, 40, 40); dispose() }
        ImageIO.write(bg, "png", File(dir.toFile(), "floor.png"))

        val layout = AnimationLayout(
            title = "Background image",
            width = 200.0, height = 120.0,
            background = listOf(
                BackgroundElement(
                    kind = BackgroundKind.IMAGE,
                    points = listOf(LayoutPoint(0.0, 0.0), LayoutPoint(200.0, 120.0)),
                    imageRef = "floor.png" // relative -> resolved against baseDir
                )
            ),
            resources = listOf(ResourceLayoutElement(resourceName = "Worker", position = LayoutPoint(100.0, 60.0), size = 24.0))
        )
        // baseDir is the layout directory (what AnimationSource.load would set).
        val model = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), emptyList(), baseDir = dir))

        val canvas = SimulationCanvas()
        canvas.setSize(400, 240)
        canvas.replay = model
        val image = BufferedImage(400, 240, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); canvas.paint(g); g.dispose()

        var tealPixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width)
            if (image.getRGB(x, y) and 0xffffff == teal.rgb and 0xffffff) tealPixels++
        assertTrue(tealPixels > 5000, "expected the teal background image to fill much of the frame, got $tealPixels")

        val out = File(System.getProperty("java.io.tmpdir"), "ksl-background-image.png")
        ImageIO.write(image, "png", out)
        println("Saved background-image frame to ${out.absolutePath}")
    }
}
