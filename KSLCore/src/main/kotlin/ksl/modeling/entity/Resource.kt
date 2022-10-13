package ksl.modeling.entity

import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.DefaultReportingOptionIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateAccessorIfc

interface ResourceCIfc : DefaultReportingOptionIfc {
    val waitingQ : QueueCIfc<ProcessModel.Entity>
    var initialCapacity: Int
    val capacity: Int
    val stateStatisticsOption: Boolean
    val busyState: StateAccessorIfc
    val idleState: StateAccessorIfc
    val failedState: StateAccessorIfc
    val inactiveState: StateAccessorIfc
    val state: StateAccessorIfc
    val previousState: StateAccessorIfc

    /** Checks if the resource is idle, has no units allocated
     */
    val isIdle: Boolean

    /** Checks to see if the resource is busy, has some units allocated
     */
    val isBusy: Boolean

    /** Checks if the resource is failed
     */
    val isFailed: Boolean

    /** Checks to see if the resource is inactive
     */
    val isInactive: Boolean
    val numBusyUnits : TWResponseCIfc
    val util: TWResponseCIfc
    val numAvailableUnits: Int
    val hasAvailableUnits: Boolean
    val hasBusyUnits: Boolean
    val fractionBusy: Double
    val numBusy: Int
}

/**
 *  A resource is considered busy if at least 1 unit is allocated.  A resource is considered idle if no
 *  units have been allocated.
 *
 * @param parent the containing model element
 * @param capacity the capacity for the resource at the beginning of each replication, must be at least 1
 * @param name the name for the resource
 * @param queue the queue for waiting entities
 * @param collectStateStatistics whether individual state statistics are collected
 */
class Resource(
    parent: ModelElement,
    name: String? = null,
    capacity: Int = 1,
    queue: HoldQueue? = null,
    collectStateStatistics: Boolean = false
) : ModelElement(parent, name), ResourceCIfc {

    init {
        require(capacity >= 1) { "The initial capacity of the resource must be >= 1" }
    }
    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    private val myWaitingQ: HoldQueue //TODO there will no longer be a queue????
    init {
        myWaitingQ = queue ?: HoldQueue(this, "${this.name}:Q")
    }
    override val waitingQ : QueueCIfc<ProcessModel.Entity>
        get() = myWaitingQ

    override var defaultReportingOption: Boolean = true
        set(value) {
            myWaitingQ.defaultReportingOption = value
            myNumBusy.defaultReportingOption = value
            myUtil.defaultReportingOption = value
            field = value
        }

    /** A resource can be allocated to 0 or more entities.
     *  An entity that is using a resource can have more than 1 allocation of the resource.
     *  The key to this map represents the entities that are using the resource.
     *  The element of this map represents the list of allocations allocated to the entity.
     */
    private val entityAllocations: MutableMap<ProcessModel.Entity, MutableList<Allocation>> = mutableMapOf()

    override var initialCapacity = capacity
        set(value) {
            require(value >= 1) { "The initial capacity of the resource must be >= 1" }
            if (model.isRunning) {
                Model.logger.warn { "Changed the initial capacity of $name during replication ${model.currentReplicationNumber}." }
            }
            field = value
        }

    override var capacity = capacity
        protected set

    override val stateStatisticsOption: Boolean = collectStateStatistics

    /** The busy state, keeps track of when all units are busy
     *
     */
    protected val myBusyState: ResourceState = ResourceState("${this.name}_Busy", collectStateStatistics)
    override val busyState: StateAccessorIfc
        get() = myBusyState

    /** The idle state, keeps track of when there are idle units
     * i.e. if any unit is idle then the resource as a whole is
     * considered idle
     */
    protected val myIdleState: ResourceState = ResourceState("${this.name}Idle", collectStateStatistics)
    override val idleState: StateAccessorIfc
        get() = myIdleState

    /** The failed state, keeps track of when no units
     * are available because the resource is failed
     *
     */
    protected val myFailedState: ResourceState = ResourceState("${this.name}_Failed", collectStateStatistics)
    override val failedState: StateAccessorIfc
        get() = myFailedState

    /** The inactive state, keeps track of when no units
     * are available because the resource is inactive
     */
    protected val myInactiveState: ResourceState = ResourceState("${this.name}_Inactive", collectStateStatistics)
    override val inactiveState: StateAccessorIfc
        get() = myInactiveState

    protected var myState: ResourceState = myIdleState
//        protected set(value) {
////            field.exit(time)
//            myPreviousState = field
//            field = value
////            field.enter(time)
//        }

    override val state: StateAccessorIfc
        get() = myState

    protected var myPreviousState: ResourceState = myInactiveState
    override val previousState: StateAccessorIfc
        get() = myPreviousState

    /** Checks if the resource is idle, has no units allocated
     */
    override val isIdle: Boolean
        get() = myState === myIdleState

    /** Checks to see if the resource is busy, has some units allocated
     */
    override val isBusy: Boolean
        get() = myState === myBusyState

    /** Checks if the resource is failed
     */
    override val isFailed: Boolean
        get() = myState === myFailedState

    /** Checks to see if the resource is inactive
     */
    override val isInactive: Boolean
        get() = myState === myInactiveState

    private val myNumBusy = TWResponse(this, "${this.name}:BusyUnits")
    override val numBusyUnits : TWResponseCIfc
        get() = myNumBusy

    private val myUtil = TWResponse(this, "${this.name}:Util")
    override val util: TWResponseCIfc
        get() = myUtil
    override val numBusy: Int
        get() = myNumBusy.value.toInt()

    override val numAvailableUnits: Int
        get() = if (isFailed || isInactive) {//isBusy || isFailed || isInactive
            0
        } else {
            capacity - numBusy
        }

    override val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    override val hasBusyUnits: Boolean
        get() = myNumBusy.value > 0.0

    override val fractionBusy: Double
        get() = myNumBusy.value/capacity

    override fun toString(): String {
        return "$name: state = $myState"
    }

    /**
     *  Checks if the entity is using (has allocated units) of the resource.
     * @param entity the entity that might be using the resource
     */
    fun isUsing(entity: ProcessModel.Entity): Boolean {
        return entityAllocations.contains(entity)
    }

    /**
     *  Computes the number of different allocations of the resource held by the entity.
     * @param entity the entity that might be using the resource
     * @return the count of the number of distinct allocations
     */
    fun numberOfAllocations(entity: ProcessModel.Entity): Int {
        return if (!isUsing(entity)) {
            0
        } else {
            entityAllocations[entity]!!.size
        }
    }

    /**
     * @param entity the entity that might be using the resource
     * @return the list of allocations associated with the entity's use of the resource,
     * which may be empty if the entity is not using the resource
     */
    fun allocations(entity: ProcessModel.Entity): List<Allocation> {
        if (!isUsing(entity)) {
            return emptyList()
        }
        return entityAllocations[entity]!!
    }

    /**
     * Computes the total number of units of the specified resource that are allocated
     * to the entity.
     * @param entity the entity that might be using the resource
     * @return the total amount requested over all the distinct allocations
     */
    fun totalAmountAllocated(entity: ProcessModel.Entity): Int {
        return if (!isUsing(entity)) {
            0
        } else {
            var sum = 0
            for (allocation in entityAllocations[entity]!!) {
                sum = sum + allocation.amount
            }
            sum
        }
    }

    override fun initialize() {
        super.initialize()
        entityAllocations.clear()
        capacity = initialCapacity
        myIdleState.initialize()
        myBusyState.initialize()
        myFailedState.initialize()
        myInactiveState.initialize()
        myPreviousState = myInactiveState
        myState = myIdleState
        myState.enter(time)
    }

    /**
     * It is an error to attempt to allocate resource units to an entity if there are insufficient
     * units available. Thus, the amount requested must be less than or equal to the number of units
     * available at the time of this call.
     *
     * @param entity the entity that is requesting the units
     * @param amountNeeded that amount to allocate, must be greater than or equal to 1
     * @param allocationName an optional name for the allocation
     * @param queue the queue associated with the allocation.  That is, where the entities would have had
     * to wait if the allocation was not immediately filled
     * @return an allocation representing that the units have been allocated to the entity. The reference
     * to this allocation is necessary in order to deallocate the allocated units.
     */
    fun allocate(entity: ProcessModel.Entity, amountNeeded: Int = 1, queue: HoldQueue, allocationName: String? = null): Allocation {
        require(amountNeeded >= 1) { "The amount to allocate must be >= 1" }
        check(numAvailableUnits >= amountNeeded) { "The amount requested, $amountNeeded must be <= the number of units available, $numAvailableUnits" }
        val allocation = Allocation(entity, this, amountNeeded, queue, allocationName)
        if (!entityAllocations.contains(entity)) {
            entityAllocations[entity] = mutableListOf()
        }
        entityAllocations[entity]?.add(allocation)
        myNumBusy.increment(amountNeeded.toDouble())
        myUtil.value = fractionBusy
        // must be busy, because an allocation occurred
        myState.exit(time)
        myState = myBusyState
        myState.enter(time)
        //need to put this allocation in Entity also
        entity.allocate(allocation)
        return allocation
    }

    fun deallocate(allocation: Allocation) {
        require(allocation.amount >= 1) { "The allocation does not have any amount to deallocate" }
        require(allocation.resource === this) { "The allocations was not on this resource." }
        require(entityAllocations.contains(allocation.entity)) { "The entity associated with the allocation is not using this resource." }
        val b: Boolean = entityAllocations[allocation.entity]!!.contains(allocation)
        require(b) { "The supplied allocation is not currently allocated for this resource." }
        //need to remove from allocations for the specific entity
        entityAllocations[allocation.entity]!!.remove(allocation)
        if (entityAllocations[allocation.entity]!!.isEmpty()) {
            // no more allocations for this entity, remove it from the map also
            entityAllocations.remove(allocation.entity)
        }
        // give back to the resource
        myNumBusy.decrement(allocation.amount.toDouble())
        myUtil.value = fractionBusy
        if (myNumBusy.value == 0.0) {
            myState.exit(time)
            myState = myIdleState
            myState.enter(time)
        }
        // need to also deallocate from the entity
        allocation.entity.deallocate(allocation)
        // deallocate the allocation, so it can't be used again
        allocation.amount = 0
        allocation.timeDeallocated = time
        // need to check the queue
        //TODO there will no longer be a queue
        if (myWaitingQ.isNotEmpty){
            val entity = myWaitingQ.removeNext()
            // resume the entity's process
            entity!!.resumeProcess()
        }
    }

    internal fun enqueue(entity: ProcessModel.Entity, priority: Int = entity.priority){
        myWaitingQ.enqueue(entity, priority) //TODO there will no longer be a queue
    }

    internal fun dequeue(entity: ProcessModel.Entity){
        myWaitingQ.remove(entity) //TODO there will no longer be a queue
    }

    protected inner class ResourceState(aName: String, stateStatistics: Boolean = false) :
        State(name = aName, useStatistic = stateStatistics) {
        //TODO need to track states: idle, busy, failed, inactive
    }
}