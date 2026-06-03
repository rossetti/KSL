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
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FitReportSerializationTest {

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    @Test
    fun `FitReport round-trips through JSON`() {
        val report = FitReport(
            datasetName = "x",
            kind = DistributionKind.CONTINUOUS,
            dataSummary = DataSummary(
                n = 100, min = 0.1, max = 9.8, average = 2.3, standardDeviation = 1.5, shift = 0.0
            ),
            fits = listOf(
                DistributionFitSummary(
                    rank = 1,
                    familyId = "exponential",
                    estimatorId = "exponential-mle",
                    displayName = "Exponential(mean=2.0)",
                    parameters = mapOf("mean" to 2.0),
                    success = true,
                    weightedValue = 0.91,
                    averageRanking = 1.0,
                    firstRankCount = 4
                ),
                DistributionFitSummary(
                    rank = 2,
                    familyId = "weibull",
                    estimatorId = "weibull-mle",
                    displayName = "Weibull(shape=1.1, scale=2.0)",
                    parameters = mapOf("shape" to 1.1, "scale" to 2.0),
                    success = false,
                    message = "estimator did not converge"
                )
            ),
            recommendedFamilyId = "exponential"
        )
        val encoded = json.encodeToString(FitReport.serializer(), report)
        val decoded = json.decodeFromString(FitReport.serializer(), encoded)
        assertEquals(report, decoded)
    }

    @Test
    fun `FitConfiguration round-trips through JSON`() {
        val config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf("a" to doubleArrayOf(1.0, 2.0, 3.0))),
            kind = DistributionKind.CONTINUOUS,
            estimatorIds = setOf("normal-mle", "exponential-mle"),
            scoringModelIds = setOf("anderson-darling")
        )
        val encoded = json.encodeToString(FitConfiguration.serializer(), config)
        val decoded = json.decodeFromString(FitConfiguration.serializer(), encoded)
        // Compare via the same data class — Inline's DoubleArray map needs
        // content-wise comparison, so test via JSON re-encoding round-trip.
        val reEncoded = json.encodeToString(FitConfiguration.serializer(), decoded)
        assertEquals(encoded, reEncoded)
        assertEquals(setOf("normal-mle", "exponential-mle"), decoded.estimatorIds)
        assertTrue(decoded.dataSource is DataSourceReference.Inline)
    }
}
