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

import ksl.app.config.optimization.InequalityType
import ksl.app.config.optimization.LinearConstraintSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationRunConfigurationToml
import ksl.app.config.optimization.PenaltyFunctionSpec
import ksl.app.config.optimization.ResponseConstraintSpec
import ksl.app.swing.simopt.stepper.Step
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Phase O5 controller-level tests for the Constraints step:
 *  declared-responses chip-row mutators, linear / response constraint
 *  mutators, penalty default mutators, and a full round-trip of a
 *  populated problem (constraints + per-constraint overrides + custom
 *  defaults) through the TOML codec.
 */
class SimoptAppProblemConstraintsTest {

    @TempDir
    lateinit var bundleDir: Path

    /** MM1 model bundle JAR, assembled once per test. */
    private val mm1Jar: Path by lazy { SimoptBundleFixtures.mm1Jar(bundleDir) }

    /** A fresh controller backed by an injected library holding the MM1 bundle.
     *  Each call builds its own library; the controller closes it on `use {}` exit. */
    private fun controller() =
        SimoptAppController("Test", injectedBundleLibrary = SimoptBundleFixtures.library(mm1Jar))

    private val mm1BundleId = SimoptBundleFixtures.MM1_BUNDLE_ID
    private val mm1ModelId = SimoptBundleFixtures.MM1_MODEL_ID
    private fun mm1Ref() = ksl.app.config.ModelReference.ByBundleAndModelId(mm1BundleId, mm1ModelId)

    private fun seedMm1ProblemMinimum(c: SimoptAppController) {
        c.setModelReference(mm1Ref())
        val descriptor = c.currentModelDescriptor.value
        assertNotNull(descriptor)
        c.setObjectiveResponseName(descriptor.responseNames.first())
        c.addInput(OptimizationInputSpec("x", 0.0, 10.0))
        c.addInput(OptimizationInputSpec("y", 1.0, 5.0))
    }

    // ── Declared responses ───────────────────────────────────────────────

    @Test
    fun `addResponseName is idempotent`() {
        controller().use { c ->
            c.addResponseName("FillRate")
            c.addResponseName("FillRate")
            assertEquals(listOf("FillRate"), c.responseNames.value)
        }
    }

    @Test
    fun `addResponseName rejects blank`() {
        controller().use { c ->
            assertThrows<IllegalArgumentException> { c.addResponseName("") }
            assertThrows<IllegalArgumentException> { c.addResponseName("   ") }
        }
    }

    @Test
    fun `removeResponseName is a no-op for unknown names`() {
        controller().use { c ->
            c.addResponseName("FillRate")
            c.removeResponseName("Other")
            assertEquals(listOf("FillRate"), c.responseNames.value)
        }
    }

    // ── Linear constraints ───────────────────────────────────────────────

    @Test
    fun `addLinearConstraint appends and selects the new row`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            c.addLinearConstraint(
                LinearConstraintSpec(coefficients = mapOf("x" to 1.0), rhsValue = 5.0)
            )
            assertEquals(1, c.linearConstraints.value.size)
            assertEquals(0, c.selectedLinearConstraintIndex.value)
        }
    }

    @Test
    fun `updateLinearConstraint rejects index out of range`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            c.addLinearConstraint(LinearConstraintSpec(mapOf("x" to 1.0), 5.0))
            assertThrows<IllegalArgumentException> {
                c.updateLinearConstraint(7, LinearConstraintSpec(mapOf("x" to 1.0), 5.0))
            }
        }
    }

    @Test
    fun `deleteLinearConstraint clamps the selection`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            c.addLinearConstraint(LinearConstraintSpec(mapOf("x" to 1.0), 5.0))
            c.addLinearConstraint(LinearConstraintSpec(mapOf("y" to 1.0), 3.0))
            // Add selects last row (index 1).
            assertEquals(1, c.selectedLinearConstraintIndex.value)
            c.deleteLinearConstraint(1)
            assertEquals(0, c.selectedLinearConstraintIndex.value)
            c.deleteLinearConstraint(0)
            assertEquals(-1, c.selectedLinearConstraintIndex.value)
        }
    }

    @Test
    fun `moveLinearConstraintUp at 0 is a no-op`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            c.addLinearConstraint(LinearConstraintSpec(mapOf("x" to 1.0), 5.0))
            c.moveLinearConstraintUp(0)
            assertEquals(1, c.linearConstraints.value.size)
        }
    }

    @Test
    fun `addLinearConstraint marks dirty and drops lastResult`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            assertTrue(c.isDirty.value)  // already dirty from seed
            c.markSaved(Path.of("/tmp/dummy"))
            assertFalse(c.isDirty.value)
            c.addLinearConstraint(LinearConstraintSpec(mapOf("x" to 1.0), 5.0))
            assertTrue(c.isDirty.value)
        }
    }

    // ── Response constraints ─────────────────────────────────────────────

    @Test
    fun `addResponseConstraint auto-declares the referenced response name`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            assertTrue(c.responseNames.value.isEmpty())
            c.addResponseConstraint(ResponseConstraintSpec(name = "FillRate", rhsValue = 0.95))
            assertEquals(listOf("FillRate"), c.responseNames.value)
        }
    }

    @Test
    fun `addResponseConstraint rejects blank name`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            assertThrows<IllegalArgumentException> {
                c.addResponseConstraint(ResponseConstraintSpec(name = "", rhsValue = 0.0))
            }
        }
    }

    @Test
    fun `updateResponseConstraint auto-declares a new name`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            c.addResponseConstraint(ResponseConstraintSpec(name = "FillRate", rhsValue = 0.95))
            c.updateResponseConstraint(
                0, ResponseConstraintSpec(name = "Backorders", rhsValue = 10.0)
            )
            assertTrue("Backorders" in c.responseNames.value)
        }
    }

    @Test
    fun `deleteResponseConstraint clamps the selection`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            c.addResponseConstraint(ResponseConstraintSpec(name = "A", rhsValue = 1.0))
            c.addResponseConstraint(ResponseConstraintSpec(name = "B", rhsValue = 2.0))
            assertEquals(1, c.selectedResponseConstraintIndex.value)
            c.deleteResponseConstraint(1)
            assertEquals(0, c.selectedResponseConstraintIndex.value)
            c.deleteResponseConstraint(0)
            assertEquals(-1, c.selectedResponseConstraintIndex.value)
        }
    }

    @Test
    fun `moveResponseConstraintDown swaps neighbours and shifts selection`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            c.addResponseConstraint(ResponseConstraintSpec(name = "A", rhsValue = 1.0))
            c.addResponseConstraint(ResponseConstraintSpec(name = "B", rhsValue = 2.0))
            c.setSelectedResponseConstraintIndex(0)
            c.moveResponseConstraintDown(0)
            assertEquals("B", c.responseConstraints.value[0].name)
            assertEquals("A", c.responseConstraints.value[1].name)
            assertEquals(1, c.selectedResponseConstraintIndex.value)
        }
    }

    // ── Penalty defaults ─────────────────────────────────────────────────

    @Test
    fun `setDefaultLinearPenalty updates the consolidated spec when complete`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            val custom = PenaltyFunctionSpec.DynamicPolynomial(basePenalty = 250.0)
            c.setDefaultLinearPenalty(custom)
            assertEquals(custom, c.problemSpec.value?.defaultLinearPenalty)
        }
    }

    @Test
    fun `setDefaultResponsePenalty updates the consolidated spec when complete`() {
        controller().use { c ->
            seedMm1ProblemMinimum(c)
            val custom = PenaltyFunctionSpec.WithMemory(basePenalty = 75.0)
            c.setDefaultResponsePenalty(custom)
            assertEquals(custom, c.problemSpec.value?.defaultResponsePenalty)
        }
    }

    // ── Step gating ──────────────────────────────────────────────────────

    @Test
    fun `Constraints step is complete the moment Problem is complete`() {
        controller().use { c ->
            assertFalse(c.canAdvanceTo(Step.CONSTRAINTS),
                "Locked while Problem is incomplete")
            seedMm1ProblemMinimum(c)
            assertTrue(c.stepCompletion.value[Step.CONSTRAINTS] == true,
                "CONSTRAINTS marks complete as an alias of PROBLEM completion")
            assertTrue(c.canAdvanceTo(Step.CONSTRAINTS))
            assertTrue(c.canAdvanceTo(Step.ALGORITHM),
                "ALGORITHM unlocks via Problem completion (Constraints is optional)")
        }
    }

    // ── TOML round-trip ──────────────────────────────────────────────────

    @Test
    fun `full problem round-trips through TOML - constraints plus per-constraint overrides plus custom defaults`(
        @TempDir tempDir: Path
    ) {
        val target = tempDir.resolve("populated.toml")
        controller().use { writer ->
            seedMm1ProblemMinimum(writer)
            writer.addResponseName("Backorders")
            writer.addResponseConstraint(
                ResponseConstraintSpec(
                    name = "FillRate",
                    rhsValue = 0.95,
                    inequalityType = InequalityType.GREATER_THAN,
                    target = 0.97,
                    tolerance = 0.01,
                    penaltyFunction = PenaltyFunctionSpec.WithMemory(basePenalty = 50.0)
                )
            )
            writer.addLinearConstraint(
                LinearConstraintSpec(
                    coefficients = mapOf("x" to 1.0, "y" to 2.0),
                    rhsValue = 8.0,
                    inequalityType = InequalityType.LESS_THAN,
                    penaltyFunction = PenaltyFunctionSpec.DynamicPolynomial(basePenalty = 300.0)
                )
            )
            writer.setDefaultLinearPenalty(PenaltyFunctionSpec.DynamicPolynomial(basePenalty = 150.0))
            writer.setDefaultResponsePenalty(PenaltyFunctionSpec.WithMemory(basePenalty = 80.0))
            // Pick a solver so the document is fully runnable.
            writer.setSolverSpec(
                ksl.app.config.optimization.SolverSpec.StochasticHillClimbing(
                    maxIterations = 1, replicationsPerEvaluation = 1
                )
            )
            writer.saveConfiguration(target)
        }

        controller().use { reader ->
            val result = reader.loadConfiguration(target)
            assertTrue(result is SimoptAppController.LoadResult.Success)

            // Round-trip equality on the consolidated spec.
            val decoded = OptimizationRunConfigurationToml.decode(target.toFile().readText())
            val problem = decoded.problem
            assertNotNull(problem)
            assertEquals(1, problem.responseConstraints.size)
            assertEquals(1, problem.linearConstraints.size)
            assertTrue("Backorders" in problem.responseNames)
            assertTrue("FillRate" in problem.responseNames,
                "Auto-declared by addResponseConstraint")
            assertEquals(0.97, problem.responseConstraints[0].target)
            assertEquals(0.01, problem.responseConstraints[0].tolerance)
            val rcPenalty = problem.responseConstraints[0].penaltyFunction
            assertTrue(rcPenalty is PenaltyFunctionSpec.WithMemory)
            assertEquals(50.0, (rcPenalty).basePenalty)
            val lcPenalty = problem.linearConstraints[0].penaltyFunction
            assertTrue(lcPenalty is PenaltyFunctionSpec.DynamicPolynomial)
            assertEquals(300.0, (lcPenalty).basePenalty)
            assertEquals(
                PenaltyFunctionSpec.DynamicPolynomial(basePenalty = 150.0),
                problem.defaultLinearPenalty
            )
            assertEquals(
                PenaltyFunctionSpec.WithMemory(basePenalty = 80.0),
                problem.defaultResponsePenalty
            )

            // Controller-decomposed pieces also round-trip.
            assertEquals(1, reader.responseConstraints.value.size)
            assertEquals(1, reader.linearConstraints.value.size)
            assertTrue("Backorders" in reader.responseNames.value)
            assertTrue("FillRate" in reader.responseNames.value)
        }
    }
}
