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

package ksl.examples.general.appsession

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RVParameterOverride
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationToml
import ksl.app.config.ScenarioSpec
import ksl.app.config.experiment.ControlBinding
import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.ExperimentConfiguration
import ksl.app.config.experiment.ExperimentConfigurationToml
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.toDesignedExperiment
import ksl.app.notification.NotificationSink
import ksl.app.session.AppWorkspacePaths
import ksl.app.session.RunResult
import ksl.controls.experiments.DesignedExperimentIfc
import ksl.examples.book.appendixD.GIGcQueue
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import java.nio.file.Files
import java.nio.file.Path

/**
 *  TOML round-trip validation demo for a host that has no Swing
 *  dependency.  Exercises the substrate config-persistence path
 *  that any non-Swing host (CLI, web service, headless test
 *  fixture) takes when loading a saved configuration from disk:
 *
 *    1. Build a config in memory (Single, Scenarios, or
 *       ExperimentConfiguration).
 *    2. Encode to TOML via the substrate codec object
 *       ([RunConfigurationToml.encode] or
 *       [ExperimentConfigurationToml.encode]).
 *    3. Write the TOML text to a real file on disk.
 *    4. Read the file back from disk via `Files.readString`.
 *    5. Decode through the symmetric `.decode(text)` entry point.
 *    6. Assert structural equality with the in-memory original.
 *    7. Submit the *decoded* config through [KSLAppSession] and
 *       assert the run produces the expected substrate outcome.
 *
 *  Three independent round-trips run in sequence against a single
 *  [KSLAppSession]:
 *
 *  - **Single** — `RunConfiguration` with one MM1 scenario →
 *    [RunSpec.Single] → `RunResult.Completed`.
 *  - **Scenarios** — `RunConfiguration` with two MM1 scenarios →
 *    [RunSpec.Scenarios] → `RunResult.BatchCompleted` with two
 *    snapshots.
 *  - **Experiment** — `ExperimentConfiguration` with 2 factors and
 *    a 2² full-factorial design → decoded → built into a
 *    `DesignedExperimentIfc` via
 *    [ExperimentConfiguration.toDesignedExperiment] → submitted as
 *    [RunSpec.Experiment] → `RunResult.BatchCompleted` with 4
 *    design-point snapshots.
 *
 *  Calls into [KSLAppSession], [RunConfigurationToml],
 *  [ExperimentConfigurationToml], [AppWorkspacePaths], and
 *  [NotificationSink] only.  No reach into low-level orchestrators
 *  (`ksl.app.orchestrator.*`), no Swing or AWT imports — the test
 *  asserts both invariants by reading this source.
 *
 *  Part of Phase E.1.4 — substrate-validation reference demos.
 */
suspend fun main() {
    val workspace = Files.createTempDirectory("ksl-toml-roundtrip-demo-")
    val notifier = NotificationSink.Collecting()
    val report = runKSLAppSessionTomlRoundTripHeadlessDemo(workspace, notifier, ::println)
    println("== Final demo summary ==")
    println("  workspace:       $workspace")
    println("  single:          ${report.singleOutcome.runResult::class.simpleName}, " +
        "round-trip equal = ${report.singleOutcome.configsEqual}")
    println("  scenario:        ${report.scenarioOutcome.runResult::class.simpleName}, " +
        "round-trip equal = ${report.scenarioOutcome.configsEqual}")
    println("  experiment:      ${report.experimentOutcome.runResult::class.simpleName}, " +
        "round-trip equal = ${report.experimentOutcome.configsEqual}")
    println("  notifications:   ${notifier.specs().size}")
    println()
    notifier.specs().forEachIndexed { i, spec ->
        println("    [${spec.severity}] $i. ${spec.message}")
    }
}

/**
 *  Application identifier — per-app subdirectory under the
 *  caller-supplied workspace.
 */
const val TOML_ROUNDTRIP_HEADLESS_DEMO_APP_NAME: String =
    "KSLAppSessionTomlRoundTripHeadlessDemo"

/**
 *  Subdirectory under the appWorkspace where round-trip TOML files
 *  are written.  Not a substrate `AppWorkspacePaths` helper — this
 *  is a demo-local convention parallel to the single-doc apps' own
 *  configs/ directory.
 */
const val TOML_ROUNDTRIP_HEADLESS_DEMO_CONFIGS_SUBDIR: String = "configs"

/** Single workflow's TOML filename stem. */
const val TOML_ROUNDTRIP_HEADLESS_DEMO_SINGLE_FILENAME: String = "single.toml"

/** Scenarios workflow's TOML filename stem. */
const val TOML_ROUNDTRIP_HEADLESS_DEMO_SCENARIOS_FILENAME: String = "scenarios.toml"

/** Experiment workflow's TOML filename stem. */
const val TOML_ROUNDTRIP_HEADLESS_DEMO_EXPERIMENT_FILENAME: String = "experiment.toml"

/** Names of the two scenarios in the round-trip scenario fixture. */
val TOML_ROUNDTRIP_HEADLESS_DEMO_SCENARIO_NAMES: List<String> =
    listOf("LowLoad", "MediumLoad")

/** Design points in the round-trip 2² experiment fixture. */
const val TOML_ROUNDTRIP_HEADLESS_DEMO_EXPERIMENT_DESIGN_POINTS: Int = 4

private const val MM1_ID: String = "TomlRoundTripMM1"
private const val LK_ID: String = "TomlRoundTripLK"
private const val EXPERIMENT_REPS_PER_POINT: Int = 5
private const val TIMEOUT_MS: Long = 90_000L

/**
 *  Run all three TOML round-trip workflows against [workspace] in
 *  sequence.  All notifications go to [notifier]; [writeLine]
 *  mirrors the smoke demo's console-trace hook.
 */
suspend fun runKSLAppSessionTomlRoundTripHeadlessDemo(
    workspace: Path,
    notifier: NotificationSink = NotificationSink.NOOP,
    writeLine: (String) -> Unit = {}
): KSLAppSessionTomlRoundTripHeadlessDemoReport {
    notifier.info("Starting headless TOML round-trip demo at workspace: $workspace")
    writeLine("[demo] workspace = $workspace")

    val appWorkspace = AppWorkspacePaths.appWorkspaceDir(
        workspace,
        TOML_ROUNDTRIP_HEADLESS_DEMO_APP_NAME
    )
    val configsDir = appWorkspace.resolve(TOML_ROUNDTRIP_HEADLESS_DEMO_CONFIGS_SUBDIR)
    Files.createDirectories(configsDir)
    writeLine("[demo] configsDir = $configsDir")

    val mm1Provider = buildMm1Provider()
    val lkProvider = buildLkProvider()

    return KSLAppSession(mm1Provider).use { mm1Session ->
        val singleOutcome = singleRoundTrip(mm1Session, configsDir, notifier, writeLine)
        val scenarioOutcome = scenarioRoundTrip(mm1Session, configsDir, notifier, writeLine)
        // Experiment fixture lives in its own session because the
        // provider must serve the LK model.
        KSLAppSession(lkProvider).use { lkSession ->
            val experimentOutcome = experimentRoundTrip(
                lkSession,
                configsDir,
                appWorkspace.resolve("experiment-output"),
                notifier,
                writeLine
            )
            KSLAppSessionTomlRoundTripHeadlessDemoReport(
                appWorkspace = appWorkspace,
                configsDir = configsDir,
                singleOutcome = singleOutcome,
                scenarioOutcome = scenarioOutcome,
                experimentOutcome = experimentOutcome
            )
        }
    }.also {
        notifier.info(
            "TOML round-trip demo complete.  Single = ${it.singleOutcome.runResult::class.simpleName}, " +
                "Scenarios = ${it.scenarioOutcome.runResult::class.simpleName}, " +
                "Experiment = ${it.experimentOutcome.runResult::class.simpleName}."
        )
    }
}

/**
 *  Top-level demo report.  Each per-workflow outcome carries the
 *  TOML file path, the in-memory original config, the decoded
 *  config, a structural-equality flag, and the terminal
 *  [RunResult] from submitting the decoded config.
 */
data class KSLAppSessionTomlRoundTripHeadlessDemoReport(
    val appWorkspace: Path,
    val configsDir: Path,
    val singleOutcome: SingleRoundTripOutcome,
    val scenarioOutcome: ScenarioRoundTripOutcome,
    val experimentOutcome: ExperimentRoundTripOutcome
)

data class SingleRoundTripOutcome(
    val tomlFile: Path,
    val original: RunConfiguration,
    val decoded: RunConfiguration,
    val configsEqual: Boolean,
    val runResult: RunResult
)

data class ScenarioRoundTripOutcome(
    val tomlFile: Path,
    val original: RunConfiguration,
    val decoded: RunConfiguration,
    val configsEqual: Boolean,
    val runResult: RunResult
)

data class ExperimentRoundTripOutcome(
    val tomlFile: Path,
    val original: ExperimentConfiguration,
    val decoded: ExperimentConfiguration,
    val configsEqual: Boolean,
    val runResult: RunResult
)

// ── Per-workflow round-trip helpers ──────────────────────────────────

private suspend fun singleRoundTrip(
    session: KSLAppSession,
    configsDir: Path,
    notifier: NotificationSink,
    writeLine: (String) -> Unit
): SingleRoundTripOutcome {
    val original = buildSingleConfig()
    notifier.info("[Single] Encoding RunConfiguration to TOML.")
    val tomlText = RunConfigurationToml.encode(original)
    val tomlFile = configsDir.resolve(TOML_ROUNDTRIP_HEADLESS_DEMO_SINGLE_FILENAME)
    Files.writeString(tomlFile, tomlText)
    writeLine("[demo] single.toml = $tomlFile (${tomlText.length} chars)")
    notifier.info("[Single] Wrote $tomlFile.")

    val readBack = Files.readString(tomlFile)
    val decoded = RunConfigurationToml.decode(readBack)
    val equal = decoded == original
    notifier.info("[Single] Decoded TOML; round-trip-equal = $equal.")

    notifier.info("[Single] Submitting decoded config via KSLAppSession.")
    val handle = session.submit(RunSpec.Single(decoded))
    val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
    notifier.info("[Single] Run terminal: ${result::class.simpleName}.")

    return SingleRoundTripOutcome(
        tomlFile = tomlFile,
        original = original,
        decoded = decoded,
        configsEqual = equal,
        runResult = result
    )
}

private suspend fun scenarioRoundTrip(
    session: KSLAppSession,
    configsDir: Path,
    notifier: NotificationSink,
    writeLine: (String) -> Unit
): ScenarioRoundTripOutcome {
    val original = buildScenariosConfig()
    notifier.info("[Scenarios] Encoding ${original.scenarios.size}-scenario RunConfiguration to TOML.")
    val tomlText = RunConfigurationToml.encode(original)
    val tomlFile = configsDir.resolve(TOML_ROUNDTRIP_HEADLESS_DEMO_SCENARIOS_FILENAME)
    Files.writeString(tomlFile, tomlText)
    writeLine("[demo] scenarios.toml = $tomlFile (${tomlText.length} chars)")
    notifier.info("[Scenarios] Wrote $tomlFile.")

    val readBack = Files.readString(tomlFile)
    val decoded = RunConfigurationToml.decode(readBack)
    val equal = decoded == original
    notifier.info("[Scenarios] Decoded TOML; round-trip-equal = $equal.")

    notifier.info("[Scenarios] Submitting decoded config via KSLAppSession.")
    val handle = session.submit(RunSpec.Scenarios(decoded))
    val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
    notifier.info("[Scenarios] Run terminal: ${result::class.simpleName}.")

    return ScenarioRoundTripOutcome(
        tomlFile = tomlFile,
        original = original,
        decoded = decoded,
        configsEqual = equal,
        runResult = result
    )
}

private suspend fun experimentRoundTrip(
    session: KSLAppSession,
    configsDir: Path,
    pathToOutputDirectory: Path,
    notifier: NotificationSink,
    writeLine: (String) -> Unit
): ExperimentRoundTripOutcome {
    Files.createDirectories(pathToOutputDirectory)
    val original = buildExperimentConfig()
    notifier.info("[Experiment] Encoding ExperimentConfiguration to TOML.")
    val tomlText = ExperimentConfigurationToml.encode(original)
    val tomlFile = configsDir.resolve(TOML_ROUNDTRIP_HEADLESS_DEMO_EXPERIMENT_FILENAME)
    Files.writeString(tomlFile, tomlText)
    writeLine("[demo] experiment.toml = $tomlFile (${tomlText.length} chars)")
    notifier.info("[Experiment] Wrote $tomlFile.")

    val readBack = Files.readString(tomlFile)
    val decoded = ExperimentConfigurationToml.decode(readBack)
    val equal = decoded == original
    notifier.info("[Experiment] Decoded TOML; round-trip-equal = $equal.")

    notifier.info("[Experiment] Building DesignedExperimentIfc via toDesignedExperiment(...).")
    val builder = lkBuilder()
    val experiment: DesignedExperimentIfc = decoded.toDesignedExperiment(
        modelBuilder = builder,
        pathToOutputDirectory = pathToOutputDirectory,
        kslDatabase = null,
        name = "TomlRoundTripExperiment"
    )

    notifier.info("[Experiment] Submitting decoded experiment via KSLAppSession.")
    val runConfig = RunConfiguration(
        scenarios = listOf(
            ScenarioSpec(
                name = "single",
                modelReference = ModelReference.ByProviderId(LK_ID)
            )
        )
    )
    val handle = session.submit(
        RunSpec.Experiment(
            config = runConfig,
            experiment = experiment,
            numRepsPerDesignPoint = EXPERIMENT_REPS_PER_POINT
        )
    )
    val result = withTimeout(TIMEOUT_MS) { handle.result.await() }
    notifier.info("[Experiment] Run terminal: ${result::class.simpleName}.")

    return ExperimentRoundTripOutcome(
        tomlFile = tomlFile,
        original = original,
        decoded = decoded,
        configsEqual = equal,
        runResult = result
    )
}

// ── Fixtures ─────────────────────────────────────────────────────────

private fun buildMm1Provider(): ModelProviderIfc =
    MapModelProvider(MM1_ID, object : ModelBuilderIfc {
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

private fun buildLkProvider(): ModelProviderIfc =
    MapModelProvider(LK_ID, lkBuilder())

private fun lkBuilder(): ModelBuilderIfc = object : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model(LK_ID, autoCSVReports = false)
        LKInventoryModel(model, "Inventory")
        model.lengthOfReplication = 120.0
        model.numberOfReplications = EXPERIMENT_REPS_PER_POINT
        model.lengthOfReplicationWarmUp = 20.0
        return model
    }
}

private fun buildSingleConfig(): RunConfiguration =
    RunConfiguration(
        scenarios = listOf(
            ScenarioSpec(
                name = "TomlRoundTripSingle",
                modelReference = ModelReference.ByProviderId(MM1_ID),
                runOverrides = ExperimentRunOverrides(
                    numberOfReplications = 3,
                    lengthOfReplication = 100.0,
                    lengthOfReplicationWarmUp = 10.0
                )
            )
        )
    )

private fun buildScenariosConfig(): RunConfiguration =
    RunConfiguration(
        scenarios = listOf(
            ScenarioSpec(
                name = TOML_ROUNDTRIP_HEADLESS_DEMO_SCENARIO_NAMES[0],
                modelReference = ModelReference.ByProviderId(MM1_ID),
                runOverrides = ExperimentRunOverrides(
                    numberOfReplications = 3,
                    lengthOfReplication = 100.0,
                    lengthOfReplicationWarmUp = 10.0
                ),
                rvOverrides = listOf(
                    RVParameterOverride("$MM1_ID:ServiceTime", "mean", 0.3)
                )
            ),
            ScenarioSpec(
                name = TOML_ROUNDTRIP_HEADLESS_DEMO_SCENARIO_NAMES[1],
                modelReference = ModelReference.ByProviderId(MM1_ID),
                runOverrides = ExperimentRunOverrides(
                    numberOfReplications = 3,
                    lengthOfReplication = 100.0,
                    lengthOfReplicationWarmUp = 10.0
                ),
                rvOverrides = listOf(
                    RVParameterOverride("$MM1_ID:ServiceTime", "mean", 0.6)
                )
            )
        )
    )

private fun buildExperimentConfig(): ExperimentConfiguration =
    ExperimentConfiguration(
        modelReference = ModelReference.ByProviderId(LK_ID),
        factors = listOf(
            FactorSpec(
                name = "OrderQuantity",
                levels = listOf(5.0, 20.0),
                binding = ControlBinding.Control(controlKey = "Inventory.orderQuantity")
            ),
            FactorSpec(
                name = "ReorderPoint",
                levels = listOf(1.0, 5.0),
                binding = ControlBinding.Control(controlKey = "Inventory.reorderPoint")
            )
        ),
        designSpec = DesignSpec.FullFactorial
    )

/**
 *  Convenience entry point for environments that aren't already in
 *  a suspending context (e.g. a Java `main`).
 */
fun runKSLAppSessionTomlRoundTripHeadlessDemoBlocking(
    workspace: Path,
    notifier: NotificationSink = NotificationSink.NOOP,
    writeLine: (String) -> Unit = {}
): KSLAppSessionTomlRoundTripHeadlessDemoReport = runBlocking {
    runKSLAppSessionTomlRoundTripHeadlessDemo(workspace, notifier, writeLine)
}
