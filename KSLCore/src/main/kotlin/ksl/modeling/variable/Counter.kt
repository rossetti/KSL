package ksl.modeling.variable

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

open class Counter(
    parent: ModelElement,
    name: String? = null,
    theInitialValue: Double = 0.0,
    theInitialCounterLimit: Double = Double.POSITIVE_INFINITY,
) : ModelElement(parent, name), CounterIfc {
//TODO need to implement resetting of counters and warmup reset!

    private val counterActions: MutableList<CounterActionIfc> = mutableListOf()

    var timeOfWarmUp: Double = 0.0
        protected set

    var lastTimedUpdate: Double = 0.0
        protected set

    init {
        require(theInitialValue >= 0.0) { "The initial value $theInitialValue must be >= 0" }
        require(theInitialCounterLimit >= 0.0) { "The initial count limit value $theInitialCounterLimit must be >= 0" }
    }

    /**
     * Sets the initial value of the count limit. Only relevant prior to each
     * replication. Changing during a replication has no effect until the next replication.
     */
    var initialCounterLimit: Double = theInitialCounterLimit
        set(value) {
            require(value >= 0) { "The initial counter stop limit, when set, must be >= 0" }
            if (model.isRunning) {
                Model.logger.info { "The user set the initial counter stop limit during the replication. The next replication will use a different initial value" }
            }
            field = value
        }

    /**
     * Indicates the count when the simulation should stop. Zero indicates no limit.
     */
    var counterStopLimit: Double = theInitialCounterLimit
        set(limit) {
            require(limit >= 0) { "The counter stop limit, when set, must be >= 0" }
            if (model.isRunning) {
                if (limit < field) {
                    Model.logger.info { "The counter stop limit was reduced to $limit from $field for $name during the replication" }
                } else if (limit > field) {
                    Model.logger.info { "The counter stop limit was increased to $limit from $field for $name during the replication" }
                    if (limit.isInfinite()) {
                        Model.logger.warn { "Setting the counter stop limit to infinity during the replication may cause the replication to not stop." }
                    }
                }
            }
            field = limit
            if (model.isRunning) {
                if (myValue >= limit) {
                    notifyCounterActions()
                }
            }
        }

    val isLimitReached: Boolean
        get() = myValue >= counterStopLimit

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

    private var stoppingAction: StoppingAction? = null

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

    fun addCounterAction(action: CounterActionIfc) {
        counterActions.add(action)
    }

    fun removeCounterAction(action: CounterActionIfc) {
        counterActions.remove(action)
    }

    fun addStoppingAction(){
        if (stoppingAction == null){
            stoppingAction = StoppingAction()
            addCounterAction(stoppingAction!!)
        }
    }

    protected fun notifyCounterActions() {
        for (a in counterActions) {
            a.action(this)
        }
    }

    protected open fun assignValue(newValue: Double) {
        require(newValue >= 0) { "The value $newValue was not >= 0" }
        previousValue = myValue
        previousTimeOfChange = timeOfChange
        myValue = newValue
        timeOfChange = time
        notifyModelElementObservers(Status.UPDATE)
        if (myValue >= counterStopLimit) {
            notifyCounterActions()
        }
    }

    /**
     * Assigns the value of the counter to the supplied value. Ensures that
     * time of change is 0.0 and previous value and previous time of
     * change are the same as the current value and current time without
     * notifying any update observers
     *
     * @param value the initial value to assign
     */
    protected fun assignInitialValue(value: Double) {
        require(value >= 0.0) { "The initial value $value must be >= 0" }
        require(value < initialCounterLimit) {"The initial value, $value, of the counter must be < the initial counter limit, $initialCounterLimit"}
        myValue = value
        timeOfChange = 0.0
        previousValue = myValue
        previousTimeOfChange = timeOfChange
        counterStopLimit = initialCounterLimit
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
        lastTimedUpdate= 0.0
        timeOfWarmUp = 0.0
        assignInitialValue(initialValue)
        myAcrossReplicationStatistic.reset()
    }

    override fun beforeReplication() {
        super.beforeReplication()
        lastTimedUpdate= 0.0
        timeOfWarmUp = 0.0
        assignInitialValue(initialValue)
    }

    override fun initialize() {
        super.initialize()
        lastTimedUpdate= 0.0
        timeOfWarmUp = 0.0
        resetCounter(initialValue, false)
        assignInitialValue(initialValue)
    }

    override fun warmUp() {
        super.warmUp()
        timeOfWarmUp = time
        resetCounter(0.0, false)
    }

    override fun afterReplication() {
        super.afterReplication()
        myAcrossReplicationStatistic.value = value
    }

    /**
     * Resets the counter to the supplied value.
     *
     * @param value, must be &lt; counterLimit and &gt;=0
     * @param notifyUpdateObservers If true, any update observers will be
     * notified otherwise they will not be notified
     */
    fun resetCounter(value: Double, notifyUpdateObservers: Boolean) {
        require(value >= 0) { "The counter's value must be >= 0" }
        require(value < counterStopLimit) { "The counter's value, $value must be < the counter limit = $counterStopLimit" }
        previousValue = value
        previousTimeOfChange = time
        myValue = value
        timeOfChange = previousTimeOfChange
//TODO        myCountAtPreviousTimedUpdate = value
        if (notifyUpdateObservers) {
            notifyModelElementObservers(Status.UPDATE)
        }
    }

    private inner class StoppingAction : CounterActionIfc {
        override fun action(counter: Counter) {
            executive.stop("Stopped because counter limit $counterStopLimit was reached for $name")
        }
    }
}