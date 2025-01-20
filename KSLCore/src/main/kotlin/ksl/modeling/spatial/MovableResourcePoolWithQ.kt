package ksl.modeling.spatial

import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourcePool
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.variable.RandomSourceCIfc
import ksl.modeling.variable.RandomVariable
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.random.RandomIfc

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
class MovableResourcePoolWithQ(
    parent: ModelElement,
    movableResources: List<MovableResource>,
    defaultVelocity: RandomIfc,
    queue: RequestQ? = null,
    name: String? = null
) : ResourcePoolWithQ(parent, movableResources, queue, name), VelocityIfc {
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
     * @param initialCapacity the initial capacity of every movable resource. Must be 0 or 1.
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(
        parent: ModelElement,
        numResources: Int = 1,
        initLocation: LocationIfc,
        defaultVelocity: RandomIfc,
        initialCapacity : Int = 1,
        queue: RequestQ? = null,
        name: String? = null
    ) : this(
        parent, mutableListOf(), defaultVelocity, queue, name
    ) {
        require((initialCapacity == 0) || (initialCapacity == 1))
        { "The initial capacity of a movable resource must be 0 or 1" }
        for (i in 1..numResources) {
            addResource(MovableResource(this, initLocation, defaultVelocity,
                initialCapacity, "${this.name}:R${i}"))
        }
    }

    fun addResource(resource: MovableResource) {
        super.addResource(resource)
        resourcesByName[resource.name] = resource
        resource.velocityRV.initialRandomSource = myVelocity
    }

    fun resourceByName(name: String): MovableResource? {
        return resourcesByName[name]
    }
}