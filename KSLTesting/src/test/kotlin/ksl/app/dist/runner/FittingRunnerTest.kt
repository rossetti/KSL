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

package ksl.app.dist.runner

import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FittingRunnerTest {

    private fun exponentialSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = ExponentialRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value }
    }

    private fun inlineConfig(name: String, data: DoubleArray): FitConfiguration =
        FitConfiguration(dataSource = DataSourceReference.Inline(mapOf(name to data)))

    @Test
    fun `fitting an exponential sample produces a coherent ranked report`() {
        val data = exponentialSample(mean = 2.5, n = 500, streamNumber = 11)
        val report = FittingRunner.fit(inlineConfig("expo", data))

        assertEquals("expo", report.datasetName)
        assertEquals(DistributionKind.CONTINUOUS, report.kind)
        assertEquals(500, report.dataSummary.n)
        assertTrue(report.fits.isNotEmpty())
        assertEquals(1, report.fits.first().rank)

        // The top recommendation must succeed and have a valid MODA score; we
        // do not assert which family wins because (e.g.) Weibull subsumes
        // Exponential and may rank above it on finite samples. We do assert
        // that the exponential family appears among the better successful
        // fits, which is the property the substrate must preserve.
        val top = report.fits.first()
        assertTrue(top.success)
        assertNotNull(top.weightedValue)
        assertTrue(top.weightedValue!! in 0.0..1.0)

        assertEquals(top.familyId, report.recommendedFamilyId)
        val expoRank = report.fits.firstOrNull { it.familyId == "exponential" && it.success }?.rank
        assertNotNull(expoRank, "exponential-mle should produce a successful fit on this sample")
        assertTrue(expoRank!! <= 3, "exponential should rank in the top 3; was $expoRank")
    }

    @Test
    fun `report fits are ranked starting from one with successful fits first`() {
        val data = exponentialSample(mean = 1.0, n = 300, streamNumber = 17)
        val report = FittingRunner.fit(inlineConfig("x", data))

        val ranks = report.fits.map { it.rank }
        assertEquals((1..report.fits.size).toList(), ranks)

        // Successes precede failures in the ordering produced by the runner.
        val successFlags = report.fits.map { it.success }
        val lastSuccessIndex = successFlags.indexOfLast { it }
        val firstFailureIndex = successFlags.indexOfFirst { !it }
        if (lastSuccessIndex >= 0 && firstFailureIndex >= 0) {
            assertTrue(lastSuccessIndex < firstFailureIndex)
        }
    }

    @Test
    fun `subset of estimators limits the result to those families`() {
        val data = exponentialSample(mean = 1.0, n = 200, streamNumber = 23)
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("x" to data)),
            estimatorIds = setOf("exponential-mle", "weibull-mle")
        )
        val report = FittingRunner.fit(config)

        val familyIds = report.fits.map { it.familyId }.toSet()
        assertEquals(setOf("exponential", "weibull"), familyIds)
        val estimatorIds = report.fits.map { it.estimatorId }.toSet()
        assertEquals(setOf("exponential-mle", "weibull-mle"), estimatorIds)
    }

    @Test
    fun `discrete kind with non-integer data is rejected`() {
        val data = exponentialSample(mean = 1.0, n = 50, streamNumber = 5) // continuous, non-integer
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("x" to data)),
            kind = DistributionKind.DISCRETE,
            estimatorIds = setOf("poisson-mle")
        )
        assertThrows<IllegalStateException> { FittingRunner.fit(config) }
    }

    @Test
    fun `multi-dataset inline source is rejected until the batch path lands`() {
        val a = exponentialSample(mean = 1.0, n = 50, streamNumber = 3)
        val b = exponentialSample(mean = 1.0, n = 50, streamNumber = 4)
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("a" to a, "b" to b))
        )
        assertThrows<IllegalStateException> { FittingRunner.fit(config) }
    }

    @Test
    fun `fits an embedded database source end to end`() {
        val dir = java.nio.file.Files.createTempDirectory("fit-db")
        val db = ksl.utilities.io.dbutil.SQLiteDb("fit.db", dir, deleteIfExists = true)
        db.executeCommand("CREATE TABLE samples (x REAL)")
        val rv = ExponentialRV(mean = 2.0, streamNum = 31)
        db.executeCommands((1..300).map { "INSERT INTO samples VALUES (${rv.value})" })
        val config = FitConfiguration(
            dataSource = DataSourceReference.Database(
                connection = ksl.app.dist.config.DatabaseConnectionRef(
                    dbType = ksl.app.dist.config.DbType.SQLITE,
                    location = dir.resolve("fit.db").toString()
                ),
                source = ksl.app.dist.config.DbSource.Table("samples"),
                layout = ksl.app.dist.config.DatasetLayout.WIDE
            ),
            estimatorIds = setOf("exponential-mle", "weibull-mle", "gamma-mle")
        )
        val report = FittingRunner.fit(config)
        assertEquals("x", report.datasetName)
        assertEquals(300, report.dataSummary.n)
        assertTrue(report.fits.any { it.success })
        assertNotNull(report.recommendedFamilyId)
    }

    @Test
    fun `fits a generated data source end to end`() {
        val config = FitConfiguration(
            dataSource = DataSourceReference.Generated(
                rvType = "Exponential",
                parameters = mapOf("mean" to 3.0),
                sampleSize = 400,
                streamNumber = 13,
                name = "synthetic"
            ),
            estimatorIds = setOf("exponential-mle", "weibull-mle", "gamma-mle")
        )
        val report = FittingRunner.fit(config)
        assertEquals("synthetic", report.datasetName)
        assertEquals(400, report.dataSummary.n)
        assertTrue(report.fits.any { it.success })
        assertNotNull(report.recommendedFamilyId)
    }
}
