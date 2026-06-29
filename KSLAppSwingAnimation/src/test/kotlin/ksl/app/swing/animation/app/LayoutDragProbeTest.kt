package ksl.app.swing.animation.app

import ksl.animation.ElementKind
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.awt.event.MouseEvent
import java.awt.geom.Point2D
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 6: dragging an element on the Layout preview moves it through the controller's mutator. Headless —
 * the canvas is sized directly (no display) and the press/drag/release are synthesized via dispatchEvent,
 * which invokes the panel's drag listener exactly as the EDT would.
 */
class LayoutDragProbeTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TRDrag").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `dragging the resource moves it to the drop point`() {
        val controller = AnimationAppController("Anim", builder)
        try {
            val moved = onEdt {
                val panel = LayoutPanel(controller)
                controller.newBlankLayout()
                controller.setLayoutCanvasSize(1000.0, 700.0)
                controller.addLayoutElement(ElementKind.RESOURCE, "Test1")
                controller.moveLayoutElement(ElementKind.RESOURCE, "Test1", 200.0, 200.0)

                val canvas = panel.previewCanvasForTest()
                canvas.setSize(800, 600) // give worldTransform real dimensions without a display

                val tx = canvas.worldTransform()
                val from = tx.transform(Point2D.Double(200.0, 200.0), null) // where the element is on screen
                val to = tx.transform(Point2D.Double(600.0, 400.0), null)   // where we drop it
                fun ev(id: Int, p: Point2D) =
                    MouseEvent(canvas, id, System.currentTimeMillis(), 0, p.x.toInt(), p.y.toInt(), 1, false)
                canvas.dispatchEvent(ev(MouseEvent.MOUSE_PRESSED, from))
                canvas.dispatchEvent(ev(MouseEvent.MOUSE_DRAGGED, to))
                canvas.dispatchEvent(ev(MouseEvent.MOUSE_RELEASED, to))

                controller.layout.value!!.positionOf(ElementKind.RESOURCE, "Test1")
            }
            assertNotNull(moved, "the element is still placed after the drag")
            assertTrue(kotlin.math.hypot(moved.x - 600.0, moved.y - 400.0) < 5.0,
                "dragged element should land near world (600,400); was (${moved.x}, ${moved.y})")
        } finally {
            onEdt { controller.close() }
        }
    }

    @Test
    fun `a drag that starts on empty space moves nothing`() {
        val controller = AnimationAppController("Anim", builder)
        try {
            val pos = onEdt {
                val panel = LayoutPanel(controller)
                controller.newBlankLayout()
                controller.setLayoutCanvasSize(1000.0, 700.0)
                controller.addLayoutElement(ElementKind.RESOURCE, "Test1")
                controller.moveLayoutElement(ElementKind.RESOURCE, "Test1", 200.0, 200.0)

                val canvas = panel.previewCanvasForTest()
                canvas.setSize(800, 600)
                val tx = canvas.worldTransform()
                val empty = tx.transform(Point2D.Double(800.0, 600.0), null) // far from the element
                val drop = tx.transform(Point2D.Double(100.0, 100.0), null)
                fun ev(id: Int, p: Point2D) =
                    MouseEvent(canvas, id, System.currentTimeMillis(), 0, p.x.toInt(), p.y.toInt(), 1, false)
                canvas.dispatchEvent(ev(MouseEvent.MOUSE_PRESSED, empty))
                canvas.dispatchEvent(ev(MouseEvent.MOUSE_DRAGGED, drop))
                canvas.dispatchEvent(ev(MouseEvent.MOUSE_RELEASED, drop))
                controller.layout.value!!.positionOf(ElementKind.RESOURCE, "Test1")
            }
            assertTrue(pos!!.x == 200.0 && pos.y == 200.0, "element unmoved when the drag misses it; was $pos")
        } finally {
            onEdt { controller.close() }
        }
    }
}
