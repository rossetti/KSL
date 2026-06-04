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

package ksl.app.dist.session

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import ksl.app.dist.DistributionModelingSession
import ksl.app.dist.config.BootstrapConfig
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.EvaluationMethod
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.config.RankingMethod
import ksl.app.dist.reporting.toDocument
import ksl.app.dist.result.FitResultData
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.toText
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end verification that the expanded `FitResultData` graph and the
 * surfaced engine parameters survive the asynchronous session boundary
 * (submit -> background fit -> Deferred result + terminal event), and that a
 * caller can rebuild the report document from the awaited DTO plus its own
 * raw data.
 */
class DistributionModelingSessionIntegrationTest {

    private fun exponentialSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = ExponentialRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value }
    }

    private fun spec(
        name: String = "expo",
        data: DoubleArray = exponentialSample(2.0, 250, 81),
        bootstrap: BootstrapConfig? = null,
        rankingMethod: RankingMethod = RankingMethod.ORDINAL,
        evaluationMethod: EvaluationMethod = EvaluationMethod.SCORING
    ): FitSpec.Single = FitSpec.Single(
        FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf(name to data)),
            estimatorIds = setOf("exponential-mle", "weibull-mle", "gamma-mle"),
            rankingMethod = rankingMethod,
            evaluationMethod = evaluationMethod,
            bootstrap = bootstrap
        )
    )

    private fun tempContext(): RenderContext {
        val dir = Files.createTempDirectory("session-r4")
        return RenderContext(outputDir = dir, plotDir = dir)
    }

    @Test
    fun `expanded result graph survives submit and await`() {
        DistributionModelingSession().use { session ->
            val result = session.submitAndAwaitBlocking(
                spec(bootstrap = BootstrapConfig(sampleSize = 50, level = 0.95, streamNumber = 1))
            )
            val report = assertIs<FitResult.Completed>(result).report
            assertNotNull(report.recommendedFamilyId)
            // histogram
            val hist = assertNotNull(report.histogram)
            assertTrue(hist.bins.isNotEmpty())
            // full MODA
            val moda = assertNotNull(report.scoring)
            assertTrue(moda.metrics.isNotEmpty())
            assertTrue(moda.scores.isNotEmpty())
            assertTrue(moda.values.isNotEmpty())
            // per-fit goodness of fit + bootstrap on the top successful fit
            val top = report.fits.first { it.success }
            assertNotNull(top.goodnessOfFit)
            val boots = assertNotNull(top.bootstrap)
            assertTrue(boots.isNotEmpty())
            assertTrue(boots.all { it.numBootstraps == 50 })
        }
    }

    @Test
    fun `terminal FitCompleted event carries the same report as the result`(): Unit = runBlocking {
        DistributionModelingSession().use { session ->
            val handle = session.submit(spec())
            val events = withTimeout(10_000) { handle.events.take(2).toList() }
            val terminal = events[1]
            val completedEvent = assertIs<FitEvent.FitCompleted>(terminal)
            val result = withTimeout(10_000) { handle.result.await() }
            val completed = assertIs<FitResult.Completed>(result)
            assertEquals(completed.report, completedEvent.report)
        }
    }

    @Test
    fun `surfaced ranking method flows through the session`() {
        DistributionModelingSession().use { session ->
            val result = session.submitAndAwaitBlocking(spec(rankingMethod = RankingMethod.DENSE))
            val report = assertIs<FitResult.Completed>(result).report
            assertEquals("Dense", report.scoring?.rankingMethod)
        }
    }

    @Test
    fun `evaluation method selects the recommendation criterion across the session`() {
        // Same fixed data fit twice through the session, differing only in
        // evaluation method; both must complete and surface a recommendation.
        val data = exponentialSample(2.0, 250, 83)
        DistributionModelingSession().use { session ->
            val byScore = assertIs<FitResult.Completed>(
                session.submitAndAwaitBlocking(spec(data = data, evaluationMethod = EvaluationMethod.SCORING))
            ).report
            val byRank = assertIs<FitResult.Completed>(
                session.submitAndAwaitBlocking(spec(data = data, evaluationMethod = EvaluationMethod.RANKING))
            ).report
            assertNotNull(byScore.recommendedFamilyId)
            assertNotNull(byRank.recommendedFamilyId)
            // Rank 1 in each ordering is the recommended family for that criterion.
            assertEquals(byScore.recommendedFamilyId, byScore.fits.first { it.success }.familyId)
            assertEquals(byRank.recommendedFamilyId, byRank.fits.first { it.success }.familyId)
        }
    }

    @Test
    fun `awaited report is JSON round-trippable`() {
        DistributionModelingSession().use { session ->
            val report = assertIs<FitResult.Completed>(
                session.submitAndAwaitBlocking(spec(bootstrap = BootstrapConfig(sampleSize = 40)))
            ).report
            val json = Json { encodeDefaults = true }
            val encoded = json.encodeToString(FitResultData.serializer(), report)
            val decoded = json.decodeFromString(FitResultData.serializer(), encoded)
            assertEquals(report, decoded)
        }
    }

    @Test
    fun `caller rebuilds the document from the awaited report and its own data`() {
        val data = exponentialSample(2.0, 250, 85)
        DistributionModelingSession().use { session ->
            val report = assertIs<FitResult.Completed>(
                session.submitAndAwaitBlocking(spec(name = "expo", data = data))
            ).report
            // The session returns only the DTO; the caller supplies its own data for plots.
            val text = report.toDocument(rawData = data).toText(tempContext())
            assertTrue(text.contains("Data Summary"))
            assertTrue(text.contains("Goodness of Fit"))
            assertTrue(text.contains("[Plot saved"))
        }
    }
}
