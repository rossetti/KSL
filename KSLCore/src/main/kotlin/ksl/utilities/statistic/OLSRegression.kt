package ksl.utilities.statistic

import ksl.utilities.KSLArrays
import org.hipparchus.stat.regression.OLSMultipleLinearRegression
import org.jetbrains.kotlinx.dataframe.AnyFrame

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