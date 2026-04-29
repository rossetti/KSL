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

import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Identity

/**
 *  A scenario represents the specification of a model to run with a fixed set of inputs and
 *  run parameters.  Each call to [simulate] produces a new [SimulationRun].
 *
 *  In the context of running multiple scenarios it is important that scenario names are unique
 *  so that each experiment can be identified within a [ksl.utilities.io.dbutil.KSLDatabase].
 *  The scenario name is used as the experiment name for the simulation run.
 *
 *  ### Model construction semantics
 *
 *  [modelBuilder] is called at the start of each [simulate] invocation to produce a fresh
 *  [Model] instance.  This design enables concurrent execution of independent scenarios
 *  (see `ScenarioRunner.simulateConcurrently`) as well as the traditional sequential path:
 *  each run is isolated and leaves no shared state behind.
 *
 *  For backward compatibility, constructors that accept a pre-built [Model] are provided.
 *  They wrap the supplied instance in a `ModelBuilderIfc` that always returns the same model,
 *  so the sequential behaviour is identical to the previous implementation.
 *
 *  ### Run-parameter semantics
 *
 *  [scenarioRunParameters] is an owned snapshot captured at construction time.  All 15 fields of
 *  [ExperimentRunParameters] are stored.  The snapshot's experiment name is automatically set
 *  to [name].
 *
 *  At [simulate] time the snapshot is applied to the model via
 *  [ksl.simulation.Model.changeRunParameters] inside `SimulationRunner.setupSimulation`.
 *
 *  @param modelBuilder  Factory that creates the [Model] instance for each run.
 *  @param name          The scenario name.  Must be unique within the set of scenarios
 *                       executed by a [ScenarioRunner].  Also used as the experiment name.
 *  @param inputs        Numeric control and RV-parameter overrides (`key → Double`).
 *  @param stringInputs  String control overrides (`key → String`).
 *  @param jsonInputs    JSON control overrides (`key → JSON String`).
 *  @param runParameters Full experimental run configuration for this scenario.
 *  @param modelConfiguration Optional model configuration map forwarded to
 *                            [ModelBuilderIfc.build] before each run.
 */
class Scenario constructor(
    val modelBuilder: ModelBuilderIfc,
    name: String,
    inputs: Map<String, Double> = emptyMap(),
    stringInputs: Map<String, String> = emptyMap(),
    jsonInputs: Map<String, String> = emptyMap(),
    runParameters: ExperimentRunParameters,
    modelConfiguration: Map<String, String>? = null
) : Identity(name) {

    // ── Backward-compatible constructor: pre-built model (full run parameters) ─

    /**
     *  Backward-compatible constructor.  The supplied [model] is wrapped so that each
     *  [simulate] call operates on the same instance.
     *
     *  Input keys are validated against [model] at construction time.
     *
     *  @param model          The model to be simulated.
     *  @param name           The scenario name.
     *  @param inputs         Numeric control and RV-parameter overrides.
     *  @param stringInputs   String control overrides.
     *  @param jsonInputs     JSON control overrides.
     *  @param runParameters  Full run configuration.  Defaults to a snapshot of [model]'s
     *                        current run parameters captured at construction time.
     *  @param modelConfiguration Optional model configuration map.
     */
    @JvmOverloads
    constructor(
        model: Model,
        name: String,
        inputs: Map<String, Double> = emptyMap(),
        stringInputs: Map<String, String> = emptyMap(),
        jsonInputs: Map<String, String> = emptyMap(),
        runParameters: ExperimentRunParameters = model.extractRunParameters(),
        modelConfiguration: Map<String, String>? = null
    ) : this(
        modelBuilder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = model
        },
        name = name,
        inputs = inputs,
        stringInputs = stringInputs,
        jsonInputs = jsonInputs,
        runParameters = runParameters,
        modelConfiguration = modelConfiguration
    ) {
        if (inputs.isNotEmpty()) {
            require(model.validateInputKeys(inputs.keys)) {
                "The inputs, ${inputs.keys.joinToString(prefix = "[", postfix = "]")} contained invalid input names"
            }
        }
    }

    // ── Backward-compatible convenience constructor: model + scalar run params ─

    /**
     *  Convenience constructor for the common case where only the three scalar
     *  run-parameter values need to differ from [model]'s current settings.
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
        numberReplications: Int,
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

    // ── ModelBuilderIfc convenience constructor: scalar run params ─────────────

    /**
     *  Convenience constructor for use with a [ModelBuilderIfc].  The builder is
     *  invoked once at construction time to extract default run parameters; the returned
     *  model is then discarded.  The three scalar values override the defaults.
     *
     *  @param modelBuilder               Factory that creates the model for each run.
     *  @param name                       The scenario name.
     *  @param inputs                     Numeric control and RV-parameter overrides.
     *  @param numberReplications         Replications for this scenario.
     *  @param lengthOfReplication        Replication length.
     *  @param lengthOfReplicationWarmUp  Warm-up length.
     *  @param modelConfiguration         Optional model configuration map.
     */
    @JvmOverloads
    constructor(
        modelBuilder: ModelBuilderIfc,
        name: String,
        inputs: Map<String, Double> = emptyMap(),
        numberReplications: Int,
        lengthOfReplication: Double,
        lengthOfReplicationWarmUp: Double = 0.0,
        modelConfiguration: Map<String, String>? = null
    ) : this(
        modelBuilder = modelBuilder,
        name = name,
        inputs = inputs,
        stringInputs = emptyMap(),
        jsonInputs = emptyMap(),
        runParameters = modelBuilder.build(modelConfiguration).extractRunParameters().copy(
            numberOfReplications = numberReplications,
            lengthOfReplication = lengthOfReplication,
            lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
        ),
        modelConfiguration = modelConfiguration
    )

    // ── Private state ─────────────────────────────────────────────────────────

    private val myInputs = mutableMapOf<String, Double>()
    private val myStringInputs = mutableMapOf<String, String>()
    private val myJsonInputs = mutableMapOf<String, String>()

    /**
     *  Owned snapshot of the run parameters for this scenario.
     *  The experiment name is set to [name].
     *  Modifying the mutable fields before [simulate] is called will affect the next run.
     */
    private val myRunParameters: ExperimentRunParameters = runParameters.copy(experimentName = name)

    private var myModelConfiguration: MutableMap<String, String>? = null

    // ── Public state ──────────────────────────────────────────────────────────

    /**
     *  The run parameters that will be applied when this scenario is simulated.
     *  This is the scenario's owned snapshot — not the model's live state.
     */
    val scenarioRunParameters: ExperimentRunParameters
        get() = myRunParameters

    /** Number of replications configured for this scenario. */
    val numberOfReplications: Int get() = myRunParameters.numberOfReplications

    /** Replication length configured for this scenario. */
    val lengthOfReplication: Double get() = myRunParameters.lengthOfReplication

    /** Warm-up length configured for this scenario. */
    val lengthOfReplicationWarmUp: Double get() = myRunParameters.lengthOfReplicationWarmUp

    /**
     *  The [SimulationRun] produced by the most recent call to [simulate], or `null`
     *  if [simulate] has not yet been called.
     */
    var simulationRun: SimulationRun? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        myInputs.putAll(inputs)
        myStringInputs.putAll(stringInputs)
        myJsonInputs.putAll(jsonInputs)
        if (modelConfiguration != null && modelConfiguration.isNotEmpty()) {
            myModelConfiguration = modelConfiguration.toMutableMap()
        }
    }

    // ── Simulation ────────────────────────────────────────────────────────────

    /**
     *  Simulates the scenario and returns the resulting [SimulationRun].
     *
     *  Execution sequence:
     *  1. [modelBuilder] is called with [myModelConfiguration] to produce a fresh [Model].
     *  2. The optional [configureModel] callback is invoked, allowing the caller to attach
     *     observers, set the output directory, or perform any other pre-run setup.
     *  3. [scenarioRunParameters] are applied to the model and the simulation runs.
     *  4. The resulting [SimulationRun] is stored in [simulationRun] and returned.
     *
     *  @param configureModel Optional callback invoked on the freshly-built model before
     *                        the simulation starts.  Intended for callers such as
     *                        [ScenarioRunner] that need to attach observers or set the
     *                        output directory without holding a permanent reference to the model.
     *  @return The [SimulationRun] produced by this invocation.
     */
    fun simulate(configureModel: ((Model) -> Unit)? = null): SimulationRun {
        val model = modelBuilder.build(myModelConfiguration)
        if (model.modelConfigurationManager != null && myModelConfiguration != null) {
            model.configuration = myModelConfiguration!!
        }
        configureModel?.invoke(model)
        val runner = SimulationRunner(model)
        simulationRun = runner.simulate(
            modelIdentifier = model.modelIdentifier,
            inputs = myInputs,
            stringInputs = myStringInputs,
            jsonInputs = myJsonInputs,
            experimentRunParameters = myRunParameters
        )
        return simulationRun!!
    }
}

/**
 *  Can be used to supply logic to configure a model prior to simulating a scenario.
 *
 *  Prefer passing a lambda to [Scenario.simulate] directly over implementing this interface
 *  for new code.  Retained for backward compatibility.
 */
fun interface ScenarioSetupIfc {
    fun setup(model: Model)
}
