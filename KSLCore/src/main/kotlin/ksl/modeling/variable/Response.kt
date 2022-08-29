package ksl.modeling.variable

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Interval
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import ksl.utilities.statistic.WeightedStatistic
import ksl.utilities.statistic.WeightedStatisticIfc

// not subclassing from Variable, Response does not have an initial value, but does have limits
// should be observable
open class Response(
    parent: ModelElement,
    name: String? = null,
    theLimits: Interval = Interval(),
    theInitialCountLimit: Double = Double.POSITIVE_INFINITY,
) : ModelElement(parent, name), ResponseIfc, ResponseStatisticsIfc {
    //TODO timed update stuff
    private val counterActions: MutableList<CountActionIfc> = mutableListOf()
    private var stoppingAction: StoppingAction? = null

    val limits: Interval = theLimits

    var timeOfWarmUp: Double = 0.0
        protected set

    var lastTimedUpdate: Double = 0.0
        protected set

    init {
        require(theInitialCountLimit >= 0) { "The initial count limit value $theInitialCountLimit must be >= 0" }
    }

    /**
     * Sets the initial value of the count limit. Only relevant prior to each
     * replication. Changing during a replication has no effect until the next
     * replication.
     */
    var initialCountLimit: Double = theInitialCountLimit
        set(value) {
            require(value >= 0) { "The initial count stop limit, when set, must be >= 0" }
            if (model.isRunning) {
                Model.logger.info { "The user set the initial count stop limit during the replication. The next replication will use a different initial value" }
            }
            field = value
        }

    /**
     * indicates the count when the simulation should stop
     * zero indicates no limit
     */
    var countStopLimit: Double = theInitialCountLimit
        set(limit) {
            require(limit >= 0) { "The count stop limit, when set, must be >= 0" }
            if (model.isRunning) {
                if (limit < field) {
                    Model.logger.info { "The count stop limit was reduced to $limit from $field for $name during the replication" }
                } else if (limit > field) {
                    Model.logger.info { "The count stop limit was increased to $limit from $field for $name during the replication" }
                    if (limit.isInfinite()) {
                        Model.logger.warn { "Setting the count stop limit to infinity during the replication may cause the replication to not stop." }
                    }
                }
            }
            field = limit
            if (model.isRunning) {
                if (myValue >= limit) {
                    notifyCountLimitActions()
                }
            }
        }

    protected var myValue: Double = Double.NaN

    override var value: Double
        get() = myValue
        set(newValue) = assignValue(newValue)

    protected open fun assignValue(newValue: Double){
        require(limits.contains(newValue)) { "The value $newValue was not within the limits $limits" }
        previousValue = myValue
        previousTimeOfChange = timeOfChange
        myValue = newValue
        timeOfChange = time
        myWithinReplicationStatistic.value = myValue
        notifyModelElementObservers(Status.UPDATE)
        if (myValue >= countStopLimit) {
            notifyCountLimitActions()
        }
    }

    override var previousValue: Double = Double.NaN
        protected set

    override var timeOfChange: Double = Double.NaN
        protected set

    override var previousTimeOfChange: Double = Double.NaN
        protected set

    protected val myAcrossReplicationStatistic: Statistic = Statistic(this.name)

    override val acrossReplicationStatistic: StatisticIfc
        get() = myAcrossReplicationStatistic.instance()

    protected val myWithinReplicationStatistic: WeightedStatistic = WeightedStatistic(this.name)

    override val withinReplicationStatistic: WeightedStatisticIfc
        get() = myWithinReplicationStatistic.instance()

    override var defaultReportingOption: Boolean = true

    override fun beforeExperiment() {
        super.beforeExperiment()
        myValue = Double.NaN
        previousValue = Double.NaN
        timeOfChange = Double.NaN
        previousTimeOfChange = Double.NaN
        myWithinReplicationStatistic.reset()
        myAcrossReplicationStatistic.reset()
        timeOfWarmUp = 0.0
        lastTimedUpdate = 0.0
        countStopLimit = initialCountLimit
    }

    override fun beforeReplication() {
        super.beforeReplication()
        myValue = Double.NaN
        previousValue = Double.NaN
        timeOfChange = Double.NaN
        previousTimeOfChange = Double.NaN
        myWithinReplicationStatistic.reset()
        timeOfWarmUp = 0.0
        lastTimedUpdate = 0.0
        countStopLimit = initialCountLimit
    }

    override fun timedUpdate() {
        super.timedUpdate()
        lastTimedUpdate = time
    }

    override fun warmUp() {
        super.warmUp()
        myWithinReplicationStatistic.reset()
        timeOfWarmUp = time
    }

    override fun afterReplication() {
        super.afterReplication()
        myAcrossReplicationStatistic.value = myWithinReplicationStatistic.average()
    }

    fun addCountLimitAction(action: CountActionIfc) {
        counterActions.add(action)
    }

    fun removeCountLimitAction(action: CountActionIfc) {
        counterActions.remove(action)
    }

    fun addCountLimitStoppingAction() : CountActionIfc{
        if (stoppingAction == null){
            stoppingAction = StoppingAction()
            addCountLimitAction(stoppingAction!!)
        }
        return stoppingAction!!
    }

    protected fun notifyCountLimitActions() {
        for (a in counterActions) {
            a.action(this)
        }
    }

    private inner class StoppingAction : CountActionIfc {
        override fun action(response: ResponseIfc) {
            executive.stop("Stopped because counter limit $countStopLimit was reached for $name")
        }
    }
}