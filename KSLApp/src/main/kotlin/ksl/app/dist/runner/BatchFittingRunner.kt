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

package ksl.app.dist.runner

import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.config.NamedFitConfiguration
import ksl.app.dist.data.DatasetImporter
import ksl.app.dist.result.BatchFailure
import ksl.app.dist.result.BatchFitResultData

/**
 * Synchronous batch driver: runs each entry of a `FitSpec.Batch` through
 * [FittingRunner], collecting successful results and capturing per-dataset
 * failures so the batch completes even when some entries fail.
 *
 * Like [FittingRunner], this layer does no async control or event emission;
 * the async session wraps it (in a later phase). Callers (CLI, tests) can use
 * it directly.
 */
object BatchFittingRunner {

    /**
     * Expands a configuration whose data source resolves to many datasets
     * into a `FitSpec.Batch` that shares the analysis configuration. The
     * source is imported once and each dataset is materialized as a
     * single-dataset `Inline` source named after the dataset, so re-running
     * each entry does not re-read the original source.
     */
    fun expand(
        config: FitConfiguration,
        importer: DatasetImporter = DatasetImporter.default
    ): FitSpec.Batch {
        val datasets = importer.import(config.dataSource)
        val configs = datasets.map { dataset ->
            NamedFitConfiguration(
                name = dataset.name,
                config = config.copy(
                    dataSource = DataSourceReference.Inline(mapOf(dataset.name to dataset.data))
                )
            )
        }
        return FitSpec.Batch(configs)
    }

    /**
     * Runs each entry, returning successful results in submission order and a
     * `BatchFailure` for each entry whose fit threw. Cancellation exceptions
     * are not swallowed.
     */
    fun run(
        batch: FitSpec.Batch,
        importer: DatasetImporter = DatasetImporter.default,
        catalog: FittingCatalog = FittingCatalog
    ): BatchFitResultData {
        val results = mutableListOf<ksl.app.dist.result.FitResultData>()
        val failures = mutableListOf<BatchFailure>()
        for (named in batch.configs) {
            try {
                results += FittingRunner.fit(named.config, importer, catalog)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                failures += BatchFailure(
                    name = named.name,
                    message = t.message ?: (t::class.simpleName ?: "fit failed")
                )
            }
        }
        return BatchFitResultData(results = results.toList(), failures = failures.toList())
    }
}
