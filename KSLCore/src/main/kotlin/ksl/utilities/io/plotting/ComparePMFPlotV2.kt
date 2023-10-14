package ksl.utilities.io.plotting

import ksl.utilities.distributions.DiscreteDistributionIfc
import ksl.utilities.statistic.IntegerFrequency
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomSegment
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.pos.positionDodge
import org.jetbrains.letsPlot.pos.positionNudge
import org.jetbrains.letsPlot.scale.scaleFillManual
import org.jetbrains.letsPlot.themes.theme

class ComparePMFPlotV2(
    data: IntArray,
    df: DiscreteDistributionIfc,
    dataName: String? = null
) : BasePlot() {

    private val dataMap: Map<String, List<Any>>
    val frequency = IntegerFrequency(data = data)
    var nudge = 0.3
        set(value) {
            require(!(value <= 0.0 || value >= 1.0)) { "nudge should be in (0,1)" }
            field = value
        }

    init {
        yLabel = "Probability"
        xLabel = "Value"
        title = if (dataName != null) "Compare PMF Plot for $dataName" else "Compare PMF Plot"
        val observedRange = frequency.min.rangeTo(frequency.max)
        val map = df.pmf(observedRange)
        // prepare the lists
        val probList = frequency.proportions.toMutableList()
        probList.addAll(map.values)
        val valueList = frequency.values.toMutableList()
        valueList.addAll(map.keys)
        val typeList = MutableList(frequency.proportions.size){"estimated"}
        for(e in map){
            typeList.add("theoretical")
        }
        this.dataMap = mapOf(
            "probability" to probList,
            "values" to valueList,
            "PMF Type" to typeList
        )
    }

    override fun buildPlot(): Plot {
        //
        val pd = positionDodge(nudge)
        var p = ggplot(dataMap) + theme().legendPositionRight() +
                geomPoint(position = pd) {
                    x = "values"
                    y = "probability"
                    color = "PMF Type"
                }
        for (i in dataMap) {
            p = p + geomSegment(yend = 0, position = pd) {
                x = "values"
                y = "probability"
                xend = "values"
                color = "PMF Type"
            }
        }
        p = p + labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}