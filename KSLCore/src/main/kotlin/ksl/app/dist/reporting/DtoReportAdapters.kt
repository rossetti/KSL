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

package ksl.app.dist.reporting

import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.result.BootstrapEstimateDTO
import ksl.app.dist.result.DistributionFitDTO
import ksl.app.dist.result.FitResultData
import ksl.app.dist.result.ModaResultDTO
import ksl.app.dist.result.ModaValueDTO
import ksl.utilities.Interval
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.DiscretePMFInRangeDistributionIfc
import ksl.utilities.distributions.fitting.DiscretePMFGoodnessOfFit
import ksl.utilities.distributions.fitting.PDFData
import ksl.utilities.distributions.fitting.PDFFitData
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.PMFFitData
import ksl.utilities.distributions.fitting.PMFModeler
import ksl.utilities.io.plotting.FitDistPlot
import ksl.utilities.moda.MODAReportData
import ksl.utilities.moda.MetricData
import ksl.utilities.statistic.BootstrapEstimateIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

/**
 * Adapters that let the canonical report extensions render a serializable
 * [FitResultData] (plus the client's raw data) exactly as they render a live
 * PDFModeler/PMFModeler. They implement the report-data interfaces from carried
 * DTO fields and cheap deterministic reconstructions (distribution, fit plot,
 * goodness-of-fit), and they serve the stochastic bootstrap quantities from
 * carried snapshots rather than recomputing — so a DTO render is deterministic.
 */

/**
 * Serves one carried [BootstrapEstimateDTO] as a [BootstrapEstimateIfc]. The
 * raw replicate array is never transmitted, so the CI/bias/MSE/std-error
 * accessors return the carried summary values directly (the confidence
 * intervals are at the level the fit was run at, regardless of the requested
 * `level`).
 */
internal class DtoBootstrapEstimate(
    private val dto: BootstrapEstimateDTO,
    private val sampleSize: Int
) : BootstrapEstimateIfc {
    override val name: String = dto.parameterName
    override var label: String? = null
    override val defaultCILevel: Double = dto.ciLevel
    override val originalDataSampleSize: Int = sampleSize
    override val originalDataEstimate: Double = dto.originalEstimate
    override val bootstrapEstimates: DoubleArray = DoubleArray(0)
    override val numberOfBootstraps: Int = dto.numBootstraps
    override val acrossBootstrapStatistics: StatisticIfc = Statistic()
    override val acrossBootstrapAverage: Double = dto.bootstrapAverage
    override val bootstrapStdErrEstimate: Double = dto.stdError
    override val bootstrapBiasEstimate: Double get() = dto.bias
    override val bootstrapMSEEstimate: Double get() = dto.mse
    override fun stdNormalBootstrapCI(level: Double): Interval = Interval(dto.normalCILower, dto.normalCIUpper)
    override fun basicBootstrapCI(level: Double): Interval = Interval(dto.basicCILower, dto.basicCIUpper)
    override fun percentileBootstrapCI(level: Double): Interval = Interval(dto.percentileCILower, dto.percentileCIUpper)
}

/**
 * Continuous sample/EDA view. Delegates the deterministic members (statistics,
 * histogram, shift, sign counts) to a reconstructed [PDFModeler] built from the
 * client's raw data, but serves the carried bootstrap confidence interval for
 * the minimum so the shift section renders deterministically.
 */
internal class DtoPdfData(
    private val dto: FitResultData,
    rawData: DoubleArray,
    private val modeler: PDFModeler = PDFModeler(rawData)
) : PDFData by modeler {
    override fun confidenceIntervalForMinimum(numBootstrapSamples: Int, level: Double): Interval =
        dto.shiftAnalysis?.let { Interval(it.ciForMinimumLower, it.ciForMinimumUpper) }
            ?: modeler.confidenceIntervalForMinimum(numBootstrapSamples, level)
}

/**
 * Per-fit continuous view built from carried parameters plus the client's raw
 * data: the fitted distribution and (possibly shifted) goodness-of-fit data are
 * reconstructed, and the bootstrap parameter estimates are served from carried
 * snapshots (empty when the fit carried no bootstrap).
 */
internal class DtoPdfFitData(
    private val fit: DistributionFitDTO,
    private val rawData: DoubleArray,
    private val catalog: FittingCatalog
) : PDFFitData {
    override val name get() = fit.displayName
    override val rvType
        get() = catalog.familyOrNull(fit.familyId)?.rvType ?: error("Unknown family '${fit.familyId}'")
    override val numberOfParameters get() = fit.numberOfParameters
    override val weightedValue get() = fit.weightedValue ?: Double.NaN
    override val averageRanking get() = fit.averageRanking ?: Double.NaN
    override val distribution: ContinuousDistributionIfc by lazy {
        val p = rvType.rvParameters
        p.fillFromDoubleArrayMap(fit.parameters.mapValues { doubleArrayOf(it.value) })
        PDFModeler.createDistribution(p) ?: error("Cannot build distribution for '${fit.familyId}'")
    }
    override val testData: DoubleArray
        get() = if (fit.shift != 0.0) DoubleArray(rawData.size) { rawData[it] - fit.shift } else rawData
    override fun distributionFitPlot(): FitDistPlot = FitDistPlot(testData, distribution, distribution)
    override fun bootstrapParameterEstimates(level: Double): List<BootstrapEstimateIfc> =
        fit.bootstrap?.map { DtoBootstrapEstimate(it, rawData.size) } ?: emptyList()
}

/**
 * Per-fit discrete view: rebuilds the chi-squared goodness-of-fit object from
 * carried parameters and the client's integer data, exactly as the result
 * extractor does, so the discrete report renders from the DTO.
 */
internal class DtoPmfFitData(
    private val fit: DistributionFitDTO,
    override val data: IntArray,
    private val catalog: FittingCatalog
) : PMFFitData {
    override val goodnessOfFit: DiscretePMFGoodnessOfFit by lazy {
        val rvType = catalog.familyOrNull(fit.familyId)?.rvType ?: error("Unknown family '${fit.familyId}'")
        val p = rvType.rvParameters
        p.fillFromDoubleArrayMap(fit.parameters.mapValues { doubleArrayOf(it.value) })
        val dist = PMFModeler.createDistribution(p) as? DiscretePMFInRangeDistributionIfc
            ?: error("Cannot build discrete distribution for '${fit.familyId}'")
        val doubles = DoubleArray(data.size) { data[it].toDouble() }
        DiscretePMFGoodnessOfFit(doubles, dist, numEstimatedParameters = fit.numberOfParameters)
    }
}

/**
 * MODA view reconstructed from the carried [ModaResultDTO] joined with the
 * [fits]: the per-metric scores/values/ranks come from the MODA detail, while
 * the overall weighted value, average ranking, and first-rank count come from
 * each successful fit (joined by `displayName == alternative`).
 */
internal class DtoModaData(
    private val moda: ModaResultDTO,
    fits: List<DistributionFitDTO>
) : MODAReportData {
    private val ok = fits.filter { it.success }
    override val name = moda.modelName
    override val metricNames = moda.metrics.map { it.metricName }
    override val alternatives = moda.values.map { it.alternative }.distinct()
    override val metricData = moda.metrics.map { m ->
        MetricData(
            metricName = m.metricName, direction = m.direction, weight = m.weight,
            domainLowerLimit = m.domainLowerLimit, domainUpperLimit = m.domainUpperLimit,
            unitsOfMeasure = m.unitsOfMeasure, description = m.description
        )
    }
    private fun byMetric(sel: (ModaValueDTO) -> Double): Map<String, List<Double>> =
        metricNames.associateWith { mn ->
            alternatives.map { a ->
                moda.values.firstOrNull { it.alternative == a && it.metricName == mn }?.let(sel) ?: Double.NaN
            }
        }
    override val scoresByMetric = metricNames.associateWith { mn ->
        alternatives.map { a ->
            moda.scores.firstOrNull { it.alternative == a && it.scoreName == mn }?.scoreValue ?: Double.NaN
        }
    }
    override val valuesByMetric = byMetric { it.metricValue }
    override val ranksByMetric = byMetric { it.rank }
    override val sortedOverallValues =
        ok.mapNotNull { f -> f.weightedValue?.let { f.displayName to it } }.sortedByDescending { it.second }
    override val sortedAvgRanks =
        ok.mapNotNull { f -> f.averageRanking?.let { f.displayName to it } }.sortedBy { it.second }
    override val firstRankCounts = ok.associate { it.displayName to (it.firstRankCount ?: 0) }
}
