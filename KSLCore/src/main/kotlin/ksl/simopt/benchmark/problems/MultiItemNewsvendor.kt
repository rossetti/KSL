package ksl.simopt.benchmark.problems

import ksl.simopt.benchmark.FunctionMemberEvaluatorFactory
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.benchmark.ReferenceSolution
import ksl.simopt.benchmark.ReferenceType
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.problem.OptimizationType
import ksl.simopt.problem.ProblemDefinition
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 *  The multi-item newsvendor with a budget constraint: a genuine static Monte Carlo
 *  problem whose deterministic linear constraint has a known feasible boundary — the
 *  measurement instrument for how solver cases handle input feasibility. Choose an
 *  integer order quantity per item; item demands are independent exponentials; each
 *  replication observes the total profit across items. The budget constrains the TOTAL
 *  units ordered (unit cost is uniform), expressed as a deterministic linear constraint
 *  in the problem definition, and the defaults make it bind (the unconstrained item
 *  optima exceed the budget).
 *
 *  The reference optimum is computed at construction by greedy marginal allocation:
 *  expected item profit is separable and concave in each integer quantity, and every
 *  unit costs the same, so repeatedly granting the next unit to the item with the
 *  largest positive marginal expected profit is exactly optimal for the total-units
 *  budget. The reference type is KNOWN-OPTIMUM.
 *
 *  @param demandMeans the mean of each item's exponential demand; all positive
 *  @param unitPrices the selling price per unit of each item; same size as demandMeans
 *  @param unitBudget the total units that may be ordered across items; at least 1
 *  @param unitCost the (uniform) purchase cost per unit; must be below every price and
 *  above the salvage value
 *  @param salvageValue the value recovered per unsold unit
 *  @param maxOrderQuantity the per-item upper bound of the order-quantity range
 */
class MultiItemNewsvendor(
    val demandMeans: DoubleArray = doubleArrayOf(30.0, 50.0, 70.0),
    val unitPrices: DoubleArray = doubleArrayOf(6.0, 4.0, 3.0),
    val unitBudget: Int = 120,
    val unitCost: Double = 1.0,
    val salvageValue: Double = 0.0,
    val maxOrderQuantity: Double = 200.0
) {

    init {
        require(demandMeans.isNotEmpty()) { "At least one item is required" }
        require(demandMeans.size == unitPrices.size) {
            "demandMeans and unitPrices must have the same size"
        }
        require(demandMeans.all { it > 0.0 }) { "All demand means must be > 0" }
        require(salvageValue >= 0.0) { "The salvage value must be >= 0" }
        require(unitCost > salvageValue) { "The unit cost must exceed the salvage value" }
        require(unitPrices.all { it > unitCost }) { "Every unit price must exceed the unit cost" }
        require(unitBudget >= 1) { "The unit budget must be >= 1" }
        require(maxOrderQuantity >= 1.0) { "The maximum order quantity must be >= 1" }
    }

    /** The number of items. */
    val numItems: Int = demandMeans.size

    /** The problem's name. */
    val problemName: String = "multiItemNewsvendor_k$numItems"

    /** The name of the (maximized) total-profit response. */
    val objectiveResponseName: String = "profit"

    /** The decision variable names, q1..qk. */
    val inputNames: List<String> = (1..numItems).map { "q$it" }

    /** The expected profit of one item at an integer order quantity, from the closed form. */
    fun expectedItemProfit(item: Int, orderQuantity: Double): Double {
        val mean = demandMeans[item]
        val expectedSales = mean * (1.0 - exp(-orderQuantity / mean))
        return (unitPrices[item] - salvageValue) * expectedSales - (unitCost - salvageValue) * orderQuantity
    }

    /** The expected total profit at the supplied per-item order quantities. */
    fun expectedProfit(orderQuantities: DoubleArray): Double {
        require(orderQuantities.size == numItems) { "Expected $numItems order quantities" }
        var total = 0.0
        for (item in 0 until numItems) {
            total += expectedItemProfit(item, orderQuantities[item])
        }
        return total
    }

    /**
     *  The optimal integer allocation under the unit budget, by greedy marginal
     *  allocation (exact here: separable concave objective, uniform unit cost).
     */
    val optimalOrderQuantities: DoubleArray by lazy {
        val quantities = DoubleArray(numItems)
        var remaining = unitBudget
        while (remaining > 0) {
            var bestItem = -1
            var bestMarginal = 0.0
            for (item in 0 until numItems) {
                if (quantities[item] >= maxOrderQuantity) continue
                val marginal = expectedItemProfit(item, quantities[item] + 1.0) -
                        expectedItemProfit(item, quantities[item])
                if (marginal > bestMarginal) {
                    bestMarginal = marginal
                    bestItem = item
                }
            }
            if (bestItem < 0) break // no positive marginal remains; budget need not be exhausted
            quantities[bestItem] += 1.0
            remaining--
        }
        quantities
    }

    /** The response function: one total-profit observation per replication, item
     *  demands drawn (in item order) from the supplied stream. */
    fun responseFunction(): ResponseFunctionIfc {
        return ResponseFunctionIfc { inputs, stream ->
            var profit = 0.0
            for (item in 0 until numItems) {
                val orderQuantity = inputs.getValue(inputNames[item])
                val demand = stream.rExponential(demandMeans[item])
                profit += unitPrices[item] * min(demand, orderQuantity) +
                        salvageValue * max(orderQuantity - demand, 0.0) -
                        unitCost * orderQuantity
            }
            mapOf(objectiveResponseName to profit)
        }
    }

    /** A fresh problem definition: integer-lattice inputs, maximizing profit, with the
     *  deterministic budget constraint (the sum of quantities at most the unit budget). */
    fun problemDefinition(): ProblemDefinition {
        val pd = ProblemDefinition(
            problemName = problemName,
            modelIdentifier = problemName,
            objFnResponseName = objectiveResponseName,
            inputNames = inputNames,
            optimizationType = OptimizationType.MAXIMIZE
        )
        for (inputName in inputNames) {
            pd.inputVariable(inputName, 0.0, maxOrderQuantity, granularity = 1.0)
        }
        // KSL linear constraints are strict inequalities; on the integer lattice,
        // sum < budget + 0.5 is exactly sum <= budget, keeping the boundary feasible
        pd.linearConstraint(
            equation = inputNames.associateWith { 1.0 },
            rhsValue = unitBudget + 0.5
        )
        return pd
    }

    /** The known optimum from greedy marginal allocation. */
    fun referenceSolution(): ReferenceSolution {
        return ReferenceSolution(
            inputs = inputNames.zip(optimalOrderQuantities.toList()).toMap(),
            objectiveValue = expectedProfit(optimalOrderQuantities),
            type = ReferenceType.KNOWN_OPTIMUM
        )
    }

    /** The benchmark-ready problem case. */
    fun problemCase(): ProblemCase {
        return ProblemCase(
            name = problemName,
            problemDefinitionFactory = { problemDefinition() },
            evaluatorFactoryProvider = { pd -> FunctionMemberEvaluatorFactory(pd, responseFunction()) },
            referenceSolution = referenceSolution(),
            tags = mapOf(
                "family" to "newsvendor",
                "dimension" to numItems.toString(),
                "noiseLevel" to "MODEL",
                "constrained" to "true"
            )
        )
    }
}
