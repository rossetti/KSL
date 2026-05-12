package ksl.app.swing.simopt

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
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.session.KSLRuntimeError
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.bundle.BundleLoader
import ksl.app.bundle.BundleModelProvider
import ksl.examples.general.appsupport.LKInventoryBundle
import ksl.simulation.ModelProviderIfc

/**
 * Observable state surface of the simulation-optimization reference app.
 *
 * Drives [RunSpec.Optimization] runs and tracks per-iteration progress
 * through the [RunEvent.OptimizationRunStarted] / [RunEvent.IterationCompleted]
 * event sequence emitted by `OptimizationOrchestrator`.
 *
 * A fresh [OptimizationRunConfiguration] is constructed on each model
 * switch via [BundledOptimizations.forModel].
 */
internal class SimoptAppViewModel(
    initialModelId: String = LKInventoryBundle.MODEL_ID,
    private val provider: ModelProviderIfc = BundleModelProvider(BundleLoader.loadFromClasspath()),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Swing)
) : AutoCloseable {

    private val session: KSLAppSession = KSLAppSession(provider, scope)

    var selectedModelId: String = initialModelId
        private set

    /** The current bundled optimization configuration.  Replaced on model switch. */
    var optimization: OptimizationRunConfiguration = BundledOptimizations.forModel(initialModelId, provider)
        private set

    private val myUiState: MutableStateFlow<UiState> = MutableStateFlow(UiState.Idle)
    val uiState: StateFlow<UiState> = myUiState.asStateFlow()

    private var activeRun: ActiveRun? = null

    /** Select a different bundled model.  Builds a fresh optimization
     *  config from the bundle.  Has no effect while a run is in flight. */
    fun selectModel(modelId: String) {
        if (myUiState.value is UiState.Running) return
        require(modelId in BundledOptimizations.supportedModelIds) {
            "No bundled optimization for model id: $modelId"
        }
        selectedModelId = modelId
        optimization = BundledOptimizations.forModel(modelId, provider)
    }

    /** Submits the current optimization configuration and transitions
     *  through Submitting → Running → terminal.  Has no effect when a
     *  run is already in flight. */
    fun submit() {
        if (activeRun != null) return
        myUiState.value = UiState.Submitting

        val handle = session.submit(RunSpec.Optimization(optimization))

        val progressJob = scope.launch {
            handle.events.collect { event ->
                when (event) {
                    is RunEvent.OptimizationRunStarted ->
                        myUiState.value = UiState.Running(
                            iterationsCompleted = 0,
                            maxIterations = event.maxIterations
                        )
                    is RunEvent.IterationCompleted -> {
                        val prev = myUiState.value
                        val maxIters = (prev as? UiState.Running)?.maxIterations
                            ?: event.iteration
                        myUiState.value = UiState.Running(
                            iterationsCompleted = event.iteration,
                            maxIterations = maxIters
                        )
                    }
                    else -> Unit
                }
            }
        }

        val resultJob = scope.launch {
            val result = handle.result.await()
            progressJob.cancel()
            myUiState.value = when (result) {
                is RunResult.OptimizationCompleted -> UiState.Completed(result)
                is RunResult.Cancelled             -> UiState.Cancelled(result.reason)
                is RunResult.Failed                -> UiState.Failed(result.error)
                is RunResult.Completed,
                is RunResult.BatchCompleted ->
                    UiState.Failed(KSLRuntimeError.ConfigurationError(
                        message = "Unexpected result type for optimization spec: $result",
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
        scope.cancel("SimoptAppViewModel closed")
    }

    private data class ActiveRun(
        val handle: RunHandle,
        val progressJob: Job,
        val resultJob: Job
    )
}

/**
 * Discriminated UI state for the simulation-optimization app.  Same
 * skeleton as the Experiment module but Running tracks solver-iteration
 * progress instead of design-point progress.
 */
internal sealed class UiState {
    data object Idle : UiState()
    data object Submitting : UiState()
    data class Running(
        val iterationsCompleted: Int,
        val maxIterations: Int
    ) : UiState()
    data class Completed(val result: RunResult.OptimizationCompleted) : UiState()
    data class Cancelled(val reason: String) : UiState()
    data class Failed(val error: KSLRuntimeError) : UiState()
}
