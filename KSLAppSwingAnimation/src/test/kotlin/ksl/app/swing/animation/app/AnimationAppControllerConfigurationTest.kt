package ksl.app.swing.animation.app

import ksl.animation.CaptureMode
import ksl.animation.CaptureSpec
import ksl.animation.ElementKind
import ksl.animation.ValidationIssue
import ksl.animation.animation
import ksl.app.config.RunConfigurationToml
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Response
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies 9C.1: the headless [AnimationAppController] — probe→inventory, capture-spec and layout state
 * with two-document dirty tracking, TOML config round-trip (incl. capture), and inventory validation.
 */
class AnimationAppControllerConfigurationTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val m = Model("AnimCfgModel", autoCSVReports = false)
            ResourceWithQ(m, "Worker")   // exposes "Worker" and "Worker:Q"
            Response(m, "SystemTime")
            return m
        }
    }

    private var controller: AnimationAppController? = null
    private fun fresh(appName: String = "AnimCfgApp"): AnimationAppController =
        AnimationAppController(appName, builder).also { controller = it }

    @AfterTest fun tearDown() { controller?.close(); controller = null }

    @Test
    fun `probe populates the inventory`() {
        val c = fresh()
        assertNull(c.probeFailure)
        assertTrue(c.inventory.resources.contains("Worker"), "resources=${c.inventory.resources}")
        assertTrue(c.inventory.queues.contains("Worker:Q"), "queues=${c.inventory.queues}")
        assertTrue(c.inventory.responses.contains("SystemTime"), "responses=${c.inventory.responses}")
    }

    @Test
    fun `a fresh controller is clean`() {
        val c = fresh()
        assertFalse(c.isDirty.value)
        assertNull(c.currentFile.value)
        assertFalse(c.layoutDirty.value)
        assertNull(c.layoutFile.value)
    }

    @Test
    fun `editing run params or capture flips config dirty`() {
        val c = fresh()
        c.updateRunOverride { it.copy(numberOfReplications = 5) }
        assertTrue(c.isDirty.value)

        val c2 = fresh()
        c2.setCaptureMode(CaptureMode.SELECTED)
        c2.addInclude(ElementKind.RESOURCE, "Worker")
        assertTrue(c2.isDirty.value)
    }

    @Test
    fun `currentConfiguration carries the capture spec and blanks install-local paths`() {
        val c = fresh()
        c.setCaptureMode(CaptureMode.SELECTED)
        c.addInclude(ElementKind.RESOURCE, "Worker")
        c.setCaptureWindow(10.0, 50.0)
        val cfg = c.currentConfiguration()
        assertEquals(c.captureSpec.value, cfg.tracingConfig.capture)
        assertNull(cfg.tracingConfig.animationTraceFile)
        assertNull(cfg.outputConfig.outputDirectory)
    }

    @Test
    fun `the capture spec round-trips through RunConfiguration TOML`() {
        val c = fresh()
        c.setCaptureMode(CaptureMode.SELECTED)
        c.addInclude(ElementKind.RESOURCE, "Worker")
        c.addExclude(ElementKind.RESPONSE, "SystemTime")
        c.setCaptureWindow(10.0, 50.0)
        c.updateRunOverride { it.copy(numberOfReplications = 3) }
        val expectedCapture = c.captureSpec.value

        val decoded = RunConfigurationToml.decode(RunConfigurationToml.encode(c.currentConfiguration()))

        val loaded = fresh()
        val result = loaded.loadConfiguration(decoded)
        assertIs<AnimationAppController.LoadResult.Loaded>(result)
        assertEquals(expectedCapture, loaded.captureSpec.value, "capture spec survives the round-trip")
        assertEquals(3, loaded.runOverrides.value.numberOfReplications)
        assertFalse(loaded.isDirty.value, "a freshly loaded configuration is clean")
    }

    @Test
    fun `the layout is a separate document with its own dirty flag`() {
        val c = fresh()
        c.scaffoldLayout()
        assertTrue(c.layout.value != null, "scaffold produced a layout")
        assertTrue(c.layoutDirty.value, "setting a layout marks the layout document dirty")
        assertFalse(c.isDirty.value, "the config document is untouched by a layout edit")

        val path = Files.createTempFile("anim-layout", ".lay.json")
        try {
            c.saveLayout(path)
            assertFalse(c.layoutDirty.value, "saving clears the layout dirty flag")
            assertEquals(path, c.layoutFile.value)

            val c2 = fresh()
            c2.loadLayout(path)
            assertTrue(c2.layout.value != null)
            assertFalse(c2.layoutDirty.value, "a freshly loaded layout is clean")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `capture validation flags a selector typo and accepts a real one`() {
        val c = fresh()
        c.setCaptureMode(CaptureMode.SELECTED)
        c.addInclude(ElementKind.RESOURCE, "Workr")   // typo -> Worker
        val report = c.captureValidation()
        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.kind == ValidationIssue.Kind.UNMATCHED_SELECTOR && it.message.contains("Worker") })

        c.removeInclude(ElementKind.RESOURCE, "Workr")
        c.addInclude(ElementKind.RESOURCE, "Worker")
        assertTrue(c.captureValidation().isValid, c.captureValidation().toString())
    }

    @Test
    fun `layout validation flags a bad binding against the inventory`() {
        val c = fresh()
        val badLayout = Model("x").animation { resource("Workr", 0.0, 0.0) } // typo -> Worker
        c.setLayout(badLayout)
        val report = c.layoutValidation()
        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.kind == ValidationIssue.Kind.UNMATCHED_RESOURCE })
    }

    @Test
    fun `resetConfiguration clears both documents`() {
        val c = fresh()
        c.updateRunOverride { it.copy(numberOfReplications = 9) }
        c.setCaptureMode(CaptureMode.SELECTED)
        c.scaffoldLayout()
        c.markSaved(Files.createTempFile("anim-cfg", ".toml"))

        c.resetConfiguration()
        assertEquals(CaptureSpec(), c.captureSpec.value)
        assertNull(c.layout.value)
        assertNull(c.currentFile.value)
        assertFalse(c.isDirty.value)
        assertFalse(c.layoutDirty.value)
    }

    @Test
    fun `saveConfiguration then openConfiguration round-trips a TOML file`() {
        val c = fresh()
        c.setCaptureMode(CaptureMode.SELECTED)
        c.addInclude(ElementKind.RESOURCE, "Worker")
        c.setCaptureWindow(10.0, 50.0)
        c.updateRunOverride { it.copy(numberOfReplications = 7) }
        val expectedCapture = c.captureSpec.value

        val path = Files.createTempFile("anim-cfg", ".toml")
        try {
            c.saveConfiguration(path)
            assertFalse(c.isDirty.value, "saving clears dirty")
            assertEquals(path, c.currentFile.value)

            val loaded = fresh()
            val result = loaded.openConfiguration(path)
            assertIs<AnimationAppController.LoadResult.Loaded>(result)
            assertEquals(expectedCapture, loaded.captureSpec.value)
            assertEquals(7, loaded.runOverrides.value.numberOfReplications)
            assertEquals(path, loaded.currentFile.value, "a successful open records the file")
            assertFalse(loaded.isDirty.value)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `openConfiguration on a malformed file leaves state unchanged`() {
        val c = fresh()
        val path = Files.createTempFile("anim-bad", ".toml")
        try {
            Files.writeString(path, "scenarios = []\n") // valid TOML, zero scenarios
            val result = c.openConfiguration(path)
            assertIs<AnimationAppController.LoadResult.Rejected>(result)
            assertNull(c.currentFile.value, "a rejected open does not bind the file")
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `markSaved clears config dirty and records the file`() {
        val c = fresh()
        c.updateRunOverride { it.copy(numberOfReplications = 2) }
        assertTrue(c.isDirty.value)
        val path = Files.createTempFile("anim-cfg", ".toml")
        try {
            c.markSaved(path)
            assertFalse(c.isDirty.value)
            assertEquals(path, c.currentFile.value)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}
