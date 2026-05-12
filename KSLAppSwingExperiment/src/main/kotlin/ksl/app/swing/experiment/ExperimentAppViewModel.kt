package ksl.app.swing.experiment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.session.KSLRuntimeError
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.app.bundle.BundleLoader
import ksl.app.bundle.BundleModelProvider
import ksl.examples.general.appsupport.LKInventoryBundle

/**
 * Observable state surface of the designed-experiment reference app.
 *
 * Drives `RunSpec.Experiment` runs and tracks per-design-point progress
 * through the [RunEvent.ExperimentRunStarted] / [RunEvent.DesignPointCompleted]
 * event sequence emitted by `ExperimentOrchestrator`.
 *
 * A fresh [ParallelDesignedExperiment] is constructed on each model
 * switch (since the type owns mutable state including a `KSLDatabase`).
 */
internal class ExperimentAppViewModel(
    initialModelId: String = LKInventoryBundle.MODEL_ID,
    private val provider: BundleModelProvider = BundleModelProvider(BundleLoader.loadFromClasspath()),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Swing)
) : AutoCloseable {

    private val session: KSLAppSession = KSLAppSession(provider, scope)

    var selectedModelId: String = initialModelId
        private set

    /** The current bundled experiment.  Replaced on model switch. */
    var experiment: ParallelDesignedExperiment = BundledExperiments.forModel(initialModelId, provider)
        private set

    private val myUiState: MutableStateFlow<UiState> = MutableStateFlow(UiState.Idle)
    val uiState: StateFlow<UiState> = myUiState.asStateFlow()

    private var activeRun: ActiveRun? = null

    /** Select a different bundled model.  Builds a fresh experiment from
     *  the bundle.  Has no effect while a run is in flight. */
    fun selectModel(modelId: String) {
        if (myUiState.value is UiState.Running) return
        require(modelId in BundledExperiments.supportedModelIds) {
            "No bundled experiment for model id: $modelId"
        }
        selectedModelId = modelId
        experiment = BundledExperiments.forModel(modelId, provider)
    }

    /** Submits the current designed experiment and transitions through
     *  Submitting → Running → terminal.  Has no effect when a run is
     *  already in flight. */
    fun submit() {
        if (activeRun != null) return
        myUiState.value = UiState.Submitting

        val baseParams = provider.provideModel(selectedModelId).extractRunParameters()
        val config = RunConfiguration(
            modelReference = ModelReference.ByProviderId(selectedModelId),
            experimentRunParameters = baseParams
        )
        val handle = session.submit(RunSpec.Experiment(config, experiment))

        val progressJob = scope.launch {
            handle.events.collect { event ->
                when (event) {
                    is RunEvent.ExperimentRunStarted ->
                        myUiState.value = UiState.Running(
                            pointsCompleted = 0,
                            totalPoints = event.totalDesignPoints
                        )
                    is RunEvent.DesignPointCompleted ->
                        myUiState.value = UiState.Running(
                            pointsCompleted = event.index,
                            totalPoints = event.totalDesignPoints
                        )
                    else -> Unit
                }
            }
        }

        val resultJob = scope.launch {
            val result = handle.result.await()
            progressJob.cancel()
            myUiState.value = when (result) {
                is RunResult.BatchCompleted -> UiState.Completed(result)
                is RunResult.Cancelled      -> UiState.Cancelled(result.reason)
                is RunResult.Failed         -> UiState.Failed(result.error)
                is RunResult.Completed,
                is RunResult.OptimizationCompleted ->
                    UiState.Failed(KSLRuntimeError.ConfigurationError(
                        message = "Unexpected result type for experiment spec: $result",
                        validationResult = null
                    ))
            }
            activeRun = null
        }

        activeRun = ActiveRun(handle, progressJob, resultJob)
    }

    fun cancel() {
        val run = activeRun ?: return
        run.handle.cancel("user cancelled from GUI")
    }

    fun acknowledgeResult() {
        if (myUiState.value is UiState.Running || myUiState.value is UiState.Submitting) return
        myUiState.value = UiState.Idle
    }

    override fun close() {
        session.close()
        scope.cancel("ExperimentAppViewModel closed")
    }

    private data class ActiveRun(
        val handle: RunHandle,
        val progressJob: Job,
        val resultJob: Job
    )
}

/**
 * Discriminated UI state for the designed-experiment app.  Same skeleton
 * as `ksl.app.swing.scenario.UiState` but the Running variant tracks
 * design-point progress.
 */
internal sealed class UiState {
    data object Idle : UiState()
    data object Submitting : UiState()
    data class Running(
        val pointsCompleted: Int,
        val totalPoints: Int
    ) : UiState()
    data class Completed(val result: RunResult.BatchCompleted) : UiState()
    data class Cancelled(val reason: String) : UiState()
    data class Failed(val error: KSLRuntimeError) : UiState()
}
