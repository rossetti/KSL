package ksl.utilities.io.plotting

import ksl.utilities.Interval
import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.geom.geomHLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomSegment
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import kotlin.math.log10
import kotlin.math.sqrt

class ACFPlot(
    data: DoubleArray,
    private val maxLag: Int = defaultMaxLag(data.size),
    dataName: String? = null
) : BasePlot() {

//    constructor(data: IntArray, maxLag: Int, dataName: String?) :
//            this(data.toDoubles(), maxLag, dataName)

    private val dataMap: Map<String, List<Number>>
    val acf = Statistic.autoCorrelations(data, maxLag)
    val ci = Interval(-2.0 / sqrt(data.size.toDouble()), 2.0 / sqrt(data.size.toDouble()))

    companion object {
        fun defaultMaxLag(size: Int): Int {
            return 10 * log10(size.toDouble()).toInt()
        }
    }

    init {
        yLabel = "Lag-k Correlation"
        xLabel = "Lag Number"
        title = if (dataName != null) "ACF Plot for $dataName" else "ACF Plot"

        this.dataMap = mapOf(
            "lags" to (1..maxLag).asList(),
            "data" to acf.asList()
        )
    }

    override fun buildPlot(): Plot {
        var p = ggplot(dataMap) +
                geomPoint() {
                    x = "lags"
                    y = "data"
                }
        for (k in acf.indices) {
            p = p + geomSegment(yend = 0) {
                x = "lags"
                y = "data"
                xend = "lags"
            }
        }
        p = p + geomHLine(yintercept = ci.upperLimit, color = "blue", linetype = "dashed") +
                geomHLine(yintercept = ci.lowerLimit, color = "blue", linetype = "dashed")
        p = p + labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}