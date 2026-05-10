package ksl.app.swing.single

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
import ksl.controls.experiments.ExperimentRunParameters
import ksl.simulation.ModelProviderIfc

/**
 * Observable state surface of the single-model run app.
 *
 * The view model owns the editable [RunConfiguration], the bundled
 * [ModelProviderIfc], and the in-flight run (when any).  It exposes a
 * [StateFlow<UiState>] that the Swing layer collects from
 * `Dispatchers.Swing`; the view re-renders whenever `uiState` updates.
 *
 * Threading: the view model launches its run coroutines on [scope]; the
 * default scope uses [Dispatchers.Swing] so state updates land on the EDT
 * automatically when collected.  Callers that need a different dispatcher
 * (e.g. headless tests) may inject their own scope.
 */
internal class SingleAppViewModel(
    initialModelId: String = BundledModels.MM1_ID,
    private val provider: ModelProviderIfc = BundledModels.provider,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Swing)
) : AutoCloseable {

    private val session: KSLAppSession = KSLAppSession(provider, scope)

    /** The model identifier the user has selected from the bundled list. */
    var selectedModelId: String = initialModelId
        private set

    /** Editable run parameters. Initialised from the selected model's defaults. */
    var runParameters: ExperimentRunParameters = defaultRunParameters(initialModelId)
        private set

    private val myUiState: MutableStateFlow<UiState> = MutableStateFlow(UiState.Idle)
    val uiState: StateFlow<UiState> = myUiState.asStateFlow()

    private var activeRun: ActiveRun? = null

    /** Select a different bundled model.  Resets the editable run parameters to
     *  the selected model's defaults.  Has no effect while a run is in flight. */
    fun selectModel(modelId: String) {
        if (myUiState.value is UiState.Running) return
        require(modelId in BundledModels.availableModelIds) {
            "Unknown bundled model id: $modelId"
        }
        selectedModelId = modelId
        runParameters = defaultRunParameters(modelId)
    }

    /** Replace the editable run parameters wholesale.  Has no effect while a
     *  run is in flight. */
    fun updateRunParameters(updated: ExperimentRunParameters) {
        if (myUiState.value is UiState.Running) return
        runParameters = updated
    }

    /** Submits the current configuration and transitions the UI through
     *  Submitting → Running → terminal.  Has no effect when a run is already
     *  in flight. */
    fun submit() {
        if (activeRun != null) return
        myUiState.value = UiState.Submitting

        val config = RunConfiguration(
            modelReference = ModelReference.ByProviderId(selectedModelId),
            experimentRunParameters = runParameters
        )
        val handle = session.submit(RunSpec.Single(config))

        // Collect lifecycle events to surface replication progress.
        val progressJob = scope.launch {
            handle.events.collect { event ->
                when (event) {
                    is RunEvent.ReplicationRunStarted ->
                        myUiState.value = UiState.Running(
                            replicationsCompleted = 0,
                            totalReplications = event.totalReplications
                        )
                    is RunEvent.ReplicationEnded ->
                        myUiState.value = UiState.Running(
                            replicationsCompleted = event.repNumber,
                            totalReplications = event.totalReplications
                        )
                    else -> Unit
                }
            }
        }

        // Wait for the terminal result on a separate coroutine.
        val resultJob = scope.launch {
            val result = handle.result.await()
            progressJob.cancel()
            myUiState.value = when (result) {
                is RunResult.Completed -> UiState.Completed(result)
                is RunResult.Cancelled -> UiState.Cancelled(result.reason)
                is RunResult.Failed    -> UiState.Failed(result.error)
                // BatchCompleted / OptimizationCompleted are not produced by RunSpec.Single,
                // but the result type is sealed across all run modes; surface defensively.
                is RunResult.BatchCompleted,
                is RunResult.OptimizationCompleted ->
                    UiState.Failed(KSLRuntimeError.ConfigurationError(
                        message = "Unexpected result type for single-run spec: $result",
                        validationResult = null
                    ))
            }
            activeRun = null
        }

        activeRun = ActiveRun(handle, progressJob, resultJob)
    }

    /** Requests cooperative cancellation of the in-flight run.  Has no effect
     *  when no run is in flight. */
    fun cancel() {
        val run = activeRun ?: return
        run.handle.cancel("user cancelled from GUI")
    }

    /** Resets the UI state back to [UiState.Idle] after a terminal state has
     *  been observed.  Has no effect while a run is in flight. */
    fun acknowledgeResult() {
        if (myUiState.value is UiState.Running || myUiState.value is UiState.Submitting) return
        myUiState.value = UiState.Idle
    }

    override fun close() {
        session.close()
        scope.cancel("SingleAppViewModel closed")
    }

    private fun defaultRunParameters(modelId: String): ExperimentRunParameters =
        provider.provideModel(modelId).extractRunParameters()

    private data class ActiveRun(
        val handle: RunHandle,
        val progressJob: Job,
        val resultJob: Job
    )
}

/**
 * Discriminated union of every UI state the single-run app can be in.
 *
 * The view renders one panel arrangement per variant; transitions are
 * driven by [SingleAppViewModel].
 */
internal sealed class UiState {

    /** No run in flight; form is editable. */
    data object Idle : UiState()

    /** User pressed Run; submission is in progress but the orchestrator has
     *  not yet emitted [RunEvent.ReplicationRunStarted]. */
    data object Submitting : UiState()

    /** The run is executing.  [replicationsCompleted] / [totalReplications]
     *  drive a progress display.  Cancel is enabled in this state. */
    data class Running(
        val replicationsCompleted: Int,
        val totalReplications: Int
    ) : UiState()

    /** Terminal — the run completed normally. */
    data class Completed(val result: RunResult.Completed) : UiState()

    /** Terminal — the run was cancelled. */
    data class Cancelled(val reason: String) : UiState()

    /** Terminal — the run failed.  Includes validation errors surfaced as
     *  [KSLRuntimeError.ConfigurationError]. */
    data class Failed(val error: KSLRuntimeError) : UiState()
}
