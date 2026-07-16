package ksl.app.swing.animation.app

import ksl.animation.ElementKind
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stage 2: the controller manages layouts as first-class, plural, editable documents — new/scaffold,
 * granular edits with dirty tracking, save/open into `layouts/`, and inventory validation. Headless.
 */
class LayoutDocumentTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            Model("TRLayout").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    private fun controller(ws: java.nio.file.Path) =
        AnimationAppController("Anim", builder).apply { workspaceOverride = ws }

    @Test
    fun `new blank layout is active, empty, dirty and unbound`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-lay")
        val c = controller(ws)
        try {
            assertNull(c.layout.value)
            c.newBlankLayout()
            assertNotNull(c.layout.value)
            assertTrue(c.layout.value!!.resources.isEmpty())
            assertTrue(c.layoutDirty.value, "new content is unsaved")
            assertNull(c.layoutFile.value, "new layout has no bound file")
        } finally { c.close(); ws.toFile().deleteRecursively() }
    }

    @Test
    fun `granular edits mutate the active layout and mark it dirty`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-lay")
        val c = controller(ws)
        try {
            // Editing with no active layout auto-starts a blank one.
            c.addLayoutElement(ElementKind.RESOURCE, "Test1")
            assertTrue(c.layout.value!!.isPlaced(ElementKind.RESOURCE, "Test1"))
            c.moveLayoutElement(ElementKind.RESOURCE, "Test1", 300.0, 150.0)
            assertEquals(300.0, c.layout.value!!.positionOf(ElementKind.RESOURCE, "Test1")?.x)
            c.setLayoutCanvasSize(1234.0, 567.0)
            assertEquals(1234.0, c.layout.value!!.width)
            c.removeLayoutElement(ElementKind.RESOURCE, "Test1")
            assertFalse(c.layout.value!!.isPlaced(ElementKind.RESOURCE, "Test1"))
            assertTrue(c.layoutDirty.value)
        } finally { c.close(); ws.toFile().deleteRecursively() }
    }

    @Test
    fun `starter (scaffold) layout is populated`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-lay")
        val c = controller(ws)
        try {
            c.scaffoldLayout()
            assertTrue(c.layout.value!!.resources.isNotEmpty(), "scaffold places the model's resources")
        } finally { c.close(); ws.toFile().deleteRecursively() }
    }

    @Test
    fun `layout validation reflects whether bindings match the inventory`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-lay")
        val c = controller(ws)
        try {
            // Build from real inventory names → valid.
            val realResource = c.inventory.namesOf(ElementKind.RESOURCE).first()
            c.newBlankLayout()
            c.addLayoutElement(ElementKind.RESOURCE, realResource)
            assertTrue(c.layoutValidation().isValid, "bindings to real elements validate")
            // A binding to a non-existent element → invalid.
            c.addLayoutElement(ElementKind.RESOURCE, "NotAnElement")
            assertFalse(c.layoutValidation().isValid, "an unmatched binding fails validation")
        } finally { c.close(); ws.toFile().deleteRecursively() }
    }

    @Test
    fun `save and reopen round-trips a layout through the layouts folder`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-lay")
        val c = controller(ws)
        try {
            c.newBlankLayout()
            c.addLayoutElement(ElementKind.RESOURCE, "Test1")
            c.addLayoutElement(ElementKind.QUEUE, "TestQ")
            val process = c.layoutsDir.resolve("process.lay.json")
            c.saveLayout(process)
            assertEquals(process, c.layoutFile.value, "saved file is bound")
            assertFalse(c.layoutDirty.value, "clean after save")

            // A second, distinct layout.
            c.newBlankLayout()
            c.addLayoutElement(ElementKind.RESOURCE, "Test2")
            c.saveLayout(c.layoutsDir.resolve("other.lay.json"))
            assertEquals(2, c.listLayouts().size, "both layouts enumerated")

            // Reopen the first and confirm its content survived the round-trip.
            c.loadLayout(process)
            assertTrue(c.layout.value!!.isPlaced(ElementKind.RESOURCE, "Test1"))
            assertTrue(c.layout.value!!.isPlaced(ElementKind.QUEUE, "TestQ"))
            assertFalse(c.layoutDirty.value, "a freshly loaded layout is clean")
        } finally { c.close(); ws.toFile().deleteRecursively() }
    }
}
