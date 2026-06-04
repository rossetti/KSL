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
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.FitSpec
import ksl.app.dist.data.DatasetImporter
import ksl.app.dist.data.ImportException
import ksl.app.dist.result.FitResultData
import ksl.app.dist.runner.FittingRunner
import java.util.UUID

/**
 * Internal worker that launches one fitting coroutine and owns the
 * exception-to-FittingError mapping for the lifecycle.
 *
 * Validation is the session's responsibility; this layer only handles
 * runtime mapping.
 */
internal object FittingExecutor {

    /**
     * Launches a coroutine to run the given single-spec fit and returns a
     * handle bound to it.
     *
     * The worker resolves the data source first (so the user-visible
     * `FitStarted` event can carry the dataset name); a resolution failure
     * skips `FitStarted` and goes straight to a terminal `FitFailed`. Any
     * `CancellationException` is re-thrown so the lifecycle's
     * `attachJobFallback` resolves the public state.
     */
    fun submit(
        spec: FitSpec.Single,
        catalog: FittingCatalog,
        importer: DatasetImporter,
        scope: CoroutineScope
    ): FitHandle {
        val fitId = "fit-" + UUID.randomUUID().toString().take(8)
        val lifecycle = FitLifecycle(fitId, replay = 4, extraBufferCapacity = 16)

        val job = scope.launch {
            if (!lifecycle.tryStart()) {
                // Cancellation won before we could claim the lifecycle.
                return@launch
            }
            try {
                val datasets = importer.import(spec.config.dataSource)
                val datasetName = datasets.firstOrNull()?.name ?: "(unknown)"
                lifecycle.emitProgress(
                    FitEvent.FitStarted(fitId, datasetName, Clock.System.now())
                )
                val report: FitResultData = FittingRunner.fit(spec.config, importer = ImporterFromDatasets(datasets), catalog = catalog)
                lifecycle.completeWithReport(FitResult.Completed(report))
            } catch (ce: CancellationException) {
                throw ce
            } catch (ie: ImportException) {
                lifecycle.completeFailed(
                    FittingError.ImportError(
                        message = ie.message ?: "data import failed",
                        cause = ie
                    )
                )
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

    /**
     * Single-use importer that returns datasets already resolved by the
     * executor's pre-run import. Avoids importing the same file twice.
     */
    private class ImporterFromDatasets(
        private val datasets: List<ksl.app.dist.data.NamedDataset>
    ) : DatasetImporter {
        override fun import(reference: ksl.app.dist.config.DataSourceReference) = datasets
    }
}
