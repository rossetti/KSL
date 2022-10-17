package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement

class ResourcePoolWithQ(
    parent: ModelElement,
    resources: List<Resource>,
    queue: RequestQ? = null,
    name: String? = null
) : ResourcePool(parent, resources, name) {

    /** Makes the specified number of single unit resources and includes them in the pool.
     *
     * @param parent the parent model element
     * @param numResources number of single unit resources to include in the pool
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(
        parent: ModelElement,
        numResources: Int = 1,
        queue: RequestQ? = null,
        name: String? = null
    ) : this(
        parent,
        mutableListOf(),
        queue,
        name
    ) {
        for (i in 1..numResources) {
            addResource(Resource(this, "${this.name}:R${i}"))
        }
    }

    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    internal val myWaitingQ: RequestQ

    init {
        myWaitingQ = queue ?: RequestQ(this, "${this.name}:Q")
    }

    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

}