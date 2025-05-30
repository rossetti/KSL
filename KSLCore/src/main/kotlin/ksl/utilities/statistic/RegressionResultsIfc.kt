package ksl.utilities.statistic

import ksl.utilities.Interval
import ksl.utilities.KSLArrays
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.StudentT
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.plotting.FitDistPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.plotting.ScatterPlot
import org.hipparchus.distribution.continuous.FDistribution
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.jetbrains.kotlinx.dataframe.api.toColumn
import org.jetbrains.kotlinx.dataframe.io.DisplayConfiguration
import org.jetbrains.kotlinx.dataframe.io.toHTML
import org.jetbrains.kotlinx.dataframe.io.toStandaloneHTML
import java.util.*
import kotlin.math.abs
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
    val regressionSumOfSquares : Double
        get() = totalSumOfSquares - residualSumOfSquares

    /**
     *  The degrees of freedom for the regression (numParameters - 1)
     */
    val regressionDoF : Double
        get() = numParameters - 1.0

    /**
     *  This is MSR = SSR/(p-1)
     */
    val meanSquaredOfRegression
        get() = regressionSumOfSquares / regressionDoF

    /**
     *  This is MSR/MSE
     */
    val fStatistic : Double
        get() = meanSquaredOfRegression / meanSquaredError

    val fPValue: Double
        get() {
            val f0 = fStatistic
            val dofNum = regressionDoF
            val dofDenom = errorDoF
            return 1.0 - FDistribution(dofNum,dofDenom).cumulativeProbability(f0)
        }

    /**
     *  This is SSE (sum of squared residual error).
     */
    val residualSumOfSquares: Double

    /**
     *  The degrees of freedom for the total error
     */
    val totalDoF : Double
        get() = numObservations - 1.0

    /**
     *  The SST total sum of squares. Sum of squared deviations of Y from its mean.
     */
    val totalSumOfSquares: Double

    /**
     *   The degrees of freedom for the error
     */
    val errorDoF : Double
        get() = (numObservations - numParameters).toDouble()

    /**
     *  An estimate of the variance of the (residual) errors. This is MSE = SSE/(n-p)
     */
    val errorVariance: Double

    /**
     *   A pseudonym for error variance (MSE)
     */
    val meanSquaredError : Double
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
            return DoubleArray(t0.size) { 2.0 * (1.0 - StudentT.cdf(dof, abs(t0[it]))) }
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
        val paramTValues = parameterTStatistics.toList().toColumn("t0-ratio")
        val pValues = parameterPValues.toList().toColumn("2*P(T>|t0|)")
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
        sb.appendLine(anovaResults())
        sb.appendLine("-------------------------------------------------------------------------------------")
        sb.appendLine("Error Variance (MSE) = $errorVariance")
        sb.appendLine("Regression Standard Error = $regressionStandardError")
        sb.appendLine("R-Squared = $rSquared")
        sb.appendLine("Adjusted R-Squared = $adjustedRSquared")
        sb.appendLine("-------------------------------------------------------------------------------------")
        sb.appendLine("Parameter Estimation Results")
        sb.appendLine(parameterResults(level))
        sb.appendLine("-------------------------------------------------------------------------------------")
        return sb.toString()
    }

    /**
     *  The regression results in the form of a html string
     */
    fun htmlResults(level: Double = 0.95) : String {
        val sb = StringBuilder().apply {
            appendLine("<h1>")
            appendLine("Regression Results")
            appendLine("</h1>")
            appendLine("<div>")
            appendLine("<pre>")
            appendLine(anovaResults())
            appendLine("</pre>")
            appendLine("</div>")
            appendLine("<div>")
            appendLine("<pre>")
            appendLine("Error Variance (MSE) = $errorVariance")
            appendLine("Regression Standard Error = $regressionStandardError")
            appendLine("R-Squared = $rSquared")
            appendLine("Adjusted R-Squared = $adjustedRSquared")
            appendLine("</pre>")
            appendLine("</div>")
            val pr = parameterResults(level)
            pr.rowsCount()
            val config = DisplayConfiguration.DEFAULT
            config.rowsLimit = pr.rowsCount() + 1
            config.cellContentLimit = 72
            appendLine("<div>")
            appendLine(pr.toStandaloneHTML(configuration = config))
            appendLine("</div>")
            appendLine("<div>")
            appendLine(htmlDiagnosticPlots())
            appendLine("</div>")
        }
        return sb.toString()
    }

    /**
     *  Shows the diagnostic plots within a browser window.
     */
    fun showResultsInBrowser(level: Double = 0.95) {
        KSLFileUtil.openInBrowser(fileName = "Regression_Results", htmlResults(level))
    }

    /**
     *  ANOVA results for regression as a string
     */
    fun anovaResults() : String {
        val sb = StringBuilder()
        sb.appendLine("Analysis of Variance")
        val formatter = Formatter(sb)
        formatter.format(
            "%-15s %10s %10s %15s %15s %15s %n", "Source   ", "SumSq",
            "DOF", "MS", "f_0", "P(F>f0)"
        )
        formatter.format(
            "%-15s %10g %10.0f %15f %15f %15f %n", "Regression", regressionSumOfSquares,
            regressionDoF, meanSquaredOfRegression, fStatistic, fPValue
        )
        formatter.format("%-15s %10g %10.0f %15f %n", "Error", residualSumOfSquares, errorDoF, meanSquaredError)
        val dof = regressionDoF + errorDoF
        formatter.format("%-15s %10g %10.0f ", "Total", totalSumOfSquares, dof)
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

    fun htmlDiagnosticPlots(): String {
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
        return sb.toString()
    }

    /**
     *  Shows the diagnostic plots within a browser window.
     */
    fun showDiagnosticPlotsInBrowser() {
        KSLFileUtil.openInBrowser(fileName = "Regression_Diagnostics", htmlDiagnosticPlots())
    }

    /**
     *  The data associated with the named predictor. The name must exist as
     *  a predictor name.
     */
    fun predictorData(name: String): DoubleArray
}