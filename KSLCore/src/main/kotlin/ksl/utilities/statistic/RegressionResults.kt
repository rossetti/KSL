package ksl.utilities.statistic

import ksl.utilities.*
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.StudentT
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.plotting.FitDistPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.plotting.ScatterPlot
import ksl.utilities.io.write
import org.hipparchus.stat.regression.OLSMultipleLinearRegression
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.letsPlot.geom.geomVLine
import java.util.*
import kotlin.math.sqrt


/**
 *  A useful resource for regression can be found at (https://online.stat.psu.edu/stat501/lesson/5/5.3)
 */
interface RegressionResultsIfc {

    /**
     *  The name of the response variable
     */
    var responseName: String

    /**
     *  The names of the predictor variables
     */
    val predictorNames: List<String>

    /**
     *  Indicates true if the regression model includes an intercept term.
     */
    val hasIntercept: Boolean

    /**
     *  Number of parameters in the model (including the intercept, if estimated)
     */
    val numParameters: Int

    /**
     *  The response values, the regressand values, the Y's
     */
    val response: DoubleArray

    /**
     *  The total number of observations (y_1, y_2, ..., y_n), where
     *  n = the number of observations.
     */
    val numObservations: Int

    /**
     *  An array containing the estimated parameters of the regression.
     *  The b_0, b_1_,..., b_k, where b_0 is the intercept term and k is
     *  the number of parameters estimated, so p = k + 1 is the total
     *  number of parameters (including the intercept term).
     */
    val parameters: DoubleArray

    /**
     *  The standard error estimate for each regression coefficient.
     */
    val parametersStdError: DoubleArray

    /**
     *  An estimate of the variance of Y. The sample variance of the dependent variable.
     */
    val regressandVariance: Double

    /**
     *  This is the sum of squares of the regression (SSR)
     *  SST = SSR + SSE. Thus, SSR = SST - SSE
     */
    val regressionSumOfSquares
        get() = totalSumOfSquares - residualSumOfSquares

    /**
     *  The degrees of freedom for the regression (numParameters - 1)
     */
    val regressionDoF
        get() = numParameters - 1.0

    /**
     *  This is MSR = SSR/(p-1)
     */
    val meanSquaredOfRegression
        get() = regressionSumOfSquares / regressionDoF

    /**
     *  This is MSR/MSE
     */
    val fStatistic
        get() = meanSquaredOfRegression / meanSquaredError

    /**
     *  This is SSE (sum of squared residual error).
     */
    val residualSumOfSquares: Double

    /**
     *  The degrees of freedom for the total error
     */
    val totalDoF
        get() = numObservations - 1.0

    /**
     *  The SST total sum of squares. Sum of squared deviations of Y from its mean.
     */
    val totalSumOfSquares: Double

    /**
     *   The degrees of freedom for the error
     */
    val errorDoF
        get() = (numObservations - numParameters).toDouble()

    /**
     *  An estimate of the variance of the (residual) errors. This is MSE = SSE/(n-p)
     */
    val errorVariance: Double

    /**
     *   A pseudonym for error variance (MSE)
     */
    val meanSquaredError
        get() = errorVariance

    /**
     *  The average distance that the observed values fall from the regression line.
     *  It tells you how wrong the regression model is on average using the units of the response variable.
     *  Smaller values are better because it indicates that the observations are closer to the fitted line.
     *  The standard deviation of the errors in the regression model.  Sometimes called
     *  the standard error of the estimate. This is the square root of MSE.
     */
    val regressionStandardError: Double

    /**
     *  Estimates for the variance of the regression parameters.
     *  The variance-covariance matrix of the regression parameters
     */
    val parametersVariance: Array<DoubleArray>

    /**
     *  The array of residual errors, e_i = (y_i - yHat_i)
     */
    val residuals: DoubleArray

    /**
     *  The array of standardize residuals
     */
    val standardizedResiduals: DoubleArray

    /**
     *  This is the yHat_i. The predicted values for each observation.
     */
    val predicted: DoubleArray
        get() = KSLArrays.subtractElements(response, residuals)

    /**
     *  This is the coefficient of multiple determination.
     *  R-squared = SSR/SST = 1 - SSE/SST, where SSE is the sum of squared residuals,
     *  SST is the total sum of squares, and SSR is the sum of squares for the regression.
     *  Note that SST = SSR + SSE.
     */
    val rSquared: Double

    /**
     *   This is the adjusted R-squared = 1 - ((1 - R-squared)*(n-1)/(n-p)) where n is the number
     *   of observations and p is the number of parameters estimated (including the intercept).
     */
    val adjustedRSquared: Double

    /**
     * The hat matrix is defined in terms of the design matrix X by $X(X^{T}X)^{-1}X^{T}$
     */
    val hatMatrix: Array<DoubleArray>

    /**
     *  The diagonal entries from the hat matrix
     */
    val hatDiagonal: DoubleArray
        get() = KSLArrays.diagonal(hatMatrix)

    /**
     *  The studentized residuals for diagnostic plotting
     */
    val studentizedResiduals: DoubleArray
        get() {
            val h = hatDiagonal
            val st = standardizedResiduals
            val sr = DoubleArray(h.size)
            for (i in h.indices) {
                sr[i] = st[i] / sqrt(1.0 - h[i])
            }
            return sr
        }

    /**
     *  The Cook distance measures for diagnostic plotting
     */
    val cookDistanceMeasures: DoubleArray
        get() {
            val h = hatDiagonal
            val st = standardizedResiduals
            val d = DoubleArray(h.size)
            val np = numParameters.toDouble()
            for (i in h.indices) {
                val hd = (1.0 - h[i]) * (1.0 - h[i])
                d[i] = st[i] * st[i] * h[i] / (hd * np)
            }
            return d
        }

    /**
     *  The test statistics for testing if parameter j is significant.
     *  This is parameters[i] divided by parametersStdError[i].
     */
    val parameterTStatistics: DoubleArray
        get() = KSLArrays.divideElements(parameters, parametersStdError)

    val parameterPValues: DoubleArray
        get() {
            val t0 = parameterTStatistics
            val dof = errorDoF
            return DoubleArray(t0.size) { 2.0 * (1.0 - StudentT.cdf(dof, t0[it])) }
        }

    /**
     *  This assumes that the errors are normally distributed with
     *  mean zero and constant variance.
     *  The [level] must be a valid probability. The default is 0.95.
     *  @return a list of the intervals where the intervals are in the
     *  same order as the parameters array.
     */
    fun parameterConfidenceIntervals(level: Double = 0.95): List<Interval> {
        require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
        val dof = errorDoF
        val alpha = 1.0 - level
        val p = 1.0 - alpha / 2.0
        val t = StudentT.invCDF(dof, p)
        val list = mutableListOf<Interval>()
        for ((i, b) in parameters.withIndex()) {
            val hw = t * parametersStdError[i]
            val ll = b - hw
            val ul = b + hw
            list.add(Interval(ll, ul))
        }
        return list
    }

    /**
     *  A data frame holding the parameter results for the regression.
     */
    fun parameterResults(level: Double = 0.95): AnyFrame {
        require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
        val pn = mutableListOf<String>()
        if (hasIntercept) {
            pn.add("Intercept")
        }
        for (name in predictorNames) {
            pn.add(name)
        }
        val pNames = pn.toColumn("Predictor")
        val param = parameters.toList().toColumn("parameter")
        val paramSE = parametersStdError.toList().toColumn("parameterSE")
        val paramTValues = parameterTStatistics.toList().toColumn("TValue")
        val pValues = parameterPValues.toList().toColumn("P-Values")
        val intervals = parameterConfidenceIntervals(level)
        val lowerLimits = List(intervals.size) { intervals[it].lowerLimit }
        val upperLimits = List(intervals.size) { intervals[it].upperLimit }
        val llCol = lowerLimits.toColumn("LowerLimit")
        val ulCol = upperLimits.toColumn("UpperLimit")
        val df = dataFrameOf(pNames, param, paramSE, paramTValues, pValues, llCol, ulCol)
        return df
    }

    /**
     *  The regression results as a String.
     *  @param level the confidence level to use for the parameter confidence intervals
     */
    fun results(level: Double = 0.95): String {
        val sb = StringBuilder()
        sb.appendLine("Regression Results")
        sb.appendLine("-------------------------------------------------------------------------------------")
        sb.appendLine("Parameter Estimation Results")
        sb.appendLine(parameterResults(level))
        sb.appendLine("-------------------------------------------------------------------------------------")
        sb.appendLine("Error Variance (MSE) = $errorVariance")
        sb.appendLine("Regression Standard Error = $regressionStandardError")
        sb.appendLine("R-Squared = $rSquared")
        sb.appendLine("Adjusted R-Squared = $adjustedRSquared")
        sb.appendLine("-------------------------------------------------------------------------------------")
        sb.appendLine("Analysis of Variance")
        val formatter = Formatter(sb)
        formatter.format(
            "%-15s %10s %10s %15s %15s %n", "Source   ", "SumSq",
            "DOF", "MS", "f_0"
        )
        formatter.format(
            "%-15s %10g %10.0f %15f %15f %n", "Regression", regressionSumOfSquares,
            regressionDoF, meanSquaredOfRegression, fStatistic
        )
        formatter.format("%-15s %10g %10.0f %15f %n", "Error", residualSumOfSquares, errorDoF, meanSquaredError)
        val dof = regressionDoF + errorDoF
        formatter.format("%-15s %10g %10.0f %n", "Total", totalSumOfSquares, dof)
        return sb.toString()
    }

    /**
     *  All the residual data in a data frame
     *  (responseName, "Predicted", "Residuals", "StandardizedResiduals", "StudentizedResiduals",
     *  "h_ii", "CookDistances")
     */
    fun residualsAsDataFrame(): AnyFrame {
        val r = response.toList().toColumn(responseName)
        val p = predicted.toList().toColumn("Predicted")
        val e = residuals.toList().toColumn("Residuals")
        val sr = standardizedResiduals.toList().toColumn("StandardizedResiduals")
        val st = studentizedResiduals.toList().toColumn("StudentizedResiduals")
        val h = hatDiagonal.toList().toColumn("h_ii")
        val cd = cookDistanceMeasures.toList().toColumn("CookDistances")
        return dataFrameOf(r, p, e, sr, st, h, cd)
    }

    /**
     *  A fit distribution plot of the standardized residuals for checking normality.
     */
    fun standardizedResidualsNormalPlot(): FitDistPlot {
        val n = Normal(0.0, 1.0)
        return FitDistPlot(standardizedResiduals, n, n)
    }

    /**
     *  A scatter plot of the residuals (on y-axis) and
     *  predicted (on x-axis).
     */
    fun residualsVsPredictedPlot(): ScatterPlot {
        val plot = ScatterPlot(predicted, residuals, 0.0)
        plot.title = "Predicted vs Residuals"
        plot.xLabel = "Predicted"
        plot.yLabel = "Residual"
        return plot
    }

    /**
     *  A plot of the residuals based on observation order.
     */
    fun residualsVsObservationOrderPlot(): ObservationsPlot {
        val plot = ObservationsPlot(residuals)
        plot.title = "Residuals vs Observation Order"
        plot.xLabel = "Observation Order"
        plot.yLabel = "Residuals"
        return plot
    }

    /**
     *  Shows the diagnostic plots within a browser window.
     */
    fun showDiagnosticPlotsInBrowser(){
        val sb = StringBuilder().apply {
            appendLine("<h1>")
            appendLine("Diagnostic Plots")
            appendLine("</h1>")
            appendLine("<div>")
            appendLine(standardizedResidualsNormalPlot().toHTML())
            appendLine("</div>")
            appendLine("<div>")
            appendLine(residualsVsPredictedPlot().toHTML())
            appendLine("</div>")
            appendLine("<div>")
            appendLine(residualsVsObservationOrderPlot().toHTML())
            appendLine("</div>")
        }
        KSLFileUtil.openInBrowser(fileName = "Regression_Diagnostics", sb.toString())
    }

    /**
     *  The data associated with the named predictor. The name must exist as
     *  a predictor name.
     */
    fun predictorData(name: String): DoubleArray
}

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

/**
 *  Performs Ordinary Least Squares fit of the data with the response.
 *  The default is to assume that an intercept term will be estimated.
 *
 *  @param regressionData specifies the data for the regression
 */
class OLSRegression(regressionData: RegressionData) : RegressionResultsIfc {

    private val myRegression = OLSMultipleLinearRegression()
    private lateinit var myRegressionData: RegressionData

    init {
        loadData(regressionData)
    }

    /**
     *  Create the regression data from a data frame. The data frame
     *  must have a column with the response name [responseName] and
     *  columns with the names in the list [predictorNames]. The
     *  data type of these columns must be Double. [hasIntercept] indicates
     *  if the regression should include an intercept term. The default is
     *  true. The data in the data frame does not need to have a column
     *  for estimating the intercept.
     */
    constructor(
        df: AnyFrame,
        responseName: String,
        predictorNames: List<String>,
        hasIntercept: Boolean = true
    ) : this(RegressionData.create(df, responseName, predictorNames, hasIntercept))

    /**
     *  Loads a new dataset for performing the regression analysis.
     */
    fun loadData(regressionData: RegressionData) {
        myRegressionData = regressionData.copy()
        myRegression.newSampleData(myRegressionData.response, myRegressionData.data)
        // default to having an intercept
        myRegression.isNoIntercept = !myRegressionData.hasIntercept
    }

    override var responseName: String
        get() = myRegressionData.responseName
        set(value) {
            myRegressionData.responseName = value
        }
    override val predictorNames: List<String>
        get() = myRegressionData.predictorNames

    override fun predictorData(name: String): DoubleArray {
        return myRegressionData.predictorData(name)
    }

    override val hasIntercept: Boolean
        get() = myRegressionData.hasIntercept
    override val numParameters: Int
        get() = myRegressionData.numParameters
    override val numObservations: Int
        get() = myRegressionData.numObservations
    override val response: DoubleArray
        get() = myRegressionData.response
    override val parameters: DoubleArray
        get() = myRegression.estimateRegressionParameters()
    override val parametersStdError: DoubleArray
        get() = myRegression.estimateRegressionParametersStandardErrors()
    override val regressandVariance: Double
        get() = myRegression.estimateRegressandVariance()
    override val residualSumOfSquares: Double
        get() = myRegression.calculateResidualSumOfSquares()
    override val totalSumOfSquares: Double
        get() = myRegression.calculateTotalSumOfSquares()
    override val errorVariance: Double
        get() = myRegression.estimateErrorVariance()
    override val regressionStandardError: Double
        get() = myRegression.estimateRegressionStandardError()
    override val parametersVariance: Array<DoubleArray>
        get() = myRegression.estimateRegressionParametersVariance()
    override val residuals: DoubleArray
        get() = myRegression.estimateResiduals()
    override val standardizedResiduals: DoubleArray
        get() = KSLArrays.divideConstant(residuals, regressionStandardError)
    override val rSquared: Double
        get() = myRegression.calculateRSquared()
    override val adjustedRSquared: Double
        get() = myRegression.calculateAdjustedRSquared()
    override val hatMatrix: Array<DoubleArray>
        get() = myRegression.calculateHat().data

    override fun toString(): String {
        return results()
    }
}

fun main() {
    val y = doubleArrayOf(
        9.95, 24.45, 31.75, 35.0, 25.02, 16.86, 14.38, 9.6, 24.35, 27.5, 17.08, 37.0,
        41.95, 11.66, 21.65, 17.89, 69.0, 10.3, 34.93, 46.59, 44.88, 54.12, 56.63, 22.13, 21.15
    )
    val x1 = doubleArrayOf(
        2.0, 8.0, 11.0, 10.0, 8.0, 4.0, 2.0, 2.0, 9.0, 8.0, 4.0, 11.0, 12.0, 2.0, 4.0,
        4.0, 20.0, 1.0, 10.0, 15.0, 15.0, 16.0, 17.0, 6.0, 5.0
    )
    val x2 = doubleArrayOf(
        50.0, 110.0, 120.0, 550.0, 295.0, 200.0, 375.0, 52.0, 100.0, 300.0, 412.0,
        400.0, 500.0, 360.0, 205.0, 400.0, 600.0, 585.0, 540.0, 250.0, 290.0, 510.0, 590.0, 100.0, 400.0
    )
    val data = arrayOf(x1, x2).transpose()
    val rd = RegressionData(y, data)
//    val df = rd.toDataFrame()
//    println(df)
    println()
    val r1 = OLSRegression(rd)
    println(r1)
//    println()
//    println(r1.residualsAsDataFrame())

//      r1.standardizedResidualsNormalPlot().showInBrowser()
//      r1.residualsVsPredictedPlot().showInBrowser()

//    println()
//    println("Test data frame")
//    val regressionData = RegressionData.create(df, "Y", listOf("X_1", "X_2"))
//    val r2 = OLSRegression(regressionData)
//    println(r2)
//    println()

}