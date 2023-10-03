package ksl.utilities.distributions


import ksl.utilities.Interval
import ksl.utilities.KSLArrays
import ksl.utilities.math.FunctionIfc
import ksl.utilities.mcintegration.MCExperimentSetUpIfc
import ksl.utilities.mcintegration.MCMultiVariateIntegration
import ksl.utilities.random.mcmc.FunctionMVIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.MVIndependentRV
import ksl.utilities.random.rvariable.MVNormalRV
import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.statistic.Statistic
import java.util.*

/**
 * Represents a multi-variate normal distribution with means = 0.0
 * and the provided covariances.  The computed CDF values are to about 2 decimal places
 * using Monte-Carlo integration.
 *
 * @param covariances the variance-covariance matrix, must not be null, must be square and positive definite
 * @param stream      the stream for the sampler
 */
class CentralMVNDistribution (
    covariances: Array<DoubleArray>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : MVCDF(covariances.size) {

    protected val covariances: Array<DoubleArray>
    protected val cfL: Array<DoubleArray>
    protected val nDim: Int
    protected var integrator: MCMultiVariateIntegration
    protected var sampler: MVIndependentRV

    init {
        require(MVNormalRV.isValidCovariance(covariances)) { "The covariance array is not a valid covariance matrix" }
        nDim = covariances.size
        cfL = MVNormalRV.choleskyDecomposition(covariances)
        this.covariances = KSLArrays.copy2DArray(covariances)
        sampler = MVIndependentRV(nDim, UniformRV(0.0, 1.0, stream))
        val genzFunc = GenzFunc(nDim)
        integrator = MCMultiVariateIntegration(genzFunc, sampler)
        //integrator.desiredHWErrorBound = 0.001
    }

    /** The user can use this to control the specification of the monte-carlo
     * integration of the CDF.
     *
     * @return  the controller
     */
    val mcIntegrationController: MCExperimentSetUpIfc
        get() = integrator

    override fun toString(): String {
        val sb = StringBuilder("CentralMVNDistribution")
        sb.append(System.lineSeparator())
        sb.append("nDim = ").append(nDim)
        sb.append(System.lineSeparator())
        sb.append("covariances = ")
        sb.append(System.lineSeparator())
        for (i in covariances.indices) {
            sb.append("[")
            sb.append(KSLArrays.toCSVString(covariances[i]))
            sb.append("]")
            sb.append(System.lineSeparator())
        }
        sb.append("Cholesky decomposition = ")
        sb.append(System.lineSeparator())
        for (i in cfL.indices) {
            sb.append("[")
            sb.append(KSLArrays.toCSVString(cfL[i]))
            sb.append("]")
            sb.append(System.lineSeparator())
        }
        sb.append(System.lineSeparator())
        return sb.toString()
    }

    /**
     * Uses the Genz transform function for Monte-carlo evaluation of the integral.
     * Accuracy depends on the sampling.  Should be to about 2 decimal places with default settings.
     *
     * Refer to equation (3) of this [paper](https://informs-sim.org/wsc15papers/032.pdf)
     *
     * @return the estimated value
     */
    override fun computeCDF(): Double {
        return integrator.runSimulation()
    }

    /**
     * @return the statistical results of the CDF calculation
     */
    val cdfCalculationStatistics: Statistic
        get() = integrator.statistics()

    /**
     * @return the results of the CDF integration as a string
     */
    val cdfIntegrationResults: String
        get() = integrator.toString()

    private inner class GenzFunc(override val dimension: Int) : FunctionMVIfc {
        override fun fx(x: DoubleArray): Double {
            return genzFunction(x)
        }
    }

    /**
     * @param u a vector of U(0,1) random variates
     * @return the evaluation of the Genz transformed function at the point u
     */
    protected fun genzFunction(u: DoubleArray): Double {
        val z = DoubleArray(nDim)
        var ap: Double = a[0] / cfL[0][0]
        var bp: Double = b[0] / cfL[0][0]
        //no need to check for infinities in a[] and b[] because stdNormalCDF handles them correctly
        var d: Double = Normal.stdNormalCDF(ap)
        var e: Double = Normal.stdNormalCDF(bp)
        var f = e - d
        for (m in 1 until nDim) {
            z[m - 1] = Normal.stdNormalInvCDF(u[m - 1])
            val mu = sumProdLandY(m, m - 1, z)
            ap = (a[m] - mu) / cfL[m][m]
            bp = (b[m] - mu) / cfL[m][m]
            d = Normal.stdNormalCDF(ap)
            e = Normal.stdNormalCDF(bp)
            f = f * (e - d)
        }
        return f
    }

    protected fun sumProdLandY(r: Int, k: Int, y: DoubleArray): Double {
        var sum = 0.0
        for (n in 0 until k) {
            sum = sum + cfL[r][n] * y[n]
        }
        return sum
    }

    protected inner class RootFunction(private val confidLevel: Double = 0.95) : FunctionIfc {

        override fun f(x: Double): Double {
            return cdf(x) - confidLevel
        }
    }

    private fun getRootFunction(level: Double): RootFunction {
        return RootFunction(level)
    }

    inner class QuantileFunction : FunctionIfc {
        override fun f(x: Double): Double {
            return cdf(x)
        }
    }

    val quantileFunction: QuantileFunction
        get() = QuantileFunction()
}

fun main(){
    testCDF1()
}

fun testCDF1() {
    val cov = arrayOf(
        doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0),
        doubleArrayOf(1.0, 2.0, 2.0, 2.0, 2.0),
        doubleArrayOf(1.0, 2.0, 3.0, 3.0, 3.0),
        doubleArrayOf(1.0, 2.0, 3.0, 4.0, 4.0),
        doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
    )
    val i1 = Interval(-5.0, 6.0)
    val i2 = Interval(-4.0, 5.0)
    val i3 = Interval(-3.0, 4.0)
    val i4 = Interval(-2.0, 3.0)
    val i5 = Interval(-1.0, 2.0)
    val intervals: MutableList<Interval> = ArrayList<Interval>()
    intervals.add(i1)
    intervals.add(i2)
    intervals.add(i3)
    intervals.add(i4)
    intervals.add(i5)
    val d = CentralMVNDistribution(cov)
    println(d)
    println()
    val v: Double = d.cdf(intervals)
    println("Answer should be = 0.4741284")
    println("v = $v")
    println()
    println(d.cdfCalculationStatistics)

    // R test
    //    A = as.matrix(data.frame(c(1.0, 1.0, 1.0, 1.0, 1.0),
    //    c(1.0, 2.0, 2.0, 2.0, 2.0),
    //    c(1.0, 2.0, 3.0, 3.0, 3.0),
    //    c(1.0, 2.0, 3.0, 4.0, 4.0),
    //    c(1.0, 2.0, 3.0, 4.0, 5.0)))
    //    colnames(A) = NULL
    //install.packages("mvtnorm")
    //    library("mvtnorm")
    //    a = c(-5.0, -4.0, -3.0, -2.0, -1.0)
    //    b = c(6.0, 5.0, 4.0, 3.0, 2.0)
    //    rs = pmvnorm(lower = a, upper = b, sigma = A)
}
