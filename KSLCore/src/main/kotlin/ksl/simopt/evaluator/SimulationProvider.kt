package ksl.simopt.evaluator

import ksl.controls.experiments.DesignPoint
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
 *  @param model the model to execute. The model's run parameters should be specified prior to running the simulations
 */
class SimulationProvider(
    val model: Model,
    useDb: Boolean = false,
    val saveSimulationRuns: Boolean = false,
) : SimulationProviderIfc {

    private val mySimulationRunner = SimulationRunner(model)

    /**
     *  capture the original experiment run parameters so that they can
     *  be restored to original after executing a simulation
     */
    private val myOriginalExpRunParams: ExperimentRunParameters = model.extractRunParameters()

    /**
     *  Use to hold executed simulation runs, 1 for each simulation executed
     */
    private val mySimulationRuns = mutableMapOf<String, SimulationRun>()

    var kslDb: KSLDatabase? = null
        private set

    /**
     *  The database observer of the model. Can be used to stop observing, etc.
     *  The observer is created to clear data before experiments.
     *  Assumes that if the user is re-running the design that existing data for the experiment
     *  should be deleted.
     */
    var dbObserver: KSLDatabaseObserver? = null
        private set

    init {
        if (useDb) {
            kslDb = KSLDatabase("${model.simulationName}.db".replace(" ", "_"), model.outputDirectory.dbDir)
            dbObserver = KSLDatabaseObserver(model, kslDb!!, true)
        }
    }

    override fun runSimulations(evaluationRequests: List<EvaluationRequest>): Map<EvaluationRequest, ResponseMap> {
        val results = mutableMapOf<EvaluationRequest, ResponseMap>()
        for (request in evaluationRequests) {
            //run the simulation and capture the simulation run
            val simulationRun = mySimulationRunner.simulate(request.inputMap, model.extractRunParameters())
            // extract the replication data for each simulation response
            val replicationData = simulationRun.results
            // make an empty response map to hold the estimated responses
            val responseMap = request.inputMap.problemDefinition.emptyResponseMap()
            // fill the response map
            for((name, _) in responseMap){
                require(replicationData.containsKey(name)){"The simulation responses did not contain the requested response name $name"}
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
        return results
    }

}