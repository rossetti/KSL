package ksl.app.swing.animation.view

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.LayoutShape
import ksl.animation.ObjectClassDefinition
import ksl.animation.SpatialSpaceDescriptor
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Verifies 8I.3c: TRIANGLE/DIAMOND object classes draw as their real shapes (in their color), and an
 * IMAGE object class draws its resolved image icon rather than the colored-square fallback.
 */
class ObjectGlyphTest {

    private fun stationaryMove(id: Long, x: Double, y: Double) =
        AnimationEvent.MoveStarted(0.0, id, fromX = x, fromY = y, toX = x, toY = y, velocity = 1.0, duration = 100.0, arrivalTime = 100.0)

    @Test
    fun `triangle and diamond draw in color, image draws its icon (8I3c)`() {
        val dir = Files.createTempDirectory("ksl-glyph")
        // A pure-red icon; the IMAGE object class color is blue, so red pixels prove the icon drew.
        val red = Color(0xff, 0x00, 0x00)
        val icon = BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB)
        icon.createGraphics().apply { color = red; fillRect(0, 0, 24, 24); dispose() }
        ImageIO.write(icon, "png", File(dir.toFile(), "icon.png"))

        val green = Color(0x00, 0xff, 0x00)
        val orange = Color(0xff, 0xa5, 0x00)
        val layout = AnimationLayout(
            title = "Glyphs", width = 300.0, height = 100.0,
            spaces = listOf(SpatialSpaceDescriptor.Continuous("s", 0.0, 300.0, 0.0, 100.0)),
            objectClasses = listOf(
                ObjectClassDefinition("Img", LayoutShape.IMAGE, color = "#0000ff", size = 24.0, imageRef = "icon.png"),
                ObjectClassDefinition("Tri", LayoutShape.TRIANGLE, color = "#00ff00", size = 24.0),
                ObjectClassDefinition("Dia", LayoutShape.DIAMOND, color = "#ffa500", size = 24.0)
            )
        )
        val events = listOf(
            AnimationEvent.EntityCreated(0.0, 1L, "Img"), stationaryMove(1L, 50.0, 50.0),
            AnimationEvent.EntityCreated(0.0, 2L, "Tri"), stationaryMove(2L, 150.0, 50.0),
            AnimationEvent.EntityCreated(0.0, 3L, "Dia"), stationaryMove(3L, 250.0, 50.0)
        )
        val model = ReplayModel.build(AnimationSource(layout, AnimationTraceHeader(), events, baseDir = dir))

        val canvas = SimulationCanvas()
        canvas.setSize(600, 200)
        canvas.replay = model
        canvas.currentTime = 1.0
        val image = BufferedImage(600, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics(); canvas.paint(g); g.dispose()

        var redPx = 0; var greenPx = 0; var orangePx = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            when (image.getRGB(x, y) and 0xffffff) {
                red.rgb and 0xffffff -> redPx++
                green.rgb and 0xffffff -> greenPx++
                orange.rgb and 0xffffff -> orangePx++
            }
        }
        assertTrue(redPx > 50, "IMAGE icon should have drawn its red image (not the blue fallback), got $redPx red px")
        assertTrue(greenPx > 50, "TRIANGLE should fill in its green color, got $greenPx")
        assertTrue(orangePx > 50, "DIAMOND should fill in its orange color, got $orangePx")

        val out = File(System.getProperty("java.io.tmpdir"), "ksl-object-glyphs.png")
        ImageIO.write(image, "png", out)
        println("Saved object-glyph frame to ${out.absolutePath}")
    }
}
