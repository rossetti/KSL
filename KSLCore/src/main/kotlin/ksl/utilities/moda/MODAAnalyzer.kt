package ksl.utilities.moda

import ksl.utilities.Interval
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.WithinRepViewData
import ksl.utilities.io.toDataFrame
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import ksl.utilities.statistic.Statistic
import java.io.PrintWriter
import kotlin.math.ceil
import kotlin.math.floor

/**
 * @param responseName the name of the response or counter to be included in the analysis
 * @param direction the indication of the direction of value that provides the context for evaluation based on the
 * response value, with two cases: 1) bigger values are considered better or 2) smaller values are considered
 * better. The default is SmallerIsBetter.
 * @param weight thw swing weight associated with the response. The default is 1.0. The weights
 * are normalized to ultimately be between 0 and 1. A common equal weight will lead to equal weighting of the responses.
 * @param valueFunction the value function associated with the response. The default is a linear value function.
 * @param domain an interval that specifies the set of possible values for the response. The default is 0.0 to Double.Max_Value.
 * If the lower limit or upper limit of the domain is negative infinity, positive infinity, -Double.MAX_VALUE, or Double.MAX_VALUE then
 * the limits will be respecified based on available data.
 * @param unitsOfMeasure an optional description of the units of measure for the response
 * @param description an optional narrative explanation of the response
 */
data class MODAAnalyzerData(
    val responseName: String,
    val direction: MetricIfc.Direction = MetricIfc.Direction.SmallerIsBetter,
    var weight: Double = 1.0,
    val valueFunction: ValueFunctionIfc = LinearValueFunction(),
    val domain: Interval = Interval(0.0, Double.MAX_VALUE),
    var unitsOfMeasure: String? = null,
    var description: String? = null
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
 * @param responseData the data to use for the analysis
 */
class MODAAnalyzer(
    private val alternativeNames: Set<String>,
    private val responseDefinitions: Set<MODAAnalyzerData>,
    responseData: List<WithinRepViewData>
) {

    /** The names of the responses as supplied within the response definitions
     */
    val responseNames: Set<String>

    /**
     *  Represents the remaining response data that is used in the analysis
     *  after filtering by alternative name and response names
     */
    private val myResponseData: List<WithinRepViewData>

    private var myMODAByRepMap = mutableMapOf<Int, AdditiveMODAModel>()
    val modaByReplication: Map<Int, AdditiveMODAModel>
        get() = myMODAByRepMap

    private var myMCBObjValMap = mutableMapOf<String, MutableList<Double>>()
    val overallValueByAlternative: Map<String, List<Double>>
        get() = myMCBObjValMap

    private val responseMetrics: Map<String, MetricIfc>
    private val metricDefinitions: Map<MetricIfc, ValueFunctionIfc>
    private val weights: MutableMap<MetricIfc, Double>

    init {
        require(alternativeNames.isNotEmpty()) { "The number of alternatives/scenarios/experiment names must be >=1" }
        require(responseDefinitions.isNotEmpty()) { "The number of response names must be >= 1" }
        require(responseData.isNotEmpty()) { "The supplied response data must not be empty" }
        val responseSet = mutableSetOf<String>()
        // gather the response names
        for (responseDefinition in responseDefinitions) {
            responseSet.add(responseDefinition.responseName)
        }
        responseNames = responseSet
        // filter the data to required alternatives, number of replications, and response names
        val rd = filterWithinRepViewData(responseData)
        require(rd.isNotEmpty()) { "The filtered response data had no remaining records. Check KSL.log for more details." }
        myResponseData = rd
        // set up the definition maps here
        val rMap = mutableMapOf<String, MetricIfc>()
        val mMap = mutableMapOf<MetricIfc, ValueFunctionIfc>()
        val wMap = mutableMapOf<MetricIfc, Double>()
        for (responseDefinition in responseDefinitions) {
            val m = createMetric(responseDefinition, responseData)
            rMap[responseDefinition.responseName] = m
            mMap[m] = responseDefinition.valueFunction
            wMap[m] = responseDefinition.weight
        }
        responseMetrics = rMap
        metricDefinitions = mMap
        weights = wMap
    }

    /**
     *  Filters the within replication view data to only the required experiments, runs that have 2 or
     *  more replications, and have the required responses.
     */
    private fun filterWithinRepViewData(responseData: List<WithinRepViewData>): List<WithinRepViewData> {
        if (responseData.isEmpty()) {
            KSL.logger.info { "MODAAnalyzer: the supplied list of within replication view data was empty." }
            return emptyList()
        }
        // find the minimum number replications
        val n = responseData.minOf { it.num_reps }
        if (n <= 1) {
            KSL.logger.info { "MODAAnalyzer: There was only 1 replication in the within replication view data" }
            return emptyList()
        }
        // restrict analysis to those having the specified number of replications
        val expData = responseData.filter {
            (it.num_reps == n) && (it.exp_name in alternativeNames) && (it.stat_name in responseNames)
        }
        if (expData.isEmpty()) {
            KSL.logger.info { "MODAAnalyzer: no within replication view records matched the experiment names and response names." }
            return emptyList()
        }
        return expData
    }

    /**
     *  Creates the metrics needed for the MODA analysis. Ensures reasonable metric domains
     *  that cannot be changed (rescaled) based on MODA processing
     */
    private fun createMetric(
        responseDefinition: MODAAnalyzerData,
        responseData: List<WithinRepViewData>
    ): Metric {
        // check the supplied domain, if needed find recommended domain
        var lowerLimit = responseDefinition.domain.lowerLimit
        var upperLimit = responseDefinition.domain.upperLimit
        val responseName = responseDefinition.responseName
        if (lowerLimit.isInfinite() || lowerLimit == -Double.MAX_VALUE || upperLimit.isInfinite() || upperLimit == Double.MAX_VALUE) {
            // use data to get limits, then only adjust the ones that need adjustment
            val data = responseData.filter { it.stat_name == responseName }.map { it.rep_value ?: Double.NaN }
            val interval = recommendDomainInterval(data)
            if (lowerLimit.isInfinite() || lowerLimit == -Double.MAX_VALUE) {
                lowerLimit = interval.lowerLimit
            }
            if (upperLimit.isInfinite() || upperLimit == Double.MAX_VALUE) {
                upperLimit = interval.upperLimit
            }
        }
        val domain = Interval(lowerLimit, upperLimit)
        val metric = Metric(responseName, domain, allowLowerLimitAdjustment = false, allowUpperLimitAdjustment = false)
        metric.direction = responseDefinition.direction
        metric.description = responseDefinition.description
        metric.unitsOfMeasure = responseDefinition.unitsOfMeasure
        return metric
    }

    /**
     *  Changes or assigns the weights for the additive model. The keys to
     *  the map are the names of the responses. The values to the map are the weights.
     *  Each weight must be greater than 0.0
     */
    fun changeWeights(newWeights: Map<String, Double>) {
        require(newWeights.keys.size == weights.size) { "The supplied number of weights does not match the required number of weights!" }
        for ((rn, w) in newWeights) {
            require(w > 0.0) { "The supplied weight $w for response $rn was not > 0.0" }
            require(responseNames.contains(rn)) { "The supplied response $rn is not a valid response name" }
            val m = responseMetrics[rn]!!
            weights[m] = w
        }
    }

    /**
     *  Causes the analyzer to analyze the data to produce MODA models for
     *  each replication and results for an overall multiple comparison
     *  of the value scoring of each alternative.
     */
    fun analyze() {
        myMODAByRepMap.clear()
        myMCBObjValMap.clear()
        val scoresByRep = processWithinRepViewData()
        if (scoresByRep.isEmpty()) {
            KSL.logger.info { "MODAAnalyzer: after processing within replication view data there were no records remaining." }
            return
        }
        // we now have the alternative/exp scores for each response for each replication
        // a MODA model can now be built for each replication
        val modaMapByRep = mutableMapOf<Int, AdditiveMODAModel>()
        val mcbListData = mutableMapOf<String, MutableList<Double>>()
        for ((rep, altData) in scoresByRep) {
            val moda = AdditiveMODAModel(metricDefinitions, weights, name = "MODA Rep $rep")
            // convert altData to scores here
            moda.defineAlternatives(convertToScores(responseMetrics, altData), allowRescalingByMetrics = false)
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

    /**
     *  Returns a multiple comparison analyzer instance that permits
     *  the comparison of overall value for each alternative based on the MODA results.
     *  The returned result may be null if insufficient data exists to perform
     *  the analysis. There must be at least 2 alternative and there must be 2 or more
     *  observations for each alternative. The number of observations per alternative must
     *  also be the same.
     */
    fun mcbForOverallValue(): MultipleComparisonAnalyzer? {
        if (myMCBObjValMap.size < 2) {
            return null
        }
        val dm = myMCBObjValMap.mapValues { it.value.toDoubleArray() }.toMap()
        if (!MultipleComparisonAnalyzer.checkLengths(dm)) {
            return null
        }
        val size = dm.values.first().size // all have the same size
        if (size < 2) {
            return null
        }
        return MultipleComparisonAnalyzer(dm, "MODA Overall Value")
    }

    /**
     *  Returns a map holding multiple comparison results for each response.
     *  The response values represent the values from the value function transformation.
     *  Thus, the MCB comparison is done on the value domains.
     *
     *  The key to the returned map is the response name. The associated
     *  multiple comparison analyzer represents the comparison for that response
     *  across all the experiments/alternatives.  If insufficient data is
     *  available, then the response is not included.
     */
    fun mcbForResponseMODAValues(): Map<String, MultipleComparisonAnalyzer> {
        // the key is the response name
        val map = mutableMapOf<String, MultipleComparisonAnalyzer>()
        // mcb holds dataMap: Map<String, DoubleArray> where keys are experiment names
        // and arrays are replication data for a specific response
        // go through the responses and make an MCA for each one
        // outer key is alternative, so we get the value function value for the specific metric
        val eMap = replicatedResponseMODAValues()
        for (responseName in responseNames) {
            val dMap = mutableMapOf<String, DoubleArray>()
            for ((eName, rMap) in eMap) {
                val data = rMap[responseName]!!
                if (data.size >= 2) {
                    dMap[eName] = rMap[responseName]!!.toDoubleArray()
                }
            }
            val mcb = MultipleComparisonAnalyzer(dMap, responseName = responseName)
            mcb.label = responseName
            map[responseName] = mcb
        }
        return map
    }

    /**
     *  Extracts the response performance as MODA values (i.e. after value function processing)
     *  The returned map of maps has the outer key as the experiment/alternative name.
     *  The associated inner map holds the response values for each replication by response name (key).
     */
    fun replicatedResponseMODAValues(): Map<String, Map<String, List<Double>>> {
        // outer key = experiment name/alternative, inner key is response name, array is across replication data
        val map = mutableMapOf<String, MutableMap<String, MutableList<Double>>>()
        for ((_, moda) in myMODAByRepMap) {
            // get the response MODA values for this replication
            val altMap = moda.alternativeValuesByMetric()
            for ((eName, mMap) in altMap) {
                if (!map.containsKey(eName)) {
                    map[eName] = mutableMapOf()
                }
                // get the map
                val rMap = map[eName]!!
                for ((metric, value) in mMap) {
                    if (!rMap.containsKey(metric.name)) {
                        rMap[metric.name] = mutableListOf()
                    }
                    rMap[metric.name]!!.add(value)
                }
            }
        }
        return map
    }

    /**
     *  Computes and returns the raw replication values for the responses for
     *  each experiment/alternative. The returned map of maps has
     *  an outer key of the experiment/alternative name. The inner map
     *  has keys representing the named responses. The associated array
     *  contains the associated replication values of the response variable (within
     *  replication average, or ending counter value), a value for each replication.
     */
    fun replicationPerformance(): Map<String, Map<String, DoubleArray>> {
        val map = mutableMapOf<String, Map<String, DoubleArray>>()
        val r1 = myResponseData.groupBy { it.exp_name }
        for ((eName, dList) in r1) {
            val g1 = dList.groupBy { it.stat_name }
            val g2 = g1.mapValues { entry -> entry.value.map { it.rep_value ?: Double.NaN } }
            val g3 = g2.mapValues { it.value.toDoubleArray() }
            map[eName] = g3
        }
        return map
    }

    /**
     *  Computes the average performance for each alternative for each response
     */
    fun averagePerformance(): Map<String, Map<String, Double>> {
        val map = mutableMapOf<String, Map<String, Double>>()
        val r1 = myResponseData.groupBy { it.exp_name }
        for ((k, v) in r1) {
            val g1 = v.groupBy { it.stat_name }
            val g2 = g1.mapValues { entry -> entry.value.map { it.rep_value ?: Double.NaN } }
            val g3 = g2.mapValues { it.value.average() }
            map[k] = g3
        }
        return map
    }

    /**
     *  Returns a map holding multiple comparison results for each response.
     *  The key to the returned map is the response name. The associated
     *  multiple comparison analyzer represents the comparison for that response
     *  across all the experiments/alternatives.  If insufficient data is
     *  available, then the response is not included.
     */
    fun mcbForResponsePerformance(): Map<String, MultipleComparisonAnalyzer> {
        // the key is the response name
        val map = mutableMapOf<String, MultipleComparisonAnalyzer>()
        // mcb holds dataMap: Map<String, DoubleArray> where keys are experiment names
        // and arrays are replication data for a specific response
        val eMap = replicationPerformance()
        for (responseName in responseNames) {
            val dMap = mutableMapOf<String, DoubleArray>()
            for ((eName, rMap) in eMap) {
                val data = rMap[responseName]!!
                if (data.size >= 2) {
                    dMap[eName] = rMap[responseName]!!
                }
            }
            val mcb = MultipleComparisonAnalyzer(dMap, responseName = responseName)
            mcb.label = responseName
            map[responseName] = mcb
        }
        return map
    }

    /**
     *  Returns a MODA model based on the average performance of the responses across
     *  the replications.
     */
    fun averageMODA(): AdditiveMODAModel {
        val moda = AdditiveMODAModel(metricDefinitions, weights)
        val ap = averagePerformance()
        // convert altData to scores here
        moda.defineAlternatives(convertToScores(responseMetrics, ap), allowRescalingByMetrics = false)
        return moda
    }

    /**
     *  Converts the un-scored values to scored values. The [unScoredValues]
     *  map contains the outer key = experiment name, inner key response
     *  name with its value
     */
    private fun convertToScores(
        responseMetrics: Map<String, MetricIfc>,
        unScoredValues: Map<String, Map<String, Double>>
    ): Map<String, List<Score>> {
        val map = mutableMapOf<String, MutableList<Score>>()
        // key is exp/alt, list holds scores of each response
        for ((expName, responseMap) in unScoredValues) {
            if (!map.containsKey(expName)) {
                map[expName] = mutableListOf()
            }
            // get the list
            val scoreList = map[expName]!! // must be there, now we can add scores
            for ((responseName, value) in responseMap) {
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
    private fun processWithinRepViewData(): Map<Int, Map<String, Map<String, Double>>> {
        // get the data by replication
        val byRep = myResponseData.groupBy { it.rep_id }
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
                    responseMap[vData.stat_name] = vData.rep_value ?: Double.NaN
                }
                responsesByExperiment[eName] = responseMap
            }
            // we now have the alternative/exp score for each response for the current replication
            performanceByRep[rep] = responsesByExperiment
        }
        return performanceByRep
    }

    override fun toString(): String {
        return asString()
    }

    fun asString(
        includeMCBByResponse: Boolean = true,
        includeOverallRankFreq: Boolean = false,
        includeMCBByMODAResponse: Boolean = false,
        includeMODAByReplication: Boolean = false
    ): String {
        val sb = StringBuilder().apply {
            appendLine("Multi-Objective Analysis")
            appendLine("Alternatives:")
            for (alternative in alternativeNames) {
                appendLine("\t $alternative")
            }
            appendLine("Responses:")
            for (response in responseNames) {
                appendLine("\t $response")
            }
            appendLine()
            appendLine("Across Replication Analysis:")
            appendLine(averageMODA().toString())
            appendLine("MCB Analysis For Overall Value:")
            appendLine(mcbForOverallValue().toString())
            appendLine("-------------------------------------------")
            if (includeOverallRankFreq){
                appendLine("Rank Frequencies Based on Overall Value")
                val freqMap = overallRankFrequenciesByAlternative()
                for((alternative, freq) in freqMap){
                    appendLine("Rank Frequencies for $alternative")
                    appendLine(freq.toDataFrame())
                }
            }
            appendLine("-------------------------------------------")
            if (includeMCBByResponse) {
                appendLine("MCB Analysis By Response")
                appendLine("-------------------------------------------")
                val mcbMap = mcbForResponsePerformance()
                for ((_, mcb) in mcbMap) {
                    appendLine(mcb.toString())
                }
                appendLine("-------------------------------------------")
            }
            if (includeMCBByMODAResponse) {
                appendLine("MCB Analysis By MODA Response")
                appendLine("-------------------------------------------")
                val mcbMap = mcbForResponseMODAValues()
                for ((_, mcb) in mcbMap) {
                    appendLine(mcb.toString())
                }
                appendLine("-------------------------------------------")
            }
            if (includeMODAByReplication) {
                for ((r, moda) in myMODAByRepMap) {
                    appendLine("MODA Results for Replication $r")
                    appendLine(moda.toString())
                }
                appendLine("-------------------------------------------")
            }
        }
        return sb.toString()
    }

    /**
     *  Tabulate the rank frequencies for each alternative based on overall MODA value
     */
    fun overallRankFrequenciesByAlternative(): Map<String, IntegerFrequency> {
        val map = mutableMapOf<String, IntegerFrequency>()
        for (alternative in alternativeNames) {
            map[alternative] = IntegerFrequency(name = "$alternative:Overall Rank")
        }
        for ((_, moda) in myMODAByRepMap) {
            val rankMap = moda.alternativeRankedByMultiObjectiveValue()
            for((alternative, rank) in rankMap){
                map[alternative]!!.collect(rank)
            }
        }
        return map
    }

    fun print(
        includeMCBByResponse: Boolean = true,
        includeOverallRankFreq: Boolean = false,
        includeMCBByMODAResponse: Boolean = false,
        includeMODAByReplication: Boolean = false
    ) {
        write(PrintWriter(System.out), includeMCBByResponse, includeOverallRankFreq,
            includeMCBByMODAResponse, includeMODAByReplication)
    }

    fun write(
        out: PrintWriter,
        includeMCBByResponse: Boolean = true,
        includeOverallRankFreq: Boolean = true,
        includeMCBByMODAResponse: Boolean = true,
        includeMODAByReplication: Boolean = true
    ) {
        out.print(asString(includeMCBByResponse, includeOverallRankFreq,
            includeMCBByMODAResponse, includeMODAByReplication))
        out.flush()
    }

    fun write(
        fileName: String,
        includeMCBByResponse: Boolean = true,
        includeOverallRankFreq: Boolean = true,
        includeMCBByMODAResponse: Boolean = true,
        includeMODAByReplication: Boolean = true
    ) {
        write(KSL.createPrintWriter(fileName), includeMCBByResponse, includeOverallRankFreq,
            includeMCBByMODAResponse, includeMODAByReplication)
    }

    companion object {

        /**
         *  Sums the weights. Can be used to process weights
         */
        fun sumWeights(weights: Map<String, Double>): Double {
            var sum = 0.0
            for ((_, weight) in weights) {
                sum = sum + weight
            }
            return sum
        }

        /**
         *  Provides recommended domain intervals for each response based on all observed simulated data
         *  across all experiments.
         */
        fun recommendMetricDomainIntervals(
            responseNames: Set<String>,
            responseData: List<WithinRepViewData>
        ): Map<String, Interval> {
            val map = mutableMapOf<String, Interval>()
            for (responseName in responseNames) {
                val data = responseData.filter { it.stat_name == responseName }.map { it.rep_value ?: Double.NaN }
                map[responseName] = recommendDomainInterval(data)
            }
            return map
        }

        /**
         *  Determines a range (interval of values) that likely bound the supplied
         *  data. This function can be useful for providing a domain for metrics
         *  for a scaling process.
         */
        fun recommendDomainInterval(data: DoubleArray): Interval {
            val statWork = Statistic()
            statWork.collect(data)
            val tmp = if (statWork.count > 2) {
                val interval = PDFModeler.rangeEstimate(statWork.min, statWork.max, statWork.count.toInt())
                // round to integers
                Interval(floor(interval.lowerLimit), ceil(interval.upperLimit))
            } else {
                // this may not be a very good interval but with 1 or 2 data points what else can be done/
                // take the floor and ceil in case min = max
                Interval(floor(statWork.min), ceil(statWork.max))
            }
            // round to nearest integers
            return Interval(floor(tmp.lowerLimit), ceil(tmp.upperLimit))
        }

        /**
         *  Determines a range (interval of values) that likely bound the supplied
         *  data. This function can be useful for providing a domain for metrics
         *  for a scaling process.
         */
        fun recommendDomainInterval(data: Collection<Double>): Interval {
            return recommendDomainInterval(data.toDoubleArray())
        }
    }

}