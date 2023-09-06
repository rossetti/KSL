package ksl.utilities.io.plotting

import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.geom.*
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.scale.ylim

class ACFPlot(
    data: DoubleArray,
    private val maxLag: Int,
    dataName: String? = null
) : BasePlot() {

    private val dataMap: Map<String, List<Number>>
    val acf = Statistic.autoCorrelations(data, maxLag)

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
//+ ylim(Pair(-1.0,1.0))
        //TODO add confidence interval bands
        var p = ggplot(dataMap)  +
                geomPoint() {
                    x = "lags"
                    y = "data"
                }
        for (k in acf.indices){
            p = p + geomSegment(yend = 0){
                x = "lags"
                y = "data"
                xend = "lags"
            }
        }
//                geomBar(stat = Stat.identity, width = 0.05, color = "black"){
//                    x = "lags"
//                    y = "data"
//                }
        p = p + labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}