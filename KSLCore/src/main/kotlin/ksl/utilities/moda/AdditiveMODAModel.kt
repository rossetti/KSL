package ksl.utilities.moda

/**
 *  Represents a multi-objective decision analysis (MODA) model that uses
 *  an additive model for the attribute valuation.  The supplied weights
 *  must correspond to weights within the model.
 *
 *  @param metricDefinitions the definition for each metric and the value function
 *  to apply for the metric
 *  @param weights the weights for each metric, by default they will be equal
 *
 */
class AdditiveMODAModel(
    metricDefinitions: Map<MetricIfc, ValueFunctionIfc>,
    weights: Map<MetricIfc, Double> = makeEqualWeights(metricDefinitions.keys)
) : MODAModel(metricDefinitions) {

    private val myWeights = mutableMapOf<MetricIfc, Double>() //TODO consider Map<String, Double> with key = metric.name

    init {
        assignWeights(weights)
    }

    /**
     *  Constructs a default additive MODA model. The supplied [names] are
     *  used to create default metrics using linear value functions with equal weighting.
     */
    constructor(names: Set<String>) : this(
        assignLinearValueFunctions(createDefaultMetrics(names)))

    /**
     *  Changes or assigns the weights for the additive model. The required number
     *  of metrics must be the number of metrics defined for the model. And,
     *  the metrics must all be in the model. The weights are normalized to ensure
     *  that they sum to 1.0 and are individually between [0, 1].
     *  The total weight supplied must be greater than 0.0. After assignment
     *  the total weight should be equal to 1.0.
     */
    fun assignWeights(weights: Map<MetricIfc, Double>) {
        require(weights.keys.size == metricFunctionMap.keys.size){"The supplied number of metrics does not match the required number of metrics!"}
        for (metric in weights.keys) {
            require(metrics.contains(metric)) { "The supplied weight's metric is not in the model" }
        }
        val totalWeight = sumWeights(weights)
        require(totalWeight > 0.0) { "The total weight must be > 0.0" }
        for ((metric, weight) in weights) {
            myWeights[metric] = weight / totalWeight
        }
    }

    val weights: Map<MetricIfc, Double>
        get() = myWeights

    override fun multiObjectiveValue(alternative: String): Double {
        require(myAlternatives.contains(alternative)) { "The supplied alternative = $alternative is not part of the model." }
        val values = valuesByAlternative(alternative)
        var sum = 0.0
        for ((metric, value) in values) {
            val w = weights[metric]!!
            sum = sum + w * value
        }
        return sum
    }

    override fun toString(): String {
        val sb = StringBuilder().apply {
            appendLine("MODA Results")
            appendLine("-------------------------------------------")
            appendLine("Metrics")
            for((metric, weight) in weights){
                append("\t ${metric.name}")
                append("\t domain = ${metric.domain}")
                append("\t direction = ${metric.direction}")
                append("\t weight = $weight")
                if (metric.unitsOfMeasure != null){
                    append("\t units = ${metric.unitsOfMeasure}")
                }
                appendLine()
            }
            appendLine()
            appendLine("-------------------------------------------")
            appendLine("Alternative Scores:")
            appendLine(alternativeScoresAsDataFrame())
            appendLine("Alternative Values:")
            appendLine(alternativeValuesAsDataFrame())
            appendLine("Alternative Ranks:")
            appendLine(alternativeRanksAsDataFrame())
        }
        return sb.toString()
    }

    companion object {

        /**
         *  Sums the weights. Can be used to process weights
         */
        fun sumWeights(weights: Map<MetricIfc, Double>): Double {
            var sum = 0.0
            for ((_, weight) in weights) {
                sum = sum + weight
            }
            return sum
        }

        /**
         *  Mutates the supplied array such that the elements sum to 1.0
         */
        fun normalizeWeights(weights: DoubleArray){
            require(weights.isNotEmpty()) {"The weights array was empty"}
            val sum = weights.sum()
            require(sum > 0.0) {"The sum of the weights must be > 0.0"}
            var ps = 0.0
            for(i in 0..< weights.size - 1){
                weights[i] = weights[i]/sum
                ps = ps + weights[i]
            }
            weights[weights.size - 1] = 1.0 - ps
        }
    }

}