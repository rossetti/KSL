package ksl.modeling.variable

import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.statistic.WeightedStatisticIfc
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.emptyDataFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

data class TimeSeriesPeriodData(
    val responseName: String,
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


interface TimeSeriesResponseCIfc {

    /**
     *  Indicates whether the collection will be scheduled to start automatically at time 0.0
     */
    var autoStart: Boolean

    /**
     *  The length (in base time units) for the period
     */
    var periodLength: Double

    /**
     *  Counts the number of periods collected
     */
    val periodCounter: Double

    /**
     *  The counters associated with the time series response.
     */
    val counters: List<CounterCIfc>

    /**
     *  The responses associated with the time series response.
     */
    val responses: List<ResponseCIfc>

    /**
     *  Returns all time series period data as a list for responses and counters
     */
    fun allTimeSeriesPeriodDataAsList() : List<TimeSeriesPeriodData>

    /**
     *  Returns all time series period data as a data frame for responses and counters
     */
    fun allTimeSeriesPeriodDataAsDataFrame() : DataFrame<TimeSeriesPeriodData>

    /**
     *  Provides a data frame representation of the response period data for the [response]
     *  or an empty data frame if the response is not collected.
     */
    fun responsePeriodDataAsList(response: ResponseCIfc): List<TimeSeriesPeriodData>

    /**
     *  Provides a data frame representation of the counter period data for the [counter]
     *  or an empty data frame if the counter is not collected.
     */
    fun counterPeriodDataAsList(counter: CounterCIfc): List<TimeSeriesPeriodData>

    /**
     *  Provides a data frame representation of the response period data for the [response]
     *  or an empty data frame if the response is not collected.
     */
    fun responsePeriodDataAsDataFrame(response: ResponseCIfc): DataFrame<TimeSeriesPeriodData>

    /**
     *  Provides a data frame representation of the counter period data for the [counter]
     *  or an empty data frame if the counter is not collected.
     */
    fun counterPeriodDataAsDataFrame(counter: CounterCIfc): DataFrame<TimeSeriesPeriodData>
}

class TimeSeriesResponse(
    parent: ModelElement,
    periodLength: Double,
    responses: Set<ResponseCIfc> = emptySet(),
    counters: Set<CounterCIfc> = emptySet(),
    override var autoStart: Boolean = true,
    name: String? = null
) : ModelElement(parent, name), TimeSeriesResponseCIfc {

    constructor(
        parent: ModelElement,
        periodLength: Double,
        response: ResponseCIfc,
        autoStart: Boolean = true,
        name: String? = null
    ) : this(parent, periodLength, setOf(response), emptySet(), autoStart, name)

    constructor(
        parent: ModelElement,
        periodLength: Double,
        counter: CounterCIfc,
        autoStart: Boolean = true,
        name: String? = null
    ) : this(parent, periodLength, emptySet(), setOf(counter), autoStart, name)

    constructor(
        parent: ModelElement,
        periodLength: Double,
        response: ResponseCIfc,
        counter: CounterCIfc,
        autoStart: Boolean = true,
        name: String? = null
    ) : this(parent, periodLength, setOf(response), setOf(counter), autoStart, name)

    private val myResponses = mutableMapOf<ResponseCIfc, PeriodStartData>()
    override val responses: List<ResponseCIfc>
        get() = myResponses.keys.toList()

    private val myCounters = mutableMapOf<CounterCIfc, PeriodStartData>()
    override val counters: List<CounterCIfc>
        get() = myCounters.keys.toList()

    private val myResponseData = mutableMapOf<ResponseCIfc, MutableList<TimeSeriesPeriodData>>()
    private val myCounterData = mutableMapOf<CounterCIfc, MutableList<TimeSeriesPeriodData>>()

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
    override var periodLength: Double = periodLength
        set(value) {
            require(value > 0.0) { "The length of the time series period must be > 0.0" }
            field = value
        }
    private var myPeriodLength = periodLength

    override var periodCounter: Double = 0.0
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
     *  Returns all time series period data as a list for responses and counters
     */
    override fun allTimeSeriesPeriodDataAsList() : List<TimeSeriesPeriodData>{
        val list = mutableListOf<TimeSeriesPeriodData>()
        for((_, dataList) in myResponseData){
            list.addAll(dataList)
        }
        for((_, dataList) in myCounterData){
            list.addAll(dataList)
        }
        return list
    }

    /**
     *  Returns all time series period data as a data frame for responses and counters
     */
    override fun allTimeSeriesPeriodDataAsDataFrame() : DataFrame<TimeSeriesPeriodData>{
        return allTimeSeriesPeriodDataAsList().toDataFrame()
    }

    /**
     *  Provides a data frame representation of the response period data for the [response]
     *  or an empty data frame if the response is not collected.
     */
    override fun responsePeriodDataAsList(response: ResponseCIfc): List<TimeSeriesPeriodData> {
        return myResponseData[response] ?: emptyList()
    }

    /**
     *  Provides a data frame representation of the counter period data for the [counter]
     *  or an empty data frame if the counter is not collected.
     */
    override fun counterPeriodDataAsList(counter: CounterCIfc): List<TimeSeriesPeriodData> {
        return myCounterData[counter] ?: emptyList()
    }

    /**
     *  Provides a data frame representation of the response period data for the [response]
     *  or an empty data frame if the response is not collected.
     */
    override fun responsePeriodDataAsDataFrame(response: ResponseCIfc): DataFrame<TimeSeriesPeriodData> {
        val list = myResponseData[response]
        return list?.toDataFrame() ?: emptyDataFrame()
    }

    /**
     *  Provides a data frame representation of the counter period data for the [counter]
     *  or an empty data frame if the counter is not collected.
     */
    override fun counterPeriodDataAsDataFrame(counter: CounterCIfc): DataFrame<TimeSeriesPeriodData> {
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
            val responseData = TimeSeriesPeriodData(response.name, r, periodCounter, timeLastStarted, periodLength, value)
            myResponseData[response]?.add(responseData)
        }

        for ((counter, data) in myCounters) {
            val intervalCount: Double = counter.value - data.myTotalAtStart
            val counterData = TimeSeriesPeriodData(counter.name, r, periodCounter, timeLastStarted, periodLength, intervalCount)
            myCounterData[counter]?.add(counterData)
        }
    }
}