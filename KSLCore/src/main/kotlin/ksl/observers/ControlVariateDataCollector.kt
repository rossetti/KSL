package ksl.observers

import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.KSLArrays
//import java.util.*

/**
 * Defines responses and controls for a control variate experiment. Collects the
 * replication responses for the responses and for the controls.
 * Must be created prior to running the simulation to actually
 * collect any data. Uses a ReplicationDataCollector
 */
class ControlVariateDataCollector(model: Model, name: String? = null) : ModelElement(model, name) {
    private val myResponseCollector: ReplicationDataCollector = ReplicationDataCollector(model)
    private val myResponses: MutableList<Response> = mutableListOf()
    private val myControls: MutableMap<String, Double> = mutableMapOf()
    private val myControlResponses: MutableMap<RandomVariable, Response> = mutableMapOf()
    private val myRVObserver = RVObserver()

    private inner class RVObserver : ModelElementObserver() {
        override fun update(modelElement: ModelElement) {
            observeRandomVariable(modelElement as RandomVariable)
        }
    }

    private fun observeRandomVariable(rv: RandomVariable){
        val response = myControlResponses[rv]
        response?.value = rv.previousValue
    }

    /**
     *
     * @param responseName the name of the response to add for collection
     */
    fun addResponse(responseName: String) {
        val responseVariable: Response? = myModel.getResponse(responseName)
        if (responseVariable != null){
            addResponse(responseVariable)
        }
    }

    /**
     *
     * @param response the response to add for collection
     */
    fun addResponse(response: Response) {
        myResponses.add(response)
        myResponseCollector.addResponse(response)
    }

    /**  If the RandomVariable doesn't exist in the model then no control is set up
     *
     * @param randomVariableName the name of the RandomVariable to add as a control
     * @param meanValue the mean of the RandomVariable
     * @return the name of the control response or null
     */
    fun addControlVariate(randomVariableName: String, meanValue: Double): ResponseCIfc {
        require(myModel.containsModelElement(randomVariableName)){"The supplied random variable name is not part of the model"}
        val me = myModel.getModelElement(randomVariableName)!!
        require(me is RandomVariable){"The name does not refer to a random variable in the model"}
        return addControlVariate(me, meanValue)
    }

    /** If the RandomVariable doesn't exist in the model then no control is set up
     *
     * @param rv the RandomVariable to add as a control
     * @param meanValue the mean of the RandomVariable
     * @return the name of the control response or null
     */
    fun addControlVariate(rv: RandomVariable, meanValue: Double): ResponseCIfc {
        require(myModel.containsModelElement(rv.name)){"The supplied random variable is not part of the model"}
        // add to controls and remember the mean value
        myControls[rv.name] = meanValue
        // attach the observer of rv value
        rv.attachModelElementObserver(myRVObserver)
        // create the response
        val response = Response(this, "${rv.name}:CVResponse")
        myResponseCollector.addResponse(response)
        // remember the response for the rv
        myControlResponses[rv] = response
        return response
    }

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
    fun responseNames(): MutableList<String> {
        val list: MutableList<String> = ArrayList()
        for (r in myResponses) {
            list.add(r.name)
        }
        return list
    }

    /**
     * The control names
     *
     * @return a copy of the names of the controls
     */
    fun controlNames(): List<String> {
        val list: MutableList<String> = ArrayList()
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
        val names = responseNames()
        names.addAll(controlNames())
        return names
    }

    /**
     * @param responseName the name of the response
     * @return the collected replication averages, each row is a replication
     */
    fun responseReplicationData(responseName: String): DoubleArray {
        return myResponseCollector.replicationData(responseName)
    }

    /**
     * @param controlName the name of the control
     * @return the collected replication averages minus the control mean, each row is a replication
     */
    fun controlReplicationData(controlName: String): DoubleArray {
        require(myModel.containsModelElement(controlName)){"The supplied name is not part of the model"}
        require(myControls.containsKey(controlName)) {"The supplied name was not a valid control name"}
        val data: DoubleArray = myResponseCollector.replicationData(controlName)
        val mean = myControls[controlName]!!
        return KSLArrays.subtractConstant(data, mean)
    }

    /** The replications are the rows. The columns are ordered first with response names
     * and then with control names based on the order from getResponseNames() and
     * getControlNames()
     *
     * @return the response and control data from each replication
     */
    fun getData(): Array<DoubleArray> {
        val numRows: Int = myResponseCollector.numReplications
        val numCols = numberOfResponses + numberOfControlVariates()
        val data = Array(numRows) { DoubleArray(numCols) }
        var j = 0
        for (r in myResponses) {
            val src = responseReplicationData(r.name)
            KSLArrays.fillColumn(j, src, data)
            j++
        }
        val controlNames = controlNames()
        for (name in controlNames) {
            val src = controlReplicationData(name)
            KSLArrays.fillColumn(j, src, data)
            j++
        }
        return data
    }

    /**
     *
     * @return a map holding the response and control names as keys and replication averages as an array
     */
    fun getDataAsMap(): Map<String, DoubleArray> {
        val dataMap: MutableMap<String, DoubleArray> = LinkedHashMap()
        for (r in myResponses) {
            val x = responseReplicationData(r.name)
            dataMap[r.name] = x
        }
        val controlNames = controlNames()
        for (name in controlNames) {
            val x = controlReplicationData(name)
            dataMap[name] = x
        }
        return dataMap
    }

    override fun toString(): String {
        val sb = StringBuilder()
//        val fmt = Formatter(sb)
        val headerFmt = "%-20s %-5s"
        val rowFmt = "%10.3f %-3s"
        val dataAsMap = getDataAsMap()
        for (name in dataAsMap.keys) {
            val x = dataAsMap[name]
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