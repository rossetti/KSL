package ksl.app.swing.animation.app

import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.examples.general.animationbundle.Example12StemFairStorage
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * #15 follow-up: named delays aren't structural, so they never appear in the [AnimationInventory]. After a run,
 * [AnimationAppController.suspensionNamesFromLastTrace] harvests them from the produced trace's `DelayStarted`
 * events so the Storage tool can offer them. Example 12 names a delay `"ConversationArea"`.
 */
class SuspensionNameTraceTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Example12StemFairStorage.buildModel()
    }

    @Test
    fun `named suspensions are harvested from the last trace`() {
        val workspace = Files.createTempDirectory(tempRoot, "anim-suspname")
        val controller = AnimationAppController("StemFair", builder).apply { workspaceOverride = workspace }

        // No run yet: nothing to harvest.
        assertTrue(controller.suspensionNamesFromLastTrace().isEmpty(), "no trace should yield no names")

        controller.submit()
        runBlocking { withTimeout(60_000) { controller.lastResult.filterNotNull().first() } }

        val names = controller.suspensionNamesFromLastTrace()
        assertTrue("ConversationArea" in names, "expected the named delay in harvested suspensions: $names")
    }
}
