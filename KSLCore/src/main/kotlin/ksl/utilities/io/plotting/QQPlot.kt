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

    val empiricalProbabilities
        get() = Statistic.empiricalProbabilities(orderStats.size, empDistType)

    val empiricalQuantiles: DoubleArray
        get() = DoubleArray(orderStats.size) { i -> quantileFunction.invCDF(empiricalProbabilities[i]) }
    //        get() = Statistic.empiricalQuantiles(orderStats.size, quantileFunction, empDistType)

    override fun buildPlot(): Plot {
        val sp = ScatterPlot(empiricalQuantiles, orderStats)
        sp.title = "Quantile-Quantile Plot"
        val p = sp.buildPlot() +
                labs(x = "Theoretical Quantiles", y = "Empirical Quantiles")
        return p
    }

}