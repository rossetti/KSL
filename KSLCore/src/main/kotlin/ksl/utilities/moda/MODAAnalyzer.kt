package ksl.utilities.moda

import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.WithinRepViewData

/**
 * @param metric the metric representing the response. Use this to define the range of the response (for scaling)
 * and to define the direction of value. The default is smaller is better. The name of the metric should be
 * the name of the corresponding response or counter within the model
 * @param weight thw swing weight associated with the response. The default is 1.0. The weights
 * are normalized to ultimately be between 0 and 1. A common equal weight will lead to equal weighting of the responses.
 * @param valueFunction the value function associated with the metric. The default is a linear value function.
 */
data class MODAAnalyzerData(
    val metric: MetricIfc,
    internal var weight: Double = 1.0,
    val valueFunction: ValueFunctionIfc = LinearValueFunction(metric),
) {
    val responseName: String = metric.name

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

    /** The names of the responses as supplied within the response definitions
     */
    val responseNames: Set<String>

    private var myMODAByRepMap = mutableMapOf<Int, AdditiveMODAModel>()
    val modaByReplication: Map<Int, AdditiveMODAModel>
        get() = myMODAByRepMap

    private var myMCBObjValMap = mutableMapOf<String, MutableList<Double>>()
    val overallValueByAlternative: Map<String, List<Double>>
        get() = myMCBObjValMap

    init {
        require(alternativeNames.isNotEmpty()) { "The number of alternatives/scenarios/experiment names must be >=1" }
        require(responseDefinitions.isNotEmpty()) { "The number of response names must be >= 1" }
        val mSet = mutableSetOf<String>()
        for (defn in responseDefinitions) {
            mSet.add(defn.responseName)
        }
        responseNames = mSet
    }

    private data class DefinitionMaps(
        var responseMetrics: Map<String, MetricIfc>,
        var metricDefinitions: Map<MetricIfc, ValueFunctionIfc>,
        var weights: MutableMap<MetricIfc, Double>
    )

    private fun createDefinitionMaps(): DefinitionMaps {
        require(alternativeNames.isNotEmpty()) { "The number of alternatives/scenarios/experiment names must be >=1" }
        require(responseDefinitions.isNotEmpty()) { "The number of response names must be >= 1" }
        val rMap = mutableMapOf<String, MetricIfc>()
        val mMap = mutableMapOf<MetricIfc, ValueFunctionIfc>()
        val wMap = mutableMapOf<MetricIfc, Double>()
        for (defn in responseDefinitions) {
            val m = defn.metric.newInstance()
//            val m = Metric(defn.metric.name, defn.metric.domain,
//                allowLowerLimitAdjustment = defn.metric.allowLowerLimitAdjustment,
//                allowUpperLimitAdjustment = defn.metric.allowUpperLimitAdjustment)
//            m.direction = defn.metric.direction
            rMap[defn.responseName] = m
//            val vf = defn.valueFunction //TODO this probably needs to be a new value function instance as well
//            vf.metric = m
            mMap[m] = defn.valueFunction.newInstance(m)
            wMap[m] = defn.weight
        }
        return DefinitionMaps(rMap, mMap, wMap)
    }


    /**
     *  Changes or assigns the weights for the additive model. The required number
     *  of metrics must be the number of metrics defined for the model. And,
     *  the metrics must all be in the model. The weights are normalized to ensure
     *  that they sum to 1.0 and are individually between [0, 1].
     *  The total weight supplied must be greater than 0.0. After assignment
     *  the total weight should be equal to 1.0.
     */
    fun changeWeights(newWeights: Map<String, Double>) {
        require(newWeights.keys.size == responseDefinitions.size) { "The supplied number of metrics does not match the required number of metrics!" }
        val totalWeight = sumWeights(newWeights)
        require(totalWeight > 0.0) { "The total weight must be > 0.0" }
        for (df in responseDefinitions) {
            df.weight = newWeights[df.responseName] ?: df.weight
        }
    }

    /**
     *  Sums the weights. Can be used to process weights
     */
    private fun sumWeights(weights: Map<String, Double>): Double {
        var sum = 0.0
        for ((_, weight) in weights) {
            sum = sum + weight
        }
        return sum
    }

    //TODO
    // - tallying performance (rankings) across replications
    // - presenting an overall (average) MODA???
    // - presenting the results

    fun analyze(
        responseData: List<WithinRepViewData>,
        allowRescalingByMetrics: Boolean = true
    ) {
        if (responseData.isEmpty()) {
            KSL.logger.info { "MODAAnalyzer: the supplied list of within replication view data was empty." }
            return
        }
        myMODAByRepMap.clear()
        myMCBObjValMap.clear()
        val scoresByRep = processWithinRepViewData(responseData)
        if (scoresByRep.isEmpty()) {
            KSL.logger.info { "MODAAnalyzer: after processing within replication view data there were no records remaining." }
            return
        }
        // we now have the alternative/exp scores for each response for each replication
        // a MODA model can now be built for each replication
        val modaMapByRep = mutableMapOf<Int, AdditiveMODAModel>()
        val mcbListData = mutableMapOf<String, MutableList<Double>>()
        for ((rep, altData) in scoresByRep) {
            // due to possible metric scaling the metrics cannot be reused
            // this creates new metrics with the same name and properties for use on each replication
            val dfMaps = createDefinitionMaps()
            val moda = AdditiveMODAModel(dfMaps.metricDefinitions, dfMaps.weights)
            // convert altData to scores here
            moda.defineAlternatives(convertToScores(dfMaps.responseMetrics, altData), allowRescalingByMetrics)
            modaMapByRep[rep] = moda
            // capture overall average scores for MCB analysis of overall score
            val repObjValues = moda.multiObjectiveValuesByAlternative()
            for ((altName, objValue) in repObjValues) {
                if (!mcbListData.containsKey(altName)) {
                    mcbListData[altName] = mutableListOf()
                }
                mcbListData[altName]!!.add(objValue)
            }
        }
        myMODAByRepMap = modaMapByRep
        myMCBObjValMap = mcbListData
    }

    private fun convertToScores(
        responseMetrics: Map<String, MetricIfc>,
        unScoredValues: Map<String, Map<String, Double>>
    ): Map<String, List<Score>>{
        val map = mutableMapOf<String, MutableList<Score>>()
        // key is exp/alt, list holds scores of each response
        for((expName, responseMap) in unScoredValues){
            if (!map.containsKey(expName)){
                map[expName] = mutableListOf()
            }
            // get the list
            val scoreList = map[expName]!! // must be there, now we can add scores
            for((responseName, value) in responseMap){
                // look up the metric for the response
                val m = responseMetrics[responseName]!!
                // use the metric to create the score
                val s = if (value.isNaN()) m.badScore() else Score(m, value)
                scoreList.add(s)
            }
        }
        return map
    }

    /**
     *  Processes the within replication view data from a set of experiments. Reorganizes the data
     *  and returns for each replication, the experiment, and the responses and their values for the
     *  replication.  The returned Map<Int, Map<String, Map<String, Double>>>
     *  (key Int = replication number, key exp name, key response name, response value
     *
     */
    private fun processWithinRepViewData(
        responseData: List<WithinRepViewData>,
    ): Map<Int, Map<String, Map<String, Double>>> {
        if (responseData.isEmpty()) {
            KSL.logger.info { "MODAAnalyzer: the supplied list of within replication view data was empty." }
            return emptyMap()
        }
        // find the minimum number replications
        val n = responseData.minOf { it.num_reps }
        if (n <= 1) {
            KSL.logger.info { "MODAAnalyzer: There was only 1 replication in the within replication view data" }
            return emptyMap()
        }
        // restrict analysis to those having the specified number of replications
        val expData = responseData.filter {
            (it.num_reps == n) && (it.exp_name in alternativeNames) && (it.stat_name in responseNames)
        }
        if (expData.isEmpty()) {
            KSL.logger.info { "MODAAnalyzer: no within replication view records matched the experiment names and response names." }
            return emptyMap()
        }
        // all remaining are from desired experiments, having equal number of replications, and required responses
        // get the data by replication
        val byRep = expData.groupBy { it.rep_id }
        val performanceByRep = mutableMapOf<Int, MutableMap<String, Map<String, Double>>>()
        for ((rep, data) in byRep) {
            // get the data for each experiment
            val byExp = data.groupBy { it.exp_name }
            // now process each experiment
            val responsesByExperiment = mutableMapOf<String, Map<String, Double>>()
            for ((eName, byExpData) in byExp) {
                // get each response's data value for the replication into a list
                val responseMap = mutableMapOf<String, Double>() // name of response, value of response
                for (vData in byExpData) {
                    responseMap[vData.stat_name] = vData.rep_value?:Double.NaN
                }
                responsesByExperiment[eName] = responseMap
            }
            // we now have the alternative/exp score for each response for the current replication
            performanceByRep[rep] = responsesByExperiment
        }
        return performanceByRep
    }
}