package ksl.utilities.io.plotting

import ksl.utilities.statistic.HistogramBinData
import ksl.utilities.statistic.HistogramPlotDataIfc

/**
 * A [HistogramPlotDataIfc] backed by a list of [HistogramBinData] rows
 * retrieved from the KSL database (table `tblHistogram`).
 *
 * The constructor sorts [rows] by [HistogramBinData.binNum] so that the
 * ordering contract of [HistogramPlotDataIfc] is satisfied regardless of
 * the order in which the database returns rows.
 *
 * [min] and [max] default to the lower limit of the first bin and the upper
 * limit of the last bin respectively.  These values are used only to clamp
 * an infinite bin boundary in `HistogramPlot`; database-sourced histograms
 * always have finite limits, so the clamping branch is never reached.
 *
 * @param rows  histogram bin records from the database; may be in any order
 */
class DbHistogramPlotData(rows: List<HistogramBinData>) : HistogramPlotDataIfc {

    private val mySorted = rows.sortedBy { it.binNum }

    override val lowerLimits: DoubleArray = DoubleArray(mySorted.size) { mySorted[it].binLowerLimit }
    override val upperLimits: DoubleArray = DoubleArray(mySorted.size) { mySorted[it].binUpperLimit }
    override val binCounts: DoubleArray   = DoubleArray(mySorted.size) { mySorted[it].binCount }
    override val binFractions: DoubleArray = DoubleArray(mySorted.size) { mySorted[it].proportion }

    override val min: Double = if (mySorted.isEmpty()) 0.0 else mySorted.first().binLowerLimit
    override val max: Double = if (mySorted.isEmpty()) 0.0 else mySorted.last().binUpperLimit
}