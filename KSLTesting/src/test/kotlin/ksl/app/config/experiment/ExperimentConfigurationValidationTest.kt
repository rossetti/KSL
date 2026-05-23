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

package ksl.app.config.experiment

import ksl.app.config.ModelReference
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  `init`-time validation checks for [ExperimentConfiguration].
 *  Validation that depends on the model's actual control / RV-parameter
 *  surface lives in Phase E2 (engine glue) and is not exercised here.
 */
class ExperimentConfigurationValidationTest {

    private val model = ModelReference.Embedded("MM1")

    @Test
    fun `factors list must be non-empty`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ExperimentConfiguration(
                modelReference = model,
                factors = emptyList(),
                designSpec = DesignSpec.FullFactorial
            )
        }
        assertTrue(ex.message!!.contains("at least one factor"))
    }

    @Test
    fun `duplicate factor names are rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ExperimentConfiguration(
                modelReference = model,
                factors = listOf(
                    factor("A", listOf(0.0, 1.0)),
                    factor("A", listOf(2.0, 3.0))
                ),
                designSpec = DesignSpec.FullFactorial
            )
        }
        assertTrue(ex.message!!.contains("unique"))
    }

    @Test
    fun `factor with fewer than 2 levels is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ExperimentConfiguration(
                modelReference = model,
                factors = listOf(factor("A", listOf(1.0))),
                designSpec = DesignSpec.FullFactorial
            )
        }
        assertTrue(ex.message!!.contains("at least 2 levels"))
    }

    @Test
    fun `factor with duplicate level values is rejected at FactorSpec init time`() {
        // Strictly-increasing precondition (added in E7.4) catches
        // both duplicates and out-of-order entries with a single
        // message, before the spec ever reaches ExperimentConfiguration.
        val ex = assertFailsWith<IllegalArgumentException> {
            factor("A", listOf(1.0, 1.0))
        }
        assertTrue(
            ex.message!!.contains("strictly increasing"),
            "expected strictly-increasing message; got: ${ex.message}"
        )
    }

    @Test
    fun `factor with out-of-order levels is rejected at FactorSpec init time`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            factor("A", listOf(30.0, 10.0))
        }
        assertTrue(
            ex.message!!.contains("strictly increasing"),
            "expected strictly-increasing message; got: ${ex.message}"
        )
    }

    @Test
    fun `twoLevelFactorial requires exactly 2 levels per factor`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ExperimentConfiguration(
                modelReference = model,
                factors = listOf(
                    factor("A", listOf(0.0, 1.0)),
                    factor("B", listOf(0.0, 1.0, 2.0))     // 3 levels — illegal
                ),
                designSpec = DesignSpec.TwoLevelFactorial(
                    fraction = Fraction.HalfFraction()
                )
            )
        }
        assertTrue(ex.message!!.contains("exactly 2 levels per factor"))
    }

    @Test
    fun `twoLevelFactorial with custom fraction rejects invalid defining relation`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ExperimentConfiguration(
                modelReference = model,
                factors = listOf(
                    factor("A", listOf(0.0, 1.0)),
                    factor("B", listOf(0.0, 1.0)),
                    factor("C", listOf(0.0, 1.0))
                ),
                designSpec = DesignSpec.TwoLevelFactorial(
                    fraction = Fraction.Custom(words = listOf(listOf(1, 2, 26)))   // 26 beyond k=3
                )
            )
        }
        assertTrue(ex.message!!.contains("defining relation is invalid"))
    }

    @Test
    fun `centralComposite requires exactly 2 levels per factor`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ExperimentConfiguration(
                modelReference = model,
                factors = listOf(
                    factor("A", listOf(0.0, 1.0)),
                    factor("B", listOf(0.0, 1.0, 2.0))     // 3 levels — illegal
                ),
                designSpec = DesignSpec.CentralComposite(
                    axialSpacing = AxialSpacing.Explicit(1.414)
                )
            )
        }
        assertTrue(ex.message!!.contains("exactly 2 levels per factor"))
    }

    @Test
    fun `manual point keys must match the document's factor names`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            ExperimentConfiguration(
                modelReference = model,
                factors = listOf(
                    factor("A", listOf(0.0, 1.0)),
                    factor("B", listOf(0.0, 1.0))
                ),
                designSpec = DesignSpec.Manual(
                    points = listOf(
                        ManualPointSpec(factorValues = mapOf("A" to 0.5))   // missing 'B'
                    )
                )
            )
        }
        assertTrue(ex.message!!.contains("must match the document's factor names"))
    }

    @Test
    fun `well-formed minimal configuration is accepted`() {
        // Smoke check that the validator doesn't reject a vanilla
        // 2-factor full factorial.
        ExperimentConfiguration(
            modelReference = model,
            factors = listOf(
                factor("A", listOf(0.0, 1.0)),
                factor("B", listOf(0.0, 1.0))
            ),
            designSpec = DesignSpec.FullFactorial
        )
    }

    // ── Fixtures ──────────────────────────────────────────────────────

    private fun factor(name: String, levels: List<Double>): FactorSpec =
        FactorSpec(
            name = name,
            levels = levels,
            binding = ControlBinding.Control("$name.value")
        )
}
