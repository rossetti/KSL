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
import ksl.utilities.statistic.MultipleComparisonAnalyzer
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

class KSLDatabase(private val db: Database, clearDataOption: Boolean = false) : DatabaseIOIfc by db {

    //TODO testing

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

    private lateinit var currentExp: ExperimentTableData
    private var currentSimRun: SimulationRunTableData? = null

    /**
     * Returns the names of the experiments in the EXPERIMENT table.
     */
    val experimentNames: List<String>
        get() {
            val list = mutableSetOf<String>()
            val data: List<ExperimentTableData> = db.selectTableDataIntoDbData(::ExperimentTableData)
            for (d in data) {
                list.add(d.expName)
            }
            return list.toList()
        }

    /**
     *  Retrieves the data for the named experiment or null if an experiment
     *  with the provided [expName] name is not found in the database
     */
    fun fetchExperimentData(expName: String): ExperimentTableData? {
        val data: List<ExperimentTableData> = db.selectTableDataIntoDbData(::ExperimentTableData)
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
            DatabaseIfc.logger.warn { "There was an SQLException when trying to delete experiment $expName" }
            DatabaseIfc.logger.warn { "SQLException: $e" }
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
            DatabaseIfc.logger.warn { "There was an SQLException when trying to delete simulation run: $runName" }
            DatabaseIfc.logger.warn { "SQLException: $e" }
            return false
        }
    }

    val withinRepResponseViewStatistics: DataFrame<WithinRepResponseViewData>
        get() = db.selectTableDataIntoDbData(::WithinRepResponseViewData).toDataFrame()

    val withinRepCounterViewStatistics: DataFrame<WithinRepCounterViewData>
        get() = db.selectTableDataIntoDbData(::WithinRepCounterViewData).toDataFrame()

    val withinRepViewStatistics: DataFrame<WithinRepViewData>
        get() = db.selectTableDataIntoDbData(::WithinRepViewData).toDataFrame()

    val acrossReplicationStatistics: DataFrame<AcrossRepViewData>
        get() = db.selectTableDataIntoDbData(::AcrossRepViewData).toDataFrame()

    val withinReplicationResponseStatistics: DataFrame<WithinRepStatTableData>
        get() = db.selectTableDataIntoDbData(::WithinRepStatTableData).toDataFrame()

    val withinReplicationCounterStatistics: DataFrame<WithinRepCounterStatTableData>
        get() = db.selectTableDataIntoDbData(::WithinRepCounterStatTableData).toDataFrame()

    val batchingStatistics: DataFrame<BatchStatTableData>
        get() = db.selectTableDataIntoDbData(::BatchStatTableData).toDataFrame()

    val batchStatViewStatistics: DataFrame<BatchStatViewData>
        get() = db.selectTableDataIntoDbData(::BatchStatViewData).toDataFrame()
    val expStatRepViewStatistics: DataFrame<ExpStatRepViewData>
        get() = db.selectTableDataIntoDbData(::ExpStatRepViewData).toDataFrame()

    val pairWiseDiffViewStatistics: DataFrame<PWDiffWithinRepViewData>
        get() = db.selectTableDataIntoDbData(::PWDiffWithinRepViewData).toDataFrame()

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
        currentSimRun = createSimulationRunData(model)
        db.insertDbDataIntoTable(currentSimRun!!)
        // insert the model elements into the database
        val modelElements: List<ModelElement> = model.getModelElements()
        insertModelElementRecords(modelElements)
        if (model.hasExperimentalControls()) {
            // insert controls if they are there
            val controls: Controls = model.controls()
            insertDbControlRecords(controls.asList())
        }
        if (model.hasParameterSetter()) {
            // insert the random variable parameters
            val ps: RVParameterSetter = model.rvParameterSetter
            insertDbRvParameterRecords(ps.rvParametersData)
        }
    }

    internal fun afterReplication(model: Model) {
        // insert the within replication statistics
        insertWithinRepResponses(model.responses)
        // insert the within replication counters
        insertWithinRepCounters(model.counters)
        // insert the batch statistics if available
        if (model.batchingElement != null) {
            val rMap = model.batchingElement!!.allResponseBatchStatisticsAsMap
            val twMap = model.batchingElement!!.allTimeWeightedBatchStatisticsAsMap
            insertResponseVariableBatchStatistics(rMap)
            insertTimeWeightedBatchStatistics(twMap)
        }
    }

    internal fun afterExperiment(model: Model) {
        // finalize current simulation run record
        finalizeCurrentSimulationRun(model)
        // insert across replication response statistics
        insertAcrossRepResponses(model.responses)
        // insert across replication counter statistics
        insertAcrossRepResponsesForCounters(model.counters)
    }

    private fun finalizeCurrentSimulationRun(model: Model) {
        currentSimRun?.lastRepId = model.startingRepId + model.numberReplicationsCompleted - 1
        currentSimRun?.runEndTimeStamp = ZonedDateTime.now().toLocalDateTime()
        currentSimRun?.runErrorMsg = model.runErrorMsg
        db.updateDbDataInTable(currentSimRun!!)
        DatabaseIfc.logger.trace { "Finalized SimulationRun record for simulation: ${model.simulationName}" }
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

    private fun createExperimentData(model: Model): ExperimentTableData {
        val record = ExperimentTableData()
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

    private fun createSimulationRunData(model: Model): SimulationRunTableData {
        val record = SimulationRunTableData()
        record.expIdFk = currentExp.expId
        record.numReps = model.numberOfReplications
        record.runName = model.runName
        record.startRepId = model.startingRepId
        record.runStartTimeStamp = ZonedDateTime.now().toLocalDateTime()
        return record
    }

    private fun createDbModelElement(element: ModelElement, expId: Int): ModelElementTableData {
        val dbm = ModelElementTableData()
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

    private fun createDbControlRecord(control: ControlIfc, expId: Int): ControlTableData {
        val c = ControlTableData()
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

    private fun createDbRvParameterRecord(rvParamData: RVParameterData, expId: Int): RvParameterTableData {
        val rvp = RvParameterTableData()
        rvp.expIdFk = expId
        rvp.elementIdFk = rvParamData.elementId
        rvp.className = rvParamData.clazzName
        rvp.dataType = rvParamData.dataType
        rvp.rvName = rvParamData.rvName
        rvp.paramName = rvParamData.paramName
        rvp.paramValue = rvParamData.paramValue
        return rvp
    }

    private fun createWithinRepStatRecord(response: Response, simId: Int): WithinRepStatTableData {
        val r = WithinRepStatTableData()
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

    private fun createWithinRepCounterRecord(counter: Counter, simId: Int): WithinRepCounterStatTableData {
        val r = WithinRepCounterStatTableData()
        r.elementIdFk = counter.id
        r.simRunIdFk = simId
        r.repId = counter.model.currentReplicationId
        r.statName = counter.name
        if (!counter.value.isNaN() && counter.value.isFinite()) {
            r.lastValue = counter.value
        }
        return r
    }

    private fun createAcrossRepStatRecord(response: ModelElement, simId: Int, s: StatisticIfc): AcrossRepStatTableData {
        val r = AcrossRepStatTableData()
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

    private fun createBatchStatRecord(response: Response, simId: Int, s: BatchStatisticIfc): BatchStatTableData {
        val r = BatchStatTableData()
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
        val list = mutableListOf<ModelElementTableData>()
        for (element in elements) {
            val dbModelElement = createDbModelElement(element, currentExp.expId)
            list.add(dbModelElement)
        }
        db.insertDbDataIntoTable(list, "model_element")
    }

    private fun insertDbControlRecords(controls: List<ControlIfc>) {
        val list = mutableListOf<ControlTableData>()
        for (c in controls) {
            val cr = createDbControlRecord(c, currentExp.expId)
            list.add(cr)
        }
        db.insertDbDataIntoTable(list, "control")
    }

    private fun insertDbRvParameterRecords(pData: List<RVParameterData>) {
        val list = mutableListOf<RvParameterTableData>()
        for (param in pData) {
            val r = createDbRvParameterRecord(param, currentExp.expId)
            list.add(r)
        }
        db.insertDbDataIntoTable(list, "rv_parameter")
    }

    private fun insertWithinRepResponses(responses: List<Response>) {
        val list = mutableListOf<WithinRepStatTableData>()
        for (response in responses) {
            val withinRepStatRecord = createWithinRepStatRecord(response, currentSimRun!!.runId)
            list.add(withinRepStatRecord)
        }
        db.insertDbDataIntoTable(list, "within_rep_stat")
    }

    private fun insertWithinRepCounters(counters: List<Counter>) {
        val list = mutableListOf<WithinRepCounterStatTableData>()
        for (counter in counters) {
            val withinRepCounterRecord = createWithinRepCounterRecord(counter, currentSimRun!!.runId)
            list.add(withinRepCounterRecord)
        }
        db.insertDbDataIntoTable(list, "within_rep_counter_stat")
    }

    private fun insertAcrossRepResponses(responses: List<Response>) {
        val list = mutableListOf<AcrossRepStatTableData>()
        for (response in responses) {
            val s = response.acrossReplicationStatistic
            val acrossRepStatRecord = createAcrossRepStatRecord(response, currentSimRun!!.runId, s)
            list.add(acrossRepStatRecord)
        }
        db.insertDbDataIntoTable(list, "across_rep_stat")
    }

    private fun insertAcrossRepResponsesForCounters(counters: List<Counter>) {
        val list = mutableListOf<AcrossRepStatTableData>()
        for (counter in counters) {
            val s = counter.acrossReplicationStatistic
            val acrossRepCounterRecord = createAcrossRepStatRecord(counter, currentSimRun!!.runId, s)
            list.add(acrossRepCounterRecord)
        }
        db.insertDbDataIntoTable(list, "across_rep_stat")
    }
    private fun insertResponseVariableBatchStatistics(rMap: Map<Response, BatchStatisticIfc>) {
        val list = mutableListOf<BatchStatTableData>()
        for (entry in rMap.entries.iterator()) {
            val r = entry.key
            val bs = entry.value
            val batchStatRecord = createBatchStatRecord(r, currentSimRun!!.runId, bs)
            list.add(batchStatRecord)
        }
        db.insertDbDataIntoTable(list, "batch_stat")
    }

    private fun insertTimeWeightedBatchStatistics(twMap: Map<TWResponse, BatchStatisticIfc>) {
        val list = mutableListOf<BatchStatTableData>()
        for (entry in twMap.entries.iterator()) {
            val tw = entry.key
            val bs = entry.value
            val batchStatRecord = createBatchStatRecord(tw, currentSimRun!!.runId, bs)
            list.add(batchStatRecord)
        }
        db.insertDbDataIntoTable(list, "batch_stat")
    }

    /**
     * Returns the observations for the named experiment and the named statistical response
     * from within
     */
    private fun withinReplicationObservationsFor(expNameStr: String, statNameStr: String): DoubleArray {
        var df = withinRepViewStatistics
        val expName by column<String>()
        val statName by column<String>()
        val repValue by column<Double>()
        val repId by column<Double>()
        df = df.filter { expName() == expNameStr && statName() == statNameStr }
        df = df.select(repId, repValue).sortBy(repValue).select(repValue)
        val values = df.values()
        val result = DoubleArray(values.count())
        for ((index, v) in values.withIndex()) {
            result[index] = v as Double
        }
        return result
    }

    /**
     * This prepares a map that can be used with MultipleComparisonAnalyzer. If the set of
     * simulation runs does not contain the provided experiment name, then an IllegalArgumentException
     * occurs.  If there are multiple simulation runs with the same experiment name, then
     * an IllegalArgumentException occurs. In other words, when running the experiments, the user
     * must make the experiment names unique in order for this map to be built.
     *
     * @param expNames     The set of experiment names for with the responses need extraction, must not
     *                     be null
     * @param responseName the name of the response variable, time weighted variable or counter
     * @return a map with key exp_name containing an array of values, each value from each replication
     */
    fun withinReplicationViewMapForExperiments(expNames: List<String>, responseName: String): Map<String, DoubleArray> {
        require(expNames.isNotEmpty()){"The list of experiment names was empty"}
        val eNames = experimentNames
        for (name in expNames) {
            if (!eNames.contains(name)) {
                DatabaseIfc.logger.error { "There were no simulation runs with the experiment name $name" }
                throw IllegalArgumentException("There were no simulation runs with the experiment name $name")
            }
        }
        val theMap = mutableMapOf<String, DoubleArray>()
        for (name in expNames) {
            theMap[name] = withinReplicationObservationsFor(name, responseName)
        }
        return theMap
    }

    /**
     * This prepares a map that can be used with MultipleComparisonAnalyzer and
     * returns the MultipleComparisonAnalyzer. If the set of
     * simulation runs does not contain the provided experiment name, then an IllegalArgumentException
     * occurs.  If there are multiple simulation runs with the same experiment name, then
     * an IllegalArgumentException occurs. In other words, when running the experiments, the user
     * must make the experiment names unique in order for this map to be built.
     *
     * @param expNames     The set of experiment names for with the responses need extraction, must not be null
     * @param responseName the name of the response variable, time weighted variable or counter
     * @return a configured MultipleComparisonAnalyzer
     */
    fun multipleComparisonAnalyzerFor(expNames: List<String>, responseName: String): MultipleComparisonAnalyzer {
        val map = withinReplicationViewMapForExperiments(expNames, responseName)
        val mca = MultipleComparisonAnalyzer(map)
        mca.name = responseName
        return mca
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
        fun createKSLDatabase(dbName: String, dbDirectory: Path = dbDir): KSLDatabase {
            val db = createSQLiteKSLDatabase(dbName, dbDirectory)
            return KSLDatabase(db)
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
        ): KSLDatabase {
            val props: Properties = DatabaseFactory.makePostgreSQLProperties(dbServerName, dbName, user, pWord)
            val db = DatabaseFactory.createDatabaseFromProperties(props)
            db.executeCommand("DROP SCHEMA IF EXISTS ksl_db CASCADE")
            executeKSLDbCreationScriptOnDatabase(db)
            db.defaultSchemaName = "ksl_db"
            return KSLDatabase(db)
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
        ): KSLDatabase {
            val props: Properties = DatabaseFactory.makePostgreSQLProperties(dbServerName, dbName, user, pWord)
            val kslDatabase: KSLDatabase = connectKSLDatabase(clearDataOption, props)
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
        ): KSLDatabase {
            val db: Database = DatabaseFactory.createDatabaseFromProperties(dBProperties)
            return KSLDatabase(db, clearDataOption)
        }

    }
}

data class ExperimentTableData(
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
) : DbTableData("experiment", keyFields = listOf("expId"), autoIncField = true)

data class SimulationRunTableData(
    var runId: Int = -1,
    var expIdFk: Int = -1,
    var runName: String = "",
    var numReps: Int = -1,
    var startRepId: Int = -1,
    var lastRepId: Int? = null,
    var runStartTimeStamp: LocalDateTime? = null,
    var runEndTimeStamp: LocalDateTime? = null,
    var runErrorMsg: String? = null
) : DbTableData("simulation_run", keyFields = listOf("runId"), autoIncField = true)

data class ModelElementTableData(
    var expIdFk: Int = -1,
    var elementId: Int = -1,
    var elementName: String = "",
    var className: String = "",
    var parentIdFk: Int? = null,
    var parentName: String? = null,
    var leftCount: Int = -1,
    var rightCount: Int = -1
) : DbTableData("model_element", keyFields = listOf("expIdFk", "elementId"))

data class ControlTableData(
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
) : DbTableData("control", keyFields = listOf("controlId"), autoIncField = true)

data class RvParameterTableData(
    var rvParamId: Int = -1,
    var expIdFk: Int = -1,
    var elementIdFk: Int = -1,
    var className: String = "",
    var dataType: String = "",
    var rvName: String = "",
    var paramName: String = "",
    var paramValue: Double = Double.NaN
) : DbTableData("rv_parameter", keyFields = listOf("rvParamId"), autoIncField = true)

data class WithinRepStatTableData(
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
) : DbTableData("within_rep_stat", keyFields = listOf("id"), autoIncField = true)

data class WithinRepCounterStatTableData(
    var id: Int = -1,
    var elementIdFk: Int = -1,
    var simRunIdFk: Int = -1,
    var repId: Int = -1,
    var statName: String = "",
    var lastValue: Double? = null
) : DbTableData("within_rep_counter_stat", keyFields = listOf("id"), autoIncField = true)

data class AcrossRepStatTableData(
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
) : DbTableData("across_rep_stat", keyFields = listOf("id"), autoIncField = true)

data class BatchStatTableData(
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
) : DbTableData("batch_stat", keyFields = listOf("id"), autoIncField = true)

data class WithinRepResponseViewData(
    var expName: String = "",
    var runName: String = "",
    var numReps: Int = -1,
    var startRepId: Int = -1,
    var lastRepId: Int = -1,
    var statName: String = "",
    var repId: Int = -1,
    var average: Double? = null
) : DbData("within_rep_response_view")

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
) : DbData("batch_stat_view")

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