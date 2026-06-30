package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.animation.ElementKind
import ksl.app.session.RunResult
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Stage 4: Replay pairs a trace with a layout chosen at replay time, and the layout can be swapped on a
 * loaded trace without reloading it (one .atf, many layouts). Headless.
 */
class ReplayPanelPairingTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TRPair").apply {
                numberOfReplications = 1
                lengthOfReplication = 30.0
                TestAndRepairShopWithMovableResources(this, "TR")
            }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `the same trace renders through different layouts without reloading`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-pair")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            // Produce a trace.
            c.submit()
            val result = runBlocking { withTimeout(60_000) { c.lastResult.filterNotNull().first() } }
            assertIs<RunResult.Completed>(result)
            val trace = c.lastTraceFile.value!!

            // Author and save a minimal layout (a single resource).
            c.newBlankLayout()
            c.addLayoutElement(ElementKind.RESOURCE, "Test1")
            c.saveLayout(c.layoutsDir.resolve("mini.lay.json"))

            val outcome = onEdt {
                val panel = ReplayPanel(c)
                panel.loadTraceForTest(trace)
                val traceShown = panel.traceChoicesForTest().any { it == trace.fileName.toString() }

                panel.selectQuickViewForTest()
                val quickSize = panel.renderedLayoutSizeForTest()
                val quickText = panel.compatibilityTextForTest()

                panel.selectSavedLayoutForTest("mini.lay.json")
                val miniSize = panel.renderedLayoutSizeForTest()
                val miniText = panel.compatibilityTextForTest()

                listOf(traceShown, quickSize, quickText, miniSize, miniText)
            }
            val traceShown = outcome[0] as Boolean
            val quickSize = outcome[1]
            val quickText = outcome[2] as String
            val miniSize = outcome[3]
            val miniText = outcome[4] as String

            assertTrue(traceShown, "the produced trace appears in the picker")
            // Quick view auto-derives a fuller layout; the mini saved layout has exactly one resource, no queues.
            assertNotEquals(quickSize, miniSize, "swapping the layout re-renders the same trace differently")
            assertEquals(1 to 0, miniSize, "mini layout places exactly one resource and no queues")
            assertContains(quickText, "Auto layout")
            // The mini layout omits many animated elements present in the trace.
            assertTrue(miniText.startsWith("⚠"), "mini layout reports unplaced animated elements: $miniText")
        } finally {
            onEdt { c.close() }
            ws.toFile().deleteRecursively()
        }
    }
}
