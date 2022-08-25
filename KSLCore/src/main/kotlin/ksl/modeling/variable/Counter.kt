package ksl.modeling.variable

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

open class Counter(
    parent: ModelElement,
    theInitialValue: Double = 0.0,
    theInitialCountLimit: Long = 0,
    name: String?
) : ModelElement(parent, name), CounterIfc {
//TODO need to implement resetting of counters and warmup reset!
    
    var timeOfWarmUp: Double = 0.0
        protected set

    var lastTimedUpdate: Double = 0.0
        protected set

    init {
        require(theInitialValue >= 0) { "The initial value $theInitialValue must be >= 0" }
        require(theInitialCountLimit >= 0) { "The initial count limit value $theInitialCountLimit must be >= 0" }
    }

    /**
     * Sets the initial value of the count limit. Only relevant prior to each
     * replication. Changing during a replication has no effect until the next replication.
     */
    var initialCountLimit: Long = theInitialCountLimit
        set(value) {
            require(value >= 0) { "The initial count stop limit, when set, must be >= 0" }
            if (model.isRunning) {
                Model.logger.info { "The user set the initial count stop limit during the replication. The next replication will use a different initial value" }
            }
            field = value
        }

    /**
     * Indicates the count when the simulation should stop. Zero indicates no limit.
     */
    var countStopLimit: Long = theInitialCountLimit
        set(limit) {
            require(limit >= 0) { "The count stop limit, when set, must be >= 0" }
            if (limit < field){
                // making limit smaller than current value
                if (model.isRunning){
                    Model.logger.info {"The count stop limit was reduced to $limit from $field for $name during the replication"}
                    if (limit == 0L){
                        Model.logger.warn {"Turning off a specified count limit may cause the replication to not stop."}
                    } else { // new value could be smaller than current counter limit
                        if (myValue >= limit) {
                            Model.logger.info {"The current counter value is >= to new counter stop limit. Causing the simulation to stop."}
                            executive.stop("Stopped because counter limit $countStopLimit was reached for $name")
                        }
                    }
                }
            }
            field = limit
        }



    /**
     * Sets the initial value of the variable. Only relevant prior to each
     * replication. Changing during a replication has no effect until the next
     * replication.
     */
    override var initialValue: Double = theInitialValue
        set(value) {
            require(value >= 0) { "The initial value $value must be >= 0" }
            if (model.isRunning) {
                Model.logger.info { "The user set the initial value during the replication. The next replication will use a different initial value" }
            }
            field = value
        }

    protected var myValue: Double = theInitialValue

    override var value: Double
        get() = myValue
        protected set(newValue) = assignValue(newValue)

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

    protected open fun assignValue(newValue: Double){
        require(newValue >= 0) { "The value $newValue was not >= 0" }
        previousValue = myValue
        previousTimeOfChange = timeOfChange
        myValue = newValue
        timeOfChange = time
        notifyModelElementObservers(Status.UPDATE)
        if (countStopLimit > 0) {
            if (myValue >= countStopLimit) {
                executive.stop("Stopped because counter limit $countStopLimit was reached for $name")
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
        require(value >= 0) { "The initial value $value must be >= 0" }
        myValue = value
        timeOfChange = 0.0
        previousValue = myValue
        previousTimeOfChange = timeOfChange
        countStopLimit = initialCountLimit
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

    protected val myAcrossReplicationStatistic: Statistic = Statistic(name!!)

    override val acrossReplicationStatistic: StatisticIfc
        get() = myAcrossReplicationStatistic.instance()

    override var defaultReportingOption: Boolean = true

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
        assignInitialValue(initialValue)
    }

    override fun afterReplication() {
        super.afterReplication()
        myAcrossReplicationStatistic.value = value
    }
}