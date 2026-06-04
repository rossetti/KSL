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

package ksl.app.dist.validation

import ksl.app.dist.config.BootstrapConfig
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FitConfigurationValidatorTest {

    private fun inline(name: String = "x", vararg values: Double): DataSourceReference =
        DataSourceReference.Inline(mapOf(name to values))

    @Test
    fun `valid configuration produces no errors`() {
        val spec = FitSpec.Single(
            FitConfiguration(
                dataSource = inline("x", 1.0, 2.0, 3.0),
                estimatorIds = setOf("normal-mle", "exponential-mle"),
                scoringModelIds = setOf("anderson-darling")
            )
        )
        val result = FitConfigurationValidator.validate(spec)
        assertTrue(result.isValid)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `default empty id sets are valid`() {
        val spec = FitSpec.Single(FitConfiguration(dataSource = inline("x", 1.0, 2.0)))
        val result = FitConfigurationValidator.validate(spec)
        assertTrue(result.isValid)
    }

    @Test
    fun `valid bootstrap config passes`() {
        val spec = FitSpec.Single(
            FitConfiguration(
                dataSource = inline("x", 1.0, 2.0, 3.0),
                bootstrap = BootstrapConfig(sampleSize = 200, streamNumber = 2)
            )
        )
        assertTrue(FitConfigurationValidator.validate(spec).isValid)
    }

    @Test
    fun `non-positive bootstrap sample size is flagged`() {
        val spec = FitSpec.Single(
            FitConfiguration(
                dataSource = inline("x", 1.0, 2.0, 3.0),
                bootstrap = BootstrapConfig(sampleSize = 0)
            )
        )
        val result = FitConfigurationValidator.validate(spec)
        assertEquals(1, result.errors.size)
        assertEquals("fit.bootstrap.sampleSize", result.errors[0].code)
    }

    @Test
    fun `out-of-range bootstrap confidence level is flagged`() {
        val spec = FitSpec.Single(
            FitConfiguration(
                dataSource = inline("x", 1.0, 2.0, 3.0),
                bootstrap = BootstrapConfig(sampleSize = 100, level = 1.5)
            )
        )
        val result = FitConfigurationValidator.validate(spec)
        assertEquals(1, result.errors.size)
        assertEquals("fit.bootstrap.level", result.errors[0].code)
    }

    @Test
    fun `unknown estimator id is flagged`() {
        val spec = FitSpec.Single(
            FitConfiguration(
                dataSource = inline("x", 1.0, 2.0),
                estimatorIds = setOf("normal-mle", "made-up-estimator")
            )
        )
        val result = FitConfigurationValidator.validate(spec)
        assertEquals(1, result.errors.size)
        assertEquals("fit.estimator.unknown", result.errors[0].code)
        assertTrue(result.errors[0].message.contains("made-up-estimator"))
    }

    @Test
    fun `discrete estimator in continuous configuration is flagged`() {
        val spec = FitSpec.Single(
            FitConfiguration(
                dataSource = inline("x", 1.0, 2.0),
                kind = DistributionKind.CONTINUOUS,
                estimatorIds = setOf("poisson-mle")
            )
        )
        val result = FitConfigurationValidator.validate(spec)
        assertEquals(1, result.errors.size)
        assertEquals("fit.estimator.kindMismatch", result.errors[0].code)
    }

    @Test
    fun `unknown scoring model id is flagged`() {
        val spec = FitSpec.Single(
            FitConfiguration(
                dataSource = inline("x", 1.0, 2.0),
                scoringModelIds = setOf("anderson-darling", "made-up-score")
            )
        )
        val result = FitConfigurationValidator.validate(spec)
        assertEquals(1, result.errors.size)
        assertEquals("fit.scoringModel.unknown", result.errors[0].code)
    }

    @Test
    fun `empty inline dataset is flagged`() {
        val spec = FitSpec.Single(
            FitConfiguration(dataSource = DataSourceReference.Inline(mapOf("x" to DoubleArray(0))))
        )
        val result = FitConfigurationValidator.validate(spec)
        assertEquals(1, result.errors.size)
        assertEquals("fit.dataset.empty", result.errors[0].code)
    }

    @Test
    fun `empty inline reference is flagged`() {
        val spec = FitSpec.Single(
            FitConfiguration(dataSource = DataSourceReference.Inline(emptyMap()))
        )
        val result = FitConfigurationValidator.validate(spec)
        assertEquals(1, result.errors.size)
        assertEquals("fit.dataSource.empty", result.errors[0].code)
    }
}
