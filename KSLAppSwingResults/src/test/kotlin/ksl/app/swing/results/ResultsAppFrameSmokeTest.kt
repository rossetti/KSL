/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.swing.results

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Smoke test for [ResultsAppFrame].  Verifies the frame constructs
 *  without throwing, has the expected tab order, and exposes the
 *  expected menus.  Does not test interactive behaviour — the analysis
 *  tabs and the controller's data path own their own tests.
 *
 *  Headless-safe: skipped automatically when the JVM is headless
 *  (Swing component construction fails without a display).
 */
class ResultsAppFrameSmokeTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun skipIfHeadless() {
            Assumptions.assumeFalse(
                GraphicsEnvironment.isHeadless(),
                "Headless JVM — Swing frame smoke test requires a display"
            )
        }
    }

    @Test
    fun `frame instantiates without throwing`() {
        var frame: ResultsAppFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                frame = ResultsAppFrame(ResultsAppController("SmokeTestApp"))
            }
            assertNotNull(frame)
        } finally {
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
    }

    @Test
    fun `tab order matches the R4 plan`() {
        var frame: ResultsAppFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                frame = ResultsAppFrame(ResultsAppController("SmokeTestApp"))
            }
            val tabs: JTabbedPane = findFirstDescendant(frame!!, JTabbedPane::class.java)
                ?: error("No JTabbedPane found in frame")
            val titles = (0 until tabs.tabCount).map { tabs.getTitleAt(it) }
            assertEquals(
                listOf(
                    "Database",
                    "Compare Experiments",
                    "Within-Replication",
                    "Time Series",
                    "Histograms & Frequencies",
                    "Experiment Summary"
                ),
                titles
            )
        } finally {
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
    }

    @Test
    fun `menu bar exposes File and Help`() {
        var frame: ResultsAppFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                frame = ResultsAppFrame(ResultsAppController("SmokeTestApp"))
            }
            val bar = frame!!.jMenuBar
            assertNotNull(bar)
            val menuNames = (0 until bar.menuCount).map { bar.getMenu(it).text }
            assertTrue("File" in menuNames, "File menu missing; got $menuNames")
            assertTrue("Help" in menuNames, "Help menu missing; got $menuNames")

            val fileMenu = (0 until bar.menuCount).map { bar.getMenu(it) }.first { it.text == "File" }
            val fileItemTexts = (0 until fileMenu.itemCount).mapNotNull { fileMenu.getItem(it)?.text }
            assertTrue("Open Database…" in fileItemTexts, "File → Open Database missing; got $fileItemTexts")
            assertTrue(
                fileItemTexts.any { it.startsWith("Set Working Directory") },
                "File → Set Working Directory missing; got $fileItemTexts"
            )
            assertTrue("Exit" in fileItemTexts, "File → Exit missing; got $fileItemTexts")
        } finally {
            SwingUtilities.invokeAndWait { frame?.dispose() }
        }
    }

    /** Depth-first search for the first descendant of the given class —
     *  extracts the JTabbedPane without depending on internal field names. */
    private fun <T : java.awt.Component> findFirstDescendant(
        root: java.awt.Container,
        type: Class<T>
    ): T? {
        for (child in root.components) {
            if (type.isInstance(child)) {
                @Suppress("UNCHECKED_CAST")
                return child as T
            }
            if (child is java.awt.Container) {
                val found = findFirstDescendant(child, type)
                if (found != null) return found
            }
        }
        return null
    }
}
