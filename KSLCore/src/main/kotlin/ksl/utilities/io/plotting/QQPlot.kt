package ksl.utilities.io.plotting

import ksl.utilities.distributions.InverseCDFIfc
import ksl.utilities.orderStatistics
import ksl.utilities.statistic.EmpDistType
import ksl.utilities.statistic.Statistic
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

class QQPlot(
    data: DoubleArray,
    private val quantileFunction: InverseCDFIfc
) : BasePlot() {

    var empDistType: EmpDistType = EmpDistType.Continuity1
    val orderStats = data.orderStatistics()

    val empProbabilities
        get() = Statistic.empDist(orderStats.size, empDistType)

    val quantiles: DoubleArray
        get() = DoubleArray(orderStats.size) { i -> quantileFunction.invCDF(empProbabilities[i]) }

    override fun buildPlot(): Plot {
        val sp = ScatterPlot(quantiles, orderStats)
        val p = sp.buildPlot() +
                labs(x = "Theoretical Quantiles", y = "Empirical Quantiles")
        return p
    }

}