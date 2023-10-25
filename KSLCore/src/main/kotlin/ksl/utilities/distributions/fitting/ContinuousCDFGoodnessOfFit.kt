package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.*
import ksl.utilities.min
import ksl.utilities.multiplyConstant
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.removeAt
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistics
import kotlin.math.ceil
import kotlin.math.floor

class ContinuousCDFGoodnessOfFit(
    data: DoubleArray,
    val distribution: ContinuousDistributionIfc,
    numEstimatedParameters: Int = 1,
    breakPoints: DoubleArray = suggestBreakPoints(data, distribution),
) : DistributionGOF(data, numEstimatedParameters, breakPoints) {

    override val binProbabilities = histogram.binProbabilities(distribution)

    override val expectedCounts = histogram.expectedCounts(distribution)

    companion object{
        fun suggestBreakPoints(data: DoubleArray, distribution: ContinuousDistributionIfc): DoubleArray {
            // get initial break points based on equalized uniform test
            var bp = PDFModeler.equalizedCDFBreakPoints(data.size, distribution)
            // use domain of distribution to add lower or upper limits
            val domain = distribution.domain()
            if (domain.lowerLimit.isFinite()){
                if (domain.lowerLimit < bp.first()){
                    bp = Histogram.addLowerLimit(domain.lowerLimit, bp)
                }
            } else {
                // lower limit of domain is infinite (must be negative infinity)
                // use the data to guide the choice, get a c.i. on the min
                val minCI = PDFModeler.confidenceIntervalForMinimum(data, level = 0.99)
                if (minCI.lowerLimit < bp.first()){
                    bp = Histogram.addLowerLimit(floor(minCI.lowerLimit), bp)
                }
            }
            if (domain.upperLimit.isFinite()){
                if (domain.upperLimit > bp.last()){
                    bp = Histogram.addUpperLimit(domain.upperLimit, bp)
                }
            } else {
                // upper limit of domain is infinite (must be positive infinity)
                // use data to guide choice of upper limit, get a c.i. on the max
                val maxCI = PDFModeler.confidenceIntervalForMaximum(data, level = 0.99)
                if (maxCI.upperLimit > bp.last()){
                    val delta = 6.0*data.statistics().standardDeviation
                    bp = Histogram.addUpperLimit(ceil(maxCI.upperLimit + delta), bp)
                }
            }
            return bp
        }
    }
    val andersonDarlingStatistic: Double
        get() = Statistic.andersonDarlingTestStatistic(data, distribution)

    val cramerVonMisesStatistic: Double
        get() = Statistic.cramerVonMisesTestStatistic(data, distribution)

//    val watsonTestStatistic: Double
//        get() = Statistic.watsonTestStatistic(data, distribution)

    val ksStatistic: Double
        get() = Statistic.ksTestStatistic(data, distribution)

    val ksPValue: Double
        get() = KolmogorovSmirnovDist.complementaryCDF(data.size, ksStatistic)

    val andersonDarlingPValue: Double
        get() = 1.0 - andersonDarlingCDF(data.size, andersonDarlingStatistic)

    val cramerVonMisesPValue: Double
        get() = 1.0 - cramerVonMisesCDF(data.size, cramerVonMisesStatistic)

//    val watsonTestPValue: Double
//        get() = 1.0 - watsonCDF(data.size, watsonTestStatistic)

    fun gofTestResults() : String {
        val sb = StringBuilder().apply {
            appendLine()
            appendLine("Goodness of Fit Test Results:")
            appendLine("K-S test statistic = $ksStatistic")
            appendLine("K-S test p-value = $ksPValue")
            appendLine()
            appendLine("Anderson-Darling test statistic = $andersonDarlingStatistic")
            appendLine("Anderson-Darling test p-value = $andersonDarlingPValue")
            appendLine()
            appendLine("Cramer-Von-Mises test statistic = $cramerVonMisesStatistic")
            appendLine("Cramer-Von-Mises test p-value = $cramerVonMisesPValue")
//            appendLine("Watson test statistic = $watsonTestStatistic")
//            appendLine("Watson test p-value = $watsonTestPValue")
        }
        return sb.toString()
    }

    override fun toString(): String {
        val sb = StringBuilder().apply {
            appendLine("GOF Results for Distribution: $distribution")
            appendLine("---------------------------------------------------------")
            appendLine()
            append(chiSquaredTestResults())
            append(gofTestResults())
        }
        return sb.toString()
    }
}

fun main(){
    val d = Exponential(10.0)
    val e = d.randomVariable
    e.advanceToNextSubStream()
    val n = 1000
    val data = e.sample(n)
    val gof = ContinuousCDFGoodnessOfFit(data, d)
    println(gof)
}
