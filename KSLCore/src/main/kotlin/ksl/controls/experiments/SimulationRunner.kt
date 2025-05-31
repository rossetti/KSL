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

package ksl.controls.experiments

import ksl.controls.Controls
import ksl.observers.ReplicationDataCollector
import ksl.observers.SimulationTimer
import ksl.simulation.Model
import ksl.utilities.KSLArrays
import ksl.utilities.collections.KSLMaps
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import ksl.utilities.toPrimitives
import java.io.PrintWriter
import java.io.StringWriter

/**
 *  The purpose of this class is to facilitate the running of a simulation model based
 *  on some inputs and experimental run parameters. The simulate() functions take in
 *  the inputs and the experimental run parameters, and after execution return an
 *  instance of SimulationRun.
 *
 *  There are two key issues to note when using this class. First, you should strongly
 *  consider changing the name of the experiment associated with the model when repeatedly
 *  executing the simulate() function. This will facilitate the identification of the experiment
 *  associated with the simulation run within databases and other output mechanisms.
 *
 *  The second issue is the specification for the map of inputs. This map of (String, Double)
 *  pairs represents the inputs to the simulation model as specified by controls and the
 *  parameters of random variables within the model. The naming convention is important to note.
 *  For controls, by default, the key to associate with the value is the model element's name
 *  concatenated with the property that was annotated with the control.  For example, if
 *  the resource had the name Worker and annotated property initialCapacity, then the key
 *  will be "Worker.initialCapacity". Note the use of the "." character to separate
 *  the model element name and the property name.  Since the KSL model element naming
 *  convention requires unique names for each model element, the key will be unique for the control.
 *  However, the model element name may be a very long string depending on your approach
 *  to naming the model elements. The name associated with each control can be inspected by
 *  asking the model for its controls via model.controls() and then using the methods on the Controls
 *  class for the names. The controlsMapAsJsonString() or asMap() functions are especially helpful
 *  for this purpose.
 *
 *  For the parameters associated with random variables, the naming convention is different.
 *  Again, the model element name is used as part of the identifier then the value of
 *  rvParamConCatString from the companion object is concatenated between the name of the
 *  model element and the name of its parameter.  For example, suppose there is a
 *  random variable that has been named ServiceTimeRV that is exponentially distributed.
 *  Also assume that rvParamConCatString is ".", which is its default value. Then,
 *  to access the mean of the service time random variable, we use "ServiceTimeRV.mean".
 *  Thus, it is important to note the name of the random variable within the model and the
 *  KSL's default names for the random variable parameters.  When the modeler does
 *  not explicitly name random variables, the KSL will automatically provide a default
 *  unique name. Thus, if you plan to control a specific random variable's parameters, you
 *  should strongly consider providing an explicit name. To get the names (and current values)
 *  of the random variable parameters, you can print out the toString() method of the
 *  RVParameterSetter class after getting it from the model via the model's rvParameterSetter
 *  property.
 *
 */
class SimulationRunner(
    private val model: Model
) {

    /**
     *  @param modelCreator a function that will create the model that will be executed
     */
    @Suppress("unused")
    constructor(modelCreator: () -> Model) : this(modelCreator())

    /**
     *  The model will be run with the [experimentRunParameters] and the provided [inputs]. The inputs
     *  can represent both control (key, value) pairs and random variable parameter
     *  (key, value) pairs to be applied to the experiment.  The inputs may be empty.
     *
     *  @return returns an instance of SimulationRun that holds the experiment, inputs, and results
     *  associated with the simulation run.
     */
    fun simulate(
        inputs: Map<String, Double> = mapOf(),
        experimentRunParameters: ExperimentRunParameters = model.extractRunParameters()
    ): SimulationRun {
        val simulationRun = SimulationRun(model.name, experimentRunParameters, inputs)
        simulate(simulationRun)
        return simulationRun
    }

    /**
     * Simulates the model based on the current settings of the experiment run parameters and inputs
     * associated with the simulation run [simulationRun]
     */
    fun simulate(simulationRun: SimulationRun) {
        try {
            // set simulation run parameters, number of advances, experimental controls, and random variables
            setupSimulation(simulationRun)
            // attach observers
            val timer = SimulationTimer(model)
            val rdc = ReplicationDataCollector(model, true)
            Model.logger.info { "SimulationRunner: Running simulation: ${model.simulationName} " }
            model.simulate()
            Model.logger.info { "SimulationRunner: Simulation ${model.simulationName} ended, capturing results." }
            // detach the observers
            rdc.stopObserving()
            timer.stopObserving()
            //capture results
            val repNums: DoubleArray = KSLArrays.toDoubles(model.repIdRange.toList().toPrimitives())
            val results = mutableMapOf<String, DoubleArray>()
            results["repNumbers"] = repNums
            results["repTimings"] = timer.replicationTimes()
            val rdcData = rdc.allReplicationDataAsMap
            results.putAll(rdcData)
            simulationRun.results = results
            simulationRun.beginExecutionTime = timer.experimentStartTime
            simulationRun.endExecutionTime = timer.experimentEndTime
        } catch (e: RuntimeException) {
            catchSimulationRunError(simulationRun, e)
        }
    }

    /**
     *  Sets up the simulation based on the inputs and experimental run parameters.
     */
    private fun setupSimulation(simulationRun: SimulationRun) {
        Model.logger.info { "SimulationRunner: Setting up simulation: ${model.simulationName} " }
        // apply the run parameters to the model
        model.changeRunParameters(simulationRun.experimentRunParameters)
        // apply the inputs to the model
        if (simulationRun.inputs.isNotEmpty()) {
            // need to apply them to the model, could be controls and random variable parameters
            // get the controls to build what will need to be changed
            val controls: Controls = model.controls()
            // get the random variable parameters
            val tmpSetter = RVParameterSetter(model)
            val rvParameters = tmpSetter.flatParametersAsDoubles(rvParamConCatChar)
            // now check if the supplied input key is a control or a rv parameter
            // and save them for application to the model
            val controlsMap = mutableMapOf<String, Double>()
            val rvParamMap = mutableMapOf<String, Double>()
            for ((keyName, value) in simulationRun.inputs) {
                if (controls.hasControl(keyName)) {
                    controlsMap[keyName] = value
                } else if (rvParameters.containsKey(keyName)) {
                    rvParamMap[keyName] = value
                } else {
                    Model.logger.info { "SimulationRunner: input $keyName was not a control or a random variable parameter" }
                }
            }
            if (controlsMap.isNotEmpty()) {
                // controls were found, tell the model to use controls when it is simulated
                model.experimentalControls = controlsMap
                Model.logger.info { "SimulationRunner: ${controlsMap.size} controls out of ${controls.size} were applied." }
            }
            if (rvParamMap.isNotEmpty()) {
                // convert to the form used by RVParameterSetter
                val unflattenMap = KSLMaps.unflattenMap(rvParamMap, rvParamConCatChar)
                // tell the model to use the supplied parameter values
                model.rvParameterSetter.changeParameters(unflattenMap)
                Model.logger.info { "SimulationRunner: ${rvParamMap.size} parameters out of ${rvParameters.size} were applied." }
            }
        }
    }

    private fun catchSimulationRunError(simulationRun: SimulationRun, e: RuntimeException) {
        // capture the full stack trace
        // per https://www.baeldung.com/java-stacktrace-to-string
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        e.printStackTrace(pw)
        simulationRun.runErrorMsg = sw.toString()
        // return an empty HashMap of results
        simulationRun.results = mutableMapOf()
        Model.logger.error { "There was a fatal exception during the running of simulation ${model.simulationName} within SimulationRunner." }
        Model.logger.error { "No responses were recorded." }
        Model.logger.error { sw.toString() }
        throw e
    }

    /**
     *  Splits the number of replications into a list of experiments
     *  with each experiment having at most [size] replications. A resulting
     *  experiment may have fewer than the given [size] but at least 1
     *  replication. The experiments are ordered in the list such that the replication identifiers
     *  for each experiment are ordered from 1 to the number of replications [numReplications]
     *  @param size the number of replications in each experiment must be positive. If greater than
     *  the number of replications, there will be 1 chunk containing all replications
     */
    @Suppress("unused")
    fun chunkReplications(numReplications: Int, size: Int): List<ExperimentRunParameters> {
        require(numReplications >= 1) { "The number of replications must be >= 1" }
        // make the range for chunking
        val r = 1..numReplications
        val chunks: List<List<Int>> = r.chunked(size)
        val eList = mutableListOf<ExperimentRunParameters>()
        for (chunk in chunks) {
            val s = chunk.first() // starting id of replication in chunk
            val n = chunk.size // number of replications in the chunk
            val runParameters = model.extractRunParameters()
            runParameters.startingRepId = s
            runParameters.numberOfReplications = n
            runParameters.numberOfStreamAdvancesPriorToRunning = s - 1
            runParameters.resetStartStreamOption = true
            runParameters.numChunks = chunks.size
            runParameters.runName = IntRange(s, s + n - 1).toString()
            eList.add(runParameters)
        }
        return eList
    }

    companion object {
        /**
         * The string used to flatten or un-flatten random variable parameters
         * Assumed as "." by default
         */
        var rvParamConCatChar = RVParameterSetter.rvParamConCatChar

    }
}
