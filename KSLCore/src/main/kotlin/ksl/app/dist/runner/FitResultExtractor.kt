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
import ksl.app.dist.config.BootstrapConfig
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.EvaluationMethod
import ksl.app.dist.data.NamedDataset
import ksl.app.dist.result.BootstrapEstimateDTO
import ksl.app.dist.result.DataSummaryDTO
import ksl.app.dist.result.DistributionFitDTO
import ksl.app.dist.result.EmpProbConvention
import ksl.app.dist.result.FitResultData
import ksl.app.dist.result.GoodnessOfFitDTO
import ksl.app.dist.result.HistogramBinDTO
import ksl.app.dist.result.HistogramDTO
import ksl.app.dist.result.MetricDTO
import ksl.app.dist.result.ModaResultDTO
import ksl.app.dist.result.ModaScoreDTO
import ksl.app.dist.result.ModaValueDTO
import ksl.app.dist.result.RankFrequencyDTO
import ksl.utilities.distributions.DiscretePMFInRangeDistributionIfc
import ksl.utilities.distributions.fitting.ContinuousCDFGoodnessOfFit
import ksl.utilities.distributions.fitting.DiscretePMFGoodnessOfFit
import ksl.utilities.distributions.fitting.EstimationResult
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.PDFModelingResults
import ksl.utilities.distributions.fitting.PMFModeler
import ksl.utilities.distributions.fitting.ScoringResult
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.toHtml
import ksl.utilities.moda.AdditiveMODAModel
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.Statistic

/**
 * Converts the live, non-serializable products of a continuous fit
 * (`PDFModeler` + `PDFModelingResults`) into the serializable `FitResultData`
 * graph. This is the orchestration layer's core translation step: it is the
 * one place that reads the engine's live objects and emits wire-safe DTOs,
 * identical whether the engine is co-located or remote.
 *
 * MODA results are sourced from the model's own record producers
 * (`metricData()`, `alternativeScoreData()`, `alternativeValueData()`,
 * `alternativeRankFrequencyData()`) rather than by walking the model; each
 * producer record's database `id` and repeated model name are dropped on the
 * mapping. No plot data is produced — a client rebuilds plots from its own
 * data plus the returned fitted distribution.
 */
object FitResultExtractor {

    /**
     * Builds the full result DTO graph for a continuous fit.
     *
     * @param resultToId maps each estimation result to its catalog estimator ID
     * @param rankingMethod the engine rank-tie method used during evaluation
     * @param evaluationMethod selects the fit ordering / recommendation criterion
     * @param bootstrap when non-null, requests engine-side bootstrap summaries
     */
    fun extractContinuous(
        dataset: NamedDataset,
        modeler: PDFModeler,
        results: PDFModelingResults,
        resultToId: Map<EstimationResult, String>,
        catalog: FittingCatalog,
        rankingMethod: Statistic.Companion.Ranking,
        evaluationMethod: EvaluationMethod,
        bootstrap: BootstrapConfig?,
        includeStandardReport: Boolean = false
    ): FitResultData {
        val ranked = when (evaluationMethod) {
            EvaluationMethod.SCORING -> results.resultsSortedByScoring
            EvaluationMethod.RANKING -> results.resultsSortedByAvgRanking
        }
        val scoredFits = ranked.mapIndexed { idx, sr ->
            scoringSummary(sr, idx + 1, resultToId, catalog, modeler, bootstrap)
        }

        val scoredEstimations = ranked.map { it.estimationResult }.toSet()
        val failedFits = results.estimationResults
            .filterNot { it in scoredEstimations }
            .mapIndexed { idx, er ->
                failedSummary(er, scoredFits.size + idx + 1, resultToId, catalog)
            }

        val allFits = scoredFits + failedFits
        val recommendedFamilyId = scoredFits.firstOrNull { it.success }?.familyId

        val standardReport = if (includeStandardReport) {
            runCatching { results.toReport(modeler, title = dataset.name).toHtml() }.getOrNull()
        } else {
            null
        }

        return FitResultData(
            datasetName = dataset.name,
            kind = DistributionKind.CONTINUOUS,
            empProbConvention = EmpProbConvention.CONTINUITY1,
            dataSummary = dataSummaryOf(modeler.statistics, modeler.leftShift),
            fits = allFits,
            recommendedFamilyId = recommendedFamilyId,
            histogram = histogramOf(modeler),
            scoring = modaResultOf(results.evaluationModel, rankingMethod),
            bootstrapFamilyFrequency = null,
            standardReportHtml = standardReport
        )
    }

    // ----- discrete (PMF) path ----------------------------------------------

    /**
     * Builds the result DTO graph for a discrete fit. Mirrors the engine's
     * intentional PDF/PMF asymmetry: there is no MODA scoring — fits are
     * ranked by chi-squared p-value (higher is a better fit) — and no
     * histogram or bootstrap. Each successful fit carries a discrete
     * `GoodnessOfFitDTO` (chi-squared plus the dispersion statistics).
     *
     * @param resultToId maps each estimation result to its catalog estimator ID
     */
    fun extractDiscrete(
        dataset: NamedDataset,
        modeler: PMFModeler,
        estimationResults: List<EstimationResult>,
        resultToId: Map<EstimationResult, String>,
        catalog: FittingCatalog,
        includeStandardReport: Boolean = false
    ): FitResultData {
        val doubleData = DoubleArray(modeler.data.size) { modeler.data[it].toDouble() }

        // Build a (fit, p-value, gof) for every estimation that produced a usable
        // discrete distribution and goodness-of-fit; everything else is failed.
        // The GoF of the top fit drives the standard report.
        data class Scored(val fit: DistributionFitDTO, val pValue: Double, val gof: DiscretePMFGoodnessOfFit)
        val scored = mutableListOf<Scored>()
        val failed = mutableListOf<EstimationResult>()
        for (er in estimationResults) {
            val params = er.parameters
            val dist = if (er.success && params != null) {
                PMFModeler.createDistribution(params) as? DiscretePMFInRangeDistributionIfc
            } else null
            if (dist == null) {
                failed += er
                continue
            }
            val estimatorId = resultToId[er] ?: er.estimator.name
            val descriptor = catalog.estimatorOrNull(estimatorId)
            val rvType = descriptor?.rvType
            val numParams = rvType?.rvParameters?.numberOfParameters ?: 1
            val gof = runCatching {
                DiscretePMFGoodnessOfFit(doubleData, dist, numEstimatedParameters = numParams)
            }.getOrNull()
            if (gof == null) {
                failed += er
                continue
            }
            val familyId = descriptor?.familyId
                ?: rvType?.let { catalog.familyIdFor(it) }
                ?: dist.toString().lowercase()
            val fit = DistributionFitDTO(
                rank = 0, // assigned after p-value sort
                familyId = familyId,
                estimatorId = estimatorId,
                rvTypeName = rvType?.let { rvTypeName(it) } ?: "unknown",
                displayName = dist.toString(),
                parameters = parametersOf(params),
                numberOfParameters = numParams,
                success = true,
                message = er.message,
                shift = 0.0,
                chiSquaredPValue = gof.chiSquaredPValue,
                goodnessOfFit = discreteGoodnessOfFitOf(gof)
            )
            scored += Scored(fit, gof.chiSquaredPValue, gof)
        }

        val sortedScored = scored.sortedByDescending { it.pValue }
        val rankedScored = sortedScored.mapIndexed { idx, s -> s.fit.copy(rank = idx + 1) }
        val failedFits = failed.mapIndexed { idx, er ->
            discreteFailedSummary(er, rankedScored.size + idx + 1, resultToId, catalog)
        }
        val allFits = rankedScored + failedFits
        val recommendedFamilyId = rankedScored.firstOrNull()?.familyId

        val standardReport = if (includeStandardReport) {
            runCatching {
                sortedScored.firstOrNull()?.gof?.toReport(modeler, title = dataset.name)?.toHtml()
            }.getOrNull()
        } else {
            null
        }

        return FitResultData(
            datasetName = dataset.name,
            kind = DistributionKind.DISCRETE,
            empProbConvention = EmpProbConvention.CONTINUITY1,
            dataSummary = dataSummaryOf(modeler.statistics, shift = 0.0),
            fits = allFits,
            recommendedFamilyId = recommendedFamilyId,
            histogram = null,
            scoring = null,
            bootstrapFamilyFrequency = null,
            standardReportHtml = standardReport
        )
    }

    private fun discreteGoodnessOfFitOf(gof: DiscretePMFGoodnessOfFit): GoodnessOfFitDTO =
        GoodnessOfFitDTO(
            chiSquaredStatistic = gof.chiSquaredTestStatistic,
            chiSquaredDOF = gof.chiSquaredTestDOF,
            chiSquaredPValue = gof.chiSquaredPValue,
            binBreakPoints = gof.breakPoints.toList(),
            binProbabilities = gof.binProbabilities.toList(),
            expectedCounts = gof.expectedCounts.toList(),
            observedCounts = gof.histogram.binCounts.toList(),
            indexOfDispersion = gof.indexOfDispersion,
            poissonVarianceTestStatistic = gof.poissonVarianceTestStatistic
        )

    private fun discreteFailedSummary(
        er: EstimationResult,
        rank: Int,
        resultToId: Map<EstimationResult, String>,
        catalog: FittingCatalog
    ): DistributionFitDTO {
        val estimatorId = resultToId[er] ?: er.estimator.name
        val descriptor = catalog.estimatorOrNull(estimatorId)
        val rvType = descriptor?.rvType
        return DistributionFitDTO(
            rank = rank,
            familyId = descriptor?.familyId ?: "unknown",
            estimatorId = estimatorId,
            rvTypeName = rvType?.let { rvTypeName(it) } ?: "unknown",
            displayName = er.message ?: "estimation failed",
            parameters = parametersOf(er.parameters),
            numberOfParameters = rvType?.rvParameters?.numberOfParameters ?: parametersOf(er.parameters).size,
            success = false,
            message = er.message,
            shift = 0.0
        )
    }

    // ----- data summary -----------------------------------------------------

    private fun dataSummaryOf(stats: StatisticIfc, shift: Double): DataSummaryDTO {
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
            shift = shift
        )
    }

    // ----- histogram --------------------------------------------------------

    private fun histogramOf(modeler: PDFModeler): HistogramDTO {
        val h = modeler.histogram
        val bins = h.histogramData().map { b ->
            HistogramBinDTO(
                binNum = b.binNum,
                binLabel = b.binLabel,
                lowerLimit = b.binLowerLimit,
                upperLimit = b.binUpperLimit,
                count = b.binCount,
                cumCount = b.cumCount,
                proportion = b.proportion,
                cumProportion = b.cumProportion
            )
        }
        return HistogramDTO(bins = bins, underFlowCount = h.underFlowCount, overFlowCount = h.overFlowCount)
    }

    // ----- MODA (full, via the model's own producers) -----------------------

    private fun modaResultOf(
        model: AdditiveMODAModel,
        rankingMethod: Statistic.Companion.Ranking
    ): ModaResultDTO? {
        val metrics = model.metricData()
        if (metrics.isEmpty()) return null
        return ModaResultDTO(
            modelName = model.name,
            rankingMethod = rankingMethod.name,
            metrics = metrics.map { md ->
                MetricDTO(
                    metricName = md.metricName,
                    direction = md.direction,
                    weight = md.weight,
                    domainLowerLimit = md.domainLowerLimit,
                    domainUpperLimit = md.domainUpperLimit,
                    unitsOfMeasure = md.unitsOfMeasure,
                    description = md.description
                )
            },
            scores = model.alternativeScoreData().map { sd ->
                ModaScoreDTO(alternative = sd.alternative, scoreName = sd.scoreName, scoreValue = sd.scoreValue)
            },
            values = model.alternativeValueData(rankingMethod).map { vd ->
                ModaValueDTO(
                    alternative = vd.alternative,
                    metricName = vd.metricName,
                    metricValue = vd.metricValue,
                    rank = vd.rank
                )
            },
            rankFrequencies = model.alternativeRankFrequencyData(rankingMethod = rankingMethod).map { rf ->
                RankFrequencyDTO(
                    alternative = rf.alternative,
                    rankValue = rf.value,
                    count = rf.count,
                    proportion = rf.proportion,
                    cumProportion = rf.cumProportion
                )
            }
        )
    }

    // ----- per-fit ----------------------------------------------------------

    private fun scoringSummary(
        sr: ScoringResult,
        rank: Int,
        resultToId: Map<EstimationResult, String>,
        catalog: FittingCatalog,
        modeler: PDFModeler,
        bootstrap: BootstrapConfig?
    ): DistributionFitDTO {
        val estimatorId = resultToId[sr.estimationResult] ?: sr.estimationResult.estimator.name
        val descriptor: EstimatorDescriptor? = catalog.estimatorOrNull(estimatorId)
        val familyId = descriptor?.familyId
            ?: catalog.familyIdFor(sr.rvType)
            ?: sr.rvType.toString().lowercase()
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
            firstRankCount = sr.firstRankCount,
            goodnessOfFit = goodnessOfFitOf(sr),
            bootstrap = bootstrapOf(modeler, sr, bootstrap)
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

    // ----- goodness of fit --------------------------------------------------

    private fun goodnessOfFitOf(sr: ScoringResult): GoodnessOfFitDTO {
        val gof = ContinuousCDFGoodnessOfFit(
            sr.estimationResult.testData,
            sr.distribution,
            numEstimatedParameters = sr.numberOfParameters
        )
        return GoodnessOfFitDTO(
            chiSquaredStatistic = gof.chiSquaredTestStatistic,
            chiSquaredDOF = gof.chiSquaredTestDOF,
            chiSquaredPValue = gof.chiSquaredPValue,
            binBreakPoints = gof.breakPoints.toList(),
            binProbabilities = gof.binProbabilities.toList(),
            expectedCounts = gof.expectedCounts.toList(),
            observedCounts = gof.histogram.binCounts.toList(),
            ksStatistic = gof.ksStatistic,
            ksPValue = gof.ksPValue,
            andersonDarlingStatistic = gof.andersonDarlingStatistic,
            andersonDarlingPValue = gof.andersonDarlingPValue,
            cramerVonMisesStatistic = gof.cramerVonMisesStatistic,
            cramerVonMisesPValue = gof.cramerVonMisesPValue
        )
    }

    // ----- bootstrap (summary only; engine-side) ----------------------------

    private fun bootstrapOf(
        modeler: PDFModeler,
        sr: ScoringResult,
        bootstrap: BootstrapConfig?
    ): List<BootstrapEstimateDTO>? {
        if (bootstrap == null) return null
        val estimates = modeler.bootStrapParameterEstimates(
            result = sr.estimationResult,
            numBootstrapSamples = bootstrap.sampleSize,
            level = bootstrap.level,
            streamNumber = bootstrap.streamNumber
        )
        return estimates.map { bse ->
            val normal = bse.stdNormalBootstrapCI(bootstrap.level)
            val basic = bse.basicBootstrapCI(bootstrap.level)
            val pct = bse.percentileBootstrapCI(bootstrap.level)
            BootstrapEstimateDTO(
                parameterName = bse.name,
                originalEstimate = bse.originalDataEstimate,
                bootstrapAverage = bse.acrossBootstrapAverage,
                bias = bse.bootstrapBiasEstimate,
                mse = bse.bootstrapMSEEstimate,
                stdError = bse.bootstrapStdErrEstimate,
                numBootstraps = bse.numberOfBootstraps,
                ciLevel = bootstrap.level,
                normalCILower = normal.lowerLimit,
                normalCIUpper = normal.upperLimit,
                basicCILower = basic.lowerLimit,
                basicCIUpper = basic.upperLimit,
                percentileCILower = pct.lowerLimit,
                percentileCIUpper = pct.upperLimit
            )
        }
    }

    // ----- helpers ----------------------------------------------------------

    private fun parametersOf(params: RVParameters?): Map<String, Double> {
        if (params == null) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (name in params.parameterNames) {
            runCatching { out[name] = params.doubleParameter(name) }
        }
        return out
    }

    /** The distribution family name as the RVType enum constant name (e.g. "Exponential"). */
    private fun rvTypeName(rvType: RVParametersTypeIfc): String =
        (rvType as? Enum<*>)?.name ?: rvType.toString()
}
