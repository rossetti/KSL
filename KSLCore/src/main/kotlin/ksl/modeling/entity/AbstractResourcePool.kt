package ksl.modeling.entity

import ksl.modeling.variable.AggregateTWResponse
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement

/**
 *  An abstract base class that represents a pool of resources (or subclasses of Resource).
 */
abstract class AbstractResourcePool<T: Resource>(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name), ResourceIfc {

    /**
     *  Tracks which queues have requests targeting the resource pool.
     *  Called internally in RequestQ.registerResources() and RequestQ.unregisterResources()
     */
    internal val myQueueSet = mutableListOf<RequestQ>() //TODO

    /**
     *  The resources that the resource pool contains
     */
    protected val myResources: MutableList<T> = mutableListOf()

    protected val myNumBusy: AggregateTWResponse = AggregateTWResponse(this, "${this.name}:NumBusy")
    val numBusyUnits: TWResponseCIfc
        get() = myNumBusy
    protected val myFractionBusy: Response = Response(this, name = "${this.name}:FractionBusy")
    val fractionBusyUnits: ResponseCIfc
        get() = myFractionBusy
    val resources: List<ResourceCIfc>
        get() = myResources

    /**
     *  The pool is considered in active if all of its associated resources
     *  are inactive
     */
    override val isInactive: Boolean
        get() {
            for (r in myResources) {
                if (!r.isInactive) {
                    return false
                }
            }
            return true
        }

    override val numAvailableUnits: Int
        get() {
            var sum = 0
            for (r in myResources) {
                sum = sum + r.numAvailableUnits
            }
            return sum
        }

    /**
     *  If a(t) is greater than zero
     */
    override val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    override val capacity: Int
        get() {
            var sum = 0
            for (r in myResources) {
                sum = sum + r.capacity
            }
            return sum
        }
    override val numBusy: Int
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

    /**
     *  Adds a resource to the pool. The model must not be running when adding a resource.
     *  @param resource the resource to add
     */
    open fun addResource(resource: T) {
        require(model.isNotRunning) { "The model must not be running when adding a resource to pool (${this.name}" }
        // prevent duplicates in the resources
        if (myResources.contains(resource)) {
            return
        }
        myResources.add(resource)
        myNumBusy.observe(resource.numBusyUnits)
        //TODO consider aggregate state collection
    }

    /**
     * @return returns a list of idle resources. It may be empty.
     */
    @Suppress("unused")
    fun findIdleResources(): List<T> {
        return findIdleResources(myResources)
    }

    /**
     * @return returns a list of resources that have available capacity. It may be empty.
     */
    fun findAvailableResources(): List<T> {
        return findAvailableResources(myResources)
    }

    override fun replicationEnded() {
        val avgNR = myNumBusy.withinReplicationStatistic.weightedAverage
        val avgMR = capacity
        if (avgMR > 0.0) {
            myFractionBusy.value = avgNR / avgMR
        }
    }

    override fun toString(): String {
        return "$name: c(t) = $capacity b(t) = $numBusy a(t) = $numAvailableUnits"
    }
}