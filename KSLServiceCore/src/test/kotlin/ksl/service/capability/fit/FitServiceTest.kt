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

package ksl.service.capability.fit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.result.FitResultData
import ksl.app.dist.session.FitEvent
import ksl.app.dist.session.FitResult
import ksl.service.job.JobManager
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Proves capability B (distribution fitting) is first-class on the shared
 * spine: a *real* fit, submitted through [FitService], flows through the same
 * generic [JobManager] as a model run and yields a wire-ready `FitResultData` —
 * the concrete payoff of the §2.3 capability reassessment.
 */
class FitServiceTest {

    @Test
    fun `a real fit flows through the generic JobManager and is wire-ready`() = runBlocking {
        // A scope the JobManager's bookkeeping coroutines live in; cancelled at
        // the end so the test does not linger on the eviction-TTL coroutine.
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            FitService().use { service ->
                val manager = JobManager<FitEvent, FitResult>(scope, retention = 1.seconds)

                val data = ExponentialRV(10.0).sample(200)
                val config = FitConfiguration(
                    dataSource = DataSourceReference.Inline(mapOf("sample" to data)),
                    kind = DistributionKind.CONTINUOUS,
                    estimatorIds = FittingCatalog.defaultEstimatorIds(DistributionKind.CONTINUOUS),
                    scoringModelIds = FittingCatalog.defaultScoringModelIds(),
                )

                // Capability B submits exactly like capability A: register a
                // JobHandleView with the shared manager.
                val record = manager.register { service.submit(FitSpec.Single(config)) }

                val result = withTimeout(30.seconds) { manager.result(record.jobId)!! }
                assertIs<FitResult.Completed>(result, "expected a completed fit; got $result")

                val report: FitResultData = result.report
                assertEquals("sample", report.datasetName)
                assertEquals(DistributionKind.CONTINUOUS, report.kind)
                assertTrue(report.fits.isNotEmpty(), "the fitter should attempt candidate families")

                // The fit result is already @Serializable — no projection needed,
                // unlike RunResult. Round-trip it to prove wire-readiness.
                val json = Json { encodeDefaults = true }
                val encoded = json.encodeToString(FitResultData.serializer(), report)
                val decoded = json.decodeFromString(FitResultData.serializer(), encoded)
                assertEquals(report.datasetName, decoded.datasetName)
                assertEquals(report.fits.size, decoded.fits.size)
            }
        } finally {
            scope.cancel()
        }
    }
}
