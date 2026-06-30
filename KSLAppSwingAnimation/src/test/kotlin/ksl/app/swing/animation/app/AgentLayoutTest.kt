package ksl.app.swing.animation.app

import ksl.animation.SpatialSpaceDescriptor
import ksl.examples.general.animationbundle.Example03GridEpidemic
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 10.8 (§7.2): auto-layout for an agent model must place its space so grid agents (which emit (col,row)
 * cells) frame at a sensible cell size instead of collapsing into a blob. §7.1: the space list is legible.
 */
class AgentLayoutTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(c: Map<String, String>?, e: ExperimentRunParametersIfc?): Model =
            Example03GridEpidemic.buildModel().apply { numberOfReplications = 1; lengthOfReplication = 10.0 }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `agent auto-layout places the grid space with a positive display cell size`() {
        val c = AnimationAppController("agent", builder).apply { workspaceOverride = Files.createTempDirectory(tempRoot, "agentlay") }
        try {
            val layout = c.buildScaffoldLayout()
            assertNotNull(layout, "scaffold built")
            val grid = layout.spaces.filterIsInstance<SpatialSpaceDescriptor.Grid>().firstOrNull()
            assertNotNull(grid, "the agent grid space is placed in the scaffold: ${layout.spaces}")
            assertTrue(grid.cols > 0 && grid.rows > 0, "grid carries its cols×rows")
            assertTrue(grid.cellSize > 0.0, "a positive display cell size frames the grid")
        } finally { c.close() }
    }

    @Test
    fun `space list shows the grid's dimensions`() {
        val c = AnimationAppController("agent", builder).apply { workspaceOverride = Files.createTempDirectory(tempRoot, "agentlay2") }
        try {
            val shown = onEdt {
                val panel = LayoutPanel(c)
                c.scaffoldLayout()       // places the agent grid space
                panel.refreshForTest()
                panel.spaceListForTest()
            }
            assertTrue(shown.any { it.contains("grid") && it.contains("×") }, "space list shows grid dims: $shown")
        } finally { c.close() }
    }
}
