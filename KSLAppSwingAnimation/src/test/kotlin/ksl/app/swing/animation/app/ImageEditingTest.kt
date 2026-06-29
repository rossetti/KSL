package ksl.app.swing.animation.app

import ksl.animation.BackgroundKind
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Images-first decoration: a placed background image is first-class on the canvas — select, mouse-resize
 * (far corner), and delete — and import copies the file into the workspace and stores a layout-relative
 * reference (`images/<name>`) so a layout + its images form a portable bundle.
 */
class ImageEditingTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TRimg").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `an image is selectable, mouse-resizable, and deletable`() {
        val c = AnimationAppController("Anim", builder)
        try {
            val (hadGrip, sizeAfter) = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                c.addBackgroundImage("images/floor.png", 0.0, 0.0, 100.0, 80.0)
                panel.refreshForTest()
                // Select it; an image (a 2-corner shape) exposes a resize grip.
                panel.selectShapeForTest(0)
                val grip = panel.shapeHasResizeGripForTest()
                // Drag the far corner to (200, 160) — the image grows.
                panel.resizeShapeForTest(0, 200.0, 160.0)
                val far = c.layout.value!!.background[0].points[1]
                grip to (far.x to far.y)
            }
            assertTrue(hadGrip, "an image shows a resize grip when selected")
            assertEquals(200.0, sizeAfter.first, 1e-9, "far corner x followed the drag")
            assertEquals(160.0, sizeAfter.second, 1e-9, "far corner y followed the drag")

            // Delete via the Delete-key path.
            onEdt {
                val panel = LayoutPanel(c)
                panel.refreshForTest()
                panel.selectShapeForTest(0)
                panel.removeSelectedForTest()
            }
            assertTrue(c.layout.value!!.background.isEmpty(), "the image was removed")
        } finally { c.close() }
    }

    @Test
    fun `import copies the file into the workspace and stores a relative reference`() {
        val c = AnimationAppController("Anim", builder)
        try {
            val src = Files.createTempFile("floorplan", ".png").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val ref = onEdt { LayoutPanel(c).importImageRefForTest(src) }
            assertEquals("images/${src.name}", ref, "stored ref is layout-relative")
            val copied = c.layoutsDir.resolve("images").resolve(src.name)
            assertTrue(Files.exists(copied), "the image was copied into layouts/images/")
            src.delete()
        } finally { c.close() }
    }

    @Test
    fun `the editor preview resolves relative image refs against the layout folder`() {
        val c = AnimationAppController("Anim", builder)
        try {
            val base = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                c.addBackgroundImage("images/floor.png", 0.0, 0.0, 50.0, 50.0)
                panel.refreshForTest()
                panel.previewBaseDirForTest()
            }
            // Untitled layout → relative refs resolve against the workspace layouts dir, where imports are copied,
            // so a layout saved there + its images/ folder render together.
            assertEquals(c.layoutsDir, base, "preview resolves images relative to the layouts dir")
        } finally { c.close() }
    }
}
