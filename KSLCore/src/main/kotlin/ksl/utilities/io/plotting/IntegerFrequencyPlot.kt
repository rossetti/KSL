package ksl.utilities.io.plotting

import ksl.utilities.statistic.IntegerFrequency
import org.jetbrains.letsPlot.Stat
import org.jetbrains.letsPlot.asDiscrete
import org.jetbrains.letsPlot.geom.geomBar
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class IntegerFrequencyPlot(
    private val frequency: IntegerFrequency,
    proportions: Boolean = false
) : BasePlot() {

    private val data: MutableMap<String, List<Any>> = mutableMapOf()
    private var dataType: String

    constructor(data: IntArray,
                proportions: Boolean = false
    ) : this(IntegerFrequency(data = data), proportions)

    init {
        data["values"] = frequency.values.asList()
        if (proportions) {
            dataType = "proportions"
            data[dataType] = frequency.proportions.asList()
        } else {
            dataType = "counts"
            data[dataType] = frequency.frequencies.asList()
        }
        xLabel = "values"
        yLabel = dataType
    }

    override fun buildPlot(): Plot {
        val p = ggplot(data) +
                geomBar(stat = Stat.identity) {
                    x = asDiscrete("values") // this causes x to be categorical and removes the x-axis scaling, etc.
                    y = dataType
                } +
                labs(title = title, x = xLabel, y = yLabel) +
                ggsize(width, height)
        return p
    }

}