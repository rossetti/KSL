package ksl.utilities.moda

import ksl.utilities.moda.AdditiveMODAModel.Companion.sumWeights

data class MODAAnalyzerData(
    val responseName: String,
    val weight: Double = 1.0,
    val metric: MetricIfc = Metric(responseName),
    val valueFunction: ValueFunctionIfc = LinearValueFunction(metric),
) {
    init {
        require(responseName.isNotBlank()) { "The response name must not be empty or blank" }
        require(weight > 0.0) { "The supplied weight must be > 0.0" }
    }
}

/**
 * @param definitions A specification of the name of the response, its
 * associated weight, metric, and value function within a Set
 */
class MODAAnalyzer(
    private val definitions: Set<MODAAnalyzerData>
) {

    /** the mapping between responses in the models to
     * metrics used within the analysis
     */
    private val responseMetrics: Map<String, MetricIfc>

    /**
     * the definition of each metric and its value function
     */
    private val metricDefinitions: Map<MetricIfc, ValueFunctionIfc>

    /**
     * the swing weights to be used in the MODA analysis
     */
    private val weights: MutableMap<MetricIfc, Double>

    init {
        require(definitions.size >= 2) { "The number of responses must be >= 2" }
        val rMap = mutableMapOf<String, MetricIfc>()
        val mMap = mutableMapOf<MetricIfc, ValueFunctionIfc>()
        val wMap = mutableMapOf<MetricIfc, Double>()
        for (defn in definitions) {
            rMap[defn.responseName] = defn.metric
            mMap[defn.metric] = defn.valueFunction
            wMap[defn.metric] = defn.weight
        }
        responseMetrics = rMap
        metricDefinitions = mMap
        weights = wMap
    }

    /**
     *  Changes or assigns the weights for the additive model. The required number
     *  of metrics must be the number of metrics defined for the model. And,
     *  the metrics must all be in the model. The weights are normalized to ensure
     *  that they sum to 1.0 and are individually between [0, 1].
     *  The total weight supplied must be greater than 0.0. After assignment
     *  the total weight should be equal to 1.0.
     */
    fun changeWeights(newWeights: Map<MetricIfc, Double>) {
        require(newWeights.keys.size == metricDefinitions.keys.size){"The supplied number of metrics does not match the required number of metrics!"}
        for (metric in newWeights.keys) {
            require(metricDefinitions.containsKey(metric)) { "The supplied weight's metric is not in the model" }
        }
        val totalWeight = sumWeights(newWeights)
        require(totalWeight > 0.0) { "The total weight must be > 0.0" }
        for ((metric, weight) in newWeights) {
            weights[metric] = weight / totalWeight
        }
    }


}