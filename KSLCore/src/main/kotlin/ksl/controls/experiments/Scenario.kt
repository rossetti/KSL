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
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.OutputDirectory

/**
 *  A scenario represents the specification of a model to run, with some
 *  inputs.  Each scenario will produce a simulation run.
 *  In the context of running multiple scenarios, it is important
 *  that the scenario names be unique to permit automated storage within
 *  a KSL database.  The name of the scenario is used to assign the
 *  name of the model's experiment prior to simulating the model.
 *  In this manner, each experiment can have a unique name.
 *
 *  @param model The model to be simulated
 *  @param name The name of the scenario. It should be unique within the context of a
 *  set of scenario being executed by a ScenarioRunner.
 *  @param inputs The map of inputs (based on control names) to apply to the model.
 *  @param numberReplications the number of replications for the scenario. By default,
 *  this is the current setting of the model.
 *  @param lengthOfReplication the length of each replication for the scenario. By default,
 *  this is the current setting of the model.
 *  @param lengthOfReplicationWarmUp the length of the warmup period for each replication for the scenario. By default,
 *  this is the current setting of the model.
 */
class Scenario(
    val model: Model,
    name: String,
    inputs: Map<String, Double> = emptyMap(),
    numberReplications: Int = model.numberOfReplications,
    lengthOfReplication: Double = model.lengthOfReplication,
    lengthOfReplicationWarmUp: Double = model.lengthOfReplicationWarmUp,
) : Identity(name), ExperimentIfc by model {

    /**
     *  Uses the supplied model creation function to creat the model. The specification
     *  of model run parameters relies on the created model to correctly specify its
     *  running parameter.
     *
     *  @param modelCreator a function that will create the model (supply and instance of the model)
     *  @param name The name of the scenario. It should be unique within the context of a
     *  set of scenario being executed by a ScenarioRunner.
     *  @param inputs The map of inputs (based on control names) to apply to the model.
     */
    constructor(
        modelCreator: () -> Model,
        name: String,
        inputs: Map<String, Double> = emptyMap(),
    ): this(modelCreator(), name, inputs)

    private val simulationRunner = SimulationRunner(model)
    private val myInputs = mutableMapOf<String, Double>()

    /**
     * returns the last generated simulation run
     */
    var simulationRun: SimulationRun? = null

    /**
     *  Can be used to supply a function that will set up the model
     *  prior to being run. This would allow for the assignment properties or invoking
     *  of additional logic prior to simulating the model.
     */
    var setup: ScenarioSetupIfc? = null

    init {
        if (inputs.isNotEmpty()){
            require(model.validateInputKeys(inputs.keys)) { "The inputs, ${inputs.keys.joinToString(prefix = "[", postfix = "]")} contained invalid input names" }
            for ((n, v) in inputs) {
                myInputs[n] = v
            }
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
        // store the current name
        val experimentName = model.experimentName
        // change the name for the run to the name of the scenario
        model.experimentName = name
        setup?.setup(model)
        simulationRun = simulationRunner.simulate(
            modelIdentifier = model.simulationName,
            inputs = myInputs,
            experimentRunParameters = model.extractRunParameters())
        // put the name back to its original value
        model.experimentName = experimentName
    }

}

/**
 *  Can be used to supply logic to configure a model prior to simulating a scenario.
 */
fun interface ScenarioSetupIfc {
    fun setup(model: Model)
}

