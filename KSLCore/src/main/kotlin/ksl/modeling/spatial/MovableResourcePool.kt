package ksl.modeling.spatial

import ksl.modeling.entity.*
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.random.RandomIfc

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
) : AbstractResourcePool<MovableResource>(parent, name), VelocityIfc {

    protected val myResourcesByName = mutableMapOf<String, MovableResource>()

    val resourcesByName: Map<String, MovableResource>
        get() = myResourcesByName

    init {
        for (r in movableResources) {
            addResource(r)
        }
    }

    /**
     *  Adds a movable resource to the pool. The model must not be running when adding a resource.
     *  @param resource the movable resource to add
     */
    final override fun addResource(resource: MovableResource) {
        super.addResource(resource)
        myResourcesByName[resource.name] = resource
        resource.velocityRV.initialRandomSource = myVelocity
        resource.myMovableResourcePools.add(this)
    }

    protected val myVelocity = RandomVariable(this, defaultVelocity)
    val velocityRV: RandomSourceCIfc
        get() = myVelocity
    override val velocity: GetValueIfc
        get() = myVelocity

    var initialDefaultMovableResourceSelectionRule : MovableResourceSelectionRuleIfc = MovableResourceSelectionRule()
        set(value) {
            require(model.isNotRunning) {"Changing the initial resource selection rule during a replication will cause replications to not have the same starting conditions"}
            field = value
        }

    var defaultMovableResourceSelectionRule: MovableResourceSelectionRuleIfc = initialDefaultMovableResourceSelectionRule
        set(value) {
            field = value
            if (model.isRunning){
                Model.logger.warn { "Changing the initial resource selection rule during a replication will only effect the current replication." }
            }
        }

    var initialDefaultMovableResourceAllocationRule: MovableResourceAllocationRuleIfc =
        ClosestMovableResourceAllocationRule()
        set(value) {
            require(model.isNotRunning) {"Changing the initial resource allocation rule during a replication will cause replications to not have the same starting conditions"}
            field = value
        }

    var defaultMovableResourceAllocationRule: MovableResourceAllocationRuleIfc = initialDefaultMovableResourceAllocationRule
        set(value) {
            field = value
            if (model.isRunning){
                Model.logger.warn { "Changing the initial resource allocation rule during a replication will only effect the current replication." }
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

    override fun initialize() {
        require(myResources.isNotEmpty()) { "There were no resources in resource pool ${this.name} during initialization" }
        defaultMovableResourceAllocationRule = initialDefaultMovableResourceAllocationRule
        defaultMovableResourceSelectionRule = initialDefaultMovableResourceSelectionRule
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