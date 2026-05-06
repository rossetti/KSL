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

package ksl.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ksl.app.config.RunConfiguration
import ksl.app.orchestrator.ExperimentOrchestrator
import ksl.app.orchestrator.OptimizationOrchestrator
import ksl.app.orchestrator.ScenarioOrchestrator
import ksl.app.orchestrator.SingleRunOrchestrator
import ksl.app.session.KSLRuntimeError
import ksl.app.session.RunAttachmentIfc
import ksl.app.session.RunHandle
import ksl.app.session.RunWarningType
import ksl.app.session.failedRunHandle
import ksl.app.validation.FieldError
import ksl.app.validation.RunConfigurationValidator
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import ksl.simulation.ModelProviderIfc
import ksl.simulation.SimulationDispatcher
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unified façade over the app runner/orchestrator layer.
 *
 * UI code should hold one session for the application lifetime, submit [RunSpec]
 * instances through [submit], observe the returned [RunHandle], and call [close]
 * during application shutdown.
 */
class KSLAppSession(
    val provider: ModelProviderIfc? = null,
    scope: CoroutineScope? = null
) : AutoCloseable {

    private val ownsScope: Boolean = scope == null
    private val scope: CoroutineScope =
        scope ?: CoroutineScope(SimulationDispatcher.default + SupervisorJob())
    private val handles = CopyOnWriteArrayList<RunHandle>()

    @Volatile
    private var closed = false

    /**
     * Submit a run for asynchronous execution.
     *
     * Validation runs before dispatch by default. Validation errors and
     * unsupported session-level combinations return an already-failed handle
     * rather than throwing, so UI code can handle all outcomes through the
     * same event/result protocol.
     *
     * Attachments are currently supported only for [RunSpec.Single]. Non-empty
     * attachments on other specs fail immediately with a configuration error.
     */
    fun submit(
        spec: RunSpec,
        attachments: List<RunAttachmentIfc> = emptyList(),
        validate: Boolean = true
    ): RunHandle {
        if (closed) {
            return failedConfigurationHandle(
                "KSLAppSession is closed.",
                FieldError(
                    path = "session",
                    message = "KSLAppSession is closed.",
                    severity = ValidationSeverity.ERROR,
                    code = "SESSION_CLOSED"
                )
            )
        }

        unsupportedAttachmentsError(spec, attachments)?.let { error ->
            return failedConfigurationHandle("Unsupported attachments for run spec.", error)
        }

        val validationResult = if (validate) {
            RunConfigurationValidator.validateForRun(validationConfig(spec), provider)
        } else {
            ValidationResult()
        }
        if (!validationResult.isValid) {
            return failedRunHandle(
                KSLRuntimeError.ConfigurationError(
                    message = "Run configuration failed validation.",
                    validationResult = validationResult
                )
            )
        }

        val preRunWarnings = validationResult.warnings
            .takeIf { it.isNotEmpty() }
            ?.let { listOf(RunWarningType.ConfigurationWarnings(it)) }
            ?: emptyList()

        val handle = runCatching {
            when (spec) {
                is RunSpec.Single -> SingleRunOrchestrator.submit(
                    config = spec.config,
                    provider = provider,
                    attachments = attachments,
                    scope = scope,
                    preRunWarnings = preRunWarnings
                )

                is RunSpec.Scenarios -> ScenarioOrchestrator().submit(
                    config = spec.config,
                    provider = provider,
                    scope = scope,
                    preRunWarnings = preRunWarnings
                )

                is RunSpec.Experiment -> ExperimentOrchestrator().submit(
                    experiment = spec.experiment,
                    numRepsPerDesignPoint = spec.numRepsPerDesignPoint,
                    scope = scope,
                    preRunWarnings = preRunWarnings
                )

                is RunSpec.Optimization -> OptimizationOrchestrator().submit(
                    solver = spec.solver,
                    scope = scope,
                    preRunWarnings = preRunWarnings
                )
            }
        }.getOrElse { e ->
            failedRunHandle(
                KSLRuntimeError.ConfigurationError(
                    message = "Run could not be submitted.",
                    validationResult = null,
                    cause = e
                )
            )
        }

        handles.add(handle)
        return handle
    }

    /**
     * Cancels every run submitted through this session and cancels the owned
     * scope when the session created it. Safe to call more than once.
     */
    override fun close() {
        if (closed) return
        closed = true
        handles.forEach { it.cancel("KSLAppSession closed") }
        handles.clear()
        if (ownsScope) {
            scope.cancel("KSLAppSession closed")
        }
    }

    private fun validationConfig(spec: RunSpec): RunConfiguration =
        when (spec) {
            is RunSpec.Single -> spec.config.copy(scenarios = emptyList(), simoptProblemId = null)
            is RunSpec.Scenarios -> spec.config.copy(simoptProblemId = null)
            is RunSpec.Experiment -> spec.config.copy(scenarios = emptyList(), simoptProblemId = null)
            is RunSpec.Optimization -> spec.config.copy(scenarios = emptyList())
        }

    private fun unsupportedAttachmentsError(
        spec: RunSpec,
        attachments: List<RunAttachmentIfc>
    ): FieldError? {
        if (attachments.isEmpty() || spec is RunSpec.Single) return null
        return FieldError(
            path = "attachments",
            message = "Attachments are currently supported only for RunSpec.Single.",
            severity = ValidationSeverity.ERROR,
            code = "ATTACHMENTS_UNSUPPORTED_FOR_RUN_SPEC"
        )
    }

    private fun failedConfigurationHandle(message: String, error: FieldError): RunHandle =
        failedRunHandle(
            KSLRuntimeError.ConfigurationError(
                message = message,
                validationResult = ValidationResult(errors = listOf(error))
            )
        )
}
