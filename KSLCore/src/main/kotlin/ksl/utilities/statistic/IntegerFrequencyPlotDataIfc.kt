package ksl.utilities.statistic

/**
 * Minimal data contract required by `IntegerFrequencyPlot` to render a
 * discrete bar chart.
 *
 * All three arrays must be the same length, indexed in **ascending value order**:
 * element `i` of [values], [frequencies], and [proportions] all refer to the
 * same observed integer.
 *
 * - In-memory [IntegerFrequency] instances satisfy this naturally (values are
 *   maintained in a sorted map).
 * - Database implementations must sort source rows by `value` **before**
 *   populating these arrays; `DbIntegerFrequencyPlotData` does this
 *   automatically in its constructor.
 *
 * [IntegerFrequencyIfc] extends this interface.
 */
interface IntegerFrequencyPlotDataIfc {

    /**
     * Returns an array of size numberOfCells containing
     * the observed values increasing by value. The 0th element
     * of the array contains the smallest value observed, 1st element
     * the next bigger value, etc.
     *
     * @return the array of values observed or an empty array
     */
    val values: IntArray

    /**
     * Returns an array of size numberOfCells containing
     * the frequencies for each value observed. The 0th element
     * is the frequency for the value stored at element 0 of the
     * array returned by the values property
     *
     * @return the array of frequencies observed or an empty array
     */
    val frequencies: IntArray

    /**
     * Returns an array of size numberOfCells containing
     * the proportion by value. The 0th element
     * is the proportion for the value stored at element 0 of the
     * array returned by the values property, etc.
     *
     * @return the array of proportions observed or an empty array
     */
    val proportions: DoubleArray
}