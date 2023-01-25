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

import kotlinx.datetime.Clock
import ksl.observers.ReplicationDataCollector
import ksl.observers.SimulationTimer
import ksl.simulation.Experiment
import ksl.simulation.ExperimentIfc
import ksl.simulation.Model
import java.io.PrintWriter
import java.io.StringWriter

class SimulationRunner(
    private val model: Model
) {

    /**
     *  The model will be run with the [experimentRunParameters] and the provided [inputs]. The inputs
     *  can represent both control (key, value) pairs and random variable parameter
     *  (key, value) pairs to be applied to the experiment.  The inputs may be empty.
     *
     *  @return returns an instance of SimulationRun that holds the experiment, inputs, and results
     *  associated with the simulation run.
     */
    fun simulate(experimentRunParameters: ExperimentRunParameters, inputs: Map<String, Double> = mapOf()): SimulationRun {
        val simulationRun = SimulationRun(experimentRunParameters)
        simulationRun.inputs = inputs
        try{
            // set simulation run parameters, number of advances, experimental controls, and random variables
            setupSimulation(experimentRunParameters, inputs) //TODO
            // attach observers
            val timer = SimulationTimer(model)
            val rdc = ReplicationDataCollector(model, true)
            Model.logger.info { "SimulationRunner: Running simulation: ${model.simulationName} " }
            model.simulate()
            Model.logger.info { "SimulationRunner: Simulation ${model.simulationName} ended, capturing results." }
            // detach the observers
            rdc.startObserving()
            timer.stopObserving()
            simulationRun.beginExecutionTime = timer.experimentStartTime
            simulationRun.endExecutionTime = timer.experimentEndTime
            //TODO capture results

        }catch (e: RuntimeException) {
            catchSimulationRunError(simulationRun, e)
        }
        return simulationRun
    }

    private fun catchSimulationRunError(simulationRun: SimulationRun, e: RuntimeException){
        // capture the full stack trace
        // per https://www.baeldung.com/java-stacktrace-to-string
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        e.printStackTrace(pw)
        simulationRun.functionError = sw.toString()
        // return an empty HashMap of results
        simulationRun.results = mutableMapOf()
        Model.logger.error { "There was a fatal exception during the running of simulation ${model.simulationName} within SimulationRunner." }
        Model.logger.error("No responses were recorded.")
        Model.logger.error(sw.toString())
    }

    /**
     *  Splits the number of replications into a list of experiments
     *  with each experiment having at most [size] replications. A resulting
     *  experiment may have fewer than the given [size] but at least 1
     *  replication. The experiments are ordered in the list such that the replication identifiers
     *  for each experiment are ordered from 1 to the number of replications [numReplications]
     *  @param size the number of replications in each experiment, must be positive. If greater than
     *  the number of replications, there will be 1 chunk containing all replications
     */
    fun chunkReplications(numReplications: Int, size: Int) : List<Experiment>{
        require(numReplications >= 1){"The number of replications must be >= 1"}
        // make the range for chunking
        val r = 1..numReplications
        val chunks: List<List<Int>> = r.chunked(size)
        val eList = mutableListOf<Experiment>()
        for(chunk in chunks){
            val s = chunk.first() // starting id of replication in chunk
            val n = chunk.size // number of replications in the chunk
            val experiment = model.experimentInstance()
            experiment.startingRepId = s
            experiment.numberOfReplications = n
            experiment.numberOfStreamAdvancesPriorToRunning = s - 1
            experiment.isChunked = true
            // change name of experiment so db can handle chunking
            // this treats each chunk as a separate experiment in the database
            //TODO this is a temporary fix until the database can be designed to hold chunks
            // as related to an overall experiment
            // this allows results of a chunk to be added to the database; otherwise,
            // an error will occur when trying to insert a experiment for a simulation
            // where the experiment name and simulation name already exist in the database
            // changing the experiment name prevents that error and permits data to be stored in the
            // current KSLDatabase design
            experiment.experimentName = experiment.experimentName + ":" + experiment.chunkLabel
            eList.add(experiment)
        }
        return eList
    }

    private fun setupSimulation(experimentRunParameters: ExperimentRunParameters, inputs: Map<String, Double>) {
        Model.logger.info { "SimulationRunner: Setting up simulation: ${model.simulationName} " }
        // reset streams to their start for all RandomIfc elements in the model
        // and skip ahead to the right replication (advancing sub-streams)
        model.resetStartStream() //TODO?
        TODO("Not yet implemented")
    }

    companion object {
        /**
         * The string used to flatten or un-flatten random variable parameters
         * Assumed as "_PARAM_" by default
         */
        var rvParamConCatString = "_PARAM_"

    }
}