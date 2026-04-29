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
 *  A scenario is a **specification** of a model to run with a fixed set of inputs and
 *  run parameters.  Each call to [simulate] produces a new [SimulationRun].
 *
 *  In the context of running multiple scenarios it is important that scenario names are unique
 *  so that each experiment can be identified within a [ksl.utilities.io.dbutil.KSLDatabase].
 *  The scenario name is used as the experiment name for the simulation run.
 *
 *  ### Model construction semantics
 *
 *  [modelBuilder] is called at the start of each [simulate] invocation to produce a fresh
 *  [Model] instance.  This design enables concurrent execution of independent scenarios via
 *  [ConcurrentScenarioRunner]: each run is isolated and leaves no shared state behind.
 *
 *  For backward compatibility, constructors that accept a pre-built [Model] are provided.
 *  They wrap the supplied instance in a `ModelBuilderIfc` that always returns the same model,
 *  so the sequential behaviour is identical to the previous implementation.
 *
 *  **Concurrency note:** scenarios constructed via the `model: Model` backward-compatible
 *  constructors wrap a single shared model instance.  They are safe for sequential execution
 *  via [ScenarioRunner] but **must not** be submitted to [ConcurrentScenarioRunner], which
 *  requires each scenario to produce a fresh, independent [Model] on every [simulate] call.
 *
 *  ### Run-parameter semantics
 *
 *  [scenarioRunParameters] is an owned snapshot captured at construction time.  All 15 fields
 *  of [ExperimentRunParameters] are stored.  The snapshot's experiment name is automatically
 *  set to [name].
 *
 *  At run time the snapshot is applied to the model via [Model.changeRunParameters] inside
 *  [SimulationRunner].
 *
 *  @param modelBuilder       Factory that creates the [Model] instance for each run.
 *  @param name               The scenario name.  Must be unique within the set of scenarios
 *                            executed by a runner.  Also used as the experiment name.
 *  @param inputs             Numeric control and RV-parameter overrides (`key → Double`).
 *  @param stringInputs       String control overrides (`key → String`).
 *  @param jsonInputs         JSON control overrides (`key → JSON String`).
 *  @param runParameters      Full experimental run configuration for this scenario.
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
     *  **Sequential use only.**  Do not submit scenarios constructed this way to
     *  [ConcurrentScenarioRunner].
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
     *  **Sequential use only.**  Do not submit scenarios constructed this way to
     *  [ConcurrentScenarioRunner].
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
     *  invoked once at construction time to extract default run parameters; the
     *  returned model is then discarded.  The three scalar values override the defaults.
     *
     *  Scenarios constructed this way are safe for both [ScenarioRunner] and
     *  [ConcurrentScenarioRunner].
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
     *  Read-only view of the numeric control and RV-parameter overrides for this scenario.
     *  Used by runner classes that build the model and invoke [SimulationRunner] directly.
     */
    val inputs: Map<String, Double> get() = myInputs

    /**
     *  Read-only view of the string control overrides for this scenario.
     *  Used by runner classes that build the model and invoke [SimulationRunner] directly.
     */
    val stringInputs: Map<String, String> get() = myStringInputs

    /**
     *  Read-only view of the JSON control overrides for this scenario.
     *  Used by runner classes that build the model and invoke [SimulationRunner] directly.
     */
    val jsonInputs: Map<String, String> get() = myJsonInputs

    /**
     *  The model configuration map forwarded to [ModelBuilderIfc.build] at run time,
     *  or `null` if no configuration was supplied at construction time.
     *  Used by runner classes that build the model directly.
     */
    val modelConfiguration: Map<String, String>? get() = myModelConfiguration

    /**
     *  The [SimulationRun] produced by the most recent call to [simulate], or `null`
     *  if [simulate] has not yet been called.
     *
     *  Runner classes ([ScenarioRunner], [ConcurrentScenarioRunner]) set this field
     *  directly after executing the simulation.
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
     *  Convenience method for standalone scenario execution.
     *
     *  Builds a fresh model via [modelBuilder], applies [scenarioRunParameters], runs the
     *  simulation, stores the result in [simulationRun], and returns it.
     *
     *  For DB-captured execution across multiple scenarios use [ScenarioRunner] (sequential)
     *  or [ConcurrentScenarioRunner] (parallel).  Those runners build the model and invoke
     *  [SimulationRunner] directly using this scenario's public properties, so they do not
     *  call this method.
     *
     *  @return The [SimulationRun] produced by this invocation.
     */
    fun simulate(): SimulationRun {
        val model = modelBuilder.build(myModelConfiguration)
        if (model.modelConfigurationManager != null && myModelConfiguration != null) {
            model.configuration = myModelConfiguration!!
        }
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
 *  Retained for backward compatibility.  New code should use [ScenarioRunner] or
 *  [ConcurrentScenarioRunner] rather than implementing this interface.
 */
fun interface ScenarioSetupIfc {
    fun setup(model: Model)
}
