package ksl.modeling.variable

import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.statistic.WeightedStatisticIfc

class TimeSeriesResponse(
    parent: ModelElement,
    periodLength: Double,
    responses: Set<Response> = parent.model.responses.toSet(),
    counters: Set<Counter> = parent.model.counters.toSet(),
    var autoStart: Boolean = true,
    name: String? = null
) : ModelElement(parent, name) {

    constructor(
        parent: ModelElement,
        periodLength: Double,
        response: Response,
        autoStart: Boolean = true,
        name: String? = null
    ) : this(parent, periodLength, setOf(response), emptySet(), autoStart, name)

    constructor(
        parent: ModelElement,
        periodLength: Double,
        counter: Counter,
        autoStart: Boolean = true,
        name: String? = null
    ) : this(parent, periodLength, emptySet(), setOf(counter), autoStart, name)

    constructor(
        parent: ModelElement,
        periodLength: Double,
        response: Response,
        counter: Counter,
        autoStart: Boolean = true,
        name: String? = null
    ) : this(parent, periodLength, setOf(response), setOf(counter), autoStart, name)

    private val myResponses = mutableMapOf<Response, PeriodStartData>()
    private val myCounters = mutableMapOf<Counter, PeriodStartData>()

    init {
        require(periodLength.isFinite()) { "The length of the time series period must be finite" }
        require(periodLength > 0.0) { "The length of the time series period must be > 0.0" }
        require(responses.isNotEmpty() || counters.isNotEmpty()) { "Both the responses set and the counter set were empty." }
        for (response in responses) {
            myResponses[response] = PeriodStartData()
        }
        for (counter in counters) {
            myCounters[counter] = PeriodStartData()
        }
    }

    private var myStartEvent: KSLEvent<Nothing>? = null
    private var myPeriodEvent: KSLEvent<Nothing>? = null

    /**
     *  The length (in base time units) for the period
     */
    var periodLength: Double = periodLength
        set(value) {
            require(value > 0.0) { "The length of the time series period must be > 0.0" }
            field = value
        }
    private var myPeriodLength = periodLength

    /**
     * This represents the time that a period last started in absolute time.
     *
     */
    var timeLastStarted: Double = 0.0
        private set

    /**
     * This represents the time that a period last ended in absolute time.
     *
     */
    var timeLastEnded: Double = 0.0
        private set

    /** Causes the start of the first period to be scheduled.
     *  The collection must not have already been started.
     *  @param startTime the time from the current time until the start of the first period
     *  The default is 0.0
     */
    fun startCollection(startTime: Double = 0.0) {
        require(startTime >= 0.0) { "The start time must be >= 0.0" }
        require(myStartEvent == null) { "The times series response collection has already been started" }
        myStartEvent = schedule(this::startFirstPeriod, startTime, priority = KSLEvent.VERY_HIGH_PRIORITY)
    }

    /**
     *  If the collection has been scheduled to start or has started, this function
     *  cancels any further collection.
     */
    fun cancelCollection() {
        if (myStartEvent != null) {
            if (myStartEvent!!.isScheduled){
                myStartEvent?.cancel
            }
            myStartEvent = null
        }
        if (myPeriodEvent != null) {
            if (myPeriodEvent!!.isScheduled){
                myPeriodEvent?.cancel
            }
            myPeriodEvent = null
        }
    }

    override fun initialize() {
        myStartEvent = null
        myPeriodEvent = null
        myPeriodLength = periodLength
        if (autoStart) {
            startCollection()
        }
    }

    override fun afterReplication() {
        super.afterReplication()
        for (d in myResponses.values) {
            d.reset()
        }
        for (d in myCounters.values) {
            d.reset()
        }
    }

    /**
     * Represents data collected at the start of period for use at the end
     * of the period
     */
    internal inner class PeriodStartData() {
        var mySumAtStart = 0.0
        var mySumOfWeightsAtStart = 0.0
        var myTotalAtStart = 0.0
        var myNumObsAtStart = 0.0
        fun reset() {
            mySumAtStart = 0.0
            mySumOfWeightsAtStart = 0.0
            myTotalAtStart = 0.0
            myNumObsAtStart = 0.0
        }
    }

    private fun startFirstPeriod(event: KSLEvent<Nothing>) {
        startPeriod()
        myPeriodEvent = schedule(this::endPeriodEvent, myPeriodLength, priority = KSLEvent.MEDIUM_LOW_PRIORITY)
    }

    private fun startPeriod(){
        for ((response, data) in myResponses) {
            timeLastStarted = time
            val w: WeightedStatisticIfc = response.withinReplicationStatistic
            data.mySumAtStart = w.weightedSum
            data.mySumOfWeightsAtStart = w.sumOfWeights
            data.myNumObsAtStart = w.count
        }
        for ((counter, data) in myCounters) {
            data.myTotalAtStart = counter.value
        }
    }

    private fun endPeriodEvent(event: KSLEvent<Nothing>) {
        // capture what happened during the period

        // capture data from end of period which is the start of the next period
    }
}