package ksl.modeling.variable

import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.statistic.WeightedStatisticIfc
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.emptyDataFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

data class TSResponsePeriodData(
    val response: ResponseCIfc,
    val repNum: Double,
    val period: Double,
    val startTime: Double,
    val length: Double,
    val value: Double?
)
{
    init {
        require(length.isFinite()) { "The length of the time series period must be finite" }
        require(length > 0.0) { "The length of the time series period must be > 0.0" }
        require(repNum >= 1.0) {"The replication number must be >= 1"}
        require(period >= 1.0) {"The period number must be >= 1"}
        require(startTime >= 0.0) { "The start time of the period must be >= 0.0" }
    }

    val endTime: Double = startTime + length
}

data class TSCounterPeriodData(
    val counter: CounterCIfc,
    val repNum: Double,
    val period: Double,
    val startTime: Double,
    val length: Double,
    val value: Double?
)
{
    init {
        require(length.isFinite()) { "The length of the time series period must be finite" }
        require(length > 0.0) { "The length of the time series period must be > 0.0" }
        require(repNum >= 1.0) {"The replication number must be >= 1"}
        require(period >= 1.0) {"The period number must be >= 1"}
        require(startTime >= 0.0) { "The start time of the period must be >= 0.0" }
    }

    val endTime: Double = startTime + length
}

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
    private val myResponseData = mutableMapOf<Response, MutableList<TSResponsePeriodData>>()
    private val myCounterData = mutableMapOf<Counter, MutableList<TSCounterPeriodData>>()

    init {
        require(periodLength.isFinite()) { "The length of the time series period must be finite" }
        require(periodLength > 0.0) { "The length of the time series period must be > 0.0" }
        require(responses.isNotEmpty() || counters.isNotEmpty()) { "Both the responses set and the counter set were empty." }
        for (response in responses) {
            myResponses[response] = PeriodStartData()
            myResponseData[response] = mutableListOf()
        }
        for (counter in counters) {
            myCounters[counter] = PeriodStartData()
            myCounterData[counter] = mutableListOf()
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

    var periodCounter: Double = 0.0
        private set

    /**
     * This represents the time that a period last started in absolute time.
     *
     */
    private var timeLastStarted: Double = 0.0

    /**
     * This represents the time that a period last ended in absolute time.
     *
     */
    private var timeLastEnded: Double = 0.0

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

    /**
     *  Provides a data frame representation of the response period data for the [response]
     *  or an empty data frame if the response is not collected.
     */
    fun responsePeriodDataAsList(response: ResponseCIfc): List<TSResponsePeriodData> {
        return myResponseData[response] ?: emptyList()
    }

    /**
     *  Provides a data frame representation of the counter period data for the [counter]
     *  or an empty data frame if the counter is not collected.
     */
    fun counterPeriodDataAsList(counter: CounterCIfc): List<TSCounterPeriodData> {
        return myCounterData[counter] ?: emptyList()
    }

    /**
     *  Provides a data frame representation of the response period data for the [response]
     *  or an empty data frame if the response is not collected.
     */
    fun responsePeriodData(response: ResponseCIfc): DataFrame<TSResponsePeriodData> {
        val list = myResponseData[response]
        return list?.toDataFrame() ?: emptyDataFrame()
    }

    /**
     *  Provides a data frame representation of the counter period data for the [counter]
     *  or an empty data frame if the counter is not collected.
     */
    fun counterPeriodData(counter: CounterCIfc): DataFrame<TSCounterPeriodData> {
        val list = myCounterData[counter]
        return list?.toDataFrame() ?: emptyDataFrame()
    }

    override fun initialize() {
        periodCounter = 0.0
        timeLastStarted = 0.0
        timeLastEnded = 0.0
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

    override fun beforeExperiment() {
        super.beforeExperiment()
        for((_, list) in myResponseData){
            list.clear()
        }
        for((_, list) in myCounterData){
            list.clear()
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
        startPeriodCollection()
        myPeriodEvent = schedule(this::endPeriodEvent, myPeriodLength, priority = KSLEvent.MEDIUM_LOW_PRIORITY)
    }

    private fun startPeriodCollection(){
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
        periodCounter++
        timeLastEnded = time
        // capture what happened during the period
        endPeriodCollection()
        // capture data from end of period which is the start of the next period
        startPeriodCollection()
        // schedule the next end of period
        myPeriodEvent = schedule(this::endPeriodEvent, myPeriodLength, priority = KSLEvent.MEDIUM_LOW_PRIORITY)
    }

    private fun endPeriodCollection(){
        val r = model.currentReplicationNumber.toDouble()
        for ((response, data) in myResponses) {
            timeLastEnded = time
            val w: WeightedStatisticIfc = response.withinReplicationStatistic
            val sum: Double = w.weightedSum - data.mySumAtStart
            val denom: Double = w.sumOfWeights - data.mySumOfWeightsAtStart
            val numObs: Double = w.count - data.myNumObsAtStart
            val value: Double? = if (numObs == 0.0) {
                // there were no changes of the variable during the period
                // cannot observe Response but can observe TWResponse
                if (response is TWResponse) {
                    //no observations, value did not change during interval
                    // average = area/time = height*width/width = height
                    //data.myResponse.value = response.value
                    response.value
                } else {
                    null
                }
            } else {
                // there were observations, denominator cannot be 0, but just in case
                if (denom != 0.0) {
                    val avg = sum / denom
                    avg
                } else {
                    null
                }
            }
            //construct the data and capture it
            val responseData = TSResponsePeriodData(response, r, periodCounter, timeLastStarted, periodLength, value)
            myResponseData[response]?.add(responseData)
        }

        for ((counter, data) in myCounters) {
            val intervalCount: Double = counter.value - data.myTotalAtStart
            val counterData = TSCounterPeriodData(counter, r, periodCounter, timeLastStarted, periodLength, intervalCount)
            myCounterData[counter]?.add(counterData)
        }
    }
}