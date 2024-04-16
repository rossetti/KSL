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

//    private val mySimulationRunner = SimulationRunner(model)
    var simulationRun: SimulationRun? = null

    init {
        require(model.validateInputKeys(inputs.keys)) {"The inputs contained invalid input names"}
        model.numberOfReplications = numberReplications
        model.lengthOfReplication = lengthOfReplication
        model.lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
    }
}

class ScenarioRunner(scenarioList: List<Scenario> = emptyList()) {

    private val myScenarios = mutableListOf<Scenario>()
    private val myScenariosByName = mutableMapOf<String, Scenario>()
    init {
        myScenarios.addAll(scenarioList)
        for(scenario in scenarioList){
            myScenariosByName[scenario.name] = scenario
        }
    }
}