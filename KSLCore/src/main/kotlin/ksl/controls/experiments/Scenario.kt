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
 *  A scenario represents the specification of a model to run with a fixed set of inputs and
 *  run parameters.  Each call to [simulate] produces a new [SimulationRun].
 *
 *  In the context of running multiple scenarios it is important that scenario names are unique
 *  so that each experiment can be identified within a [ksl.utilities.io.dbutil.KSLDatabase].
 *  The scenario name is used as the experiment name for the simulation run.
 *
 *  ### Run-parameter semantics
 *
 *  [runParameters] is captured as an owned snapshot at construction time.  All 15 fields of
 *  [ExperimentRunParameters] are stored, including those (antithetic option, stream-reset
 *  behaviour, stream advances, etc.) that older constructors did not expose individually.
 *  The snapshot's experiment name is automatically set to [name].
 *
 *  At [simulate] time the snapshot is applied to the model via
 *  [ksl.simulation.Model.changeRunParameters], the simulation runs, and the model's previous
 *  run parameters are restored in a `finally` block.  This means:
 *  - Multiple scenarios that share the same model can be constructed and executed in any order.
 *  - An exception during simulation leaves the model in its pre-scenario state.
 *  - The model is never mutated at construction time.
 *
 *  @param model              The model to be simulated.
 *  @param name               The scenario name.  Must be unique within the set of scenarios
 *                            executed by a [ScenarioRunner].  Also used as the experiment name.
 *  @param inputs             Numeric control and RV-parameter overrides (`key → Double`).
 *  @param stringInputs       String control overrides (`key → String`), applied via the
 *                            deferred [ksl.simulation.Model.experimentalStringControls] slot.
 *                            Unknown keys are logged and silently skipped.
 *  @param jsonInputs         JSON control overrides (`key → JSON String`), applied via the
 *                            deferred [ksl.simulation.Model.experimentalJsonControls] slot.
 *                            Unknown keys are logged and silently skipped.
 *  @param runParameters      Full experimental run configuration for this scenario.  Defaults
 *                            to a snapshot of the model's current run parameters captured at
 *                            construction time.  The snapshot's experiment name is overridden
 *                            with [name] automatically.
 *  @param modelConfiguration Optional model configuration map applied before each run when
 *                            the model has a [ksl.simulation.ModelConfigurationManagerIfc].
 */
class Scenario @JvmOverloads constructor(
    val model: Model,
    name: String,
    inputs: Map<String, Double> = emptyMap(),
    stringInputs: Map<String, String> = emptyMap(),
    jsonInputs: Map<String, String> = emptyMap(),
    runParameters: ExperimentRunParameters = model.extractRunParameters(),
    modelConfiguration: Map<String, String>? = null
) : Identity(name), ExperimentIfc by model {

    /**
     *  Convenience constructor for the common case where only the three scalar run-parameter
     *  values need to differ from the model's current settings.  All other run parameters
     *  are captured from the model at construction time.  String and JSON control overrides
     *  must be supplied via the primary constructor.
     *
     *  @param model                      The model to be simulated.
     *  @param name                       The scenario name.
     *  @param inputs                     Numeric control and RV-parameter overrides.
     *  @param numberReplications         Replications for this scenario.
     *  @param lengthOfReplication        Replication length for this scenario.
     *  @param lengthOfReplicationWarmUp  Warm-up length for this scenario.
     *  @param modelConfiguration         Optional model configuration map.
     */
    @JvmOverloads
    constructor(
        model: Model,
        name: String,
        inputs: Map<String, Double> = emptyMap(),
        numberReplications: Int = model.numberOfReplications,
        lengthOfReplication: Double = model.lengthOfReplication,
        lengthOfReplicationWarmUp: Double = model.lengthOfReplicationWarmUp,
        modelConfiguration: Map<String, String>? = null
    ) : this(
        model = model,
        name = name,
        inputs = inputs,
        stringInputs = emptyMap(),
        jsonInputs = emptyMap(),
        runParameters = model.extractRunParameters().copy(
            numberOfReplications = numberReplications,
            lengthOfReplication = lengthOfReplication,
            lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
        ),
        modelConfiguration = modelConfiguration
    )

    /**
     *  Constructor that creates the model via [modelCreator] before delegating to the primary
     *  constructor.  String and JSON inputs may be supplied; run parameters default to the
     *  newly created model's current settings.
     *
     *  @param modelCreator  A function that creates and returns the [Model] instance.
     *  @param name          The scenario name.
     *  @param inputs        Numeric control and RV-parameter overrides.
     *  @param stringInputs  String control overrides.
     *  @param jsonInputs    JSON control overrides.
     */
    @Suppress("unused")
    @JvmOverloads
    constructor(
        modelCreator: () -> Model,
        name: String,
        inputs: Map<String, Double> = emptyMap(),
        stringInputs: Map<String, String> = emptyMap(),
        jsonInputs: Map<String, String> = emptyMap(),
    ) : this(modelCreator(), name, inputs, stringInputs, jsonInputs)

    // ── private state ─────────────────────────────────────────────────────────

    private val simulationRunner = SimulationRunner(model)
    private val myInputs = mutableMapOf<String, Double>()
    private val myStringInputs = mutableMapOf<String, String>()
    private val myJsonInputs = mutableMapOf<String, String>()

    /**
     *  Owned snapshot of the run parameters for this scenario.
     *  The experiment name is set to [name] so that [simulate] does not need to
     *  manipulate [ksl.simulation.Model.experimentName] manually.
     *  Modifying this object before [simulate] is called will affect the next run.
     */
    private val myRunParameters: ExperimentRunParameters = runParameters.copy(experimentName = name)

    private var myModelConfiguration: MutableMap<String, String>? = null

    // ── public state ──────────────────────────────────────────────────────────

    /**
     *  The run parameters that will be applied when this scenario is simulated.
     *  This is the scenario's owned snapshot — not the model's live state.
     *
     *  All 15 fields of [ExperimentRunParameters] are available.  Modifying them
     *  before [simulate] is called will affect the next run.
     */
    val scenarioRunParameters: ExperimentRunParameters
        get() = myRunParameters

    /**
     *  The [SimulationRun] produced by the most recent call to [simulate], or `null`
     *  if [simulate] has not yet been called.
     */
    var simulationRun: SimulationRun? = null

    /**
     *  Optional pre-run hook.  When set, [setup] is called on the model immediately
     *  before [simulationRunner] executes.
     */
    var setup: ScenarioSetupIfc? = null  //TODO evaluate whether this is still needed

    // ── init ──────────────────────────────────────────────────────────────────

    init {
        if (inputs.isNotEmpty()) {
            require(model.validateInputKeys(inputs.keys)) {
                "The inputs, ${inputs.keys.joinToString(prefix = "[", postfix = "]")} contained invalid input names"
            }
            myInputs.putAll(inputs)
        }
        // String and JSON inputs are not validated upfront; unknown keys are logged and
        // silently skipped by Controls.setStringControlsFromMap() / setJsonControlsFromMap().
        myStringInputs.putAll(stringInputs)
        myJsonInputs.putAll(jsonInputs)
        if (modelConfiguration != null && modelConfiguration.isNotEmpty()) {
            myModelConfiguration = modelConfiguration.toMutableMap()
        }
        // The model is NOT mutated here.  Run parameters are owned by myRunParameters and
        // applied to the model at simulate() time, then restored — so multiple scenarios
        // that share the same model work correctly regardless of construction or run order.
    }

    // ── simulation ────────────────────────────────────────────────────────────

    /**
     *  Simulates the scenario.
     *
     *  Execution sequence:
     *  1. The model's current run parameters are saved.
     *  2. [scenarioRunParameters] (including the scenario name as experiment name) are applied
     *     to the model via [SimulationRunner], which calls [ksl.simulation.Model.changeRunParameters]
     *     inside [ksl.controls.experiments.SimulationRunner.setupSimulation].
     *  3. The optional [setup] hook and [simulationRunner] run.
     *  4. In a `finally` block, the model's saved run parameters and configuration are restored,
     *     even if an exception was thrown during the simulation.
     *
     *  A new [SimulationRun] is produced and stored in [simulationRun] after each successful call.
     */
    fun simulate() {
        val savedRunParameters = model.extractRunParameters()
        val savedConfiguration = model.configuration
        try {
            if (model.modelConfigurationManager != null && myModelConfiguration != null) {
                model.configuration = myModelConfiguration!!
            }
            setup?.setup(model)
            simulationRun = simulationRunner.simulate(
                modelIdentifier = model.modelIdentifier,
                inputs = myInputs,
                stringInputs = myStringInputs,
                jsonInputs = myJsonInputs,
                experimentRunParameters = myRunParameters
            )
        } finally {
            // Restore the model's state whether the run succeeded or threw.
            model.changeRunParameters(savedRunParameters)
            model.configuration = savedConfiguration
        }
    }
}

/**
 *  Can be used to supply logic to configure a model prior to simulating a scenario.
 */
fun interface ScenarioSetupIfc {
    fun setup(model: Model)
}
