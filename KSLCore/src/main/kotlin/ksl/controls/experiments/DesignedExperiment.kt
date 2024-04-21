package ksl.controls.experiments

import ksl.simulation.Model
import ksl.utilities.Identity
import ksl.utilities.KSLArrays
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.io.writeCSV
import java.nio.file.Path

/**
 *  Facilitates the simulation of a model via a factorial design.
 *
 *  The map representing the factor and its associated string requires some further
 *  discussion.  The naming convention for controls and random variable parameters is important to note.
 *
 *  For controls, by default, the key to associate with the value is the model element's name
 *  concatenated with the property that was annotated with the control.  For example, if
 *  the resource had name Worker and annotated property initialCapacity, then the key
 *  will be "Worker.initialCapacity". Note the use of the "." character to separate
 *  the model element name and the property name.  Since, the KSL model element naming
 *  convention require unique names for each model element, the key will be unique for the control.
 *  However, the model element name may be a very long string depending on your approach
 *  to naming the model elements. The name associated with each control can be inspected by
 *  asking the model for its controls via model.controls() and then using the methods on the Controls
 *  class for the names. The controlsMapAsJsonString() or asMap() functions are especially helpful
 *  for this purpose.
 *
 *  For the parameters associated with random variables, the naming convention is different.
 *  Again, the model element name is used as part of the identifier, then the value of
 *  rvParamConCatString from the companion object is concatenated between the name of the
 *  model element and the name of its parameter.  For example, suppose there is a
 *  random variable that has been named ServiceTimeRV that is exponentially distributed.
 *  Also assume that rvParamConCatString is "_PARAM_", which is its default value. Then,
 *  to access the mean of the service time random variable, we use "ServiceTimeRV_PARAM_mean".
 *  Thus, it is important to note the name of the random variable within the model and the
 *  KSL's default names for the random variable parameters.  When random variables are
 *  not explicitly named by the modeler, the KSL will automatically provide a default
 *  unique name. Thus, if you plan to control a specific random variable's parameters, you
 *  should strongly consider providing an explicit name. To get the names (and current values)
 *  of the random variable parameters, you can print out the toString() method of the
 *  RVParameterSetter class after obtaining it from the model via the model's rvParameterSetter
 *  property.
 *
 *  Suppose factor A was associated with the worker's initial capacity and factor B was
 *  associated with the mean of the service time distribution, then the factor settings map
 *  would be mapOf(factorA to "Worker.initialCapacity", factorB to "ServiceTimeRV_PARAM_mean")
 *  where factorA and factorB are references to the associated Factor instances.
 *
 *  @param model the model to simulate
 *  @param factorSettings a mapping between each factor and a string
 *  representing the name of the control or parameter to associate with the factor
 *  @param numRepsPerDesignPoint the number of replications for each design point. Defaults to 10.
 */
class DesignedExperiment(
    name: String,
    private val model: Model,
    private val factorSettings: Map<Factor, String>,
    val design: ExperimentalDesignIfc,
    numRepsPerDesignPoint: Int = 10,
    val kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"), KSL.dbDir)
) : Identity(name) {

    private val mySimulationRunner = SimulationRunner(model)

    /**
     *  The database observer of the model. Can be used to stop observing, etc.
     *  The observer is created to clear data before experiments.
     *  Assumes that if the user is re-running the design that existing data for the experiment
     *  should be deleted.
     */
    val dbObserver: KSLDatabaseObserver = KSLDatabaseObserver(model, kslDb, true)

    /**
     *  capture the original experiment run parameters so that they can
     *  be restored to original after executing a design point
     */
    private val myOriginalExpRunParams: ExperimentRunParameters = model.extractRunParameters()

    /**
     *  Use to hold executed simulation runs, 1 for each design point executed
     */
    private val mySimulationRuns = mutableMapOf<Int, SimulationRun>() //TODO should key be design point?

    /**
     *  Returns the list of executed runs, one run for each design point simulated
     */
    val simulationRuns: List<SimulationRun>
        get() = mySimulationRuns.values.toList()

    init {
        require(factorSettings.isNotEmpty()) { "factorControls must not be empty" }
        require(numRepsPerDesignPoint >= 1) { "The number of replications per design point must be >= 1." }
        // ensure that factors in the design are the same as in the factor settings
        for ((_, factor) in design.factors) {
            require(factorSettings.containsKey(factor))
            { "The factor settings do not contain (${factor.name} which is in the design." }
        }
        // check if supplied control or parameter keys make sense for this model
        require(model.validateInputKeys(factorSettings.values.toSet())) {
            "The factor settings contained invalid input names"
        }
    }

    /**
     *  The factorial design implied by the factors
     */
//    val factorialDesign: FactorialDesignIfc = FactorialDesign(factorSettings.keys, "${model}_Factorial_DOE")

    /**
     *  The number of design points in the base design (without replications)
     */
//    val numDesignPoints: Int
//        get() = factorialDesign.numDesignPoints

    /**
     *  The names of the responses or counters in the model
     */
    val responseNames: List<String>
        get() = model.responseNames

//    private val myReplicates = IntArray(numDesignPoints) { numRepsPerDesignPoint }

    /**
     *  Causes any previous simulation runs associated with the execution of design points
     *  to be cleared.
     */
    fun clearSimulationRuns() {
        mySimulationRuns.clear()
    }

//    /**
//     *  Specifies that each design point have [numReps] replications.
//     *  If [numReps] is less than 1, then it is silently changed to 1.
//     */
//    fun replicationsPerDesignPoint(numReps: Int) {
//        myReplicates.fill(if (numReps < 1) 1 else numReps)
//    }

//    /**
//     *  Allows the number of replications for each of the design points in the design
//     *  to be different by allowing an unbalanced design. If any element in the
//     *  array is less than 1, it is set to 1. There must be at least 1 replicate
//     *  for each design point.
//     */
//    var replicationsPerDesignPoint: IntArray
//        get() = myReplicates.copyOf()
//        set(array) {
//            require(array.size == numDesignPoints) { "The size (${array.size}) of the array must be the number of design points: ${myReplicates.size}" }
//            for ((i, r) in array.withIndex()) {
//                myReplicates[i] = if (array[i] < 1) 1 else array[i]
//            }
//        }

//    /**
//     *  Each design point in the associated factorial design is replicated
//     *  by the number of associated replications held in the property
//     *  designPointReplications. This results in an expanded list of
//     *  design points (as double arrays) with repeated copies
//     *  of the design points within the returned list. The number of
//     *  copies of each design point is based on its associated
//     *  number of replications.
//     */
//    fun replicatedDesignPoints(): List<DoubleArray> {
//        val dpList = mutableListOf<DoubleArray>()
//        val dps = factorialDesign.designPointsToList()
//        for ((i, dp) in dps.withIndex()) {
//            for (r in 1..myReplicates[i]) {
//                dpList.add(dp.copyOf())
//            }
//        }
//        return dpList
//    }

//    /**
//     *  Each coded design point in the associated factorial design is replicated
//     *  by the number of associated replications held in the property
//     *  designPointReplications. This results in an expanded list of
//     *  design points (as double arrays) with repeated copies
//     *  of the design points within the returned list. The number of
//     *  copies of each design point is based on its associated
//     *  number of replications.
//     */
//    fun replicatedCodedDesignPoints(): List<DoubleArray> {
//        val dpList = mutableListOf<DoubleArray>()
//        val dps = factorialDesign.codedDesignPointsToList()
//        for ((i, dp) in dps.withIndex()) {
//            for (r in 1..myReplicates[i]) {
//                dpList.add(dp.copyOf())
//            }
//        }
//        return dpList
//    }

    /**
     *  @param coded indicates if the points should be coded, the default is false
     */
    fun replicatedDesignPoints(coded: Boolean = false) :List<DoubleArray> {
        TODO("Not Implemented yet!")
    }

    /**
     *  Supports extraction of replication identifiers from simulation runs
     */
    private fun replicationIds(): List<Int> {
        val list = mutableListOf<Int>()
        for ((dp, sr) in mySimulationRuns) {
            // dp is design point number 1<=dp<=number of design points
            for (r in 1..myReplicates[dp - 1]) {
                list.add(r)
            }
        }
        return list
    }

    /**
     *  Supports extraction of replicated experiment names from simulation runs
     */
    private fun replicatedExpNames(): List<String> {
        val list = mutableListOf<String>()
        for ((dp, sr) in mySimulationRuns) {
            // dp is design point number 1<=dp<=number of design points
            for (r in 1..myReplicates[dp - 1]) {
                list.add(sr.experimentRunParameters.experimentName)
            }
        }
        return list
    }
    
    /**
     *  Each design point in the associated factorial design is replicated
     *  by the number of associated replications held in the property
     *  designPointReplications. This results in an expanded list of
     *  design points within a dataframe with repeated copies
     *  of the design points within the data frame. The number of
     *  copies of each design point is based on its associated
     *  number of replications.
     *  The data frame has columns (exp_name, rep_id, factor1, factor2, ..., factorN)
     *  where factorK is the name of the kth factor.
     *  @param coded indicates if the points should be coded, the default is false
     */
    fun replicatedDesignPointsAsDataFrame(coded: Boolean = false): AnyFrame {
        val points = KSLArrays.to2DDoubleArray(replicatedDesignPoints(coded))
        val cols = points.toMapOfLists(design.factorNames)
        val expCol = replicatedExpNames().toColumn("exp_name")
        val repIds = replicationIds().toColumn("rep_id")
        val df = if (expCol.size() == points.size) {
            expCol.toDataFrame().add(repIds).add(cols.toDataFrame())
        } else {
            cols.toDataFrame()
        }
        return df
    }

    /**
     *  Returns a data frame that has columns (exp_name, rep_id, [responseName]) where
     *  the values in the [responseName] column have the value of the response for the named experiments
     *  and the replication id (number) for the value.
     */
    fun responseAsDataFrame(responseName: String): AnyFrame {
        return kslDb.withRepViewStatistics(responseName)
    }

    /**
     *  Returns a data frame that has columns (exp_name, rep_id, factor1, factor2, ..., factorN, [responseName]) where
     *  the values in the [responseName] column have the value of the response for the named experiments
     *  and the replication id (number) for the value.  The dataframe provides the data
     *  for performing a response surfacing model for the named response.
     *  @param coded indicates if the points should be coded, the default is false
     */
    fun replicatedDesignPointsWithResponse(responseName: String, coded: Boolean = false): AnyFrame {
        return replicatedDesignPointsWithResponses(setOf(responseName), coded)
    }

    /**
     *  Returns a data frame that has columns:
     *
     *  (exp_name, rep_id, factor1, factor2, ..., factorN, responseName1, responseName2, ...)
     *
     *  where the values in the response name columns have the value of the response for the named experiments
     *  and the replication id (number) for the value.  The dataframe provides the data
     *  for performing a response surfacing model for the named responses.
     *  @param coded indicates if the points should be coded, the default is false
     */
    fun replicatedDesignPointsWithResponses(
        names: Set<String> = responseNames.toSet(),
        coded: Boolean = false
    ): AnyFrame {
        require(names.isNotEmpty()) { "The supplied names cannot be empty" }
        val vn = responseNames
        var df = replicatedDesignPointsAsDataFrame(coded)
        val exp_name by column<String>()
        val rep_id by column<Int>()
        for (name in names) {
            if (vn.contains(name)) {
                val df2 = responseAsDataFrame(name)
                df = df.join(df2, type = JoinType.Inner) { exp_name and rep_id }
            }
        }
        return df
    }

    fun simulate(
        iterator: Iterator<DesignPoint> = design.iterator(),
        clearRuns: Boolean = true,
        addRuns: Boolean = true
    ) {
        while (iterator.hasNext()) {
            val dp = iterator.next()
            //TODO set number of replications
            if (dp.numReplications >= 1){
                simulate(dp, clearRuns = clearRuns, addRuns = addRuns)
            }
        }
    }

    /**
     *  Simulates the specified design point from the factorial design for the
     *  specified number of replications.  The specified number of replications
     *  will override whatever is specified in the model's specified number of replications.
     *  Also, the model experiment's name is automatically to use the supplied [baseExperimentName]
     *  with the design point identity concatenated to ensure that is experiment has
     *  a unique name when capturing the data within a database.
     *
     *  @param designPoint the design point to simulate
     *  @param baseExperimentName the base name for each experiment representing the design point
     *  @param clearRuns Any prior simulation runs are cleared prior to executing. The default is false
     *  @param addRuns If true the executed run will be added to the executed simulation runs. The
     *  default is true.
     */
    fun simulate(
        designPoint: DesignPoint,
        baseExperimentName: String = "${myOriginalExpRunParams.experimentName}_DP_${designPoint.number}",
        clearRuns: Boolean = false,
        addRuns: Boolean = true
    ) {
        require(designPoint.design == design) { "The design point was not associated with this experiment." }
        if (clearRuns) {
            clearSimulationRuns()
        }
        val dp = designPoint.settings
        // dp holds (factor name, factor level) for the factors at this design point
        // use to hold the inputs for the simulation
        val inputs = mutableMapOf<String, Double>()
        // fill the inputs map based on the factor level settings
        // the simulation runner takes care of assigning the inputs to the model
        for ((f, v) in dp) {
            // use the factor from the design point settings
            // to get the control parameter from the factor setting
            val cp = factorSettings[f]!!
            // get correct control name or parameter name for assigning to input map
            inputs[cp] = v
        }
        // setup experiment and its name
        model.numberOfReplications = designPoint.numReplications
        model.experimentName = baseExperimentName
        // use SimulationRunner to run the simulation
        Model.logger.info { "FactorialExperiment: Running design point $designPoint for experiment: ${model.experimentName} " }
        val sr = mySimulationRunner.simulate(inputs, model.extractRunParameters())
        Model.logger.info { "FactorialExperiment: Completed design point $designPoint for experiment: ${model.experimentName} " }
        // add SimulationRun to simulation run list
        if (addRuns) {
            mySimulationRuns[designPoint.number] = sr
        }
        // reset the model run parameters back to their original values
        model.changeRunParameters(myOriginalExpRunParams)
    }


    /**
     *  Writes the results to a csv formatted file
     *
     *  (exp_name, rep_id, factor1, factor2, ..., factorN, responseName1, responseName2, ...)
     *
     *  where the values in the response name columns have the value of the response for the named experiments
     *  and the replication id (number) for the value.
     */
    fun resultsToCSV(
        fileName: String = name.replace(" ", "_") + ".csv",
        directory: Path = KSL.csvDir
    ) {
        val df = replicatedDesignPointsWithResponses()
        val out = KSLFileUtil.createPrintWriter(directory.resolve(fileName))
        df.writeCSV(out)
    }

    /**
     *  Writes the results to a csv formatted file
     *
     *  (exp_name, rep_id, factor1, factor2, ..., factorN, responseName1, responseName2, ...)
     *
     *  where the values in the response name columns have the value of the response for the named experiments
     *  and the replication id (number) for the value.
     */
    fun codedResultsToCSV(
        fileName: String = name.replace(" ", "_") + ".csv",
        directory: Path = KSL.csvDir
    ) {
        val df = replicatedCodedDesignPointsWithResponses()
        val out = KSLFileUtil.createPrintWriter(directory.resolve(fileName))
        df.writeCSV(out)
    }
}