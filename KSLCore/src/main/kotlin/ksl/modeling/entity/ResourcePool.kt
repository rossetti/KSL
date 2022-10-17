package ksl.modeling.entity

import ksl.modeling.variable.AggregateTWResponse
import ksl.simulation.ModelElement

interface ResourceSelectionRuleIfc {
    /**
     * @param list of resources to consider selecting from
     * @return the selected list of resources. It may be empty
     */
    fun selectResources(amountNeeded: Int, list: List<Resource>): List<Resource>
}

/**
 *  Returns the first resource that can fully meet the amount needed
 */
class DefaultResourceSelectionRule : ResourceSelectionRuleIfc {
    override fun selectResources(amountNeeded: Int, list: List<Resource>): List<Resource> {
        require(amountNeeded >= 1){"The amount needed must be >= 1"}
        val rList = mutableListOf<Resource>()
        for (r in list) {
            if (r.numAvailableUnits >= amountNeeded) {
                rList.add(r)
                break
            }
        }
        return rList
    }
}

/**
 * @return returns a list of idle resources. It may be empty.
 */
fun findIdleResources(list: List<Resource>): List<Resource> {
    val rList = mutableListOf<Resource>()
    for (ru in list) {
        if (ru.isIdle) {
            rList.add(ru)
        }
    }
    return rList
}

/**
 * @return returns a list of resources that have available capacity. It may be empty.
 */
fun findAvailableResources(list: List<Resource>): List<Resource> {
    val rList = mutableListOf<Resource>()
    for (ru in list) {
        if (ru.hasAvailableUnits) {
            rList.add(ru)
        }
    }
    return rList
}

/**
 * A ResourcePool represents a list of Resources from which
 * resources can be selected to fill requests made by Entities.
 *
 * Resources are selected according to a ResourceSelectionRule.
 * The assumption is that any of the resources
 * within the pool may be used to fill the request.
 *
 * If no selection rule is supplied the pool selects the first idle resource
 * that can fully satisfy the request by default.
 *
 * @param parent the parent model element
 * @param resources a list of resources to be included in the pool
 * @param name the name of the pool
 * @author rossetti
 */
open class ResourcePool(parent: ModelElement, resources: List<Resource>, name: String? = null) : ModelElement(parent, name) {
    private val myNumBusy: AggregateTWResponse = AggregateTWResponse(this, "${this.name}:NumBusy")
    private val myResources: MutableList<Resource> = mutableListOf()
    val resources: List<Resource>
        get() = myResources.toList()

    var resourceSelectionRule : ResourceSelectionRuleIfc = DefaultResourceSelectionRule()

    init {
        for(r in resources){
            addResource(r)
        }
    }

    /** Makes the specified number of single unit resources and includes them in the pool.
     *
     * @param parent the parent model element
     * @param numResources number of single unit resources to include in the pool
     * @param name the name of the pool
     * @author rossetti
     */
    constructor(parent: ModelElement, numResources: Int = 1, name: String? = null) : this(
        parent,
        mutableListOf(),
        name
    ) {
        for (i in 1..numResources) {
            addResource(Resource(this, "${this.name}:R${i}"))
        }
    }

    protected fun addResource(resource: Resource){
        myResources.add(resource)
        myNumBusy.observe(resource.numBusyUnits)
        //TODO stat stuff such as utilization
    }

    val numAvailableUnits: Int
        get() {
            var sum = 0
            for (r in myResources){
                sum = sum + r.numAvailableUnits
            }
            return sum
        }

    val hasAvailableUnits: Boolean
        get() = numAvailableUnits > 0

    val capacity: Int
        get() {
            var sum = 0
            for (r in myResources){
                sum = sum + r.capacity
            }
            return sum
        }

    val fractionBusy: Double
        get() = myNumBusy.value / capacity

    /**
     * @return returns a list of idle resources. It may be empty.
     */
    fun findIdleResources(): List<Resource> {
        return findIdleResources(myResources)
    }

    /**
     * @return returns a list of resources that have available capacity. It may be empty.
     */
    fun findAvailableResources(): List<Resource> {
        return findAvailableResources(myResources)
    }

    /**
     * @param amountNeeded the amount needed by a request
     * @return a list, which may be empty, that has resources that can satisfy the requested amount
     */
    fun selectResources(amountNeeded: Int) : List<Resource>{
        return resourceSelectionRule.selectResources(amountNeeded, myResources)
    }

}