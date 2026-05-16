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

package ksl.app.swing.single

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.settings.UserSettingsStore
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import ksl.controls.experiments.ExperimentRunDefaults
import ksl.simulation.MapModelProvider
import ksl.simulation.ModelBuilderIfc
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

/**
 * Internal state-holder for one `kslSingleApp(...)` instance.
 * Owns the per-document model provider, validation bus, settings
 * store, run-event flow, running-state flag, and the in-flight
 * [RunHandle] when one exists.
 *
 * Not thread-safe — all mutation expected on the Swing EDT.
 * Coroutine plumbing internally schedules onto [Dispatchers.Swing]
 * so the [eventFlow] and [runningFlow] emissions reach widgets on
 * the right thread.
 *
 * @param appName window title; also used as the embedded model
 *   identifier when constructing `ModelReference.Embedded`.
 * @param modelBuilder the developer's named [ModelBuilderIfc].
 */
class SingleAppController(
    val appName: String,
    val modelBuilder: ModelBuilderIfc
) : AutoCloseable {

    /** Scope for EDT-confined coroutine work (event forwarding, etc.). */
    val edtScope: CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    /** User-wide settings (workspace, recent list).  Real file at `~/.ksl/settings.toml`. */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /** Validation bus.  Empty until parameter-panel wiring lands in N2. */
    val validationBus: ValidationFeedbackBus = ValidationFeedbackBus()

    private val provider: MapModelProvider = MapModelProvider(appName, modelBuilder)
    private val session: KSLAppSession = KSLAppSession(provider = provider)

    /**
     * Model defaults captured by probing the developer's
     * [ModelBuilderIfc] once at construction.  Used as the
     * placeholder values rendered by the default parameter panel.
     *
     * If the probe build throws, this falls back to a safe
     * placeholder shape ([SAFE_FALLBACK_DEFAULTS]); the underlying
     * Throwable is exposed as [probeFailure] so the frame can
     * surface a notification.
     */
    val modelDefaults: ExperimentRunDefaults

    /**
     * `null` when the probe build succeeded; the underlying
     * Throwable otherwise.  Frames surface this as an ERROR
     * notification when the app starts up so the developer can
     * fix the broken builder.
     */
    val probeFailure: Throwable?

    init {
        val (defaults, failure) = probeDefaults()
        this.modelDefaults = defaults
        this.probeFailure = failure
    }

    /**
     * Recomputes pre-run validation findings from the current
     * [runOverrides] + [modelDefaults] and publishes them to
     * [validationBus].  Called on construction and on every
     * `runOverrides` emission.
     *
     * Currently surfaces one finding:
     *
     *  - **Infinite-horizon, no timeout** (WARNING) — when the
     *    effective `lengthOfReplication` is infinite AND the
     *    model's `maximumAllowedExecutionTimePerReplication` is
     *    `Duration.ZERO`.  Mirrors the engine-side warning
     *    emitted by `Model.ReplicationProcess.initializeIterations`
     *    so the user sees it before clicking Run.  The user can
     *    clear it by overriding `lengthOfReplication` to a finite
     *    value, or accept the warning and rely on the model's
     *    internal stopping mechanism (e.g. `addCountLimitStoppingAction`,
     *    `stopReplication()`, or `endSimulation()`).
     */
    private fun computeValidation(): ValidationResult {
        val warnings = mutableListOf<FieldError>()
        val effectiveLength = myRunOverrides.value.lengthOfReplication
            ?: modelDefaults.lengthOfReplication
        val maxTime = modelDefaults.maximumAllowedExecutionTimePerReplication
        if (effectiveLength.isInfinite() && maxTime == Duration.ZERO) {
            warnings.add(
                FieldError(
                    path = "scenarios[0].runOverrides.lengthOfReplication",
                    message = "Length of replication is infinite and no maximum execution time is set. " +
                        "The model must include a stopping mechanism " +
                        "(e.g. addCountLimitStoppingAction on a Response/Counter, or call stopReplication() / endSimulation() " +
                        "from inside the model) or the run will not terminate.",
                    severity = ValidationSeverity.WARNING,
                    code = "INFINITE_HORIZON_NO_TIMEOUT"
                )
            )
        }
        return ValidationResult(warnings = warnings)
    }

    private fun probeDefaults(): Pair<ExperimentRunDefaults, Throwable?> = try {
        val model = modelBuilder.build(null, null)
        model.modelDescriptor().experimentRunDefaults to null
    } catch (t: Throwable) {
        logger.warn(t) { "ModelBuilder probe build failed; falling back to safe defaults." }
        SAFE_FALLBACK_DEFAULTS to t
    }

    private val myEventFlow = MutableSharedFlow<RunEvent>(replay = 0, extraBufferCapacity = 256)
    /** Hot flow of run events, fed from the active [RunHandle]. */
    val eventFlow: SharedFlow<RunEvent> = myEventFlow.asSharedFlow()

    private val myRunningFlow = MutableStateFlow(false)
    /** True while a run is in flight. */
    val runningFlow: StateFlow<Boolean> = myRunningFlow.asStateFlow()

    private val myLastResult = MutableStateFlow<RunResult?>(null)
    /** Most recently observed terminal [RunResult], or null when none yet. */
    val lastResult: StateFlow<RunResult?> = myLastResult.asStateFlow()

    private val myRunOverrides = MutableStateFlow(ExperimentRunOverrides())
    /** Pending run-parameter overrides.  Threaded into the ScenarioSpec on [submit]. */
    val runOverrides: StateFlow<ExperimentRunOverrides> = myRunOverrides.asStateFlow()

    private var currentHandle: RunHandle? = null

    init {
        // Initial validation pass + subscribe to runOverrides so the bus
        // reflects current state without the caller having to recompute.
        validationBus.publish(computeValidation())
        edtScope.launch {
            myRunOverrides
                .onEach { validationBus.publish(computeValidation()) }
                .collect { /* no-op terminal */ }
        }
    }

    /**
     * Mutates the pending [runOverrides] via the supplied transform.
     * Typical use from a parameter-panel field:
     *
     * ```kotlin
     * controller.updateRunOverride { it.copy(numberOfReplications = newValue) }
     * ```
     */
    fun updateRunOverride(transform: (ExperimentRunOverrides) -> ExperimentRunOverrides) {
        myRunOverrides.value = transform(myRunOverrides.value)
    }

    /**
     * Submits a run with the model built fresh from [modelBuilder].
     * The configuration is a one-scenario [RunConfiguration] keyed
     * by [appName] (via [ModelReference.Embedded]); the runtime
     * resolves it through [MapModelProvider].
     *
     * No-op when a run is already in flight.  The caller is
     * expected to disable the *Run* control while [runningFlow] is
     * true.
     */
    fun submit() {
        if (myRunningFlow.value) return
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = appName,
                    modelReference = ModelReference.Embedded(appName),
                    runOverrides = myRunOverrides.value
                )
            )
        )
        val handle = session.submit(RunSpec.Single(config))
        currentHandle = handle
        myRunningFlow.value = true

        edtScope.launch {
            handle.events.collect { ev -> myEventFlow.emit(ev) }
        }
        edtScope.launch {
            val result = handle.result.await()
            myLastResult.value = result
            myRunningFlow.value = false
            currentHandle = null
        }
    }

    /** Cancels the in-flight run, if any. */
    fun cancel() {
        currentHandle?.cancel("Cancelled by user")
    }

    override fun close() {
        currentHandle?.cancel("App closed")
        currentHandle = null
        session.close()
        edtScope.cancel("controller closed")
    }

    companion object {
        /**
         * Defaults used when the developer's `ModelBuilderIfc` throws on the
         * probe build.  Conservative values so the GUI renders something
         * sensible even when the real defaults are unreachable.  See
         * [probeFailure] for surfacing the underlying error.
         */
        val SAFE_FALLBACK_DEFAULTS: ExperimentRunDefaults = ExperimentRunDefaults(
            numberOfReplications = 1,
            numChunks = 1,
            startingRepId = 1,
            lengthOfReplication = 1.0,
            lengthOfReplicationWarmUp = 0.0,
            replicationInitializationOption = true,
            maximumAllowedExecutionTimePerReplication = 5.minutes,
            resetStartStreamOption = true,
            advanceNextSubStreamOption = true,
            antitheticOption = false,
            numberOfStreamAdvancesPriorToRunning = 0,
            garbageCollectAfterReplicationFlag = false
        )
    }
}
