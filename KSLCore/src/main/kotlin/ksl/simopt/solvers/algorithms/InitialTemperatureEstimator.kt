/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2025  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.simopt.solvers.algorithms

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.utilities.random.rng.RNStreamIfc
import kotlin.math.ln

/**
 * Shared machinery for estimating a simulated annealing initial temperature from an
 * unbiased random walk over the objective-function landscape.
 *
 * The key structural fact exploited here: the geometry of a random walk never depends on
 * the evaluated objective values. Each step is a function of the previous point's inputs
 * and the random number stream only, so the entire walk path can be generated up front
 * and its points evaluated with a single multi-point evaluation request. When the
 * evaluator is backed by a parallel simulation oracle, the points of that one request are
 * evaluated concurrently; the resulting temperature estimate is the same either way
 * because each point's random number streams are positioned absolutely by the provider.
 *
 * Used by `SimulatedAnnealing.calibrateTemperature` (which supplies the solver's
 * neighbor-generation function so custom neighborhood generators are honored) and by the
 * static `SimulatedAnnealing.estimateInitialTemperature` companion function (which uses
 * the default input-randomization neighbor function).
 */
internal object InitialTemperatureEstimator {

    val logger: KLogger = KotlinLogging.logger {}

    /**
     * Pre-generates the full random-walk path beginning at [start]. The returned list has
     * size steps + 1, with element 0 equal to [start] and each subsequent element produced
     * by applying [neighborFn] to its predecessor.
     *
     * @param start the walk's starting point
     * @param steps the number of walk steps to take; must be positive
     * @param rnStream the stream used for neighbor generation
     * @param neighborFn produces the next point from the current point and the stream
     */
    fun generateWalkPath(
        start: InputMap,
        steps: Int,
        rnStream: RNStreamIfc,
        neighborFn: (InputMap, RNStreamIfc) -> InputMap
    ): List<InputMap> {
        require(steps > 0) { "The number of walk steps must be positive" }
        val chain = ArrayList<InputMap>(steps + 1)
        chain.add(start)
        var current = start
        for (i in 0 until steps) {
            current = neighborFn(current, rnStream)
            chain.add(current)
        }
        return chain
    }

    /**
     * Computes the estimated initial temperature from the walk chain and the evaluated
     * solutions of its points: the average positive (worsening) cost difference between
     * consecutive points, scaled by the target acceptance probability.
     *
     * Points with a missing solution, an invalid solution (a failed evaluation produces
     * the problem's bad solution, which is marked invalid and carries an enormous
     * objective value), or a non-finite penalized objective value are skipped with a
     * warning, and the difference chain restarts after the gap, so a single failed point
     * cannot blow up the temperature estimate.
     *
     * @param chain the walk path, element 0 being the starting point
     * @param solutionsByInput the evaluated solution for each point of the chain
     * @param targetAcceptanceProbability desired initial probability of accepting a worse
     * solution; must be strictly between 0 and 1
     * @return the estimated temperature, or null when the walk produced no usable
     * worsening moves (the caller applies its fallback temperature)
     */
    fun estimateFromChain(
        chain: List<InputMap>,
        solutionsByInput: Map<InputMap, Solution>,
        targetAcceptanceProbability: Double
    ): Double? {
        require(targetAcceptanceProbability > 0.0 && targetAcceptanceProbability < 1.0) {
            "Target probability must be strictly between 0 and 1"
        }
        var totalWorseningCost = 0.0
        var worseningMovesCount = 0
        var previousValue = Double.NaN
        var havePrevious = false
        for (point in chain) {
            val solution = solutionsByInput[point]
            if (solution == null) {
                logger.warn { "Temperature calibration: no solution for walk point; skipping. Point: ${point.inputValues.joinToString()}" }
                havePrevious = false
                continue
            }
            if (!solution.isValid) {
                logger.warn { "Temperature calibration: invalid (failed) solution at walk point; skipping. Point: ${point.inputValues.joinToString()}" }
                havePrevious = false
                continue
            }
            val value = solution.penalizedObjFncValue
            if (!value.isFinite()) {
                logger.warn { "Temperature calibration: non-finite objective at walk point; skipping. Point: ${point.inputValues.joinToString()}" }
                havePrevious = false
                continue
            }
            if (havePrevious) {
                val costDiff = value - previousValue
                if (costDiff > 0.0) {
                    totalWorseningCost += costDiff
                    worseningMovesCount++
                }
            }
            previousValue = value
            havePrevious = true
        }
        if (worseningMovesCount == 0) {
            return null
        }
        val averageWorseningCost = totalWorseningCost / worseningMovesCount
        return -averageWorseningCost / ln(targetAcceptanceProbability)
    }

    /**
     * Builds a lookup from walk point to its evaluated solution. The evaluation results
     * are keyed by the request objects; each request's inputs property is the InputMap
     * instance that was submitted, so the keys line up with the chain's points. Falls
     * back to the solution's own input map when the request key is not an InputMap.
     *
     * @param solutions the evaluation results, keyed by the submitted requests
     * @return the solutions keyed by their input points
     */
    fun solutionsByInput(solutions: Map<ModelInputs, Solution>): MutableMap<InputMap, Solution> {
        val byInput = HashMap<InputMap, Solution>(solutions.size)
        for ((modelInputs, solution) in solutions) {
            val key = modelInputs.inputs
            if (key is InputMap) {
                byInput[key] = solution
            } else {
                byInput[solution.inputMap] = solution
            }
        }
        return byInput
    }
}
