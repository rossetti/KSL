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

import ksl.app.config.ModelReference
import ksl.app.config.optimization.LinearConstraintSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationType
import ksl.app.config.optimization.PenaltyFunctionSpec
import ksl.app.config.optimization.ResponseConstraintSpec
import ksl.app.swing.simopt.stepper.Step
import ksl.examples.general.appsupport.MM1Bundle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Phase O4 controller-level tests for the decomposed problem-spec
 *  state and the per-input mutators.  Pure JVM tests — no Swing
 *  constructed.
 */
class SimoptAppProblemTest {

    private val mm1BundleId = MM1Bundle().bundleId
    private val mm1ModelId = MM1Bundle.MODEL_ID
    private fun mm1Ref() = ModelReference.ByBundleAndModelId(mm1BundleId, mm1ModelId)

    private fun inputX() = OptimizationInputSpec(name = "x", lowerBound = 0.0, upperBound = 10.0)
    private fun inputY() = OptimizationInputSpec(name = "y", lowerBound = 1.0, upperBound = 5.0)
    private fun inputZ() = OptimizationInputSpec(name = "z", lowerBound = -1.0, upperBound = 1.0)

    @Test
    fun `default state has null objective and empty inputs`() {
        SimoptAppController("Test").use { c ->
            assertNull(c.objectiveResponseName.value)
            assertTrue(c.inputs.value.isEmpty())
            assertNull(c.problemSpec.value)
            assertEquals(-1, c.selectedInputIndex.value)
        }
    }

    @Test
    fun `setting objective alone leaves problemSpec null`() {
        SimoptAppController("Test").use { c ->
            c.setObjectiveResponseName("Cost")
            assertEquals("Cost", c.objectiveResponseName.value)
            // Substrate requires non-empty inputs — consolidated spec stays null.
            assertNull(c.problemSpec.value)
        }
    }

    @Test
    fun `objective plus one input publishes problemSpec`() {
        SimoptAppController("Test").use { c ->
            c.setObjectiveResponseName("Cost")
            c.addInput(inputX())
            val spec = c.problemSpec.value
            assertNotNull(spec)
            assertEquals("Cost", spec.objectiveResponseName)
            assertEquals(1, spec.inputs.size)
            assertEquals("x", spec.inputs[0].name)
        }
    }

    @Test
    fun `addInput rejects duplicate names`() {
        SimoptAppController("Test").use { c ->
            c.addInput(inputX())
            val ex = assertThrows<IllegalArgumentException> {
                c.addInput(inputX())
            }
            assertTrue("already exists" in (ex.message ?: ""),
                "Expected duplicate-name message; got '${ex.message}'")
        }
    }

    @Test
    fun `updateInput rejects index out of range`() {
        SimoptAppController("Test").use { c ->
            c.addInput(inputX())
            assertThrows<IllegalArgumentException> { c.updateInput(5, inputY()) }
        }
    }

    @Test
    fun `updateInput rejects a name collision with a different input`() {
        SimoptAppController("Test").use { c ->
            c.addInput(inputX())
            c.addInput(inputY())
            // Try to rename y to x.
            val collision = inputY().copy(name = "x")
            assertThrows<IllegalArgumentException> {
                c.updateInput(1, collision)
            }
        }
    }

    @Test
    fun `updateInput accepts renaming to the same row's existing name`() {
        SimoptAppController("Test").use { c ->
            c.addInput(inputX())
            // Updating row 0 with the same name + new bounds is allowed.
            c.updateInput(0, inputX().copy(lowerBound = -5.0))
            assertEquals(-5.0, c.inputs.value[0].lowerBound)
        }
    }

    @Test
    fun `deleteInput shifts selectedInputIndex sensibly`() {
        SimoptAppController("Test").use { c ->
            c.addInput(inputX())
            c.addInput(inputY())
            c.addInput(inputZ())
            // Add selects the new row each time, so selected = 2.
            assertEquals(2, c.selectedInputIndex.value)

            c.deleteInput(2)
            assertEquals(1, c.selectedInputIndex.value)
            c.deleteInput(1)
            assertEquals(0, c.selectedInputIndex.value)
            c.deleteInput(0)
            assertEquals(-1, c.selectedInputIndex.value)
            assertTrue(c.inputs.value.isEmpty())
        }
    }

    @Test
    fun `moveInputUp and moveInputDown reorder correctly`() {
        SimoptAppController("Test").use { c ->
            c.addInput(inputX())
            c.addInput(inputY())
            c.addInput(inputZ())
            assertEquals(listOf("x", "y", "z"), c.inputs.value.map { it.name })

            c.setSelectedInputIndex(2)
            c.moveInputUp(2)
            assertEquals(listOf("x", "z", "y"), c.inputs.value.map { it.name })
            assertEquals(1, c.selectedInputIndex.value)

            c.moveInputDown(0)
            assertEquals(listOf("z", "x", "y"), c.inputs.value.map { it.name })
        }
    }

    @Test
    fun `moveInputUp at index 0 is a no-op`() {
        SimoptAppController("Test").use { c ->
            c.addInput(inputX())
            c.addInput(inputY())
            c.moveInputUp(0)
            assertEquals(listOf("x", "y"), c.inputs.value.map { it.name })
        }
    }

    @Test
    fun `moveInputDown at last index is a no-op`() {
        SimoptAppController("Test").use { c ->
            c.addInput(inputX())
            c.addInput(inputY())
            c.moveInputDown(1)
            assertEquals(listOf("x", "y"), c.inputs.value.map { it.name })
        }
    }

    @Test
    fun `setOptimizationType updates the consolidated spec when complete`() {
        SimoptAppController("Test").use { c ->
            c.setObjectiveResponseName("Cost")
            c.addInput(inputX())
            assertEquals(OptimizationType.MINIMIZE, c.problemSpec.value?.optimizationType)
            c.setOptimizationType(OptimizationType.MAXIMIZE)
            assertEquals(OptimizationType.MAXIMIZE, c.problemSpec.value?.optimizationType)
        }
    }

    @Test
    fun `setIndifferenceZoneParameter rejects negative values`() {
        SimoptAppController("Test").use { c ->
            assertThrows<IllegalArgumentException> { c.setIndifferenceZoneParameter(-0.1) }
            assertThrows<IllegalArgumentException> { c.setIndifferenceZoneParameter(Double.NaN) }
            assertThrows<IllegalArgumentException> { c.setIndifferenceZoneParameter(Double.POSITIVE_INFINITY) }
        }
    }

    @Test
    fun `setObjectiveGranularity rejects negative values`() {
        SimoptAppController("Test").use { c ->
            assertThrows<IllegalArgumentException> { c.setObjectiveGranularity(-0.5) }
            assertThrows<IllegalArgumentException> { c.setObjectiveGranularity(Double.NaN) }
        }
    }

    @Test
    fun `setProblemSpec(null) clears every piece`() {
        SimoptAppController("Test").use { c ->
            c.setObjectiveResponseName("Cost")
            c.addInput(inputX())
            c.setProblemName("MyProblem")
            c.setIndifferenceZoneParameter(0.5)
            assertNotNull(c.problemSpec.value)

            c.setProblemSpec(null)
            assertNull(c.objectiveResponseName.value)
            assertTrue(c.inputs.value.isEmpty())
            assertNull(c.problemName.value)
            assertEquals(0.0, c.indifferenceZoneParameter.value)
            assertNull(c.problemSpec.value)
        }
    }

    @Test
    fun `setProblemSpec(spec) fans out into the pieces`() {
        SimoptAppController("Test").use { c ->
            val spec = OptimizationProblemSpec(
                problemName = "PlugIn",
                objectiveResponseName = "Cost",
                inputs = listOf(inputX(), inputY()),
                responseNames = listOf("FillRate", "Backorders"),
                optimizationType = OptimizationType.MAXIMIZE,
                indifferenceZoneParameter = 0.5,
                objectiveGranularity = 0.0,
                linearConstraints = listOf(
                    LinearConstraintSpec(coefficients = mapOf("x" to 1.0, "y" to 1.0), rhsValue = 5.0)
                ),
                responseConstraints = listOf(
                    ResponseConstraintSpec(name = "FillRate", rhsValue = 0.95)
                ),
                defaultLinearPenalty = PenaltyFunctionSpec.DynamicPolynomial(basePenalty = 200.0),
                defaultResponsePenalty = PenaltyFunctionSpec.WithMemory(basePenalty = 75.0)
            )
            c.setProblemSpec(spec)
            assertEquals("Cost", c.objectiveResponseName.value)
            assertEquals(2, c.inputs.value.size)
            assertEquals(listOf("FillRate", "Backorders"), c.responseNames.value)
            assertEquals(OptimizationType.MAXIMIZE, c.optimizationType.value)
            assertEquals(0.5, c.indifferenceZoneParameter.value)
            assertEquals(1, c.linearConstraints.value.size)
            assertEquals(1, c.responseConstraints.value.size)
            assertEquals(spec, c.problemSpec.value)
        }
    }

    @Test
    fun `Problem step completion gates on objective plus inputs`() {
        SimoptAppController("Test").use { c ->
            c.setModelReference(mm1Ref())
            assertFalse(c.canAdvanceTo(Step.ALGORITHM))
            c.setObjectiveResponseName("Cost")
            assertFalse(c.canAdvanceTo(Step.ALGORITHM), "Objective alone is not enough")
            c.addInput(inputX())
            assertTrue(c.canAdvanceTo(Step.ALGORITHM))
        }
    }

    @Test
    fun `addInput marks document dirty plus drops lastResult`() {
        SimoptAppController("Test").use { c ->
            assertFalse(c.isDirty.value)
            c.addInput(inputX())
            assertTrue(c.isDirty.value)
            assertTrue(c.editedSinceLastRun.value)
        }
    }

    @Test
    fun `setResponseNames is reflected in the consolidated spec`() {
        SimoptAppController("Test").use { c ->
            c.setObjectiveResponseName("Cost")
            c.addInput(inputX())
            c.setResponseNames(listOf("Resp1", "Resp2"))
            assertEquals(listOf("Resp1", "Resp2"), c.problemSpec.value?.responseNames)
        }
    }

    @Test
    fun `selectedInputIndex setter clamps out-of-range values`() {
        SimoptAppController("Test").use { c ->
            c.addInput(inputX())
            c.addInput(inputY())
            c.setSelectedInputIndex(99)
            assertEquals(1, c.selectedInputIndex.value)
            c.setSelectedInputIndex(-5)
            assertEquals(-1, c.selectedInputIndex.value)
        }
    }
}
