/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.observers

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.dbutil.WithinRepViewData
import ksl.utilities.collections.KSLMaps
import ksl.utilities.collections.toMapOfLists
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

//import java.util.*

/**
 * Collects and stores the replication average for each specified response or final value for each counter. Must
 * be created prior to running the simulation for any data to be collected.  The added responses or counters
 * must already be part of the model.  This is important. Only those responses or counters that already exist
 * in the model hierarchy will be added automatically if you use the automatic add option.
 *
 * The collector collects data at the end of each replication.  Running the simulation multiple times
 * within the same execution will record over any data from a previous simulation run.  Use the
 * various methods to save the data if it is needed prior to running the simulation again. Or, remove
 * the collector as an observer of the model prior to running subsequent simulations.
 * @param model the model that has the responses, must not be null
 * @param addAll if true then ALL currently defined response variables and counters within the
 * @param autoAttach causes the collector to automatically be attached as an observer
 * to the model when created. The default is true. If not attached automatically,
 * then the startObserving() function needs to be called before running the model.
 * model will be automatically added to the data collector. The default is false.
 */
class ReplicationDataCollector(
    model: Model,
    addAll: Boolean = false,
    autoAttach: Boolean = true
) {
    private val myResponses: MutableList<ResponseCIfc> = mutableListOf()
    private val myCounters: MutableList<CounterCIfc> = mutableListOf()
    private val myModel: Model = model
    private val myResponseData: MutableMap<String, DoubleArray> = mutableMapOf()

    /**
     * the number of replications collected so far
     */
    var numReplications : Int = 0
        private set
    private val modelObserver: ModelObserver = ModelObserver()

    init {
        if (autoAttach) {
            model.attachModelElementObserver(modelObserver)
        }
        if (addAll) {
            addAllResponsesAndCounters()
        }
    }

    /**
     * Start observing the model
     */
    fun startObserving() {
        if (!myModel.isModelElementObserverAttached(modelObserver)) {
            myModel.attachModelElementObserver(modelObserver)
        }
    }

    /**
     * Stop observing the model
     */
    fun stopObserving() {
        if (myModel.isModelElementObserverAttached(modelObserver)) {
            myModel.detachModelElementObserver(modelObserver)
        }
    }

    internal fun beforeExperiment() {
        numReplications = 0
        myResponseData.clear()
        val numRows: Int = myModel.numberOfReplications
        for (r in myResponses) {
            myResponseData[r.name] = DoubleArray(numRows)
        }
        for (c in myCounters) {
            myResponseData[c.name] = DoubleArray(numRows)
        }
    }

    internal fun afterReplication() {
        numReplications = myModel.currentReplicationNumber
        val row = numReplications - 1
        for (r in myResponses) {
            val data = myResponseData[r.name]
            data!![row] = r.withinReplicationStatistic.weightedAverage
        }
        for (c in myCounters) {
            val data = myResponseData[c.name]
            data!![row] = c.value
        }
    }

    private inner class ModelObserver : ModelElementObserver() {
        override fun beforeExperiment(modelElement: ModelElement) {
            this@ReplicationDataCollector.beforeExperiment()
        }

        override fun afterReplication(modelElement: ModelElement) {
            this@ReplicationDataCollector.afterReplication()
        }
    }

    /**
     * Adds all response variables and counters that are in the model to the data collector
     */
    fun addAllResponsesAndCounters() {
        val counterList: List<CounterCIfc> = myModel.counters
        for (counter in counterList) {
            addCounterResponse(counter)
        }
        val responseVariables: List<ResponseCIfc> = myModel.responses
        for (r in responseVariables) {
            addResponse(r)
        }
    }

    /**
     * @param responseName the name of the response within the model, must be in the model
     */
    fun addResponse(responseName: String) {
        val responseVariable: Response? = myModel.response(responseName)
        if (responseVariable != null) {
            addResponse(responseVariable)
        }
    }

    /**
     * @param response the response within the model to collect and store data for, must
     * not be null
     */
    fun addResponse(response: Response) {
        if (myResponses.contains(response)) {
            return
        }
        myResponses.add(response)
    }

    /**
     * @param response the response within the model to collect and store data for, must
     * not be null
     */
    fun addResponse(response: ResponseCIfc) {
        addResponse(response as Response)
    }

    /**
     * @param counterName the name of the counter within the model, must be in the model
     */
    fun addCounterResponse(counterName: String) {
        val counter: Counter? = myModel.counter(counterName)
        if (counter != null) {
            addCounterResponse(counter)
        }
    }

    /**
     * @param counter the counter within the model to collect and store data for, must
     * not be null
     */
    fun addCounterResponse(counter: Counter) {
        if (myCounters.contains(counter)) {
            return
        }
        myCounters.add(counter)
    }

    /**
     * @param counter the counter within the model to collect and store data for, must
     * not be null
     */
    fun addCounterResponse(counter: CounterCIfc) {
        addCounterResponse(counter as Counter)
    }

    /**
     * @return the number of responses to collect
     */
    val numberOfResponses: Int
        get() = myResponses.size + myCounters.size

    /**
     *
     * @return a list holding the names of the responses and counters
     */
    val responseNames: List<String>
        get() {
            val list: MutableList<String> = ArrayList()
            for (r in myResponses) {
                list.add(r.name)
            }
            for (c in myCounters) {
                list.add(c.name)
            }
            return list
        }

    /**
     *
     * @param responseName the name of the response or counter in the model
     * @return true if the name is present, false otherwise
     */
    operator fun contains(responseName: String): Boolean {
        return myResponseData.containsKey(responseName)
    }

    /** If the response name does not exist in the collector a zero length array is returned.
     * The array elements represent the within replication average for the response or
     * the ending value of the counter.
     *
     * Note that the returned array may contain Double.NaN values if no observations
     * of the response were observed during the replication.
     *
     * @param responseName the name of the response or counter, must be in the model
     * @return the replication averages for the named response
     */
    fun replicationData(responseName: String): DoubleArray {
        val data = myResponseData[responseName] ?: return DoubleArray(0)
        return data.copyOf(data.size)
    }

    /** If the response name does not exist in the collector a zero length array is returned.
     * The array elements represent the within replication average for the response.
     *
     * Note that the returned array may contain Double.NaN values if no observations
     * of the response were observed during the replication.
     *
     * @param responseVariable the response variable, must not be null and must be in model
     * @return the replication averages for the named response
     */
    fun replicationAverages(responseVariable: ResponseCIfc): DoubleArray {
        return replicationData(responseVariable.name)
    }

    /** If the counter name does not exist in the collector a zero length array is returned.
     * The array elements represent the ending value of the counter within each replication.
     *
     * @param counter the counter, must not be null and must be in model
     * @return the counter final values for the named counter
     */
    fun finalReplicationValues(counter: CounterCIfc): DoubleArray {
        return replicationData(counter.name)
    }

    /**
     * The responses are ordered in the same order as returned by property responseNames and
     * are the columns, each row for a column is the replication average, row 0 is replication 1
     *
     * The array elements represent the within replication average for the responses or
     * the ending value of the counters.
     *
     * Note that the returned array may contain Double.NaN values if no observations
     * of the response were observed during the replication.
     *
     * @return the replication averages for each response or value for counter for each replication
     */
    val allReplicationData: Array<DoubleArray>
        get() {
            val data = Array(myResponses.size) {
                DoubleArray(
                    numReplications
                )
            }
            val names = responseNames
            for ((column, name) in names.withIndex()) {
                val x = myResponseData[name]
                for (i in x!!.indices) {
                    data[i][column] = x[i]
                }
            }
            return data
        }

    /**
     * The array elements represent the within replication average for the responses or
     * the ending value of the counters.
     *
     * Note that the stored array may contain Double.NaN values if no observations
     * of the response were observed during the replication.
     *
     * @return a map holding the response name as key and the end replication data as an array
     */
    val allReplicationDataAsMap: Map<String, DoubleArray>
        get() {
            val dataMap: MutableMap<String, DoubleArray> = LinkedHashMap()
            val names = responseNames
            for (name in names) {
                val x = myResponseData[name]
                dataMap[name] = x!!.copyOf()
            }
            return dataMap
        }

    /**
     *  Provides a within replication view data representation of the
     *  collected data.
     */
    fun withRepViewData(): List<WithinRepViewData> {
        val list = mutableListOf<WithinRepViewData>()
        val names = responseNames
        for (name in names) {
            val data = myResponseData[name]!!
            for ((i, datum) in data.withIndex()) {
                val d = WithinRepViewData()
                d.exp_name = myModel.experimentName
                d.run_name = myModel.runName
                d.num_reps = myModel.numberOfReplications
                d.start_rep_id = myModel.startingRepId
                d.last_rep_id = myModel.numberReplicationsCompleted
                d.stat_name = name
                d.rep_id = i
                d.rep_value = datum
                list.add(d)
            }
        }
        return list
    }

    /**
     *  Converts the data collected by the ReplicationDataCollector to
     *  a DataFrame, with the column names as the response names and
     *  the columns holding the data across replications
     */
    fun toDataFrame(): AnyFrame {
        return allReplicationDataAsMap.toMapOfLists().toDataFrame()
    }

    /**
     * Translates property allReplicationDataAsMap to Json string
     */
    fun allReplicationDataAsJson(): String {
        return KSLMaps.stringDoubleArrayMapToJson(allReplicationDataAsMap)
    }

    override fun toString(): String {
        return toDataFrame().toString()
    }

    fun asString(): String {
        val sb = StringBuilder()
//        val fmt = Formatter(sb)
        val headerFmt = "%-20s %-5s"
        val rowFmt = "%10.3f %-3s"
        val responseNames = responseNames
        for (name in responseNames) {
            val x = myResponseData[name]
            sb.append(headerFmt.format(name, "|"))
//            fmt.format("%-20s %-5s", name, "|")
            for (v in x!!) {
                sb.append(rowFmt.format(v, "|"))
//                fmt.format("%10.3f %-3s", v, "|")
            }
            sb.appendLine()
//            fmt.format("%n")
        }
        return sb.toString()
    }
}