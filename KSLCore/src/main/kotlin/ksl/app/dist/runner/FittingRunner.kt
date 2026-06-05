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
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.RankingMethod
import ksl.app.dist.data.DatasetImporter
import ksl.app.dist.data.NamedDataset
import ksl.app.dist.result.FitResultData
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.PDFModelingResults
import ksl.utilities.distributions.fitting.PMFModeler
import ksl.utilities.distributions.fitting.estimators.ParameterEstimatorIfc
import ksl.utilities.statistic.Statistic
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Synchronous driver that turns one fit configuration into a serializable
 * `FitResultData` by orchestrating the appropriate engine: `PDFModeler` for
 * the continuous path and `PMFModeler` for the discrete path. It owns import
 * and the estimate/score/evaluate sequence, and hands the live results to
 * [FitResultExtractor] for translation into the DTO. The live engine objects
 * are used only transiently and are never exposed — presentation is driven
 * entirely from the returned DTO (plus the caller's own raw data for plots).
 *
 * This runner does no event emission, no validation, and no async control:
 * the async session wraps it in a coroutine and converts thrown exceptions
 * into a typed failure result.
 */
object FittingRunner {

    /**
     * Imports the configured data and runs the fit on the path selected by
     * `config.kind`.
     *
     * Multi-dataset sources are rejected with `IllegalStateException` until
     * the batch orchestrator lands; discrete configurations whose data is not
     * integer-valued are rejected with `IllegalStateException`. Both become
     * typed errors in the async layer above.
     */
    fun fit(
        config: FitConfiguration,
        importer: DatasetImporter = DatasetImporter.default,
        catalog: FittingCatalog = FittingCatalog
    ): FitResultData {
        val datasets = importer.import(config.dataSource)
        check(datasets.size == 1) {
            "single-fit runner received ${datasets.size} datasets; multi-dataset sources will arrive with the batch path"
        }
        val dataset = datasets.single()
        return when (config.kind) {
            DistributionKind.CONTINUOUS -> fitContinuous(config, dataset, catalog)
            DistributionKind.DISCRETE -> fitDiscrete(config, dataset, catalog)
        }
    }

    // ----- continuous (PDF) --------------------------------------------------

    private fun fitContinuous(
        config: FitConfiguration,
        dataset: NamedDataset,
        catalog: FittingCatalog
    ): FitResultData {
        val estimatorIds = config.estimatorIds.ifEmpty {
            catalog.defaultEstimatorIds(DistributionKind.CONTINUOUS)
        }
        val scoringIds = config.scoringModelIds.ifEmpty { catalog.defaultScoringModelIds() }

        val orderedEstimators = orderedEstimatorsOf(estimatorIds, catalog)
        val estimatorSet = estimatorSetOf(orderedEstimators)
        val scoringModels = catalog.instantiateScoringModels(scoringIds)

        // Explicit estimate -> score -> evaluate so the configured ranking
        // method is surfaced to the MODA evaluation (estimateAndEvaluateScores
        // would silently use the engine default).
        val ranking = config.rankingMethod.toKslRanking()
        val modeler = PDFModeler(dataset.data, scoringModels)
        val estimationResults = modeler.estimateParameters(estimatorSet, config.automaticShifting)
        val scoringResults = modeler.scoringResults(estimationResults)
        val evaluationModel = modeler.evaluateScoringResults(scoringResults, ranking)
        val results = PDFModelingResults(estimationResults, scoringResults, evaluationModel)

        val resultToId = identityMapResultsToEstimatorIds(results.estimationResults, orderedEstimators)
        return FitResultExtractor.extractContinuous(
            dataset = dataset,
            modeler = modeler,
            results = results,
            resultToId = resultToId,
            catalog = catalog,
            rankingMethod = ranking,
            evaluationMethod = config.evaluationMethod,
            bootstrap = config.bootstrap,
            includeStandardReport = config.includeStandardReport
        )
    }

    // ----- discrete (PMF) ----------------------------------------------------

    private fun fitDiscrete(
        config: FitConfiguration,
        dataset: NamedDataset,
        catalog: FittingCatalog
    ): FitResultData {
        val intData = toIntData(dataset.data)
        val estimatorIds = config.estimatorIds.ifEmpty {
            catalog.defaultEstimatorIds(DistributionKind.DISCRETE)
        }
        val orderedEstimators = orderedEstimatorsOf(estimatorIds, catalog)
        val estimatorSet = estimatorSetOf(orderedEstimators)

        val modeler = PMFModeler(intData)
        val estimationResults = modeler.estimateParameters(estimatorSet)

        val resultToId = identityMapResultsToEstimatorIds(estimationResults, orderedEstimators)
        return FitResultExtractor.extractDiscrete(
            dataset = dataset,
            modeler = modeler,
            estimationResults = estimationResults,
            resultToId = resultToId,
            catalog = catalog,
            includeStandardReport = config.includeStandardReport
        )
    }

    /** Validates and converts double data to integers for the discrete path. */
    private fun toIntData(data: DoubleArray): IntArray = IntArray(data.size) { i ->
        val v = data[i]
        val rounded = v.roundToLong()
        check(abs(v - rounded) <= 1e-9) {
            "discrete fitting requires integer-valued data; found non-integer value $v"
        }
        rounded.toInt()
    }

    // ----- shared helpers ----------------------------------------------------

    private fun orderedEstimatorsOf(
        estimatorIds: Set<String>,
        catalog: FittingCatalog
    ): List<Pair<String, ParameterEstimatorIfc>> = estimatorIds.map { id ->
        id to catalog.estimator(id).factory()
    }

    private fun estimatorSetOf(
        orderedEstimators: List<Pair<String, ParameterEstimatorIfc>>
    ): LinkedHashSet<ParameterEstimatorIfc> =
        LinkedHashSet<ParameterEstimatorIfc>().apply { addAll(orderedEstimators.map { it.second }) }

    /**
     * Pairs each EstimationResult with the catalog ID of the estimator that
     * produced it. Both engines iterate `estimators: Set` in order and return
     * the result list in the same order, so a positional zip is sufficient
     * and a defensive size check catches any future divergence.
     */
    private fun identityMapResultsToEstimatorIds(
        estimationResults: List<EstimationResult>,
        orderedEstimators: List<Pair<String, ParameterEstimatorIfc>>
    ): Map<EstimationResult, String> {
        check(estimationResults.size == orderedEstimators.size) {
            "expected ${orderedEstimators.size} estimation results, got ${estimationResults.size}"
        }
        val out = LinkedHashMap<EstimationResult, String>(estimationResults.size)
        for ((i, er) in estimationResults.withIndex()) {
            out[er] = orderedEstimators[i].first
        }
        return out
    }

    private fun RankingMethod.toKslRanking(): Statistic.Companion.Ranking = when (this) {
        RankingMethod.DENSE -> Statistic.Companion.Ranking.Dense
        RankingMethod.FRACTIONAL -> Statistic.Companion.Ranking.Fractional
        RankingMethod.ORDINAL -> Statistic.Companion.Ranking.Ordinal
    }
}
