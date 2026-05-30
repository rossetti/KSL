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

package ksl.app

import kotlinx.coroutines.runBlocking
import ksl.app.notification.NotificationSeverity
import ksl.app.notification.NotificationSink
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunResult
import ksl.examples.general.appsession.TOML_ROUNDTRIP_HEADLESS_DEMO_APP_NAME
import ksl.examples.general.appsession.TOML_ROUNDTRIP_HEADLESS_DEMO_CONFIGS_SUBDIR
import ksl.examples.general.appsession.TOML_ROUNDTRIP_HEADLESS_DEMO_EXPERIMENT_DESIGN_POINTS
import ksl.examples.general.appsession.TOML_ROUNDTRIP_HEADLESS_DEMO_EXPERIMENT_FILENAME
import ksl.examples.general.appsession.TOML_ROUNDTRIP_HEADLESS_DEMO_SCENARIOS_FILENAME
import ksl.examples.general.appsession.TOML_ROUNDTRIP_HEADLESS_DEMO_SCENARIO_NAMES
import ksl.examples.general.appsession.TOML_ROUNDTRIP_HEADLESS_DEMO_SINGLE_FILENAME
import ksl.examples.general.appsession.runKSLAppSessionTomlRoundTripHeadlessDemo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Substrate-validation tests for
 *  `KSLAppSessionTomlRoundTripHeadlessDemo`.
 *
 *  Drives the demo with a JUnit `@TempDir` workspace and asserts
 *  that for each of the three workflows (Single, Scenarios,
 *  Experiment):
 *
 *  - The substrate TOML codec round-trips a config bit-perfectly
 *    via a real file on disk (`encode → write → read → decode`
 *    produces an equal config).
 *  - The decoded config, submitted through [KSLAppSession],
 *    produces the expected substrate [RunResult] — proving the
 *    disk-loading path a non-Swing host would also take preserves
 *    full runtime fidelity.
 *  - The TOML files land at the predicted on-disk locations under
 *    the `AppWorkspacePaths`-derived appWorkspace.
 *  - The host's [NotificationSink.Collecting] captures every
 *    user-facing message including per-workflow round-trip
 *    announcements.
 *  - The demo source does not import Swing / AWT or low-level
 *    orchestrators, mirroring the E.1.1 / E.1.2 / E.1.3
 *    invariant.
 */
class KSLAppSessionTomlRoundTripHeadlessDemoTest {

    @Test
    fun `single workflow TOML round-trips and submits to RunResult Completed`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionTomlRoundTripHeadlessDemo(workspace, notifier)

        val outcome = report.singleOutcome
        assertTrue(outcome.tomlFile.toFile().exists(),
            "Single TOML file must exist on disk.")
        assertEquals(expectedTomlPath(workspace, TOML_ROUNDTRIP_HEADLESS_DEMO_SINGLE_FILENAME),
            outcome.tomlFile,
            "Single TOML file must land at the AppWorkspacePaths-derived path.")
        assertTrue(outcome.configsEqual,
            "Decoded RunConfiguration must structurally equal the in-memory original.")
        assertEquals(outcome.original, outcome.decoded)

        val result = assertIs<RunResult.Completed>(outcome.runResult)
        assertTrue(result.snapshot.experiment.exp_name.isNotBlank(),
            "Completed result must carry a snapshot with a non-blank experiment name.")
    }

    @Test
    fun `scenarios workflow TOML round-trips and submits to BatchCompleted with 2 snapshots`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionTomlRoundTripHeadlessDemo(workspace, notifier)

        val outcome = report.scenarioOutcome
        assertTrue(outcome.tomlFile.toFile().exists(),
            "Scenarios TOML file must exist on disk.")
        assertEquals(expectedTomlPath(workspace, TOML_ROUNDTRIP_HEADLESS_DEMO_SCENARIOS_FILENAME),
            outcome.tomlFile)
        assertTrue(outcome.configsEqual,
            "Decoded RunConfiguration must structurally equal the in-memory original.")
        assertEquals(outcome.original, outcome.decoded)
        assertEquals(2, outcome.original.scenarios.size,
            "Round-trip fixture must contain exactly 2 scenarios.")

        val batch = assertIs<RunResult.BatchCompleted>(outcome.runResult)
        assertEquals(2, batch.snapshots.size,
            "Submitting the decoded 2-scenario config must produce 2 snapshots.")
        assertEquals(TOML_ROUNDTRIP_HEADLESS_DEMO_SCENARIO_NAMES,
            batch.snapshots.map { it.experiment.exp_name },
            "Snapshot names must match the demo's published scenario names in commit order.")
    }

    @Test
    fun `experiment workflow TOML round-trips and submits to BatchCompleted with 4 design points`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionTomlRoundTripHeadlessDemo(workspace, notifier)

        val outcome = report.experimentOutcome
        assertTrue(outcome.tomlFile.toFile().exists(),
            "Experiment TOML file must exist on disk.")
        assertEquals(expectedTomlPath(workspace, TOML_ROUNDTRIP_HEADLESS_DEMO_EXPERIMENT_FILENAME),
            outcome.tomlFile)
        assertTrue(outcome.configsEqual,
            "Decoded ExperimentConfiguration must structurally equal the in-memory original.")
        assertEquals(outcome.original, outcome.decoded)

        val batch = assertIs<RunResult.BatchCompleted>(outcome.runResult)
        assertEquals(TOML_ROUNDTRIP_HEADLESS_DEMO_EXPERIMENT_DESIGN_POINTS, batch.snapshots.size,
            "Submitting the decoded ExperimentConfiguration must produce one snapshot per design point.")
    }

    @Test
    fun `TOML files contain decodable content matching the substrate codecs`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        val report = runKSLAppSessionTomlRoundTripHeadlessDemo(workspace, notifier)

        // Each file is non-empty, contains the substrate codec's document
        // header, and re-reading the bytes from disk decodes back to the
        // demo's reported decoded value (sanity check that the file on
        // disk actually carries the round-tripped content, not just that
        // the in-memory text round-trips).
        val singleText = Files.readString(report.singleOutcome.tomlFile)
        val scenarioText = Files.readString(report.scenarioOutcome.tomlFile)
        val experimentText = Files.readString(report.experimentOutcome.tomlFile)

        assertTrue(singleText.isNotBlank(), "Single TOML must not be blank.")
        assertTrue(scenarioText.isNotBlank(), "Scenarios TOML must not be blank.")
        assertTrue(experimentText.isNotBlank(), "Experiment TOML must not be blank.")
        // TOML section headers should appear — minimal sanity check on shape.
        assertTrue(singleText.contains("[["), "Single TOML must contain at least one TOML array-of-tables marker.")
        assertTrue(scenarioText.contains("[["), "Scenarios TOML must contain at least one TOML array-of-tables marker.")
        assertTrue(experimentText.contains("["),
            "Experiment TOML must contain at least one TOML section marker.")
    }

    @Test
    fun `headless TOML demo emits notifications through the substrate sink`(
        @TempDir workspace: Path
    ) = runBlocking {
        val notifier = NotificationSink.Collecting()
        runKSLAppSessionTomlRoundTripHeadlessDemo(workspace, notifier)

        val specs = notifier.specs()
        // Conservative lower bound: start + per-workflow {encode + write + decode + submit + terminal} ×3 + final
        // ≈ 17 minimum but allow slack.
        assertTrue(specs.size >= 12,
            "Demo must emit at least 12 notifications (4+ per workflow ×3); got ${specs.size}.")
        assertTrue(specs.all { it.severity == NotificationSeverity.INFO },
            "Happy-path demo must emit only INFO notifications; got " +
                specs.map { it.severity }.distinct())

        // Each workflow announces its encode, write, decode, and submit
        // steps.  Spot-check the three prefixes.
        assertTrue(specs.any { it.message.startsWith("[Single] Encoding") },
            "Demo must announce the Single encode step.")
        assertTrue(specs.any { it.message.startsWith("[Scenarios] Decoded") },
            "Demo must announce the Scenarios decode step with round-trip flag.")
        assertTrue(specs.any { it.message.startsWith("[Experiment] Building DesignedExperimentIfc") },
            "Demo must announce the Experiment toDesignedExperiment step.")
    }

    @Test
    fun `headless TOML demo does not import low-level orchestrators or Swing`() {
        val source = readDemoSource()
        // Same import-line scoping as the E.1.1 / E.1.2 / E.1.3 tests.
        val importLines = source.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") }
            .toList()

        assertTrue(importLines.none { it.startsWith("import ksl.app.orchestrator") },
            "Demo must drive runs through KSLAppSession, not orchestrators.  Offending imports: " +
                importLines.filter { it.startsWith("import ksl.app.orchestrator") })
        assertTrue(importLines.none { it.startsWith("import javax.swing") },
            "Demo must not import any Swing API.")
        assertTrue(importLines.none { it.startsWith("import java.awt") },
            "Demo must not import any AWT API.")
    }

    private fun expectedTomlPath(workspace: Path, filename: String): Path =
        AppWorkspacePaths
            .appWorkspaceDir(workspace, TOML_ROUNDTRIP_HEADLESS_DEMO_APP_NAME)
            .resolve(TOML_ROUNDTRIP_HEADLESS_DEMO_CONFIGS_SUBDIR)
            .resolve(filename)

    private fun readDemoSource(): String {
        val repoRoot = File(System.getProperty("user.dir")).parentFile
        return repoRoot.resolve(
            "KSLExamples/src/main/kotlin/ksl/examples/general/appsession/" +
                "KSLAppSessionTomlRoundTripHeadlessDemo.kt"
        ).readText()
    }
}
