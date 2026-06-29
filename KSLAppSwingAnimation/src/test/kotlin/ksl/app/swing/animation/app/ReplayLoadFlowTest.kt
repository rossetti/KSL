package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.session.RunResult
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * U2: Replay loads only when the user presses Load (no implicit combo-load), Load is disabled until a
 * trace is selectable, and a loaded-state line reports what's loaded. Headless.
 */
class ReplayLoadFlowTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TRLoad").apply { numberOfReplications = 1; lengthOfReplication = 30.0; TestAndRepairShopWithMovableResources(this, "TR") }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `no trace yet means Load is disabled and nothing is loaded`() {
        val ws = Files.createTempDirectory("anim-load")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            val state = onEdt {
                val panel = ReplayPanel(c)
                panel.loadEnabledForTest() to panel.loadedTextForTest()
            }
            assertFalse(state.first, "Load disabled with no trace")
            assertContains(state.second, "Nothing loaded")
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }

    @Test
    fun `replay view-bar toggles grid and pan on the canvas`() {
        val ws = Files.createTempDirectory("anim-view")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            val r = onEdt {
                val panel = ReplayPanel(c)
                val canvas = panel.previewCanvasForTest()
                val gridBefore = canvas.showGrid
                panel.clickGridForTest()
                val gridAfter = canvas.showGrid
                val panBefore = canvas.panEnabled // pan defaults on for replay
                panel.clickPanForTest()
                arrayOf(gridBefore, gridAfter, panBefore, canvas.panEnabled)
            }
            assertFalse(r[0], "grid starts off")
            assertTrue(r[1], "clicking the toggle turns the grid on")
            assertTrue(r[2], "pan is on by default in replay")
            assertFalse(r[3], "clicking the toggle turns pan off")
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }

    @Test
    fun `pressing Load pairs the trace and reports the loaded state`() {
        val ws = Files.createTempDirectory("anim-load")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            c.submit()
            val result = runBlocking { withTimeout(60_000) { c.lastResult.filterNotNull().first() } }
            assertIs<RunResult.Completed>(result)
            val traceName = c.lastTraceFile.value!!.fileName.toString()

            val loaded = onEdt {
                val panel = ReplayPanel(c)
                panel.loadByNameForTest(traceName) // rescan + select + Load
                Triple(panel.loadEnabledForTest(), panel.renderedLayoutSizeForTest(), panel.loadedTextForTest())
            }
            assertTrue(loaded.first, "Load enabled once a trace is present")
            assertTrue(loaded.second.first + loaded.second.second > 0, "a layout (resources/queues) is rendered after Load")
            assertContains(loaded.third, traceName)
            assertContains(loaded.third, "events")
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }
}
