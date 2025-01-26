package ksl.modeling.spatial

import ksl.modeling.entity.*
import ksl.modeling.queue.QueueCIfc
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
) : MovableResourcePool(parent, movableResources, defaultVelocity, name), VelocityIfc {
    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    internal val myWaitingQ: RequestQ = queue ?: RequestQ(this, "${this.name}:Q")

    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    val resourcesWithQ: List<ResourceWithQCIfc>
        get() {
            val list = mutableListOf<ResourceWithQCIfc>()
            for(resource in myResources){
                if (resource is MovableResourceWithQ){
                    list.add(resource)
                }
            }
            return list
        }

    /** Makes the specified number of single unit resources and includes them in the pool.
     *  The pool is configured with a queue and each created resource is a ResourceWithQ that
     *  uses the pool's queue.
     *
     * @param parent the parent model element
     * @param numResources number of movable resources to include in the pool
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(
        parent: ModelElement,
        numResources: Int = 1,
        initLocation: LocationIfc,
        defaultVelocity: RandomIfc,
        name: String? = null
    ) : this(parent, mutableListOf(), defaultVelocity, null, name) {
        require(numResources >= 1) {"There must be 1 or more movable resources to create when creating ${this.name}"}
        for (i in 1..numResources) {
            addResource(MovableResourceWithQ(this, initLocation, myVelocity, queue = myWaitingQ, name = "${this.name}:R${i}"))
        }
    }

    /** Makes the specified number of single unit resources and includes them in the pool.
     *  The pool is configured with the supplied queue and each create resource is a ResourceWithQ that
     *  uses the supplied queue.
     *
     * @param parent the parent model element
     * @param numResources number of single unit resources to include in the pool
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(
        parent: ModelElement,
        numResources: Int = 1,
        initLocation: LocationIfc,
        defaultVelocity: RandomIfc,
        queue: RequestQ,
        name: String? = null
    ) : this(parent, mutableListOf(), defaultVelocity, queue, name){
        for (i in 1..numResources) {
            addResource(MovableResourceWithQ(this, initLocation, myVelocity, queue = myWaitingQ, name = "${this.name}:R${i}"))
        }
    }
}