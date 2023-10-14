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
import org.jetbrains.letsPlot.pos.positionNudge
import org.jetbrains.letsPlot.scale.scaleFillManual
import org.jetbrains.letsPlot.themes.theme

class ComparePMFPlot(
    data: IntArray,
    df: DiscreteDistributionIfc,
    dataName: String? = null
) : BasePlot() {

    private val dataMap: Map<String, List<Number>>
    val frequency = IntegerFrequency(data = data)
    var nudge = 0.1
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
        this.dataMap = mapOf(
            "estimated" to frequency.proportions.asList(),
            "observed" to frequency.values.asList(),
            "probability" to map.values.asList(),
            "values" to map.keys.asList(),
        )
    }

    override fun buildPlot(): Plot {
        //
        val pd = positionNudge(nudge)
        var p = ggplot(dataMap) + theme().legendPositionRight() +
                geomPoint(color = "red", position = pd) {
                    x = "observed"
                    y = "estimated"
                } +
                geomPoint(color = "black") {
                    x = "values"
                    y = "probability"
                }
        for (i in frequency.values) {
            p = p + geomSegment(yend = 0, color = "red", position = pd) {
                x = "observed"
                y = "estimated"
                xend = "observed"
            }
        }
        for (i in frequency.values) {
            p = p + geomSegment(yend = 0, color = "black") {
                x = "values"
                y = "probability"
                xend = "values"
            }
        }
        p = p + labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}