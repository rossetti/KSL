package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc

/**
 * Represents a multi-variate distribution with the specified dimensions.
 * The sampling of each dimension is independent. That is the resulting
 * distribution has independent marginals that are represented by the same
 * distribution as provided by the supplied random variable
 * @param theDimension         the dimension, must be at least 2
 * @param theRandomVariable the random variable for the marginals
 */
class MVIndependentRV(theDimension: Int, theRandomVariable: RVariableIfc) : MVRVariableIfc {
    init {
        require(theDimension > 1) { "The multi-variate dimension must be at least 2" }
    }
    override val dimension: Int = theDimension

    private val myRV: RVariableIfc = theRandomVariable

    override fun instance(stream: RNStreamIfc): MVRVariableIfc {
        return MVIndependentRV(dimension, myRV.instance())
    }

    override fun antitheticInstance(): MVRVariableIfc {
        return MVIndependentRV(dimension, myRV.antitheticInstance())
    }

    override fun sample(array: DoubleArray) {
        require(array.size == dimension) { "The size of the array to fill does not match the sampling dimension!" }
        myRV.sample(array)
    }

    override var rnStream: RNStreamIfc
        get() = myRV.rnStream
        set(value) {
            myRV.rnStream = value
        }

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

    override fun resetStartStream() {
        myRV.resetStartStream()
    }

    override fun resetStartSubStream() {
        myRV.resetStartSubStream()
    }

    override fun advanceToNextSubStream() {
        myRV.advanceToNextSubStream()
    }

    override var antithetic: Boolean
        get() = myRV.antithetic
        set(value) {
            myRV.antithetic = value
        }
}