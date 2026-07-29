/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.simopt.tutorial

import ksl.simopt.benchmark.FunctionMemberEvaluatorFactory
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.benchmark.ReferenceSolution
import ksl.simopt.benchmark.ReferenceType
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.problem.OptimizationType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.concurrent.MemberEvaluatorFactoryIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

/*
 * Tutorial Example 3 (Type 1: a GENUINE static Monte Carlo model) -- shared setup.
 *
 * The newsvendor buys q units at unit cost, sells each for a higher price up to
 * demand, and salvages leftovers. Demand is random (exponential), so profit is
 * random -- but here the randomness IS the model: there is no separate additive
 * "noise" term as in the Rosenbrock example. We MAXIMIZE expected profit, which
 * also exercises the framework's maximize path.
 *
 * The classic critical-fractile result gives a closed-form optimum, which we use
 * as the benchmark's known reference.
 */

const val NEWSVENDOR_ID: String = "newsvendor"
const val NEWSVENDOR_OBJECTIVE: String = "profit"
const val NEWSVENDOR_INPUT: String = "orderQuantity"

const val NEWSVENDOR_DEMAND_MEAN: Double = 50.0
const val NEWSVENDOR_PRICE: Double = 5.0
const val NEWSVENDOR_COST: Double = 1.0
const val NEWSVENDOR_SALVAGE: Double = 0.0
const val NEWSVENDOR_MAX_Q: Double = 200.0

/**
 *  One observation of newsvendor profit for a chosen order quantity q. Demand is
 *  drawn from an exponential random variable acquired ONCE at construction (on
 *  stream 1), honoring the "acquire all streams up front" contract.
 */
class NewsvendorResponse(
    streamProvider: RNStreamProviderIfc
) : ResponseFunctionIfc {

    private val demand: ExponentialRV =
        ExponentialRV(NEWSVENDOR_DEMAND_MEAN, streamNum = 1, streamProvider = streamProvider)

    override fun replication(inputs: Map<String, Double>): Map<String, Double> {
        val q: Double = inputs.getValue(NEWSVENDOR_INPUT)
        val d: Double = demand.value
        val sold: Double = minOf(d, q)
        val leftOver: Double = maxOf(q - d, 0.0)
        val profit: Double = NEWSVENDOR_PRICE * sold + NEWSVENDOR_SALVAGE * leftOver - NEWSVENDOR_COST * q
        return mapOf(NEWSVENDOR_OBJECTIVE to profit)
    }
}

/** Builds a fresh newsvendor response function against the given provider. */
class NewsvendorResponseBuilder : ResponseFunctionBuilderIfc {
    override fun build(streamProvider: RNStreamProviderIfc): ResponseFunctionIfc {
        return NewsvendorResponse(streamProvider)
    }
}

/** The closed-form expected profit at order quantity q. */
fun newsvendorExpectedProfit(orderQuantity: Double): Double {
    val expectedSales: Double = NEWSVENDOR_DEMAND_MEAN * (1.0 - exp(-orderQuantity / NEWSVENDOR_DEMAND_MEAN))
    return (NEWSVENDOR_PRICE - NEWSVENDOR_SALVAGE) * expectedSales -
        (NEWSVENDOR_COST - NEWSVENDOR_SALVAGE) * orderQuantity
}

/**
 *  The optimal integer order quantity. The continuous critical-fractile solution
 *  is q* = mean * ln((price - salvage) / (cost - salvage)); the best integer is one
 *  of its two neighbors, whichever gives the higher expected profit.
 */
fun newsvendorOptimalOrderQuantity(): Double {
    val continuous: Double =
        NEWSVENDOR_DEMAND_MEAN * ln((NEWSVENDOR_PRICE - NEWSVENDOR_SALVAGE) / (NEWSVENDOR_COST - NEWSVENDOR_SALVAGE))
    val low: Double = floor(continuous)
    val high: Double = ceil(continuous)
    return if (newsvendorExpectedProfit(high) > newsvendorExpectedProfit(low)) high else low
}

/**
 *  Describes the optimization problem: MAXIMIZE expected profit over one integer
 *  decision variable, the order quantity, on the range 0..200.
 */
fun makeNewsvendorProblem(): ProblemDefinition {
    val problem = ProblemDefinition(
        problemName = "Newsvendor",
        modelIdentifier = NEWSVENDOR_ID,
        objFnResponseName = NEWSVENDOR_OBJECTIVE,
        inputNames = listOf(NEWSVENDOR_INPUT),
        optimizationType = OptimizationType.MAXIMIZE
    )
    problem.inputVariable(name = NEWSVENDOR_INPUT, lowerBound = 0.0, upperBound = NEWSVENDOR_MAX_Q, granularity = 1.0)
    return problem
}

/** The known critical-fractile optimum, used as the benchmark reference. */
fun newsvendorReference(): ReferenceSolution {
    val qStar: Double = newsvendorOptimalOrderQuantity()
    return ReferenceSolution(
        inputs = mapOf(NEWSVENDOR_INPUT to qStar),
        objectiveValue = newsvendorExpectedProfit(qStar),
        type = ReferenceType.KNOWN_OPTIMUM
    )
}

/** The evaluator factory the benchmark uses for the newsvendor problem. */
fun makeNewsvendorEvaluatorFactory(problem: ProblemDefinition): MemberEvaluatorFactoryIfc {
    return FunctionMemberEvaluatorFactory(problem, NewsvendorResponseBuilder(), microRepSampleSize = 1)
}

/** Packages the newsvendor as a benchmark-ready case with its known optimum. */
fun makeNewsvendorProblemCase(): ProblemCase {
    return ProblemCase(
        name = "Newsvendor",
        problemDefinitionFactory = ::makeNewsvendorProblem,
        evaluatorFactoryProvider = ::makeNewsvendorEvaluatorFactory,
        referenceSolution = newsvendorReference(),
        tags = mapOf("family" to "newsvendor", "dimension" to "1", "constrained" to "false")
    )
}
