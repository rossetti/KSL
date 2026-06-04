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

package ksl.app.dist.result

import kotlinx.serialization.json.Json
import ksl.app.dist.config.BootstrapConfig
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trips the full result DTO graph through JSON. Every DTO is populated
 * (including the fields the runner does not yet fill) so serialization of the
 * entire contract is proven independently of what the runner currently emits.
 */
class FitResultDataSerializationTest {

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    private fun fullyPopulatedReport(): FitResultData {
        val gof = GoodnessOfFitDTO(
            chiSquaredStatistic = 3.21, chiSquaredDOF = 5, chiSquaredPValue = 0.67,
            binBreakPoints = listOf(0.0, 1.0, 2.0, 3.0),
            binProbabilities = listOf(0.3, 0.4, 0.3),
            expectedCounts = listOf(30.0, 40.0, 30.0),
            observedCounts = listOf(28.0, 41.0, 31.0),
            ksStatistic = 0.04, ksPValue = 0.9,
            andersonDarlingStatistic = 0.5, andersonDarlingPValue = 0.75,
            cramerVonMisesStatistic = 0.08, cramerVonMisesPValue = 0.8
        )
        val bootstrap = listOf(
            BootstrapEstimateDTO(
                parameterName = "mean", originalEstimate = 2.0, bootstrapAverage = 2.01,
                bias = 0.01, mse = 0.05, stdError = 0.22, numBootstraps = 399, ciLevel = 0.95,
                normalCILower = 1.6, normalCIUpper = 2.4,
                basicCILower = 1.58, basicCIUpper = 2.42,
                percentileCILower = 1.59, percentileCIUpper = 2.43
            )
        )
        val fits = listOf(
            DistributionFitDTO(
                rank = 1, familyId = "exponential", estimatorId = "exponential-mle",
                rvTypeName = "Exponential", displayName = "Exponential(mean=2.0)",
                parameters = mapOf("mean" to 2.0), numberOfParameters = 1,
                success = true, message = null, shift = 0.0,
                weightedValue = 0.91, averageRanking = 1.0, firstRankCount = 4,
                goodnessOfFit = gof, bootstrap = bootstrap
            ),
            DistributionFitDTO(
                rank = 2, familyId = "weibull", estimatorId = "weibull-mle",
                rvTypeName = "Weibull", displayName = "Weibull(shape=1.1, scale=2.0)",
                parameters = mapOf("shape" to 1.1, "scale" to 2.0), numberOfParameters = 2,
                success = false, message = "did not converge"
            )
        )
        val scoring = ModaResultDTO(
            modelName = "PDF MODA", rankingMethod = "Ordinal",
            metrics = listOf(
                MetricDTO("Anderson-Darling", "SmallerIsBetter", 0.25, 0.0, Double.MAX_VALUE, null, "AD stat"),
                MetricDTO("BIC", "SmallerIsBetter", 0.25, 0.0, Double.MAX_VALUE)
            ),
            scores = listOf(
                ModaScoreDTO("Exponential(mean=2.0)", "Anderson-Darling", 0.5),
                ModaScoreDTO("Exponential(mean=2.0)", "BIC", 120.0)
            ),
            values = listOf(
                ModaValueDTO("Exponential(mean=2.0)", "Anderson-Darling", 0.92, 1.0),
                ModaValueDTO("Exponential(mean=2.0)", "BIC", 0.88, 1.0)
            ),
            rankFrequencies = listOf(
                RankFrequencyDTO("Exponential(mean=2.0)", 1, 8.0, 0.8, 0.8)
            )
        )
        return FitResultData(
            datasetName = "x",
            kind = DistributionKind.CONTINUOUS,
            empProbConvention = EmpProbConvention.CONTINUITY1,
            dataSummary = DataSummaryDTO(
                n = 100, min = 0.1, max = 9.8, average = 2.3, variance = 2.25,
                standardDeviation = 1.5, skewness = 1.9, kurtosis = 8.1,
                zeroCount = 0, negativeCount = 0, positiveCount = 100, shift = 0.0
            ),
            fits = fits,
            recommendedFamilyId = "exponential",
            histogram = HistogramDTO(
                bins = listOf(
                    HistogramBinDTO(1, "[0,1)", 0.0, 1.0, 28.0, 28.0, 0.28, 0.28),
                    HistogramBinDTO(2, "[1,2)", 1.0, 2.0, 41.0, 69.0, 0.41, 0.69)
                ),
                underFlowCount = 0.0, overFlowCount = 0.0
            ),
            scoring = scoring,
            bootstrapFamilyFrequency = mapOf("exponential" to 320, "weibull" to 80)
        )
    }

    @Test
    fun `full FitResultData graph round-trips through JSON`() {
        val report = fullyPopulatedReport()
        val encoded = json.encodeToString(FitResultData.serializer(), report)
        val decoded = json.decodeFromString(FitResultData.serializer(), encoded)
        assertEquals(report, decoded)
    }

    @Test
    fun `minimal FitResultData (R1 runner shape) round-trips with null deferred fields`() {
        val report = FitResultData(
            datasetName = "x",
            kind = DistributionKind.CONTINUOUS,
            dataSummary = DataSummaryDTO(
                n = 10, min = 0.0, max = 5.0, average = 2.0, variance = 1.0,
                standardDeviation = 1.0, skewness = 0.0, kurtosis = 3.0,
                zeroCount = 1, negativeCount = 0, positiveCount = 9, shift = 0.0
            ),
            fits = listOf(
                DistributionFitDTO(
                    rank = 1, familyId = "normal", estimatorId = "normal-mle",
                    rvTypeName = "Normal", displayName = "Normal(2.0, 1.0)",
                    parameters = mapOf("mean" to 2.0, "variance" to 1.0),
                    numberOfParameters = 2, success = true,
                    weightedValue = 0.8, averageRanking = 1.0, firstRankCount = 1
                )
            ),
            recommendedFamilyId = "normal"
        )
        val encoded = json.encodeToString(FitResultData.serializer(), report)
        val decoded = json.decodeFromString(FitResultData.serializer(), encoded)
        assertEquals(report, decoded)
        assertEquals(null, decoded.histogram)
        assertEquals(null, decoded.scoring)
        assertEquals(EmpProbConvention.CONTINUITY1, decoded.empProbConvention)
    }

    @Test
    fun `FitConfiguration with engine parameters round-trips`() {
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("a" to doubleArrayOf(1.0, 2.0, 3.0))),
            estimatorIds = setOf("normal-mle"),
            rankingMethod = ksl.app.dist.config.RankingMethod.FRACTIONAL,
            evaluationMethod = ksl.app.dist.config.EvaluationMethod.RANKING,
            bootstrap = BootstrapConfig(sampleSize = 200, level = 0.9, streamNumber = 3)
        )
        val encoded = json.encodeToString(FitConfiguration.serializer(), config)
        val decoded = json.decodeFromString(FitConfiguration.serializer(), encoded)
        assertEquals(BootstrapConfig(sampleSize = 200, level = 0.9, streamNumber = 3), decoded.bootstrap)
        assertEquals(ksl.app.dist.config.RankingMethod.FRACTIONAL, decoded.rankingMethod)
        assertEquals(ksl.app.dist.config.EvaluationMethod.RANKING, decoded.evaluationMethod)
        assertTrue(decoded.dataSource is DataSourceReference.Inline)
    }

    @Test
    fun `Generated data source round-trips through JSON`() {
        val config = FitConfiguration(
            dataSource = DataSourceReference.Generated(
                rvType = "Gamma",
                parameters = mapOf("shape" to 2.0, "scale" to 3.0),
                sampleSize = 250,
                streamNumber = 4,
                name = "synthetic"
            ),
            estimatorIds = setOf("gamma-mle")
        )
        val encoded = json.encodeToString(FitConfiguration.serializer(), config)
        val decoded = json.decodeFromString(FitConfiguration.serializer(), encoded)
        val src = decoded.dataSource
        assertTrue(src is DataSourceReference.Generated)
        assertEquals("Gamma", src.rvType)
        assertEquals(mapOf("shape" to 2.0, "scale" to 3.0), src.parameters)
        assertEquals(250, src.sampleSize)
        assertEquals("synthetic", src.name)
    }

    @Test
    fun `Database data source round-trips through JSON`() {
        val config = FitConfiguration(
            dataSource = DataSourceReference.Database(
                connection = ksl.app.dist.config.DatabaseConnectionRef(
                    dbType = ksl.app.dist.config.DbType.SQLITE, location = "/data/x.db"
                ),
                source = ksl.app.dist.config.DbSource.Query("SELECT a, b FROM t"),
                layout = ksl.app.dist.config.DatasetLayout.WIDE,
                datasetColumns = listOf("a", "b")
            ),
            estimatorIds = setOf("normal-mle")
        )
        val encoded = json.encodeToString(FitConfiguration.serializer(), config)
        val decoded = json.decodeFromString(FitConfiguration.serializer(), encoded)
        val src = decoded.dataSource
        assertTrue(src is DataSourceReference.Database)
        assertEquals(ksl.app.dist.config.DbType.SQLITE, src.connection.dbType)
        assertTrue(src.source is ksl.app.dist.config.DbSource.Query)
        assertEquals(listOf("a", "b"), src.datasetColumns)
    }

    @Test
    fun `each CredentialSource variant round-trips and carries no secret`() {
        val sources = listOf(
            ksl.app.dist.config.CredentialSource.None,
            ksl.app.dist.config.CredentialSource.RuntimePrompt("alice"),
            ksl.app.dist.config.CredentialSource.Environment("PG_USER", "PG_PASS"),
            ksl.app.dist.config.CredentialSource.ExternalFile("/etc/secrets/db.toml")
        )
        for (cs in sources) {
            val ref = ksl.app.dist.config.DatabaseConnectionRef(
                dbType = ksl.app.dist.config.DbType.POSTGRES,
                location = "mydb", serverName = "localhost", portNumber = 5432, credentials = cs
            )
            val encoded = json.encodeToString(ksl.app.dist.config.DatabaseConnectionRef.serializer(), ref)
            val decoded = json.decodeFromString(ksl.app.dist.config.DatabaseConnectionRef.serializer(), encoded)
            assertEquals(ref, decoded)
            // The reference carries only references to secrets, never values.
            assertFalse(encoded.contains("s3cret"))
            assertFalse(encoded.contains("password=\"") || encoded.contains("\"password\":\""))
        }
    }
}
