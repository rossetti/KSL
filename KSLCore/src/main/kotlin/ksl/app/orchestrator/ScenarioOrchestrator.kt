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
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.session.*
import ksl.controls.experiments.ConcurrentScenarioRunner
import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.Scenario
import ksl.simulation.*
import ksl.utilities.io.JARModelBuilder
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import ksl.simulation.IterativeProcessIfc.EndingStatus

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
     * @param provider required when [RunConfiguration.modelReference] is
     *                 [ModelReference.ByProviderId]
     * @param scope    coroutine scope that owns the orchestrator coroutine
     * @return a [RunHandle] for observing progress and obtaining the result
     */
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
                var completedIdx = 0

                val modelIdentifier: String = when (val ref = config.modelReference) {
                    is ModelReference.ByProviderId -> ref.providerId
                    is ModelReference.ByJar -> ref.builderClassName ?: ref.jarPath
                    is ModelReference.ByBundleAndModelId -> "${ref.bundleId}/${ref.modelId}"
                }
                lifecycle.emitProgress(
                    RunEvent.ScenarioRunStarted(
                        runId = runId,
                        modelIdentifier = modelIdentifier,
                        totalScenarios = totalScenarios,
                        startTime = beginTime
                    )
                )

                val outputDir = KSL.createSubDirectory("scenario_run_$runId")
                val runner = ConcurrentScenarioRunner(
                    "ScenarioOrchestrator_$runId", scenarios, outputDir
                )

                runner.simulate(onScenarioComplete = { scenarioName, snapshot ->
                    completedIdx++
                    capturedSnapshots.add(snapshot)
                    lifecycle.emitProgress(
                        RunEvent.ScenarioCompleted(scenarioName, completedIdx, totalScenarios, snapshot)
                    )
                })

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
                            modelIdentifier = config.modelReference.displayId(),
                            experimentName = "ScenarioOrchestrator",
                            requestedReplications = totalScenarios,
                            completedReplications = successSnapshots.size,
                            endingStatus = endingStatus,
                            beginTime = beginTime,
                            endTime = endTime
                        )
                    )
                lifecycle.complete(
                    RunResult.BatchCompleted(orchestratorSummary, successSnapshots),
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

        return RunHandleImpl(lifecycle, job)
    }

    private fun buildScenarios(
        config: RunConfiguration,
        provider: ModelProviderIfc?
    ): List<Scenario> = config.scenarios.map { spec -> buildScenario(config, spec, provider) }

    private fun buildScenario(
        config: RunConfiguration,
        spec: ScenarioSpec,
        provider: ModelProviderIfc?
    ): Scenario {
        val builder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val model = when (val ref = config.modelReference) {
                    is ModelReference.ByProviderId -> {
                        requireNotNull(provider) {
                            "ModelProviderIfc required for ByProviderId reference '${ref.providerId}'"
                        }
                        provider.provideModel(ref.providerId)
                    }
                    is ModelReference.ByJar ->
                        JARModelBuilder(ref.jarPath, ref.builderClassName).use { it.build() }
                    is ModelReference.ByBundleAndModelId ->
                        throw UnsupportedOperationException(
                            "ModelReference.ByBundleAndModelId is not yet supported by " +
                                    "ScenarioOrchestrator.buildScenario; this branch is replaced " +
                                    "by the follow-up commit that reshapes ScenarioSpec to be " +
                                    "self-contained (workflow doc §3 OQ1)."
                        )
                }

                // apply parent controls first, then scenario controls (last-write-wins)
                if (config.controls.totalControls > 0) {
                    model.controls().importAll(config.controls)
                }
                if (spec.controls.totalControls > 0) {
                    model.controls().importAll(spec.controls)
                }

                // apply parent RV overrides, then scenario RV overrides (last-write-wins)
                val allOverrides = config.rvOverrides + spec.rvOverrides
                if (allOverrides.isNotEmpty()) {
                    val paramMap = allOverrides
                        .groupBy { it.rvName }
                        .mapValues { (_, list) -> list.associate { it.paramName to it.value } }
                    val setter = RVParameterSetter(model)
                    setter.changeParameters(paramMap)
                    setter.applyParameterChanges(model)
                }

                // apply model configuration if spec provides one, else fall back to call-site arg
                val effectiveConfig = spec.modelConfiguration ?: modelConfiguration
                if (effectiveConfig != null && model.modelConfigurationManager != null) {
                    model.configuration = effectiveConfig
                }

                return model
            }
        }

        return Scenario(
            modelBuilder = builder,
            name = spec.name,
            inputs = emptyMap(),
            stringInputs = emptyMap(),
            jsonInputs = emptyMap(),
            runParameters = spec.runParameters
        )
    }
}

/** Extracts a human-readable identifier string from a [ModelReference]. */
private fun ModelReference.displayId(): String = when (this) {
    is ModelReference.ByProviderId         -> providerId
    is ModelReference.ByJar                -> jarPath
    is ModelReference.ByBundleAndModelId   -> "$bundleId/$modelId"
}
