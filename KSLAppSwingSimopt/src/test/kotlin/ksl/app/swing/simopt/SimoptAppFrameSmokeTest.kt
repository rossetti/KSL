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

package ksl.app.swing.simopt

import ksl.app.swing.simopt.stepper.Step
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import javax.swing.JMenuItem
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Smoke test for [SimoptAppFrame].  Verifies the Phase O2 skeleton
 *  constructs without throwing, the controller exposes the expected
 *  initial state, and the file menu carries the expected items.
 *  Interactive behaviour (clicking menu items, step navigation under
 *  user input, etc.) is exercised in later-phase tests once those
 *  phases land their actual UIs.
 *
 *  Headless-safe: skipped automatically when the JVM is headless
 *  (Swing component construction fails without a display).
 */
class SimoptAppFrameSmokeTest {

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
        var controller: SimoptAppController? = null
        var frame: SimoptAppFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                controller = SimoptAppController("SmokeTestApp")
                frame = SimoptAppFrame(controller!!)
            }
            assertNotNull(frame)
        } finally {
            SwingUtilities.invokeAndWait {
                frame?.dispose()        // dispose triggers controller.close() via window listener
                controller?.close()     // belt-and-braces if the listener didn't run yet
            }
        }
    }

    @Test
    fun `controller starts at the Model step with only Model unlocked`() {
        val controller = SimoptAppController("SmokeTestApp")
        try {
            assertEquals(Step.MODEL, controller.activeStep.value)
            assertTrue(controller.canAdvanceTo(Step.MODEL))
            assertFalse(controller.canAdvanceTo(Step.PROBLEM))
            assertFalse(controller.canAdvanceTo(Step.ALGORITHM))
            assertFalse(controller.canAdvanceTo(Step.RUN_SETUP))
            assertFalse(controller.canAdvanceTo(Step.EXECUTE))
            assertFalse(controller.canAdvanceTo(Step.RESULTS))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `jumpToStep is a no-op for unreachable steps`() {
        val controller = SimoptAppController("SmokeTestApp")
        try {
            controller.jumpToStep(Step.PROBLEM)
            // Problem is locked at startup (no model) — active step
            // should still be MODEL.
            assertEquals(Step.MODEL, controller.activeStep.value)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `menu bar carries the expected File and Help items`() {
        var controller: SimoptAppController? = null
        var frame: SimoptAppFrame? = null
        try {
            SwingUtilities.invokeAndWait {
                controller = SimoptAppController("SmokeTestApp")
                frame = SimoptAppFrame(controller!!)
            }
            SwingUtilities.invokeAndWait {
                val menuBar = frame!!.jMenuBar
                assertNotNull(menuBar)
                val menuTexts = (0 until menuBar.menuCount).map { menuBar.getMenu(it).text }
                assertTrue(menuTexts.contains("File"), "File menu must be present; got $menuTexts")
                assertTrue(menuTexts.contains("Help"), "Help menu must be present; got $menuTexts")

                val fileMenu = (0 until menuBar.menuCount)
                    .map { menuBar.getMenu(it) }
                    .first { it.text == "File" }
                val items = (0 until fileMenu.itemCount).mapNotNull {
                    (fileMenu.getMenuComponent(it) as? JMenuItem)?.text
                }
                assertTrue(items.any { it.contains("New Optimization") },
                    "File menu should include 'New Optimization…'; got $items")
                assertTrue(items.any { it.contains("Open Optimization") },
                    "File menu should include 'Open Optimization…'; got $items")
                assertTrue(items.any { it.contains("Save Optimization") && !it.contains("As") },
                    "File menu should include 'Save Optimization'; got $items")
                assertTrue(items.any { it.contains("Save Optimization As") },
                    "File menu should include 'Save Optimization As…'; got $items")
                assertTrue(items.any { it == "Exit" }, "File menu should include 'Exit'; got $items")
            }
        } finally {
            SwingUtilities.invokeAndWait {
                frame?.dispose()
                controller?.close()
            }
        }
    }

    @Test
    fun `controller advances through gating as specs are set`() {
        val controller = SimoptAppController("SmokeTestApp")
        try {
            // Setting just the model template unlocks PROBLEM but not later steps.
            val model = ksl.app.config.ModelRunTemplate(
                modelReference = ksl.app.config.ModelReference.ByProviderId("dummy"),
                runParameters = ksl.simulation.Model("dummy").extractRunParameters()
            )
            controller.setModelTemplate(model)
            assertTrue(controller.canAdvanceTo(Step.PROBLEM))
            assertFalse(controller.canAdvanceTo(Step.ALGORITHM))

            // Setting a problem unlocks ALGORITHM.
            val problem = ksl.app.config.optimization.OptimizationProblemSpec(
                objectiveResponseName = "Cost",
                inputs = listOf(
                    ksl.app.config.optimization.OptimizationInputSpec("x", 0.0, 10.0)
                )
            )
            controller.setProblemSpec(problem)
            assertTrue(controller.canAdvanceTo(Step.ALGORITHM))
            assertFalse(controller.canAdvanceTo(Step.RUN_SETUP))

            // Setting a solver unlocks RUN_SETUP and EXECUTE (in O2 the
            // RUN_SETUP completion gate is purely structural).
            val solver = ksl.app.config.optimization.SolverSpec.StochasticHillClimbing(
                maxIterations = 1,
                replicationsPerEvaluation = 1
            )
            controller.setSolverSpec(solver)
            assertTrue(controller.canAdvanceTo(Step.RUN_SETUP))
            assertTrue(controller.canAdvanceTo(Step.EXECUTE))
            // RESULTS still locked — no run has completed.
            assertFalse(controller.canAdvanceTo(Step.RESULTS))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `setting a structural spec marks the document dirty`() {
        val controller = SimoptAppController("SmokeTestApp")
        try {
            assertFalse(controller.isDirty.value, "new document should start clean")
            val problem = ksl.app.config.optimization.OptimizationProblemSpec(
                objectiveResponseName = "Cost",
                inputs = listOf(
                    ksl.app.config.optimization.OptimizationInputSpec("x", 0.0, 10.0)
                )
            )
            controller.setProblemSpec(problem)
            assertTrue(controller.isDirty.value, "setProblemSpec must mark dirty")
            assertTrue(controller.editedSinceLastRun.value,
                "setProblemSpec must mark editedSinceLastRun")
        } finally {
            controller.close()
        }
    }
}
