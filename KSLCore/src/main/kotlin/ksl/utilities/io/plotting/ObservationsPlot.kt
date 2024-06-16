package ksl.utilities.io.plotting

import ksl.utilities.Interval
import ksl.utilities.statistic.Statistic
import ksl.utilities.toDoubles
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.geom.geomHLine
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

/**
 *  This class displays the [data] array in the order list. The [interval] parameter
 *  can be used to display a lower limit and upper limit line on the plot.
 *  @param data the data to plot
 *  @param interval the interval to show on the plot
 */
class ObservationsPlot(
    data: DoubleArray,
    var interval: Interval? = null,
    dataName: String? = null
) : BasePlot() {

    private val dataMap: Map<String, List<Number>>
    val statistics: Statistic

    constructor(
        data: IntArray,
        interval: Interval? = null,
        dataName: String? = null
    ) : this(data.toDoubles(), interval, dataName)

    init {
        yLabel = "Observation"
        xLabel = "Observation Number"
        title = if (dataName != null) "Observation Plot for $dataName" else "Observation Plot"
        statistics = if (dataName == null) {
            Statistic(data)
        } else {
            Statistic(dataName, data)
        }
        this.dataMap = mapOf(
            "indices" to (1..data.size).asList(),
            "data" to data.asList()
        )
    }

    fun confidenceInterval(level: Double = 0.99) {
        require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
        interval = statistics.confidenceInterval(level)
    }

    override fun buildPlot(): Plot {

        var p = ggplot(dataMap) +
                geomLine() {
                    x = "indices"
                    y = "data"
                } +
                geomPoint() {
                    x = "indices"
                    y = "data"
                }
        if (interval != null) {
            p = p + geomHLine(yintercept = interval!!.upperLimit, color = "blue", linetype = "dashed") +
                    geomHLine(yintercept = interval!!.midPoint, color = "red", linetype = "dashed") +
                    geomHLine(yintercept = interval!!.lowerLimit, color = "blue", linetype = "dashed")
        }
        p = p + labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

    override fun toString(): String {
        return statistics.toString()
    }

}