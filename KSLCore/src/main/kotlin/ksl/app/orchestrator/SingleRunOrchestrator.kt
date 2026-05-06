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
import ksl.app.session.RunAttachmentIfc
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
 * Accepts a [RunConfiguration], builds the model via [RunConfiguration.buildModel],
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
 * [submit].  If [RunConfiguration.modelReference] is
 * [ksl.app.config.ModelReference.ByProviderId] and [provider] is `null`,
 * [RunConfiguration.buildModel] throws [IllegalStateException] synchronously —
 * before the coroutine is launched.
 */
object SingleRunOrchestrator {

    /**
     * Builds the model from [config] and submits it for asynchronous execution.
     *
     * @param config      the run configuration describing the model, controls,
     *                    and experiment parameters
     * @param provider    required when [RunConfiguration.modelReference] is
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
        val model = config.buildModel(provider)
        return Runner().submit(RunRequest.SingleRun(model, attachments), scope, preRunWarnings)
    }
}
