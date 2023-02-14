/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.io.dbutil

import ksl.controls.ControlIfc
import ksl.controls.Controls
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.RVParameterData
import ksl.utilities.random.rvariable.RVParameterSetter
import ksl.utilities.statistic.BatchStatisticIfc
import ksl.utilities.statistic.StatisticIfc
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.*
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.*
import java.sql.SQLException
import java.time.ZonedDateTime

class KSLDb(private val db: Database, clearDataOption: Boolean = false) : DatabaseIOIfc by db {

    //TODO number of replications for experiments is always the number for the simulation run
    // SimulationRunner is still chunking experiments the old way 1 experiment = 1 simulation run
    // possible solution, sim runner puts overall experiment into database first, then submits
    // the chunks
    // issue: a model may have many databases and the runner cannot know this, even the model
    // does not know this
    // isChunked is not copying over
    // need to fix other KSL_DB script

    /** This constructs a SQLite database on disk and configures it to hold KSL simulation data.
     * The database will be empty.
     *
     * @param dbName the name of the database
     * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
     * @param clearDataOption indicates if the data should be cleared. The default is true.
     * @return an empty embedded SQLite database configured to hold KSL simulation results
     */
    constructor(dbName: String, dbDirectory: Path = KSLDatabase.dbDir, clearDataOption: Boolean = true) : this(
        KSLDatabase.createSQLiteKSLDatabase(dbName, dbDirectory), clearDataOption
    )

    /**
     *  If true the underlying database was configured as a KSLDatabase
     */
    val configured: Boolean

    init {
        configured = checkTableNames()
        if (!configured) {
            DatabaseIfc.logger.error { "The database does not have the required tables for a KSLDatabase" }
            throw KSLDatabaseNotConfigured()
        }
        if (clearDataOption) {
            clearAllData()
        }
    }

    fun clearAllData() {
        // remove all data from user tables
        var i = 0
        for (tblName in TableNames) {
            val b = db.deleteAllFrom(tblName, db.defaultSchemaName)
            if (b) {
                i++
            }
        }
        DatabaseIfc.logger.info { "Cleared data for $i tables out of ${TableNames.size} tables for KSLDatabase ${db.label}" }
    }

    private fun checkTableNames(): Boolean {
        //check if supplied database is configured as KSL database
        // by checking if the names of the tables match with the KSL table names
        // an admittedly poor test, but it is something
        val tableNames = if (db.defaultSchemaName != null) {
            db.tableNames(db.defaultSchemaName!!)
        } else {
            db.userDefinedTables
        }
        for (name in KSLDatabase.TableNames) {
            if (!containsTableName(name, tableNames)) {
                return false
            }
        }
        return true
    }

    private fun containsTableName(name: String, list: List<String>): Boolean {
        for (tn in list) {
            if (tn.equals(name, true)) {
                return true
            }
        }
        return false
    }

    //TODO need update routine for simulation run data
    private lateinit var currentExp: ExperimentData
    private var currentSimRun: SimulationRunData? = null

    /**
     * Returns the names of the experiments in the EXPERIMENT table.
     */
    val experimentNames: List<String>
        get() {
            val list = mutableSetOf<String>()
            val data: List<ExperimentData> = db.selectTableDataInto(::ExperimentData)
            for (d in data) {
                list.add(d.expName)
            }
            return list.toList()
        }

    /**
     *  Retrieves the data for the named experiment or null if an experiment
     *  with the provided [expName] name is not found in the database
     */
    fun fetchExperimentData(expName: String): ExperimentData? {
        val data: List<ExperimentData> = db.selectTableDataInto(::ExperimentData)
        for (d in data) {
            if (d.expName == expName) {
                return d
            }
        }
        return null
    }

    /**
     *  Checks if the supplied experiment name exists within the database.
     *  Experiment names should be unique within the database
     *  @param expName the name to check
     *  @return true if found
     */
    fun doesExperimentRecordExist(expName: String): Boolean {
        return experimentNames.contains(expName)
    }

    /**
     * Deletes all simulation data associated with the supplied model. In other
     * words, the simulation run data associated with a simulation with the
     * name and the experiment with the name.
     *
     * @param model the model to clear data from
     */
    fun clearSimulationData(model: Model) {
        val expName = model.experimentName
        // find the record and delete it. This should cascade all related records
        deleteExperimentWithName(expName)
    }

    /**
     * The expName should be unique within the database. Many
     * experiments can be run with different names for the same simulation. This method
     * deletes the experiment record with the provided name AND all related data
     * associated with that experiment.  If an experiment record does not
     * exist with the expName, then nothing occurs.
     *
     * @param expName the experiment name for the simulation
     * @return true if the record was deleted, false if it was not
     */
    fun deleteExperimentWithName(expName: String): Boolean {
        try {
            DatabaseIfc.logger.trace { "Getting a connection to delete experiment $expName in database: $label" }
            db.getConnection().use { connection ->
                val ps = db.makeDeleteFromPreparedStatement(connection, "experiment", "expName", defaultSchemaName)
                ps.setString(1, expName)
                val deleted = ps.execute()
                if (deleted) {
                    DatabaseIfc.logger.trace { "Deleted Experiment, $expName, for simulation." }
                } else {
                    DatabaseIfc.logger.trace { "PreparedStatement: Experiment, $expName, was not deleted." }
                }
                return deleted
            }
        } catch (e: SQLException) {
            DatabaseIfc.logger.warn {"There was an SQLException when trying to delete experiment $expName"}
            DatabaseIfc.logger.warn {"SQLException: $e"}
            return false
        }
    }

    /**
     * The expName should be unique within the database. Many
     * experiments can be run with different names for the same simulation. This method
     * deletes any simulation runs for the given named experiment
     * from the simulation_run table
     *
     * @param expId the experiment name for the simulation
     * @param runName the related simulation run name
     * @return true if the record was deleted, false if it was not
     */
    private fun deleteSimulationRunWithName(expId: Int, runName: String): Boolean {
        try {
            DatabaseIfc.logger.trace { "Getting a connection to delete simulation run $runName from experimentId = $expId in database: $label" }
            db.getConnection().use { connection ->
                var sql = DatabaseIfc.deleteFromTableWhereSQL("simulation_run", "runName", defaultSchemaName)
                sql = "$sql and exp_id_fk = ?"
                val ps = connection.prepareStatement(sql)
                ps.setString(1, runName)
                ps.setInt(2, expId)
                val deleted = ps.execute()
                if (deleted) {
                    DatabaseIfc.logger.trace { "Deleted SimulationRun, $runName, for experiment $expId." }
                } else {
                    DatabaseIfc.logger.trace { "PreparedStatement: SimulationRun, $runName, was not deleted." }
                }
                return deleted
            }
        } catch (e: SQLException) {
            DatabaseIfc.logger.warn {"There was an SQLException when trying to delete simulation run: $runName"}
            DatabaseIfc.logger.warn {"SQLException: $e"}
            return false
        }
    }

    val withinRepResponseViewStatistics: DataFrame<WithinRepViewData>
        get() = db.selectTableDataInto(::WithinRepViewData).toDataFrame()

    val withinRepCounterViewStatistics: DataFrame<WithinRepCounterViewData>
        get() = db.selectTableDataInto(::WithinRepCounterViewData).toDataFrame()

    val withinRepViewStatistics: DataFrame<WithinRepViewData>
        get() = db.selectTableDataInto(::WithinRepViewData).toDataFrame()

    val acrossReplicationStatistics: DataFrame<AcrossRepViewData>
        get() = db.selectTableDataInto(::AcrossRepViewData).toDataFrame()

    val withinReplicationResponseStatistics: DataFrame<WithinRepStatData>
        get() = db.selectTableDataInto(::WithinRepStatData).toDataFrame()

    val withinReplicationCounterStatistics: DataFrame<WithinRepCounterStatData>
        get() = db.selectTableDataInto(::WithinRepCounterStatData).toDataFrame()

    val batchingStatistics: DataFrame<BatchStatData>
        get() = db.selectTableDataInto(::BatchStatData).toDataFrame()

    val expStatRepViewStatistics: DataFrame<ExpStatRepViewData>
        get() = db.selectTableDataInto(::ExpStatRepViewData).toDataFrame()

    val pairWiseDiffViewStatistics: DataFrame<PWDiffWithinRepViewData>
        get() = db.selectTableDataInto(::PWDiffWithinRepViewData).toDataFrame()

    internal fun beforeExperiment(model: Model) {
        val experimentRecord = fetchExperimentData(model.experimentName)
        if (experimentRecord != null) {
            // experiment record exists, this must be a simulation run related to a chunk
            if (model.isChunked) {
                // run is a chunk, make sure there is not an existing simulation run
                // just assume user wants to write over any existing chunks with the same name for this
                // simulation execution,
                currentExp = experimentRecord
                deleteSimulationRunWithName(experimentRecord.expId, model.runName)
            } else {
                // not a chunk, same experiment but not chunked, this is a potential user error
                reportExistingExperimentRecordError(model)
            }
        } else {
            currentExp = createExperimentData(model)
            db.insertDbDataIntoTable(currentExp)
        }
        // start simulation run record
//TODO        insertSimulationRun(model)
        // insert the model elements into the database
        val modelElements: List<ModelElement> = model.getModelElements()
//TODO        insertModelElements(modelElements)
        if (model.hasExperimentalControls()) {
            // insert controls if they are there
            val controls: Controls = model.controls()
//TODO            insertDbControlRecords(controls.asList())
        }
        if (model.hasParameterSetter()) {
            // insert the random variable parameters
            val ps: RVParameterSetter = model.rvParameterSetter
//TODO            insertDbRvParameterRecords(ps.rvParametersData)
        }
    }

    private fun reportExistingExperimentRecordError(model: Model) {
        val simName: String = model.simulationName
        val expName: String = model.experimentName
        KSL.logger.error { "An experiment record exists for simulation: $simName, and experiment: $expName in database ${db.label}" }
        KSL.logger.error("The user attempted to run a simulation for an experiment that has ")
        KSL.logger.error(" the same name as an existing experiment without allowing its data to be cleared.")
        KSL.logger.error("The user should consider using the clearDataBeforeExperimentOption property on the observer.")
        KSL.logger.error("Or, the user might change the name of the experiment before calling model.simulate().")
        KSL.logger.error(
            "This error is to prevent the user from accidentally losing data associated with simulation: {}, and experiment: {} in database {}",
            simName, expName, db.label
        )
        throw DataAccessException("An experiment record already exists with the experiment name $expName. Check the ksl.log for details.")
    }

    private fun createExperimentData(model: Model): ExperimentData {
        val record = ExperimentData()
        record.simName = model.simulationName
        record.expName = model.experimentName
        record.modelName = model.name
        record.numReps = model.numberOfReplications
        record.isChunked = model.isChunked
        if (!model.lengthOfReplication.isNaN() && model.lengthOfReplication.isFinite()) {
            record.lengthOfRep = model.lengthOfReplication
        }
        record.lengthOfWarmUp = model.lengthOfReplicationWarmUp
        record.repAllowedExecTime = model.maximumAllowedExecutionTime.inWholeMilliseconds
        record.repInitOption = model.replicationInitializationOption
        record.resetStartStreamOption = model.resetStartStreamOption
        record.antitheticOption = model.antitheticOption
        record.advNextSubStreamOption = model.advanceNextSubStreamOption
        record.numStreamAdvances = model.numberOfStreamAdvancesPriorToRunning
        record.gcAfterRepOption = model.garbageCollectAfterReplicationFlag
        return record
    }

    private fun createSimulationRunData(model: Model): SimulationRunData{
        val record: SimulationRunData = SimulationRunData()
        record.expIdFk = currentExp.expId
        record.numReps = model.numberOfReplications
        record.runName = model.runName
        record.startRepId = model.startingRepId
        record.runStartTimeStamp = ZonedDateTime.now().toLocalDateTime()
        return record
    }

    private fun createDbModelElement(element: ModelElement, expId: Int): ModelElementData {
        val dbm = ModelElementData()
        dbm.expIdFk = expId
        dbm.elementName = element.name
        dbm.elementId = element.id
        dbm.className = element::class.simpleName!!
        if (element.myParentModelElement != null) {
            dbm.parentIdFk = element.myParentModelElement!!.id
            dbm.parentName = element.myParentModelElement!!.name
        }
        dbm.leftCount = element.leftTraversalCount
        dbm.rightCount = element.rightTraversalCount
        return dbm
    }

    private fun createDbControlRecord(control: ControlIfc, expId: Int): ControlData {
        val c = ControlData()
        c.expIdFk = expId
        c.elementIdFk = control.elementId
        c.keyName = control.keyName
        c.controlValue = control.value
        c.lowerBound = control.lowerBound
        c.upperBound = control.upperBound
        c.propertyName = control.propertyName
        c.controlType = control.type.toString()
        c.comment = control.comment
        return c
    }

    private fun createDbRvParameterRecord(rvParamData: RVParameterData, expId: Int): RvParameterData {
        val rvp = RvParameterData()
        rvp.expIdFk = expId
        rvp.elementIdFk = rvParamData.elementId
        rvp.className = rvParamData.clazzName
        rvp.dataType = rvParamData.dataType
        rvp.rvName = rvParamData.rvName
        rvp.paramName = rvParamData.paramName
        rvp.paramValue = rvParamData.paramValue
        return rvp
    }

    private fun createWithinRepStatRecord(response: Response, simId: Int): WithinRepStatData {
        val r = WithinRepStatData()
        r.elementIdFk = response.id
        r.simRunIdFk = simId
        r.repId = response.model.currentReplicationId
        val s = response.withinReplicationStatistic
        r.statName = s.name
        if (!s.count.isNaN() && s.count.isFinite()) {
            r.statCount = s.count
        }
        if (!s.weightedAverage.isNaN() && s.weightedAverage.isFinite()) {
            r.average = s.weightedAverage
        }
        if (!s.min.isNaN() && s.min.isFinite()) {
            r.minimum = s.min
        }
        if (!s.max.isNaN() && s.max.isFinite()) {
            r.maximum = s.max
        }
        if (!s.weightedSum.isNaN() && s.weightedSum.isFinite()) {
            r.weightedSum = s.weightedSum
        }
        if (!s.sumOfWeights.isNaN() && s.sumOfWeights.isFinite()) {
            r.sumOfWeights = s.sumOfWeights
        }
        if (!s.weightedSumOfSquares.isNaN() && s.weightedSumOfSquares.isFinite()) {
            r.weightedSsq = s.weightedSumOfSquares
        }
        if (!s.lastValue.isNaN() && s.lastValue.isFinite()) {
            r.lastValue = s.lastValue
        }
        if (!s.lastWeight.isNaN() && s.lastWeight.isFinite()) {
            r.lastWeight = s.lastWeight
        }
        return r
    }

    private fun createWithinRepCounterRecord(counter: Counter, simId: Int): WithinRepCounterStatData {
        val r = WithinRepCounterStatData()
        r.elementIdFk = counter.id
        r.simRunIdFk = simId
        r.repId = counter.model.currentReplicationId
        r.statName = counter.name
        if (!counter.value.isNaN() && counter.value.isFinite()) {
            r.lastValue = counter.value
        }
        return r
    }

    private fun createAcrossRepStatRecord(response: ModelElement, simId: Int, s: StatisticIfc): AcrossRepStatData {
        val r = AcrossRepStatData()
        r.elementIdFk = response.id
        r.simRunIdFk = simId
        r.statName = s.name
        if (!s.count.isNaN() && s.count.isFinite()) {
            r.statCount = s.count
        }
        if (!s.average.isNaN() && s.average.isFinite()) {
            r.average = s.average
        }
        if (!s.standardDeviation.isNaN() && s.standardDeviation.isFinite()) {
            r.stdDev = s.standardDeviation
        }
        if (!s.standardError.isNaN() && s.standardError.isFinite()) {
            r.stdErr = s.standardError
        }
        if (!s.halfWidth.isNaN() && s.halfWidth.isFinite()) {
            r.halfWidth = s.halfWidth
        }
        if (!s.confidenceLevel.isNaN() && s.confidenceLevel.isFinite()) {
            r.confLevel = s.confidenceLevel
        }
        if (!s.min.isNaN() && s.min.isFinite()) {
            r.minimum = s.min
        }
        if (!s.max.isNaN() && s.max.isFinite()) {
            r.maximum = s.max
        }
        if (!s.sum.isNaN() && s.sum.isFinite()) {
            r.sumOfObs = s.sum
        }
        if (!s.deviationSumOfSquares.isNaN() && s.deviationSumOfSquares.isFinite()) {
            r.devSsq = s.deviationSumOfSquares
        }
        if (!s.lastValue.isNaN() && s.lastValue.isFinite()) {
            r.lastValue = s.lastValue
        }
        if (!s.kurtosis.isNaN() && s.kurtosis.isFinite()) {
            r.kurtosis = s.kurtosis
        }
        if (!s.skewness.isNaN() && s.skewness.isFinite()) {
            r.skewness = s.skewness
        }
        if (!s.lag1Covariance.isNaN() && s.lag1Covariance.isFinite()) {
            r.lag1Cov = s.lag1Covariance
        }
        if (!s.lag1Correlation.isNaN() && s.lag1Correlation.isFinite()) {
            r.lag1Corr = s.lag1Correlation
        }
        if (!s.vonNeumannLag1TestStatistic.isNaN() && s.vonNeumannLag1TestStatistic.isFinite()) {
            r.vonNeumannLag1Stat = s.vonNeumannLag1TestStatistic
        }
        if (!s.numberMissing.isNaN() && s.numberMissing.isFinite()) {
            r.numMissingObs = s.numberMissing
        }
        return r
    }
    private fun createBatchStatRecord(response: Response, simId: Int, s: BatchStatisticIfc): BatchStatData {
        val r = BatchStatData()
        r.elementIdFk = response.id
        r.simRunIdFk = simId
        r.repId = response.model.currentReplicationId
        r.statName = s.name
        if (!s.count.isNaN() && s.count.isFinite()) {
            r.statCount = s.count
        }
        if (!s.average.isNaN() && s.average.isFinite()) {
            r.average = s.average
        }
        if (!s.standardDeviation.isNaN() && s.standardDeviation.isFinite()) {
            r.stdDev = s.standardDeviation
        }
        if (!s.standardError.isNaN() && s.standardError.isFinite()) {
            r.stdErr = s.standardError
        }
        if (!s.halfWidth.isNaN() && s.halfWidth.isFinite()) {
            r.halfWidth = s.halfWidth
        }
        if (!s.confidenceLevel.isNaN() && s.confidenceLevel.isFinite()) {
            r.confLevel = s.confidenceLevel
        }
        if (!s.min.isNaN() && s.min.isFinite()) {
            r.minimum = s.min
        }
        if (!s.max.isNaN() && s.max.isFinite()) {
            r.maximum = s.max
        }
        if (!s.sum.isNaN() && s.sum.isFinite()) {
            r.sumOfObs = s.sum
        }
        if (!s.deviationSumOfSquares.isNaN() && s.deviationSumOfSquares.isFinite()) {
            r.devSsq = s.deviationSumOfSquares
        }
        if (!s.lastValue.isNaN() && s.lastValue.isFinite()) {
            r.lastValue = s.lastValue
        }
        if (!s.kurtosis.isNaN() && s.kurtosis.isFinite()) {
            r.kurtosis = s.kurtosis
        }
        if (!s.skewness.isNaN() && s.skewness.isFinite()) {
            r.skewness = s.skewness
        }
        if (!s.lag1Covariance.isNaN() && s.lag1Covariance.isFinite()) {
            r.lag1Cov = s.lag1Covariance
        }
        if (!s.lag1Correlation.isNaN() && s.lag1Correlation.isFinite()) {
            r.lag1Corr = s.lag1Correlation
        }
        if (!s.vonNeumannLag1TestStatistic.isNaN() && s.vonNeumannLag1TestStatistic.isFinite()) {
            r.vonNeumannLag1Stat = s.vonNeumannLag1TestStatistic
        }
        if (!s.numberMissing.isNaN() && s.numberMissing.isFinite()) {
            r.numMissingObs = s.numberMissing
        }
        r.minBatchSize = s.minBatchSize.toDouble()
        r.minNumBatches = s.minNumBatches.toDouble()
        r.maxNumBatchesMultiple = s.minNumBatchesMultiple.toDouble()
        r.maxNumBatches = s.maxNumBatches.toDouble()
        r.numRebatches = s.numRebatches.toDouble()
        r.currentBatchSize = s.currentBatchSize.toDouble()
        if (!s.amountLeftUnbatched.isNaN() && s.amountLeftUnbatched.isFinite()) {
            r.amtUnbatched = s.amountLeftUnbatched
        }
        if (!s.totalNumberOfObservations.isNaN() && s.totalNumberOfObservations.isFinite()) {
            r.totalNumObs = s.totalNumberOfObservations
        }
        return r
    }

    private fun insertModelElementRecords(elements: List<ModelElement>) {
        val list = mutableListOf<ModelElementData>()
        for (element in elements) {
            val dbModelElement = createDbModelElement(element, currentExp.expId)
            list.add(dbModelElement)
        }
        db.insertDbDataIntoTable(list, "model_element")
    }

    private fun insertDbControlRecords(controls: List<ControlIfc>) {
        val list = mutableListOf<ControlData>()
        for (c in controls) {
            val cr = createDbControlRecord(c, currentExp.expId)
            list.add(cr)
        }
        db.insertDbDataIntoTable(list, "control")
    }

    private fun insertDbRvParameterRecords(pData: List<RVParameterData>) {
        val list = mutableListOf<RvParameterData>()
        for (param in pData) {
            val r = createDbRvParameterRecord(param, currentExp.expId)
            list.add(r)
        }
        db.insertDbDataIntoTable(list, "rv_parameter")
    }

    private fun insertWithinRepResponses(responses: List<Response>) {
        val list = mutableListOf<WithinRepStatData>()
        for (response in responses) {
            val withinRepStatRecord = createWithinRepStatRecord(response, currentSimRun!!.runId)
            list.add(withinRepStatRecord)
        }
        db.insertDbDataIntoTable(list, "within_rep_stat")
     }

    private fun insertWithinRepCounters(counters: List<Counter>) {
        val list = mutableListOf<WithinRepCounterStatData>()
        for (counter in counters) {
            val withinRepCounterRecord = createWithinRepCounterRecord(counter, currentSimRun!!.runId)
            list.add(withinRepCounterRecord)
        }
        db.insertDbDataIntoTable(list, "within_rep_counter_stat")
     }

    private fun insertAcrossRepResponses(responses: List<Response>) {
        val list = mutableListOf<AcrossRepStatData>()
        for (response in responses) {
            val s = response.acrossReplicationStatistic
            val acrossRepStatRecord = createAcrossRepStatRecord(response, currentSimRun!!.runId, s)
            list.add(acrossRepStatRecord)
        }
        db.insertDbDataIntoTable(list, "across_rep_stat")
    }

    private fun insertResponseVariableBatchStatistics(rMap: Map<Response, BatchStatisticIfc>) {
        val list = mutableListOf<BatchStatData>()
        for (entry in rMap.entries.iterator()) {
            val r = entry.key
            val bs = entry.value
            val batchStatRecord = createBatchStatRecord(r, currentSimRun!!.runId, bs)
            list.add(batchStatRecord)
        }
        db.insertDbDataIntoTable(list, "batch_stat")
    }

    private fun insertTimeWeightedBatchStatistics(twMap: Map<TWResponse, BatchStatisticIfc>) {
        val list = mutableListOf<BatchStatData>()
        for (entry in twMap.entries.iterator()) {
            val tw = entry.key
            val bs = entry.value
            val batchStatRecord = createBatchStatRecord(tw, currentSimRun!!.runId, bs)
            list.add(batchStatRecord)
        }
        db.insertDbDataIntoTable(list, "batch_stat")
    }

    companion object {
        val TableNames = listOf(
            "batch_stat", "within_rep_counter_stat", "across_rep_stat", "within_rep_stat",
            "rv_parameter", "control", "model_element", "simulation_run", "experiment"
        )

        val ViewNames = listOf(
            "within_rep_response_view", "within_rep_counter_view", "within_rep_view", "exp_stat_rep_view",
            "across_rep_view", "batch_stat_view", "pw_diff_within_rep_view"
        )

        private const val SchemaName = "KSL_DB"

        val dbDir: Path = KSL.dbDir
        val dbScriptsDir: Path = KSL.createSubDirectory("dbScript")

        init {
            try {
                val classLoader = this::class.java.classLoader
                val dbCreate = classLoader.getResourceAsStream("KSL_Db.sql")
                val dbDrop = classLoader.getResourceAsStream("KSL_DbDropScript.sql")
                val dbSQLiteCreate = classLoader.getResourceAsStream("KSL_SQLite.sql")
                if (dbCreate != null) {
                    Files.copy(
                        dbCreate, dbScriptsDir.resolve("KSL_Db.sql"),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    DatabaseIfc.logger.trace { "Copied KSL_Db.sql to $dbScriptsDir" }
                }
                if (dbDrop != null) {
                    Files.copy(
                        dbDrop, dbScriptsDir.resolve("KSL_DbDropScript.sql"),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    DatabaseIfc.logger.trace { "Copied KSL_DbDropScript.sql to $dbScriptsDir" }
                }
                if (dbSQLiteCreate != null) {
                    Files.copy(
                        dbSQLiteCreate, dbScriptsDir.resolve("KSL_SQLite.sql"),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                    DatabaseIfc.logger.trace { "Copied KSL_SQLite.sql to $dbScriptsDir" }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        /** This method creates the database on disk and configures it to hold KSL simulation data.
         *
         * @param dbName the name of the database
         * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
         * @return an empty embedded SQLite database configured to hold KSL simulation results
         */
        fun createSQLiteKSLDatabase(dbName: String, dbDirectory: Path = dbDir): Database {
            val database = DatabaseFactory.createSQLiteDatabase(dbName, dbDirectory)
            val executed = database.executeScript(dbScriptsDir.resolve("KSL_SQLite.sql"))
            if (!executed) {
                DatabaseIfc.logger.error("Unable to execute KSL_SQLite.sql creation script")
                throw DataAccessException("The execution script KSL_SQLite.sql did not fully execute")
            }
            return database
        }

        /** This method creates the database on disk and configures it to hold KSL simulation data.
         *
         * @param dbName the name of the database
         * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
         *
         * @return an empty embedded SQLite database configured to hold KSL simulation results
         */
        fun createKSLDatabase(dbName: String, dbDirectory: Path = dbDir): KSLDb {
            val db = createSQLiteKSLDatabase(dbName, dbDirectory)
            return KSLDb(db)
        }

        /** This method creates the database on disk and configures it to hold KSL simulation data.
         *
         * @param dbName the name of the database
         * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
         * @return an empty embedded Derby database configured to hold KSL simulation results
         */
        fun createEmbeddedDerbyKSLDatabase(dbName: String, dbDirectory: Path = dbDir): Database {
            val derbyDatabase = DatabaseFactory.createEmbeddedDerbyDatabase(dbName, dbDirectory)
            executeKSLDbCreationScriptOnDatabase(derbyDatabase)
            derbyDatabase.defaultSchemaName = SchemaName
            return derbyDatabase
        }

        /** Executes the KSL database creation script on the database if the database does not already
         * have a KSL_DB schema. If the database already contains a KSL_DB schema then the creation
         * script is not executed. This method assumes that the underlying data source supports schemas.
         * For example, SQLite does not support schemas.
         *
         * @param db the database
         */
        fun executeKSLDbCreationScriptOnDatabase(db: Database) {
            if (!db.containsSchema(SchemaName)) {
                DatabaseIfc.logger.warn("The database {} does not contain schema {}", db.label, SchemaName)
                try {
                    DatabaseIfc.logger.warn("Assume the schema has not be made and execute the creation script KSL_Db.sql")
                    val executed = db.executeScript(dbScriptsDir.resolve("KSL_Db.sql"))
                    if (!executed) {
                        throw DataAccessException("The execution script KSL_Db.sql did not fully execute")
                    }
                } catch (e: IOException) {
                    DatabaseIfc.logger.error("Unable to execute KSL_Db.sql creation script")
                    throw DataAccessException("Unable to execute KSL_Db.sql creation script")
                }
            }
        }

        /**
         * Creates a new KSLDatabase
         *
         * @param dbServerName    the name of the database server, must not be null
         * @param dbName          the name of the database, must not be null
         * @param user            the user
         * @param pWord           the password
         * @return a reference to a KSLDatabase
         */
        fun createPostgreSQLKSLDatabase(
            dbServerName: String = "localhost",
            dbName: String,
            user: String,
            pWord: String
        ): KSLDb {
            val props: Properties = DatabaseFactory.makePostgreSQLProperties(dbServerName, dbName, user, pWord)
            val db = DatabaseFactory.createDatabaseFromProperties(props)
            db.executeCommand("DROP SCHEMA IF EXISTS ksl_db CASCADE")
            executeKSLDbCreationScriptOnDatabase(db)
            db.defaultSchemaName = "ksl_db"
            return KSLDb(db)
        }

        /**
         * Creates a reference to a KSLDatabase. This method assumes that the data source
         * has an existing properly configured KSL schema. If it does not, an exception occurs. If it has
         * one the data from previous simulations remains. If the clear data option is
         * set to true then the data WILL be deleted immediately.
         *
         * @param clearDataOption whether the data will be deleted when the KSLDatabase instance is created
         * @param dbServerName    the name of the database server, must not be null
         * @param dbName          the name of the database, must not be null
         * @param user            the user
         * @param pWord           the password
         * @return a reference to a KSLDatabase
         */
        fun connectPostgresKSLDatabase(
            clearDataOption: Boolean = false,
            dbServerName: String = "localhost",
            dbName: String,
            user: String,
            pWord: String
        ): KSLDb {
            val props: Properties = DatabaseFactory.makePostgreSQLProperties(dbServerName, dbName, user, pWord)
            val kslDatabase: KSLDb = connectKSLDatabase(clearDataOption, props)
            DatabaseIfc.logger.info("Connected to a postgres KSL database {} ", kslDatabase.db.dbURL)
            return kslDatabase
        }

        /**
         * Creates a reference to a KSLDatabase. This method assumes that the data source
         * has an existing properly configured KSL schema. If it does not, an exception occurs. If it has
         * one the data from previous simulation runs will be deleted if the
         * clear data option is true. The deletion occurs immediately if configured as true.
         *
         * @param clearDataOption whether the data will be cleared of prior experiments when created
         * @param dBProperties    appropriately configured HikariCP datasource properties
         * @return a reference to a KSLDatabase
         */
        fun connectKSLDatabase(
            clearDataOption: Boolean = false,
            dBProperties: Properties,
        ): KSLDb {
            val db: Database = DatabaseFactory.createDatabaseFromProperties(dBProperties)
            return KSLDb(db, clearDataOption)
        }

    }
}

data class ExperimentData(
    var expId: Int = -1,
    var simName: String = "",
    var modelName: String = "",
    var expName: String = "",
    var numReps: Int = -1,
    var isChunked: Boolean = false,
    var lengthOfRep: Double? = null,
    var lengthOfWarmUp: Double? = null,
    var repAllowedExecTime: Long? = null,
    var repInitOption: Boolean = true,
    var resetStartStreamOption: Boolean = false,
    var antitheticOption: Boolean = false,
    var advNextSubStreamOption: Boolean = true,
    var numStreamAdvances: Int = -1,
    var gcAfterRepOption: Boolean = false
) : DbData("experiment", autoIncField = "expId")

data class SimulationRunData(
    var runId: Int = -1,
    var expIdFk: Int = -1,
    var runName: String = "",
    var numReps: Int = -1,
    var startRepId: Int = -1,
    var lastRepId: Int? = null,
    var runStartTimeStamp: LocalDateTime? = null,
    var runEndTimeStamp: LocalDateTime? = null,
    var runErrorMsg: String? = null
) : DbData("simulation_run", autoIncField = "runId")

data class ModelElementData(
    var expIdFk: Int = -1,
    var elementId: Int = -1,
    var elementName: String = "",
    var className: String = "",
    var parentIdFk: Int? = null,
    var parentName: String? = null,
    var leftCount: Int = -1,
    var rightCount: Int = -1
) : DbData("model_element")

data class ControlData(
    var controlId: Int = -1,
    var expIdFk: Int = -1,
    var elementIdFk: Int = -1,
    var keyName: String = "",
    var controlValue: Double = Double.NaN,
    var lowerBound: Double? = null,
    var upperBound: Double? = null,
    var propertyName: String = "",
    var controlType: String = "",
    var comment: String? = null
) : DbData("control", autoIncField = "controlId")

data class RvParameterData(
    var rvParamId: Int = -1,
    var expIdFk: Int = -1,
    var elementIdFk: Int = -1,
    var className: String = "",
    var dataType: String = "",
    var rvName: String = "",
    var paramName: String = "",
    var paramValue: Double = Double.NaN
) : DbData("rv_parameter", autoIncField = "rvParamId")

data class WithinRepStatData(
    var id: Int = -1,
    var elementIdFk: Int = -1,
    var simRunIdFk: Int = -1,
    var repId: Int = -1,
    var statName: String = "",
    var statCount: Double? = null,
    var average: Double? = null,
    var minimum: Double? = null,
    var maximum: Double? = null,
    var weightedSum: Double? = null,
    var sumOfWeights: Double? = null,
    var weightedSsq: Double? = null,
    var lastValue: Double? = null,
    var lastWeight: Double? = null
) : DbData("within_rep_stat", autoIncField = "id")
data class WithinRepCounterStatData(
    var id: Int = -1,
    var elementIdFk: Int = -1,
    var simRunIdFk: Int = -1,
    var repId: Int = -1,
    var statName: String = "",
    var lastValue: Double? = null
) : DbData("within_rep_counter_stat", autoIncField = "id")
data class AcrossRepStatData(
    var id: Int = -1,
    var elementIdFk: Int = -1,
    var simRunIdFk: Int = -1,
    var statName: String = "",
    var statCount: Double? = null,
    var average: Double? = null,
    var stdDev: Double? = null,
    var stdErr: Double? = null,
    var halfWidth: Double? = null,
    var confLevel: Double? = null,
    var minimum: Double? = null,
    var maximum: Double? = null,
    var sumOfObs: Double? = null,
    var devSsq: Double? = null,
    var lastValue: Double? = null,
    var kurtosis: Double? = null,
    var skewness: Double? = null,
    var lag1Cov: Double? = null,
    var lag1Corr: Double? = null,
    var vonNeumannLag1Stat: Double? = null,
    var numMissingObs: Double? = null
) : DbData("across_rep_stat", autoIncField = "id")
data class BatchStatData(
    var id: Int = -1,
    var elementIdFk: Int = -1,
    var simRunIdFk: Int = -1,
    var repId: Int = -1,
    var statName: String = "",
    var statCount: Double? = null,
    var average: Double? = null,
    var stdDev: Double? = null,
    var stdErr: Double? = null,
    var halfWidth: Double? = null,
    var confLevel: Double? = null,
    var minimum: Double? = null,
    var maximum: Double? = null,
    var sumOfObs: Double? = null,
    var devSsq: Double? = null,
    var lastValue: Double? = null,
    var kurtosis: Double? = null,
    var skewness: Double? = null,
    var lag1Cov: Double? = null,
    var lag1Corr: Double? = null,
    var vonNeumannLag1Stat: Double? = null,
    var numMissingObs: Double? = null,
    var minBatchSize: Double? = null,
    var minNumBatches: Double? = null,
    var maxNumBatchesMultiple: Double? = null,
    var maxNumBatches: Double? = null,
    var numRebatches: Double? = null,
    var currentBatchSize: Double? = null,
    var amtUnbatched: Double? = null,
    var totalNumObs: Double? = null
) : DbData("batch_stat", autoIncField = "id")

data class WithinRepResponseViewData(
    var expName: String = "",
    var runName: String = "",
    var numReps: Int = -1,
    var startRepId: Int = -1,
    var lastRepId: Int = -1,
    var statName: String = "",
    var repId: Int = -1,
    var average: Double? = null
) : DbData("within_rep_response_view") //TODO not used?

data class WithinRepCounterViewData(
    var expName: String = "",
    var runName: String = "",
    var numReps: Int = -1,
    var startRepId: Int = -1,
    var lastRepId: Int = -1,
    var statName: String = "",
    var repId: Int = -1,
    var lastValue: Double? = null
) : DbData("within_rep_counter_view")

data class WithinRepViewData(
    var expName: String = "",
    var runName: String = "",
    var numReps: Int = -1,
    var startRepId: Int = -1,
    var lastRepId: Int = -1,
    var statName: String = "",
    var repId: Int = -1,
    var repValue: Double? = null
) : DbData("within_rep_view")

data class ExpStatRepViewData(
    var expName: String = "",
    var statName: String = "",
    var repId: Int = -1,
    var repValue: Double? = null
) : DbData("exp_stat_rep_view")

data class AcrossRepViewData(
    var expName: String = "",
    var statName: String = "",
    var statCount: Double? = null,
    var average: Double? = null,
    var stdDev: Double? = null
) : DbData("across_rep_view")

data class BatchStatViewData(
    var expName: String = "",
    var runName: String = "",
    var repId: Int = -1,
    var statName: String = "",
    var statCount: Double? = null,
    var average: Double? = null,
    var stdDev: Double? = null
) : DbData("batch_stat_view") //TODO not used?

data class PWDiffWithinRepViewData(
    var simName: String = "",
    var statName: String = "",
    var repId: Int = -1,
    var aExpName: String = "",
    var aValue: Double? = null,
    var bExpName: String = "",
    var bValue: Double? = null,
    var diffName: String = "",
    var aMinusB: Double? = null
) : DbData("pw_diff_within_rep_view")