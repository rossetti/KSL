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

import ksl.app.dist.catalog.EstimatorDescriptor
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.data.DatasetImporter
import ksl.app.dist.data.NamedDataset
import ksl.app.dist.result.DataSummaryDTO
import ksl.app.dist.result.DistributionFitDTO
import ksl.app.dist.result.FitResultData
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.PDFModelingResults
import ksl.utilities.distributions.fitting.ScoringResult
import ksl.utilities.distributions.fitting.estimators.ParameterEstimatorIfc
import ksl.utilities.random.rvariable.parameters.RVParameters

/**
 * Synchronous driver that turns one continuous fit configuration into a
 * serializable FitReport by orchestrating PDFModeler.
 *
 * This runner does no event emission, no validation, and no async control:
 * the eventual async session wraps it in a coroutine and converts thrown
 * exceptions into a typed failure result. Until then, callers (including
 * tests) drive it directly and let exceptions propagate.
 */
object FittingRunner {

    /**
     * Imports the configured data, runs PDFModeler over the configured
     * estimator and scoring-model set, and returns only the serializable
     * `FitReport`. Thin convenience over [run] for callers that do not need
     * the live modeling objects (headless DTO consumers, the async session).
     */
    fun fit(
        config: FitConfiguration,
        importer: DatasetImporter = DatasetImporter.default,
        catalog: FittingCatalog = FittingCatalog
    ): FitResultData = run(config, importer, catalog).report

    /**
     * Imports the configured data, runs PDFModeler over the configured
     * estimator and scoring-model set, and returns a [FitOutcome] carrying
     * both the serializable `FitReport` and the live `PDFModeler` /
     * `PDFModelingResults`. The live objects are what the reporting layer
     * needs to build a `ReportNode.Document` via the existing `*.toReport()`
     * extensions; the report results hold no back-reference to the modeler.
     *
     * Discrete configurations are rejected with `IllegalArgumentException`
     * until the PMF path lands; multi-dataset sources are rejected with
     * `IllegalStateException` until the batch orchestrator lands. Both
     * become typed errors in the async layer above.
     */
    fun run(
        config: FitConfiguration,
        importer: DatasetImporter = DatasetImporter.default,
        catalog: FittingCatalog = FittingCatalog
    ): FitOutcome {
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

        val modeler = PDFModeler(dataset.data, scoringModels)
        val results = modeler.estimateAndEvaluateScores(
            estimators = estimatorSet,
            automaticShifting = config.automaticShifting
        )

        val resultToId = identityMapResultsToEstimatorIds(results, orderedEstimators)
        val report = buildReport(dataset, modeler, results, resultToId, catalog)
        return FitOutcome.Continuous(
            report = report,
            datasetName = dataset.name,
            modeler = modeler,
            results = results
        )
    }

    // ----- result -> requested-estimator-ID lookup --------------------------

    /**
     * Pairs each EstimationResult with the catalog ID of the estimator that
     * produced it. PDFModeler iterates `estimators: Set` in order and
     * returns the result list in the same order, so a positional zip is
     * sufficient and a defensive size check catches any future divergence.
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

    // ----- report assembly --------------------------------------------------

    private fun buildReport(
        dataset: NamedDataset,
        modeler: PDFModeler,
        results: PDFModelingResults,
        resultToId: Map<EstimationResult, String>,
        catalog: FittingCatalog
    ): FitResultData {
        val rankedScored = results.resultsSortedByScoring
        val scoredSummaries = rankedScored.mapIndexed { idx, sr ->
            scoringSummary(sr, idx + 1, resultToId, catalog)
        }

        val scoredEstimations = rankedScored.map { it.estimationResult }.toSet()
        val failedSummaries = results.estimationResults
            .filterNot { it in scoredEstimations }
            .mapIndexed { idx, er ->
                failedSummary(er, scoredSummaries.size + idx + 1, resultToId, catalog)
            }

        val allFits = scoredSummaries + failedSummaries
        val recommendedFamilyId = scoredSummaries.firstOrNull { it.success }?.familyId

        // histogram, scoring (full MODA), and bootstrapFamilyFrequency are
        // populated by the result extractor in a later phase; null for now.
        return FitResultData(
            datasetName = dataset.name,
            kind = DistributionKind.CONTINUOUS,
            dataSummary = dataSummaryOf(modeler),
            fits = allFits,
            recommendedFamilyId = recommendedFamilyId
        )
    }

    private fun dataSummaryOf(modeler: PDFModeler): DataSummaryDTO {
        val stats = modeler.statistics
        return DataSummaryDTO(
            n = stats.count.toInt(),
            min = stats.min,
            max = stats.max,
            average = stats.average,
            variance = stats.variance,
            standardDeviation = stats.standardDeviation,
            skewness = stats.skewness,
            kurtosis = stats.kurtosis,
            zeroCount = stats.zeroCount.toInt(),
            negativeCount = stats.negativeCount.toInt(),
            positiveCount = stats.positiveCount.toInt(),
            shift = modeler.leftShift
        )
    }

    private fun scoringSummary(
        sr: ScoringResult,
        rank: Int,
        resultToId: Map<EstimationResult, String>,
        catalog: FittingCatalog
    ): DistributionFitDTO {
        val estimatorId = resultToId[sr.estimationResult] ?: sr.estimationResult.estimator.name
        val descriptor: EstimatorDescriptor? = catalog.estimatorOrNull(estimatorId)
        val familyId = descriptor?.familyId
            ?: catalog.familyIdFor(sr.rvType)
            ?: sr.rvType.toString().lowercase()
        // goodnessOfFit and bootstrap are populated by the extractor in a later phase.
        return DistributionFitDTO(
            rank = rank,
            familyId = familyId,
            estimatorId = estimatorId,
            rvTypeName = rvTypeName(sr.rvType),
            displayName = sr.name,
            parameters = parametersOf(sr.estimationResult.parameters),
            numberOfParameters = sr.numberOfParameters,
            success = sr.estimationResult.success,
            message = sr.estimationResult.message,
            shift = sr.estimationResult.shiftedData?.shift ?: 0.0,
            weightedValue = sr.weightedValue,
            averageRanking = sr.averageRanking,
            firstRankCount = sr.firstRankCount
        )
    }

    private fun failedSummary(
        er: EstimationResult,
        rank: Int,
        resultToId: Map<EstimationResult, String>,
        catalog: FittingCatalog
    ): DistributionFitDTO {
        val estimatorId = resultToId[er] ?: er.estimator.name
        val descriptor = catalog.estimatorOrNull(estimatorId)
        val familyId = descriptor?.familyId ?: "unknown"
        val rvType = descriptor?.rvType
        return DistributionFitDTO(
            rank = rank,
            familyId = familyId,
            estimatorId = estimatorId,
            rvTypeName = rvType?.let { rvTypeName(it) } ?: "unknown",
            displayName = er.distribution,
            parameters = parametersOf(er.parameters),
            numberOfParameters = rvType?.rvParameters?.numberOfParameters ?: parametersOf(er.parameters).size,
            success = false,
            message = er.message,
            shift = er.shiftedData?.shift ?: 0.0
        )
    }

    /** The distribution family name as the RVType enum constant name (e.g. "Exponential"). */
    private fun rvTypeName(rvType: ksl.utilities.random.rvariable.RVParametersTypeIfc): String =
        (rvType as? Enum<*>)?.name ?: rvType.toString()

    private fun parametersOf(params: RVParameters?): Map<String, Double> {
        if (params == null) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (name in params.parameterNames) {
            runCatching { out[name] = params.doubleParameter(name) }
        }
        return out
    }
}
