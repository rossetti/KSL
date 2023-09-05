package ksl.utilities.io.plotting

import ksl.utilities.statistic.Statistic
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.geom.geomHLine
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class ObservationsPlot(
    data: DoubleArray,
    private val confLevel: Double? = 0.95,
    dataName: String? = null
) : BasePlot() {

    private val dataMap: Map<String, List<Number>>
    private val stats = Statistic(data)

    init {
        yLabel = "Observation"
        xLabel = "Observation Number"
        title = if (dataName != null) "Observation Plot for $dataName" else "Observation Plot"
        this.dataMap = mapOf(
            "indices" to (1..data.size).asList(),
            "data" to data.asList()
        )
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
        if (confLevel != null) {
            val ci = stats.confidenceInterval(confLevel)
            p = p + geomHLine(yintercept = ci.upperLimit, color = "blue", linetype = "dashed") +
                    geomHLine(yintercept = stats.average, color = "red", linetype = "dashed") +
                    geomHLine(yintercept = ci.lowerLimit, color = "blue", linetype = "dashed")
        }
        p = p + labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}