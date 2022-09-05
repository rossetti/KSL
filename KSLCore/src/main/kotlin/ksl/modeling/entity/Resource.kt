package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateAccessorIfc

/**
 *  A resource is considered busy if at least 1 unit is allocated.  A resource is considered idle if no
 *  units have been allocated.
 *
 * @param parent the containing model element
 * @param theInitialCapacity the capacity for the resource at the beginning of each replication, must be at least 1
 * @param aName the name for the resource
 * @param discipline the queue discipline for waiting entities
 * @param collectStateStatistics whether individual state statistics are collected
 */
class Resource(
    parent: ModelElement,
    aName: String? = null,
    theInitialCapacity: Int = 1,
    discipline: Queue.Discipline = Queue.Discipline.FIFO,
    collectStateStatistics: Boolean = false
) : ModelElement(parent, aName) {

    init {
        require(theInitialCapacity >= 1) { "The initial capacity of the resource must be >= 1" }
    }

    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    private val waitingQ: Queue<EntityType.Entity> = Queue(this, "${name}:Q", discipline)

    /** A resource can be allocated to 0 or more entities.
     *  An entity that is using a resource can have more than 1 allocation of the resource.
     *  The key to this map represents the entities that are using the resource.
     *  The element of this map represents the list of allocations allocated to the entity.
     */
    private val entityAllocations: MutableMap<EntityType.Entity, MutableList<Allocation>> = mutableMapOf()

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

    val stateStatistics: Boolean = collectStateStatistics

    /** The busy state, keeps track of when all units are busy
     *
     */
    protected val myBusyState: ResourceState = ResourceState("${name}_Busy", collectStateStatistics)
    val busyState: StateAccessorIfc
        get() = myBusyState

    /** The idle state, keeps track of when there are idle units
     * i.e. if any unit is idle then the resource as a whole is
     * considered idle
     */
    protected val myIdleState: ResourceState = ResourceState("${name}Idle", collectStateStatistics)
    val idleState: StateAccessorIfc
        get() = myIdleState

    /** The failed state, keeps track of when no units
     * are available because the resource is failed
     *
     */
    protected val myFailedState: ResourceState = ResourceState("${name}_Failed", collectStateStatistics)
    val failedState: StateAccessorIfc
        get() = myFailedState

    /** The inactive state, keeps track of when no units
     * are available because the resource is inactive
     */
    protected val myInactiveState: ResourceState = ResourceState("${name}_Inactive", collectStateStatistics)
    val inactiveState: StateAccessorIfc
        get() = myInactiveState

    protected var myState: ResourceState = myIdleState
//        protected set(value) {
////            field.exit(time)
//            myPreviousState = field
//            field = value
////            field.enter(time)
//        }

    val state: StateAccessorIfc
        get() = myState

    protected var myPreviousState: ResourceState = myInactiveState
    val previousState: StateAccessorIfc
        get() = myPreviousState

    /** Checks if the resource is idle, has no units allocated
     */
    val isIdle: Boolean
        get() = myState === myIdleState

    /** Checks to see if the resource is busy, has some units allocated
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

    val hasBusyUnits: Boolean
        get() = numBusyUnits > 0.0

    override fun toString(): String {
        return "$name: state = $myState"
    }

    /**
     *  Checks if the entity is using (has allocated units) of resource.
     */
    fun isUsing(entity: EntityType.Entity): Boolean {
        return entityAllocations.contains(entity)
    }

    /**
     *  Computes the number of different allocations of the resource held by the entity.
     */
    fun numberOfAllocations(entity: EntityType.Entity): Int {
        return if (!isUsing(entity)) {
            0
        } else {
            entityAllocations[entity]!!.size
        }
    }

    /**
     * Computes the total number of units of the specified resource that are allocated
     * to the entity.
     */
    fun totalAmountAllocated(entity: EntityType.Entity): Int {
        if (!isUsing(entity)) {
            return 0
        } else {
            var sum = 0
            for (allocation in entityAllocations[entity]!!) {
                sum = sum + allocation.amount
            }
            return sum
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
     * @param amountRequested that amount to allocate, must be greater than or equal to 1
     * @return an allocation representing that the units have been allocated to the entity. The reference
     * to this allocation is necessary in order to deallocate the allocated units.
     */
    fun allocate(entity: EntityType.Entity, amountRequested: Int = 1): Allocation {
        require(amountRequested >= 1) { "The amount to allocate must be >= 1" }
        check(numAvailableUnits >= amountRequested) { "The amount requested, $amountRequested must be <= the number of units available, $numAvailableUnits" }
        val allocation = Allocation(entity, this, amountRequested)
        if (!entityAllocations.contains(entity)) {
            entityAllocations[entity] = mutableListOf()
        }
        entityAllocations[entity]?.add(allocation)
        myNumBusy.increment(amountRequested.toDouble())
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
        if (myNumBusy.value == 0.0) {
            myState.exit(time)
            myState = myIdleState
            myState.enter(time)
        }
        // need to also deallocate from the entity
        allocation.entity.deallocate(allocation)
        // deallocate the allocation, so it can't be used again
        allocation.amount = 0
        // need to check the queue
        if (waitingQ.isNotEmpty){
            val entity = waitingQ.removeNext()
            // resume the entity's process
            entity!!.resumeProcess()
        }
    }

    internal fun enqueue(entity: EntityType.Entity, priority: Int = entity.priority){
        waitingQ.enqueue(entity, priority)
    }

    internal fun dequeue(entity: EntityType.Entity){
        waitingQ.remove(entity)
    }

    protected inner class ResourceState(aName: String, stateStatistics: Boolean = false) :
        State(name = aName, useStatistic = stateStatistics) {
        //TODO need to track states: idle, busy, failed, inactive
    }
}