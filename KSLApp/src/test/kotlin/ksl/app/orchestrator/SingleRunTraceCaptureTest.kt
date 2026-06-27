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

package ksl.app.orchestrator

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.config.TraceResponseSpec
import ksl.app.session.RunResult
import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.ResponseTraceData
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Phase-2 tests for response-trace capture in `SingleRunOrchestrator`.
 *
 *  When `OutputConfig.enableResponseTrace` is on, the orchestrator attaches a
 *  `ResponseTrace` to each selected response before the run, which streams
 *  every change to `<outputDirectory>/<responseName>_Trace` as the run
 *  executes.  These tests assert the on-disk capture happens (and reloads via
 *  `ResponseTraceData`), that the per-response `maxReplications` cap is wired,
 *  that disabling the toggle writes nothing, and that an unknown response name
 *  is skipped defensively rather than failing the run.
 */
class SingleRunTraceCaptureTest {

    private companion object {
        const val MM1_ID = "TraceCaptureMM1"
        const val TIMEOUT_MS = 30_000L

        // Response names declared inside GIGcQueue: a tally (System Time) and
        // a time-weighted (Num in System) response.
        const val SYSTEM_TIME = "System Time"
        const val NUM_IN_SYSTEM = "Num in System"
    }

    private val mm1Provider = MapModelProvider(MM1_ID, object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model(MM1_ID, autoCSVReports = false)
            model.numberOfReplications = 3
            model.lengthOfReplication = 100.0
            GIGcQueue(model, numServers = 1, name = "Q")
            return model
        }
    })

    private fun buildConfig(outputDir: Path, outputConfig: OutputConfig): RunConfiguration =
        RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "single",
                    modelReference = ModelReference.ByProviderId(MM1_ID)
                )
            ),
            outputConfig = outputConfig.copy(
                outputDirectory = outputDir.toAbsolutePath().normalize().toString()
            )
        )

    private fun submitAndAwait(config: RunConfiguration) = runBlocking {
        KSLAppSession(mm1Provider, this).use { session ->
            val handle = session.submit(RunSpec.Single(config))
            val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
            assertIs<RunResult.Completed>(result)
        }
    }

    /** The trace file for [responseName] under the run output dir. */
    private fun traceFile(outputDir: Path, responseName: String): Path =
        outputDir.resolve(responseName.replace(':', '_') + "_Trace")

    private fun traceFileNames(outputDir: Path): List<String> =
        if (Files.exists(outputDir)) Files.list(outputDir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith("_Trace") }
                .sorted()
                .toList()
        } else emptyList()

    @Test
    @DisplayName("ResponseTrace writes a trace file per selected response, reloadable from disk")
    fun traceFilesWrittenPerSelectedResponse(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                enableResponseTrace = true,
                traceResponses = listOf(
                    TraceResponseSpec(SYSTEM_TIME, maxReplications = 3),
                    TraceResponseSpec(NUM_IN_SYSTEM, maxReplications = 3)
                )
            )
        )
        submitAndAwait(cfg)

        for ((responseName, isTW) in listOf(SYSTEM_TIME to false, NUM_IN_SYSTEM to true)) {
            val file = traceFile(tempDir, responseName)
            assertTrue(Files.exists(file), "Expected a trace file for '$responseName' at $file")
            val data = ResponseTraceData(file, isTimeWeighted = isTW)
            assertTrue(data.replicationNumbers.isNotEmpty(),
                "Reloaded trace for '$responseName' should expose recorded replications")
        }
    }

    @Test
    @DisplayName("Per-response maxReplications caps how many replications are recorded")
    fun maxReplicationsCapIsApplied(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                enableResponseTrace = true,
                // 3 replications run, but only the first should be traced.
                traceResponses = listOf(TraceResponseSpec(SYSTEM_TIME, maxReplications = 1))
            )
        )
        submitAndAwait(cfg)

        val data = ResponseTraceData(traceFile(tempDir, SYSTEM_TIME), isTimeWeighted = false)
        assertEquals(listOf(1), data.replicationNumbers,
            "maxReplications = 1 should record only replication 1; got ${data.replicationNumbers}")
    }

    @Test
    @DisplayName("No trace files are written when the toggle is disabled")
    fun noTraceFilesWhenDisabled(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                enableResponseTrace = false,
                traceResponses = listOf(TraceResponseSpec(SYSTEM_TIME, maxReplications = 1))
            )
        )
        submitAndAwait(cfg)

        assertTrue(traceFileNames(tempDir).isEmpty(),
            "No *_Trace files must be written when enableResponseTrace is off; got: ${traceFileNames(tempDir)}")
    }

    @Test
    @DisplayName("Unknown response name is skipped defensively; run still completes")
    fun unknownResponseNameIsSkippedDefensively(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                enableResponseTrace = true,
                traceResponses = listOf(
                    TraceResponseSpec("NoSuchResponse", maxReplications = 1),
                    TraceResponseSpec(SYSTEM_TIME, maxReplications = 1)
                )
            )
        )
        // submitAndAwait already asserts RunResult.Completed — the unknown
        // name did not throw and abort the run.
        submitAndAwait(cfg)

        assertTrue(Files.exists(traceFile(tempDir, SYSTEM_TIME)),
            "The real response's trace file must be written")
        assertTrue(!Files.exists(traceFile(tempDir, "NoSuchResponse")),
            "No trace file must be written for an unknown response name")
        assertEquals(1, traceFileNames(tempDir).size,
            "Exactly one *_Trace file expected; got: ${traceFileNames(tempDir)}")
    }
}
