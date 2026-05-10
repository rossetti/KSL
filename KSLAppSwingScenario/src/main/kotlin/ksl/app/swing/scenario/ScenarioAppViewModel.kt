package ksl.app.swing.scenario

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
import ksl.app.config.ScenarioSpec
import ksl.app.session.KSLRuntimeError
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.simulation.ModelProviderIfc

/**
 * Observable state surface of the scenario-sweep reference app.
 *
 * Drives `RunSpec.Scenarios` runs and tracks per-scenario progress through
 * the [RunEvent.ScenarioRunStarted] / [RunEvent.ScenarioCompleted] event
 * sequence emitted by `ScenarioOrchestrator`.
 */
internal class ScenarioAppViewModel(
    initialModelId: String = BundledModels.MM1_ID,
    private val provider: ModelProviderIfc = BundledModels.provider,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Swing)
) : AutoCloseable {

    private val session: KSLAppSession = KSLAppSession(provider, scope)

    var selectedModelId: String = initialModelId
        private set

    /** Read-only view of the currently selected model's bundled scenarios. */
    var scenarios: List<ScenarioSpec> = BundledScenarios.forModel(initialModelId)
        private set

    private val myUiState: MutableStateFlow<UiState> = MutableStateFlow(UiState.Idle)
    val uiState: StateFlow<UiState> = myUiState.asStateFlow()

    private var activeRun: ActiveRun? = null

    /** Select a different bundled model.  Resets the scenarios list to the new
     *  model's bundled sweep.  Has no effect while a run is in flight. */
    fun selectModel(modelId: String) {
        if (myUiState.value is UiState.Running) return
        require(modelId in BundledModels.availableModelIds) {
            "Unknown bundled model id: $modelId"
        }
        selectedModelId = modelId
        scenarios = BundledScenarios.forModel(modelId)
    }

    /** Submits the current scenario sweep and transitions through
     *  Submitting → Running → terminal.  Has no effect when a run is already
     *  in flight, or when the scenarios list is empty. */
    fun submit() {
        if (activeRun != null) return
        if (scenarios.isEmpty()) return
        myUiState.value = UiState.Submitting

        val baseParams = provider.provideModel(selectedModelId).extractRunParameters()
        val config = RunConfiguration(
            modelReference = ModelReference.ByProviderId(selectedModelId),
            experimentRunParameters = baseParams,
            scenarios = scenarios
        )
        val handle = session.submit(RunSpec.Scenarios(config))

        val progressJob = scope.launch {
            handle.events.collect { event ->
                when (event) {
                    is RunEvent.ScenarioRunStarted ->
                        myUiState.value = UiState.Running(
                            scenariosCompleted = 0,
                            totalScenarios = event.totalScenarios
                        )
                    is RunEvent.ScenarioCompleted ->
                        myUiState.value = UiState.Running(
                            scenariosCompleted = event.index,
                            totalScenarios = event.totalScenarios
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
                // The other RunResult variants are not produced by RunSpec.Scenarios.
                is RunResult.Completed,
                is RunResult.OptimizationCompleted ->
                    UiState.Failed(KSLRuntimeError.ConfigurationError(
                        message = "Unexpected result type for scenario spec: $result",
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
        scope.cancel("ScenarioAppViewModel closed")
    }

    private data class ActiveRun(
        val handle: RunHandle,
        val progressJob: Job,
        val resultJob: Job
    )
}

/**
 * Discriminated UI state for the scenario-sweep app.  Same skeleton as
 * `ksl.app.swing.single.UiState` but the Running variant tracks scenario
 * progress and the Completed variant carries [RunResult.BatchCompleted].
 */
internal sealed class UiState {
    data object Idle : UiState()
    data object Submitting : UiState()
    data class Running(
        val scenariosCompleted: Int,
        val totalScenarios: Int
    ) : UiState()
    data class Completed(val result: RunResult.BatchCompleted) : UiState()
    data class Cancelled(val reason: String) : UiState()
    data class Failed(val error: KSLRuntimeError) : UiState()
}
