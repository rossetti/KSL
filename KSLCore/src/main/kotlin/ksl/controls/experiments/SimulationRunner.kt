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
import ksl.simulation.Model
import java.io.PrintWriter
import java.io.StringWriter
import java.util.stream.IntStream

class SimulationRunner(
    private val model: Model
) {
    fun run(runParameters: RunParameters? = null) {
        val simulationRun: SimulationRun = if (runParameters == null) {
            // make simulation run from model
            with(model) {
                val rp = RunParameters(
                    1..numberOfReplications,
                    lengthOfReplication,
                    lengthOfReplicationWarmUp,
                    antitheticOption
                )
                SimulationRun(runParameters = rp)
            }
        } else {
            // make simulation run from run parameters
            SimulationRun(runParameters = runParameters)
        }
        try {
            simulationRun.beginExecutionTime = Clock.System.now()
            // reset streams to their start for all RandomIfc elements in the model
            // and skip ahead to the right replication (advancing sub-streams)
            val rdc = ReplicationDataCollector(model, true)
            model.resetStartStream()
            val first = simulationRun.parameters.replicationRange.first
            val numAdvances = first - 1
            model.advanceSubStreams(numAdvances)
            // set simulation run parameters and controls
            Model.logger.info { "Setting up simulation: ${model.simulationName} " }
            setupSimulation()
            // run the simulation
            Model.logger.info { "Running simulation: ${model.simulationName} " }
            val timer = SimulationTimer(model)
            model.simulate()
            Model.logger.info { "Simulation ${model.simulationName} ended, capturing results." }
            val reps = DoubleArray(simulationRun.parameters.numberOfReplications)
            for (i in simulationRun.parameters.replicationRange) {
                val k = i - first
                reps[k] = i.toDouble()
            }
            //TODO

        } catch (e: RuntimeException) {
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
    }

    private fun setupSimulation() {
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