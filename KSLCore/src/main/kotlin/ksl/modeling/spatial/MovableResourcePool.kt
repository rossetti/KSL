package ksl.modeling.spatial

import ksl.modeling.entity.*
import ksl.modeling.variable.*
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.randomlySelect


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
    fun selectMovableResources(list: List<MovableResource>): MutableList<MovableResource>
}

/**
 *  Function to determine which movable resource should be allocated to
 *  a request. The function provides the location of the request to allow
 *  distance based criteria to be used.
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
        resourceList: MutableList<MovableResource>
    ): MovableResource
}

/**
 *  Determines movable resource that is closest to the request location
 */
class ClosestMovableResourceAllocationRule : MovableResourceAllocationRuleIfc {

    override fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc,
        resourceList: MutableList<MovableResource>
    ): MovableResource {
        require(resourceList.isNotEmpty()){ "The supplied list of movable resources was empty" }
        resourceList.distancesTo(requestLocation)
        resourceList.sortBy { it.selectionCriteria }
        return resourceList.first()
    }

}

/**
 *  Determines movable resource that is furthest from the request location
 */
class FurthestMovableResourceAllocationRule : MovableResourceAllocationRuleIfc {

    override fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc,
        resourceList: MutableList<MovableResource>
    ): MovableResource {
        require(resourceList.isNotEmpty()){ "The supplied list of movable resources was empty" }
        resourceList.distancesTo(requestLocation)
        resourceList.sortByDescending { it.selectionCriteria }
        return resourceList.first()
    }

}

/**
 *  This rule randomly picks from a list of movable resources that can satisfy the request.
 *  @param stream the stream to use for randomness
 */
class RandomMovableResourceAllocationRule(val stream: RNStreamIfc) : MovableResourceAllocationRuleIfc {

    /**
     *  This rule randomly picks from a list of movable resources that can satisfy the request.
     *  @param streamNum the stream number of the stream to use for randomness
     */
    constructor(streamNum: Int) : this(KSLRandom.rnStream(streamNum))

    override fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc, resourceList: MutableList<MovableResource>): MovableResource {
        require(resourceList.isNotEmpty()){ "The supplied list of movable resources was empty" }
        return resourceList.randomlySelect(stream)
    }
}

/**
 * The default is to allocate all available from each resource until amount needed is met
 * in the order in which the resources are listed within the list.
 */
class MovableResourceAllocateInOrderListedRule : MovableResourceAllocationRuleIfc {
    override fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc, resourceList: MutableList<MovableResource>): MovableResource {
        require(resourceList.isNotEmpty()){ "The supplied list of movable resources was empty" }
        return resourceList.first()
    }
}

/**
 *  This rule will sort the list according to the comparator and then allocate the first element
 */
open class MovableResourceAllocationRule(var comparator: Comparator<in MovableResource>) : MovableResourceAllocationRuleIfc {
    override fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc, resourceList: MutableList<MovableResource>): MovableResource {
        require(resourceList.isNotEmpty()){ "The supplied list of movable resources was empty" }
        resourceList.sortWith(comparator)
        return resourceList.first()
    }
}

/**
 *  This rule sorts the resources such that list is ordered from least to most utilized and
 *  then allocates the first element
 */
class LeastUtilizedMovableResourceAllocationRule : MovableResourceAllocationRule(LeastUtilizedComparator())

/**
 * This rule sorts the resources such that this is ordered from least seized to most seized and
 * then allocates the first element
 */
class LeastSeizedMovableResourceAllocationRule : MovableResourceAllocationRule(LeastSeizedComparator())

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

/**
 *  Returns a list of movable resources that are available for allocation. If the returned list is empty, this means that
 *  there were no movable resources available.  It is
 *  important to note that the returned list may have more units available than requested.
 *  Resource allocation rules are used to select from the returned list to specify which of the
 *  list of resources may be allocated to meet the request.  This rule selects all that
 *  are available.
 *
 */
class MovableResourceSelectionRule : MovableResourceSelectionRuleIfc {
    override fun selectMovableResources(list: List<MovableResource>): MutableList<MovableResource> {
        if (list.isEmpty()) {
            return mutableListOf()
        }
        val rList = mutableListOf<MovableResource>()
        for (resource in list) {
            if (resource.numAvailableUnits == 0) {
                continue
            } else {
                rList.add(resource)
            }
        }
        return rList
    }

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

/**
 *  Computes and assigns the distance to the provided location from the current location of the resource for
 *  each resource. The distance is assigned to the resource's sectionCriteria attribute.
 *  This mutates elements of the list.
 *
 *  @param location the location
 */
fun List<MovableResource>.distancesTo(location: LocationIfc){
    for(m in this) {
        m.selectionCriteria = m.distanceTo(location)
    }
}

/**
 * A MovableResourcePool represents a list of movable resources from which
 * resources can be selected to fill requests made by Entities.
 *
 * Resources are selected according to a ResourceSelectionRule.
 * The assumption is that any of the resources
 * within the pool may be used to fill the request.
 *
 * If no selection rule is supplied the pool selects a list of resources
 * that can fully satisfy the request.  The default allocation rule is to allocate
 * the movable resource that is closest to the entity.
 *
 * If no default rules are supplied, the first available movable resource
 * is selected based on the order of the resources supplied.
 *
 * @param parent the parent model element
 * @param movableResources a list of resources to be included in the pool
 * @param defaultVelocity the default velocity fore all resources to use.
 * @param name the name of the pool
 * @author rossetti
 */
open class MovableResourcePool(
    parent: ModelElement,
    movableResources: List<MovableResource>,
    defaultVelocity: RandomIfc,
    name: String? = null
) : ModelElement(parent, name), VelocityIfc {

    protected val myNumBusy: AggregateTWResponse = AggregateTWResponse(this, "${this.name}:NumBusy")
    val numBusyUnits: TWResponseCIfc
        get() = myNumBusy

    protected val myFractionBusy: Response = Response(this, name = "${this.name}:FractionBusy")
    val fractionBusyUnits: ResponseCIfc
        get() = myFractionBusy

    protected val myResources: MutableList<MovableResource> = mutableListOf()

    protected val myResourcesByName = mutableMapOf<String, MovableResource>()

    val resourcesByName: Map<String, MovableResource>
        get() = myResourcesByName

    val resources: List<ResourceCIfc>
        get() = myResources

    protected val myVelocity = RandomVariable(this, defaultVelocity)
    val velocityRV: RandomSourceCIfc
        get() = myVelocity
    override val velocity: GetValueIfc
        get() = myVelocity

    //TODO this is where the resource selection and allocation rules are defined/set

    var defaultMovableResourceSelectionRule: MovableResourceSelectionRuleIfc = MovableResourceSelectionRule()
    var defaultMovableResourceAllocationRule: MovableResourceAllocationRuleIfc = ClosestMovableResourceAllocationRule()

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

    /**
     *  Adds a movable resource to the pool. The model must not be running when adding a resource.
     *  @param resource the movable resource to add
     */
    fun addResource(resource: MovableResource) {
        require(model.isNotRunning) { "The model must not be running when adding a resource to pool (${this.name}" }
        // prevent duplicates in the resources
        if (myResources.contains(resource)) {
            return
        }
        myResources.add(resource)
        myNumBusy.observe(resource.numBusyUnits)
        myResourcesByName[resource.name] = resource
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
    fun canAllocate(resourceSelectionRule: MovableResourceSelectionRuleIfc): Boolean {
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
        resourceSelectionRule: MovableResourceSelectionRuleIfc
    ): MutableList<MovableResource> {
        val availableResources = findAvailableResources()
        return resourceSelectionRule.selectMovableResources(availableResources)
    }

    /**
     *  @param requestLocation the location of the request
     *  @param resourceAllocationRule the rule to use to select a movable resource. If null, then
     *  the first available will be selected for allocation
     *  @param resourceList the list of movable resources to select from. This list must not be empty.
     */
    protected open fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc,
        resourceAllocationRule: MovableResourceAllocationRuleIfc,
        resourceList: MutableList<MovableResource>
    ): MovableResource {
        require(resourceList.isNotEmpty()) { "There must be at least one movable resource available to make an allocation" }
        // this is where the allocation rule is applied
        return resourceAllocationRule.selectMovableResourceForAllocation(requestLocation, resourceList)
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
        resourceSelectionRule: MovableResourceSelectionRuleIfc,
        resourceAllocationRule: MovableResourceAllocationRuleIfc,
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