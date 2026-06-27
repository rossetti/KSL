package ksl.simopt.solvers.algorithms.bo

import ksl.simopt.problem.ProblemDefinition
import org.hipparchus.linear.CholeskyDecomposition
import org.hipparchus.linear.MatrixUtils
import org.hipparchus.linear.RealMatrix
import kotlin.math.ln

/**
 *  A Gaussian-process (GP) regression surrogate with per-point observation noise, suitable for the
 *  stochastic simulation setting: each observed point carries its own noise variance (the variance
 *  of the estimated mean). The GP uses a constant prior mean (the data mean by default) and a
 *  [StationaryKernel].
 *
 *  The fit forms the covariance matrix `K + diag(noiseVar) + jitter*I`, factorizes it with a
 *  Cholesky decomposition (Hipparchus, already on the classpath), and caches the weight vector and
 *  the inverse for prediction. If the matrix is not numerically positive definite, the jitter is
 *  escalated and the fit retried.
 *
 *  @param problemDefinition the problem definition (used for default kernel length scales)
 *  @param kernel the covariance kernel. Defaults to an [RBFKernel] with ARD length scales set to the
 *  problem's input ranges.
 *  @param constantMean the constant prior mean. When null (the default), the mean of the observed
 *  responses is used (ordinary kriging).
 *  @param jitter a small positive value added to the diagonal for numerical stability. Defaults to
 *  [defaultJitter].
 */
class GaussianProcessModel @JvmOverloads constructor(
    val problemDefinition: ProblemDefinition,
    var kernel: StationaryKernel = RBFKernel(problemDefinition),
    var constantMean: Double? = null,
    jitter: Double = defaultJitter
) : SurrogateModelIfc {

    var jitter: Double = jitter
        set(value) {
            require(value > 0.0) { "The jitter must be > 0" }
            field = value
        }

    init {
        require(jitter > 0.0) { "The jitter must be > 0" }
    }

    private var trainPoints: List<DoubleArray> = emptyList()
    private var yCentered: DoubleArray = DoubleArray(0)
    private var alpha: DoubleArray = DoubleArray(0)
    private var kInverse: RealMatrix? = null
    private var priorMean: Double = 0.0
    private var logDeterminant: Double = 0.0
    private var fitted: Boolean = false

    /** Whether the model has been successfully fit. */
    @Suppress("unused")
    val isFitted: Boolean
        get() = fitted

    override fun fit(points: List<DoubleArray>, means: DoubleArray, noiseVars: DoubleArray) {
        require(points.isNotEmpty()) { "The GP must be fit to at least one point" }
        require(points.size == means.size && points.size == noiseVars.size) {
            "points, means, and noiseVars must have the same length"
        }
        val n = points.size
        trainPoints = points.map { it.copyOf() }
        priorMean = constantMean ?: means.average()
        yCentered = DoubleArray(n) { means[it] - priorMean }

        // Escalate jitter if the matrix is not numerically positive definite.
        var currentJitter = jitter
        var attempt = 0
        while (true) {
            try {
                factorize(points, noiseVars, currentJitter)
                return
            } catch (e: Exception) {
                attempt++
                if (attempt > maxJitterEscalations) throw e
                currentJitter *= jitterEscalationFactor
            }
        }
    }

    private fun factorize(points: List<DoubleArray>, noiseVars: DoubleArray, jitterValue: Double) {
        val n = points.size
        val k = Array(n) { i -> DoubleArray(n) { j -> kernel.cov(points[i], points[j]) } }
        for (i in 0 until n) {
            val noise = if (noiseVars[i].isFinite() && noiseVars[i] > 0.0) noiseVars[i] else 0.0
            k[i][i] += noise + jitterValue
        }
        val kMatrix = MatrixUtils.createRealMatrix(k)
        val cd = CholeskyDecomposition(kMatrix)
        val solver = cd.solver
        alpha = solver.solve(MatrixUtils.createRealVector(yCentered)).toArray()
        kInverse = solver.inverse
        val l = cd.l
        var logDiagSum = 0.0
        for (i in 0 until n) logDiagSum += ln(l.getEntry(i, i))
        logDeterminant = 2.0 * logDiagSum
        fitted = true
    }

    override fun predict(x: DoubleArray): SurrogateModelIfc.Prediction {
        check(fitted) { "The GP must be fit before predicting" }
        val n = trainPoints.size
        val kStar = DoubleArray(n) { kernel.cov(x, trainPoints[it]) }
        var mu = priorMean
        for (i in 0 until n) mu += kStar[i] * alpha[i]
        val kInvKStar = kInverse!!.operate(kStar)
        var quad = 0.0
        for (i in 0 until n) quad += kStar[i] * kInvKStar[i]
        val variance = kernel.cov(x, x) - quad
        return SurrogateModelIfc.Prediction(mu, if (variance > 0.0) variance else 0.0)
    }

    /**
     *  The log marginal likelihood of the current fit, used for hyperparameter selection:
     *  `-0.5 (y-m)^T K^{-1} (y-m) - 0.5 log|K| - (n/2) log(2π)`.
     */
    fun logMarginalLikelihood(): Double {
        check(fitted) { "The GP must be fit before computing the log marginal likelihood" }
        val n = trainPoints.size
        var quad = 0.0
        for (i in 0 until n) quad += yCentered[i] * alpha[i]
        return -0.5 * quad - 0.5 * logDeterminant - 0.5 * n * ln(2.0 * Math.PI)
    }

    override fun toString(): String =
        "GaussianProcessModel(kernel=$kernel, priorMean=$priorMean, jitter=$jitter, n=${trainPoints.size})"

    companion object {
        /** The default numerical jitter added to the covariance diagonal. By default, this is 1.0E-8. */
        @JvmStatic
        var defaultJitter: Double = 1.0E-8
            set(value) {
                require(value > 0.0) { "The default jitter must be > 0" }
                field = value
            }

        private const val maxJitterEscalations = 6
        private const val jitterEscalationFactor = 10.0
    }
}
