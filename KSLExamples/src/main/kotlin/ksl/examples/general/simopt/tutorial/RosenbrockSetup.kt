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
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.concurrent.MemberEvaluatorFactoryIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.NormalRV

/*
 * Tutorial Example 1 (Type 1: a noisy mathematical function) -- shared setup.
 *
 * These declarations are used by BOTH RosenbrockOptimizationExample.kt (a single
 * optimization run) and RosenbrockBenchmarkExample.kt (a small benchmark study).
 *
 * Style note: this tutorial deliberately uses ordinary NAMED classes and NAMED
 * functions instead of Kotlin lambdas, so every moving part has a name you can
 * find, read, and reuse.
 */

/**
 *  The identifier that ties the problem, the response function, and the oracle
 *  together. For a Type 1 problem it is just a label that we choose; it plays the
 *  same role that a simulation model's name plays for a DEDS problem.
 */
const val ROSENBROCK_ID: String = "noisyRosenbrock2D"

/** The name of the objective response our function reports. */
const val ROSENBROCK_OBJECTIVE: String = "objFn"

/**
 *  One noisy observation of the 2-D Rosenbrock function.
 *
 *  The true (noise-free) function is
 *
 *      f(x1, x2) = 100 * (x2 - x1*x1)^2 + (1 - x1)^2
 *
 *  which has its minimum value 0 at the point (1, 1). We never get to see f
 *  directly: each call returns f plus a draw of Gaussian noise, so the optimizer
 *  must cope with randomness, exactly as it would with a real simulation.
 *
 *  The noise random variable is created ONCE, in the constructor, on its own
 *  random-number stream (stream 1). This is the reproducibility contract: acquire
 *  every source of randomness up front so the framework can position the streams
 *  for common random numbers and repeatable experiments.
 */
class NoisyRosenbrockResponse(
    streamProvider: RNStreamProviderIfc
) : ResponseFunctionIfc {

    // mean 0, variance 1 (so the standard deviation is 1), on stream number 1.
    private val noise: NormalRV = NormalRV(
        mean = 0.0,
        variance = 1.0,
        streamNum = 1,
        streamProvider = streamProvider
    )

    override fun replication(inputs: Map<String, Double>): Map<String, Double> {
        val x1: Double = inputs.getValue("x1")
        val x2: Double = inputs.getValue("x2")
        val a: Double = x2 - x1 * x1
        val b: Double = 1.0 - x1
        val trueValue: Double = 100.0 * a * a + b * b
        val observed: Double = trueValue + noise.value
        return mapOf(ROSENBROCK_OBJECTIVE to observed)
    }
}

/**
 *  A builder that hands the framework a FRESH response function whenever it needs
 *  one (for example, one per worker in a benchmark). Each fresh function is built
 *  against the provider it is given, honoring the "acquire all streams at
 *  construction" contract.
 */
class NoisyRosenbrockResponseBuilder : ResponseFunctionBuilderIfc {
    override fun build(streamProvider: RNStreamProviderIfc): ResponseFunctionIfc {
        return NoisyRosenbrockResponse(streamProvider)
    }
}

/**
 *  Builds the problem definition: what we are optimizing, over which variables,
 *  and within what ranges. Both variables live on the integer lattice
 *  (granularity 1.0), which also lets integer-ordered solvers such as R-SPLINE
 *  participate.
 */
fun makeRosenbrockProblem(): ProblemDefinition {
    val problem = ProblemDefinition(
        problemName = "Rosenbrock2D",
        modelIdentifier = ROSENBROCK_ID,
        objFnResponseName = ROSENBROCK_OBJECTIVE,
        inputNames = listOf("x1", "x2")
    )
    problem.inputVariable(name = "x1", lowerBound = -5.0, upperBound = 10.0, granularity = 1.0)
    problem.inputVariable(name = "x2", lowerBound = -5.0, upperBound = 10.0, granularity = 1.0)
    return problem
}

/**
 *  The known optimum, used by the benchmark to measure how close each run got.
 *  The minimum of the true Rosenbrock function is at (1, 1) with value 0.
 */
fun rosenbrockReference(): ReferenceSolution {
    return ReferenceSolution(
        inputs = mapOf("x1" to 1.0, "x2" to 1.0),
        objectiveValue = 0.0,
        type = ReferenceType.KNOWN_OPTIMUM
    )
}

/**
 *  The evaluator factory the benchmark uses to give each concurrent run its own
 *  private oracle over the response function. It is written as a NAMED function so
 *  that we can hand the benchmark its name (see makeRosenbrockProblemCase) rather
 *  than an anonymous lambda.
 */
fun makeRosenbrockEvaluatorFactory(problem: ProblemDefinition): MemberEvaluatorFactoryIfc {
    return FunctionMemberEvaluatorFactory(
        problem,
        NoisyRosenbrockResponseBuilder(),
        microRepSampleSize = 1 // one raw evaluation per observation
    )
}

/**
 *  Packages the problem as a benchmark-ready case. The two "factory" arguments are
 *  recipes the harness calls whenever it needs a fresh problem or a fresh
 *  evaluator; we pass the NAMES of the two functions above. The `::` syntax reads
 *  as "the function called ...", so `::makeRosenbrockProblem` means "call
 *  makeRosenbrockProblem when you need a problem."
 */
fun makeRosenbrockProblemCase(): ProblemCase {
    return ProblemCase(
        name = "Rosenbrock2D",
        problemDefinitionFactory = ::makeRosenbrockProblem,
        evaluatorFactoryProvider = ::makeRosenbrockEvaluatorFactory,
        referenceSolution = rosenbrockReference(),
        tags = mapOf("family" to "noisyRosenbrock", "dimension" to "2")
    )
}
