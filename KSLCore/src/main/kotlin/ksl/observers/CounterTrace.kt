package ksl.observers

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
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
 *  Provides the ability to trace the value of a counter during replications.
 *  @param theCounter the counter to trace
 *  @param pathToFile the path to the file to store the trace
 */
class CounterTrace @JvmOverloads constructor(
    theCounter: Counter,
    val pathToFile: Path = theCounter.myModel.outputDirectory.outDir.resolve(
        theCounter.name.replace(':', '_') + "_Trace"),
) : ModelElementObserver(theCounter.name) {

    private val variable = theCounter
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

    @JvmOverloads
    constructor(
        theCounter: CounterCIfc,
        pathToFile: Path = (theCounter as Response).myModel.outputDirectory.outDir.resolve(
            theCounter.name.replace(':', '_') + "_Trace"),
    ) : this(theCounter as Counter, pathToFile)

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

    private fun createSelectTimeValueSQL(repNum: Double, time: Double): String {
        require(repNum > 0.0) {"The replication number must be > 0"}
        require(time >= 0.0) {"The time must be >= 0.0"}
        return "select time, value from ${tf.dataTableName} where repNum = $repNum and time <= $time"
    }

    /**
     *  Returns a map that has the times and values for the provided replication [repNum]
     *  up to and including the [time]. The default value of time is Double.MAX_VALUE,
     *  which will result in all values for the replication.
     *  Element "times" holds the times that the variable changed
     *  Element "values" holds the values associated with each time change.
     */
    fun traceValues(repNum: Double, time: Double = Double.MAX_VALUE) : Map<String, DoubleArray>{
        return selectTimeAndValue(repNum, time)
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

    fun asDataFrame() : AnyFrame {
        return tf.asDataFrame()
    }

    fun asTabularInputFile() : TabularInputFile {
        return tf.asTabularInputFile()
    }

    fun asDatabase() : DatabaseIfc{
        return tf.asDatabase()
    }
}