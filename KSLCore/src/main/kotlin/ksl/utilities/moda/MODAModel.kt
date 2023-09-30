package ksl.utilities.moda

import ksl.utilities.Interval

/**
 *  Defines a base class for creating multi-objective decision analysis (MODA) models.
 */
abstract class MODAModel(
    val valueRange: Interval = Interval(0.0, 100.0)
) {

    init {
        require(valueRange.width > 0.0) { "The range of the value functions must have a width > 0.0" }
    }

    private val metricFunctionMap: MutableMap<MetricIfc, ValueFunctionIfc> = mutableMapOf()
    private val myAlternatives: MutableMap<String, List<Score>> = mutableMapOf()

    /**
     *  The set of metrics defined for the model.
     */
    val metrics: Set<MetricIfc>
        get() = metricFunctionMap.keys

    /**
     *  The set of alternative within the model.
     */
    val alternatives: Set<String>
        get() = myAlternatives.keys

    /**
     *   Defines the metrics to be used in the evaluation of the alternatives.
     *   Each metric must be associated with the related value function. If not
     *   it is not added to the model.  The value functions are all adjusted so
     *   that they return values that are consisted with the defined value range
     *   for the model. If there are previously defined metrics, they will be
     *   cleared and replaced by the supplied definitions.
     */
    fun defineMetrics(definitions: Map<MetricIfc, ValueFunctionIfc>) {
        if (metricFunctionMap.isNotEmpty()) {
            metricFunctionMap.clear()
        }
        for ((metric, valFunc) in definitions) {
            if (metric == valFunc.metric) {
                metricFunctionMap[metric] = valFunc
                valFunc.range.setInterval(valueRange.lowerLimit, valueRange.upperLimit)
            }
        }
    }

    /**
     *  Defines the alternatives and their scores that should be evaluated by the
     *  model. The metrics for the model must have been previously defined prior
     *  to specifying the alternatives. The scores supplied for each must have
     *  been created for each metric. If insufficient scores or incompatible
     *  score are provided, the alternative is not added to the model.
     */
    fun defineAlternatives(alternatives: Map<String, List<Score>>) {
        if (metricFunctionMap.isEmpty()) {
            throw IllegalStateException("There were no metrics defined for the model")
        }
        for ((name, list) in alternatives) {
            if (hasValidScores(list)) {
                myAlternatives[name] = list
            }
        }
    }

    /**
     *  Checks if there are sufficient metrics and if the metrics associated with
     *  each score are related to the defined metrics.
     */
    private fun hasValidScores(list: List<Score>): Boolean {
        if (metrics.size != list.size){
            return false
        }
        for (score in list) {
            if (!metrics.contains(score.metric)) {
                return false
            }
        }
        return true
    }

    /**
     *  Computes the multi-objective value for the specified
     *  alternative
     */
    abstract fun multiObjectiveValue(alternative: String) : Double

    /**
     *  Computes the overall values for all defined alternatives
     *  based on the defined multi-objective value function.
     */
    fun multiObjectiveValues() : Map<String, Double> {
        val map = mutableMapOf<String, Double> ()
        for (alternative in alternatives){
            map[alternative] = multiObjectiveValue(alternative)
        }
        return map
    }

    companion object {

        fun assignLinearValueFunctions(
            metrics: Set<Metric>,
            valueRange: Interval
        ): Map<Metric, ValueFunctionIfc> {
            val map = mutableMapOf<Metric, ValueFunctionIfc>()
            for (metric in metrics) {
                map[metric] = LinearValueFunction(metric, valueRange)
            }
            return map
        }
    }
}