package ksl.modeling.entity

import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.DefaultReportingOptionIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc
import ksl.utilities.statistic.StateAccessorIfc

interface ResourceCIfc : DefaultReportingOptionIfc {

    /**
     * The initial capacity of the resource at the start of the replication. The initial
     * capacity must be greater than 0.
     */
    var initialCapacity: Int

    /**
     *  The current capacity of the resource. In general, it can be 0 or greater
     */
    val capacity: Int

    /**
     *  Access to the busy state. Busy means at least 1 unit of the resource is allocated.
     */
    val busyState: StateAccessorIfc

    /**
     *  Access to the idle state. Idle means that no units of the resource are allocated.
     */
    val idleState: StateAccessorIfc

    /**
     * Access to the inactive state. Inactive means that the capacity of the resource is 0
     */
    val inactiveState: StateAccessorIfc

    /**
     *  Indicates if proportion of time spent in states (idle, busy, inactive) is automatically reported
     */
    val stateReportingOption: Boolean

    /**
     *  The current state of the resource.
     */
    val state: StateAccessorIfc

    /**
     *  The last (previous) state before the current state.
     */
    val previousState: StateAccessorIfc

    /** Checks if the resource is idle, has no units allocated
     */
    val isIdle: Boolean

    /** Checks to see if the resource is busy, has some units allocated
     */
    val isBusy: Boolean

    /** Checks to see if the resource is inactive
     */
    val isInactive: Boolean

    /**
     * Statistical response representing the number of busy units of the resource.
     */
    val numBusyUnits: TWResponseCIfc

    /**
     * Statistical response representing the utilization of the resource.
     * This is the time average number of busy units divided by the time average
     * capacity.
     */
    val scheduledUtil: ResponseCIfc

    /**
     *  The number of times the resource was seized
     */
    val seizeCounter: CounterCIfc

    /**
     *  If c(t) is the current capacity and b(t) is the current number busy,
     *  then a(t) = c(t) - b(t) is the current number of available units.
     *  Under some capacity change situations, a(t) may be negative.
     */
    val numAvailableUnits: Int

    /**
     *  If a(t) is greater than zero
     */
    val hasAvailableUnits: Boolean

    /**
     *  If b(t) is greater than zero
     */
    val hasBusyUnits: Boolean

    /**
     *  The number of busy units at any time t, b(t)
     */
    val numBusy: Int

    /**
     *  The number of times that the resource has been seized (allocated)
     */
    val numTimesSeized: Int

    /**
     *  The number of times that the resource has been released (deallocated)
     */
    val numTimesReleased: Int

    /** If b(t) is the number of busy units, and c(t) is the current capacity, then
     *  the instantaneous utilization iu(t) is
     *
     *  if b(t) = 0, then iu(t) = 0.0
     *  if b(t) greater than or equal to c(t) then iu(t) = 1.0
     *  else iu(t) = b(t)/c(t)
     *
     */
    val instantaneousUtil: Double

    /**
     * time average instantaneous utilization
     */
    val timeAvgInstantaneousUtil: TWResponseCIfc

    /**
     *  A general attribute that can be used to assist with selecting resources
     */
    val selectionCriteria: Double

    /**
     *  The initial request queue notification rule for controlling the order
     *  in which queues are notified for processing requests after a capacity change.
     */
    val initialRequestQueueNotificationRule: RequestQueueNotificationRuleIfc
}