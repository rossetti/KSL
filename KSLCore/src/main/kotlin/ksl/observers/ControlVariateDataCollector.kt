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

import ksl.modeling.variable.RandomSourceCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.KSLArrays
import ksl.utilities.io.toDataFrame
import ksl.utilities.statistic.BatchStatistic
import ksl.utilities.statistic.RegressionData
import ksl.utilities.transpose
import org.jetbrains.kotlinx.dataframe.AnyFrame

/**
 * Defines responses and controls for a control variate experiment. Collects the
 * replication responses for the responses and for the controls.
 * Must be created prior to running the simulation to actually
 * collect any data. Uses a ReplicationDataCollector
 */
class ControlVariateDataCollector(model: Model, name: String? = null) : ModelElement(model, name) {
    /**
     *  Collects the responses, including the responses created for each control
     */
    private val myResponseCollector: ReplicationDataCollector = ReplicationDataCollector(model)

    /**
     *  There can be more than one response associated with the controls.
     *  Holds the alias for the response as the key and the response.
     */
    private val myResponses: MutableMap<String, Response> = mutableMapOf()

    /**
     *  Holds the alias for the control as the key and the random variable representing the control
     */
    private val myControls: MutableMap<String, RandomVariable> = mutableMapOf()

    /**
     *  Holds the name and the mean of the control
     */
    private val myControlMeans = mutableMapOf<String, Double>()

    /**
     *  the control as a random variable and the associated response
     */
    private val myControlResponses: MutableMap<RandomVariable, Response> = mutableMapOf()

    /**
     *  Used to observe the random variables (as controls) to collect their responses
     */
    private val myRVObserver = RVObserver()

    private inner class RVObserver : ModelElementObserver() {
        override fun update(modelElement: ModelElement) {
            observeRandomVariable(modelElement as RandomVariable)
        }
    }

    private fun observeRandomVariable(rv: RandomVariable) {
        val response = myControlResponses[rv]
        response?.value = rv.previousValue
    }

    /** The supplied name must be the name of the model element representing
     * the desired response. If the name does not exist in the model, then
     * nothing is added and no errors occur. Thus, this method fails silently.
     *
     * @param responseName the name of the response to add for collection
     */
    fun addResponse(responseName: String) {
        val responseVariable: Response? = myModel.response(responseName)
        if (responseVariable != null) {
            addResponse(responseVariable)
        }
    }

    /**
     *  The [response] is added to the control variate data collector with the supplied
     *  [responseAlias] response alias. The default response alias is the name of
     *  the response within the model. If the response alias has already been added, then
     *  an exception occurs. That is, the response alias must be unique to the collector.
     */
    fun addResponse(response: ResponseCIfc, responseAlias: String = response.name) {
        // only allow unique aliases
        require(!myResponses.contains(responseAlias)) { "The supplied response has already been added!" }
        val r = response as Response
        require(myModel.containsModelElement(r.name)) { "The supplied response was not part of the associated model!" }
        myResponses[responseAlias] = r
        myResponseCollector.addResponse(r)
    }

    /** If the random source doesn't exist in the model then an error occurs
     *
     * @param rvSource the RandomSourceCIfc to add as a control. It must be a RandomVariable in the model
     * @param meanValue the mean of the control.
     * @param controlAlias the alias to use for the control. By default, it is the name of the RandomSourceCIfc.
     * @return the control as a response
     */
    fun addControlVariate(
        rvSource: RandomSourceCIfc,
        meanValue: Double,
        controlAlias: String = rvSource.name
    ): ResponseCIfc {
        // only allow unique aliases
        require(!myControls.contains(controlAlias)) { "The supplied control has already been added!" }
        require(myModel.containsModelElement(rvSource.name)) { "The supplied random source was not part of the associated model!" }
        require(rvSource is RandomVariable) { "The random source does not refer to a random variable in the model" }
        // remember the mean
        myControlMeans[controlAlias] = meanValue
        // remember the control
        myControls[controlAlias] = rvSource
        // attach the observer of rv value
        rvSource.attachModelElementObserver(myRVObserver)
        // create the response for the control
        val response = Response(this, "${rvSource.name}:CVResponse")
        myResponseCollector.addResponse(response)
        // remember the response for the rv
        myControlResponses[rvSource] = response
        return response
    }

    /**  If the RandomVariable doesn't exist in the model then an error occurs
     *
     * @param randomVariableName the name of the RandomVariable to add as a control
     * @param meanValue the mean of the RandomVariable
     * @return the name of the control response or null
     */
    fun addControlVariate(randomVariableName: String, meanValue: Double): ResponseCIfc {
        require(myModel.containsModelElement(randomVariableName)) { "The supplied random variable name is not part of the model" }
        val me = myModel.getModelElement(randomVariableName)!!
        require(me is RandomVariable) { "The name does not refer to a random variable in the model" }
        return addControlVariate(me, meanValue)
    }

    /**
     *  The number of replications observed
     */
    val numReplications: Int
        get() = myResponseCollector.numReplications

    /**
     * @return the number of responses
     */
    val numberOfResponses: Int
        get() = myResponses.size

    /**
     * @return the number of controls
     */
    fun numberOfControlVariates(): Int {
        return myControls.size
    }

    /**
     *
     * @return a list holding the names of the responses
     */
    fun responseNames(): List<String> {
        val list = mutableListOf<String>()
        for (r in myResponses) {
            list.add(r.key)
        }
        return list
    }

    /**
     * The control names
     *
     * @return a copy of the names of the controls
     */
    fun controlNames(): List<String> {
        val list = mutableListOf<String>()
        for (name in myControls.keys) {
            list.add(name)
        }
        return list
    }

    /**
     *
     * @return gets all the names, first responses, then controls in that order
     */
    fun allNames(): List<String> {
        val names = responseNames().toMutableList()
        names.addAll(controlNames())
        return names
    }

    /**
     * @param responseName the name of the response
     * @return the collected replication averages, each row is a replication
     */
    fun responseReplicationData(responseName: String): DoubleArray {
        require(myResponses.containsKey(responseName)) { "The response name was not found for the collector!" }
        // assume that the response name is the alias, get the response from it
        val r = myResponses[responseName]!!
        return myResponseCollector.replicationAverages(r)
    }

    /**
     * @param controlName the name of the control
     * @return the collected replication averages minus the control mean, each row is a replication
     */
    fun controlReplicationData(controlName: String): DoubleArray {
        require(myControls.containsKey(controlName)) { "The supplied name was not a valid control name" }
        val rv = myControls[controlName]
        val response = myControlResponses[rv]!!
        val data: DoubleArray = myResponseCollector.replicationAverages(response)
        val mean = myControlMeans[controlName]!!
        return KSLArrays.subtractConstant(data, mean)
    }

    /**
     *  Returns a k by n matrix of data that represents the control data
     *  where n is the number of observations, and k is the number of controls.
     *  Each row of the returned matrix represents the observations for a different control.
     *  The row are ordered by the names of the controls.
     */
    private fun controlsData(controls: List<String>): Array<DoubleArray> {
        val numRows: Int = myResponseCollector.numReplications
        val numCols = numberOfControlVariates()
        val data = Array(numCols) { DoubleArray(numRows) }
        for ((j, name) in controls.withIndex()) {
            if (myControls.containsKey(name)) {
                data[j] = controlReplicationData(name)
            }
        }
        return data
    }

    /**
     *  Returns the collected data ready for regression in the form of CVData.
     */
    fun collectedData(
        responseName: String,
        numBatches: Int = numReplications,
        controlNames: List<String> = controlNames()
    ): RegressionData {
        require(numBatches <= numReplications) { "The number of batches must be <= the number of replications." }
        // if numBatches is numReplications, then no batching should occur
        // that is each replication is an observation
        val response = responseReplicationData(responseName)
        val controls = controlsData(controlNames)
        if (numBatches == numReplications) {
            return RegressionData(response, controls.transpose())
        } else {
            // do the batching
            val rbm = BatchStatistic.batchMeans(response, numBatches)
            val cbm = BatchStatistic.batchMeans(controls, numBatches)
            return RegressionData(rbm, cbm.transpose())
        }
    }

    /** The response data and then the control data is returned in the map.
     *
     * @return a map holding the response and control names as keys and replication averages as the arrays
     */
    fun collectedDataAsMap(): Map<String, DoubleArray> {
        val dataMap: MutableMap<String, DoubleArray> = LinkedHashMap()
        for (r in myResponses) {
            val x = responseReplicationData(r.key)
            dataMap[r.key] = x
        }
        val controlNames = controlNames()
        for (name in controlNames) {
            val x = controlReplicationData(name)
            dataMap[name] = x
        }
        return dataMap
    }

    /**
     *
     * @return a dataframe holding the response and control names as column names and replication averages within the columns.
     */
    fun toDataFrame(): AnyFrame {
        return collectedDataAsMap().toDataFrame()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Control Variate Collector")
        sb.appendLine("Responses:")
        for (r in myResponses) {
            sb.appendLine("response: ${r.key}")
        }
        sb.appendLine()
        sb.appendLine("Controls:")
        for ((c, mean) in myControlMeans) {
            sb.appendLine("control: $c \t mean = $mean")
        }
        sb.appendLine()
        sb.appendLine("Replication Data Collector")
        sb.appendLine(myResponseCollector)
        sb.appendLine()
        val df = toDataFrame()
        sb.appendLine("Control Variate Data")
        sb.append(df.toString())
//        val headerFmt = "%-20s %-5s"
//        val rowFmt = "%10.3f %-3s"
//        val dataAsMap = collectedDataAsMap()
//        for (name in dataAsMap.keys) {
//            val x = dataAsMap[name]
//            sb.append(headerFmt.format(name, "|"))
//            for (v in x!!) {
//                sb.append(rowFmt.format(v, "|"))
//            }
//            sb.appendLine()
//        }
        return sb.toString()
    }
}