package ksl.modeling.spatial

import ksl.modeling.entity.*
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.AggregateTWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc

/**
 * A movable resource is a single unit capacity resource that resides within a spatial model and thus can be moved.
 *
 *  A movable resource is considered idle if it has not allocated. The capacity can be changed during a replication; however,
 *  the capacity of every replication starts at the same initial capacity. A movable resource
 *  can have capacity of 0 or 1.
 *
 *  A movable resource is inactive if its capacity is zero.  Capacity can only become 0 via the use of a CapacitySchedule or
 *  via the use of a CapacityChangeNotice.  A movable resource that is inactive can be seized.  If a request for the
 *  movable resource occurs when the resource is inactive, the request waits (as usual) until it can be fulfilled.
 *
 *  Define b(t) as the number of units allocated and c(t) as the current capacity of the resource at time t.
 *
 *  If (b(t) = 0 and c(t) = 0) then the resource is considered inactive
 *  If b(t) = 1 and c(t) >= 0, then the resource is busy
 *  If b(t) = 0 and c(t) = 1, then the resource is idle
 *
 *  Note that a resource may be busy when the capacity is 0 because of the timing of capacity changes.
 * @param parent the parent model element
 * @param initLocation the initial starting location of the resource within the spatial model
 * @param defaultVelocity the default velocity for movement within the spatial model
 * @param name the name of the resource
 */
class MovableResourceWithQ(
    parent: ModelElement,
    initLocation: LocationIfc,
    defaultVelocity: RandomIfc,
    queue: RequestQ? = null,
    name: String? = null,
) : MovableResource(parent, initLocation, defaultVelocity, name), ResourceWithQCIfc{

    /**
     * Holds the entities that are waiting for allocations of the resource's units
     */
    internal val myWaitingQ: RequestQ = queue ?: RequestQ(this, "${this.name}:Q")
    init {
        registerCapacityChangeQueue(myWaitingQ)
    }
    override val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    /**
     *  The number waiting plus number in service: Q(t) + B(t)
     */
    protected val myWIP = AggregateTWResponse(this, "${this.name}:WIP")

    override val wip: TWResponseCIfc
        get() = myWIP

    init {
        myWIP.observe(myWaitingQ.numInQ)
        myWIP.observe(myNumBusy)
    }

    override var defaultReportingOption: Boolean
        get() = super.defaultReportingOption
        set(value) {
            super.defaultReportingOption = value
            myWaitingQ.defaultReportingOption = value
        }


    override fun toString(): String {
        return super.toString() + " q(t) = ${myWaitingQ.numInQ.value}"
    }

    companion object {
        /**
         *  Creates the required number of movable resources that have their own queues.
         * @param parent the containing model element
         * @param numToCreate the number of resources to create, must be 1 or more
         * @param initLocation the initial starting location of the resource within the spatial model
         * @param defaultVelocity the default velocity for movement within the spatial model
         */
        fun createMovableResourcesWithQueues(
            parent: ModelElement,
            numToCreate: Int,
            initLocation: LocationIfc,
            defaultVelocity: RandomIfc,
        ): List<MovableResource> {
            require(numToCreate >= 1) { "The initial numToCreate must be >= 1" }
            val list = mutableListOf<MovableResource>()
            for (i in 1..numToCreate) {
                list.add(MovableResourceWithQ(parent, initLocation, defaultVelocity, name = "${parent.name}:R${i}"))
            }
            return list
        }
    }
}