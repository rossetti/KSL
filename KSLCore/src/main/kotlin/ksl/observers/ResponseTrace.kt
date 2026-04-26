package ksl.observers

import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.simulation.ModelElement
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.tabularfiles.DataType
import ksl.utilities.io.tabularfiles.RowSetterIfc
import ksl.utilities.io.tabularfiles.TabularInputFile
import ksl.utilities.io.tabularfiles.TabularOutputFile
import org.jetbrains.kotlinx.dataframe.AnyFrame
import java.nio.file.Path
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 *  Provides the ability to trace the values of a response during replications.
 *  @param theResponse the response to trace
 *  @param pathToFile the path to the file to store the trace
 */
class ResponseTrace @JvmOverloads constructor(
    theResponse: Response,
    val pathToFile: Path = theResponse.myModel.outputDirectory.outDir.resolve(
        theResponse.name.replace(':', '_') + "_Trace"),
) : ModelElementObserver(theResponse.name) {

    private val variable = theResponse
    private val tf: TabularOutputFile
    init {
        variable.attachModelElementObserver(this)
        //n,t,x(t),t(n-1),x(t(n-1)),w,r,nr,sim,model,exp
        val columns = mapOf(
            "count" to DataType.NUMERIC,
            "time" to DataType.NUMERIC,
            "value" to DataType.NUMERIC,
            "prevTime" to DataType.NUMERIC,
            "prevValue" to DataType.NUMERIC,
            "weight" to DataType.NUMERIC,
            "repNum" to DataType.NUMERIC,
            "repObsNum" to DataType.NUMERIC,
            "simName" to DataType.TEXT,
            "modelName" to DataType.TEXT,
            "expName" to DataType.TEXT
        )
        tf = TabularOutputFile(columns, pathToFile)
    }
    private val row: RowSetterIfc = tf.row()
    private var count: Double = 0.0
    private var myRepObservationCount: Int = 0
    private var myRepNum = 0.0

    constructor(
        theResponse: ResponseCIfc,
        pathToFile: Path = (theResponse as Response).myModel.outputDirectory.outDir.resolve(
            theResponse.name.replace(':', '_') + "_Trace"),
    ) : this(theResponse as Response, pathToFile)
    /**
     * The maximum number of replications to include in the trace.
     * Once the maximum is reached no further replications are traced.
     */
    var maxNumReplications: Int = Int.MAX_VALUE
        set(value) {
            require(value > 0){"The maximum number of replications to observe must > 0"}
            field = value
        }
    /**
     *  The maximum number of observations to trace within each replication.
     *  Once the maximum is reached no further collection occurs within
     *  the replication.
     */
    var maxNumObsPerReplication: Long = Long.MAX_VALUE
        set(value) {
            require(value > 0){"The maximum number of observations per replications to observe must > 0"}
            field = value
        }
    /**
     *  The maximum number of observations to collect across all replications.
     *  Once the maximum is reached no further collection occurs.
     */
    var maxNumObservations: Double = Double.MAX_VALUE
        set(value) {
            require(value > 0){"The maximum number of observations to observe must > 0"}
            field = value
        }

    var maxRepObsTime: Double = Double.MAX_VALUE
        set(value) {
            require(value > 0){"The maximum time for each replication to observe must be > 0"}
            field = value
        }

    override fun update(modelElement: ModelElement) {
        val model = variable.model
        count++
        if (count >= maxNumObservations) {
            return
        }
        if (myRepNum != model.currentReplicationNumber.toDouble()) {
            myRepObservationCount = 0
        }
        myRepObservationCount++
        if (myRepObservationCount >= maxNumObsPerReplication) {
            return
        }
        if (variable.timeOfChange > maxRepObsTime){
            return
        }
        myRepNum = model.currentReplicationNumber.toDouble()
        if (myRepNum > maxNumReplications) {
            return
        }
        row.setNumeric("count", count)
        row.setNumeric("time", variable.timeOfChange)
        row.setNumeric("value", variable.value)
        row.setNumeric("prevTime", variable.previousTimeOfChange)
        row.setNumeric("prevValue", variable.previousValue)
        row.setNumeric("weight", variable.timeOfChange - variable.previousTimeOfChange)
        row.setNumeric("repNum", myRepNum)
        row.setNumeric("repObsNum", myRepObservationCount.toDouble())
        row.setText("simName", model.simulationName)
        row.setText("modelName", model.name)
        row.setText("expName", model.experimentName)
        tf.writeRow(row)
    }

    override fun afterReplication(modelElement: ModelElement) {
        tf.flushRows()
    }

    /**
     * `true` when the traced response is a [TWResponse] (time-weighted,
     * step-function semantics); `false` for observation-based [Response] variables.
     *
     * Reporting extensions use this to auto-select between a state-variable
     * sample-path plot and an observations plot.
     */
    val isTimeWeighted: Boolean
        get() = variable is TWResponse

    /**
     * Returns the distinct replication numbers present in the trace, in
     * ascending order.  Replications that were not recorded (e.g. because
     * [maxNumReplications] was reached) are not included.
     */
    val replicationNumbers: List<Int>
        get() = selectRepNums()

    private fun createSelectRepNumsSQL(): String {
        return "select distinct repNum from ${tf.dataTableName} order by repNum"
    }

    private fun selectRepNums(): List<Int> {
        val sql = createSelectRepNumsSQL()
        val rs = tf.myDb.fetchCachedRowSet(sql)
        val myRepNums = mutableListOf<Int>()
        if (rs != null) {
            val myRc = rs.toCollection("repNum")
            for (item in myRc) {
                if (item is Double) {
                    myRepNums.add(item.toInt())
                }
            }
            rs.close()
        }
        return myRepNums
    }

    private fun createSelectTimeValueSQL(repNum: Double, time: Double): String {
        require(repNum > 0.0) {"The replication number must be > 0"}
        require(time >= 0.0) {"The time must be >= 0.0"}
        return "select time, value from ${tf.dataTableName} where repNum = $repNum and time <= $time"
    }

    private fun createSelectTimeValueSQL(repNum: Int, startTime: Double, endTime: Double): String {
        require(repNum > 0)           { "The replication number must be > 0" }
        require(startTime >= 0.0)     { "The start time must be >= 0.0" }
        require(endTime >= startTime) { "The end time must be >= startTime" }
        return "select time, value from ${tf.dataTableName} " +
               "where repNum = $repNum and time >= $startTime and time <= $endTime"
    }

    /**
     *  Returns a map that has the times and values for the provided replication [repNum]
     *  up to and including the [time]. The default value of time is Double.MAX_VALUE,
     *  which will result in all values for the replication.
     *  Element "times" holds the times that the variable changed
     *  Element "values" holds the values associated with each time change.
     */
    fun traceDataMap(repNum: Double, time: Double = Double.MAX_VALUE) : Map<String, DoubleArray>{
        return selectTimeAndValue(repNum, time)
    }

    /**
     * Returns a map containing the times and values for replication [repNum]
     * within the time window [[startTime], [endTime]].
     *
     * The default window covers the entire replication.  Setting [startTime]
     * to the model warm-up length excludes transient data from the plot or
     * analysis.
     *
     * Element `"times"`  holds the simulation times at which the variable changed.
     * Element `"values"` holds the corresponding values.
     *
     * @param repNum    replication number; must be > 0
     * @param startTime lower bound of the time window (inclusive); defaults to 0.0
     * @param endTime   upper bound of the time window (inclusive); defaults to [Double.MAX_VALUE]
     */
    fun traceDataMap(
        repNum: Int,
        startTime: Double = 0.0,
        endTime: Double = Double.MAX_VALUE
    ): Map<String, DoubleArray> {
        return selectTimeAndValue(repNum, startTime, endTime)
    }

    /**
     * Returns a map of replication number → trace data map for each replication
     * in [repNums] over the time window [[startTime], [endTime]].
     *
     * Each replication is retrieved independently; replications absent from
     * the trace produce empty inner maps.  The returned map preserves the
     * order of [repNums].
     *
     * The default for [repNums] is [replicationNumbers] (all recorded replications).
     * For large traces, pass an explicit subset to limit how much data is loaded.
     *
     * @param repNums   replication numbers to retrieve; defaults to all in the trace
     * @param startTime lower bound of the time window; defaults to 0.0
     * @param endTime   upper bound of the time window; defaults to [Double.MAX_VALUE]
     */
    fun traceDataMaps(
        repNums: List<Int> = replicationNumbers,
        startTime: Double = 0.0,
        endTime: Double = Double.MAX_VALUE
    ): Map<Int, Map<String, DoubleArray>> {
        val myResult = linkedMapOf<Int, Map<String, DoubleArray>>()
        for (myRepNum in repNums) {
            myResult[myRepNum] = traceDataMap(myRepNum, startTime, endTime)
        }
        return myResult
    }

    private fun selectTimeAndValue(repNum: Double, time: Double): Map<String, DoubleArray> {
        val sql = createSelectTimeValueSQL(repNum, time)
        val rs  = tf.myDb.fetchCachedRowSet(sql)
        val dataMap = mutableMapOf<String, DoubleArray>()
        if (rs != null){
            val vc = rs.toCollection("value")
            val tc = rs.toCollection("time")
            val values = mutableListOf<Double>()
            for(item in vc){
                if (item is Double){
                    values.add(item)
                }
            }
            val times = mutableListOf<Double>()
            for(item in tc){
                if (item is Double){
                    times.add(item)
                }
            }
            dataMap["times"] = times.toDoubleArray()
            dataMap["values"] = values.toDoubleArray()
            rs.close()
        }
        return dataMap
    }

    private fun selectTimeAndValue(repNum: Int, startTime: Double, endTime: Double): Map<String, DoubleArray> {
        val sql = createSelectTimeValueSQL(repNum, startTime, endTime)
        val rs = tf.myDb.fetchCachedRowSet(sql)
        val myDataMap = mutableMapOf<String, DoubleArray>()
        if (rs != null) {
            val myVc = rs.toCollection("value")
            val myTc = rs.toCollection("time")
            val myValues = mutableListOf<Double>()
            for (item in myVc) { if (item is Double) myValues.add(item) }
            val myTimes = mutableListOf<Double>()
            for (item in myTc) { if (item is Double) myTimes.add(item) }
            myDataMap["times"]  = myTimes.toDoubleArray()
            myDataMap["values"] = myValues.toDoubleArray()
            rs.close()
        }
        return myDataMap
    }

    fun asDataFrame() : AnyFrame {
        return tf.asDataFrame()
    }

    fun asTabularInputFile() : TabularInputFile{
        return tf.asTabularInputFile()
    }

    fun asDatabase() : DatabaseIfc{
        return tf.asDatabase()
    }
}