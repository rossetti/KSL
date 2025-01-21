package ksl.modeling.spatial

import ksl.modeling.entity.*
import ksl.modeling.variable.RandomSourceCIfc
import ksl.modeling.variable.RandomVariable
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
    fun selectResources(list: List<MovableResource>): List<MovableResource>
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
    fun makeAllocations(
        requestLocation: LocationIfc,
        resourceList: List<MovableResource>
    ): Map<MovableResource, Int>
}

/**
 * A MovableResourcePool represents a list of MovableResource from which
 * resources can be selected to fill requests made by Entities.
 *
 * Resources are selected according to a ResourceSelectionRule.
 * The assumption is that any of the resources
 * within the pool may be used to fill the request.
 *
 * If no selection rule is supplied the pool selects a list of resources
 * that can fully satisfy the request and makes allocations to the resources based on
 * the order in which they are listed in the pool.
 *
 * @param parent the parent model element
 * @param movableResources a list of resources to be included in the pool
 * @param name the name of the pool
 * @author rossetti
 */
class MovableResourcePool(
    parent: ModelElement,
    movableResources: List<MovableResource>,
    defaultVelocity: RandomIfc,
    name: String? = null
) : ResourcePool(parent, movableResources, name), VelocityIfc {
    //TODO cannot sub-class from ResourcePool anymore
    // ISSUES:
    // a) allows Resource to be added but they are not movable
    // b) does not have movable resource selection or allocation defaults
    // c) If not subclass, then Request will not work correctly
    //

    protected val myVelocity = RandomVariable(this, defaultVelocity)
    val velocityRV: RandomSourceCIfc
        get() = myVelocity
    override val velocity: GetValueIfc
        get() = myVelocity
    private val resourcesByName = mutableMapOf<String, MovableResource>()

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
    ) : this(parent, mutableListOf(), defaultVelocity, name
    ) {
        for (i in 1..numResources) {
            addResource(MovableResource(this, initLocation, defaultVelocity, "${this.name}:R${i}"))
        }
    }

    fun addResource(resource: MovableResource) {
        super.addResource(resource)
        resourcesByName[resource.name] = resource
        resource.velocityRV.initialRandomSource = myVelocity
    }

    fun resourceByName(name: String): MovableResource?{
        return resourcesByName[name]
    }

    fun canAllocate(resourceSelectionRule: MovableResourceSelectionRuleIfc) : Boolean {
        //TODO this causes the selection rule to be invoked to see if resources are available
        //resourceSelectionRule.
        TODO("Not implemented yet")
    }

    internal fun allocate(
        entity: ProcessModel.Entity,
        queue: RequestQ,
        resourceSelectionRule: MovableResourceSelectionRuleIfc,
        resourceAllocationRule: MovableResourceAllocationRuleIfc,
        allocationName: String? = null
    ) : ResourcePoolAllocation {
        //TODO This causes both the selection rule and the allocation rule to be invoked
        TODO("Not implemented yet")
    }


}