package ksl.modeling.station

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.modeling.variable.TWResponseFunction
import ksl.simulation.Model
import ksl.simulation.ModelElement


interface SResourceCIfc {
    /**
     * The initial capacity of the resource at time just prior to 0.0
     */
    var initialCapacity: Int

    /**
     * The capacity of the resource at time any time t
     */
    val capacity: Int

    /**
     * Counts how many times the resource has units become busy
     */
    val numTimesSeized: Int

    /**
     * Counts how many times the resource has units become idle
     */
    val numTimesReleased: Int

    /**
     *  Response information on number of busy units
     */
    val numBusyUnits: TWResponseCIfc

    /**
     *  Response information on resource utilization
     */
    val utilization: TWResponseCIfc

    /**
     *  Current number of available units
     */
    val numAvailableUnits: Int

    /**
     *  Indicates if resource has available units
     */
    val hasAvailableUnits: Boolean

    /** Checks if the resource is idle, has no units allocated
     */
    val isIdle: Boolean

    /** Checks to see if the resource is busy, has some units allocated
     */
    val isBusy: Boolean
}

/**
 * A SResource represents a simple resource that can have units become busy. A
 * resource is considered busy when it has 1 or more units seized. A resource is
 * considered idle when all available units are idle. A resource has an initial
 * capacity, which represents the units that can be allocated.
 *
 * The capacity of the resource represents the maximum number of units available
 * for use. For example, if the resource has capacity 3, it may have 2 units busy
 * and 1 unit available. A resource cannot have more units busy than the capacity.
 *
 * @author rossetti
 */
class SResource(
    parent: ModelElement,
    capacity: Int = 1,
    name: String? = null
) : ModelElement(parent, name), SResourceCIfc {
    init {
        require(capacity >= 1) { "The initial capacity of the resource must be >= 1" }
    }

    /**
     * The initial capacity of the resource at time just prior to 0.0
     */
    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    override var initialCapacity = capacity
        set(value) {
            require(value >= 1) { "The initial capacity of the resource must be >= 1" }
            if (model.isRunning) {
                Model.logger.warn { "Changed the initial capacity of $name during replication ${model.currentReplicationNumber}." }
            }
            field = value
        }

    override val capacity: Int
        get() = myCapacity

    override val numTimesSeized: Int
        get() = myNumTimesSeized

    override val numTimesReleased: Int
        get() = myNumTimesReleased

    /**
     * The capacity of the resource at time any time t
     */
    private var myCapacity = capacity

    /**
     * Counts how many times the resource has units become busy
     */
    private var myNumTimesSeized = 0

    /**
     * Counts how many times the resource has units become idle
     */
    private var myNumTimesReleased = 0

    private val myNumBusy = TWResponse(this, "${this.name}:NumBusy")
    override val numBusyUnits: TWResponseCIfc
        get() = myNumBusy

    private val myUtil: TWResponseFunction = TWResponseFunction({ x -> x / (capacity) }, myNumBusy, "${this.name}:Util")
    override val utilization: TWResponseCIfc
        get() = myUtil

    override val numAvailableUnits: Int
        get() = capacity - myNumBusy.value.toInt()

    override val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    /** Checks if the resource is idle, has no units allocated
     */
    override val isIdle: Boolean
        get() = myNumBusy.value == 0.0

    /** Checks to see if the resource is busy, has some units allocated
     */
    override val isBusy: Boolean
        get() = myNumBusy.value > 0.0

    override fun initialize() {
        myCapacity = initialCapacity
        myNumTimesReleased = 0
        myNumTimesSeized = 0
    }

    /**
     * Seizes amount units of the resource. If amt = 0, then an exception occurs. If
     * the resource has no units available, then an exception occurs. If the amt
     * is greater than the number available, then an exception occurs. Thus, users
     * must check for availability before calling this function.
     *
     * @param amount the amount to seize
     */
    fun seize(amount: Int = 1) {
        require(amount > 0) { "The seize amount must be > 0" }
        require(amount <= numAvailableUnits) { "Attempted to seize more than amount available ($numAvailableUnits) " }
        myNumBusy.increment(amount.toDouble())
        myNumTimesSeized++
    }

    /**
     *  Release the [amount] of the resource. The amount to release must be 1 or more and less than
     *  or equal to the current number of busy resource units.
     */
    fun release(amount: Int = 1) {
        require(amount > 0) { "The release amount must be > 0" }
        require(amount <= myNumBusy.value.toInt()) { "Attempted to release more units than were busy (${myNumBusy.value}" }
        myNumBusy.decrement(amount.toDouble())
        myNumTimesReleased++
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Resource: $name Capacity = $capacity \t Available = $numAvailableUnits \t Busy = ${myNumBusy.value}")
        return sb.toString()
    }
}