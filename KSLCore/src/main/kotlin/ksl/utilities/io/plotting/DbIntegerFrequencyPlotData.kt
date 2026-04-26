package ksl.utilities.io.plotting

import ksl.utilities.statistic.FrequencyData
import ksl.utilities.statistic.IntegerFrequencyPlotDataIfc

/**
 * An [IntegerFrequencyPlotDataIfc] backed by a list of [FrequencyData] rows
 * retrieved from the KSL database (table `tblFrequency`).
 *
 * The constructor sorts [rows] by [FrequencyData.value] so that the ordering
 * contract of [IntegerFrequencyPlotDataIfc] is satisfied regardless of the
 * order in which the database returns rows.
 *
 * @param rows  frequency records from the database; may be in any order
 */
class DbIntegerFrequencyPlotData(rows: List<FrequencyData>) : IntegerFrequencyPlotDataIfc {

    private val mySorted = rows.sortedBy { it.value }

    override val values: IntArray      = IntArray(mySorted.size)    { mySorted[it].value }
    override val frequencies: IntArray = IntArray(mySorted.size)    { mySorted[it].count.toInt() }
    override val proportions: DoubleArray = DoubleArray(mySorted.size) { mySorted[it].proportion }
}