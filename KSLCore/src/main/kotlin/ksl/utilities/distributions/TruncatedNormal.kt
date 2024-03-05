package ksl.utilities.distributions

import ksl.utilities.Interval
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.GetRVariableIfc
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.TruncatedNormalRV

/**
 *  Creates a truncated normal distribution over the supplied [interval].
 *  The supplied mean [normalMean] and variance [normalVariance] is the mean of the not truncated normal distribution.
 *  The supplied mean must be contained within the supplied interval.
 *
 *  Uses the algorithm:
 *   1. W ~ U(F(a), F(b))
 *   2. X = F^{-1}(W)
 *
 *   where "a" is the lower limit and b is the upper limit, and F is the normal distribution CDF.
 */
class TruncatedNormal(
    normalMean: Double,
    normalVariance: Double,
    interval: Interval,
    name: String? = null
) : Distribution(name), ContinuousDistributionIfc, InverseCDFIfc, GetRVariableIfc {

    constructor(
        normal: Normal,
        interval: Interval,
        name: String? = null
    ) : this(normal.mean, normal.variance, interval, name)

    private lateinit var myInterval: Interval

    private val myNormal: Normal = Normal(normalMean, normalVariance)

    init {
        setLimits(normalMean, normalVariance, interval)
    }

    val lowerLimit
        get() = myInterval.lowerLimit

    val upperLimit
        get() = myInterval.upperLimit

    var cdfAtLowerLimit = 0.0
        private set
    var cdfAtUpperLimit = 0.0
        private set

    private val myDeltaFUFL
        get() = cdfAtUpperLimit - cdfAtLowerLimit

    /**
     *  A convenience function for setting the parameters of the distribution.
     */
    fun setLimits(normalMean: Double, normalVariance: Double, lower: Double, upper: Double) {
        setLimits(normalMean, normalVariance, Interval(lower, upper))
    }

    /**
     *  Should be used to change the parameters of the distribution
     */
    fun setLimits(normalMean: Double, normalVariance: Double, interval: Interval) {
        require(interval.lowerLimit < interval.upperLimit) { "The lower limit must be strictly < upper limit" }
        require(!(interval.lowerLimit == Double.NEGATIVE_INFINITY && interval.upperLimit == Double.POSITIVE_INFINITY))
        { "There was no truncation over the interval of support" }
        require(interval.contains(normalMean)) { "The mean value was not within the truncation interval." }
        myNormal.mean = normalMean
        myNormal.variance = normalVariance
        val truncLL = interval.lowerLimit
        val truncUL = interval.upperLimit
        val cdfLL = Double.NEGATIVE_INFINITY
        val cdfUL = Double.POSITIVE_INFINITY
        if (truncLL > cdfLL && truncUL < cdfUL) {
            // truncation on both ends
            cdfAtUpperLimit = myNormal.cdf(truncUL)
            cdfAtLowerLimit = myNormal.cdf(truncLL)
        } else if (truncUL < cdfUL) { // truncation on upper tail
            // must be that upperLimit < UL, and lowerLimit == LL
            cdfAtUpperLimit = myNormal.cdf(truncUL)
            cdfAtLowerLimit = 0.0
        } else { //truncation on the lower tail
            // must be that upperLimit == UL, and lowerLimit > LL
            cdfAtUpperLimit = 1.0
            cdfAtLowerLimit = myNormal.cdf(truncLL)
        }
        require(!KSLMath.equal((cdfAtUpperLimit - cdfAtLowerLimit), 0.0))
        { "The supplied limits have no probability support (F(upper) - F(lower) = 0.0)" }
        myInterval = interval.instance()
    }

    val interval
        get() = myInterval.instance()

    var normalMean: Double
        get() = myNormal.mean
        set(value) {
            setLimits(value, myNormal.variance, myInterval)
        }

    var normalVariance: Double
        get() = myNormal.variance
        set(value) {
            setLimits(myNormal.mean, value, myInterval)
        }

    override fun cdf(x: Double): Double {
        return if (x < lowerLimit) {
            0.0
        } else if (x in lowerLimit..upperLimit) {
            val F = myNormal.cdf(x)
            (F - cdfAtLowerLimit) / myDeltaFUFL
        } else {
            //if (x > myUpperLimit)
            1.0
        }
    }

    override fun pdf(x: Double): Double {
        return (myNormal.pdf(x) / myDeltaFUFL)
    }

    /**
     *  This is the mean of the truncated distribution.
     */
    override fun mean(): Double {
        val mu = myNormal.mean()
        return mu / myDeltaFUFL
    }

    /**
     *  This is the variance of the truncated distribution.
     */
    override fun variance(): Double {
        // Var[X] = E[X^2] - E[X]*E[X]
        // first get 2nd moment of truncated distribution
        // E[X^2] = 2nd moment of original cdf/(F(b)-F(a)
        var mu = myNormal.mean()
        val s2 = myNormal.variance()
        // 2nd moment of original cdf
        var m2 = s2 + mu * mu
        // 2nd moment of truncated
        m2 = m2 / myDeltaFUFL
        // mean of truncated
        mu = mean()
        return m2 - mu * mu
    }

    override fun domain(): Interval {
        return interval
    }

    override fun invCDF(p: Double): Double {
        val v = cdfAtLowerLimit + myDeltaFUFL * p
        return myNormal.invCDF(v)
    }

    /**
     * Sets the parameters of the truncated distribution
     * normal mean = parameter[0]
     * normal variance = parameters[1]
     * lower limit = parameters[2]
     * upper limit = parameters[3]
     *
     * any other values in the array should be interpreted as the parameters
     * for the underlying distribution
     */
    override fun parameters(params: DoubleArray) {
        val interval = Interval(params[2], params[3])
        setLimits(params[0], params[1], interval)
    }

    /**
     * Gets the parameters of the truncated distribution
     * normal mean = parameter[0]
     * normal variance = parameters[1]
     * lower limit = parameters[2]
     * upper limit = parameters[3]
     *
     * any other values in the array should be interpreted as the parameters
     * for the underlying distribution
     */
    override fun parameters(): DoubleArray {
        return doubleArrayOf(normalMean, normalVariance, lowerLimit, upperLimit)
    }

    override fun randomVariable(stream: RNStreamIfc): RVariableIfc {
        return TruncatedNormalRV(normalMean, normalVariance, myInterval, stream)
    }

    override fun instance(): TruncatedNormal {
        return TruncatedNormal(normalMean, normalVariance, myInterval)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("TruncatedNormal")
        sb.appendLine("normalMean=$normalMean, normalVariance=$normalVariance")
        sb.appendLine("lowerLimit=$lowerLimit, upperLimit=$upperLimit")
        sb.appendLine("cdfAtLowerLimit=$cdfAtLowerLimit, cdfAtUpperLimit=$cdfAtUpperLimit")
        return sb.toString()
    }

}