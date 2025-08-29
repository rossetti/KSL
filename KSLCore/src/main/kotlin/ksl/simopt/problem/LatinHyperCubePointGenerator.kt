package ksl.simopt.problem

import ksl.simopt.problem.ProblemDefinition.Companion.defaultMaximumFeasibleSamplingIterations
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.LatinHyperCubeSampler

/**
 *  Creates a Latin hyper-cube sampler with each dimension divided into the specified number of points.
 *  The hyper-cube is formed from the specified intervals of the problem. The sampling will ensure
 *  input feasible starting points.
 *
 *  @param pointsPerDimension the number of divisions of the dimensions
 *  @param problemDefinition the problem to sample from
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 */
class LatinHyperCubePointGenerator @JvmOverloads constructor(
    val pointsPerDimension: Int,
    val problemDefinition: ProblemDefinition,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
) : StartingPointIfc {

    private val myGenerator =
        LatinHyperCubeSampler(pointsPerDimension, problemDefinition.inputIntervals, streamNum, streamProvider)

    /**
     * The maximum number of iterations when sampling for an input feasible point
     */
    var maxFeasibleSamplingIterations: Int = defaultMaximumFeasibleSamplingIterations
        set(value) {
            require(value > 0) { "The maximum number of samples is $value, must be > 0" }
            field = value
        }

    override fun startingPoint(problemDefinition: ProblemDefinition): InputMap {
        var count = 0
        var point: DoubleArray
        do {
            count++
            check(count <= maxFeasibleSamplingIterations) { "The number of iterations exceeded the limit $maxFeasibleSamplingIterations when sampling for an input feasible point" }
            // generate the point
            point = myGenerator.sample()
        } while (!problemDefinition.isInputFeasible(point))
        return problemDefinition.toInputMap(point)
    }

    /**
     *  Generates a set of input feasible points using Latin hyper cube sampling.
     */
    fun generateInputFeasiblePoints(numPoints: Int): Set<InputMap> {
        require(numPoints > 0) { "The number of points must be > 0" }
        val set = mutableSetOf<InputMap>()
        for(i in 1..numPoints) {
            set.add(startingPoint(problemDefinition))
        }
        return set
    }


}