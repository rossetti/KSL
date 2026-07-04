package ksl.examples.general.simopt.problems

import kotlin.math.PI
import kotlin.math.cos

/**
 *  The regular multimodal testbed of the synthetic ladder: the Rastrigin function,
 *  shifted so its global optimum sits at the standard off-center integer lattice
 *  point, observed through additive Gaussian noise. True objective
 *  10 d plus the sum of (z_i^2 - 10 cos(2 pi z_i)) with z = x - s, over the box from
 *  -10 to 10 per coordinate; the global optimum value is 0 amid a regular grid of
 *  local minima — the natural testbed for restart strategies and portfolios.
 *
 *  @param dimension the number of decision variables
 *  @param noiseLevel the additive Gaussian noise level
 */
class NoisyRastrigin(
    dimension: Int,
    noiseLevel: NoiseLevel
) : SyntheticFunctionProblem(dimension, noiseLevel) {

    override val familyName: String = "noisyRastrigin"
    override val lowerBound: Double = -10.0
    override val upperBound: Double = 10.0
    override val optimum: DoubleArray = standardShift(dimension)

    override fun trueObjective(point: DoubleArray): Double {
        var sum = 10.0 * point.size
        for (i in point.indices) {
            val z = point[i] - optimum[i]
            sum += z * z - 10.0 * cos(2.0 * PI * z)
        }
        return sum
    }
}
