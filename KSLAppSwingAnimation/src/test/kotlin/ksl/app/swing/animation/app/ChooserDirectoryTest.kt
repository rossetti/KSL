package ksl.app.swing.animation.app

import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * U1: file choosers open in the model's own folders even before anything is saved (the dirs are created
 * on demand), instead of silently falling back to the user home.
 */
class ChooserDirectoryTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            Model("TRChooser").also { TestAndRepairShopWithMovableResources(it, "TR") }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    fun `layout choosers start in the created layouts folder`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-chooser")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            // Nothing saved yet → the folder does not exist on disk.
            assertTrue(!Files.exists(c.layoutsDir))
            val dir = onEdt { LayoutPanel(c).chooserStartDirForTest() }
            assertEquals(c.layoutsDir, dir)
            assertTrue(Files.isDirectory(dir), "the chooser start dir is created so it actually opens there")
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }

    @Test
    fun `replay browse choosers start in the created traces and layouts folders`() {
        val ws = Files.createTempDirectory(tempRoot, "anim-chooser")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            val dirs = onEdt {
                val panel = ReplayPanel(c)
                panel.traceChooserDirForTest() to panel.layoutChooserDirForTest()
            }
            assertEquals(c.tracesDir, dirs.first)
            assertEquals(c.layoutsDir, dirs.second)
            assertTrue(Files.isDirectory(dirs.first) && Files.isDirectory(dirs.second))
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }

    @Test
    @DisplayName("exporting HTML starts in output, not among the traces it was made from")
    fun exportChooserStartsInOutput() {
        val ws = Files.createTempDirectory(tempRoot, "anim-export-dir")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            val dir = onEdt { ReplayPanel(c).exportChooserDirForTest() }
            assertEquals(c.outputDir, dir, "an export is a product, so it belongs with the products")
            assertTrue(dir != c.tracesDir, "and specifically not in with its own input")
            assertTrue(Files.isDirectory(dir), "created, so the chooser actually opens there")
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }
}
