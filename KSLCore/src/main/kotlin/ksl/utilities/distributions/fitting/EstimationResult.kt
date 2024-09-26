package ksl.utilities.distributions.fitting

import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.parameters.RVParameters
import ksl.utilities.statistic.BootstrapEstimate
import ksl.utilities.statistic.BootstrapSampler
import ksl.utilities.statistic.MVBSEstimatorIfc
import ksl.utilities.statistic.StatisticIfc

/**
 *  Holds information from the parameter bootstrapping process.
 *  @param totalMSE the total estimated mean squared error summed over the parameters
 *  @param totalBias the total estimated bias summed over the parameters
 *  @param totalVariance the total estimated variance summed over the parameters. This ignores
 *  any dependence between parameters.
 *  @param estimates the base bootstrap estimate for each parameter
 */
data class BootStrapResults(
    val totalMSE: Double,
    val totalBias: Double,
    val totalVariance: Double,
    val estimates: Map<String, BootstrapEstimate>
)

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
    var success: Boolean,
    val estimator: MVBSEstimatorIfc
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

    /**
     *  If the original data has been shifted, this returns the shifted data.
     *  If the original data has not been shifted, this returns the original data.
     */
    val testData: DoubleArray
        get() {
            return if (shiftedData != null){
                shiftedData!!.shiftedData
            } else {
                originalData
            }
        }

    /**
     *  Computes and saves the bootstrap results for the quality
     *  of the parameter estimates.
     */
    fun bootStrapResults(
        numBootstrapSamples: Int = 399,
        stream: RNStreamIfc = KSLRandom.nextRNStream(),
    ): BootStrapResults {
        val map = bootstrapParameters(numBootstrapSamples, stream)
        var tb = 0.0
        var tm = 0.0
        var tv = 0.0
        for((_,b) in map){
            tb = tb + b.bootstrapBiasEstimate
            tm = tm + b.bootstrapMSEEstimate
            tv = tv + b.acrossBootstrapStatistics.variance
        }
        return BootStrapResults(tm, tb, tv, map)
    }

    /**
     *  Performs the bootstrap sampling of the parameters associated
     *  with the estimation result.
     *
     *  @param numBootstrapSamples the number of bootstrap samples
     *  @param stream the stream for the bootstrap sampling
     *  @return map of BootStrapEstimate instances representing the bootstrap
     *  estimate results for each parameter. The key to the map is the name
     *  of the parameter.
     */
    fun bootstrapParameters(
        numBootstrapSamples: Int = 399,
        stream: RNStreamIfc = KSLRandom.nextRNStream(),
    ): Map<String, BootstrapEstimate> {
        val data = if (shiftedData != null) {
            shiftedData!!.shiftedData
        } else {
            originalData
        }
        val bss = BootstrapSampler(data, estimator, stream)
        val list = bss.bootStrapEstimates(numBootstrapSamples)
        val map = mutableMapOf<String, BootstrapEstimate>()
        for (e in list) {
            map[e.name] = e
        }
        return map
    }

    /**
     *  Returns a map containing the double and integer valued
     *  parameters from the estimation result.
     *
     *  The key to the map is the name of the parameter
     *  and the value is the current estimated value of the parameter. If the
     *  parameter is integer value, it is converted to a double value.
     *
     *  The map may be empty if the underlying parameters for the
     *  estimation result was null.
     */
    fun parameters(): Map<String, Double> {
        return if (this.parameters == null) {
            mapOf()
        } else {
            parameters.asDoubleMap()
        }
    }

    /**
     *  Returns an array containing the double and integer valued
     *  parameters. The elements of the array are the parameter values
     *  based on the order of their names in doubleParameterNames
     *  and integerParameterNames. If the
     *  parameter is integer value, it is converted to a double value.
     *
     *  The array may be empty if the underlying parameters for the
     *  estimation result was null.
     */
    fun parametersAsDoubleArray(): DoubleArray {
        return if (this.parameters == null) {
            doubleArrayOf()
        } else {
            parameters.asDoubleArray()
        }
    }

    /**
     *  Returns a map containing the percentile bootstrap confidence
     *  intervals for the parameters associated with the estimation result.
     *
     *  The key to the map is the name of the parameter as specified
     *  by the estimator associated with the estimation result
     *  and the value is an interval representing the percentile bootstrap
     *  confidence interval.
     *
     *  @param numBootstrapSamples the number of bootstrap samples
     *  @param level the desired confidence interval level for each parameter
     *  @param stream the stream for the bootstrap sampling
     *  @return a map with key = parameter name and value being the interval
     *
     */
    fun percentileBootstrapCI(
        numBootstrapSamples: Int = 399,
        level: Double = 0.95,
        stream: RNStreamIfc = KSLRandom.nextRNStream(),
    ): Map<String, Interval> {
        val map = mutableMapOf<String, Interval>()
        val bMap = bootstrapParameters(numBootstrapSamples, stream)
        for ((name, e) in bMap) {
            map[name] = e.percentileBootstrapCI(level)
        }
        return map
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