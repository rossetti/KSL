/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.*
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.toDoubles
import kotlin.math.floor

class PMFModeler(
    private val data: IntArray
) {

    //   constructor(data : IntArray) : this(data.toDoubles())
    private val dataAsDoubles = data.toDoubles()

    val frequency: IntegerFrequency = IntegerFrequency(data = data)

    val statistics: StatisticIfc = frequency.statistic()

    val hasZeroes: Boolean
        get() = statistics.zeroCount > 0

    val hasNegatives: Boolean
        get() = statistics.negativeCount > 0

    /**
     *  This set holds the default set of estimators to try
     *  during the estimation process of the discrete distributions.
     */
    val defaultEstimators: Set<ParameterEstimatorIfc>
        get() = setOf(
            NegBinomialMOMParameterEstimator,
            PoissonMLEParameterEstimator,
            BinomialMOMParameterEstimator,
            BinomialMaxParameterEstimator
        )

    /**
     *  Estimates the parameters for all estimators represented by
     *  the set of [estimators]. Shifting of the data is not
     *  performed. It is assumed that the supplied estimators are
     *  appropriate for the supplied data.
     *
     *  The returned list will contain the results for
     *  each estimator.  Keep in mind that some estimators may fail the estimation
     *  process, which will be noted in the success property of the estimation results.
     */
    fun estimateParameters(
        estimators: Set<ParameterEstimatorIfc>
    ): List<EstimationResult> {
        val estimatedParameters = mutableListOf<EstimationResult>()
        for (estimator in estimators) {
            val result = estimator.estimate(dataAsDoubles, statistics)
            estimatedParameters.add(result)
        }
        return estimatedParameters
    }

    companion object {

        /**
         *  This function is similar in purpose to the similarly named function in PDFModeler.
         *  The primary difference is that this function ensures that the returned break points
         *  are unique because a set of probability values may map to the same value for a discrete
         *  distribution.
         *
         *  Computes breakpoints for the distribution that ensures (approximately) that
         *  the expected number of observations within the intervals defined by the breakpoints
         *  will be equal. That is, the probability associated with each interval is roughly
         *  equal. In addition, the expected number of observations will be approximately
         *  greater than or equal to 5.  There will be at least two breakpoints and thus at least
         *  3 intervals defined by the breakpoints.
         *
         *  If the sample size [sampleSize] is less than 15, then the approximate
         *  expected number of observations within the intervals may not be greater than or equal to 5.
         *  Note that the returned break points do not consider the range of the CDF
         *  and may require end points to be added to the beginning or end of the array
         *  to adjust for the range of the CDF.
         *
         *  The returned break points are based on the natural domain of the implied
         *  CDF and do not account for any shift that may be needed during the modeling
         *  process.
         */
        fun equalizedPMFBreakPoints(sampleSize: Int, dist: DiscreteDistributionIfc): DoubleArray {
            var bp = PDFModeler.equalizedCDFBreakPoints(sampleSize, dist)
            // there could be duplicate values, remove them by forcing them into a set
            val set = LinkedHashSet<Double>(bp.asList())
            // convert back to array
            bp = set.toDoubleArray()
            // make sure that they are sorted from smallest to largest
            bp.sort()
            return bp
        }

        /**
         *  Returns the probability for each bin of the [histogram] based on an open
         *  integer range interpretation of the bin .
         *  The discrete distribution, [discreteCDF] must implement the ProbInRangeIfc interface
         */
        fun binProbabilities(histogram: Histogram, discreteCDF : ProbInRangeIfc) : DoubleArray {
            val binProb = DoubleArray(histogram.numberBins)
            for((i, bin) in histogram.bins.withIndex()){
                binProb[i] = discreteCDF.probIn(bin.openIntRange)
            }
            return binProb
        }

        /**
         *  Returns the expected counts for each bin of the histogram as the
         *  first element of the Pair. The second element is the probability
         *  associated with each bin of the [histogram]. The discrete distribution, [discreteCDF]
         *  must implement the ProbInRangeIfc interface
         */
        fun expectedCounts(histogram: Histogram, discreteCDF : ProbInRangeIfc) : Pair<DoubleArray, DoubleArray>{
            val binProb = DoubleArray(histogram.numberBins)
            val expectedCounts = DoubleArray(histogram.numberBins)
            for((i, bin) in histogram.bins.withIndex()){
                binProb[i] = discreteCDF.probIn(bin.openIntRange)
                expectedCounts[i] = binProb[i]*histogram.count
            }
            return Pair(expectedCounts, binProb)
        }

        /**
         *  Creates break points for discrete distributions with domain 0 to positive infinity such
         *  as the Poisson and NegativeBinomial distributions. An attempt is made to achieve
         *  breakpoints with approximately equal probabilities. Zero is added as the first break point
         *  and positive infinity is added as the last break point.
         *
         *  If the sample size [sampleSize] is less than 15, then the approximate
         *  expected number of observations within the intervals may not be greater than or equal to 5.
         */
        fun makeZeroToInfinityBreakPoints(sampleSize: Int, dist: DiscreteDistributionIfc): DoubleArray {
            var bp = equalizedPMFBreakPoints(sampleSize, dist)
            // start the intervals at 0
            if (bp.min() > 0.0){
                bp = Histogram.addLowerLimit(0.0, bp)
            }
            // this ensures that the last interval captures all remaining data
            return Histogram.addPositiveInfinity(bp)
        }

        /**
         *  Constructs an instance of the appropriate discrete probability distribution
         *  for the provided random variable [parameters].  If no probability distribution
         *  is defined for the supplied type of random variable, then null is returned.
         */
        fun createDistribution(parameters: RVParameters): DiscreteDistributionIfc? {

            return when (parameters.rvType) {
                RVType.Binomial -> {
                    val p = parameters.doubleParameter("probOfSuccess")
                    val n = parameters.doubleParameter("numTrials")
                    return Binomial(p, n.toInt())
                }

                RVType.NegativeBinomial -> {
                    val p = parameters.doubleParameter("probOfSuccess")
                    val n = parameters.doubleParameter("numSuccesses")
                    return NegativeBinomial(p, floor(n))
                }

                RVType.Poisson -> {
                    val r = parameters.doubleParameter("mean")
                    return Poisson(r)
                }

                else -> null
            }
        }
    }
}