package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc

/**
 * Represents a multi-variate distribution with the specified marginals
 * The sampling of each marginal random variable is independent. That is the resulting
 * distribution has independent marginals. The supplied marginals may be the same
 * distribution or not.  If they are all the same, then use MVIndependentRV instead.
 * All the random variables will share the same stream. The sampling ensures that
 * is the sampling is consecutive within the stream and thus independent.
 * @param marginals must have at least 2 supplied marginals
 * @param stream the stream to associate with each marginal
 */
class MVIndependentMarginals(
    marginals: List<RVariableIfc>,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : MVRVariableIfc {
    /**
     * myRNStream provides a reference to the underlying stream of random numbers
     */
    private var myRNStream: RNStreamIfc = stream

    override var advanceToNextSubStreamOption: Boolean
        get() = rnStream.advanceToNextSubStreamOption
        set(value) {
            rnStream.advanceToNextSubStreamOption = value
        }

    override var resetStartStreamOption: Boolean
        get() = rnStream.resetStartStreamOption
        set(value) {
            rnStream.resetStartStreamOption = value
        }

    private val myRVs: MutableList<RVariableIfc> = ArrayList()

    init {
        require(marginals.size > 1) { "The number of supplied marginals must be at least 2" }
        for (rv in marginals) {
            myRVs.add(rv.instance(myRNStream))
        }
    }

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return MVIndependentMarginals(myRVs, stream)
    }

    override fun antitheticInstance(): MVRVariableIfc {
        return MVIndependentMarginals(myRVs, myRNStream.antitheticInstance())
    }

    override val dimension: Int
        get() = myRVs.size

    override fun sample(array: DoubleArray) {
        require(array.size == dimension) { "The size of the array to fill does not match the sampling dimension!" }
        for ((i, rv) in myRVs.withIndex()) {
            array[i] = rv.sample()
        }
    }

    override fun resetStartStream() {
        myRNStream.resetStartStream()
    }

    override fun resetStartSubStream() {
        myRNStream.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        myRNStream.advanceToNextSubStream()
    }

    override var rnStream: RNStreamIfc
        get() = myRNStream
        set(value) {
            for (rv in myRVs) {
                rv.rnStream = value
            }
        }

    override var antithetic: Boolean
        get() = myRNStream.antithetic
        set(value) {
            myRNStream.antithetic = value
        }
}