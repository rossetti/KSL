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

package ksl.app.dist.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.FitSpec
import ksl.app.dist.data.DatasetImporter
import ksl.app.dist.result.BatchFailure
import ksl.app.dist.result.BatchFitResultData
import ksl.app.dist.result.FitResultData
import ksl.app.dist.runner.FittingRunner
import java.util.UUID

/**
 * Internal worker that launches one batch coroutine. It runs each entry
 * sequentially through [FittingRunner], emitting a `BatchFitStarted`, one
 * `DatasetCompleted` per entry, and a terminal `BatchFitCompleted` carrying
 * the aggregate `BatchFitResultData`.
 *
 * Per-dataset failures are captured into the report (the batch still
 * completes). Cooperative cancellation is honored at dataset boundaries via
 * `ensureActive`; a cancellation resolves the public lifecycle as
 * `Cancelled` (partial results discarded).
 */
internal object BatchFittingExecutor {

    fun submit(
        spec: FitSpec.Batch,
        catalog: FittingCatalog,
        importer: DatasetImporter,
        scope: CoroutineScope
    ): FitHandle {
        val fitId = "batch-" + UUID.randomUUID().toString().take(8)
        val lifecycle = FitLifecycle(fitId, replay = 8, extraBufferCapacity = 64)

        val job = scope.launch {
            if (!lifecycle.tryStart()) {
                return@launch
            }
            try {
                val total = spec.configs.size
                lifecycle.emitProgress(FitEvent.BatchFitStarted(fitId, total, Clock.System.now()))

                val results = mutableListOf<FitResultData>()
                val failures = mutableListOf<BatchFailure>()
                spec.configs.forEachIndexed { i, named ->
                    ensureActive() // cancel between datasets
                    val success = try {
                        results += FittingRunner.fit(named.config, importer, catalog)
                        true
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        failures += BatchFailure(
                            name = named.name,
                            message = t.message ?: (t::class.simpleName ?: "fit failed")
                        )
                        false
                    }
                    lifecycle.emitProgress(
                        FitEvent.DatasetCompleted(fitId, named.name, i + 1, total, success)
                    )
                }

                lifecycle.completeWithBatch(
                    FitResult.BatchCompleted(BatchFitResultData(results.toList(), failures.toList()))
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                lifecycle.completeFailed(
                    FittingError.RuntimeError(
                        message = t.message ?: (t::class.simpleName ?: "unknown error"),
                        cause = t
                    )
                )
            }
        }

        return FitHandleImpl(lifecycle, job)
    }
}
