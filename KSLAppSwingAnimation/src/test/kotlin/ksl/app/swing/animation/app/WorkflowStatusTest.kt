package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.animation.ElementKind
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** Tests the pure workflow-status derivation (9F.5) and the controller's live status transitions. */
class WorkflowStatusTest {

    @TempDir
    lateinit var tempRoot: Path

    // ── Pure derivation ──────────────────────────────────────────────────────

    @Test
    fun `fresh session points at Run and blocks Replay`() {
        val s = deriveWorkflowStatus(captureValid = true, hasTrace = false, hasLayout = false, layoutValid = true)
        assertEquals(StageState.DONE, s.capture)
        assertEquals(StageState.AVAILABLE, s.run)
        assertEquals(StageState.BLOCKED, s.replay)
        assertContains(s.nextStep, "Run a simulation")
    }

    @Test
    fun `invalid capture takes priority in the next step`() {
        val s = deriveWorkflowStatus(captureValid = false, hasTrace = true, hasLayout = true, layoutValid = true)
        assertEquals(StageState.AVAILABLE, s.capture)
        assertContains(s.nextStep, "capture")
    }

    @Test
    fun `with a trace but no layout, Replay is available and the hint offers layout or quick view`() {
        val s = deriveWorkflowStatus(captureValid = true, hasTrace = true, hasLayout = false, layoutValid = true)
        assertEquals(StageState.DONE, s.run)
        assertEquals(StageState.AVAILABLE, s.replay)
        assertContains(s.nextStep, "layout")
    }

    @Test
    fun `everything done points at Replay`() {
        val s = deriveWorkflowStatus(captureValid = true, hasTrace = true, hasLayout = true, layoutValid = true)
        assertEquals(StageState.DONE, s.layout)
        assertContains(s.nextStep, "Replay")
    }

    // ── Live transitions through the controller ────────────────────────────────

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Model("TRFlow").apply { numberOfReplications = 1; lengthOfReplication = 30.0; TestAndRepairShopWithMovableResources(this, "TR") }
    }

    @Test
    fun `controller status advances as a run and a layout are produced`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-flow")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            // Fresh: no trace, no layout.
            c.workflowStatus().let {
                assertEquals(StageState.AVAILABLE, it.run)
                assertEquals(StageState.BLOCKED, it.replay)
            }
            // After a run: trace exists → run DONE, replay AVAILABLE.
            c.submit()
            val result = runBlocking { withTimeout(60_000) { c.lastResult.filterNotNull().first() } }
            assert(result is ksl.app.session.RunResult.Completed)
            c.workflowStatus().let {
                assertEquals(StageState.DONE, it.run)
                assertEquals(StageState.AVAILABLE, it.replay)
                assertContains(it.nextStep, "layout")
            }
            // After authoring a layout: layout DONE, hint points at Replay.
            c.newBlankLayout()
            c.addLayoutElement(ElementKind.RESOURCE, c.inventory.namesOf(ElementKind.RESOURCE).first())
            c.workflowStatus().let {
                assertEquals(StageState.DONE, it.layout)
                assertContains(it.nextStep, "Replay")
            }
        } finally { c.close(); ws.toFile().deleteRecursively() }
    }
}
