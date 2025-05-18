package ksl.modeling.elements

import ksl.utilities.GetValueIfc
import ksl.utilities.random.rvariable.RVariableIfc

interface EventGeneratorTimeUntilFirstEventCIfc {
    /**
     * Controls the random variable representing the time until the first event that is
     * used at the beginning of each replication to generate the time until the
     * first event. This change becomes effective at the beginning of the next
     * replication to execute
     */
    var timeUntilFirstEvent: GetValueIfc
}

interface EventGeneratorTimeUntilFirstEventRVCIfc {
    /**
     * Controls the random variable representing the time until the first event that is
     * used at the beginning of each replication to generate the time until the
     * first event. This change becomes effective at the beginning of the next
     * replication to execute
     */
    var timeUntilFirstEvent: RVariableIfc
}

interface EventGeneratorInitialEventTimeProcessesCIfc {
    /**
     *  Often the time of the first event and the time between events is
     *  the same distribution. This property causes both [initialTimeUntilFirstEvent]
     *  and [initialTimeBtwEvents] to be set to the same random variable.
     */
    fun setInitialEventTimeProcesses(eventTimeProcess: GetValueIfc)
}

interface EventGeneratorInitialTimeBtwEventsCIfc {
    /**
     * Sets the time between events and the maximum number of events to be used
     * to initialize each replication. The time between events cannot evaluate
     * to a constant value of 0.0. The maximum number of events is kept at its
     * current value, which by default is Long.Max_Value. Note that
     * setting the initial time between events does not affect the random
     * variable governing the time of the first event.
     *
     */
    var initialTimeBtwEvents: GetValueIfc

    /**
     * Sets the time between events and the maximum number of events to be used
     * to initialize each replication. These parameters are dependent. The time
     * between events cannot evaluate to a constant value of 0.0 if the maximum
     * number of events is infinite (Long.MAX_VALUE)
     *
     * @param initialTimeBtwEvents the initial time between events
     * @param initialMaxNumEvents the initial maximum number of events
     */
    fun setInitialTimeBetweenEventsAndMaxNumEvents(
        initialTimeBtwEvents: GetValueIfc,
        initialMaxNumEvents: Long = Long.MAX_VALUE
    )

}

interface EventGeneratorInitialEventTimeProcessesRVCIfc {
    /**
     *  Often the time of the first event and the time between events is
     *  the same distribution. This property causes both [initialTimeUntilFirstEvent]
     *  and [initialTimeBtwEvents] to be set to the same random variable.
     */
    fun setInitialEventTimeProcesses(eventTimeProcess: RVariableIfc)
}

interface EventGeneratorInitialTimeBtwEventsRVCIfc  {
    /**
     * Sets the time between events and the maximum number of events to be used
     * to initialize each replication. The time between events cannot evaluate
     * to a constant value of 0.0. The maximum number of events is kept at its
     * current value, which by default is Long.Max_Value. Note that
     * setting the initial time between events does not affect the random
     * variable governing the time of the first event.
     *
     */
    var initialTimeBtwEvents: RVariableIfc

    /**
     * Sets the time between events and the maximum number of events to be used
     * to initialize each replication. These parameters are dependent. The time
     * between events cannot evaluate to a constant value of 0.0 if the maximum
     * number of events is infinite (Long.MAX_VALUE)
     *
     * @param initialTimeBtwEvents the initial time between events
     * @param initialMaxNumEvents the initial maximum number of events
     */
    fun setInitialTimeBetweenEventsAndMaxNumEvents(
        initialTimeBtwEvents: RVariableIfc,
        initialMaxNumEvents: Long = Long.MAX_VALUE
    )

}

interface EventGeneratorInitializationCIfc {
    /**
     * Sets the flag that indicates whether the generator will
     * automatically start at the beginning of a replication when initialized
     *
     * true indicates automatic start
     */
    var startOnInitializeOption: Boolean

    /**
     * This value is used to set the ending time for generating actions for each
     * replication. Changing this variable during a replication cause the next
     * replication to use this value for its ending time.
     *
     */
    var initialEndingTime: Double

    /**
     * Controls the maximum number of events to be used to initialize each
     * replication. The time between events cannot evaluate to a constant value
     * of 0.0 if the maximum number of events is infinite (Long.MAX_VALUE). Uses
     * the current value for initial time between events
     */
    var initialMaximumNumberOfEvents: Long
}

/**
 *  Permits controlled access to the underlying event generator.
 *  Of particular note are the [timeUntilFirstEvent] and
 *  [initialTimeBtwEvents] properties which govern the arrival process.
 *  In addition, the function setInitialEventTimeProcesses() is provided
 *  to facilitate the setting of both [timeUntilFirstEvent] and
 *  [initialTimeBtwEvents] to the same reference, which is
 *  a common use case.
 */
interface EventGeneratorCIfc : EventGeneratorTimeUntilFirstEventCIfc, EventGeneratorInitialTimeBtwEventsCIfc,
    EventGeneratorInitializationCIfc, EventGeneratorInitialEventTimeProcessesCIfc

/**
 *  Permits controlled access to the underlying event generator.
 *  Of particular note are the [timeUntilFirstEvent] and
 *  [initialTimeBtwEvents] properties which govern the arrival process.
 *  In addition, the function setInitialEventTimeProcesses() is provided
 *  to facilitate the setting of both [timeUntilFirstEvent] and
 *  [initialTimeBtwEvents] to the same reference, which is
 *  a common use case.
 */
interface EventGeneratorRVCIfc : EventGeneratorTimeUntilFirstEventRVCIfc,
    EventGeneratorInitialTimeBtwEventsRVCIfc,
    EventGeneratorInitializationCIfc,
    EventGeneratorInitialEventTimeProcessesRVCIfc

interface EventGeneratorRVIfc :  EventGeneratorTimeUntilFirstEventRVCIfc,
    EventGeneratorInitialTimeBtwEventsRVCIfc, EventGeneratorTimeBtwEventsRVIfc,
    EventGeneratorInitialEventTimeProcessesRVCIfc