package ksl.simopt.benchmark.problems

/**
 *  The ill-conditioned valley of the synthetic ladder: the Rosenbrock function over the
 *  box from -5 to 10 per coordinate, observed through additive Gaussian noise. The
 *  optimum is the all-ones point (already an integer lattice point) with value 0; the
 *  long, curved, nearly flat valley floor punishes algorithms that cannot follow a
 *  narrow improving direction under noise.
 *
 *  @param dimension the number of decision variables; must be at least 2
 *  @param noiseLevel the additive Gaussian noise level
 */
class NoisyRosenbrock(
    dimension: Int,
    noiseLevel: NoiseLevel
) : SyntheticFunctionProblem(dimension, noiseLevel) {

    init {
        require(dimension >= 2) { "The Rosenbrock function requires dimension >= 2" }
    }

    override val familyName: String = "noisyRosenbrock"
    override val lowerBound: Double = -5.0
    override val upperBound: Double = 10.0
    override val optimum: DoubleArray = DoubleArray(dimension) { 1.0 }

    override fun trueObjective(point: DoubleArray): Double {
        var sum = 0.0
        for (i in 0 until point.size - 1) {
            val a = point[i + 1] - point[i] * point[i]
            val b = 1.0 - point[i]
            sum += 100.0 * a * a + b * b
        }
        return sum
    }
}
