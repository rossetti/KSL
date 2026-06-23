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

import ksl.app.KSLAppSession
import ksl.app.config.DatabasePolicy
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.config.sanitizeAnalysisName
import ksl.app.session.*
import ksl.controls.experiments.ConcurrentScenarioRunner
import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.Scenario
import ksl.simulation.*
import ksl.utilities.io.JARModelBuilder
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import ksl.simulation.IterativeProcessIfc.EndingStatus
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Orchestrates a scenario-sweep run from a [RunConfiguration] whose
 * [RunConfiguration.scenarios] list is non-empty.
 *
 * Low-level API note: application and UI code should prefer [KSLAppSession],
 * which owns scope lifecycle, validation, warning emission, and dispatch across
 * all supported run modes. This orchestrator remains public so lower-level
 * tests and advanced integrations can exercise scenario execution directly.
 *
 * Each [ksl.app.config.ScenarioSpec] is translated into a [Scenario] with a
 * [ModelBuilderIfc] that builds a fresh model for each scenario and applies
 * the merged (parent + scenario) controls and RV overrides.  Scenarios run
 * concurrently via [ConcurrentScenarioRunner].
 *
 * The returned [RunHandle] emits:
 * - one [RunEvent.ScenarioCompleted] per scenario (in commit order, after all
 *   scenarios have finished executing)
 * - a terminal [RunEvent.RunCompleted] (or [RunEvent.RunFailed])
 *
 * The resolved [RunResult] is [RunResult.BatchCompleted] carrying the
 * [OrchestratorSummary] and one [SimulationSnapshot.ExperimentCompleted] per
 * successfully completed scenario.
 */
class ScenarioOrchestrator {

    /**
     * Submits the scenario sweep described by [config] for asynchronous execution.
     *
     * @param config   run configuration with a non-empty [RunConfiguration.scenarios] list
     * @param provider required when `RunConfiguration.modelReference` is
     *                 [ModelReference.ByProviderId]
     * @param scope    coroutine scope that owns the orchestrator coroutine
     * @return a [RunHandle] for observing progress and obtaining the result
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun submit(
        config: RunConfiguration,
        provider: ModelProviderIfc? = null,
        scope: CoroutineScope = CoroutineScope(SimulationDispatcher.default + SupervisorJob()),
        preRunWarnings: List<RunWarningType> = emptyList()
    ): RunHandle {
        require(config.scenarios.isNotEmpty()) {
            "RunConfiguration must contain at least one ScenarioSpec"
        }

        val runId = KSL.randomUUIDString()
        val lifecycle = RunLifecycle(runId, replay = 128, extraBufferCapacity = 64)
        // Published from inside the launch block once the runner is
        // constructed; the RunHandleImpl's per-scenario cancel
        // callback reads it lazily so the handle can be returned
        // before the runner exists.
        val runnerRef = java.util.concurrent.atomic.AtomicReference<ConcurrentScenarioRunner?>(null)

        val job = scope.launch(SimulationDispatcher.default, CoroutineStart.ATOMIC) {
            if (!lifecycle.tryStart()) return@launch

            val beginTime = Clock.System.now()
            try {
                ensureActive()

                for (warning in preRunWarnings) {
                    lifecycle.emitProgress(RunEvent.RunWarning(warning))
                }

                val scenarios = buildScenarios(config, provider)
                val totalScenarios = scenarios.size
                val capturedSnapshots = mutableListOf<SimulationSnapshot.ExperimentCompleted?>()
                val capturedReplications = mutableMapOf<String, List<SimulationSnapshot.ReplicationCompleted>>()
                var completedIdx = 0

                // The first scenario's model is the report-friendly summary for
                // run-level events; per-scenario events carry the scenario name,
                // so mixed-model documents still disambiguate cleanly downstream.
                val modelIdentifier: String = config.scenarios.first().modelReference.displayId()
                lifecycle.emitProgress(
                    RunEvent.ScenarioRunStarted(
                        runId = runId,
                        modelIdentifier = modelIdentifier,
                        totalScenarios = totalScenarios,
                        startTime = beginTime
                    )
                )

                // Honor a GUI-supplied workspace output path when present
                // — the runner overwrites each model's outputDirectory
                // from this path (see ConcurrentScenarioRunner line 402
                // and ScenarioRunner line 219), so CSV / KSL-database /
                // report artifacts land where the analyst can find them.
                // Falls back to a per-run subdir of KSL.outDir when the
                // caller (e.g. a programmatic test) hasn't configured a
                // workspace path.
                val outputDir = config.outputConfig.outputDirectory
                    ?.let { java.nio.file.Paths.get(it) }
                    ?: KSL.createSubDirectory("scenario_run_$runId")
                // The GUI-supplied branch above hands us a Path object
                // only — there's no guarantee the directory exists on
                // disk yet.  ConcurrentScenarioRunner immediately
                // constructs its default KSLDatabase inside this path,
                // and SQLite fails with SQLITE_CANTOPEN if the parent
                // dir is missing.  createDirectories is idempotent —
                // safe for the fallback branch where the dir already
                // exists.
                java.nio.file.Files.createDirectories(outputDir)
                // Resolve the run identity from the analysis name on
                // OutputConfig.  The sanitised form is the on-disk
                // identifier (directory / file stem); the un-sanitised
                // form is what the user typed.  Hosts already nest
                // outputDir under the sanitised name (see
                // `ScenarioAppController.submit()`), so the runner
                // name + the database file land in the right place
                // without further path arithmetic here.
                val runnerName = resolveRunnerName(config.outputConfig, runId)
                val kslDb = resolveKslDatabase(config.outputConfig, runnerName, outputDir)
                val runner = ConcurrentScenarioRunner(
                    name = runnerName,
                    scenarioList = scenarios,
                    pathToOutputDirectory = outputDir,
                    kslDb = kslDb
                )
                runnerRef.set(runner)

                runner.simulate(
                    onScenarioComplete = { scenarioName, snapshot ->
                        completedIdx++
                        capturedSnapshots.add(snapshot)
                        lifecycle.emitProgress(
                            RunEvent.ScenarioCompleted(scenarioName, completedIdx, totalScenarios, snapshot)
                        )
                    },
                    executionMode = config.executionMode,
                    onScenarioStart = { scenarioName, scenarioIndex, total ->
                        lifecycle.emitProgress(
                            RunEvent.ScenarioStarted(scenarioName, scenarioIndex, total)
                        )
                    },
                    onReplicationStart = { scenarioName, repNumber, totalReps ->
                        lifecycle.emitProgress(
                            RunEvent.ScenarioReplicationStarted(scenarioName, repNumber, totalReps)
                        )
                    },
                    onReplicationEnd = { scenarioName, repNumber, totalReps ->
                        lifecycle.emitProgress(
                            RunEvent.ScenarioReplicationEnded(scenarioName, repNumber, totalReps)
                        )
                    },
                    onScenarioReplications = { scenarioName, reps ->
                        capturedReplications[scenarioName] = reps
                    },
                    onScenarioReplicationsCompleted = { scenarioName, scenarioIndex, total ->
                        lifecycle.emitProgress(
                            RunEvent.ScenarioReplicationsCompleted(scenarioName, scenarioIndex, total)
                        )
                    }
                )

                val endTime = Clock.System.now()
                val successSnapshots = capturedSnapshots.filterNotNull()
                val failedCount = capturedSnapshots.count { it == null }
                val endingStatus = if (failedCount == 0) EndingStatus.COMPLETED_ALL_STEPS
                                   else EndingStatus.UNFINISHED

                val orchestratorSummary = OrchestratorSummary(
                    runId = runId,
                    orchestratorName = "ScenarioOrchestrator",
                    totalItems = totalScenarios,
                    completedItems = successSnapshots.size,
                    failedItems = failedCount,
                    beginTime = beginTime,
                    endTime = endTime
                )
                val completedEvent =
                    RunEvent.RunCompleted(
                        RunSummary(
                            runId = runId,
                            modelIdentifier = modelIdentifier,
                            experimentName = "ScenarioOrchestrator",
                            requestedReplications = totalScenarios,
                            completedReplications = successSnapshots.size,
                            endingStatus = endingStatus,
                            beginTime = beginTime,
                            endTime = endTime
                        )
                    )
                lifecycle.complete(
                    RunResult.BatchCompleted(
                        summary = orchestratorSummary,
                        snapshots = successSnapshots,
                        replicationsByItem = capturedReplications
                    ),
                    completedEvent
                )

            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    val reason = e.message ?: "Cancelled by user"
                    lifecycle.completeCancelled(reason)
                }
                throw e
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    val error = KSLRuntimeError.ExecutiveError(0.0, 0, e)
                    lifecycle.completeFailed(error)
                }
            }
        }

        return RunHandleImpl(
            lifecycle = lifecycle,
            job = job,
            onCancelScenario = { name -> runnerRef.get()?.cancelScenario(name) ?: false }
        )
    }

    private fun buildScenarios(
        config: RunConfiguration,
        provider: ModelProviderIfc?
    ): List<Scenario> = config.scenarios
        .filterNot { it.skipOnRun }
        .map { spec -> buildScenario(spec, provider) }

    /**
     * Resolves a self-contained [ScenarioSpec] into a runtime
     * [Scenario]. Each scenario is independent: its model comes from
     * `spec.modelReference`, its run parameters are computed as
     * `model.extractRunParameters() + spec.runOverrides.applyTo(...)` with
     * `experimentName` set to `spec.name`, and its control / RV / model-
     * configuration overrides come only from the spec.  There are no
     * document-level defaults that scenarios inherit.
     *
     * Workspace output redirection (CSV / KSL-database / report
     * artifacts landing under the caller's
     * [ksl.app.config.OutputConfig.outputDirectory]) is handled
     * outside this method — see [submit] where the orchestrator picks
     * the runner's `pathToOutputDirectory`.  The per-scenario runners
     * unconditionally overwrite `model.outputDirectory` from that
     * path, so setting it here would be silently clobbered.
     */
    private fun buildScenario(
        spec: ScenarioSpec,
        provider: ModelProviderIfc?
    ): Scenario {
        // Build a sample model to extract its defaults for run-parameter
        // resolution.  The Scenario then uses its own modelBuilder lambda
        // below to rebuild a fresh, isolated model each time it runs.
        val sampleModel = buildModelFromReference(spec.modelReference, provider)
        val baseParams = sampleModel.extractRunParameters().copy(experimentName = spec.name)
        val finalParams = (spec.runOverrides ?: ExperimentRunOverrides.EMPTY).applyTo(baseParams)

        val builder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val model = buildModelFromReference(spec.modelReference, provider)

                if (spec.controlOverrides.totalControls > 0) {
                    model.controls().importAll(spec.controlOverrides)
                }

                if (spec.rvOverrides.isNotEmpty()) {
                    val paramMap = spec.rvOverrides
                        .groupBy { it.rvName }
                        .mapValues { (_, list) -> list.associate { it.paramName to it.value } }
                    val setter = RVParameterSetter(model)
                    setter.changeParameters(paramMap)
                    setter.applyParameterChanges(model)
                }

                val effectiveConfig = spec.modelConfiguration ?: modelConfiguration
                if (effectiveConfig != null && model.modelConfigurationManager != null) {
                    model.configuration = effectiveConfig
                }

                // Per-scenario CSV reporting flags (Scenario app's per-spec
                // output toggles; see ScenarioSpec.enableReplicationCSV /
                // enableExperimentCSV).  Set before Runner takes the model
                // so Model.simulate() picks them up.  Independent of any
                // document-level OutputConfig CSV flags — Scenario's
                // GUI exposes only per-spec CSV.
                //
                // model.outputDirectory is *not* touched here — the
                // ScenarioRunner / ConcurrentScenarioRunner unconditionally
                // overwrites it inside their simulate loop with a subdir
                // of pathToOutputDirectory (see runner construction below
                // and ScenarioRunner.simulate line 219 /
                // ConcurrentScenarioRunner line 402).  Pointing the
                // workspace there is done at runner-construction time,
                // not here.
                model.autoReplicationCSVReports = spec.enableReplicationCSV
                model.autoExperimentCSVReports = spec.enableExperimentCSV

                return model
            }
        }

        return Scenario(
            modelBuilder = builder,
            name = spec.name,
            inputs = emptyMap(),
            stringInputs = emptyMap(),
            jsonInputs = emptyMap(),
            runParameters = finalParams
        )
    }

    /**
     * Resolves a [ModelReference] to a freshly-built [Model], dispatching
     * on the sealed-class variant.  `ByBundleAndModelId` requires
     * [provider] to be a `BundleModelProvider` (the only provider that
     * supports two-key lookup); the other two variants accept any
     * [ModelProviderIfc] or none.
     */
    private fun buildModelFromReference(
        ref: ModelReference,
        provider: ModelProviderIfc?
    ): Model = when (ref) {
        is ModelReference.ByProviderId -> {
            requireNotNull(provider) {
                "ModelProviderIfc required for ByProviderId reference '${ref.providerId}'"
            }
            provider.provideModel(ref.providerId)
        }
        is ModelReference.ByJar ->
            JARModelBuilder(ref.jarPath, ref.builderClassName).use { it.build() }
        is ModelReference.ByBundleAndModelId -> {
            require(provider is ksl.app.bundle.BundleModelProvider) {
                "ModelReference.ByBundleAndModelId requires a BundleModelProvider; got " +
                        (provider?.let { it::class.simpleName } ?: "null")
            }
            provider.provideModel(ref.bundleId, ref.modelId)
        }
        is ModelReference.Embedded -> {
            requireNotNull(provider) {
                "ModelProviderIfc required to resolve ModelReference.Embedded(\"${ref.modelName}\")"
            }
            require(provider.isModelProvided(ref.modelName)) {
                "Provider has no model with id '${ref.modelName}' for ModelReference.Embedded — " +
                        "was this configuration authored by a different app?"
            }
            provider.provideModel(ref.modelName)
        }
    }
}

/** Extracts a human-readable identifier string from a [ModelReference]. */
private fun ModelReference.displayId(): String = when (this) {
    is ModelReference.ByProviderId         -> providerId
    is ModelReference.ByJar                -> jarPath
    is ModelReference.ByBundleAndModelId   -> "$bundleId/$modelId"
    is ModelReference.Embedded             -> "embedded:$modelName"
}

/**
 *  Pick the [ConcurrentScenarioRunner] name for this run.  Prefers
 *  the user-controlled analysis name from [OutputConfig.analysisName]
 *  (sanitised), so the database file and any artifacts the runner
 *  derives from its name carry a stable, predictable identity across
 *  re-runs of the same document.  Falls back to the substrate's
 *  historical `ScenarioOrchestrator_<UUID>` form only when the
 *  caller (e.g. a low-level test that bypasses the GUI) hasn't set
 *  a meaningful analysis name — the field defaults to `"Untitled"`
 *  on a fresh `OutputConfig`, which still sanitises cleanly.
 */
private fun resolveRunnerName(outputConfig: OutputConfig, runId: String): String {
    val sanitised = sanitizeAnalysisName(outputConfig.analysisName)
    return sanitised.ifBlank { "ScenarioOrchestrator_$runId" }
}

/**
 *  Open the [KSLDatabase] for this run according to
 *  [OutputConfig.databasePolicy].
 *
 *  * `OVERWRITE` — delete `<runnerName>.db` (if present) before
 *    opening a fresh database with that stem.
 *  * `NEW` — open `<runnerName>_<yyyy-MM-dd_HHmmss>.db` alongside
 *    any existing file, leaving the old one untouched.
 *
 *  KSL's schema rejects re-inserting `SimulationRun` rows with
 *  experiment names that already appear in the database, so an
 *  "append to existing" mode would deadlock on a same-document
 *  re-run (every scenario carries the same name).  The two
 *  policies above are exhaustive.
 */
private fun resolveKslDatabase(
    outputConfig: OutputConfig,
    runnerName: String,
    outputDir: java.nio.file.Path
): KSLDatabase {
    val fileStem = when (outputConfig.databasePolicy) {
        DatabasePolicy.OVERWRITE -> {
            // Replace-in-place semantics: delete the prior file so
            // KSLDatabase opens a fresh SQLite file with no leftover
            // rows.  deleteIfExists is a no-op when the file is
            // absent (first run for this analysis name).
            val target = outputDir.resolve("$runnerName.db")
            java.nio.file.Files.deleteIfExists(target)
            runnerName
        }
        DatabasePolicy.NEW -> {
            // Side-by-side semantics: timestamp suffix on the file
            // stem.  Existing <runnerName>.db (or any prior NEW file)
            // is untouched.
            val timestamp = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")
            )
            "${runnerName}_$timestamp"
        }
    }
    return KSLDatabase("$fileStem.db", outputDir)
}
