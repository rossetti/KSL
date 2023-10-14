package ksl.utilities.io.plotting

import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.Statistic
import ksl.utilities.toDoubles
import org.jetbrains.kotlinx.dataframe.impl.asList
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.geom.*
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import org.jetbrains.letsPlot.scale.ylim

class PMFPlot(
    val pmf: DEmpiricalCDF,
    dataName: String? = null
) : BasePlot() {

    private val dataMap: Map<String, List<Number>>
    private val p = pmf.probPoints
    private val v = pmf.values

    init {
        yLabel = "Probability"
        xLabel = "Value"
        title = if (dataName != null) "PMF Plot for $dataName" else "PMF Plot"

        this.dataMap = mapOf(
            "prob" to p.asList(),
            "value" to v.asList()
        )
    }

    constructor(
        values: DoubleArray,
        probabilities: DoubleArray,
        dataName: String? = null
    ) : this(DEmpiricalCDF(values, KSLRandom.makeCDF(probabilities)), dataName)

    override fun buildPlot(): Plot {
        var p = ggplot(dataMap) +
                geomPoint() {
                    x = "value"
                    y = "prob"
                }
        for (i in v.indices) {
            p = p + geomSegment(yend = 0) {
                x = "value"
                y = "prob"
                xend = "value"
            }
        }
        p = p + labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}