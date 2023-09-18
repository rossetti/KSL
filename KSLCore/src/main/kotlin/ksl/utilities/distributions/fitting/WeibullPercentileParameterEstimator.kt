package ksl.utilities.distributions.fitting

import ksl.utilities.countLessEqualTo
import ksl.utilities.countLessThan
import ksl.utilities.isAllEqual
import ksl.utilities.orderStatistics

object WeibullPercentileParameterEstimator : ParameterEstimatorIfc {
    override fun estimate(data: DoubleArray): EstimatedParameters {
        if (data.size < 2){
            return EstimatedParameters(
                message = "There must be at least two observations",
                success = false
            )
        }
        if (data.countLessEqualTo(0.0) > 0) {
            return EstimatedParameters(
                message = "Cannot fit Weibull distribution when some observations are <= 0.0",
                success = false
            )
        }
        if (data.isAllEqual()){
            return EstimatedParameters(
                message = "Cannot estimate parameters.  The observations were all equal.",
                success = false
            )
        }
        val sorted = data.orderStatistics()
        TODO("Not implemented yet")
    }
}