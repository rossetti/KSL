package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.animation.AnimationEvent
import ksl.animation.CaptureMode
import ksl.animation.ElementKind
import ksl.animation.TraceFileReader
import ksl.app.session.RunResult
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verifies 9D.1: the controller's run wiring actually produces a `.atf` trace — written into the model's
 * own workspace subfolder, honoring the authored capture spec. Headless (a real simulation needs no display).
 */
class AnimationAppRunTest {

    private val builder = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val m = Model("TRRun", autoCSVReports = false)
            m.numberOfReplications = 1
            m.lengthOfReplication = 200.0
            TestAndRepairShopWithMovableResources(m, "TR")
            return m
        }
    }

    @Test
    fun `submit produces a selective trace in the model workspace`() {
        val workspace = Files.createTempDirectory("anim-ws")
        val controller = AnimationAppController("TRApp", builder)
        controller.workspaceOverride = workspace
        try {
            controller.setCaptureMode(CaptureMode.SELECTED)
            controller.addInclude(ElementKind.RESOURCE, "Test1")
            controller.submit()

            val result = runBlocking { withTimeout(60_000) { controller.lastResult.filterNotNull().first() } }
            assertIs<RunResult.Completed>(result)

            val trace = controller.lastTraceFile.value
            assertNotNull(trace, "a completed run records the produced trace file")
            assertTrue(Files.exists(trace), "the .atf file exists")
            assertTrue(trace.startsWith(workspace), "the trace lives under the app workspace: $trace")

            val events = TraceFileReader.readAll(trace).second
            val resourceStates = events.filterIsInstance<AnimationEvent.ResourceStateChanged>()
                .map { it.resourceName }.toSet()
            assertTrue("Test1" in resourceStates, "the included resource is captured; got $resourceStates")
            assertTrue("DiagnosticWorkers" !in resourceStates, "an unselected resource is not captured; got $resourceStates")
            // Lifecycle framing is present (retained regardless of selective capture).
            assertTrue(events.any { it is AnimationEvent.ReplicationStarted }, "expected the replication marker")
        } finally {
            controller.close()
            workspace.toFile().deleteRecursively()
        }
    }
}
