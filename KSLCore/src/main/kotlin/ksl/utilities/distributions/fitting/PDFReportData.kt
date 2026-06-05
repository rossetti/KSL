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

package ksl.utilities.distributions.fitting

import ksl.utilities.Interval
import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.io.plotting.FitDistPlot
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.statistic.BootstrapEstimateIfc
import ksl.utilities.statistic.HistogramIfc
import ksl.utilities.statistic.StatisticIfc

/**
 * The sample-level data a continuous distribution-fitting report needs for its
 * exploratory-data sections (statistics, box plot, histogram, shift analysis).
 *
 * [PDFModeler] implements this directly. A reconstructed/serialized result can
 * implement it too, so the same report extensions render from either source —
 * the renderer depends on this data view rather than the live engine.
 */
interface PDFData {
    val statistics: StatisticIfc
    val originalData: DoubleArray
    val histogram: HistogramIfc
    val hasZeroes: Boolean
    val hasNegatives: Boolean
    val defaultZeroTolerance: Double
    val leftShift: Double
    fun confidenceIntervalForMinimum(numBootstrapSamples: Int = 399, level: Double = 0.95): Interval
}

/**
 * The per-fitted-distribution data a continuous fitting report needs: identity
 * and scoring summary, the fitted distribution and the (possibly shifted) data
 * used for goodness-of-fit, the fit-diagnostic plot bundle, and the bootstrap
 * parameter-estimate summaries.
 */
interface PDFFitData {
    val name: String
    val rvType: RVParametersTypeIfc
    val numberOfParameters: Int
    val weightedValue: Double
    val averageRanking: Double
    val distribution: ContinuousDistributionIfc
    val testData: DoubleArray
    fun distributionFitPlot(): FitDistPlot
    fun bootstrapParameterEstimates(level: Double): List<BootstrapEstimateIfc>
}
