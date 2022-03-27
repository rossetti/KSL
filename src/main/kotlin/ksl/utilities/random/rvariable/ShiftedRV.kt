package ksl.utilities.random.rvariable

import ksl.utilities.random.rng.RNStreamIfc

/**
 * Shifts the generated value of the supplied random variable by the shift amount.
 * The shift amount must be positive.
 * @param shift a non-negative value
 * @param rv    the random variable to shift
 * @param stream   the generator to use
 */
class ShiftedRV(
    val shift: Double,
    rv: RVariableIfc,
    stream: RNStreamIfc = KSLRandom.nextRNStream()
) : RVariable(stream) {

    init {
        require(shift >= 0.0) { "The shift should not be < 0.0" }
    }

    private val myRV: RVariableIfc = rv.instance(stream)

    /**
     * @param shift     a non-negative value
     * @param rv        the random variable to shift
     * @param streamNum the stream number
     */
    constructor(shift: Double, rv: RVariableIfc, streamNum: Int) :
            this(shift, rv, KSLRandom.rnStream(streamNum))

    override fun generate(): Double {
        return shift + myRV.value
    }

    override fun instance(stream: RNStreamIfc): RVariableIfc {
        return ShiftedRV(shift, myRV, stream)
    }

    override fun toString(): String {
        return "ShiftedRV(shift=$shift, myRV=$myRV)"
    }

}