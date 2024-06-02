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

package ksl.utilities.random.rvariable.parameters

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.utilities.maps.KSLMaps
import ksl.utilities.maps.toJson
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ParameterizedRV
import java.lang.StringBuilder

/**
 *  A data transfer class holding the information about a random
 *  variable's parameters. Used primarily to store the data within
 *  the KSL database.
 */
data class RVParameterData(
    val clazzName: String,
    val elementId: Int,
    val dataType: String,
    val rvName: String,
    val paramName: String,
    val paramValue: Double
)

/**
 *  The purpose of this class is to work with a model instance to
 *  facilitate the changes of parameters associated with random variables.
 *  The parameters of random variables can be changes and then applied
 *  to the model. A change in the underlying data is not applied to the
 *  associated model until explicitly applied to the model.
 *
 */
class RVParameterSetter(private val model: Model) {

    /**
     *  The key to this map is the unique name of the random variable
     *  within the model. The associated value is an instance of
     *  RVParameters which can be used to get the associated parameters
     *  and to change the parameter values.
     *  The map cannot be modified, but the values can be retrieved and changed
     *  as needed. Changing the values have no effect within the model until they are applied.
     *
     * @return parameters for every parameterized random variable within the model
    */
    val rvParameters: Map<String, RVParameters>

    /**
     *  The parameters held by the model in a list of a data class
     */
    val rvParametersData: List<RVParameterData>
        get() = extractParameters().second

    init{
        val pair = extractParameters()
        rvParameters = pair.first
 //       rvParametersData = pair.second
    }

    /**
     *  The model element name of the associated model
     */
    val modelName: String = model.name

    /**
     *  The id of the associated model
     */
    val modelId = model.id

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Model: $modelName")
        sb.appendLine("Model Id: $modelId")
        sb.appendLine("Parameters:")
        for((key, value) in rvParameters){
            sb.appendLine("Random variable name: $key")
            sb.appendLine(value)
        }
        return sb.toString()
    }

    /**
     * @return the extracted parameters
     */
    private fun extractParameters() : Pair<Map<String, RVParameters>, List<RVParameterData>> {
        val rvList: List<RandomVariable> = model.randomVariables()
        val rvDataList = mutableListOf<RVParameterData>()
        val rvMap = mutableMapOf<String, RVParameters>()
        for (rv in rvList) {
            //TODO is it possible to extract the name of the property to which
            // the random variable is assigned?
            val rs: RandomIfc = rv.initialRandomSource
            if (rs is ParameterizedRV) {
                rvMap[rv.name] = rs.parameters
                rvDataList.addAll(rs.parameters.extractParameterData(rv.id, rv.name))
            }
        }
        // ignores any double[] parameters
        return Pair(rvMap, rvDataList)
    }

    /**
     * Converts double and integer parameters to a Map that holds a Map, with the
     * outer key being the random variable name and the inner map the parameter names
     * as keys and the parameter values as values.  Ignores any double array parameters
     * and converts any integer parameter values to double values. The resulting
     * map can be used for data transfer.
     *
     * @return the parameters as a map of maps
     */
    val parametersAsDoubles: Map<String, MutableMap<String, Double>>
        get() {
            val theMap = LinkedHashMap<String, MutableMap<String, Double>>()
            for ((rvName, parameters) in rvParameters) {
                if (!theMap.containsKey(rvName)) {
                    val innerMap: MutableMap<String, Double> = LinkedHashMap()
                    theMap[rvName] = innerMap
                }
                val innerMap = theMap[rvName]!!
                for (pName in parameters.doubleParameterNames) {
                    innerMap[pName] = parameters.doubleParameter(pName)
                }
                for (pName in parameters.integerParameterNames) {
                    val v: Int = parameters.integerParameter(pName)
                    innerMap[pName] = v.toDouble()
                }
                // ignore any double[] parameters
            }
            return theMap
        }

    /**
     *  Converts the results of the property parametersAsDoubles to a
     *  Json string
     */
    fun parametersAsJson(): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(parametersAsDoubles)
    }

    /**
     *  Converts the results of the property flatParametersAsDoubles
     *  to a Json string
     */
    fun flatParametersAsJson() : String {
        return flatParametersAsDoubles.toJson()
    }

    /**
     * Uses parametersAsDoubles to get a map of map, then flattens the map
     * to a single map with the key as the concatenated key of the outer and inner keys
     * concatenated with the current value of the rvParamConCatString character string,
     * which is "." by default.
     *
     * The combined key needs to be unique and not be present within the random variable names.
     *
     * @return the flattened map
     */
    val flatParametersAsDoubles: Map<String, Double>
        get() = flatParametersAsDoubles(rvParamConCatChar)

    /**
     * Uses parametersAsDoubles to get a map of map, then flattens the map
     * to a single map with the key as the concatenated key of the outer and inner keys
     * concatenated with the supplied character string. The combined key needs to be unique
     * and not be present within the random variable names.
     *
     * @param conCatString the char to form the common key
     * @return the flattened map
     */
    fun flatParametersAsDoubles(conCatString: Char): Map<String, Double> {
        return KSLMaps.flattenMap(parametersAsDoubles, conCatString.toString())
    }

    /**
     * A convenience method that will set any Double or Integer parameter to the
     * supplied double value provided that the named random variable is
     * available to be set, and it has the named parameter.
     *
     * The inner map represents the parameters to change.
     * Double values are coerced to Integer values
     * by rounding up. If the key of the supplied map representing the
     * random variable to change is not found, then no change occurs.
     * If the parameter name is not found for the named random variable's parameters
     * then no change occurs.  In other words, the change "fails silently"
     *
     * @param settings the map of settings
     */
    fun changeParameters(settings: Map<String, Map<String, Double>>) {
        for ((rvName) in settings) {
            if (rvParameters.containsKey(rvName)) {
                for ((paramName, value) in settings[rvName]!!) {
                    changeParameter(rvName, paramName, value)
                }
            }
        }
    }

    /**
     * A convenience method to change the named parameter of the named random variable
     * to the supplied value. This will work with either double or integer parameters.
     * Integer parameters are coerced to the rounded up value of the supplied double,
     * provided that the integer can hold the supplied value.  If the named
     * random variable is not in the setter, then no value will change. If the named
     * parameter is not associated with the random variable type, then no change occurs.
     * In other words, the action fails, silently by returning false.
     *
     * @param rvName    the name of the random variable to change, must not be null
     * @param paramName the parameter name of the random variable, must not be null
     * @param value     the value to change to
     * @return true if the value was changed, false if no change occurred
     */
    fun changeParameter(rvName: String, paramName: String, value: Double): Boolean {
        if (!rvParameters.containsKey(rvName)) {
            return false
        }
        val parameters: RVParameters? = rvParameters[rvName]
        return if (!parameters!!.containsParameter(paramName)) {
            false
        } else parameters.changeParameter(paramName, value)
        // ask the parameter to make the change
    }

    /**
     * A convenience method to check if the named random variable exists and
     * if so, if the named paramName exists for it parameters
     *
     * @param rvName    the name of the random variable to change, must not be null
     * @param paramName the parameter name of the random variable, must not be null
     * @return true if the named random variable has a parameter with the provided name
     * False if the name random variable does not exist or the parameter name doesn't exit for it.
     */
    fun containsParameter(rvName: String, paramName: String) : Boolean{
        if (!rvParameters.containsKey(rvName)) {
            return false
        }
        return rvParameters[rvName]!!.containsParameter(paramName)
    }

//    /**
//     * The returned map cannot be modified, but the values can be retrieved and changed
//     * as needed. Changing the values have no effect within the model until they are applied.
//     *
//     * @return parameters for every parameterized random variable within the model
//     */
//    val allRVParameters: Map<String, RVParameters>
//        get() = rvParameters

    /**
     * Gets a parameters instance for the named random variable. This will be the current parameter settings being
     * used in the model.  This instance can be changed and then applied to the model.
     *
     * @param rvName the name of the random variable from the model, must not be null and must be in the model
     * @return the parameters associated with the named random variable
     */
    fun rvParameters(rvName: String): RVParameters {
        require(rvParameters.containsKey(rvName)) { "The supplied name is not a valid random variable name" }
        return rvParameters[rvName]!!
    }

    /**
     * @return the list of names for the random variables that are parameterized
     */
    val randomVariableNames: List<String>
        get() = ArrayList(rvParameters.keys)

    /**
     * @return the number of parameterized random variables that can be changed
     */
    val numberOfParameterizedRandomVariables: Int
        get() = rvParameters.size

    /** If a RVParameterSetter has been created based on a model, then
     *  this method can be used to apply changed parameters to the model.
     *  The primary use of the function occurs in the setUpExperiment()
     *  function of the model.  If the model has a RVParameterSetter
     *  then it is used to apply any potential parameter changes to the model
     *  automatically prior to running the experiment.
     *  This method could be used at any time to change the parameters of the model;
     *  however, changing the parameter setting during the execution of a model
     *  is highly discouraged.  The parameter changes ensure that the underlying
     *  random number stream remains the same.
     *
     * @return the number of parameterized random variables that had their parameters changed in some way
     */
    fun applyParameterChanges(model: Model): Int {
        require(modelName == model.name) {
            "Cannot apply parameters from model, " + model.name +
                    ", to model " + modelName
        }
        require(modelId == model.id) {
            "Cannot apply parameters from model id =, " + model.id +
                    ", to model with id = " + modelId
        }
        if (model.isRunning) {
            Model.logger.warn { "The model was running when attempting to apply parameter changes" }
        }
        var countChanged = 0
        val rvList: List<RandomVariable> = model.randomVariables()
        for (rv in rvList) {
            val rs: RandomIfc = rv.initialRandomSource
            if (rs is ParameterizedRV) {
                val rvName: String = rv.name
                // compare the map entries
                val toBe: RVParameters? = rvParameters[rvName]
                val current: RVParameters = rs.parameters
//                println("rv = $rvName")
//                println("current = $current")
//                println("toBe = $toBe")
                if (current != toBe) {
//                    println("current and toBe are different, make changes")
                    // change has occurred
                    countChanged++
                    rv.initialRandomSource = toBe!!.createRVariable(rv.streamNumber)
                }
            }
        }
        Model.logger.info{ "$countChanged out of $numberOfParameterizedRandomVariables random variable parameters were changed in the model via the parameter setter."}
        return countChanged
    }

    companion object {
        /**
         * The string used to flatten or un-flatten random variable parameters
         * Assumed as "." by default
         */
 //       var rvParamConCatString = "_PARAM_"
        var rvParamConCatChar = '.'

        /**
         *  Splits the key into two strings based on the [catChar] regular expression. Since the
         *  flattened map of random variable parameters has a key that concatenates
         *  the name of the random variable with the parameter's name, this function
         *  can be used to separate them. The default concatenation string is held
         *  in rvParamConCatString, which is "." by default.
         */
        fun splitFlattenedRVKey(key: String, catChar: Char = rvParamConCatChar): Array<String> {
            return key.split(catChar, limit = 2).toTypedArray()
        }

    }
}