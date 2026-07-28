package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.session.RunResult
import ksl.examples.general.agent.WarehouseAGVExample
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.Test

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The replay view's "Show …" toggles must describe the trace that is loaded, not the feature in the
 * abstract.
 *
 * Drawing an overlay and recording one are separate decisions: the capture switches on the run bar are all
 * off by default, so an ordinary run produces a trace with no routes, no flow field and no vectors in it.
 * The view bar used to offer a ticked "Show paths" regardless, which drew nothing and looked exactly like a
 * broken renderer — the failure that prompted these tests. Whichever way it misleads, it does so silently,
 * so the assertions here are about what the interface *says*.
 */
class OverlayToggleHonestyTest {

    @TempDir
    lateinit var tempRoot: Path

    /** An agent model that reports planned routes — so the overlay has something to record when asked. */
    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            Model("AGVOverlay").apply {
                numberOfReplications = 1
                lengthOfReplication = 30.0
                WarehouseAGVExample(this, "AGV")
            }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    /** Runs the model and loads the resulting trace into a panel, returning each toggle as the user sees it. */
    private fun togglesAfterRun(capturePaths: Boolean): List<Triple<String, Boolean, String>> {
        val ws = Files.createTempDirectory(tempRoot, "anim-overlay")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            c.setCapturePlannedPaths(capturePaths)
            c.submit()
            val result = runBlocking { withTimeout(120_000) { c.lastResult.filterNotNull().first() } }
            assertIs<RunResult.Completed>(result)
            val trace = c.lastTraceFile.value!!
            return onEdt {
                val panel = ReplayPanel(c)
                panel.loadTraceForTest(trace)
                panel.selectAutoLayoutForTest()
                panel.overlayTogglesForTest()
            }
        } finally {
            onEdt { c.close() }
            ws.toFile().deleteRecursively()
        }
    }

    private fun paths(toggles: List<Triple<String, Boolean, String>>) =
        toggles.single { it.first.startsWith("Show paths") }

    @Test
    @DisplayName("with nothing loaded the toggles are inert but described normally")
    fun nothingLoadedLeavesTheOrdinaryDescription() {
        val ws = Files.createTempDirectory(tempRoot, "anim-overlay-empty")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            val toggles = onEdt { ReplayPanel(c).overlayTogglesForTest() }
            assertEquals(4, toggles.size, "flow field, paths, vectors, pulses")
            // No trace means no claim to make about one. Saying "(not captured)" here would be a statement
            // about a trace that does not exist.
            assertTrue(toggles.all { !it.second }, "nothing to show, so nothing is clickable")
            assertTrue(toggles.none { it.first.contains("not captured") }, "no trace, so no verdict on one")
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }

    @Test
    @DisplayName("a trace captured without routes says so instead of offering to show them")
    fun aTraceWithoutRoutesSaysNotCaptured() {
        val (label, enabled, tip) = paths(togglesAfterRun(capturePaths = false))
        assertTrue("not captured" in label, "the label has to carry the news; an empty canvas does not: $label")
        assertFalse(enabled, "offering a switch that cannot do anything is the original defect")
        assertTrue("Capture paths" in tip, "and it must name the switch that fixes it: $tip")
        assertTrue("before simulating" in tip, "which has to be set before the run, not after: $tip")
    }

    @Test
    @DisplayName("a trace captured with routes offers them normally")
    fun aTraceWithRoutesIsOfferedNormally() {
        val (label, enabled, tip) = paths(togglesAfterRun(capturePaths = true))
        assertEquals("Show paths", label, "nothing to warn about")
        assertTrue(enabled, "the trace carries routes, so the user may draw them")
        assertFalse(tip.contains("not captured"), "no leftover warning: $tip")
    }
}
