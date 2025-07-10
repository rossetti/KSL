package ksl.utilities.io.plotting

import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.distributions.PMFIfc
import ksl.utilities.random.rvariable.KSLRandom
import org.jetbrains.letsPlot.geom.*
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

/**
 *   Plots a probability mass function as represented by a DEmpirical.
 */
class PMFPlot @JvmOverloads constructor(
    val dEmpiricalCDF: DEmpiricalCDF,
    dataName: String? = null
) : BasePlot() {

    private val dataMap: Map<String, List<Number>>
    private val p = dEmpiricalCDF.probPoints
    private val v = dEmpiricalCDF.values

    init {
        yLabel = "Probability"
        xLabel = "Value"
        title = if (dataName != null) "PMF Plot for $dataName" else "PMF Plot"

        this.dataMap = mapOf(
            "prob" to p.asList(),
            "value" to v.asList()
        )
    }

    /**
     *  Creates a DEmpirical based on the [values] and the [probabilities]
     *  and then plots it.
     */
    constructor(
        values: DoubleArray,
        probabilities: DoubleArray,
        dataName: String? = null
    ) : this(DEmpiricalCDF(values, KSLRandom.makeCDF(probabilities)), dataName)

    /**
     *   Can be used to make a plot of a probability mass function ([pmf]) over
     *   a [range] of values.
     */
    constructor(
        range: IntRange,
        pmf: PMFIfc,
        dataName: String? = null
    ) : this(DEmpiricalCDF.makeDEmpirical(range, pmf), dataName)


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