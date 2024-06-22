package ksl.utilities.statistic

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.io.dbutil.DbTableData
import ksl.utilities.io.plotting.StringFrequencyPlot
import ksl.utilities.random.robj.DEmpiricalList
import ksl.utilities.random.rvariable.toDouble
import ksl.utilities.statistic.ErrorResult.*
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import kotlin.math.sqrt

/**
 *  There are two classes class 1 (positive) and class 0 (negative)
 *  An instance (exemplar) must be in one of the classes.
 */
enum class ErrorResult {
    TP, FP, FN, TN
}

data class Classification(val actual: Double, val predicted: Double) {
    constructor(actual: Int, predicted: Int) : this(actual.toDouble(), predicted.toDouble())
    constructor(predicted: Boolean, actual: Boolean) : this(predicted.toDouble(), actual.toDouble())

    val classification
        get() = ErrorMatrix.classify(actual, predicted)
}

/**
 *  Computes the [confusion matrix](https://en.wikipedia.org/wiki/Confusion_matrix)
 *
 *  There are two classes class 1 (positive) and class 0 (negative)
 *  An instance (exemplar) must be in one of the classes.
 */
class ErrorMatrix(
    data: Collection<ErrorResult>? = null,
    name: String? = null,
) : IdentityIfc by Identity(name) {

    val stringFrequency: StringFrequency

    init {
        val limitSet = ErrorResult.entries.map { it.toString() }.toSet()
        stringFrequency = StringFrequency(name = name, limitSet = limitSet)
        if (data != null) {
            for (result in data) {
                stringFrequency.collect(result.toString())
            }
        }
    }

    /**
     *  Present the case to the matrix for tabulation
     *  first = actual, second = predicted
     */
    fun collect(case: Classification) {
        collect(case.classification)
    }

    /**
     *  Present the case to the matrix for tabulation
     *  first = actual, second = predicted
     */
    fun collect(case: Pair<Double, Double>) {
        collect(classify(case.first, case.second))
    }

    /**
     *  Present the case to the matrix for tabulation
     */
    fun collect(actual: Boolean, predicted: Boolean) {
        collect(classify(actual, predicted))
    }

    /**
     *  Present the case to the matrix for tabulation
     */
    fun collect(actual: Int, predicted: Int) {
        collect(classify(actual, predicted))
    }

    /**
     *  Present the case to the matrix for tabulation
     */
    fun collect(actual: Double, predicted: Double) {
        collect(classify(actual, predicted))
    }

    /**
     *  Present the case to the matrix for tabulation
     */
    fun collect(errorResult: ErrorResult) {
        stringFrequency.collect(errorResult.toString())
    }

    fun reset() {
        stringFrequency.reset()
    }

    fun count(errorResult: ErrorResult): Int {
        return stringFrequency.frequency(errorResult.toString()).toInt()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Confusion matrix:")
        sb.append(stringFrequency)
        sb.appendLine()
        sb.appendLine("numTP = $numTP")
        sb.appendLine("numFP = $numFP")
        sb.appendLine("numTN = $numTN")
        sb.appendLine("numFN = $numFN")
        sb.appendLine("numP = $numP")
        sb.appendLine("numN = $numN")
        sb.appendLine("total = $total")
        sb.appendLine("numPP = $numPP")
        sb.appendLine("numPN = $numPN")
        sb.appendLine("prevalence = $prevalence")
        sb.appendLine("accuracy = $accuracy")
        sb.appendLine("truePositiveRate = $truePositiveRate")
        sb.appendLine("falseNegativeRate = $falseNegativeRate")
        sb.appendLine("falsePositiveRate = $falsePositiveRate")
        sb.appendLine("trueNegativeRate = $trueNegativeRate")
        sb.appendLine("falseOmissionRate = $falseOmissionRate")
        sb.appendLine("precision = $precision")
        sb.appendLine("positivePredictiveValue = $positivePredictiveValue")
        sb.appendLine("falseDiscoveryRate = $falseDiscoveryRate")
        sb.appendLine("negativePredictiveValue = $negativePredictiveValue")
        sb.appendLine("positiveLikelihoodRatio = $positiveLikelihoodRatio")
        sb.appendLine("negativeLikelihoodRatio = $negativeLikelihoodRatio")
        sb.appendLine("markedness = $markedness")
        sb.appendLine("diagnosticOddsRatio = $diagnosticOddsRatio")
        sb.appendLine("balancedAccuracy = $balancedAccuracy")
        sb.appendLine("f1Score = $f1Score")
        sb.appendLine("fowlkesMallowsIndex = $fowlkesMallowsIndex")
        sb.appendLine("mathhewsCorrelationCoefficient = $mathhewsCorrelationCoefficient")
        sb.appendLine("threatScore = $threatScore")
        sb.appendLine("informedness = $informedness")
        sb.appendLine("prevalenceThreshold = $prevalenceThreshold")
        return sb.toString()

    }

    val numTP: Int get() = count(TP)
    val numFP: Int get() = count(FP)
    val numTN: Int get() = count(TN)
    val numFN: Int get() = count(FN)
    val numP: Int get() = numTP + numFN
    val numN: Int get() = numFP + numTN
    val total: Int get() = stringFrequency.totalCount
    val numPP: Int get() = numTP + numFP
    val numPN: Int get() = numFN + numTN

    val prevalence: Double
        get() {
            if (total == 0) return Double.NaN
            return numPP / total.toDouble()
        }

    val accuracy: Double
        get() {
            if (total == 0) return Double.NaN
            return (numTP + numTN) / total.toDouble()
        }

    val truePositiveRate: Double
        get() {
            if (numP == 0) return Double.NaN
            return numTP / numP.toDouble()
        }

    val falseNegativeRate: Double
        get() {
            if (numP == 0) return Double.NaN
            return numFN / numP.toDouble()
        }

    val falsePositiveRate: Double
        get() {
            if (numN == 0) return Double.NaN
            return numFP / numN.toDouble()
        }

    val trueNegativeRate: Double
        get() {
            if (numN == 0) return Double.NaN
            return numTN / numN.toDouble()
        }

    val falseOmissionRate: Double
        get() {
            if (numPN == 0) return Double.NaN
            return numFN / numPN.toDouble()
        }

    val precision: Double
        get() {
            if (numPP == 0) return Double.NaN
            return numTP / numPP.toDouble()
        }

    val positivePredictiveValue: Double
        get() = precision

    val falseDiscoveryRate: Double
        get() {
            if (numPP == 0) return Double.NaN
            return numFP / numPP.toDouble()
        }

    val negativePredictiveValue: Double
        get() {
            if (numPN == 0) return Double.NaN
            return numTN / numPN.toDouble()
        }

    val positiveLikelihoodRatio: Double
        get() = truePositiveRate / falsePositiveRate

    val negativeLikelihoodRatio: Double
        get() = falseNegativeRate / trueNegativeRate

    val markedness: Double
        get() = positivePredictiveValue + negativePredictiveValue - 1.0

    val diagnosticOddsRatio: Double
        get() = positiveLikelihoodRatio / negativeLikelihoodRatio

    val balancedAccuracy: Double
        get() = (truePositiveRate + trueNegativeRate) / 2.0

    val f1Score: Double
        get() = (2.0 * positivePredictiveValue * truePositiveRate) / (positivePredictiveValue + truePositiveRate)

    val fowlkesMallowsIndex: Double
        get() = sqrt(positivePredictiveValue * truePositiveRate)

    val mathhewsCorrelationCoefficient: Double
        get() {
            val a = sqrt(truePositiveRate * trueNegativeRate * positivePredictiveValue * negativePredictiveValue)
            val b = sqrt(falseNegativeRate * falsePositiveRate * falseOmissionRate * falseDiscoveryRate)
            return a - b
        }

    val threatScore: Double
        get() {
            if (total == 0) return Double.NaN
            return numTP / (numTP + numFN + numFP).toDouble()
        }

    val informedness: Double
        get() = truePositiveRate + trueNegativeRate - 1.0

    val prevalenceThreshold: Double
        get() {
            val num = sqrt(truePositiveRate * falsePositiveRate) - falsePositiveRate
            val dnom = truePositiveRate - falsePositiveRate
            return num / dnom
        }

    fun frequencyData(sortedByCount: Boolean = false): List<StringFrequencyData> {
        return stringFrequency.frequencyData(sortedByCount)
    }

    fun frequencyPlot(proportions: Boolean = false): StringFrequencyPlot {
        return stringFrequency.frequencyPlot(proportions)
    }

    fun asErrorMatrixData() : ErrorMatrixData {
        return ErrorMatrixData(id = id, name = name, numTP, numFP, numTN, numFN)
    }

    fun asErrorMatrixRecord() : ErrorMatrixRecord {
        return ErrorMatrixRecord(id = id, name = name, numTP, numFP, numTN, numFN)
    }

    companion object {
        /**
         *  actual true means that the instance belongs to class 1 (positive)
         *  actual false means that the instance belongs to class 0 (negative)
         *  predicted true means that the instance was predicted to be in class 1 (positive)
         *  predicted false means that the instance was predicted to be in class 0 (negative)
         */
        fun classify(actual: Boolean, predicted: Boolean): ErrorResult {
            return if (actual) {
                if (predicted) {
                    TP
                } else {
                    FN
                }
            } else {
                if (predicted) {
                    FP
                } else {
                    TN
                }
            }
        }

        /**
         *  actual = 1 means that the instance belongs to class 1 (positive)
         *  actual = 0 means that the instance belongs to class 0 (negative)
         *  predicted = 1 means that the instance was predicted to be in class 1 (positive)
         *  predicted = 0 means that the instance was predicted to be in class 0 (negative)
         *  actual and predicted must be 1 or 0
         */
        fun classify(actual: Int, predicted: Int): ErrorResult {
            require((actual == 0) || (actual == 1)) { "actual must be 0 or 1" }
            require((predicted == 0) || (predicted == 1)) { "actual must be 0 or 1" }
            return if (actual == 1) {
                if (predicted == 1) {
                    TP
                } else {
                    FN
                }
            } else {
                if (predicted == 1) {
                    FP
                } else {
                    TN
                }
            }
        }

        /**
         *  actual = 1.0 means that the instance belongs to class 1 (positive)
         *  actual = 0.0 means that the instance belongs to class 0 (negative)
         *  predicted = 1.0 means that the instance was predicted to be in class 1 (positive)
         *  predicted = 0.0 means that the instance was predicted to be in class 0 (negative)
         *  actual and predicted must be 1.0 or 0.0
         */
        fun classify(actual: Double, predicted: Double): ErrorResult {
            return classify(actual.toInt(), predicted.toInt())
        }
    }
}

data class ErrorMatrixData(
    var id: Int = 1,
    var name: String = "",
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
)

data class ErrorMatrixRecord(
    var id: Int = 1,
    var name: String = "",
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
): DbTableData("tblErrorMatrix", listOf("id"))

fun main() {
    val possibilities = listOf(TP, FP, FN, TN)
    val rList = DEmpiricalList<ErrorResult>(possibilities, doubleArrayOf(0.20, 0.7, 0.8, 1.0))
    val data = rList.sample(100)
    val sf = ErrorMatrix(data = data)
    println(sf)
    sf.frequencyPlot().showInBrowser()
    println(sf.frequencyData().toDataFrame())
}