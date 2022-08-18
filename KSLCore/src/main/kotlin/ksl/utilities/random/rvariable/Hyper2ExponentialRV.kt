package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc

/**
 * Two exponential random variables mixed to get a hyper-exponential. For higher
 * order hyper-exponential use MixtureRV.  The mixing probability is the
 * probability of getting the first exponential distribution with mean1
 */
class Hyper2ExponentialRV(
    val mixingProb: Double,
    val mean1: Double,
    val mean2: Double,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {

    init {
        require(!(mixingProb < 0.0 || mixingProb > 1.0)) { "Mixing Probability must be [0,1]" }
        require(mean1 > 0.0) { "Exponential mean1 must be > 0.0" }
        require(mean2 > 0.0) { "Exponential mean2 must be > 0.0" }
    }

    constructor(mixingProb: Double, mean1: Double, mean2: Double, streamNum: Int) :
            this(mixingProb, mean1, mean2, KSLRandom.rnStream(streamNum))

    override fun generate(): Double {
        val v = KSLRandom.rBernoulli(mixingProb, rnStream)
        return if (v >= 1.0) {
            KSLRandom.rExponential(mean1, rnStream)
        } else {
            KSLRandom.rExponential(mean2, rnStream)
        }
    }

    /**
     * @param stream the RNStreamIfc to use
     * @return a new instance with same parameter value
     */
    override fun instance(stream: RNStreamIfc): Hyper2ExponentialRV {
        return Hyper2ExponentialRV(mixingProb, mean1, mean2, stream)
    }

    override fun toString(): String {
        return "Hyper2ExponentialRV(mixingProb=$mixingProb, mean1=$mean1, mean2=$mean2)"
    }
}