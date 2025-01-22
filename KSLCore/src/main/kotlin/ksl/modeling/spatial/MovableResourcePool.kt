package ksl.modeling.spatial

import ksl.modeling.entity.*
import ksl.modeling.variable.*
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.random.RandomIfc


/**
 * Provides for a method to select resources from a list such that
 * the returned list will contain resources that can fully fill the amount needed
 * or the list will be empty.
 */
fun interface MovableResourceSelectionRuleIfc {
    /**
     * @param list of resources to consider selecting from
     * @return the selected list of resources. It may be empty
     */
    fun selectMovableResources(list: List<MovableResource>): List<MovableResource>
}

/**
 *  Function to determine how to allocate requirement for units across
 *  a list of resources that have sufficient available units to meet
 *  the amount needed.
 */
fun interface MovableResourceAllocationRuleIfc {

    /** The method assumes that the provided list of resources has
     *  enough units available to satisfy the needs of the request.
     *
     * @param requestLocation the location associated with the request. This information can be
     * used to determine the allocation based on distances.
     * @param resourceList list of resources to be allocated from
     * @return the amount to allocate from each resource as a map
     */
    fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc,
        resourceList: List<MovableResource>
    ): MovableResource
}

/**
 * @return returns a (new) list of idle movable resources. It may be empty.
 */
fun findIdleResources(list: List<MovableResource>): MutableList<MovableResource> {
    val rList = mutableListOf<MovableResource>()
    for (ru in list) {
        if (ru.isIdle) {
            rList.add(ru)
        }
    }
    return rList
}

/** Filters the supplied list such that the returned list has movable resources that
 * are available for allocation.
 *
 * @return returns a (new) list of movable resources are available for allocation. It may be empty.
 */
fun findAvailableResources(list: List<MovableResource>): MutableList<MovableResource> {
    val rList = mutableListOf<MovableResource>()
    for (ru in list) {
        if (ru.hasAvailableUnits) {
            rList.add(ru)
        }
    }
    return rList
}

open class MovableResourcePool(
    parent: ModelElement,
    movableResources: List<MovableResource>,
    defaultVelocity: RandomIfc,
    name: String? = null
) : ModelElement(parent, name), VelocityIfc {
    protected val myResources: MutableList<MovableResource> = mutableListOf()

    val resources: List<ResourceCIfc>
        get() = myResources

    init {
        for (r in movableResources) {
            addResource(r)
        }
    }

    /** Makes the specified number of movable resources and includes them in the pool.
     *
     * @param parent the parent model element
     * @param numResources number of movable resources to include in the pool
     * @param initLocation the initial starting location of the resources within the spatial model
     * @param defaultVelocity the default velocity for movement within the spatial model
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(
        parent: ModelElement,
        numResources: Int = 1,
        initLocation: LocationIfc,
        defaultVelocity: RandomIfc,
        name: String? = null
    ) : this(
        parent, mutableListOf(), defaultVelocity, name
    ) {
        for (i in 1..numResources) {
            addResource(MovableResource(this, initLocation, defaultVelocity, "${this.name}:R${i}"))
        }
    }

    protected val myVelocity = RandomVariable(this, defaultVelocity)
    val velocityRV: RandomSourceCIfc
        get() = myVelocity
    override val velocity: GetValueIfc
        get() = myVelocity

    protected val resourcesByName = mutableMapOf<String, MovableResource>()

    protected val myNumBusy: AggregateTWResponse = AggregateTWResponse(this, "${this.name}:NumBusy")
    val numBusyUnits: TWResponseCIfc
        get() = myNumBusy

    protected val myFractionBusy: Response = Response(this, name = "${this.name}:FractionBusy")
    val fractionBusyUnits: ResponseCIfc
        get() = myFractionBusy

    //TODO this is where the resource selection and allocation rules are defined/set

    var defaultResourceSelectionRule: MovableResourceSelectionRuleIfc? = null
    var defaultResourceAllocationRule: MovableResourceAllocationRuleIfc? = null

    /**
     *  Adds a resource to the pool. The model must not be running when adding a resource.
     *  @param resource the resource to add
     */
    fun addResource(resource: MovableResource) {
        require(model.isNotRunning) { "The model must not be running when adding a resource to pool (${this.name}" }
        // prevent duplicates in the resources
        if (myResources.contains(resource)) {
            return
        }
        myResources.add(resource)
        myNumBusy.observe(resource.numBusyUnits)
        resourcesByName[resource.name] = resource
        resource.velocityRV.initialRandomSource = myVelocity
    }

    val numAvailableUnits: Int
        get() {
            var sum = 0
            for (r in myResources) {
                sum = sum + r.numAvailableUnits
            }
            return sum
        }

    val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    val capacity: Int
        get() {
            var sum = 0
            for (r in myResources) {
                sum = sum + r.capacity
            }
            return sum
        }

    val numBusy: Int
        get() {
            var sum = 0
            for (r in myResources) {
                sum = sum + r.numBusy
            }
            return sum
        }

    val fractionBusy: Double
        get() {
            return if (capacity == 0) {
                0.0
            } else {
                numBusy.toDouble() / capacity.toDouble()
            }
        }

    override fun initialize() {
        require(myResources.isNotEmpty()) { "There were no resources in resource pool ${this.name} during initialization" }
    }

    override fun replicationEnded() {
        val avgNR = myNumBusy.withinReplicationStatistic.weightedAverage
        val avgMR = capacity
        if (avgMR > 0.0) {
            myFractionBusy.value = avgNR / avgMR
        }
    }

    /**
     * @return returns a list of idle resources. It may be empty.
     */
    fun findIdleResources(): List<MovableResource> {
        return findIdleResources(myResources)
    }

    /**
     * @return returns a list of movable resources that have available capacity. It may be empty.
     */
    fun findAvailableResources(): List<MovableResource> {
        return findAvailableResources(myResources)
    }


    /** For use, before calling allocate()
     *
     * @param resourceSelectionRule the resource selection rule to use for selecting the resources.
     * The null rule case just checks if any are available.
     * @return true if and only if resources can be selected according to the current resource selection rule
     * that will have sufficient amount available to fill the request
     */
    fun canAllocate(resourceSelectionRule: MovableResourceSelectionRuleIfc?): Boolean {
        // this causes the selection rule to be invoked to see if movable resources are available
        return selectMovableResources(resourceSelectionRule).isNotEmpty()
    }

    /** Uses the pool's resource selection rule to select resources from those
     *  that are available that have enough units available to satisfy the request in full.
     *  If there are insufficient resources in the pool to satisfy the full amount, then
     *  the returned list will be empty.  In general, the returned list may have more
     *  units available than the requested amount.
     *
     * @param resourceSelectionRule the resource selection rule to use for selecting the resources
     * The null rule case just checks if any are available.
     * @return a list, which may be empty, that has resources that can satisfy the requested amount
     */
    protected open fun selectMovableResources(
        resourceSelectionRule: MovableResourceSelectionRuleIfc?
    ): List<MovableResource> {
        val availableResources = findAvailableResources()
        return resourceSelectionRule?.selectMovableResources(availableResources) ?: availableResources
    }

    /**
     *  @param requestLocation the location of the request
     *  @param resourceAllocationRule the rule to use to select a movable resource. If null, then
     *  the first available will be selected for allocation
     *  @param resourceList the list of movable resources to select from. This list must not be empty.
     */
    protected open fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc,
        resourceAllocationRule: MovableResourceAllocationRuleIfc?,
        resourceList: List<MovableResource>
    ): MovableResource {
        require(resourceList.isNotEmpty()) { "There must be at least one movable resource available to make an allocation" }
        // this is where the allocation rule is applied
        return resourceAllocationRule?.selectMovableResourceForAllocation(requestLocation, resourceList) ?: resourceList.first()
    }

    /**
     * It is an error to attempt to allocate a movable resource unit to an entity if there are insufficient
     * units available at the time of allocation.
     *
     * @param entity the entity that is requesting the units
     * @param allocationName an optional name for the allocation
     * @param queue the queue associated with the allocation.  That is, where the entities would have had
     * to wait if the allocation was not immediately filled
     * @param resourceSelectionRule The rule to use to select resources to allocate from
     * @param resourceAllocationRule The rule to use to determine the resources to allocate from given the selected resources
     * @return an allocation representing that the units have been allocated to the entity. The reference
     * to this allocation is necessary in order to deallocate the allocated units.
     */
    fun allocate(
        entity: ProcessModel.Entity,
        requestLocation: LocationIfc,
        queue: RequestQ,
        resourceSelectionRule: MovableResourceSelectionRuleIfc?,
        resourceAllocationRule: MovableResourceAllocationRuleIfc?,
        allocationName: String? = null
    ): Allocation {
        // This causes both the selection rule and the allocation rule to be invoked
        require(hasAvailableUnits) { "The pool must have available units in order to allocate." }
        // this should select enough movable resources to meet the request based on how much they have available
        // guaranteed to select some available movable resources because of the previous check
        val list = selectMovableResources(resourceSelectionRule)
        // just in case make sure that the list has at least one movable resource
        check(list.isNotEmpty()) { "There were no movable resources selected to allocate using the current selection rule" }
        ProcessModel.logger.trace { "There were ${list.size} movable resources selected that can be allocated to the request, using the current selection rule." }
        // now determine the movable resource to allocate
        val movableResource = selectMovableResourceForAllocation(requestLocation, resourceAllocationRule, list)
        ProcessModel.logger.trace { "Movable Resource ${movableResource.name} allocated from the pool $name." }
        return movableResource.allocate(entity, 1, queue, allocationName)
    }

    fun deallocate(allocation: Allocation) {
        ProcessModel.logger.trace { "Movable Resource Pool $name is deallocating from resource ${allocation.myResource.name}" }
        allocation.myResource.deallocate(allocation)
    }

}