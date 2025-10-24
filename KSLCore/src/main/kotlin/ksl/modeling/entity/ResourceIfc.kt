package ksl.modeling.entity

interface ResourceIfc {

    /**
     *  The current capacity of the resource. In general, it can be 0 or greater
     */
    val capacity: Int

    /** Checks if the resource is idle, has no units allocated
     */
    val isIdle: Boolean
        get() = numBusy == 0

    /** Checks to see if the resource is busy, has some units allocated
     */
    val isBusy: Boolean
        get() = numBusy > 0

    /** Checks to see if the resource is inactive
     */
    val isInactive: Boolean

    /**
     *  If c(t) is the current capacity and b(t) is the current number busy,
     *  then a(t) = c(t) - b(t) is the current number of available units.
     *  Under some capacity change situations, a(t) may be negative.
     */
    val numAvailableUnits: Int
        get() = capacity - numBusy

    /**
     *  If a(t) is greater than zero
     */
    val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    /**
     *  The number of busy units at any time t, b(t)
     */
    val numBusy: Int

}