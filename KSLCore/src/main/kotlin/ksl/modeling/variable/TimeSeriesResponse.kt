package ksl.modeling.variable

import com.google.common.collect.HashBasedTable
import com.google.common.collect.Table
import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.statistic.DEFAULT_CONFIDENCE_LEVEL
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.WeightedStatisticIfc
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.emptyDataFrame
import org.jetbrains.kotlinx.dataframe.api.remove
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

/**
 *  The within replication data collected for time series responses.
 *  For Response and TWResponse instances the value property represents
 *  the average of the response over the indicated period. For Counter instances
 *  the value property represents the total count during the indicated period.
 *  @param elementId the model element id of the response or counter
 *  @param responseName the name of the counter or response
 *  @param repNum the replication that the period was within
 *  @param period the number of the period, numbered consecutively starting at 1
 *  @param startTime the time that the period started
 *  @param length the length of time associated with the period
 *  @param value the collected value. For Response and TWResponse instances the value property represents
 *  the average of the response over the indicated period. For Counter instances
 *  the value property represents the total count during the indicated period.
 */
data class TimeSeriesPeriodData(
    val elementId: Int,
    val responseName: String,
    val repNum: Int,
    val period: Int,
    val startTime: Double,
    val length: Double,
    val value: Double?
) {
    init {
        require(length.isFinite()) { "The length of the time series period must be finite" }
        require(length > 0.0) { "The length of the time series period must be > 0.0" }
        require(repNum >= 1.0) { "The replication number must be >= 1" }
        require(period >= 1.0) { "The period number must be >= 1" }
        require(startTime >= 0.0) { "The start time of the period must be >= 0.0" }
    }

    /**
     *  The ending time of the period.
     */
    val endTime: Double = startTime + length
}

/**
 *  Holds the statistics by period across the simulation replications.
 */
data class TimeSeriesPeriodStatisticData(
    val responseName: String,
    val period: Int,
    val startTime: Double,
    val endTime: Double,
    val count: Double,
    val average: Double,
    val standardDeviation: Double,
    val standardError: Double,
    val halfWidth: Double,
    val confidenceLevel: Double,
    val lowerLimit: Double,
    val upperLimit: Double,
    val min: Double,
    val max: Double,
    val kurtosis: Double,
    val skewness: Double,
    val lag1Correlation: Double,
    val numberMissing: Double
) {
    init {
        require(period >= 1.0) { "The period number must be >= 1" }
        require(startTime >= 0.0) { "The start time of the period must be >= 0.0" }
        require(endTime > startTime) { "The end time of the period must be > than the start time" }
    }
}

/**
 *  A limiting view of a TimeSeriesResponse via an interface.
 */
interface TimeSeriesResponseCIfc : ParentNameIfc {

    /**
     *  The default time that the first period will start. By default, it is zero.
     */
    var defaultStartTime: Double

    /**
     *  If true, across replications statistics will be collected for each period.
     *  The default is false.
     */
    var acrossRepStatisticsOption: Boolean

    /**
     *  The length (in base time units) for the period
     */
    var periodLength: Double

    /**
     *  Counts the number of periods collected
     */
    val periodCounter: Int

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
    fun allTimeSeriesPeriodDataAsList(): List<TimeSeriesPeriodData>

    /**
     *  Returns all time series period data as a data frame for responses and counters
     */
    fun allTimeSeriesPeriodDataAsDataFrame(): DataFrame<TimeSeriesPeriodData>

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

    /**
     *  If the acrossRepStatisticsOption option is true, then this function
     *  returns the across replication statistics for each period for the
     *  specified [response]. If the response is not associated with the
     *  time series collector, then an empty map is returned.
     *
     */
    fun acrossReplicationStatisticsByPeriod(response: ResponseCIfc): Map<Int, Statistic>

    /**
     *  If the acrossRepStatisticsOption option is true, then this function
     *  returns the across replication statistics for each period for the
     *  specified [counter]. If the counter is not associated with the
     *  time series collector, then an empty map is returned.
     *
     */
    fun acrossReplicationStatisticsByPeriod(counter: CounterCIfc): Map<Int, Statistic>

    /**
     *  Returns a data frame of the across replication statistics by period where the
     *  statistics are computed replications.
     *  @param confidenceLevel the confidence level for the confidence interval on the average
     */
    fun allAcrossReplicationStatisticsByPeriodAsDataFrame(
        confidenceLevel: Double = TimeSeriesResponse.defaultConfidenceLevel
    ): DataFrame<TimeSeriesPeriodStatisticData> {
        var df = allAcrossReplicationStatisticsByPeriodAsList(confidenceLevel).toDataFrame()
        df = df.remove(
            "standardError", "confidenceLevel", "kurtosis", "skewness",
            "lag1Correlation", "numberMissing"
        )
        //return allAcrossReplicationStatisticsByPeriodAsList(confidenceLevel).toDataFrame()
        return df
    }

    /**
     *  Returns a list of the across replication statistics by period where the
     *  statistics are computed replications.
     *  @param confidenceLevel the confidence level for the confidence interval on the average
     */
    fun allAcrossReplicationStatisticsByPeriodAsList(
        confidenceLevel: Double = TimeSeriesResponse.defaultConfidenceLevel
    ): List<TimeSeriesPeriodStatisticData>
}

/**
 *  This class addresses the problem of collecting simulation responses by period. A typical use case
 *  involves the reporting of statistics over fixed time periods, such as hourly, daily, monthly, yearly, etc.
 *  A time series response divides the simulated time horizon into sequential periods, with the periods
 *  marking the time frame over which performance is observed. Users specify which responses will
 *  be tabulated by providing Response, TWResponse, or Counter instances. Then for each period during the
 *  simulation horizon, the response over the period is recorded into a series of records,
 *  constituting a time series for the response. For Response and TWResponse instances, the recorded
 *  response represents the average of the variable during the period. For Counter instances, the total
 *  count during the period is recorded.  This data is recorded for every response, for every period,
 *  for each replication.  The response or counter information is recorded at the end of each
 *  completed period. The number of periods to collect must be supplied.
 *
 *  This class does not react to warm up events.  That is, periods observed prior to the warmup event
 *  will contain data that was observed during the warmup period.  The standard usage for this
 *  class is likely not within an infinite horizon (steady-state) context. However, if you do not
 *  want data collected during warmup periods, then specify the default start time for the
 *  time series to be greater than or equal to the specified warmup period length using
 *  the defaultStartTime property.  The default starting time of the first period is at time 0.0.
 *
 *  The collected responses are not automatically shown in console output. However, the data can
 *  be accessed via a reference to the class or by using a KSLDatabase.
 *
 * @param parent the parent model element for the time series response.
 * @param periodLength the length of time for the period. This must be greater than zero.
 * @param responses A set of responses to observe for each period. This set may be empty only if
 * the counters set is not empty.
 * @param counters A set of counters to observe for each period. This set may be empty only if
 * the responses set is not empty.
 * @param acrossRepStatisticsOption This option indicates that within memory statistics will be defined
 * to collect statistics across the periods for each replication.  This will result in statistics for
 * each period for each response. The default is false.
 * @param name the name of the model element
 */
class TimeSeriesResponse(
    parent: ModelElement,
    periodLength: Double,
    numPeriods: Int,
    responses: Set<ResponseCIfc> = emptySet(),
    counters: Set<CounterCIfc> = emptySet(),
    override var acrossRepStatisticsOption: Boolean = false,
    name: String? = null
) : ModelElement(parent, name), TimeSeriesResponseCIfc {
    init {
        require(numPeriods >= 1) {"The number of periods to collect must be >= 1"}
    }
    constructor(
        parent: ModelElement,
        periodLength: Double,
        numPeriods: Int,
        response: ResponseCIfc,
        acrossRepStatisticsOption: Boolean = false,
        name: String? = null
    ) : this(parent, periodLength, numPeriods, setOf(response), emptySet(), acrossRepStatisticsOption, name)

    constructor(
        parent: ModelElement,
        periodLength: Double,
        numPeriods: Int,
        counter: CounterCIfc,
        acrossRepStatisticsOption: Boolean = false,
        name: String? = null
    ) : this(parent, periodLength, numPeriods, emptySet(), setOf(counter), acrossRepStatisticsOption, name)

    constructor(
        parent: ModelElement,
        periodLength: Double,
        numPeriods: Int,
        response: ResponseCIfc,
        counter: CounterCIfc,
        acrossRepStatisticsOption: Boolean = false,
        name: String? = null
    ) : this(parent, periodLength, numPeriods, setOf(response), setOf(counter), acrossRepStatisticsOption, name)

    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    var numPeriodsToCollect = numPeriods
        set(value) {
            require(value >= 1) {"The number of periods to collect must be >= 1"}
            require(model.isNotRunning) {"The model must not be running when changing the number of periods to collect."}
            field = value
        }

    private val myResponses = mutableMapOf<ResponseCIfc, PeriodStartData>()
    override val responses: List<ResponseCIfc>
        get() = myResponses.keys.toList()

    private val myCounters = mutableMapOf<CounterCIfc, PeriodStartData>()
    override val counters: List<CounterCIfc>
        get() = myCounters.keys.toList()

    private val myResponseData = mutableMapOf<ResponseCIfc, MutableList<TimeSeriesPeriodData>>()
    private val myCounterData = mutableMapOf<CounterCIfc, MutableList<TimeSeriesPeriodData>>()
    private var myAcrossRepResponseStatsTable: Table<ResponseCIfc, Int, Statistic>? = null
    private var myAcrossRepCounterStatsTable: Table<CounterCIfc, Int, Statistic>? = null

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

    override var periodCounter: Int = 0
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

    /**
     *  The default time that the first period will start. By default, it is zero.
     */
    override var defaultStartTime: Double = 0.0
        set(value) {
            require(value >= 0.0) { "The default start time must be >= 0.0" }
            require(model.isNotRunning) { "The simulation cannot be running when changing the default start time." }
            field = value
        }

    /**
     *  If the collection has been scheduled to start or has started, this function
     *  cancels any further collection.
     */
    fun cancelCollection() {
        if (myStartEvent != null) {
            if (myStartEvent!!.isScheduled) {
                myStartEvent?.cancel
            }
            myStartEvent = null
        }
        if (myPeriodEvent != null) {
            if (myPeriodEvent!!.isScheduled) {
                myPeriodEvent?.cancel
            }
            myPeriodEvent = null
        }
    }

    /**
     *  If the acrossRepStatisticsOption option is true, then this function
     *  returns the across replication statistics for each period for the
     *  specified [response]. If the response is not associated with the
     *  time series collector, then an empty map is returned.
     *
     */
    override fun acrossReplicationStatisticsByPeriod(response: ResponseCIfc): Map<Int, Statistic> {
        if (myAcrossRepResponseStatsTable == null) {
            return emptyMap()
        }
        return myAcrossRepResponseStatsTable!!.row(response)
    }

    override fun allAcrossReplicationStatisticsByPeriodAsList(confidenceLevel: Double): List<TimeSeriesPeriodStatisticData> {
        require(!(confidenceLevel <= 0.0 || confidenceLevel >= 1.0)) { "Confidence Level must be (0,1)" }
        if (myAcrossRepResponseStatsTable == null) {
            return emptyList()
        }
        val list = mutableListOf<TimeSeriesPeriodStatisticData>()
        for (cell in myAcrossRepResponseStatsTable!!.cellSet()) {
            val period = cell.columnKey
            val startTime = defaultStartTime + (period - 1) * periodLength
            val endTime = startTime + periodLength
            cell.value.confidenceLevel = confidenceLevel
            val data = TimeSeriesPeriodStatisticData(
                responseName = cell.rowKey.name,
                period = period,
                startTime = startTime,
                endTime =endTime,
                count = cell.value.count,
                average = cell.value.average,
                standardDeviation = cell.value.standardDeviation,
                standardError = cell.value.standardError,
                halfWidth = cell.value.halfWidth,
                confidenceLevel = cell.value.confidenceLevel,
                lowerLimit = cell.value.average - cell.value.halfWidth,
                upperLimit = cell.value.average + cell.value.halfWidth,
                min = cell.value.min,
                max = cell.value.max,
                kurtosis = cell.value.kurtosis,
                skewness = cell.value.skewness,
                lag1Correlation = cell.value.lag1Correlation,
                numberMissing = cell.value.numberMissing
            )
            list.add(data)
        }
        for (cell in myAcrossRepCounterStatsTable!!.cellSet()) {
            val period = cell.columnKey
            val startTime = defaultStartTime + (period - 1) * periodLength
            val endTime = startTime + periodLength
            val data = TimeSeriesPeriodStatisticData(
                responseName = cell.rowKey.name,
                period = period,
                startTime = startTime,
                endTime =endTime,
                count = cell.value.count,
                average = cell.value.average,
                standardDeviation = cell.value.standardDeviation,
                standardError = cell.value.standardError,
                halfWidth = cell.value.halfWidth,
                confidenceLevel = cell.value.confidenceLevel,
                lowerLimit = cell.value.average - cell.value.halfWidth,
                upperLimit = cell.value.average + cell.value.halfWidth,
                min = cell.value.min,
                max = cell.value.max,
                kurtosis = cell.value.kurtosis,
                skewness = cell.value.skewness,
                lag1Correlation = cell.value.lag1Correlation,
                numberMissing = cell.value.numberMissing
            )
            list.add(data)
        }
        return list
    }

    /**
     *  If the acrossRepStatisticsOption option is true, then this function
     *  returns the across replication statistics for each period for the
     *  specified [counter]. If the counter is not associated with the
     *  time series collector, then an empty map is returned.
     *
     */
    override fun acrossReplicationStatisticsByPeriod(counter: CounterCIfc): Map<Int, Statistic> {
        if (myAcrossRepResponseStatsTable == null) {
            return emptyMap()
        }
        return myAcrossRepCounterStatsTable!!.row(counter)
    }

    /**
     *  Returns all time series period data as a list for responses and counters
     */
    override fun allTimeSeriesPeriodDataAsList(): List<TimeSeriesPeriodData> {
        val list = mutableListOf<TimeSeriesPeriodData>()
        for ((_, dataList) in myResponseData) {
            list.addAll(dataList)
        }
        for ((_, dataList) in myCounterData) {
            list.addAll(dataList)
        }
        return list
    }

    /**
     *  Returns all time series period data as a data frame for responses and counters
     */
    override fun allTimeSeriesPeriodDataAsDataFrame(): DataFrame<TimeSeriesPeriodData> {
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
        periodCounter = 0
        timeLastStarted = 0.0
        timeLastEnded = 0.0
        myStartEvent = null
        myPeriodEvent = null
        myPeriodLength = periodLength
        myStartEvent = schedule(this::startFirstPeriod, defaultStartTime, priority = KSLEvent.VERY_HIGH_PRIORITY)
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
        for ((_, list) in myResponseData) {
            list.clear()
        }
        for ((_, list) in myCounterData) {
            list.clear()
        }
        if (acrossRepStatisticsOption) {
            // true, means turn on. so if not already created we need to create them
            if (myAcrossRepResponseStatsTable == null) {
                myAcrossRepResponseStatsTable = HashBasedTable.create()
            } else {
                myAcrossRepResponseStatsTable!!.clear()
            }
            if (myAcrossRepCounterStatsTable == null) {
                myAcrossRepCounterStatsTable = HashBasedTable.create()
            } else {
                myAcrossRepCounterStatsTable!!.clear()
            }
        } else {
            // false means we are not going to collect so can get rid of them
            myAcrossRepResponseStatsTable?.clear()
            myAcrossRepCounterStatsTable?.clear()
            myAcrossRepResponseStatsTable = null
            myAcrossRepCounterStatsTable = null
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

    @Suppress("UNUSED_PARAMETER")
    private fun startFirstPeriod(event: KSLEvent<Nothing>) {
        startPeriodCollection()
        myPeriodEvent = schedule(this::endPeriodEvent, myPeriodLength, priority = KSLEvent.MEDIUM_LOW_PRIORITY)
    }

    private fun startPeriodCollection() {
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

    @Suppress("UNUSED_PARAMETER")
    private fun endPeriodEvent(event: KSLEvent<Nothing>) {
        periodCounter++
        timeLastEnded = time
        // capture what happened during the period
        endPeriodCollection()
        // capture data from end of period which is the start of the next period
        startPeriodCollection()
        // schedule the next end of period
        if (periodCounter < numPeriodsToCollect){
            myPeriodEvent = schedule(this::endPeriodEvent, myPeriodLength, priority = KSLEvent.MEDIUM_LOW_PRIORITY)
        }
    }

    private fun endPeriodCollection() {
        val r = model.currentReplicationNumber
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
            val responseData = TimeSeriesPeriodData(
                response.id, response.name, r,
                periodCounter, timeLastStarted, periodLength, value
            )
            if ((myAcrossRepResponseStatsTable != null) && (value != null)) {
                var statistic = myAcrossRepResponseStatsTable!!.get(response, periodCounter)
                if (statistic == null) {
                    statistic = Statistic("${response.name}_Period_$periodCounter")
                    myAcrossRepResponseStatsTable!!.put(response, periodCounter, statistic)
                }
                statistic.collect(value)
            }
            myResponseData[response]?.add(responseData)
        }

        for ((counter, data) in myCounters) {
            val intervalCount: Double = counter.value - data.myTotalAtStart
            val counterData = TimeSeriesPeriodData(
                counter.id, counter.name, r, periodCounter,
                timeLastStarted, periodLength, intervalCount
            )
            if (myAcrossRepCounterStatsTable != null) {
                var statistic = myAcrossRepCounterStatsTable!!.get(counter, periodCounter)
                if (statistic == null) {
                    statistic = Statistic("${counter.name}_Period_$periodCounter")
                    myAcrossRepCounterStatsTable!!.put(counter, periodCounter, statistic)
                }
                statistic.collect(intervalCount)
            }
            myCounterData[counter]?.add(counterData)
        }
    }

    companion object {
        var defaultConfidenceLevel: Double = DEFAULT_CONFIDENCE_LEVEL
            set(level) {
                require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
                field = level
            }
    }
}