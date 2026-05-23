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

package ksl.app.swing.experiment

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
 *  Smoke test for [ExperimentAppFrame].  Verifies the Phase E4
 *  skeleton constructs without throwing, has the expected tab order,
 *  and exposes the expected menus.  Does not test interactive
 *  behaviour — later phases (E5–E10) own the tab contents and their
 *  respective tests.
 *
 *  Headless-safe: skipped automatically when the JVM is headless
 *  (Swing component construction fails without a display).
 */
class ExperimentAppFrameSmokeTest {

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
        var controller: ExperimentAppController? = null
        var frame: ExperimentAppFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                controller = ExperimentAppController("SmokeTestApp")
                frame = ExperimentAppFrame(controller!!)
            }
            assertNotNull(frame)
        } finally {
            SwingUtilities.invokeAndWait {
                frame?.dispose()        // disposes triggers controller.close() via window listener
                controller?.close()     // belt-and-braces if the listener didn't run yet
            }
        }
    }

    @Test
    fun `tab order matches the Phase E4 plan`() {
        var controller: ExperimentAppController? = null
        var frame: ExperimentAppFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                controller = ExperimentAppController("SmokeTestApp")
                frame = ExperimentAppFrame(controller!!)
            }
            val tabs: JTabbedPane = findFirstDescendant(frame!!, JTabbedPane::class.java)
                ?: error("No JTabbedPane found in frame")
            val titles = (0 until tabs.tabCount).map { tabs.getTitleAt(it) }
            assertEquals(
                listOf(
                    "Model",
                    "Factors",
                    "Design",
                    "Design Points",
                    "Regression",
                    "Comparison Analyzer",
                    "Reports"
                ),
                titles
            )
        } finally {
            SwingUtilities.invokeAndWait {
                frame?.dispose()
                controller?.close()
            }
        }
    }

    @Test
    fun `Factors tab activates without throwing after a model is selected and a factor is added`() {
        // Drives the same path a user would: select a model, add a
        // factor, switch to the Factors tab.  Catches construction
        // / wiring errors in the populated-card branch (the no-model
        // empty-state branch is exercised by the bare frame
        // instantiation test).
        var controller: ExperimentAppController? = null
        var frame: ExperimentAppFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                controller = ExperimentAppController("FactorsTabSmoke")
                frame = ExperimentAppFrame(controller!!)
            }
            SwingUtilities.invokeAndWait {
                controller!!.setModelReference(
                    ksl.app.config.ModelReference.ByBundleAndModelId(
                        bundleId = "ksl.examples.lk-inventory",
                        modelId = ksl.examples.general.appsupport.LKInventoryBundle.MODEL_ID
                    )
                )
                val descriptor = controller!!.currentModelDescriptor.value
                if (descriptor != null) {
                    val key = descriptor.controls.numericControls.first().keyName
                    controller!!.addFactor(
                        ksl.app.config.experiment.FactorSpec(
                            name = "F1",
                            levels = listOf(10.0, 30.0),
                            binding = ksl.app.config.experiment.ControlBinding.Control(key)
                        )
                    )
                }
                val tabs: JTabbedPane = findFirstDescendant(frame!!, JTabbedPane::class.java)
                    ?: error("No JTabbedPane found")
                tabs.selectedIndex = 1   // Factors tab
            }
            // If we got here without an exception, the Factors panel
            // wired its detail-editor + binding-picker collectors
            // cleanly.
        } finally {
            SwingUtilities.invokeAndWait {
                frame?.dispose()
                controller?.close()
            }
        }
    }

    @Test
    fun `Design tab activates without throwing across all four variant cards`() {
        // Exercises each DesignSpec variant in turn — catches
        // construction / wiring errors in the variant cards
        // (Full factorial, Two-level fractional, Central composite,
        // Manual).  The empty-state branch (no model, no factors) is
        // exercised by the bare frame instantiation test.
        var controller: ExperimentAppController? = null
        var frame: ExperimentAppFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                controller = ExperimentAppController("DesignTabSmoke")
                frame = ExperimentAppFrame(controller!!)
            }
            SwingUtilities.invokeAndWait {
                controller!!.setModelReference(
                    ksl.app.config.ModelReference.ByBundleAndModelId(
                        bundleId = "ksl.examples.lk-inventory",
                        modelId = ksl.examples.general.appsupport.LKInventoryBundle.MODEL_ID
                    )
                )
                val descriptor = controller!!.currentModelDescriptor.value
                if (descriptor != null) {
                    // Need >= 2 factors so the fractional variant can load.
                    val keys = descriptor.controls.numericControls.take(3).map { it.keyName }
                    for ((i, k) in keys.withIndex()) {
                        controller!!.addFactor(
                            ksl.app.config.experiment.FactorSpec(
                                name = "F${i + 1}",
                                levels = listOf(1.0, 2.0),
                                binding = ksl.app.config.experiment.ControlBinding.Control(k)
                            )
                        )
                    }
                }
                val tabs: JTabbedPane = findFirstDescendant(frame!!, JTabbedPane::class.java)
                    ?: error("No JTabbedPane found")
                tabs.selectedIndex = 2   // Design tab
            }
            // Walk each variant — each setDesignSpec triggers the
            // matching card's load(...) on the EDT.
            SwingUtilities.invokeAndWait {
                controller!!.setDesignSpec(
                    ksl.app.config.experiment.DesignSpec.FullFactorial(centerPoints = 2)
                )
            }
            SwingUtilities.invokeAndWait {
                if (controller!!.factors.value.size >= 2) {
                    val k = controller!!.factors.value.size
                    val rel = (0 until k).joinToString("") { ('A' + it).toString() }
                    controller!!.setDesignSpec(
                        ksl.app.config.experiment.DesignSpec.TwoLevelFractional(
                            numFactors = k,
                            fractionExponent = 1,
                            definingRelations = listOf(rel)
                        )
                    )
                }
            }
            SwingUtilities.invokeAndWait {
                controller!!.setDesignSpec(
                    ksl.app.config.experiment.DesignSpec.CentralComposite(
                        axialSpacing = 1.5, centerPoints = 3
                    )
                )
            }
            SwingUtilities.invokeAndWait {
                val factors = controller!!.factors.value
                if (factors.isNotEmpty()) {
                    val midpoint = factors.associate { f ->
                        f.name to ((f.levels.min() + f.levels.max()) / 2.0)
                    }
                    controller!!.setDesignSpec(
                        ksl.app.config.experiment.DesignSpec.Manual(
                            listOf(ksl.app.config.experiment.ManualPointSpec(midpoint))
                        )
                    )
                }
            }
            // Stream-policy round-trip
            SwingUtilities.invokeAndWait {
                controller!!.setStreamPolicy(
                    ksl.app.config.experiment.StreamPolicy.CommonRandomNumbers
                )
                controller!!.setStreamPolicy(
                    ksl.app.config.experiment.StreamPolicy.Independent(
                        startingStreamAdvance = 5,
                        streamAdvanceSpacing = 3
                    )
                )
            }
        } finally {
            SwingUtilities.invokeAndWait {
                frame?.dispose()
                controller?.close()
            }
        }
    }

    @Test
    fun `menu bar exposes File, Bundles, View`() {
        var controller: ExperimentAppController? = null
        var frame: ExperimentAppFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                controller = ExperimentAppController("SmokeTestApp")
                frame = ExperimentAppFrame(controller!!)
            }
            val bar = frame!!.jMenuBar
            assertNotNull(bar)
            val menuNames = (0 until bar.menuCount).map { bar.getMenu(it).text }
            assertTrue("File" in menuNames, "File menu missing; got $menuNames")
            assertTrue("Bundles" in menuNames, "Bundles menu missing; got $menuNames")
            assertTrue("View" in menuNames, "View menu missing; got $menuNames")

            val fileMenu = (0 until bar.menuCount)
                .map { bar.getMenu(it) }
                .first { it.text == "File" }
            val fileItemTexts = (0 until fileMenu.itemCount).mapNotNull { fileMenu.getItem(it)?.text }
            assertTrue("New Experiment" in fileItemTexts,
                "File → New Experiment missing; got $fileItemTexts")
            assertTrue(fileItemTexts.any { it.startsWith("Save Configuration") },
                "File → Save Configuration missing; got $fileItemTexts")
            assertTrue(fileItemTexts.any { it.startsWith("Open") },
                "File → Open Configuration missing; got $fileItemTexts")
        } finally {
            SwingUtilities.invokeAndWait {
                frame?.dispose()
                controller?.close()
            }
        }
    }

    /** Depth-first search through the component tree for the first
     *  descendant of the given class.  Used to extract the JTabbedPane
     *  without depending on internal field names. */
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
