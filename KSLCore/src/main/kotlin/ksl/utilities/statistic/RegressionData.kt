package ksl.utilities.statistic

import ksl.utilities.KSLArrays
import ksl.utilities.isRectangular
import ksl.utilities.maxNumColumns
import ksl.utilities.transpose
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.add
import org.jetbrains.kotlinx.dataframe.api.emptyDataFrame
import org.jetbrains.kotlinx.dataframe.api.toColumn

/**
 *  The [response] is an n by 1 array of the data, where n is the number of observations for a
 *   response variable.
 *  The [data] is an n by k matrix of the data for the regression, where
 *  k is the number of regression coefficients and n is the number of observations. This
 *  data should not include a column of 1's for estimating an intercept term. The rows
 *  of the array represent the predictor values associated with each observation. The
 *  array must be rectangular. That is, each row has the same number of columns.
 *  @param responseName an optional name for the response variable. The default is Y
 *  @param predictorNames a list of names to associate with the predictors. The
 *  default is X_1, X_2,..., X_k
 */
data class RegressionData(
    val response: DoubleArray,
    val data: Array<DoubleArray>,
    val hasIntercept: Boolean = true,
    var responseName: String = "Y",
    val predictorNames: List<String> = makePredictorNames(data)
) {

    init {
        require(response.size == data.size) { "The number of observations do not match the regression observations" }
        require(data.isRectangular()) { "The data matrix must be rectangular. Each array must be of the same size" }
        val np = data.maxNumColumns()
        require(np == predictorNames.size) { "There need to be $np predictor names" }
        require(response.size > numParameters) { "The number of observations must be greater than the number of parameters to estimate" }
    }

    val numPredictors: Int
        get() = predictorNames.size

    val numParameters: Int
        get() = if (hasIntercept) numPredictors + 1 else numPredictors

    val numObservations: Int
        get() = response.size

    /**
     *  The data associated with the named predictor. The name must exist as
     *  a predictor name.
     */
    fun predictorData(name: String): DoubleArray {
        require(predictorNames.contains(name)) { "The name of the predictor ($name) was not found" }
        val cn = predictorNames.indexOf(name)
        return KSLArrays.column(cn, data)
    }

    fun toDataFrame(): DataFrame<Any> {
        val r = response.toList().toColumn(responseName)
        var df = emptyDataFrame<Any>()
        df = df.add(r)
        for ((i, name) in predictorNames.withIndex()) {
            val col = KSLArrays.column(i, data).toList().toColumn(name)
            df = df.add(col)
        }
        return df
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Regression Data")
        sb.appendLine("number of predictors = $numPredictors")
        sb.appendLine("number of parameters = $numParameters")
        sb.appendLine("number of observations = $numObservations")
        sb.appendLine("has intercept = $hasIntercept")
        sb.appendLine("response name = $responseName")
        sb.appendLine("Predictor Names")
        for (name in predictorNames) {
            sb.appendLine(name)
        }
        sb.appendLine()
        return sb.append(toDataFrame()).toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RegressionData

        if (!response.contentEquals(other.response)) return false
        if (!data.contentDeepEquals(other.data)) return false
        if (hasIntercept != other.hasIntercept) return false
        if (responseName != other.responseName) return false
        if (predictorNames != other.predictorNames) return false

        return true
    }

    override fun hashCode(): Int {
        var result = response.contentHashCode()
        result = 31 * result + data.contentDeepHashCode()
        result = 31 * result + hasIntercept.hashCode()
        result = 31 * result + responseName.hashCode()
        result = 31 * result + predictorNames.hashCode()
        return result
    }

    companion object {

        /**
         *  Create the regression data from a data frame. The data frame
         *  must have a column with the response name [responseName] and
         *  columns with the names in the list [predictorNames]. The
         *  data type of these columns must be Double. [hasIntercept] indicates
         *  if the regression should include an intercept term. The default is
         *  true. The data in the data frame does not need to have a column
         *  for estimating the intercept.
         */
        fun create(
            df: AnyFrame,
            responseName: String,
            predictorNames: List<String>,
            hasIntercept: Boolean = true
        ): RegressionData {
            val cn = df.columnNames()
            require(cn.contains(responseName)) { "There is no column with response name $responseName" }
            for (name in predictorNames) {
                require(cn.contains(name)) { "There is no column with predictor name $name" }
            }
            require(df[responseName].type().classifier == Double::class) { "The response was not a Double" }
            for (name in predictorNames) {
                require(df[name].type().classifier == Double::class) { "There predictor ($name) was not a Double" }
            }
            val y = (df[responseName].toList() as List<Double>).toDoubleArray()
            val x = mutableListOf<DoubleArray>()
            for (name in predictorNames) {
                x.add((df[name].toList() as List<Double>).toDoubleArray())
            }
            return RegressionData(y, x.toTypedArray().transpose(), hasIntercept, responseName, predictorNames)
        }

        /**
         *  @return a list of predictor names X_1, X_2, etc
         */
        fun makePredictorNames(data: Array<DoubleArray>): List<String> {
            require(data.isRectangular()) { "The data matrix must be rectangular. Each array must be of the same size" }
            return makePredictorNames(data.maxNumColumns())
        }

        /**
         *  @return a list of predictor names X_1, X_2, etc
         */
        fun makePredictorNames(numPredictors: Int): List<String> {
            return List(numPredictors) { "X_${it + 1}" }
        }
    }
}