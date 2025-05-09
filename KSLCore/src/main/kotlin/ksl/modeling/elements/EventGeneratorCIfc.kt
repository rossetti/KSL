package ksl.modeling.elements

import ksl.modeling.variable.RandomVariableCIfc
import ksl.utilities.random.rvariable.RVariableIfc

interface EventGeneratorCIfc {
    /**
     * Sets the flag that indicates whether the generator will
     * automatically start at the beginning of a replication when initialized
     *
     * true indicates automatic start
     */
    var startOnInitializeOption: Boolean

    /**
     * Controls the random variable representing the time until the first event that is
     * used at the beginning of each replication to generate the time until the
     * first event. This change becomes effective at the beginning of the next
     * replication to execute
     */
    val initialTimeUntilFirstEvent: RandomVariableCIfc

    /**
     * This value is used to set the ending time for generating actions for each
     * replication. Changing this variable during a replication cause the next
     * replication to use this value for its ending time.
     *
     */
    var initialEndingTime: Double

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

    /**
     * Controls the maximum number of events to be used to initialize each
     * replication. The time between events cannot evaluate to a constant value
     * of 0.0 if the maximum number of events is infinite (Long.MAX_VALUE). Uses
     * the current value for initial time between events
     */
    var initialMaximumNumberOfEvents: Long

    /**
     * Sets the time between events and the maximum number of events to be used
     * to initialize each replication. The time between events cannot evaluate
     * to a constant value of 0.0. The maximum number of events is kept at its
     * current value, which by default is Long.Max_Value
     *
     */
    var initialTimeBtwEvents: RVariableIfc
}