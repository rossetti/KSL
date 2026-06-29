package ksl.app.swing.animation.app

import ksl.animation.BackgroundKind
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Shapes are first-class: a placed background shape (rect/line/text) can be selected, moved, edited, and
 * removed on the canvas (Delete key), the same as every other element — not orphaned with no way to delete it.
 */
class ShapeEditingTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TRshape").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `a placed shape can be selected, moved, edited, and deleted on the canvas`() {
        val c = AnimationAppController("Anim", builder)
        try {
            val outcome = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                // Two shapes: a rectangle and a text label.
                c.addBackgroundRect(10.0, 10.0, 60.0, 40.0, "#444444", 2.0)
                c.addBackgroundText(100.0, 100.0, "Zone A", "#222222")
                panel.refreshForTest()

                // Hit-test finds the text shape (index 1) at its anchor.
                val hit = panel.pickShapeAtWorldForTest(101.0, 99.0)
                // Selecting shows the outline.
                val outlined = panel.selectShapeForTest(0)
                // Move the rectangle (index 0) by a delta; its corner points shift.
                c.moveBackgroundAt(0, 5.0, 7.0)
                // Edit: recolor + restroke the rectangle in place.
                val rect0 = c.layout.value!!.background[0]
                c.setBackgroundAt(0, rect0.copy(color = "#ff0000", strokeWidth = 4.0))
                // Delete the selected shape (index 0) via the Delete-key path.
                panel.selectShapeForTest(0)
                panel.removeSelectedForTest()
                Triple(hit, outlined, panel.selectedShapeIndexForTest())
            }
            val (hit, outlined, selAfterDelete) = outcome
            assertEquals(1, hit, "the text shape is hit-tested at its anchor")
            assertTrue(outlined, "selecting a shape shows its outline")

            val bg = c.layout.value!!.background
            assertEquals(1, bg.size, "one shape remains after deleting the selected rectangle")
            assertEquals(BackgroundKind.TEXT, bg[0].kind, "the surviving shape is the text label")
            assertNull(selAfterDelete, "selection cleared after delete")
        } finally { c.close() }
    }

    @Test
    fun `move translates a shape's points and edit replaces its properties`() {
        val c = AnimationAppController("Anim", builder)
        try {
            onEdt {
                c.newBlankLayout()
                c.addBackgroundRect(0.0, 0.0, 20.0, 10.0, "#000000", 1.0)
            }
            c.moveBackgroundAt(0, 100.0, 50.0)
            val moved = c.layout.value!!.background[0]
            assertEquals(100.0, moved.points[0].x, 1e-9)
            assertEquals(50.0, moved.points[0].y, 1e-9)
            assertEquals(120.0, moved.points[1].x, 1e-9)
            assertEquals(60.0, moved.points[1].y, 1e-9)

            c.setBackgroundAt(0, moved.copy(color = "#00ff00", strokeWidth = 3.0))
            val edited = c.layout.value!!.background[0]
            assertEquals("#00ff00", edited.color)
            assertEquals(3.0, edited.strokeWidth, 1e-9)
        } finally { c.close() }
    }

    @Test
    fun `a text annotation carries a font family and size, and mouse-resize scales the font`() {
        val c = AnimationAppController("Anim", builder)
        try {
            val grewFont = onEdt {
                val panel = LayoutPanel(c)
                c.newBlankLayout()
                c.addBackgroundText(0.0, 0.0, "Zone A", "#222222", fontSize = 14.0, fontFamily = "Serif")
                panel.refreshForTest()
                panel.selectShapeForTest(0)
                val hadGrip = panel.shapeHasResizeGripForTest()  // text exposes a resize grip too
                val before = c.layout.value!!.background[0].fontSize
                // Drag the grip far from the anchor → font grows.
                panel.resizeShapeForTest(0, 200.0, 60.0)
                val after = c.layout.value!!.background[0].fontSize
                Triple(hadGrip, before, after)
            }
            val (hadGrip, before, after) = grewFont
            val text = c.layout.value!!.background[0]
            assertEquals(BackgroundKind.TEXT, text.kind)
            assertEquals("Serif", text.fontFamily, "font family stored")
            assertTrue(hadGrip, "a selected text annotation shows a resize grip")
            assertEquals(14.0, before, 1e-9, "initial font size")
            assertTrue(after > before, "dragging the grip away from the anchor enlarged the font ($before → $after)")
        } finally { c.close() }
    }
}
