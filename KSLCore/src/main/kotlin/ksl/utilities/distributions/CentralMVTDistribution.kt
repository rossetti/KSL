package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.KSLArrays
import ksl.utilities.mcintegration.MCMultiVariateIntegration
import ksl.utilities.random.mcmc.FunctionMVIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.rootfinding.GridEnumerator
import kotlin.math.sqrt

/**
 * Represents a multi-variate t-distribution with means = 0.0
 * and the provided covariances.  The computed CDF values are to about 2 decimal places
 * using Monte-Carlo integration
 * @param dof         the degrees of freedom, must be greater than zero
 * @param covariances the variance-covariance matrix, must not be null, must be square and positive definite
 * @param stream      the stream for the sampler
 */
class CentralMVTDistribution(
    dof: Double,
    covariances: Array<DoubleArray>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : CentralMVNDistribution(covariances, stream) {

    val dof: Double

    init {
        require(dof > 0.0) { "The degrees of freedom must be > 0" }
        this.dof = dof
        val genzTFunc = GenzTFunc(this.dimension)
        integrator = MCMultiVariateIntegration(genzTFunc, sampler)
    }

    override fun toString(): String {
        val sb = StringBuilder("CentralMVTDistribution")
        sb.append(System.lineSeparator())
        sb.append("dof = ").append(dof)
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
        sb.append("cfL = ")
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

    private inner class GenzTFunc(override val dimension: Int) : FunctionMVIfc {
        override fun fx(x: DoubleArray): Double {
            return genzTFunction(x)
        }
    }

    /**
     * @param u a vector of U(0,1) random variates
     * @return the evaluation of the Genz transformed function at the point u
     */
    private fun genzTFunction(u: DoubleArray): Double {
        val z = DoubleArray(nDim)
        val r2: Double = Gamma.invChiSquareDistribution(u[nDim - 1], dof)
        // generate r from a chi-distribution
        val r = sqrt(r2)
        val sqrtDof = sqrt(dof)
        val c = r / sqrtDof
        var ap: Double = c * a[0] / cfL[0][0]
        var bp: Double = c * b[0] / cfL[0][0]
        //no need to check for infinities in a[] and b[] because stdNormalCDF handles them correctly
        var d: Double = Normal.stdNormalCDF(ap)
        var e: Double = Normal.stdNormalCDF(bp)
        var f = e - d
        for (m in 1 until nDim) {
            z[m - 1] = Normal.stdNormalInvCDF(u[m - 1])
            val mu: Double = sumProdLandY(m, m - 1, z)
            ap = (c * a[m] - mu) / cfL[m][m]
            bp = (c * b[m] - mu) / cfL[m][m]
            d = Normal.stdNormalCDF(ap)
            e = Normal.stdNormalCDF(bp)
            f = f * (e - d)
        }
        return f
    }

}

fun main() {
    testCDF()
    testQuantile()
//    enumerateQuantiles();
}

fun testCDF() {
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
    val intervals: MutableList<Interval> = ArrayList()
    intervals.add(i1)
    intervals.add(i2)
    intervals.add(i3)
    intervals.add(i4)
    intervals.add(i5)
    val d = CentralMVTDistribution(8.0, cov)
    println(d)
    println()
    val v: Double = d.cdf(intervals)
    println("Answer should be = 0.447862")
    println("v = $v")
    println()
    println(d.cdfCalculationStatistics)
}

fun testQuantile() {
    val cov = arrayOf(
        doubleArrayOf(1.0, 0.5, 0.5),
        doubleArrayOf(0.5, 1.0, 0.5),
        doubleArrayOf(0.5, 0.5, 1.0)
    )
    val d = CentralMVTDistribution(20.0, cov)
    val i1 = Interval(Double.NEGATIVE_INFINITY, 2.191936)
    val i2 = Interval(Double.NEGATIVE_INFINITY, 2.191936)
    val i3 = Interval(Double.NEGATIVE_INFINITY, 2.191936)
    val intervals: MutableList<Interval> = ArrayList<Interval>()
    intervals.add(i1)
    intervals.add(i2)
    intervals.add(i3)
    println(d)
    println()
    val v: Double = d.cdf(intervals)
    println("Integral should evaluate to 0.95")
    println("v = $v")
    println()
    println(d.cdfCalculationStatistics)
    println()
}

fun enumerateQuantiles() {
    val cov = arrayOf(
        doubleArrayOf(1.0, 0.5, 0.5),
        doubleArrayOf(0.5, 1.0, 0.5),
        doubleArrayOf(0.5, 0.5, 1.0)
    )
    val d = CentralMVTDistribution(20.0, cov)
    val grid = GridEnumerator(d.quantileFunction)
    grid.evaluate(2.1, 0.01, 20)
    println(grid)
    println()
    println("Sorted evaluations")
    val list = grid.sortedEvaluations
    for (e in list) {
        println(e)
    }

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
//    rs = pmvt(lower = a, upper = b, df=8, sigma = A)
}


