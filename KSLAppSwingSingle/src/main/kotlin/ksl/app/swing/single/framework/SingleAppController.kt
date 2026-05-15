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

package ksl.app.swing.single.framework

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.config.ModelReference
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.settings.UserSettingsStore
import ksl.app.validation.ValidationFeedbackBus
import ksl.simulation.MapModelProvider
import ksl.simulation.ModelBuilderIfc

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

    private val myEventFlow = MutableSharedFlow<RunEvent>(replay = 0, extraBufferCapacity = 256)
    /** Hot flow of run events, fed from the active [RunHandle]. */
    val eventFlow: SharedFlow<RunEvent> = myEventFlow.asSharedFlow()

    private val myRunningFlow = MutableStateFlow(false)
    /** True while a run is in flight. */
    val runningFlow: StateFlow<Boolean> = myRunningFlow.asStateFlow()

    private val myLastResult = MutableStateFlow<RunResult?>(null)
    /** Most recently observed terminal [RunResult], or null when none yet. */
    val lastResult: StateFlow<RunResult?> = myLastResult.asStateFlow()

    private var currentHandle: RunHandle? = null

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
                    modelReference = ModelReference.Embedded(appName)
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
}
