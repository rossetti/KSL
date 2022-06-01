package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc
import java.util.*

/**
 * Represents a multi-variate distribution with the specified marginals
 * The sampling of each marginal random variable is independent. That is the resulting
 * distribution has independent marginals. The supplied marginals may be the same
 * distribution or not.  If they are all the same, then use MVIndependentRV instead.
 * All the random variables will share the same stream. The sampling ensures that
 * draws are consecutive and thus independent.
 */
class MVIndependentMarginals(marginals: List<RVariableIfc>, stream: RNStreamIfc) : MVRVariableIfc {
    protected val myRVs: MutableList<RVariableIfc>

    /**
     * myRNStream provides a reference to the underlying stream of random numbers
     */
    protected var myRNStream: RNStreamIfc

    init {
        myRNStream = Objects.requireNonNull(stream, "RNStreamIfc stream must be non-null")
        myRVs = ArrayList()
        for (rv in marginals) {
            requireNotNull(rv) { "The list of marginal random variables had a null member!" }
            myRVs.add(rv.instance(stream))
        }
    }

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return MVIndependentMarginals(myRVs, stream)
    }

    fun antitheticInstance(): MVRVariableIfc {
        return MVIndependentMarginals(myRVs, myRNStream.antitheticInstance())
    }

    override val dimension: Int
        get() = myRVs.size

    override fun sample(array: DoubleArray) {
        require(array.size == dimension) { "The size of the array to fill does not match the sampling dimension!" }
        var i = 0
        for (rv in myRVs) {
            array[i] = rv.sample()
            i++
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