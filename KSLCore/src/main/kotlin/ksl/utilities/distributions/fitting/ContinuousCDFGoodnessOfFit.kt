package ksl.utilities.distributions.fitting

import ksl.utilities.distributions.*
import ksl.utilities.multiplyConstant
import ksl.utilities.removeAt
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc
import ksl.utilities.statistic.Statistic

class ContinuousCDFGoodnessOfFit(
    data: DoubleArray,
    val distribution: ContinuousDistributionIfc,
    numEstimatedParameters: Int = 1,
    breakPoints: DoubleArray = PDFModeler.equalizedCDFBreakPoints(data.size, distribution),
) : DistributionGOF(data, numEstimatedParameters, breakPoints) {

    override val binProbabilities = histogram.binProbabilities(distribution)

    override val expectedCounts = binProbabilities.multiplyConstant(histogram.count)

    val andersonDarlingStatistic: Double
        get() = Statistic.andersonDarlingTestStatistic(data, distribution)

    val cramerVonMisesStatistic: Double
        get() = Statistic.cramerVonMisesTestStatistic(data, distribution)

    val watsonTestStatistic: Double
        get() = Statistic.watsonTestStatistic(data, distribution)

    val ksStatistic: Double
        get() = Statistic.ksTestStatistic(data, distribution)

    val ksPValue: Double
        get() = KolmogorovSmirnovDist.complementaryCDF(data.size, ksStatistic)

    val andersonDarlingPValue: Double
        get() = 1.0 - andersonDarlingCDF(data.size, andersonDarlingStatistic)

    val cramerVonMisesPValue: Double
        get() = 1.0 - cramerVonMisesCDF(data.size, cramerVonMisesStatistic)

    val watsonTestPValue: Double
        get() = 1.0 - watsonCDF(data.size, watsonTestStatistic)
}
