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
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver

/**
 *  A scenario represents the specification of a model to run, with some
 *  inputs.  Each scenario will produce a simulation run.
 */
class Scenario(
    name: String? = null,
    val model: Model,
    inputs: Map<String, Double>,
    numberReplications: Int = model.numberOfReplications,
    lengthOfReplication: Double = model.lengthOfReplication,
    lengthOfReplicationWarmUp: Double = model.lengthOfReplicationWarmUp,
) : Identity(name), ExperimentIfc by model {

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
        model.experimentName = "Scenario_${id}_${model.experimentName}"
    }

    /**
     *  Simulates the scenario by simulating the model at its current experimental
     *  run parameters using the supplied inputs. Generates a new simulation run
     *  with each execution
     */
    fun simulate() {
        simulationRun = simulationRunner.simulate(myInputs, model.extractRunParameters())
    }

}

/**
 *  Facilitates the running of many scenarios in a sequence. A KSLDatabase
 *  is used to capture the statistics for each scenario. Each scenario is
 *  treated like a different experiment. The scenarios can be based on
 *  the same or different models.  The scenarios also capture the inputs and
 *  results via a SimulationRun.
 *
 *  @param the name of the scenario runner. By default, this name
 *  is used as the name of the database
 *  @param scenarioList a list of scenarios to execute
 *  @param kslDb the KSLDatabase that will hold the results from the scenarios
 */
class ScenarioRunner(
    name: String,
    scenarioList: List<Scenario> = emptyList(),
    val kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"), KSL.dbDir),
) : Identity(name) {

    private val myScenarios = mutableListOf<Scenario>()

    /**
     *  A read only list of the scenarios to be run.
     */
    val scenarioList: List<Scenario>
        get() = myScenarios

    private val myScenariosByName = mutableMapOf<String, Scenario>()
    private val myDbObserversByName = mutableMapOf<String, KSLDatabaseObserver>()

    /**
     *  The database observers that were attached to the models. This
     *  property could be used to turn off all or some of the observers
     */
    val dbObservers: List<KSLDatabaseObserver>
        get() = myDbObserversByName.values.toList()

    init {
        myScenarios.addAll(scenarioList)
        for (scenario in scenarioList) {
            myScenariosByName[scenario.name] = scenario
            myDbObserversByName[scenario.name] = KSLDatabaseObserver(scenario.model, kslDb)
        }
    }

    /**
     *  Gets the scenario by its name or null if not there.
     */
    fun scenarioByName(name: String): Scenario? {
        return myScenariosByName[name]
    }

    /**
     *  Gets the database observer by the scenario name or null if not there.
     */
    fun databaseObserverByName(name: String): KSLDatabaseObserver? {
        return myDbObserversByName[name]
    }

    /** Sets the number replications for each scenario to a common
     *  number of replications.
     *  @param numReps the number of replications for each scenario. Must be
     *  greater than or equal to 1.
     */
    fun numReplicationsPerScenario(numReps: Int) {
        // require(numReps >=1){"The number of replications for each scenario should be >= 1"}
        for (scenario in myScenarios) {
            if (numReps >= 1) {
                scenario.numberOfReplications = numReps
            }
        }
    }

    /**
     *  Adds a scenario to the possible scenarios to simulate.
     */
    fun addScenario(
        name: String? = null,
        model: Model,
        inputs: Map<String, Double>,
        numberReplications: Int = model.numberOfReplications,
        lengthOfReplication: Double = model.lengthOfReplication,
        lengthOfReplicationWarmUp: Double = model.lengthOfReplicationWarmUp,
    ): Scenario {
        val s = Scenario(name, model, inputs, numberReplications, lengthOfReplication, lengthOfReplicationWarmUp)
        myScenarios.add(s)
        myScenariosByName[s.name] = s
        myDbObserversByName[s.name] = KSLDatabaseObserver(model, kslDb)
        return s
    }

    /** Interprets the integer progression as the indices of the
     *  contained scenarios that should be simulated. If the
     *  progression is not a valid index then no scenario is simulated.
     *
     */
    fun simulate(scenarios: IntProgression = myScenarios.indices) {
        for (scenarioIndex in scenarios) {
            if (scenarioIndex in myScenarios.indices) {
                myScenarios[scenarioIndex].simulate()
            }
        }
    }

}