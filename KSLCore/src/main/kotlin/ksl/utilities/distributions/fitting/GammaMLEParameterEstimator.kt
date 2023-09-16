package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.Gamma
import kotlin.math.ln


object GammaMLEParameterEstimator : ParameterEstimatorIfc {

    override fun estimate(data: DoubleArray): EstimatedParameters {
        val start = ParameterEstimatorIfc.gammaMOMEstimator(data)
        if (!start.success) {
            return start
        }
        // use starting estimator to seed the MLE search
        val shape = start.parameters!!.doubleParameter("shape")
        val scale = start.parameters.doubleParameter("shape")
        var sum = 0.0
        for (x in data) {
            sum = sum + ln(x)
        }
        val rhs = sum / data.size
        val mean = start.statistics!!.average
        // define the function
        val fn: (Double) -> Double = { x: Double -> ln(mean / x) + Gamma.diGammaFunction(x) - rhs }
        // define a search interval for the shape parameter search
        

        TODO("Not yet implemented")
    }

}

/*
public class GammaParameters : ParameterEstimatorIfc {
    /**
     * Estimate the parameters for a gamma distribution
     * Returns parameters in the form `[shape, scale`].
     * @param [data] Input data.
     * @return Array containing `[shape, scale`]
     */
    override fun estimate(data: DoubleArray): Result<DoubleArray> {
        if (data.any { it <= 0 }) { return estimateFailure("Data must be positive") }
        val solver = BisectionRootFinder(
            FuncToSolve(data),
            Interval(KSLMath.machinePrecision, Int.MAX_VALUE.toDouble())
        )
        solver.evaluate()
        val alpha = solver.result
        val beta = Statistic(data).average / alpha
        return estimateSuccess(alpha, beta)
    }

    private class FuncToSolve(data: DoubleArray) : FunctionIfc {
        val rhs = sumLog(data) / data.size
        val mean = Statistic(data).average

        override fun f(x: Double): Double = ln(mean / x) + Digamma.value(x) - rhs
    }
}
 */