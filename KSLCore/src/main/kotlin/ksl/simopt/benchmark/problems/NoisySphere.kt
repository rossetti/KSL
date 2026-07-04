package ksl.simopt.benchmark.problems

/**
 *  The unimodal sanity check of the synthetic ladder: the sphere function, shifted so
 *  its optimum sits at the standard off-center integer lattice point, observed through
 *  additive Gaussian noise. True objective sum of (x_i - s_i)^2 over the box from -10
 *  to 10 per coordinate; the optimum value is 0. Any reasonable algorithm should
 *  descend this reliably at LOW noise — a solver case that cannot is misconfigured.
 *
 *  @param dimension the number of decision variables
 *  @param noiseLevel the additive Gaussian noise level
 */
class NoisySphere(
    dimension: Int,
    noiseLevel: NoiseLevel
) : SyntheticFunctionProblem(dimension, noiseLevel) {

    override val familyName: String = "noisySphere"
    override val lowerBound: Double = -10.0
    override val upperBound: Double = 10.0
    override val optimum: DoubleArray = standardShift(dimension)

    override fun trueObjective(point: DoubleArray): Double {
        var sum = 0.0
        for (i in point.indices) {
            val z = point[i] - optimum[i]
            sum += z * z
        }
        return sum
    }
}
