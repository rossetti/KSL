package ksl.modeling.spatial

import ksl.modeling.entity.LeastSeizedComparator
import ksl.modeling.entity.LeastUtilizedComparator
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.randomlySelect

/**
 * Provides for a method to select movable resources from a list such that
 * the returned list will contain movable resources that can satisfy the request
 * or the list will be empty.
 */
fun interface MovableResourceSelectionRuleIfc {
    /**
     * @param list of resources to consider selecting from
     * @return the selected list of resources. It may be empty
     */
    fun selectMovableResources(list: List<MovableResource>): MutableList<MovableResource>
}

/**
 *  Function to determine which movable resource should be allocated to
 *  a request. The function provides the location of the request to allow
 *  distance based criteria to be used.
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
    fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc,
        resourceList: MutableList<MovableResource>
    ): MovableResource
}

/**
 *  Determines movable resource that is closest to the request location
 */
class ClosestMovableResourceAllocationRule : MovableResourceAllocationRuleIfc {

    override fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc,
        resourceList: MutableList<MovableResource>
    ): MovableResource {
        require(resourceList.isNotEmpty()){ "The supplied list of movable resources was empty" }
        resourceList.distancesTo(requestLocation)
        resourceList.sortBy { it.selectionCriteria }
        return resourceList.first()
    }

}

/**
 *  Determines movable resource that is furthest from the request location
 */
class FurthestMovableResourceAllocationRule : MovableResourceAllocationRuleIfc {

    override fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc,
        resourceList: MutableList<MovableResource>
    ): MovableResource {
        require(resourceList.isNotEmpty()){ "The supplied list of movable resources was empty" }
        resourceList.distancesTo(requestLocation)
        resourceList.sortByDescending { it.selectionCriteria }
        return resourceList.first()
    }

}

/**
 *  This rule randomly picks from a list of movable resources that can satisfy the request.
 *  @param stream the stream to use for randomness
 */
class RandomMovableResourceAllocationRule(val stream: RNStreamIfc) : MovableResourceAllocationRuleIfc {

    /**
     *  This rule randomly picks from a list of movable resources that can satisfy the request.
     *  @param streamNum the stream number of the stream to use for randomness
     */
    constructor(streamNum: Int) : this(KSLRandom.rnStream(streamNum))

    override fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc, resourceList: MutableList<MovableResource>): MovableResource {
        require(resourceList.isNotEmpty()){ "The supplied list of movable resources was empty" }
        return resourceList.randomlySelect(stream)
    }
}

/**
 * The default is to allocate all available from each resource until amount needed is met
 * in the order in which the resources are listed within the list.
 */
class MovableResourceAllocateInOrderListedRule : MovableResourceAllocationRuleIfc {
    override fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc, resourceList: MutableList<MovableResource>): MovableResource {
        require(resourceList.isNotEmpty()){ "The supplied list of movable resources was empty" }
        return resourceList.first()
    }
}

/**
 *  This rule will sort the list according to the comparator and then allocate the first element
 */
open class MovableResourceAllocationRule(var comparator: Comparator<in MovableResource>) : MovableResourceAllocationRuleIfc {
    override fun selectMovableResourceForAllocation(
        requestLocation: LocationIfc, resourceList: MutableList<MovableResource>): MovableResource {
        require(resourceList.isNotEmpty()){ "The supplied list of movable resources was empty" }
        resourceList.sortWith(comparator)
        return resourceList.first()
    }
}

/**
 *  This rule sorts the resources such that list is ordered from least to most utilized and
 *  then allocates the first element
 */
class LeastUtilizedMovableResourceAllocationRule : MovableResourceAllocationRule(LeastUtilizedComparator())

/**
 * This rule sorts the resources such that this is ordered from least seized to most seized and
 * then allocates the first element
 */
class LeastSeizedMovableResourceAllocationRule : MovableResourceAllocationRule(LeastSeizedComparator())

/**
 *  Returns a list of movable resources that are available for allocation. If the returned list is empty, this means that
 *  there were no movable resources available.  It is
 *  important to note that the returned list may have more units available than requested.
 *  Resource allocation rules are used to select from the returned list to specify which of the
 *  list of resources may be allocated to meet the request.  This rule selects all that
 *  are available.
 *
 */
class MovableResourceSelectionRule : MovableResourceSelectionRuleIfc {
    override fun selectMovableResources(list: List<MovableResource>): MutableList<MovableResource> {
        if (list.isEmpty()) {
            return mutableListOf()
        }
        val rList = mutableListOf<MovableResource>()
        for (resource in list) {
            if (resource.numAvailableUnits == 0) {
                continue
            } else {
                rList.add(resource)
            }
        }
        return rList
    }

}

/**
 *  Computes and assigns the distance to the provided location from the current location of the resource for
 *  each resource. The distance is assigned to the resource's sectionCriteria attribute.
 *  This mutates elements of the list.
 *
 *  @param location the location
 */
fun List<MovableResource>.distancesTo(location: LocationIfc){
    for(m in this) {
        m.selectionCriteria = m.distanceTo(location)
    }
}