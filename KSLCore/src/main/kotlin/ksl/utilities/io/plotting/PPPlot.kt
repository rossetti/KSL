package ksl.utilities.io.plotting

import ksl.utilities.distributions.CDFIfc
import ksl.utilities.orderStatistics
import ksl.utilities.statistic.EmpDistType
import ksl.utilities.statistic.Statistic
import org.jetbrains.letsPlot.geom.geomABLine
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class PPPlot (
    data: DoubleArray,
    private val cdfFunction: CDFIfc
) : BasePlot() {

    var empDistType: EmpDistType = EmpDistType.Continuity1
    val orderStats: DoubleArray = data.orderStatistics()

    val empProbabilities: DoubleArray
        get() = Statistic.empiricalProbabilities(orderStats.size, empDistType)

    val theoreticalProbabilities: DoubleArray
        get() = DoubleArray(orderStats.size) { i -> cdfFunction.cdf(orderStats[i]) }

    override fun buildPlot(): Plot {
        val sp = ScatterPlot(theoreticalProbabilities, empProbabilities)
        sp.title = "Probability-Probability Plot"
        val p = sp.buildPlot() +
                geomABLine(slope = 1.0, intercept = 0.0, color = "red") +
                labs(x = "Theoretical Probabilities", y = "Empirical Probabilities")
        return p
    }

}