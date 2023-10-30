package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.DiscretePMFInRangeDistributionIfc
import ksl.utilities.statistic.HistogramIfc

interface DiscreteDistributionGOFIfc : DistributionGOFIfc {

    val distribution: DiscretePMFInRangeDistributionIfc

    val poissonVarianceTestStatistic: Double
        get() {
            if (histogram.count <= 1.0) {
                return 0.0
            }
            // n > 1
            val v = histogram.variance
            val a = histogram.average
            if (a == 0.0){
                return Double.POSITIVE_INFINITY
            }
            val n = histogram.count
            return ((n - 1.0) * v / a)
        }

    val indexOfDispersion: Double
        get() {
            if (histogram.count <= 1.0) {
                return 0.0
            }
            val a = histogram.average
            if (a == 0.0){
                return Double.POSITIVE_INFINITY
            }
            val v = histogram.variance
            return v/a
        }
}