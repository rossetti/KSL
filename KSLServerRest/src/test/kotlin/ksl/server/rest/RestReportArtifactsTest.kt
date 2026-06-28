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

package ksl.server.rest

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.ScenarioSpec
import ksl.app.config.TraceResponseSpec
import ksl.app.config.WelchResponseSpec
import ksl.service.store.ArtifactStore
import ksl.service.store.ResultStore
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * End-to-end Phase B wiring over the run service: a single-run document that
 * captures Welch + trace data has its reports auto-materialized into the
 * result's artifact directory, served through the artifact surface. The lets-plot
 * Swing frontend is excluded from this module, so rendering runs headless.
 */
class RestReportArtifactsTest {

    private fun captureDoc(): String {
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "single",
                    modelReference = ModelReference.ByProviderId("MM1"),
                    runOverrides = ExperimentRunOverrides(
                        numberOfReplications = 4,
                        lengthOfReplication = 2000.0,
                    ),
                ),
            ),
            outputConfig = OutputConfig(
                enableWelchAnalysis = true,
                welchResponses = listOf(WelchResponseSpec("System Time", 1.0)),
                enableResponseTrace = true,
                traceResponses = listOf(
                    TraceResponseSpec("System Time", maxReplications = 1),
                    TraceResponseSpec("Num in System", maxReplications = 1),
                ),
            ),
        )
        return RunConfigurationJson.encode(config)
    }

    @Test
    @DisplayName("a capture-enabled run auto-materializes Welch + trace report artifacts")
    fun captureRunProducesReportArtifacts() = runBlocking {
        val root = Files.createTempDirectory("rest-report-e2e")
        val registry = TestBundles.registry()
        val service = KslRestService(
            registry,
            resultStore = ResultStore(root),
            artifactStore = ArtifactStore(root),
        )
        try {
            val submission = service.submitRunDocument(captureDoc())
            // Drive the run to completion (it executes on the service's own scope).
            withTimeout(60_000) {
                while (service.runResult(submission.jobId) == null) delay(100)
            }
            val artifacts = service.artifacts(submission.resultId).map { it.name }
            assertTrue(artifacts.contains("welch.html"),
                "expected an auto-rendered welch.html artifact; got $artifacts")
            assertTrue(artifacts.contains("trace.html"),
                "expected an auto-rendered trace.html artifact; got $artifacts")
        } finally {
            service.close()
            registry.close()
        }
    }
}
