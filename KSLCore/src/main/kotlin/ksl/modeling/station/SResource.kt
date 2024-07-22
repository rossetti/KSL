package ksl.modeling.station

import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.Model
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
            if (model.isRunning) {
                Model.logger.warn { "Changed the initial capacity of $name during replication ${model.currentReplicationNumber}." }
            }
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
    var numTimesReleased = 0
        private set

    private val myNumBusy = TWResponse(this, "${this.name}:NumBusyUnits")
    val numBusyUnits: TWResponseCIfc
        get() = myNumBusy

    val numAvailableUnits: Int
        get() = capacity - myNumBusy.value.toInt()

    val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    /** Checks if the resource is idle, has no units allocated
     */
    val isIdle: Boolean
        get() = myNumBusy.value == 0.0

    /** Checks to see if the resource is busy, has some units allocated
     */
    val isBusy: Boolean
        get() = myNumBusy.value > 0.0

    override fun initialize() {
        capacity = initialCapacity
        numTimesReleased = 0
        numTimesSeized = 0
    }

    /**
     * Seizes amt units of the resource. If amt = 0, then an exception occurs. If
     * the resource has no units available, then an exception occurs. If the amt
     * &gt; getNumberAvailable() then an exception occurs.
     *
     * @param amt the amount to seize
     */
    fun seize(amt: Int = 1) {
        require(amt > 0) { "The seize amount must be > 0" }
        if (!hasAvailableUnits) {
            val s = "Tried to increment number busy with no units available"
            throw IllegalStateException(s)
        }
        if (amt > numAvailableUnits) {
            val s = "Tried to increment number busy by $amt with $numAvailableUnits units available"
            throw IllegalStateException(s)
        }
        myNumBusy.increment(amt.toDouble())
        numTimesSeized++
    }
}