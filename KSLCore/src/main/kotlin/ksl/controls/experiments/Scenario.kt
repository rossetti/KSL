/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.simulation.ExperimentIfc
import ksl.simulation.Model
import ksl.utilities.Identity

/**
 *  A scenario represents the specification of a model to run, with some
 *  inputs.  Each scenario will produce a simulation run.
 *  The name of the scenario is set equal to the name of the experiment from
 *  the model. In the context of running multiple scenarios, it is important
 *  that the experiment names be unique to permit automated storage within
 *  a KSL database.
 *
 *  @param model The model to be simulated
 *  @param inputs The map of inputs (based on control names) to apply to the model
 *  @param numberReplications the number of replications for the scenario. By default,
 *  this is the current setting of the model.
 *  @param lengthOfReplication the length of each replication for the scenario. By default,
 *  this is the current setting of the model.
 *  @param lengthOfReplicationWarmUp the length of the warmup period for each replication for the scenario. By default,
 *  this is the current setting of the model.
 */
class Scenario(
    val model: Model,
    inputs: Map<String, Double>,
    numberReplications: Int = model.numberOfReplications,
    lengthOfReplication: Double = model.lengthOfReplication,
    lengthOfReplicationWarmUp: Double = model.lengthOfReplicationWarmUp,
) : Identity(model.experimentName), ExperimentIfc by model {

    private val simulationRunner = SimulationRunner(model)
    private val myInputs = mutableMapOf<String, Double>()

    /**
     * returns the last generated simulation run
     */
    var simulationRun: SimulationRun? = null

    init {
        require(model.validateInputKeys(inputs.keys)) { "The inputs contained invalid input names" }
        for ((n, v) in inputs) {
            myInputs[n] = v
        }
        model.numberOfReplications = numberReplications
        model.lengthOfReplication = lengthOfReplication
        model.lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
    }

    /**
     *  Simulates the scenario by simulating the model at its current experimental
     *  run parameters using the supplied inputs. Generates a new simulation run
     *  with each execution.
     */
    fun simulate() {
        simulationRun = simulationRunner.simulate(myInputs, model.extractRunParameters())
    }

}

