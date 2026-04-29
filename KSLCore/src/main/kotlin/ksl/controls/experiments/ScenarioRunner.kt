package ksl.controls.experiments

import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Identity
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.OutputDirectory
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import java.io.PrintWriter
import java.nio.file.Path

/**
 *  Facilitates the running of many scenarios in a sequence. A KSLDatabase
 *  is used to capture the statistics for each scenario. Each scenario is
 *  treated like a different experiment. The scenarios can be based on
 *  the same or different models.  The scenarios also capture the inputs and
 *  results via a SimulationRun.
 *
 *  @param name of the scenario runner. By default, this name
 *  is used as the name of the database
 *  @param scenarioList a list of scenarios to execute
 *  @param kslDb the KSLDatabase that will hold the results from the scenarios
 */
class ScenarioRunner @JvmOverloads constructor(
    name: String,
    scenarioList: List<Scenario> = emptyList(),
    val pathToOutputDirectory: Path = KSL.createSubDirectory(name.replace(" ", "_") + "_OutputDir"),
    val kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"), pathToOutputDirectory)
) : Identity(name) {

    private val myScenarios = mutableListOf<Scenario>()

    /**
     *  A read-only list of the scenarios to be run.
     */
    val scenarioList: List<Scenario>
        get() = myScenarios

    private val myScenariosByName = mutableMapOf<String, Scenario>()
    val scenariosByName: Map<String, Scenario>
        get() = myScenariosByName

    private val myDbObserversByName = mutableMapOf<String, KSLDatabaseObserver>()

    /**
     *  The database observers that were attached to the models. This
     *  property could be used to turn off all or some of the observers
     */
    val dbObservers: List<KSLDatabaseObserver>
        get() = myDbObserversByName.values.toList()

    init {
        for (scenario in scenarioList) {
            addScenario(scenario)
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

    /**
     *  Sets a common number of replications for every scenario managed by this runner.
     *  Updates [Scenario.scenarioRunParameters] directly so that the model is not mutated
     *  and scenarios that share a model remain independent.
     *
     *  @param numReps the number of replications; ignored for individual scenarios if < 1.
     */
    fun numReplicationsPerScenario(numReps: Int) {
        require(numReps >= 1) { "The number of replications for each scenario must be >= 1" }
        for (scenario in myScenarios) {
            scenario.scenarioRunParameters.numberOfReplications = numReps
        }
    }

    /**
     *  Creates a [Scenario] from the three most common run-parameter scalars and adds it to
     *  this runner.  All other run parameters are captured from [model]'s current state.
     *  String and JSON control overrides may be supplied via [stringInputs] and [jsonInputs].
     *
     *  The scenario name must be unique within this runner.
     */
    @JvmOverloads
    @Suppress("unused")
    fun addScenario(
        model: Model,
        name: String,
        inputs: Map<String, Double>,
        stringInputs: Map<String, String> = emptyMap(),
        jsonInputs: Map<String, String> = emptyMap(),
        numberReplications: Int = model.numberOfReplications,
        lengthOfReplication: Double = model.lengthOfReplication,
        lengthOfReplicationWarmUp: Double = model.lengthOfReplicationWarmUp,
    ): Scenario {
        val runParams = model.extractRunParameters().copy(
            numberOfReplications = numberReplications,
            lengthOfReplication = lengthOfReplication,
            lengthOfReplicationWarmUp = lengthOfReplicationWarmUp
        )
        val s = Scenario(model, name, inputs, stringInputs, jsonInputs, runParams)
        addScenario(s)
        return s
    }

    /**
     *  Creates a [Scenario] from a full [ExperimentRunParameters] snapshot and adds it to
     *  this runner.  Use this overload when you need to configure run parameters beyond the
     *  three scalar values (e.g. antithetic option, stream-reset behaviour, stream advances).
     *
     *  The scenario name must be unique within this runner.
     */
    @Suppress("unused")
    @JvmOverloads
    fun addScenario(
        model: Model,
        name: String,
        inputs: Map<String, Double>,
        runParameters: ExperimentRunParameters,
        stringInputs: Map<String, String> = emptyMap(),
        jsonInputs: Map<String, String> = emptyMap(),
    ): Scenario {
        val s = Scenario(model, name, inputs, stringInputs, jsonInputs, runParameters)
        addScenario(s)
        return s
    }

    /**
     *  Creates a [Scenario] from a [ModelBuilderIfc] and a full [ExperimentRunParameters]
     *  snapshot and adds it to this runner.
     *
     *  The scenario name must be unique within this runner.
     */
    @Suppress("unused")
    @JvmOverloads
    fun addScenario(
        modelBuilder: ModelBuilderIfc,
        name: String,
        inputs: Map<String, Double>,
        runParameters: ExperimentRunParameters,
        stringInputs: Map<String, String> = emptyMap(),
        jsonInputs: Map<String, String> = emptyMap(),
    ): Scenario {
        val s = Scenario(modelBuilder, name, inputs, stringInputs, jsonInputs, runParameters)
        addScenario(s)
        return s
    }

    private fun addScenario(scenario: Scenario) {
        require(!myScenariosByName.containsKey(scenario.name)) { "Scenario ${scenario.name} already exists" }
        myScenarios.add(scenario)
        myScenariosByName[scenario.name] = scenario
    }

    /** Interprets the integer progression as the indices of the
     *  contained scenarios that should be simulated. If the
     *  progression is not a valid index then no scenario is simulated.
     *  @param scenarios The indices of the scenarios to execute. By default, all scenarios.
     *  @param clearAllData indicates if all data will be removed from the associated KSLDatabase
     *  prior to executing the scenarios. The default is true. All data is cleared. This assumes
     *  that the basic use case is to re-run the scenarios. If false is specified, then special
     *  care is needed to ensure that no execution of any scenario has the same experiment name.
     *  If the user doesn't change any scenario experiment names, then re-running will result in
     *  an error to prevent unplanned loss of data.
     */
    @JvmOverloads
    fun simulate(scenarios: IntProgression = myScenarios.indices, clearAllData: Boolean = true) {
        if (clearAllData) {
            kslDb.clearAllData()
        }
        for (scenarioIndex in scenarios) {
            if (scenarioIndex in myScenarios.indices) {
                val scenario = myScenarios[scenarioIndex]
                val modelDirName = scenario.name.replace(" ", "_") + "_OutputDir"
                val modelDir = KSLFileUtil.createSubDirectory(pathToOutputDirectory, modelDirName)
                scenario.simulate { model ->
                    model.outputDirectory = OutputDirectory(modelDir, outFileName = "kslOutput.txt")
                    myDbObserversByName[scenario.name] = KSLDatabaseObserver(model, kslDb)
                }
                myDbObserversByName[scenario.name]?.stopObserving()
            }
        }
    }

    /**
     *  Prints basic half-width summary reports for each scenario to the console
     */
    fun print(){
        write(PrintWriter(System.out))
    }

    /**
     *  Writes basic half-width summary reports to the provided PrintWriter
     *  @param out The print writer. By default, this is KSL.out
     */
    @JvmOverloads
    fun write(out: PrintWriter = KSL.out) {
        for(s in scenarioList) {
            val sr = s.simulationRun?.statisticalReporter()
            val r = sr?.halfWidthSummaryReport(title = s.name)
            out.println(r)
            out.println()
        }
    }

    /**
     * Returns a map of scenario name → per-replication observations for
     * [responseName] across all executed scenarios.
     *
     * Keys are [Scenario.name] for each scenario whose [SimulationRun] has
     * non-empty observations for [responseName]. Scenarios not yet executed
     * or that produced no observations for the response are silently omitted.
     * Returns an empty map when no scenarios have been executed.
     *
     * Suitable as the direct input to [ksl.utilities.statistic.Statistic.boxPlotSummaries]
     * for constructing a [ksl.utilities.io.plotting.MultiBoxPlot].
     *
     * @param responseName the response to extract
     */
    fun observationsAsMap(responseName: String): Map<String, DoubleArray> {
        val myResult = linkedMapOf<String, DoubleArray>()
        for (myScenario in scenarioList) {
            val myObs = myScenario.simulationRun?.replicationObservations(responseName)
            if (myObs != null && myObs.isNotEmpty()) {
                myResult[myScenario.name] = myObs
            }
        }
        return myResult
    }

}