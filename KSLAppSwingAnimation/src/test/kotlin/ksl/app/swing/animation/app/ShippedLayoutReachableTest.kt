package ksl.app.swing.animation.app

import ksl.app.settings.WorkspaceLayout
import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
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
import kotlin.test.assertTrue

/**
 * The shipped layout must be reachable from the Layout **tab**, not only from the window's menu bar.
 *
 * It shipped in 0.3.0 as a menu item alone. The Layout tab carries the same seven actions as buttons a few
 * pixels from where the user is working, and on macOS the menu bar is at the top of the screen — so a person
 * looking at the tab concluded, reasonably, that the feature had not been built. It was reachable and
 * unfindable, which for a feature is much the same thing.
 *
 * The underlying cause is that the menu bar and the tab toolbars are two independent lists of actions with
 * no shared definition, so parity between them rests on memory. This test does not fix that; it pins the one
 * action whose absence was reported.
 */
class ShippedLayoutReachableTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            Model("TRShipped").apply {
                numberOfReplications = 1
                lengthOfReplication = 10.0
                TestAndRepairShopWithMovableResources(this, "TR")
            }
    }

    private fun <T> onEdt(block: () -> T): T {
        var r: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { r = runCatching(block) }
        return r.getOrThrow()
    }

    @Test
    @DisplayName("the Layout tab offers a shipped-layout button")
    fun theTabOffersTheAction() {
        val ws = Files.createTempDirectory(tempRoot, "anim-shipped")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            val (label, _) = onEdt { LayoutPanel(c).shippedButtonForTest() }
            assertEquals("Shipped", label, "the action must exist on the tab, not only in the menu bar")
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }

    @Test
    @DisplayName("it is disabled for a model whose bundle ships no layout")
    fun disabledWithoutAShippedLayout() {
        val ws = Files.createTempDirectory(tempRoot, "anim-noshipped")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            // An embedded model: no bundle, so no shipped layout can exist for it.
            val (_, enabled) = onEdt { LayoutPanel(c).shippedButtonForTest() }
            assertFalse(
                enabled,
                "a button that cannot do anything must not invite a click — and it defaults to enabled, so " +
                    "this has to be settled at construction rather than only when the layout next changes"
            )
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }

    @Test
    @DisplayName("the lookup the button depends on reads the installed layouts directory")
    fun theLookupFollowsTheInstalledLocation() {
        // The button is gated on WorkspaceLayout.builtinLayoutFor, which resolves through the
        // ksl.builtinLayouts property that an installed launcher sets. The same property the server
        // launcher failed to set, which is why that lookup is worth pinning independently of the UI.
        val root = Files.createDirectories(tempRoot.resolve("examples/layouts/edu.uark.ksl.demo"))
        Files.writeString(root.resolve("SomeModel.lay.toml"), "title = \"x\"\n")
        val property = WorkspaceLayout.BUILTIN_LAYOUTS_PROPERTY
        val previous = System.getProperty(property)
        try {
            System.setProperty(property, tempRoot.resolve("examples/layouts").toString())
            assertTrue(
                WorkspaceLayout.builtinLayoutFor("edu.uark.ksl.demo", "SomeModel") != null,
                "a shipped layout under the installed layouts root must be found"
            )
            assertTrue(
                WorkspaceLayout.builtinLayoutFor("edu.uark.ksl.demo", "NoSuchModel") == null,
                "and only for the model it names"
            )
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
}
