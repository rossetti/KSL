package ksl.examples.general.simopt.problems

import ksl.simopt.benchmark.FunctionMemberEvaluatorFactory
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.benchmark.ReferenceSolution
import ksl.simopt.benchmark.ReferenceType
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.problem.OptimizationType
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 *  The single-item newsvendor: a genuine static Monte Carlo problem (no additive noise
 *  layer — the randomness IS the model) with a known critical-fractile optimum. Choose
 *  an integer order quantity q; demand is exponential with the given mean; each
 *  replication observes the profit
 *  price times min(demand, q) plus salvage times max(q - demand, 0) minus cost times q.
 *  The problem MAXIMIZES expected profit, exercising the harness's orientation path.
 *
 *  The expected profit has the closed form
 *  (price - salvage) times mean times (1 - exp(-q / mean)) minus (cost - salvage) times q,
 *  which is concave in q, so the integer optimum is the better of the two integer
 *  neighbors of the continuous critical-fractile solution
 *  q-star = mean times ln((price - salvage) / (cost - salvage)). The reference solution
 *  is computed from this closed form at construction, not hardcoded.
 *
 *  @param demandMean the mean of the exponential demand; must be positive
 *  @param unitPrice the selling price per unit sold
 *  @param unitCost the purchase cost per unit ordered
 *  @param salvageValue the value recovered per unsold unit
 *  @param maxOrderQuantity the upper bound of the order-quantity range
 */
class Newsvendor(
    val demandMean: Double = 50.0,
    val unitPrice: Double = 5.0,
    val unitCost: Double = 1.0,
    val salvageValue: Double = 0.0,
    val maxOrderQuantity: Double = 200.0
) {

    init {
        require(demandMean > 0.0) { "The demand mean must be > 0" }
        require(salvageValue >= 0.0) { "The salvage value must be >= 0" }
        require(unitCost > salvageValue) { "The unit cost must exceed the salvage value" }
        require(unitPrice > unitCost) { "The unit price must exceed the unit cost" }
        require(maxOrderQuantity >= 1.0) { "The maximum order quantity must be >= 1" }
    }

    /** The problem's name. */
    val problemName: String = "newsvendor"

    /** The name of the (maximized) profit response. */
    val objectiveResponseName: String = "profit"

    /** The name of the single decision variable. */
    val inputName: String = "orderQuantity"

    /** The expected profit at order quantity q, from the closed form. */
    fun expectedProfit(orderQuantity: Double): Double {
        val expectedSales = demandMean * (1.0 - exp(-orderQuantity / demandMean))
        return (unitPrice - salvageValue) * expectedSales - (unitCost - salvageValue) * orderQuantity
    }

    /** The optimal integer order quantity: the best lattice point in range. */
    val optimalOrderQuantity: Double by lazy {
        val continuous = demandMean * kotlin.math.ln((unitPrice - salvageValue) / (unitCost - salvageValue))
        val candidates = listOf(
            kotlin.math.floor(continuous), kotlin.math.ceil(continuous)
        ).map { it.coerceIn(0.0, maxOrderQuantity) }
        candidates.maxBy { expectedProfit(it) }
    }

    /** The response function: one profit observation per replication, demand drawn
     *  from a demand random variable acquired at construction (stream 1). */
    fun responseFunctionBuilder(): ResponseFunctionBuilderIfc {
        return ResponseFunctionBuilderIfc { streamProvider ->
            val demandRV = ExponentialRV(demandMean, streamNum = 1, streamProvider = streamProvider)
            ResponseFunctionIfc { inputs ->
                val orderQuantity = inputs.getValue(inputName)
                val demand = demandRV.value
                val profit = unitPrice * min(demand, orderQuantity) +
                        salvageValue * max(orderQuantity - demand, 0.0) -
                        unitCost * orderQuantity
                mapOf(objectiveResponseName to profit)
            }
        }
    }

    /** A fresh problem definition: one integer-lattice input, maximizing profit. */
    fun problemDefinition(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = problemName,
            modelIdentifier = problemName,
            objFnResponseName = objectiveResponseName,
            inputNames = listOf(inputName),
            optimizationType = OptimizationType.MAXIMIZE
        )
        pd.inputVariable(inputName, 0.0, maxOrderQuantity, granularity = 1.0)
        return pd
    }

    /** The known critical-fractile optimum, computed from the closed form. */
    fun referenceSolution(): ReferenceSolution {
        return ReferenceSolution(
            inputs = mapOf(inputName to optimalOrderQuantity),
            objectiveValue = expectedProfit(optimalOrderQuantity),
            type = ReferenceType.KNOWN_OPTIMUM
        )
    }

    /**
     *  The benchmark-ready problem case.
     *
     *  @param microRepSampleSize the number of demand realizations averaged into one
     *  replication (observation); the default of 1 makes an observation a single
     *  period's profit — see `ResponseFunctionOracle`
     */
    @JvmOverloads
    fun problemCase(microRepSampleSize: Int = 1): ProblemCase {
        return ProblemCase(
            name = problemName,
            problemDefinitionFactory = { problemDefinition() },
            evaluatorFactoryProvider = { pd ->
                FunctionMemberEvaluatorFactory(pd, responseFunctionBuilder(), microRepSampleSize)
            },
            referenceSolution = referenceSolution(),
            tags = mapOf(
                "family" to "newsvendor",
                "dimension" to "1",
                "noiseLevel" to "MODEL",
                "constrained" to "false"
            )
        )
    }
}
