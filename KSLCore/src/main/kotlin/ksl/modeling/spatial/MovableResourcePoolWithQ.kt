package ksl.modeling.spatial

import ksl.modeling.entity.*
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.RVariableIfc

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
 * @param defaultVelocity the default velocity for movement within the spatial model
 * @param queue the queue for the pool
 * @param name the name of the pool
 * @author rossetti
 */
class MovableResourcePoolWithQ(
    parent: ModelElement,
    movableResources: List<MovableResource>,
    defaultVelocity: RVariableIfc,
    queue: RequestQ? = null,
    name: String? = null
) : MovableResourcePool(parent, movableResources, defaultVelocity, name), VelocityIfc {

    /**
     *  Creates the required number of movable resources for the pool. All created
     *  movable resources have the same initial starting location. The pool uses
     *  the supplied queue.
     *
     *  @param parent the containing model element
     *  @param numUnits the number of resources to create, must be 1 or more
     *  @param initLocation the initial starting location of the resource within the spatial model
     *  @param defaultVelocity the default velocity for movement within the spatial model
     *  @param queue the queue for the pool
     *  @param name the name of the pool
     */
    constructor(
        parent: ModelElement,
        numUnits: Int,
        initLocation: LocationIfc,
        defaultVelocity: RVariableIfc,
        queue: RequestQ? = null,
        name: String? = null
    ) : this(parent, MovableResource.createMovableResources(parent, numUnits, initLocation, defaultVelocity, name),
        defaultVelocity, queue, name
    )

    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    internal val myWaitingQ: RequestQ = queue ?: RequestQ(this, "${this.name}:Q")

    /**
     *  A trigger for a movable resource of this pool, waiting in its own queue, for use in a wait for the
     *  first of several things (`waitForFirst`). As the form of `MovableResourcePool` that takes a queue.
     *
     *  @param requestLocation the location the request is made from; null means the entity's location
     *  when the wait starts
     *  @param seizePriority the priority of the yield a wait with this trigger makes before it starts
     *  @param resourceSelectionRule the rule that selects the movable resource, as for `seize`
     *  @param resourceAllocationRule the rule that allocates it, as for `seize`
     *  @param recordRenegedWait whether a request that leaves without service records its wait in the
     *  queue's time in queue
     */
    fun seizeTrigger(
        requestLocation: LocationIfc? = null,
        seizePriority: Int = ProcessModel.SEIZE_PRIORITY,
        resourceSelectionRule: MovableResourceSelectionRuleIfc = defaultMovableResourceSelectionRule,
        resourceAllocationRule: MovableResourceAllocationRuleIfc = defaultMovableResourceAllocationRule,
        recordRenegedWait: Boolean = true
    ): WaitTrigger<Allocation> = seizeTrigger(
        myWaitingQ, requestLocation, seizePriority, resourceSelectionRule, resourceAllocationRule, recordRenegedWait
    )

    init {
        for (resource in myResources) {
            resource.registerCapacityChangeQueue(myWaitingQ)
        }
    }

    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    val resourcesWithQ: List<ResourceWithQCIfc>
        get() {
            val list = mutableListOf<ResourceWithQCIfc>()
            for (resource in myResources) {
                if (resource is MovableResourceWithQ) {
                    list.add(resource)
                }
            }
            return list
        }

}