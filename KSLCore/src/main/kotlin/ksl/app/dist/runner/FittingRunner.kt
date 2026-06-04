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
import ksl.app.dist.result.FitResultData
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.PDFModelingResults
import ksl.utilities.distributions.fitting.estimators.ParameterEstimatorIfc
import ksl.utilities.statistic.Statistic

/**
 * Synchronous driver that turns one continuous fit configuration into a
 * fitted outcome by orchestrating `PDFModeler`. It owns import, the
 * estimate/score/evaluate sequence (surfacing the configured ranking
 * method), and hands the live results to [FitResultExtractor] for
 * translation into the serializable `FitResultData`.
 *
 * This runner does no event emission, no validation, and no async control:
 * the async session wraps it in a coroutine and converts thrown exceptions
 * into a typed failure result. Until then, callers (including tests) drive
 * it directly and let exceptions propagate.
 */
object FittingRunner {

    /**
     * Imports the configured data, runs the fit, and returns the serializable
     * `FitResultData`. The live `PDFModeler` / `PDFModelingResults` are used
     * only transiently by [FitResultExtractor] to populate the DTO and are not
     * exposed — presentation is driven entirely from the returned DTO (plus
     * the caller's own raw data for plots).
     *
     * Discrete configurations are rejected with `IllegalArgumentException`
     * until the PMF path lands; multi-dataset sources are rejected with
     * `IllegalStateException` until the batch orchestrator lands. Both
     * become typed errors in the async layer above.
     */
    fun fit(
        config: FitConfiguration,
        importer: DatasetImporter = DatasetImporter.default,
        catalog: FittingCatalog = FittingCatalog
    ): FitResultData {
        require(config.kind == DistributionKind.CONTINUOUS) {
            "discrete fitting will arrive with the PMF path; got kind=${config.kind}"
        }
        val datasets = importer.import(config.dataSource)
        check(datasets.size == 1) {
            "single-fit runner received ${datasets.size} datasets; multi-dataset sources will arrive with the batch path"
        }
        val dataset = datasets.single()

        val estimatorIds = config.estimatorIds.ifEmpty {
            catalog.defaultEstimatorIds(DistributionKind.CONTINUOUS)
        }
        val scoringIds = config.scoringModelIds.ifEmpty { catalog.defaultScoringModelIds() }

        // Preserve estimator-ID order so we can re-key results back to IDs
        // after fitting (PDFModeler iterates the input set in order and
        // returns one EstimationResult per estimator in the same order).
        val orderedEstimators: List<Pair<String, ParameterEstimatorIfc>> = estimatorIds.map { id ->
            id to catalog.estimator(id).factory()
        }
        val estimatorSet: LinkedHashSet<ParameterEstimatorIfc> =
            LinkedHashSet<ParameterEstimatorIfc>().apply { addAll(orderedEstimators.map { it.second }) }
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

        val resultToId = identityMapResultsToEstimatorIds(results, orderedEstimators)
        return FitResultExtractor.extractContinuous(
            dataset = dataset,
            modeler = modeler,
            results = results,
            resultToId = resultToId,
            catalog = catalog,
            rankingMethod = ranking,
            evaluationMethod = config.evaluationMethod,
            bootstrap = config.bootstrap
        )
    }

    /**
     * Pairs each EstimationResult with the catalog ID of the estimator that
     * produced it. PDFModeler iterates `estimators: Set` in order and returns
     * the result list in the same order, so a positional zip is sufficient
     * and a defensive size check catches any future divergence.
     */
    private fun identityMapResultsToEstimatorIds(
        results: PDFModelingResults,
        orderedEstimators: List<Pair<String, ParameterEstimatorIfc>>
    ): Map<EstimationResult, String> {
        val ers = results.estimationResults
        check(ers.size == orderedEstimators.size) {
            "expected ${orderedEstimators.size} estimation results, got ${ers.size}"
        }
        val out = LinkedHashMap<EstimationResult, String>(ers.size)
        for ((i, er) in ers.withIndex()) {
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
