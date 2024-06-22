package ksl.examples.general.utilities.fitting

import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.DbTableData
import ksl.utilities.io.dbutil.SimpleDb
import ksl.utilities.random.rvariable.ParameterizedRV
import ksl.utilities.random.rvariable.RVParametersTypeIfc
import ksl.utilities.statistic.ErrorMatrixData
import ksl.utilities.statistic.Statistic
import java.nio.file.Path
import kotlin.math.sqrt

/**
 *   Each case has an ID for identification. The label represents
 *   the string representation of the distribution being tested.
 *   The family field represents the family name of the distribution.
 *   Each case is dependent upon the number of replications of the
 *   same sample size.  That is, all experiments of different sample sizes
 *   use the same number of repeated samples [numSamples]
 */
data class CaseData(
    var caseID: Int = -1,
    var label: String = "",
    var family: String = "",
    var numSamples: Int = 1
) : DbTableData("Cases", listOf("caseID"))

/**
 *  See csv file _Statistical_Results.csv
 *  Prob(rank=1) this is the probability that the correct family was recommended. This is
 *  the average over that the samples in the (caseID, sampleSize) combination.
 *  Prob(rank<=2) this is the probability that the correct family was ranked 1 or 2.
 *  This is the average over the samples in the (caseID, sampleSize) combination.
 *  Prob(rank<=3) this is the probability that the correct family was ranked 1,2,or 3.
 *  This is the average over the samples in the (caseID, sampleSize) combination.
 *  parameter error this should be named the parameterName_E with the value the error
 *      This is the average error over the samples in the (caseID, sampleSize) combination.
 *  parameter relative error this should be named the parameterName_RE with the value the relative error
 *      This is the average error over the samples in the (caseID, sampleSize) combination.
 *  parameter with 10% of true, within 20% of true, with 30% of true
 *      parameterName_10, parameterName_20, parameterName_30 with corresponding averages
 *    over the samples in the (caseID, sampleSize) combination.
 *
 *  These statistics could be computed/derived from CaseScoringResults
 */
data class CaseStatistics(
    var caseID: Int = -1,
    var sampleSize: Int = 1,
    var statName: String = "",
    var statValue: Double = 0.0,
) : DbTableData("CaseStatistics", listOf("caseID", "sampleSize", "statName"))

/**
 *  These error matrix measures are collected across the samples generated for each
 *  case for each metric used to score the distributions. This data represents
 *  the performance of the metric in correctly classifying the case distribution's family.
 *  If the metric ranks the estimated distribution as rank 1, then that distribution
 *  will be classified either correctly or incorrectly. If the metric ranks the
 *  estimated distribution 2 or higher, then the metric would not recommend the distribution
 *  and thus, it is classifying the distribution as not the recommended distribution. This
 *  classification can be either correct or incorrect. This data tabulates the
 *  error matrix (or [confusion matrix](https://en.wikipedia.org/wiki/Confusion_matrix)
 *  for the classification by the metric.
 */
data class CaseMetricErrorMatrixData(
    var caseID: Int = -1,
    var sampleSize: Int = 1,
    var metricName: String = "",
    var numTP: Int = 1,
    var numFP: Int = 1,
    var numTN: Int = 1,
    var numFN: Int = 1,
    var numP: Int = numTP + numFN,
    var numN: Int = numFP + numTN,
    var total: Int = numP + numN,
    var numPP: Int = numTP + numFP,
    var numPN: Int = numFN + numTN,
    var prevalence: Double = if (total == 0) Double.NaN else numPP / total.toDouble(),
    var accuracy: Double = if (total == 0) Double.NaN else (numTP + numTN) / total.toDouble(),
    var truePositiveRate: Double = if (numP == 0) Double.NaN else numTP / numP.toDouble(),
    var falseNegativeRate: Double = if (numP == 0) Double.NaN else numFN / numP.toDouble(),
    var falsePositiveRate: Double = if (numN == 0) Double.NaN else numFP / numN.toDouble(),
    var trueNegativeRate: Double = if (numN == 0) Double.NaN else numTN / numN.toDouble(),
    var falseOmissionRate: Double = if (numPN == 0) Double.NaN else numFN / numPN.toDouble(),
    var precision: Double = if (numPP == 0) Double.NaN else numTP / numPP.toDouble(),
    var positivePredictiveValue: Double = precision,
    var falseDiscoveryRate: Double = if (numPP == 0) Double.NaN else numFP / numPP.toDouble(),
    var negativePredictiveValue: Double = if (numPN == 0) Double.NaN else numTN / numPN.toDouble(),
    var positiveLikelihoodRatio: Double = truePositiveRate / falsePositiveRate,
    var negativeLikelihoodRatio: Double = falseNegativeRate / trueNegativeRate,
    var markedness: Double = positivePredictiveValue + negativePredictiveValue - 1.0,
    var diagnosticOddsRatio: Double = positiveLikelihoodRatio / negativeLikelihoodRatio,
    var balancedAccuracy: Double = (truePositiveRate + trueNegativeRate) / 2.0,
    var f1Score: Double = (2.0 * positivePredictiveValue * truePositiveRate) / (positivePredictiveValue + truePositiveRate),
    var fowlkesMallowsIndex: Double = sqrt(positivePredictiveValue * truePositiveRate),
    var mathhewsCorrelationCoefficient: Double = sqrt(truePositiveRate * trueNegativeRate * positivePredictiveValue * negativePredictiveValue)
            - sqrt(falseNegativeRate * falsePositiveRate * falseOmissionRate * falseDiscoveryRate),
    var threatScore: Double = if (total == 0) Double.NaN else numTP / (numTP + numFN + numFP).toDouble(),
    var informedness: Double = truePositiveRate + trueNegativeRate - 1.0,
    var prevalenceThreshold: Double = (sqrt(truePositiveRate * falsePositiveRate) - falsePositiveRate) /
            (truePositiveRate - falsePositiveRate)
): DbTableData("CaseMetricErrorMatrix",
    listOf("caseID", "sampleSize", "metricName"))

/**
 *  Each case identifies the distribution being fitting. The distribution can have
 *  one or more parameters. This data class captures the name of each parameter
 *  and its true value for the case.
 */
data class CaseParameters(
    var caseID: Int = -1,
    var parameterName: String = "",
    var parameterValue: Double = 0.0
) : DbTableData("CaseParameters", listOf("caseID", "parameterName")) {

    companion object {

        fun create(dfTestCase: DFTestCase): List<CaseParameters> {
            val list = mutableListOf<CaseParameters>()
            val parameters = dfTestCase.rv.parameters
            val pm = parameters.asDoubleMap()
            for ((paramName, paramValue) in pm) {
                val cp = CaseParameters(dfTestCase.case.caseID, paramName, paramValue)
                list.add(cp)
            }
            return list
        }
    }
}

/**
 *  This class captures the statistical summary results for each randomly generated
 *  sample of the various sample sizes for each case.  This holds the sample
 *  data in wide-format.
 */
data class CaseSampleStatistics(
    var caseID: Int = -1,
    var sampleSize: Int = 1,
    var sampleID: Int = 1,
    var avg: Double = 0.0,
    var sd: Double = 0.0,
    var cv: Double = 0.0,
    var kurtosis: Double = 0.0,
    var skewness: Double = 0.0,
    var centralMoment2: Double = 0.0,
    var centralMoment3: Double = 0.0,
    var centralMoment4: Double = 0.0,
    var rawMoment2: Double = 0.0,
    var rawMoment3: Double = 0.0,
    var rawMoment4: Double = 0.0,
    var min: Double = 0.0,
    var max: Double = 0.0
) : DbTableData("CaseSampleResults", listOf("caseID", "sampleSize", "sampleID"))

/**
 *  For every (caseID, sampleSize, sampleID) combination one of the possible
 *  distributions is estimated.  This class captures the results of the estimation
 *  process for each distribution.  This includes:
 *
 *   - estimated values for each distribution parameter, use same name as case parameter name,
 *     there will be an estimated parameter value for each sampleID
 *   - recorded score value for each specified score
 *   - recorded metric value for each specified metric
 *   - recorded rank value for each specified metric. This is the rank of the metric with respect
 *     to the set of metrics (as if only that metric was used to rank the distribution).
 *
 *  resultName = (score names, metric names, rank names, parameter names, overallWeight,
 *  first rank count, average ranking)
 *  resultValue is the value of the named score, metric, rank, or parameter, etc.
 *
 *  This holds the data in long format. To get to wide format see:
 *  - https://kaicui726.medium.com/advanced-sql-reshaping-tables-d80c81e3fd9e
 *  - https://stackoverflow.com/questions/2255640/mysql-reshape-data-from-long-tall-to-wide
 *
 *  Basically from this data all other summary statistics could be computed.
 *  @param caseID The identifier for the case.
 *  @param sampleSize The size of the sample being tested for the case.
 *  @param sampleID Each case can be evaluated over a specific number of samples. This
 *  represents the identifier of the sample (sample number).
 *  @param estimatedDistribution This is the family name of the distribution that had its
 *  parameters estimated, was scored, and was ranked.
 *  @param resultName This represents the name of the data element to be stored about the distribution
 *  that was estimated from the data.
 *  @param resultValue This represents the value associated with the supplied result name.
 */
data class CaseScoringResults(
    var caseID: Int = -1,
    var sampleSize: Int = 1,
    var sampleID: Int = 1,
    var estimatedDistribution: String = "",
    var resultType: String = "",
    var resultName: String = "",
    var resultValue: Double = 0.0,
    var classification: String? = null,
) : DbTableData(
    "CaseScoringResults",
    listOf("caseID", "sampleSize", "sampleID", "estimatedDistribution", "resultType", "resultName")
)

class ResultsDb(
    dbName: String,
    tableDefinitions: Set<DbTableData> = resultTables,
    dbDirectory: Path = KSL.dbDir
) : SimpleDb(tableDefinitions, dbName, dbDirectory) {

    fun saveCase(dfTestCase: DFTestCase) {
        insertDbDataIntoTable(dfTestCase.case)
    }

    fun saveCaseParameters(dfTestCase: DFTestCase) {
        val caseParameters: List<CaseParameters> = dfTestCase.caseParameters()
        insertAllDbDataIntoTable(caseParameters, caseParameters.first().tableName)
    }

    fun saveScoringResults(list: List<CaseScoringResults>){
        val tblName = list.first().tableName
        insertAllDbDataIntoTable(list, tblName)
    }

    fun saveErrorMatrixData(list: List<CaseMetricErrorMatrixData>){
        val tblName = list.first().tableName
        insertAllDbDataIntoTable(list, tblName)
    }

    fun saveStatistics(caseID: Int, sampleSize: Int, sampleID: Int, statistic: Statistic) {
        val cs = CaseSampleStatistics()
        cs.caseID = caseID
        cs.sampleSize = sampleSize
        cs.sampleID = sampleID
        cs.avg = statistic.average
        cs.sd = statistic.standardDeviation
        cs.cv = cs.sd / cs.avg
        cs.kurtosis = statistic.kurtosis
        cs.skewness = statistic.skewness
        cs.centralMoment2 = statistic.centralMoment2
        cs.centralMoment3 = statistic.centralMoment3
        cs.centralMoment4 = statistic.centralMoment4
        cs.rawMoment2 = statistic.rawMoment2
        cs.rawMoment3 = statistic.rawMoment3
        cs.rawMoment4 = statistic.rawMoment4
        cs.min = statistic.min
        cs.max = statistic.max
        insertDbDataIntoTable(cs)
    }

    companion object {
        val resultTables = setOf(
            CaseData(),
            CaseStatistics(),
            CaseParameters(),
            CaseSampleStatistics(),
            CaseScoringResults(),
            CaseMetricErrorMatrixData()
        )
    }
}

/**
 *  A DFTestCase has a random variable specified and the sample size
 *  associated with the generation from the random variable. The number of
 *  samples indicates how many times the case requires the sampling
 *  to be repeated.
 */
class DFTestCase(
    val rv: ParameterizedRV,
    val sampleSizes: Set<Int>,
    numSamples: Int = 200
) {
    init {
        require(sampleSizes.size >= 2) { "There must be at least two sample sizes to test." }
        require(sampleSizes.min() >= 2) { "The minimum sample size must be >= 2" }
        require(numSamples >= 2) { "The number of samples to generate must be >= 2" }
        id = id + 1
        rv.parameters.rvType
    }

    var automaticShifting: Boolean = false

    fun rvType() : RVParametersTypeIfc = rv.parameters.rvType

    val case: CaseData = CaseData(id, rv.toString(), rvType().toString(), numSamples)

    fun caseParameters(): List<CaseParameters> {
        return CaseParameters.create(this)
    }

    companion object {
        private var id = 0
    }
}

fun main() {
    val rb = ResultsDb("CaseResults.db")
}