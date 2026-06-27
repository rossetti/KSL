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
import ksl.app.config.WelchResponseSpec
import ksl.app.session.RunResult
import ksl.examples.book.appendixD.GIGcQueue
import ksl.observers.welch.WelchDataFileAnalyzer
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Phase-2 tests for Welch warm-up capture in `SingleRunOrchestrator`.
 *
 *  When `OutputConfig.enableWelchAnalysis` is on, the orchestrator
 *  attaches a `WelchFileObserver` to each selected response before the
 *  run, which streams observations to
 *  `<outputDirectory>/<responseName>_Welch/` as the run executes.  These
 *  tests assert the on-disk capture happens, that disabling the toggle
 *  writes nothing, and that an unknown response name is skipped
 *  defensively rather than failing the run.
 *
 *  The reload-from-disk assertion (`WelchDataFileAnalyzer.makeFromJSON`)
 *  also discharges the plan's Phase-0 round-trip check: a file written by
 *  a run in this app is reconstructable from its JSON metadata alone.
 */
class SingleRunWelchCaptureTest {

    private companion object {
        const val MM1_ID = "WelchCaptureMM1"
        const val TIMEOUT_MS = 30_000L

        // Response names declared inside GIGcQueue: a tally (System Time)
        // and a time-weighted (Num in System) response.  WelchFileObserver
        // derives its subdirectory from the response name with ':' -> '_'.
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

    /** The Welch subdirectory for [responseName] under the run output dir. */
    private fun welchDir(outputDir: Path, responseName: String): Path =
        outputDir.resolve(responseName.replace(':', '_') + "_Welch")

    private fun listFiles(dir: Path): List<String> =
        if (Files.exists(dir)) Files.list(dir).use { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        } else emptyList()

    private fun welchDirNames(outputDir: Path): List<String> =
        if (Files.exists(outputDir)) Files.list(outputDir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith("_Welch") }
                .sorted()
                .toList()
        } else emptyList()

    @Test
    @DisplayName("Welch observers write a .json and .wdf per selected response, reloadable from disk")
    fun welchObserversWriteJsonAndWdfPerSelectedResponse(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                enableWelchAnalysis = true,
                welchResponses = listOf(
                    WelchResponseSpec(SYSTEM_TIME, 1.0),
                    WelchResponseSpec(NUM_IN_SYSTEM, 10.0)
                )
            )
        )
        submitAndAwait(cfg)

        for (responseName in listOf(SYSTEM_TIME, NUM_IN_SYSTEM)) {
            val dir = welchDir(tempDir, responseName)
            assertTrue(Files.isDirectory(dir),
                "Expected Welch subdirectory for '$responseName' at $dir")
            val files = listFiles(dir)
            assertTrue(files.any { it.endsWith(".json") },
                "Expected a .json metadata file in $dir; got: $files")
            assertTrue(files.any { it.endsWith(".wdf") },
                "Expected a .wdf data file in $dir; got: $files")

            // Reload the analyzer from the JSON alone — Phase-0 round-trip.
            val jsonPath = Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") }.findFirst().orElseThrow()
            }
            val analyzer = WelchDataFileAnalyzer.makeFromJSON(jsonPath)
            assertTrue(analyzer.responseName.isNotBlank(),
                "Reloaded analyzer for '$responseName' should expose a response name")
        }
    }

    @Test
    @DisplayName("No Welch directories are written when the toggle is disabled")
    fun noWelchDirsAreWrittenWhenDisabled(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                enableWelchAnalysis = false,
                welchResponses = listOf(
                    WelchResponseSpec(SYSTEM_TIME, 1.0),
                    WelchResponseSpec(NUM_IN_SYSTEM, 10.0)
                )
            )
        )
        submitAndAwait(cfg)

        val welchDirs = welchDirNames(tempDir)
        assertTrue(welchDirs.isEmpty(),
            "No *_Welch directories must be written when enableWelchAnalysis is off; got: $welchDirs")
    }

    @Test
    @DisplayName("Unknown response name is skipped defensively; run still completes")
    fun unknownResponseNameIsSkippedDefensively(@TempDir tempDir: Path) {
        val cfg = buildConfig(
            outputDir = tempDir,
            outputConfig = OutputConfig(
                enableWelchAnalysis = true,
                welchResponses = listOf(
                    WelchResponseSpec("NoSuchResponse", 1.0),
                    WelchResponseSpec(SYSTEM_TIME, 1.0)
                )
            )
        )
        // submitAndAwait already asserts RunResult.Completed — i.e. the
        // unknown name did not throw and abort the run.
        submitAndAwait(cfg)

        assertTrue(Files.isDirectory(welchDir(tempDir, SYSTEM_TIME)),
            "The real response's Welch dir must be written")
        assertTrue(!Files.exists(welchDir(tempDir, "NoSuchResponse")),
            "No Welch dir must be written for an unknown response name")
        // Exactly one *_Welch dir overall — the bogus one produced nothing.
        assertTrue(welchDirNames(tempDir).size == 1,
            "Exactly one *_Welch dir expected; got: ${welchDirNames(tempDir)}")
    }
}
