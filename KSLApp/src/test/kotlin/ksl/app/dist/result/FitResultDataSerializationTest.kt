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
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
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
                statistics = sampleStats(count = 100.0, average = 2.3, variance = 2.25),
                zeroCount = 0, negativeCount = 0, positiveCount = 100
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
            shiftAnalysis = ShiftAnalysisDTO(
                leftShift = 0.0, hasZeroes = false, hasNegatives = false,
                zeroTolerance = 0.001, ciForMinimumLevel = 0.95,
                ciForMinimumLower = 0.05, ciForMinimumUpper = 0.15
            ),
            frequency = IntegerFrequencyDTO(
                listOf(
                    IntegerFrequencyCellDTO(0, 5.0, 5.0, 0.05, 0.05),
                    IntegerFrequencyCellDTO(1, 12.0, 17.0, 0.12, 0.17)
                )
            ),
            dispersion = DispersionAnalysisDTO(
                indexOfDispersion = 0.98, poissonVarianceTestStatistic = 97.0,
                degreesOfFreedom = 99, upperPValue = 0.55, lowerPValue = 0.45, twoSidedPValue = 0.9
            ),
            scoring = scoring
        )
    }

    private fun sampleStats(count: Double, average: Double, variance: Double) = StatisticDataDTO(
        name = "x", count = count, average = average,
        standardDeviation = kotlin.math.sqrt(variance), standardError = 0.1, halfWidth = 0.2,
        confidenceLevel = 0.95, lowerLimit = average - 0.2, upperLimit = average + 0.2,
        min = 0.1, max = 9.8, sum = average * count, variance = variance,
        deviationSumOfSquares = variance * (count - 1.0), kurtosis = 8.1, skewness = 1.9,
        lag1Covariance = 0.01, lag1Correlation = 0.02, vonNeumannLag1TestStatistic = 1.95,
        numberMissing = 0.0
    )

    @Test
    fun `FamilyFrequencyResult round-trips through JSON`() {
        val result = FamilyFrequencyResult(
            datasetName = "x",
            numSamples = 400,
            frequency = IntegerFrequencyDTO(
                listOf(
                    IntegerFrequencyCellDTO(1, 320.0, 320.0, 0.8, 0.8, "Exponential"),
                    IntegerFrequencyCellDTO(2, 80.0, 400.0, 0.2, 1.0, "Weibull")
                )
            )
        )
        val encoded = json.encodeToString(FamilyFrequencyResult.serializer(), result)
        val decoded = json.decodeFromString(FamilyFrequencyResult.serializer(), encoded)
        assertEquals(result, decoded)
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
                statistics = sampleStats(count = 10.0, average = 2.0, variance = 1.0),
                zeroCount = 1, negativeCount = 0, positiveCount = 9
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
        assertEquals(null, decoded.shiftAnalysis)
        assertEquals(null, decoded.frequency)
        assertEquals(null, decoded.dispersion)
        assertEquals(null, decoded.scoring)
        assertEquals(EmpProbConvention.CONTINUITY1, decoded.empProbConvention)
    }

    @Test
    fun `poissonDispersionTest computes index, statistic, dof, and p-values`() {
        // equidispersed: variance == mean => IoD == 1 and T == n - 1
        val r = ksl.utilities.distributions.fitting.DiscretePMFGoodnessOfFit
            .poissonDispersionTest(mean = 5.0, variance = 5.0, sampleSize = 100.0)
        assertEquals(1.0, r.indexOfDispersion, 1e-12)
        assertEquals(99.0, r.testStatistic, 1e-9)
        assertEquals(99, r.degreesOfFreedom)
        assertTrue(r.upperPValue in 0.0..1.0)
        assertTrue(r.lowerPValue in 0.0..1.0)
        assertEquals(2.0 * minOf(r.upperPValue, r.lowerPValue), r.twoSidedPValue, 1e-12)
        // degenerate: zero mean yields NaN index and p-values
        val z = ksl.utilities.distributions.fitting.DiscretePMFGoodnessOfFit
            .poissonDispersionTest(mean = 0.0, variance = 1.0, sampleSize = 50.0)
        assertTrue(z.indexOfDispersion.isNaN())
        assertTrue(z.upperPValue.isNaN())
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
                rv = RVData(RVType.Gamma, mapOf("shape" to doubleArrayOf(2.0), "scale" to doubleArrayOf(3.0))),
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
        assertEquals(RVType.Gamma, src.rv.rvType)
        assertContentEquals(doubleArrayOf(2.0), src.rv.parameters["shape"])
        assertContentEquals(doubleArrayOf(3.0), src.rv.parameters["scale"])
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
