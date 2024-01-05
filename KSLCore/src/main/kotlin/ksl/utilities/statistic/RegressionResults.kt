package ksl.utilities.statistic

data class RegressionResults(
    val parameters: DoubleArray,
    val parametersStdError: DoubleArray,
    val parametersVariance: Array<DoubleArray>,
    val residuals: DoubleArray,
    val regressandVariance: Double,
    val rSquared: Double,
    val adjustedRSquared: Double,
    val regressionStandardError: Double,
    val residualSumOfSquares: Double,
    val totalSumOfSquares: Double,
//    val errorSumSquares: Double,
//    val meanSquareError: Double,
    val errorVariance: Double,
    val hatMatrix: Array<DoubleArray>,
    val hasIntercept: Boolean,
    val numParameters: Int,
    val numObservations: Long
) {

}
