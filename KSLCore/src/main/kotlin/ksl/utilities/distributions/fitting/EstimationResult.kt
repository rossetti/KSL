package ksl.utilities.distributions.fitting

import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.StatisticIfc

/**
 *  A data class to hold information from a parameter estimation algorithm.
 *  In general the algorithm may fail due to data or numerical computation issues.
 *  The [parameters] may be null because of such issues; however,
 *  there may be cases where the parameters are produced but the algorithm
 *  still considers the process a failure as indicated in the [success] field.
 *  The string [message] allows a general diagnostic explanation of success,
 *  failure, or other information about the estimation process. In the case
 *  of uni-variate distributions, there may be a shift parameter estimated on [shiftedData]
 *  in order to handle data that has a lower range of domain that does not
 *  match well with the distribution. The algorithm may compute [statistics] on the
 *  supplied data.
 */
class EstimationResult(
    val originalData: DoubleArray,
    var statistics: StatisticIfc,
    var shiftedData: ShiftedData? = null,
    val parameters: RVParameters? = null,
    var message: String? = null,
    var success: Boolean
){

    val distribution: String
        get() {
            return if ((parameters == null) || !success){
                "Success=$success: $message"
            } else {
                if (shiftedData != null){
                    "${shiftedData!!.shift} + ${PDFModeler.createDistribution(parameters).toString()}"
                } else {
                    PDFModeler.createDistribution(parameters).toString()
                }
            }
        }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Estimation Results:")
        if (success){
            sb.appendLine("The estimation was a SUCCESS!")
        } else {
            sb.appendLine("The estimation was a FAILURE!")
        }
        sb.appendLine("Estimation message:")
        sb.appendLine(message)
        sb.appendLine()
        sb.appendLine("Statistics for the data:")
        sb.appendLine(statistics)
        sb.appendLine()
        sb.appendLine("Shift estimation results:")
        sb.appendLine(shiftedData)
        sb.appendLine()
        sb.appendLine("Estimated parameters:")
        sb.appendLine(parameters)
        return sb.toString()
    }
}