package ksl.utilities.random.rng

import ksl.utilities.random.rvariable.KSLRandom

interface RNStreamChangeIfc {
    /**
     *
     * the underlying stream of random numbers
     */
    var rnStream: RNStreamIfc

    /**
     *
     * @return the stream number allocated to the random variable by the default stream provider. This will
     * return -1 if the random variable's underlying stream was not provided by the default stream provider
     */
    val streamNumber: Int
        get() = KSLRandom.streamNumber(rnStream)

    /** Assigns the stream associated with the supplied number from the default RNStreamProvider
     *
     * @param streamNumber a stream number, 1, 2, etc.
     */
    fun useStreamNumber(streamNumber: Int) {
        rnStream = KSLRandom.rnStream(streamNumber)
    }
}