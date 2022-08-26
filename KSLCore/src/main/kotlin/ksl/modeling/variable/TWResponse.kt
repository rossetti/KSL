package ksl.modeling.variable

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Interval

class TWResponse(
    parent: ModelElement,
    theInitialValue: Double = 0.0,
    theLimits: Interval = Interval(0.0, Double.POSITIVE_INFINITY),
    theInitialCountLimit: Long = 0,
    name: String? = null
) : Response(parent, theLimits, theInitialCountLimit, name), TimeWeightedIfc {
    //TODO timed update stuff

    init {
        require(limits.contains(theInitialValue)) { "The initial value $theInitialValue must be within the specified limits: $limits" }
    }

    /**
     * Sets the initial value of the variable. Only relevant prior to each
     * replication. Changing during a replication has no effect until the next
     * replication.
     */
    override var initialValue: Double = theInitialValue
        set(value) {
            require(limits.contains(value)) { "The initial value, $value must be within the specified limits: $limits" }
            if (model.isRunning) {
                Model.logger.info { "The user set the initial value during the replication. The next replication will use a different initial value" }
            }
            field = value
        }

    /**
     *  The previous value, before the current value changed
     */
    override var previousValue: Double = theInitialValue
        protected set

    override var timeOfChange: Double = 0.0
        protected set

    override var previousTimeOfChange: Double = 0.0
        protected set

    override val weight: Double
        get() {
            val w = timeOfChange - previousTimeOfChange
            return if (w < 0.0) {
                0.0
            } else {
                w
            }
        }

    protected override fun assignValue(newValue: Double) {
        require(limits.contains(newValue)) { "The value $newValue was not within the limits $limits" }
        previousValue = myValue
        previousTimeOfChange = timeOfChange
        myValue = newValue
        timeOfChange = time
        myWithinReplicationStatistic.collect(previousValue, weight)
        notifyModelElementObservers(Status.UPDATE)
        if (countStopLimit > 0) {
            if (myWithinReplicationStatistic.count >= countStopLimit) {
                executive.stop("Stopped because observation limit $countStopLimit was reached for $name")
            }
        }
    }

    /**
     * Assigns the value of the variable to the supplied value. Ensures that
     * time of change is 0.0 and previous value and previous time of
     * change are the same as the current value and current time without
     * notifying any update observers
     *
     * @param value the initial value to assign
     */
    protected fun assignInitialValue(value: Double) {
        require(limits.contains(value)) { "The initial value, $value must be within the specified limits: $limits" }
        myValue = value
        timeOfChange = 0.0
        previousValue = myValue
        previousTimeOfChange = timeOfChange
    }

    /**
     * Increments the value of the variable by the amount supplied. Throws an
     * IllegalArgumentException if the value is negative.
     *
     * @param increase The amount to increase by. Must be non-negative.
     */
    fun increment(increase: Double = 1.0) {
        require(increase >= 0) { "Invalid argument. Attempted an negative increment." }
        value = value + increase
    }

    /**
     * Decrements the value of the variable by the amount supplied. Throws an
     * IllegalArgumentException if the value is negative.
     *
     * @param decrease The amount to decrease by. Must be non-negative.
     */
    fun decrement(decrease: Double = 1.0) {
        require(decrease >= 0) { "Invalid argument. Attempted an negative decrement." }
        value = value - decrease
    }

    override fun beforeExperiment() {
        super.beforeExperiment()
        assignInitialValue(initialValue)
    }

    override fun beforeReplication() {
        super.beforeReplication()
        assignInitialValue(initialValue)
    }

    override fun initialize() {
        super.initialize()
        // this is so at least two changes are recorded on the variable
        // to properly account for variables that have zero area throughout the replication
        value = value
    }

    override fun timedUpdate() {
        super.timedUpdate()
        // this is to capture the area under the curve up to and including the current time
        value = value
    }

    override fun warmUp() {
        super.warmUp()
        // this is so at least two changes are recorded on the variable
        // to properly account for variables that have zero area throughout the replication
        // make it think that it changed at the warm-up time to the same value
        timeOfChange = time
        value = value
    }

    override fun replicationEnded() {
        super.replicationEnded()
        // this allows time weighted to be collected all the way to end of the replication
        value = value
    }
}