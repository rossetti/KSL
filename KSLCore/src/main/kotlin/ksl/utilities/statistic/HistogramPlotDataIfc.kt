package ksl.utilities.statistic

/**
 * Minimal data contract required by `HistogramPlot` to render a histogram.
 *
 * All four arrays must be the same length, indexed in **ascending bin order**:
 * element `i` of [lowerLimits], [upperLimits], [binCounts], and [binFractions]
 * all refer to the same bin.
 *
 * - In-memory [Histogram] instances satisfy this naturally (bins are built in order).
 * - Database implementations must sort source rows by `bin_num` **before**
 *   populating these arrays; `DbHistogramPlotData` does this automatically
 *   in its constructor.
 *
 * [HistogramIfc] extends this interface, so any existing [HistogramIfc] instance
 * is a valid [HistogramPlotDataIfc] without modification.
 */
interface HistogramPlotDataIfc {

    /** Lower boundary of each bin, in ascending order. */
    val lowerLimits: DoubleArray

    /** Upper boundary of each bin, in ascending order. */
    val upperLimits: DoubleArray

    /** Raw count of observations in each bin. */
    val binCounts: DoubleArray

    /** Proportion of observations in each bin (counts divided by total). */
    val binFractions: DoubleArray

    /**
     * Minimum observed value.  Used by `HistogramPlot` to substitute a
     * concrete lower bound when [lowerLimits].first() is infinite.
     */
    val min: Double

    /**
     * Maximum observed value.  Used by `HistogramPlot` to substitute a
     * concrete upper bound when [upperLimits].last() is infinite.
     */
    val max: Double
}