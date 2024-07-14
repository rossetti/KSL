package ksl.utilities.moda

import ksl.utilities.moda.MODAModel.Companion.makeEqualWeights

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
    private val weights: Map<MetricIfc, Double>

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


}