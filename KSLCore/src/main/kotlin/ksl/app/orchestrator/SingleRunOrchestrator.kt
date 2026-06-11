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
import ksl.app.config.RunConfiguration
import ksl.app.config.sanitizeAnalysisName
import ksl.app.session.RunAttachmentIfc
import ksl.app.single.results.SingleAppPaths
import ksl.app.session.RunHandle
import ksl.app.session.RunRequest
import ksl.app.session.RunWarningType
import ksl.app.session.Runner
import ksl.simulation.ModelProviderIfc
import ksl.simulation.SimulationDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Thin facade over [Runner] for the single-model GUI use case.
 *
 * Low-level API note: application and UI code should prefer [KSLAppSession],
 * which owns scope lifecycle, validation, warning emission, and dispatch across
 * all supported run modes. This orchestrator remains public so lower-level
 * tests and advanced integrations can exercise the single-run path directly.
 *
 * Accepts a [RunConfiguration], builds the model via `RunConfiguration.buildModel`,
 * and delegates to [Runner.submit].  Returns the [RunHandle] directly so the caller
 * can observe lifecycle events and await the terminal [ksl.app.session.RunResult.Completed].
 *
 * ```kotlin
 * val handle = SingleRunOrchestrator.submit(config, provider)
 * launch { handle.events.collect { event -> updateUi(event) } }
 * when (val r = handle.result.await()) {
 *     is RunResult.Completed -> showResults(r.snapshot)
 *     is RunResult.Failed    -> showError(r.error)
 *     is RunResult.Cancelled -> showCancelled(r.reason)
 *     else -> {}
 * }
 * ```
 *
 * Pre-flight validation should be performed with
 * [ksl.app.validation.RunConfigurationValidator.validateForRun] before calling
 * [submit].  If `RunConfiguration.modelReference` is
 * [ksl.app.config.ModelReference.ByProviderId] and `provider` is `null`,
 * `RunConfiguration.buildModel` throws [IllegalStateException] synchronously —
 * before the coroutine is launched.
 */
object SingleRunOrchestrator {

    /**
     * Builds the model from [config] and submits it for asynchronous execution.
     *
     * @param config      the run configuration describing the model, controls,
     *                    and experiment parameters
     * @param provider    required when `RunConfiguration.modelReference` is
     *                    [ksl.app.config.ModelReference.ByProviderId]; ignored for
     *                    [ksl.app.config.ModelReference.ByJar]
     * @param attachments optional [RunAttachmentIfc] instances wired into the run
     *                    (e.g. [ksl.app.session.ReplicationDataAttachment])
     * @param scope       coroutine scope that owns the simulation coroutine
     * @param preRunWarnings validation warnings emitted before run lifecycle events
     * @return a [RunHandle] for observing progress and obtaining the result
     */
    fun submit(
        config: RunConfiguration,
        provider: ModelProviderIfc? = null,
        attachments: List<RunAttachmentIfc> = emptyList(),
        scope: CoroutineScope = CoroutineScope(SimulationDispatcher.default + SupervisorJob()),
        preRunWarnings: List<RunWarningType> = emptyList()
    ): RunHandle {
        require(config.scenarios.size == 1) {
            "RunSpec.Single requires exactly one ScenarioSpec; got ${config.scenarios.size}"
        }
        val spec = config.scenarios.single()
        val model = buildScenarioModel(spec, provider, config.outputConfig)
        return Runner().submit(RunRequest.SingleRun(model, attachments), scope, preRunWarnings)
    }

    /**
     * Builds the single scenario's model and applies its run parameters,
     * controls, RV overrides, and model configuration.  The shape mirrors
     * `ScenarioOrchestrator.buildScenario` but produces a configured
     * `Model` directly (rather than a `Scenario` wrapper) because
     * `RunRequest.SingleRun` consumes a `Model`.
     *
     * When [outputConfig].outputDirectory is non-null, the model's
     * [ksl.simulation.Model.outputDirectory] is replaced with a fresh
     * [ksl.utilities.io.OutputDirectory] rooted at that path so the
     * framework's runtime files (`kslOutput.txt`, csvDir, dbDir,
     * plotDir, etc.) land under the host's workspace rather than the
     * JVM launch directory.  The original constructor-supplied output
     * directory may have been eagerly materialized; that directory is
     * left in place as a harmless empty side-effect.
     */
    private fun buildScenarioModel(
        spec: ksl.app.config.ScenarioSpec,
        provider: ModelProviderIfc?,
        outputConfig: ksl.app.config.OutputConfig
    ): ksl.simulation.Model {
        val model = buildModelFromReference(spec.modelReference, provider)

        val baseParams = model.extractRunParameters().copy(experimentName = spec.name)
        val finalParams = (spec.runOverrides ?: ksl.app.config.ExperimentRunOverrides.EMPTY).applyTo(baseParams)
        model.changeRunParameters(finalParams)

        if (spec.controlOverrides.totalControls > 0) {
            model.controls().importAll(spec.controlOverrides)
        }

        if (spec.rvOverrides.isNotEmpty()) {
            val paramMap = spec.rvOverrides
                .groupBy { it.rvName }
                .mapValues { (_, list) -> list.associate { it.paramName to it.value } }
            val setter = ksl.utilities.random.rvariable.parameters.RVParameterSetter(model)
            setter.changeParameters(paramMap)
            setter.applyParameterChanges(model)
        }

        if (spec.modelConfiguration != null && model.modelConfigurationManager != null) {
            model.configuration = spec.modelConfiguration
        }

        outputConfig.outputDirectory?.let { pathString ->
            val outRoot = java.nio.file.Paths.get(pathString)
            model.outputDirectory = ksl.utilities.io.OutputDirectory(outRoot, "kslOutput.txt")
        }

        // Build the per-run filename stem for the CSV + SQLite artifacts
        // from the analyst-supplied output (analysis) name.  Falls back to
        // the model's own sanitised name when the analysis name is blank
        // OR the canonical [SingleAppPaths.UNTITLED] sentinel — matching
        // the same fallback `SingleAppPaths.appWorkspaceDir` uses for the
        // workspace folder name, so the directory layer and the artifact
        // layer trip the model-name fallback in lockstep.  Without the
        // explicit isBlank/UNTITLED check, `sanitizeAnalysisName("")`
        // returns "Untitled" (the substrate's empty-input default), which
        // would *not* be caught by `.ifBlank { ... }` downstream.
        val outputStem: String =
            if (outputConfig.analysisName.isBlank() ||
                outputConfig.analysisName == SingleAppPaths.UNTITLED) {
                model.simulationName.replace(" ", "_")
            } else {
                sanitizeAnalysisName(outputConfig.analysisName)
            }

        // Per-kind CSV reporting.  The `CSVReplicationReport` /
        // `CSVExperimentReport` constructors *self-attach* to the model in
        // their parent `CSVReport.init` block, so we don't follow up with
        // `model.attachModelElementObserver(...)` — that would throw
        // "already attached".  We also do NOT toggle
        // `model.autoReplicationCSVReports` / `autoExperimentCSVReports`:
        // the auto path in `Model.simulate()` instantiates a default
        // `CSVReplicationReport(model)` whose file name is model-name-
        // derived, which would race against our analysis-named report and
        // emit a second default-named file.  Constructing explicitly with
        // the analysis-derived stem and leaving the auto flags off gives
        // a single, correctly-named file per kind.
        if (outputConfig.enableReplicationCSV) {
            ksl.observers.textfile.CSVReplicationReport(
                model = model,
                reportName = "${outputStem}_CSVReplicationReport",
                directoryPath = model.outputDirectory.csvDir
            )
        }
        if (outputConfig.enableExperimentCSV) {
            ksl.observers.textfile.CSVExperimentReport(
                model = model,
                reportName = "${outputStem}_CSVExperimentReport",
                directoryPath = model.outputDirectory.csvDir
            )
        }

        // SQLite KSLDatabase observer.  Same outputStem story — explicit
        // [KSLDatabase] build with our stem instead of the default factory
        // that uses `model.simulationName`.  The observer constructor
        // attaches itself to the model (via `attachModelElementObserver`
        // inside its own init block); lifecycle is bound to the model —
        // when this fresh-per-Run model dies, the observer goes with it.
        // No explicit detach needed.
        if (outputConfig.enableKSLDatabase) {
            val db = ksl.utilities.io.dbutil.KSLDatabase(
                dbName = "$outputStem.db",
                dbDirectory = model.outputDirectory.dbDir
            )
            ksl.utilities.io.dbutil.KSLDatabaseObserver(model = model, db = db)
        }

        return model
    }

    private fun buildModelFromReference(
        ref: ksl.app.config.ModelReference,
        provider: ModelProviderIfc?
    ): ksl.simulation.Model = when (ref) {
        is ksl.app.config.ModelReference.ByProviderId -> {
            requireNotNull(provider) {
                "ModelProviderIfc required for ByProviderId reference '${ref.providerId}'"
            }
            provider.provideModel(ref.providerId)
        }
        is ksl.app.config.ModelReference.ByJar ->
            ksl.utilities.io.JARModelBuilder(ref.jarPath, ref.builderClassName).use { it.build() }
        is ksl.app.config.ModelReference.ByBundleAndModelId -> {
            require(provider is ksl.app.bundle.BundleModelProvider) {
                "ModelReference.ByBundleAndModelId requires a BundleModelProvider; got " +
                        (provider?.let { it::class.simpleName } ?: "null")
            }
            provider.provideModel(ref.bundleId, ref.modelId)
        }
        is ksl.app.config.ModelReference.Embedded -> {
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
