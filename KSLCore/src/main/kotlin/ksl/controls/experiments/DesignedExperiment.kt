package ksl.controls.experiments

import ksl.simulation.Model
import ksl.utilities.Identity
import ksl.utilities.KSLArrays
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.addColumnsFor
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.statistic.OLSRegression
import ksl.utilities.statistic.RegressionData
import ksl.utilities.statistic.RegressionResultsIfc
import ksl.utilities.toMapOfLists
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.io.writeCSV
import java.nio.file.Path

data class DesignPointInfo(val point: Int, val exp_name: String, val rep_id: Int)

/**
 *  Facilitates the simulation of a model via an experimental design.
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
 *  Also assume that rvParamConCatString is ".", which is its default value. Then,
 *  to access the mean of the service time random variable, we use "ServiceTimeRV.mean".
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
 *  would be mapOf(factorA to "Worker.initialCapacity", factorB to "ServiceTimeRV.mean")
 *  where factorA and factorB are references to the associated Factor instances.
 *
 *  @param name the name of the experiment for saving simulation results
 *  @param model The model to simulate.
 *  @param factorSettings A mapping between each factor and a string
 *  representing the name of the control or parameter to associate with the factor.
 *  @param design The design that will be simulated. The factors specified in the design must
 *  be contained in the factor settings.
 *  @param kslDb a KSLDatabase that will hold the data from the experiment.
 */
class DesignedExperiment(
    name: String,
    private val model: Model,
    private val factorSettings: Map<Factor, String>,
    val design: ExperimentalDesignIfc,
    val kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"),
        model.outputDirectory.dbDir)
) : Identity(name) {

    /**
     *  @param name the name of the experiment for saving simulation results
     *  @param model The model to simulate.
     *  @param twoLevelSettings A mapping between each factor and a string
     *  representing the name of the control or parameter to associate with the factor.
     *  @param design The design that will be simulated. The factors specified in the design must
     *  be contained in the factor settings.
     *  @param kslDb a KSLDatabase that will hold the data from the experiment.
     */
    constructor(
        name: String,
        model: Model,
        twoLevelSettings: Map<TwoLevelFactor, String>,
        design: TwoLevelFactorialDesign,
        kslDb: KSLDatabase = KSLDatabase("${name}.db".replace(" ", "_"),
            model.outputDirectory.dbDir)
    ) : this(name, model, twoLevelFactorSetting(twoLevelSettings), design, kslDb)

    /**
     *
     *  @param name the name of the experiment for saving simulation results
     *  @param modelCreator A function designed to create the model
     *  @param factorSettings A mapping between each factor and a string
     *  representing the name of the control or parameter to associate with the factor.
     *  @param design The design that will be simulated. The factors specified in the design must
     *  be contained in the factor settings.
     */
    constructor(
        name: String,
        modelCreator: () -> Model,
        factorSettings: Map<Factor, String>,
        design: TwoLevelFactorialDesign
    ) : this(name, modelCreator(), factorSettings, design)

    private val mySimulationRunner = SimulationRunner(model)

    /**
     *  A default value for the number of replications per design point.
     *  Used only if specified greater that or equal to 1.  This
     *  will overwrite the number of replications for every design point
     *  if specified greater than 0. By default, it is set to -1, causing
     *  the design points to use the specification from the design.
     */
    var defaultNumRepsPerDesignPoint: Int = -1

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
    private val mySimulationRuns = mutableMapOf<DesignPoint, SimulationRun>()

    /**
     *  Returns the list of executed runs, one run for each design point simulated
     */
    val simulationRuns: List<SimulationRun>
        get() = mySimulationRuns.values.toList()

    init {
        require(factorSettings.isNotEmpty()) { "factorControls must not be empty" }
        // ensure that factors in the design are the same as in the factor settings
        for ((_, factor) in design.factors) {
            require(factorSettings.containsKey(factor))
            { "The factor settings do not contain (${factor.name} which is in the design." }
        }
        // check if supplied control or parameter keys make sense for this model
        require(model.validateInputKeys(factorSettings.values.toSet())) {
            "The factor settings, ${factorSettings.values.toSet().joinToString(prefix = "[", postfix = "]")} , contained invalid input names"
        }
    }

    /**
     *  The number of design points executed in the base design (without replications)
     */
    val numSimulationRuns: Int
        get() = mySimulationRuns.size

    /**
     *  The names of the responses or counters in the model
     */
    val responseNames: List<String>
        get() = model.responseNames

    /**
     *  Causes any previous simulation runs associated with the execution of design points
     *  to be cleared.
     */
    fun clearSimulationRuns() {
        mySimulationRuns.clear()
    }

    /**
     *  @param coded indicates if the points should be coded, the default is false
     */
    fun replicatedDesignPoints(coded: Boolean = false): List<DoubleArray> {
        val dpList = mutableListOf<DoubleArray>()
        for ((dp, _) in mySimulationRuns) {
            for (i in 1..dp.numReplications) {
                val points = if (coded) dp.codedValues() else dp.values()
                dpList.add(points)
            }
        }
        return dpList
    }

    /**
     *  Returns replicated design point information in the order that the points were executed
     *  as a data frame.
     */
    fun replicatedDesignPointInfo(): DataFrame<DesignPointInfo> {
        val list = mutableListOf<DesignPointInfo>()
        for ((dp, sr) in mySimulationRuns) {
            // dp is design point number 1<=dp<=number of design points
            for (r in 1..dp.numReplications) {
                list.add(DesignPointInfo(dp.number, sr.experimentRunParameters.experimentName, r))
            }
        }
        return list.toDataFrame()
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
        if (mySimulationRuns.isEmpty()) {
            return DataFrame.empty()
        }
        val points = KSLArrays.to2DDoubleArray(replicatedDesignPoints(coded))
        val cols = points.toMapOfLists(design.factorNames)
        val dpi = replicatedDesignPointInfo()
        val df = if (dpi.rowsCount() == points.size) {
            dpi.add(cols.toDataFrame())
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
        val dpi = replicatedDesignPointInfo()
        val df = kslDb.withinRepViewStatistics(responseName)
        if (dpi.rowsCount() != df.rowsCount()) {
            return DataFrame.empty()
        }
        val exp_name by column<String>()
        val rep_id by column<Int>()
        return dpi.join(df, type = JoinType.Inner) { exp_name and rep_id }
    }

    /**
     *  Returns a data frame that has columns
     *  (point, exp_name, rep_id, [responseName], factor1, factor2, ..., factorN) where
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
     *  (point, exp_name, rep_id, responseName1, responseName2, ..., factor1, factor2, ..., factorN)
     *
     *  where the values in the response name columns have the value of the response for the named experiments
     *  and the replication id (number) for the value.  The dataframe provides the data
     *  for performing a response surfacing model for the named responses.
     *  @param coded indicates if the points should be coded, the default is false.
     */
    fun replicatedDesignPointsWithResponses(
        names: Set<String> = responseNames.toSet(),
        coded: Boolean = false
    ): AnyFrame {
        require(names.isNotEmpty()) { "The supplied names cannot be empty" }
        if (mySimulationRuns.isEmpty()) {
            return DataFrame.empty()
        }
        val vn = responseNames
        var df = replicatedDesignPointsAsDataFrame(coded)
        val exp_name by column<String>()
        val rep_id by column<Int>()
        val point by column<Int>()
        for (name in names) {
            if (vn.contains(name)) {
                val df2 = responseAsDataFrame(name)
                df = df2.join(df, type = JoinType.Inner) { exp_name and rep_id and point }
            }
        }
        return df
    }

    /**
     *  The regression data to perform the regression of the linear model
     *
     *  @param responseName the name of the response variable in the regression
     *  @param linearModel the linear model specification for the regression
     *  @param coded if true perform the regression with the coded variables. The default is true.
     *  @return the data necessary to perform the regression analysis as a dataframe
     */
    fun regressionDataAsDataFrame(
        responseName: String,
        linearModel: LinearModel,
        coded: Boolean = true
    ): AnyFrame {
        // get the base dataframe with the response
        var df = replicatedDesignPointsWithResponse(responseName, coded)
        df = df.addColumnsFor(linearModel)
        return df
    }

    /**
     *  The regression data to perform the regression of the linear model
     *
     *  @param responseName the name of the response variable in the regression
     *  @param linearModel the linear model specification for the regression
     *  @param coded if true perform the regression with the coded variables. The default is true.
     *  @return the data necessary to perform the regression analysis
     */
    fun regressionData(
        responseName: String,
        linearModel: LinearModel,
        coded: Boolean = true
    ): RegressionData {
        val df = regressionDataAsDataFrame(responseName, linearModel, coded)
        val rns = linearModel.termsAsMap.keys.toList()
        return RegressionData.create(df, responseName, rns, linearModel.intercept)
    }

    /**
     *  Perform the regression of the linear model for predicting the response.
     *  @param responseName the name of the response variable in the regression
     *  @param linearModel the linear model specification for the regression
     *  @param coded if true perform the regression with the coded variables. The default is true.
     *  @return the regression results
     */
    fun regressionResults(
        responseName: String,
        linearModel: LinearModel,
        coded: Boolean = true
    ): RegressionResultsIfc {
        val rd = regressionData(responseName, linearModel, coded)
        return OLSRegression(rd)
    }

    /**
     *   Causes all design points to be simulated as presented by the design's
     *   default iterator using the number of replications specified by the design points.
     *
     *   @param numRepsPerDesignPoint the number of replications per design point. If null (the default)
     *   then the specification of replications is obtained from the design point. If a value
     *   greater than or equal to 1 is supplied, the value is used for every executed design point.
     *   @param clearRuns indicates that any previous simulation runs for the design points will be cleared
     *   prior to executing these design points. The default is true.
     *   @param addRuns If true the executed simulations will be added to the executed simulation runs. The
     *   default is true.
     */
    fun simulateAll(
        numRepsPerDesignPoint: Int? = null,
        clearRuns: Boolean = true,
        addRuns: Boolean = true
    ) {
        simulate(design.iterator(), numRepsPerDesignPoint, clearRuns, addRuns)
    }

    /**
     *   Causes all design points to be simulated as presented by the [iterator]
     *   using the number of replications specified by the design points.
     *
     *   @param iterator an iterator that presents the design points to simulate. If the iterator
     *   is empty then a warning is printed.
     *   @param numRepsPerDesignPoint the number of replications per design point. If null (the default)
     *   then the specification of replications is obtained from the design point. If a value
     *   greater than or equal to 1 is supplied, the value is used for every executed design point.
     *   @param clearRuns indicates that any previous simulation runs for the design points will be cleared
     *   prior to executing these design points. The default is true.
     *   @param addRuns If true the executed simulations will be added to the executed simulation runs. The
     *   default is true.
     */
    fun simulate(
        iterator: Iterator<DesignPoint>,
        numRepsPerDesignPoint: Int? = null,
        clearRuns: Boolean = true,
        addRuns: Boolean = true
    ) {
        if (!iterator.hasNext()) {
            val wm = "WARNING: The supplied iterator for designed experiment, $name, had no design points."
            Model.logger.warn { wm }
            println()
            println(wm)
            println()
            System.out.flush()
        }
        if (clearRuns) {
            clearSimulationRuns()
        }
        while (iterator.hasNext()) {
            val dp = iterator.next()
            // set number of replications
            if (numRepsPerDesignPoint != null) {
                if (numRepsPerDesignPoint > 0) {
                    dp.numReplications = numRepsPerDesignPoint
                }
            }
            simulate(dp, addRuns = addRuns)
        }
    }

    /**
     *  Simulates the specified design point from the factorial design for the
     *  specified number of replications.  The specified number of replications
     *  will override whatever is specified in the model's specified number of replications.
     *  Also, the model experiment's name is automatically set to use the supplied [baseExperimentName]
     *  with the design point identity concatenated to ensure that the experiment has
     *  a unique name when capturing the data within a database.
     *
     *  @param designPoint the design point to simulate
     *  @param baseExperimentName the base name for each experiment representing the design point
     *  @param clearRuns Any prior simulation runs are cleared prior to executing. The default is false
     *  @param addRuns If true the executed run will be added to the executed simulation runs. The
     *  default is true.
     */
    private fun simulate(
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
        Model.logger.info { "DesignedExperiment: Running design point $designPoint for experiment: ${model.experimentName} " }
        val sr = mySimulationRunner.simulate(
            modelIdentifier = model.simulationName,
            inputs = inputs,
            experimentRunParameters = model.extractRunParameters()
        )
        Model.logger.info { "DesignedExperiment: Completed design point $designPoint for experiment: ${model.experimentName} " }
        // add SimulationRun to simulation run list
        if (addRuns) {
            mySimulationRuns[designPoint] = sr
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
     *  @param coded indicates if the points should be coded, the default is false
     */
    fun resultsToCSV(
        fileName: String = name.replace(" ", "_") + ".csv",
        directory: Path = model.outputDirectory.csvDir,
        coded: Boolean = false
    ) {
        val df = replicatedDesignPointsWithResponses(coded = coded)
        val out = KSLFileUtil.createPrintWriter(directory.resolve(fileName))
        df.writeCSV(out)
    }

    companion object {

        /**
         *  Converts a two level factor setting map to a standard factor setting map
         */
        fun twoLevelFactorSetting(twoLevelSettings: Map<TwoLevelFactor, String>): Map<Factor, String> {
            val map = mutableMapOf<Factor, String>()
            for ((key, value) in twoLevelSettings) {
                map[key] = value
            }
            return map
        }
    }
}