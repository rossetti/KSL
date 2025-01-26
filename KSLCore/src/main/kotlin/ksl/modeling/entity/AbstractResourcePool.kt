package ksl.modeling.entity

import ksl.modeling.variable.AggregateTWResponse
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement

abstract class AbstractResourcePool(
    parent: ModelElement,
    poolResources: List<Resource>,
    name: String? = null
) : ModelElement(parent, name) {

    /**
     *  Tracks which queues have requests targeting the resource pool
     */
    internal val myQueueSet = mutableListOf<RequestQ>()

    /**
     *  The resources that the resource pool contains
     */
    protected val myResources: MutableList<Resource> = mutableListOf()
    protected val myNumBusy: AggregateTWResponse = AggregateTWResponse(this, "${this.name}:NumBusy")
    val numBusyUnits: TWResponseCIfc
        get() = myNumBusy
    protected val myFractionBusy: Response = Response(this, name = "${this.name}:FractionBusy")
    val fractionBusyUnits: ResponseCIfc
        get() = myFractionBusy
    val resources: List<ResourceCIfc>
        get() = myResources
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

    /**
     *  Adds a resource to the pool. The model must not be running when adding a resource.
     *  @param resource the resource to add
     */
    abstract fun addResource(resource: Resource)

    override fun replicationEnded() {
        val avgNR = myNumBusy.withinReplicationStatistic.weightedAverage
        val avgMR = capacity
        if (avgMR > 0.0) {
            myFractionBusy.value = avgNR / avgMR
        }
    }
}