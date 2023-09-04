package ksl.observers

import ksl.modeling.variable.Response
import ksl.simulation.ModelElement
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.tabularfiles.DataType
import ksl.utilities.io.tabularfiles.RowSetterIfc
import ksl.utilities.io.tabularfiles.TabularOutputFile
import java.nio.file.Path
import java.sql.PreparedStatement
import java.sql.SQLException

class ResponseTrace(
    theResponse: Response,
    val pathToFile: Path = theResponse.myModel.outputDirectory.outDir.resolve(theResponse.name + "_Trace"),
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

    /**
     * The maximum number of replications to include in the trace.
     * Once the maximum is reached no further replications are traced.
     */
    var maxNumReplications: Int = Int.MAX_VALUE

    /**
     *  The maximum number of observations to trace within each replication.
     *  Once the maximum is reached no further collection occurs within
     *  the replication.
     */
    var maxNumObsPerReplication: Long = Long.MAX_VALUE

    /**
     *  The maximum number of observations to collect across all replications.
     *  Once the maximum is reached no further collection occurs.
     */
    var maxNumObservations: Double = Double.MAX_VALUE

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

}