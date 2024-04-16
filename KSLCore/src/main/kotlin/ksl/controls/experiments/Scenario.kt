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
 */
class Scenario(
    val model: Model,
    inputs: Map<String, Double>,
    numberReplications: Int = model.numberOfReplications,
    lengthOfReplication: Double = model.lengthOfReplication,
    lengthOfReplicationWarmUp: Double = model.lengthOfReplicationWarmUp,
    name: String? = null
) : Identity(name), ExperimentIfc by model {

    val simulationRunner = SimulationRunner(model)
    private val myInputs = mutableMapOf<String, Double>()
    var simulationRun: SimulationRun? = null

    init {
        require(model.validateInputKeys(inputs.keys)) {"The inputs contained invalid input names"}
        for((n, v) in inputs){
            myInputs[n] = v
        }
        model.numberOfReplications = numberReplications
        model.lengthOfReplication = lengthOfReplication
        model.lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
    }

    fun runScenario(){
        simulationRun = simulationRunner.simulate(myInputs, model.extractRunParameters())
    }
}

class ScenarioRunner(scenarioList: List<Scenario> = emptyList()) {

    private val myScenarios = mutableListOf<Scenario>()
    val scenarioList: List<Scenario>
        get() = myScenarios

    private val myScenariosByName = mutableMapOf<String, Scenario>()
    init {
        myScenarios.addAll(scenarioList)
        for(scenario in scenarioList){
            myScenariosByName[scenario.name] = scenario
        }
    }

    fun addScenario(
        model: Model,
        inputs: Map<String, Double>,
        numberReplications: Int = model.numberOfReplications,
        lengthOfReplication: Double = model.lengthOfReplication,
        lengthOfReplicationWarmUp: Double = model.lengthOfReplicationWarmUp,
        name: String? = null
    ) : Scenario {
        val s = Scenario(model, inputs, numberReplications, lengthOfReplication, lengthOfReplicationWarmUp, name)
        myScenarios.add(s)
        myScenariosByName[s.name] = s
        return s
    }

}