package ksl.controls.experiments

import ksl.simulation.Model
import ksl.utilities.Identity
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.OutputDirectory
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import java.io.PrintWriter
import java.nio.file.Path
import kotlin.io.path.deleteRecursively

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
     *  The name of the scenario (based on the model's experiment) must be
     *  unique within the context of the list of scenarios to run.
     */
    @JvmOverloads
    @Suppress("unused")
    fun addScenario(
        model: Model,
        name: String,
        inputs: Map<String, Double>,
        numberReplications: Int = model.numberOfReplications,
        lengthOfReplication: Double = model.lengthOfReplication,
        lengthOfReplicationWarmUp: Double = model.lengthOfReplicationWarmUp,
    ): Scenario {
        val s = Scenario(model, name, inputs, numberReplications, lengthOfReplication, lengthOfReplicationWarmUp)
        addScenario(s)
        return s
    }

    private fun modelExists(model: Model): Boolean {
        for(scenario in myScenarios) {
            if (scenario.model == model) return true
        }
        return false
    }

    private fun addScenario(scenario: Scenario) {
        require(!myScenariosByName.containsKey(scenario.name)) { "Scenario ${scenario.name} already exists" }
        if (!modelExists(scenario.model)) {
            // this is a scenario with a new model
            // Because the default output directory for the model will not be needed, delete it.
            // It will be given a new output directory within the scenario directory.
            val modelCurrentDirectory = scenario.model.outputDirectory.outDir.toFile()
            modelCurrentDirectory.deleteRecursively()
        }
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
                //TODO consider clearing data only if the experiment name already exists
                val scenario = myScenarios[scenarioIndex]
                val modelDirName = scenario.name.replace(" ", "_") + "_OutputDir"
                val modelDir = KSLFileUtil.createSubDirectory(pathToOutputDirectory, modelDirName)
                scenario.model.outputDirectory = OutputDirectory(modelDir, outFileName  = "kslOutput.txt")
                myDbObserversByName[scenario.name] = KSLDatabaseObserver(scenario.model, kslDb)
                scenario.simulate()
                myDbObserversByName[scenario.name]!!.stopObserving()
                // if the model has auto reports on, turn them off so that new reports are independently attached
                if (scenario.model.autoCSVReports){
                    scenario.model.turnOffCSVStatisticalReports()
                }
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

}