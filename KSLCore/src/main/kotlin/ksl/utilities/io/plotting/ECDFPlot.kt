package ksl.utilities.io.plotting

import ksl.utilities.Interval
import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.geom.geomFunction
import org.jetbrains.letsPlot.geom.geomHLine
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.stat.statECDF
import kotlin.math.ceil
import kotlin.math.floor

class ECDFPlot @JvmOverloads constructor(
    data: DoubleArray,
    var cdf: ((Double) -> Double)? = null,
    dataName: String? = null
) : BasePlot() {

    private val dataMap: Map<String, List<Number>>
    private val stats = Statistic(data)
    var numPoints: Int = 512
        set(value) {
            require(value > 0) { "The number of points on the x-axis must be > 0" }
            field = value
        }

    init {
        yLabel = "Probability"
        xLabel = dataName ?: "Observations"
        title = if (dataName != null) "Empirical Distribution Plot for $dataName" else "Empirical Distribution Plot"
        this.dataMap = mapOf(
            "data" to data.asList()
        )
    }

    override fun buildPlot(): Plot {

        var p = ggplot(dataMap) +
                statECDF(){
                    x = "data"
                }
        if (cdf != null) {
            val limits = Pair(floor(stats.min), ceil(stats.max) )
            p = p + geomFunction(xlim = limits, fn = cdf, n = numPoints, color = "red")
        }
        p = p + labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}