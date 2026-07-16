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
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Stage 1: the animation app routes its artifacts into the consistent workspace structure
 * `<workingDir>/KSLAnimation/<ModelName>/{configs,traces,layouts}`, writes a run's `.atf` into `traces/`,
 * and can enumerate existing traces and layouts. Headless.
 */
class WorkspaceLayoutTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            Model("TRWs").apply {
                numberOfReplications = 1
                lengthOfReplication = 60.0
                TestAndRepairShopWithMovableResources(this, "TR")
            }
    }

    private fun controller(ws: java.nio.file.Path) =
        AnimationAppController("Anim", builder).apply { workspaceOverride = ws }

    @Test
    fun `artifact directories follow the KSLAnimation per-model structure`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-ws")
        val c = controller(ws)
        try {
            assertEquals(ws.resolve("KSLAnimation"), c.appWorkspace)
            assertEquals(c.appWorkspace.resolve(c.modelName), c.modelWorkspace)
            assertEquals(c.modelWorkspace.resolve("configs"), c.configsDir)
            assertEquals(c.modelWorkspace.resolve("traces"), c.tracesDir)
            assertEquals(c.modelWorkspace.resolve("layouts"), c.layoutsDir)
        } finally {
            c.close(); ws.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a run writes its atf into traces and it is enumerable`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-ws")
        val c = controller(ws)
        try {
            assertTrue(c.listTraces().isEmpty(), "no traces before a run")
            c.submit()
            val result = runBlocking { withTimeout(60_000) { c.lastResult.filterNotNull().first() } }
            assertIs<RunResult.Completed>(result)

            val trace = c.lastTraceFile.value!!
            assertEquals(c.tracesDir, trace.parent, "trace lives in traces/")
            assertTrue(trace.fileName.toString().endsWith(".atf"))
            assertTrue(c.listTraces().contains(trace), "the produced trace is enumerated")
        } finally {
            c.close(); ws.toFile().deleteRecursively()
        }
    }

    @Test
    fun `layouts are enumerated from the layouts folder, newest first`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-ws")
        val c = controller(ws)
        try {
            assertTrue(c.listLayouts().isEmpty(), "no layouts before any are saved")
            Files.createDirectories(c.layoutsDir)
            val older = c.layoutsDir.resolve("floor.lay.json")
            val newer = c.layoutsDir.resolve("process.lay.json")
            Files.writeString(older, "{}")
            Files.setLastModifiedTime(older, java.nio.file.attribute.FileTime.fromMillis(1_000L))
            Files.writeString(newer, "{}")
            Files.setLastModifiedTime(newer, java.nio.file.attribute.FileTime.fromMillis(2_000L))
            // A non-layout file is ignored.
            Files.writeString(c.layoutsDir.resolve("notes.txt"), "x")

            val layouts = c.listLayouts()
            assertEquals(listOf(newer, older), layouts, "both .lay.json files, most-recent first")
        } finally {
            c.close(); ws.toFile().deleteRecursively()
        }
    }
}
