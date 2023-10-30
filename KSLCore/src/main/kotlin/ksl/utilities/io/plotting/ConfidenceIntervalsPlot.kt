package ksl.utilities.io.plotting

import ksl.utilities.Interval
import ksl.utilities.io.StatisticReporter
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.geom.geomErrorBar
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomVLine
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class ConfidenceIntervalsPlot(
    private val intervals: Map<String, Interval>,
    private val referencePoint: Double? = null
) : BasePlot() {

    private val data: MutableMap<String, MutableList<Any>> = mutableMapOf()

    constructor(list: List<StatisticIfc>, level: Double = 0.95, referencePoint: Double? = null) :
            this(StatisticReporter.confidenceIntervals(list, level), referencePoint)

    constructor(data: Map<String, DoubleArray>, level: Double = 0.95, referencePoint: Double? = null) :
            this(Statistic.confidenceIntervals(data, level), referencePoint)

    init {
        data["CI"] = mutableListOf()
        data["upperLimit"] = mutableListOf()
        data["average"] = mutableListOf()
        data["lowerLimit"] = mutableListOf()
        for ((n, ci) in intervals) {
            data["CI"]!!.add(n)
            data["upperLimit"]!!.add(ci.upperLimit)
            data["average"]!!.add(ci.midPoint)
            data["lowerLimit"]!!.add(ci.lowerLimit)
        }
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomErrorBar(stat = Stat.identity) {
                    y = "CI"
                    width = "average"
                    xmin = "lowerLimit"
                    xmax = "upperLimit"
                } +
                geomPoint {
                    y = "CI"
                    x = "average"
                } + geomVLine(xintercept = referencePoint, color = "red", linetype = "dashed") +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}