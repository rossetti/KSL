package ksl.simopt.evaluator

import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRun
import ksl.controls.experiments.SimulationRunner
import ksl.simulation.Model
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver

/**
 *  This simulation provider will execute evaluation requests on the same model
 *  and collect the desired responses.  This provider runs the model's replications
 *  locally and sequentially in the same execution thread as the requests.
 *
 *  @param modelCreator a function that promises to create the model that will be executed. The model that is created
 *  is assumed to be configured to run.
 *  @param useDb if true a database to capture simulation output is configured. The default is false.
 *  @param clearDataBeforeExperimentOption indicates whether database data should be cleared before each experiment. Only
 *  relevant if useDb is true. The default is false. Data will not be cleared if multiple simulations of the
 *  same model are executed within the same execution frame. An error is issued if the experiment name has not changed.
 *  The experiment names are automatically created based on the execution counter. This allows for every response and
 *  input execution to be captured in the database. In the context of simulation optimization, you may only want the
 *  last provided execution. In that case, set the clear option to true. Then, the database will be cleared prior
 *  to each execution, leaving only the last execution in the database.
 *  @param saveSimulationRuns indicates if the SimulationRun instances created by running the model will be saved. The
 *  default is false.  Since the provider may execute thousands of simulations and simulation runs have substantial
 *  associated data, caution should be considered if setting this option to true. In essence, this allows in-memory
 *  access to all inputs and output responses from every execution.
 */
class SimulationProvider(
    modelCreator: () -> Model,
    var saveSimulationRuns: Boolean = false,
    useDb: Boolean = false,
    clearDataBeforeExperimentOption: Boolean = false,
) : SimulationProviderIfc {

    val model: Model = modelCreator()

    private val mySimulationRunner = SimulationRunner(model)

    /**
     *  capture the original experiment run parameters so that they can
     *  be restored to original after executing a simulation
     */
    private val myOriginalExpRunParams: ExperimentRunParameters = model.extractRunParameters()

    /**
     *  Use to hold executed simulation runs, 1 for each simulation executed.
     *  The key is based on the problem definition name.
     *
     *  "ProblemDefinition.name_Exp_k", where k is the current value of the execution counter.
     */
    private val mySimulationRuns = mutableMapOf<String, SimulationRun>()
    val simulationRuns: Map<String, SimulationRun>
        get() = mySimulationRuns

    //TODO could add ExperimentDataCollector as an option

    /**
     *  The KSLDatabase used to capture model execution results. The name of the experiments
     *  are based on the name of the associated ProblemDefinition, as
     *  "ProblemDefinition.name_Exp_k", where k is the current value of the execution counter.
     */
    var kslDb: KSLDatabase? = null
        private set

    /**
     *  The database observer of the model. Can be used to stop observing, etc.
     *  The observer is created to clear data before experiments.
     */
    var dbObserver: KSLDatabaseObserver? = null
        private set

    /**
     *  Used to count the number of times that the simulation model is executed. Each execution can
     *  be considered a different experiment
     */
    var executionCounter = 0
        private set

    init {
        if (useDb) {
            kslDb = KSLDatabase("${model.simulationName}.db".replace(" ", "_"), model.outputDirectory.dbDir)
            dbObserver = KSLDatabaseObserver(model, kslDb!!, clearDataBeforeExperimentOption)
        }
    }

    /**
     *  Causes the execution counter to be reset to 0. Care must be taken if a database is used to
     *  collect simulation results. The names of the experiments are based on the value of the counter. An
     *  error will occur if multiple experiments have the same name in the database. You will likely want
     *  to export and clear the data from the database prior running additional simulations.
     */
    fun resetExecutionCounter() {
        executionCounter = 0
    }

    /**
     *  Causes any previous simulation runs associated with the execution of the model to be cleared.
     */
    fun clearSimulationRuns() {
        mySimulationRuns.clear()
    }

    override fun runSimulations(requests: List<RequestData>): Map<RequestData, ResponseMap> {
        val results = mutableMapOf<RequestData, ResponseMap>()
        for (request in requests) {
            executionCounter++
            // update experiment name on the model and number of replications
            model.experimentName = request.modelIdentifier + "_Exp_$executionCounter"
            model.numberOfReplications = request.numReplications
            if (request.experimentRunParameters != null) {
                // update the name and the number of replications on the supplied parameters
                request.experimentRunParameters.experimentName = model.experimentName
                request.experimentRunParameters.numberOfReplications = model.numberOfReplications
            }
            Model.logger.info { "SimulationProvider: Running simulation for experiment: ${model.experimentName} " }
            //run the simulation
            val simulationRun = mySimulationRunner.simulate(
                request.inputs,
                request.experimentRunParameters ?: model.extractRunParameters()
            )
            Model.logger.info { "SimulationProvider: Completed simulation for experiment: ${model.experimentName} " }
            // capture the simulation results
            captureResults(request, results, simulationRun)
            // add the SimulationRun to the simulation run list
            if (saveSimulationRuns) {
                mySimulationRuns[model.experimentName] = simulationRun
            }
            // reset the model run parameters back to their original values
            model.changeRunParameters(myOriginalExpRunParams)
        }
        return results
    }

    private fun captureResults(
        request: RequestData,
        results: MutableMap<RequestData, ResponseMap>,
        simulationRun: SimulationRun
    ) {
        // extract the replication data for each simulation response
        val replicationData = simulationRun.results
        // make an empty response map to hold the estimated responses
        val responseNames = request.responseNames
        //TODO if response names is empty this will not work!!!!
        val responseMap = ResponseMap(responseNames)
        // fill the response map
        for (name in responseNames) {
            require(replicationData.containsKey(name)) { "The simulation responses did not contain the requested response name $name" }
            // get the data from the simulation
            val data = replicationData[name]!!
            // compute the estimates from the replication data
            val estimatedResponse = EstimatedResponse(name, data)
            // place the estimate in the response map
            responseMap.add(estimatedResponse)
        }
        // capture the responses for each request
        results[request] = responseMap
    }

}