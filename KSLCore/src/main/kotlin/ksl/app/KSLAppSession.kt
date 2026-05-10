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
import ksl.app.config.optimization.OptimizationSolverFactory
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
import ksl.app.validation.OptimizationConfigurationValidator
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
 *
 * ## Dispatch
 *
 * [submit] selects validator and execution path by [RunSpec] variant:
 *
 * - [RunSpec.Single] / [RunSpec.Scenarios] / [RunSpec.Experiment] →
 *   validated by [RunConfigurationValidator] and executed through
 *   [SingleRunOrchestrator], [ScenarioOrchestrator], or
 *   [ExperimentOrchestrator] respectively.
 * - [RunSpec.Optimization] → validated by
 *   [OptimizationConfigurationValidator]; on success a [Solver] is built by
 *   [OptimizationSolverFactory] from the spec's
 *   [ksl.app.config.optimization.OptimizationRunConfiguration] and handed to
 *   [OptimizationOrchestrator].  Programmatic users who already hold a
 *   built solver can call `OptimizationOrchestrator().submit(solver, …)`
 *   directly and bypass the session.
 *
 * Validation errors return an immediately-failed [RunHandle] rather than
 * throwing, so the same event/result protocol covers all outcomes.
 *
 * ## Lifecycle
 *
 * Every handle returned from [submit] is retained on the session so that
 * [close] can cancel any in-flight runs and release the owned coroutine
 * scope.  `close()` is idempotent.
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
     * Validation runs before dispatch by default — [RunConfigurationValidator]
     * for non-optimization specs, [OptimizationConfigurationValidator] for
     * [RunSpec.Optimization].  Validation errors and unsupported session-level
     * combinations return an already-failed handle rather than throwing, so UI
     * code can handle all outcomes through the same event/result protocol.
     *
     * For [RunSpec.Optimization], a [Solver] is built from
     * [ksl.app.config.optimization.OptimizationRunConfiguration] via
     * [OptimizationSolverFactory] and then submitted to
     * [OptimizationOrchestrator].
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

        val validationResult: ValidationResult = if (!validate) {
            ValidationResult()
        } else when (spec) {
            is RunSpec.Single, is RunSpec.Scenarios, is RunSpec.Experiment ->
                RunConfigurationValidator.validateForRun(runConfigurationOf(spec), provider)
            is RunSpec.Optimization ->
                OptimizationConfigurationValidator.validateForRun(spec.config, provider)
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

                is RunSpec.Optimization -> {
                    val solver = OptimizationSolverFactory(provider).build(spec.config)
                    OptimizationOrchestrator().submit(
                        solver = solver,
                        scope = scope,
                        preRunWarnings = preRunWarnings
                    )
                }
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

    /**
     * Returns the [RunConfiguration] to validate for a non-optimization
     * spec, with the spec-irrelevant slices cleared so validation focuses
     * on what the run path actually consumes.  Throws on
     * [RunSpec.Optimization] — that spec carries
     * [ksl.app.config.optimization.OptimizationRunConfiguration] and is
     * validated by [OptimizationConfigurationValidator] on a separate
     * branch in [submit].
     */
    private fun runConfigurationOf(spec: RunSpec): RunConfiguration =
        when (spec) {
            is RunSpec.Single -> spec.config.copy(scenarios = emptyList())
            is RunSpec.Scenarios -> spec.config
            is RunSpec.Experiment -> spec.config.copy(scenarios = emptyList())
            is RunSpec.Optimization ->
                error("runConfigurationOf is not applicable to RunSpec.Optimization")
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
