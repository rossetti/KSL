package ksl.simopt.solvers.algorithms.bo

import ksl.simopt.problem.ProblemDefinition
import kotlin.math.exp
import kotlin.math.sqrt

/**
 *  A covariance (kernel) function for a Gaussian-process surrogate: it returns the prior covariance
 *  between the responses at two input points.
 */
fun interface KernelIfc {
    /** The prior covariance between the responses at [x1] and [x2]. */
    fun cov(x1: DoubleArray, x2: DoubleArray): Double
}

/**
 *  Base class for stationary kernels parameterized by an output scale ([signalVariance]) and a
 *  per-dimension length scale ([lengthScales], i.e. automatic relevance determination, ARD).
 *
 *  @param signalVariance the output scale (prior variance). Must be > 0.
 *  @param lengthScales the per-dimension length scales. Must be non-empty and all > 0.
 */
abstract class StationaryKernel(
    signalVariance: Double,
    lengthScales: DoubleArray
) : KernelIfc {

    init {
        require(signalVariance > 0.0) { "The signal variance must be > 0" }
        require(lengthScales.isNotEmpty()) { "The length scales must not be empty" }
        require(lengthScales.all { it > 0.0 }) { "All length scales must be > 0" }
    }

    var signalVariance: Double = signalVariance
        set(value) {
            require(value > 0.0) { "The signal variance must be > 0" }
            field = value
        }

    var lengthScales: DoubleArray = lengthScales.copyOf()
        set(value) {
            require(value.isNotEmpty()) { "The length scales must not be empty" }
            require(value.all { it > 0.0 }) { "All length scales must be > 0" }
            field = value.copyOf()
        }

    /** The input dimension (number of length scales). */
    val dimension: Int
        get() = lengthScales.size

    /** The sum of squared, length-scaled coordinate differences between [x1] and [x2]. */
    protected fun scaledSquaredDistance(x1: DoubleArray, x2: DoubleArray): Double {
        var s = 0.0
        for (d in x1.indices) {
            val diff = (x1[d] - x2[d]) / lengthScales[d]
            s += diff * diff
        }
        return s
    }
}

/**
 *  The squared-exponential (radial basis function) kernel with ARD length scales. This is the
 *  default kernel; it models very smooth response surfaces.
 *
 *  `cov(x1, x2) = signalVariance * exp(-0.5 * sum_d ((x1_d - x2_d)/lengthScale_d)^2)`
 */
class RBFKernel(
    signalVariance: Double,
    lengthScales: DoubleArray
) : StationaryKernel(signalVariance, lengthScales) {

    /**
     *  Creates an RBF kernel whose ARD length scales default to the problem's per-input ranges.
     *
     *  @param problemDefinition the problem providing per-input ranges
     *  @param signalVariance the output scale. Defaults to 1.0.
     */
    constructor(problemDefinition: ProblemDefinition, signalVariance: Double = 1.0) :
        this(signalVariance, problemDefinition.inputRanges.map { if (it > 0.0) it else 1.0 }.toDoubleArray())

    override fun cov(x1: DoubleArray, x2: DoubleArray): Double =
        signalVariance * exp(-0.5 * scaledSquaredDistance(x1, x2))

    override fun toString(): String =
        "RBFKernel(signalVariance=$signalVariance, lengthScales=${lengthScales.contentToString()})"
}

/**
 *  The Matern 5/2 kernel with ARD length scales. It is less smooth than the RBF kernel (twice
 *  mean-square differentiable) and is often a more realistic choice for response surfaces.
 *
 *  `cov = signalVariance * (1 + sqrt(5)*r + (5/3)*r^2) * exp(-sqrt(5)*r)`, where `r` is the
 *  length-scaled Euclidean distance.
 */
class Matern52Kernel(
    signalVariance: Double,
    lengthScales: DoubleArray
) : StationaryKernel(signalVariance, lengthScales) {

    @Suppress("unused")
    constructor(problemDefinition: ProblemDefinition, signalVariance: Double = 1.0) :
        this(signalVariance, problemDefinition.inputRanges.map { if (it > 0.0) it else 1.0 }.toDoubleArray())

    override fun cov(x1: DoubleArray, x2: DoubleArray): Double {
        val s = scaledSquaredDistance(x1, x2) // r^2
        val r = sqrt(s)
        val sqrt5r = sqrt(5.0) * r
        return signalVariance * (1.0 + sqrt5r + (5.0 / 3.0) * s) * exp(-sqrt5r)
    }

    override fun toString(): String =
        "Matern52Kernel(signalVariance=$signalVariance, lengthScales=${lengthScales.contentToString()})"
}
