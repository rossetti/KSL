package ksl.app.swing.animation.app

import ksl.examples.book.chapter8.TestAndRepairShopWithMovableResources
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The replay controls must survive a narrow window.
 *
 * `FlowLayout` wraps its children into rows when the width is squeezed but reports a **single row** as its
 * preferred size. Inside a `BorderLayout` region the parent then allots one row's height and everything on
 * the wrapped rows is drawn outside it — the trailing controls do not move down, they disappear. In this
 * panel those were Zoom +, Zoom − and Fit: the three that get a lost view back, gone exactly when a cramped
 * window makes you want them.
 *
 * `WrapLayout` reports a preferred size that accounts for the wrapping, which is why the toolbar above
 * already used it. The view bar did not, ten lines away in the same file.
 *
 * Asserting on bounds rather than on the layout manager's class is deliberate: the next panel to get this
 * wrong will get it wrong in some other way, and what matters is that a user can still reach the control.
 */
class ViewBarWrapTest {

    @TempDir
    lateinit var tempRoot: Path

    private val builder = object : ModelBuilderIfc {
        override fun build(modelConfiguration: Map<String, String>?, experimentRunParameters: ExperimentRunParametersIfc?): Model =
            Model("TRWrap").apply {
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

    private fun buttons(root: JComponent): List<JButton> {
        val found = ArrayList<JButton>()
        fun walk(c: java.awt.Container) {
            for (child in c.components) {
                if (child is JButton) found.add(child)
                if (child is java.awt.Container) walk(child)
            }
        }
        walk(root)
        return found
    }

    /** Lays the panel out at [width] the way a real frame would, then reports every button's placement. */
    private fun buttonsAt(width: Int): List<Pair<String, Boolean>> {
        val ws = Files.createTempDirectory(tempRoot, "anim-wrap")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            return onEdt {
                val panel = ReplayPanel(c)
                val host = JPanel(java.awt.BorderLayout()).apply {
                    add(panel, java.awt.BorderLayout.CENTER)
                    size = Dimension(width, 700)
                    preferredSize = size
                }
                host.doLayout()
                // Two passes: the first gives the wrapping layout a width to wrap against, the second lets
                // the enclosing BorderLayout react to the height it then asks for.
                repeat(2) { layOutDeeply(host) }
                buttons(panel).map { b ->
                    // Against its OWN parent, because that is what clips it. Measuring against the whole
                    // ReplayPanel is what made the first version of this test pass over the very bug it
                    // was written for: a button wrapped onto a second row is outside the toolbar but still
                    // well inside the 700px panel, so it looked fine and was invisible in the app.
                    val parent = b.parent
                    val fits = parent != null && b.width > 0 && b.height > 0 &&
                        b.x >= 0 && b.y >= 0 &&
                        b.x + b.width <= parent.width && b.y + b.height <= parent.height
                    (b.text ?: "?") to fits
                }
            }
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }

    private fun layOutDeeply(c: java.awt.Container) {
        c.doLayout()
        for (child in c.components) if (child is java.awt.Container) layOutDeeply(child)
    }

    private fun JButton.locationOnScreenSafe(within: JComponent): Pair<Int, Int> {
        var x = 0
        var y = 0
        var cur: java.awt.Component? = this
        while (cur != null && cur !== within) {
            x += cur.x
            y += cur.y
            cur = cur.parent
        }
        return x to y
    }

    @Test
    @DisplayName("the view controls stay reachable when the window is narrow")
    fun viewControlsSurviveANarrowWindow() {
        val wide = buttonsAt(1400).filter { it.first in VIEW_CONTROLS }
        assertTrue(wide.isNotEmpty(), "the view controls must exist at all: found ${buttonsAt(1400).map { it.first }}")
        assertTrue(wide.all { it.second }, "at 1400px everything should already be visible: $wide")

        val narrow = buttonsAt(640).filter { it.first in VIEW_CONTROLS }
        val lost = narrow.filterNot { it.second }.map { it.first }
        assertTrue(
            lost.isEmpty(),
            "these view controls fall outside the panel at 640px wide and cannot be clicked: $lost.\n" +
                "A toolbar in a BorderLayout region needs WrapLayout; FlowLayout reports one row and the " +
                "rest is drawn out of bounds."
        )
    }

    @Test
    @DisplayName("navigating the view is a separate row from choosing what is drawn")
    fun navigationAndOverlaysAreNotMixed() {
        val ws = Files.createTempDirectory(tempRoot, "anim-rows")
        val c = AnimationAppController("Anim", builder).apply { workspaceOverride = ws }
        try {
            val rows = onEdt {
                val panel = ReplayPanel(c)
                // The row a control sits in, identified by its parent container.
                val zoom = buttons(panel).first { it.text == "Zoom +" }.parent
                val shows = ArrayList<java.awt.Container>()
                fun walk(x: java.awt.Container) {
                    for (child in x.components) {
                        if (child is javax.swing.JCheckBox && child.text.startsWith("Show")) shows.add(child.parent)
                        if (child is java.awt.Container) walk(child)
                    }
                }
                walk(panel)
                zoom to shows.distinct()
            }
            val (zoomRow, showRows) = rows
            assertTrue(showRows.isNotEmpty(), "the Show toggles must exist to be separated from anything")
            assertTrue(
                showRows.none { it === zoomRow },
                "Zoom/Fit/Pan share a row with the Show toggles. They answer different questions — what is " +
                    "drawn versus where you are looking — and mixed together the controls that recover a " +
                    "lost view are the hardest to find in the strip."
            )
        } finally { onEdt { c.close() }; ws.toFile().deleteRecursively() }
    }

    private companion object {
        /** The controls that get a lost view back — the ones whose disappearance is unrecoverable. */
        val VIEW_CONTROLS = setOf("Zoom +", "Zoom −", "Fit")
    }
}
