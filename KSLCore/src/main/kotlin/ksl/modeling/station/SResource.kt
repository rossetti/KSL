package ksl.modeling.station

import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement

/**
 * A SResource represents a simple resource that can have units become busy. A
 * resource is considered busy when it has 1 or more units busy. A resource is
 * considered idle when all available units are idle. A resource has an initial
 * capacity, which represents the units that can be allocated.
 *
 * The capacity of the resource represents the maximum number of units available
 * for use. For example, if the resource has capacity 3, it may have 2 units busy
 * and 1 unit idle. A resource cannot have more units busy than the capacity.
 *
 * @author rossetti
 */
class SResource(
    parent: ModelElement,
    capacity: Int = 1,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(capacity >= 1) { "The initial capacity of the resource must be >= 1" }
    }

    /**
     * The initial capacity of the resource at time just prior to 0.0
     */
    var initialCapacity = capacity
        set(value) {
            require(value >= 1) { "The initial capacity of the resource must be >= 1" }
            field = value
        }

    /**
     * The capacity of the resource at time any time t
     */
    var capacity = capacity
        private set

    /**
     * Counts how many times the resource has units become busy
     */
    var numTimesSeized = 0
        private set

    /**
     * Counts how many times the resource has units become idle
     */
    var myNumTimesReleased = 0
        private set

    private val myNumBusy: TWResponse = TWResponse(this, "${this}:NumBusy")
    val numBusy: TWResponseCIfc
        get() = myNumBusy
}