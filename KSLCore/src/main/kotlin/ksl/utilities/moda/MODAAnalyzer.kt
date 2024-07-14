package ksl.utilities.moda

import ksl.utilities.io.dbutil.WithinRepViewData
import ksl.utilities.moda.AdditiveMODAModel.Companion.sumWeights

/**
 * @param responseName the name of the response or counter that will serve as
 * part of the evaluation based on the metric
 * @param weight thw swing weight associated with the response. The default is 1.0. The weights
 * are normalized to ultimately be between 0 and 1. A common equal weight will lead to equal weighting of the responses.
 * @param metric the metric representing the response. Use this to define the range of the reponse (for scaling)
 * and to define the direction of value. The default is smaller is better.
 * @param valueFunction the value function associated with the metric. The default is a linear value function.
 */
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
 * @param alternativeNames the set of names representing the experiments or scenarios
 * to be evaluated.
 * @param responseDefinitions A specification of the name of the response, its
 * associated weight, metric, and value function within a Set
 */
class MODAAnalyzer(
    private val alternativeNames: Set<String>,
    private val responseDefinitions: Set<MODAAnalyzerData>
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
        require(responseDefinitions.size >= 2) { "The number of responses must be >= 2" }
        val rMap = mutableMapOf<String, MetricIfc>()
        val mMap = mutableMapOf<MetricIfc, ValueFunctionIfc>()
        val wMap = mutableMapOf<MetricIfc, Double>()
        for (defn in responseDefinitions) {
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
        require(newWeights.keys.size == metricDefinitions.keys.size) { "The supplied number of metrics does not match the required number of metrics!" }
        for (metric in newWeights.keys) {
            require(metricDefinitions.containsKey(metric)) { "The supplied weight's metric is not in the model" }
        }
        val totalWeight = sumWeights(newWeights)
        require(totalWeight > 0.0) { "The total weight must be > 0.0" }
        for ((metric, weight) in newWeights) {
            weights[metric] = weight / totalWeight
        }
    }
    //TODO
    // - defining the alternatives (experiments/scenarios)
    // - supplying the within replication view data
    // - providing the scores of each alternative Map<String, List<Score>>
    // - making a MODA model for each replication
    // - tallying the overall score for each replication for each alternative for MCB analysis
    // - tallying performance (rankings) across replications
    // - presenting an overall (average) MODA

    fun analyze(responseData: List<WithinRepViewData>) {
        val scoresByRep = processWithinRepViewData(responseData)
        if (scoresByRep.isEmpty()){
            //TODO log this?
            return
        }
        // we now have the alternative/exp score for each response for each replication
        // a MODA model can now be built for each replication
    }

    private fun processWithinRepViewData(
        responseData: List<WithinRepViewData>
    ): Map<Int, Map<String, List<Score>>> {
        // find the minimum number replications
        val n = responseData.minOf { it.num_reps }
        if (n <= 1) {
            //TODO log this?
            return emptyMap()
        }
        // restrict analysis to those having the specified number of replications
        val expData = responseData.filter {
            (it.num_reps == n) && (it.exp_name in alternativeNames) && (it.stat_name in responseMetrics.keys)
        }
        if (expData.isEmpty()) {
            //TODO log this?
            return emptyMap()
        }
        // all remaining are from desired experiments, having equal number of replications, and required responses
        // get the data by replication
        val byRep = expData.groupBy { it.rep_id }
        val scoresByRep = mutableMapOf<Int, MutableMap<String, List<Score>>>()
        for ((rep, data) in byRep) {
            // get the data for each experiment
            val byExp = data.groupBy { it.exp_name }
            // now process each experiment
            val altScores = mutableMapOf<String, List<Score>>()
            for ((eName, byExpData) in byExp) {
                // get each response's data value for the replication into a list
                val scoreList = mutableListOf<Score>()
                for (vData in byExpData) {
                    // look up the metric for the datum
                    val m = responseMetrics[vData.stat_name]!!
                    // create a score based on the data, decide about null values and bad scores
                    val s = if (vData.rep_value == null) m.badScore() else Score(m, vData.rep_value!!)
                    scoreList.add(s)
                }
                altScores[eName] = scoreList
            }
            // we now have the alternative/exp score for each response for the current replication
            scoresByRep[rep] = altScores
        }
        return scoresByRep
    }


}