package ksl.utilities.distributions.fitting

import ksl.utilities.countLessThan
import ksl.utilities.isAllEqual
import kotlin.math.ln
import kotlin.math.sqrt

object WeibullMLEParameterEstimator : ParameterEstimatorIfc {
    override fun estimate(data: DoubleArray): EstimatedParameters {
        if (data.size < 2) {
            return EstimatedParameters(
                message = "There must be at least two observations",
                success = false
            )
        }
        if (data.countLessThan(0.0) > 0) {
            return EstimatedParameters(
                null,
                message = "Cannot fit Weibull distribution when some observations are less than 0.0",
                success = false
            )
        }
        if (data.isAllEqual()) {
            return EstimatedParameters(
                message = "Cannot estimate parameters.  The observations were all equal.",
                success = false
            )
        }

        TODO("Not yet implemented")
    }

    /**
     *  Based on the recommendation on page 188 of Law(2007)
     *  There must be at least two observations. Returns
     *  the average of ln(x(i)) and the estimated initial shape parameter
     *  Pair(initial shape, average of ln(x(i)). The average of ln(x(i))
     *  is a useful by-product of the estimation process.
     */
    fun initialShapeEstimate(data: DoubleArray): Pair<Double, Double> {
        require(data.size >= 2) { "There must be at least two observations" }
        val n = data.size.toDouble()
        var sumlnx = 0.0
        var sumlnxsq = 0.0
        for (x in data) {
            val lnx = ln(x)
            sumlnx = sumlnx + lnx
            sumlnxsq = sumlnxsq + lnx * lnx
        }
        val coefficient = 6.0 / (Math.PI * Math.PI)
        val diff = sumlnxsq - (sumlnx * sumlnx / n)
        val f = sqrt(coefficient * diff / (n - 1.0))
        return Pair((1.0 / f), (sumlnx / n))
    }
}

/*

public class WeibullParameters : ParameterEstimatorIfc {
    /**
     * Estimate the parameters for a weibull distribution.
     * Returns parameters in the form `[alpha, beta`].
     * @param [data] Input data.
     * @return Array containing `[alpha, beta`]
     */
    override fun estimate(data: DoubleArray): Result<DoubleArray> {
        if (data.any { it <= 0 }) { return estimateFailure("Data must be positive") }
        var alpha = getAlpha0(data)
        for (i in 1..4) {
            alpha = getAlphaK(data, alpha)
        }
        val beta = (data.sumOf { it.pow(alpha) } / data.size ).pow(1.0 / alpha)

        return estimateSuccess(alpha, beta)
    }

    private fun getAlpha0(data: DoubleArray): Double {
        val n = data.size.toDouble()
        val coefficient = 6.0 / (Math.PI.pow(2))
        val sums = data.sumOf { ln(it).pow(2) } - (data.sumOf { ln(it) }.pow(2) / n)
        return ((coefficient * sums) / (n-1)).pow(-0.5)
    }

    private fun getAlphaK(data: DoubleArray, previousAlpha: Double): Double {
        val A = data.sumOf { ln(it) } / data.size
        val B = data.sumOf { it.pow(previousAlpha) }
        val C = data.sumOf { it.pow(previousAlpha) * ln(it) }
        val H = data.sumOf { it.pow(previousAlpha) * ln(it).pow(2) }
        val numerator = A + (1/previousAlpha) - (C/B)
        val denominator = (1/(previousAlpha.pow(2))) + ( ((B*H) - C.pow(2)) / B.pow(2) )
        return previousAlpha + (numerator / denominator)
    }

}
 */