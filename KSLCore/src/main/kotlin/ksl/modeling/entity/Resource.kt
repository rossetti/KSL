package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateAccessorIfc

class Resource(
    parent: ModelElement,
    theInitialCapacity: Int = 1,
    aName: String? = null,
    discipline: Queue.Discipline = Queue.Discipline.FIFO
) : ModelElement(parent, aName) {

    init {
        require(theInitialCapacity >= 1) { "The initial capacity of the resource must be >= 1" }
    }

    private val waitingQ: Queue<EntityType.Entity> = Queue(this, "${name}:Q", discipline)
//TODO also need to keep track of entities using the resource.
    var initialCapacity = theInitialCapacity
        set(value) {
            require(value >= 1) { "The initial capacity of the resource must be >= 1" }
            if (model.isRunning) {
                Model.logger.warn { "Changed the initial capacity of $name during replication ${model.currentReplicationNumber}." }
            }
            field = value
        }

    var capacity = theInitialCapacity
        protected set

    /** The busy state, keeps track of when all units are busy
     *
     */
    protected val myBusyState: ResourceState = ResourceState("${name}_Busy")
    val busyState: StateAccessorIfc
        get() = myBusyState

    /** The idle state, keeps track of when there are idle units
     * i.e. if any unit is idle then the resource as a whole is
     * considered idle
     */
    protected val myIdleState: ResourceState = ResourceState("${name}Idle")
    val idleState: StateAccessorIfc
        get() = myIdleState

    /** The failed state, keeps track of when no units
     * are available because the resource is failed
     *
     */
    protected val myFailedState: ResourceState = ResourceState("${name}_Failed")
    val failedState: StateAccessorIfc
        get() = myFailedState

    /** The inactive state, keeps track of when no units
     * are available because the resource is inactive
     */
    protected val myInactiveState: ResourceState = ResourceState("${name}_Inactive")
    val inactiveState: StateAccessorIfc
        get() = myInactiveState

    protected var myState: ResourceState = myIdleState
    val state: StateAccessorIfc
        get() = myState

    protected var myPreviousState: ResourceState = myIdleState
    val previousState: StateAccessorIfc
        get() = myPreviousState

    /** Checks if the resource is idle.
     */
    val isIdle: Boolean
        get() = myState === myIdleState

    /** Checks to see if the resource is busy
     */
    val isBusy: Boolean
        get() = myState === myBusyState

    /** Checks if the resource is failed
     */
    val isFailed: Boolean
        get() = myState === myFailedState

    /** Checks to see if the resource is inactive
     */
    val isInactive: Boolean
        get() = myState === myInactiveState

    protected val myNumBusy = TWResponse(this, "${name}:#Busy Units")
    val numBusyUnits
        get() = myNumBusy.value

    val numAvailableUnits: Int
        get() = if (isBusy || isFailed || isInactive) {
            0
        } else {
            capacity - numBusyUnits.toInt()
        }

    val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    override fun toString(): String {
        return "$name: state = $myState"
    }

    protected inner class ResourceState(aName: String) : State(name = aName) {
        //TODO need to track states: idle, busy, failed, inactive
    }
}