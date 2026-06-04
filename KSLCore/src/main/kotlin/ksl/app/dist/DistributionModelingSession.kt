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

package ksl.app.dist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.FitSpec
import ksl.app.dist.data.DatasetImporter
import ksl.app.dist.session.FitHandle
import ksl.app.dist.session.FitResult
import ksl.app.dist.session.FittingError
import ksl.app.dist.session.FittingExecutor
import ksl.app.dist.session.failedFitHandle
import ksl.app.dist.validation.FitConfigurationValidator
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * UX-agnostic facade for distribution-fitting work, parallel to
 * `ksl.app.KSLAppSession`. UI code (Swing, CLI, REST host, MCP server)
 * holds one session for the application lifetime, submits `FitSpec`
 * instances through `submit`, observes the returned `FitHandle`, and
 * calls `close` during shutdown.
 *
 * ## Quick start (synchronous)
 *
 * Callers that just want to "run a fit and read the result" — test code
 * or a non-coroutine `main` — can use `submitAndAwaitBlocking` and skip
 * coroutines entirely.
 *
 * ## Dispatch
 *
 * Validation runs by default through `FitConfigurationValidator`;
 * failures return an immediately-failed handle rather than throwing, so
 * the event/result protocol covers every outcome. Successful submissions
 * dispatch to `FittingExecutor` on the session's coroutine scope.
 *
 * ## Lifecycle
 *
 * Every handle returned by `submit` is retained on the session so that
 * `close` can cancel any in-flight fits and release the owned scope when
 * the session created it. `close` is idempotent.
 */
class DistributionModelingSession(
    private val catalog: FittingCatalog = FittingCatalog,
    private val importer: DatasetImporter = DatasetImporter.default,
    scope: CoroutineScope? = null
) : AutoCloseable {

    private val ownsScope: Boolean = scope == null
    private val scope: CoroutineScope =
        scope ?: CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handles = CopyOnWriteArrayList<FitHandle>()

    @Volatile
    private var closed = false

    /**
     * Submit a fit for asynchronous execution.
     *
     * When `validate = true` (the default), `FitConfigurationValidator`
     * runs first; errors short-circuit to an already-failed handle.
     * Otherwise the spec is dispatched directly and any failures surface
     * at run time as `FitResult.Failed`.
     */
    fun submit(spec: FitSpec, validate: Boolean = true): FitHandle {
        if (closed) {
            return failedFitHandle(
                fitId = newPreFailedId(),
                error = FittingError.RuntimeError(
                    message = "DistributionModelingSession is closed.",
                    cause = null
                )
            )
        }

        if (validate) {
            val result = FitConfigurationValidator.validate(spec)
            if (!result.isValid) {
                return failedFitHandle(
                    fitId = newPreFailedId(),
                    error = FittingError.ConfigurationError(
                        message = "Fit configuration failed validation.",
                        validationResult = result
                    )
                )
            }
        }

        val handle = when (spec) {
            is FitSpec.Single -> FittingExecutor.submit(spec, catalog, importer, scope)
            // Async batch execution is not yet wired; the synchronous
            // BatchFittingRunner is available in the meantime. Returns a
            // failed handle so the event/result protocol still applies.
            is FitSpec.Batch -> failedFitHandle(
                fitId = newPreFailedId(),
                error = FittingError.RuntimeError(
                    message = "batch is not yet available via the async session; use BatchFittingRunner",
                    cause = null
                )
            )
        }
        handles += handle
        return handle
    }

    /**
     * Synchronously submit and await the terminal `FitResult`. Bridges
     * the `Deferred` result into a non-coroutine caller via `runBlocking`.
     *
     * Do not call from inside a coroutine running on this session's scope
     * (which would deadlock waiting for itself) or from a UI thread
     * (which would freeze the UI). Use `submit` and observe the handle
     * asynchronously instead in those contexts.
     */
    fun submitAndAwaitBlocking(spec: FitSpec, validate: Boolean = true): FitResult {
        val handle = submit(spec, validate)
        return runBlocking { handle.result.await() }
    }

    /**
     * Cancels any in-flight fits and, when the session owns its scope,
     * cancels that scope. Idempotent.
     */
    override fun close() {
        if (closed) return
        closed = true
        val snapshot = handles.toList()
        handles.clear()
        for (handle in snapshot) {
            handle.cancel("Session closed")
        }
        if (ownsScope) {
            scope.cancel("DistributionModelingSession.close")
        }
    }

    private fun newPreFailedId(): String = "fit-pre-" + UUID.randomUUID().toString().take(8)
}
