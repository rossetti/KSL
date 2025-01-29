package ksl.modeling.entity

/**
 * An allocation listener is notified whenever the resource is allocated and when the resource
 * is deallocated. This allows general actions to occur when the resource's state changes
 * at these instances in time.
 */
interface AllocationListenerIfc {

    /**
     * @param allocation the allocation that was allocated
     */
    fun allocate(allocation: Allocation)

    /**
     * @param allocation the allocation that was deallocated
     */
    fun deallocate(allocation: Allocation)
}

/**
 *  The ordering is determined such that more available units "rise to the top",
 *  then by least number times seized, then by oldest time last busy
 */
class MostAvailableComparator : Comparator<Resource> {
    override fun compare(r1: Resource, r2: Resource): Int {
        if (r1.numAvailableUnits > r2.numAvailableUnits) {
            return -1
        }
        if (r1.numAvailableUnits < r2.numAvailableUnits) {
            return 1
        }

        if (r1.numTimesSeized < r2.numTimesSeized) {
            return -1
        }
        if (r1.numTimesSeized > r2.numTimesSeized) {
            return 1
        }
        // number of seizes was the same. if exited earlier, then prefer it
        return (r1.busyState.timeStateExited.compareTo(r2.busyState.timeStateExited))
    }

}

/**
 *  The number of times the resource was seized is used to determine the ordering.
 *  The less the number the smaller. If the number of times seized is equal, then
 *  the resource with the earliest time exiting the busy state is considered smaller.
 *  That is the one furthest back in time that the busy state was exited.
 */
class LeastSeizedComparator : Comparator<Resource> {
    override fun compare(r1: Resource, r2: Resource): Int {
        if (r1.numTimesSeized < r2.numTimesSeized) {
            return -1
        }
        if (r1.numTimesSeized > r2.numTimesSeized) {
            return 1
        }
        // number of seizes was the same. if exited earlier, then prefer it
        return (r1.busyState.timeStateExited.compareTo(r2.busyState.timeStateExited))
    }

}

/*
  The resource with smaller estimated instantaneous utilization is considered smaller. If there is a tie
  then the resource that has been seized fewer times is smaller. If there still is a tie
 *  then the resource with the earliest time exiting the busy state is considered smaller.
 *  That is the one furthest back in time that the busy state was exited.
 */
class LeastUtilizedComparator : Comparator<Resource> {
    override fun compare(r1: Resource, r2: Resource): Int {
        val u1 = r1.timeAvgInstantaneousUtil.withinReplicationStatistic.weightedAverage
        val u2 = r2.timeAvgInstantaneousUtil.withinReplicationStatistic.weightedAverage

        if (u1 < u2) {
            return -1
        }
        if (u1 > u2) {
            return 1
        }

        if (r1.numTimesSeized < r2.numTimesSeized) {
            return -1
        }
        if (r1.numTimesSeized > r2.numTimesSeized) {
            return 1
        }
        // number of seizes was the same. if exited earlier, then prefer it
        return (r1.busyState.timeStateExited.compareTo(r2.busyState.timeStateExited))
    }

}

/**
 *  Compares the resources based on the number available
 */
class NumAvailableComparator : Comparator<Resource> {
    override fun compare(r1: Resource, r2: Resource): Int {
        // number of seizes was the same. if exited earlier, then prefer it
        return (r1.numAvailableUnits.compareTo(r2.numAvailableUnits))
    }
}

/**
 *  Compares the resources based on the value of the selection criteria attribute
 */
class SelectionCriteriaComparator : Comparator<Resource> {
    override fun compare(r1: Resource, r2: Resource): Int {
        // number of seizes was the same. if exited earlier, then prefer it
        return (r1.selectionCriteria.compareTo(r2.selectionCriteria))
    }
}