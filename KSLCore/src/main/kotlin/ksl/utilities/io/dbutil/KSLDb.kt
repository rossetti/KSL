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
import ksl.utilities.io.dbutil.ksldbjooq.tables.records.*
import ksl.utilities.io.dbutil.ksldbjooq.tables.references.*
import ksl.utilities.random.rvariable.RVParameterData
import ksl.utilities.random.rvariable.RVParameterSetter
import ksl.utilities.statistic.BatchStatisticIfc
import ksl.utilities.statistic.StatisticIfc
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.move
import org.jetbrains.kotlinx.dataframe.api.remove
import org.jetbrains.kotlinx.dataframe.api.to
import org.jetbrains.kotlinx.dataframe.api.toDataFrame
import org.jooq.DSLContext
import org.jooq.Result
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.ktorm.dsl.like
import org.ktorm.entity.add
import org.ktorm.entity.find
import org.ktorm.entity.toList
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZonedDateTime
import java.util.*

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
        KSLDatabase.createEmbeddedDerbyKSLDatabase(dbName, dbDirectory), clearDataOption
    )

    /**
     *  If true the underlying database was configured as a KSLDatabase
     */
    val configured: Boolean
    private val myDSLContext: DSLContext
    private var currentExpId: Int? = null
    private var currentSimRun: SimulationRunRecord? = null

    init {
        val sqlDialect = JOOQ.getSQLDialect(db.dataSource)
        if (sqlDialect == SQLDialect.DEFAULT) {
            DatabaseIfc.logger.error { "The SQLDialect of database ${db.label} could not be determined." }
            throw KSLDatabaseNotConfigured("The SQLDialect of database ${db.label} could not be determined.")
        }
        configured = checkTableNames()
        if (!configured) {
            DatabaseIfc.logger.error { "The database does not have the required tables for a KSLDatabase" }
            throw KSLDatabaseNotConfigured("The database does not have the required tables for a KSLDatabase")
        }
        if (clearDataOption) {
            clearAllData()
        }
        myDSLContext = DSL.using(db.dataSource, sqlDialect)
        myDSLContext.settings().withExecuteLogging(false)
    }

//    private val dbExperiments: List<ExperimentData>
//        get() = myDSLContext.selectFrom(EXPERIMENT).fetch().into(ExperimentData::class.java)

    /**
     * Returns the names of the experiments in the EXPERIMENT table.
     */
    val experimentNames: List<String>
        get() {
            val names: MutableList<String?> = myDSLContext.select(EXPERIMENT.EXP_NAME).from(EXPERIMENT).fetch(EXPERIMENT.EXP_NAME)
            return names.filterNotNull()
        }

    val withinRepResponseViewStatistics: DataFrame<WithinRepResponseViewRecord>
        get() {
            val records: Result<WithinRepResponseViewRecord> = myDSLContext.selectFrom(WITHIN_REP_RESPONSE_VIEW).fetch()
            var df: DataFrame<WithinRepResponseViewRecord> = records.toDataFrame()
            df = df.move("expName").to(0)
                .move("runName").to(1)
                .move("expNumReps").to(2)
                .move("startRepId").to(3)
                .move("lastRepId").to(4)
                .move("statName").to(5)
                .move("repId").to(6)
                .move("average").to(7)
 //           df = df.remove("entityClass", "properties")
            return df
        }

    val withinRepCounterViewStatistics: DataFrame<WithinRepCounterViewRecord>
        get() {
            val records: Result<WithinRepCounterViewRecord> = myDSLContext.selectFrom(WITHIN_REP_COUNTER_VIEW).fetch()
            var df: DataFrame<WithinRepCounterViewRecord> = records.toDataFrame()
            df = df.move("expName").to(0)
                .move("runName").to(1)
                .move("expNumReps").to(2)
                .move("startRepId").to(3)
                .move("lastRepId").to(4)
                .move("statName").to(5)
                .move("repId").to(6)
                .move("lastValue").to(7)
 //           df = df.remove("entityClass", "properties")
            return df
        }

    val withinRepViewStatistics: DataFrame<WithinRepViewRecord>
        get() {
            val records: Result<WithinRepViewRecord> = myDSLContext.selectFrom(WITHIN_REP_VIEW).fetch()
            var df: DataFrame<WithinRepViewRecord> = records.toDataFrame()
            df = df.move("expName").to(0)
                .move("runName").to(1)
                .move("expNumReps").to(2)
                .move("startRepId").to(3)
                .move("lastRepId").to(4)
                .move("statName").to(5)
                .move("repId").to(6)
                .move("repValue").to(7)
 //           df = df.remove("entityClass", "properties")
            return df
        }

    val acrossReplicationStatistics: DataFrame<AcrossRepStatRecord>
        get() {
            val records: Result<AcrossRepStatRecord> = myDSLContext.selectFrom(ACROSS_REP_STAT).fetch()
            var df: DataFrame<AcrossRepStatRecord> = records.toDataFrame()
            df = df.move("simRunIdFk").to(0)
                .move("id").to(1)
                .move("statName").to(2)
                .move("statCount").to(3)
                .move("average").to(4)
                .move("halfWidth").to(5)
                .move("stdDev").to(6)
                .move("stdErr").to(7)
                .move("minimum").to(8)
                .move("maximum").to(9)
                .move("confLevel").to(10)
//            df = df.remove("entityClass", "properties", "elementIdFk")
            return df
        }


    val withinReplicationResponseStatistics: DataFrame<WithinRepStatRecord>
        get() {
            val records: Result<WithinRepStatRecord> = myDSLContext.selectFrom(WITHIN_REP_STAT).fetch()
            var df: DataFrame<WithinRepStatRecord> = records.toDataFrame()
            df = df.move("simRunIdFk").to(0)
                .move("id").to(1)
                .move("statName").to(2)
                .move("repId").to(3)
                .move("statCount").to(4)
                .move("average").to(5)
                .move("minimum").to(6)
                .move("maximum").to(7)
 //               .remove("entityClass", "properties", "elementIdFk")
            return df
        }
    val withinReplicationCounterStatistics: DataFrame<WithinRepCounterStatRecord>
        get() {
            val records: Result<WithinRepCounterStatRecord> = myDSLContext.selectFrom(WITHIN_REP_COUNTER_STAT).fetch()
            var df: DataFrame<WithinRepCounterStatRecord> = records.toDataFrame()
            df = df.move("simRunIdFk").to(0)
                .move("id").to(1)
                .move("statName").to(2)
                .move("repId").to(3)
                .move("lastValue").to(4)
 //               .remove("entityClass", "properties", "elementIdFk")
            return df
        }

    val batchingStatistics: DataFrame<BatchStatRecord>
        get() {
            val records: Result<BatchStatRecord> = myDSLContext.selectFrom(BATCH_STAT).fetch()
            var df: DataFrame<BatchStatRecord> = records.toDataFrame()
            df = df.move("simRunIdFk").to(0)
                .move("id").to(1)
                .move("statName").to(2)
                .move("repId").to(3)
                .move("statCount").to(4)
                .move("average").to(5)
                .move("halfWidth").to(6)
                .move("stdDev").to(7)
                .move("stdError").to(8)
                .move("minimum").to(9)
                .move("maximum").to(10)
                .move("confLevel").to(11)
//            df = df.remove("entityClass", "properties", "elementIdFk")
            return df
        }

    val expStatRepViewStatistics: DataFrame<ExpStatRepViewRecord>
        get() {
            val records: Result<ExpStatRepViewRecord> = myDSLContext.selectFrom(EXP_STAT_REP_VIEW).fetch()
            var df: DataFrame<ExpStatRepViewRecord> = records.toDataFrame()
            df = df.move("expName").to(0)
                .move("statName").to(1)
                .move("repId").to(2)
                .move("repValue").to(3)
//            df = df.remove("entityClass", "properties")
            return df
        }

    val pairWiseDiffViewStatistics: DataFrame<PwDiffWithinRepViewRecord>
        get() {
            val records: Result<PwDiffWithinRepViewRecord> = myDSLContext.selectFrom(PW_DIFF_WITHIN_REP_VIEW).fetch()
            var df: DataFrame<PwDiffWithinRepViewRecord> = records.toDataFrame()
            df = df.move("simName").to(0)
                .move("statName").to(1)
                .move("repId").to(2)
                .move("expNameA").to(3)
                .move("valueA").to(4)
                .move("expNameB").to(5)
                .move("valueB").to(6)
                .move("diffName").to(7)
                .move("AminusB").to(8)
 //           df = df.remove("entityClass", "properties")
            return df
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
        deleteExperimentRecord(expName)
    }

    /**
     * The expName should be unique within the database. Many
     * experiments can be run with different names for the same simulation. This method
     * deletes the experiment record with the provided name AND all related data
     * associated with that experiment.  If an experiment record does not
     * exist with the expName, nothing occurs.
     *
     * @param expName the experiment name for the simulation
     * @return true if the record was deleted, false if it was not
     */
    fun deleteExperimentRecord(expName: String): Boolean {
        val experimentRecord: ExperimentRecord? =
            myDSLContext.selectFrom(EXPERIMENT).where(EXPERIMENT.EXP_NAME.eq(expName)).fetchOne()
        if (experimentRecord != null) {
            DatabaseIfc.logger.trace { "Deleting Experiment, $expName, for simulation ${experimentRecord.simName}." }
            val result = experimentRecord.delete()
            return result == 1
        }
        return false
    }

    /**
     *  Checks if the supplied experiment name exists within the database.
     *  Experiment names should be unique within the database
     *  @param expName the name to check
     *  @return true if found
     */
    fun doesExperimentRecordExist(expName: String): Boolean {
        val experimentRecord: ExperimentRecord? =
            myDSLContext.selectFrom(EXPERIMENT).where(EXPERIMENT.EXP_NAME.eq(expName)).fetchOne()
        return experimentRecord != null
    }

    internal fun beforeExperiment(model: Model) {
        var experimentRecord: ExperimentRecord? =
            myDSLContext.selectFrom(EXPERIMENT).where(EXPERIMENT.EXP_NAME.eq(model.experimentName)).fetchOne()
        if (experimentRecord != null) {
            // experiment record exists, this must be a simulation run related to a chunk
            if (model.isChunked) {
                // run is a chunk, make sure it is not an existing simulation run
                // just assume user wants to write over any existing chunks with the same name
                myDSLContext.deleteFrom(SIMULATION_RUN).where(SIMULATION_RUN.RUN_NAME.eq(model.runName)).execute()
                currentExpId = experimentRecord.expId!!
            } else {
                // not a chunk, same experiment but not chunked, this is a potential user error
                reportExistingExperimentRecordError(model)
            }
        } else {
            // experiment record does not exist, create it, remember it, and insert it
            experimentRecord = createExperimentRecord(model)
            experimentRecord.store()
        }
        // start simulation run record
        insertSimulationRun(model)
        // insert the model elements into the database
        val modelElements: List<ModelElement> = model.getModelElements()
        insertModelElements(modelElements)
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

    private fun createExperimentRecord(model: Model): ExperimentRecord {
        val record = myDSLContext.newRecord(EXPERIMENT)
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

    private fun insertSimulationRun(model: Model) {
        val record: SimulationRunRecord = myDSLContext.newRecord(SIMULATION_RUN)
        record.expIdFk = currentExpId
        record.numReps = model.numberOfReplications
        record.runName = model.runName
        record.startRepId = model.startingRepId
        record.runStartTimeStamp = ZonedDateTime.now().toLocalDateTime()
        record.store()
        currentSimRun = record
    }

    private fun finalizeCurrentSimulationRun(model: Model) {
        currentSimRun?.lastRepId = model.startingRepId + model.numberReplicationsCompleted - 1
        currentSimRun?.runEndTimeStamp = ZonedDateTime.now().toLocalDateTime()
        currentSimRun?.runErrorMsg = model.runErrorMsg
        currentSimRun?.store()
        DatabaseIfc.logger.trace { "Finalized SimulationRun record for simulation: ${model.simulationName}" }
    }

    private fun insertModelElements(elements: List<ModelElement>) {
        // it would be nice to know how to make a batch insert rather each individually
        for (element in elements) {
            val dbModelElement = createDbModelElement(element, currentExpId!!)
            dbModelElement.store()
        }
        DatabaseIfc.logger.trace { "Inserted model element records into ${db.label} for ${currentSimRun?.runName}" }
    }

    private fun createDbModelElement(element: ModelElement, expId: Int): ModelElementRecord {
        val dbm = myDSLContext.newRecord(MODEL_ELEMENT)
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

    private fun insertDbControlRecords(controls: List<ControlIfc>) {
        for (c in controls) {
            val cr = createDbControlRecord(c, currentExpId!!)
            cr.store()
        }
    }

    private fun createDbControlRecord(control: ControlIfc, expId: Int): ControlRecord {
        val c = myDSLContext.newRecord(CONTROL)
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

    private fun insertDbRvParameterRecords(pData: List<RVParameterData>) {
        for (param in pData) {
            val r = createDbRvParameterRecord(param, currentExpId!!)
            r.store()
        }
    }

    private fun createDbRvParameterRecord(rvParamData: RVParameterData, expId: Int): RvParameterRecord {
        val rvp = myDSLContext.newRecord(RV_PARAMETER)
        rvp.expIdFk = expId
        rvp.elementIdFk = rvParamData.elementId
        rvp.className = rvParamData.clazzName
        rvp.dataType = rvParamData.dataType
        rvp.rvName = rvParamData.rvName
        rvp.paramName = rvParamData.paramName
        rvp.paramValue = rvParamData.paramValue
        return rvp
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

    private fun insertWithinRepResponses(responses: List<Response>) {
        for (response in responses) {
            val withinRepStatRecord = createWithinRepStatRecord(response, currentSimRun!!.runId!!)
            withinRepStatRecord.store()
        }
        DatabaseIfc.logger.trace { "Inserted within replication responses into ${db.label} for simulation ${currentSimRun?.runName}" }
    }

    private fun createWithinRepStatRecord(response: Response, simId: Int): WithinRepStatRecord {
        val r = myDSLContext.newRecord(WITHIN_REP_STAT)
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

    private fun insertWithinRepCounters(counters: List<Counter>) {
        for (counter in counters) {
            val withinRepCounterRecord = createWithinRepCounterRecord(counter, currentSimRun!!.runId!!)
            withinRepCounterRecord.store()
        }
        DatabaseIfc.logger.trace { "Inserted within replication counters into ${db.label} for simulation ${currentSimRun?.runName}" }
    }

    private fun createWithinRepCounterRecord(counter: Counter, simId: Int): WithinRepCounterStatRecord {
        val r = myDSLContext.newRecord(WITHIN_REP_COUNTER_STAT)
        r.elementIdFk = counter.id
        r.simRunIdFk = simId
        r.repId = counter.model.currentReplicationId
        r.statName = counter.name
        if (!counter.value.isNaN() && counter.value.isFinite()) {
            r.lastValue = counter.value
        }
        return r
    }

    private fun insertResponseVariableBatchStatistics(rMap: Map<Response, BatchStatisticIfc>) {
        for (entry in rMap.entries.iterator()) {
            val r = entry.key
            val bs = entry.value
            val batchStatRecord = createBatchStatRecord(r, currentSimRun!!.runId!!, bs)
            batchStatRecord.store()
        }
        DatabaseIfc.logger.trace { "Inserted within response batch statistics into ${db.label} for simulation ${currentSimRun?.runName}" }
    }

    private fun insertTimeWeightedBatchStatistics(twMap: Map<TWResponse, BatchStatisticIfc>) {
        for (entry in twMap.entries.iterator()) {
            val tw = entry.key
            val bs = entry.value
            val batchStatRecord = createBatchStatRecord(tw, currentSimRun!!.runId!!, bs)
            batchStatRecord.store()
        }
        DatabaseIfc.logger.trace { "Inserted within time-weighted batch statistics into ${db.label} for simulation ${currentSimRun?.runName}" }
    }

    private fun createBatchStatRecord(response: Response, simId: Int, s: BatchStatisticIfc): BatchStatRecord {
        val r = myDSLContext.newRecord(BATCH_STAT)
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


    private fun insertAcrossRepResponses(responses: List<Response>) {
        for (response in responses) {
            val s = response.acrossReplicationStatistic
            val acrossRepStatRecord = createAcrossRepStatRecord(response, currentSimRun!!.runId!!, s)
            acrossRepStatRecord.store()
        }
        DatabaseIfc.logger.trace { "Inserted within across replication statistics into ${db.label} for simulation ${currentSimRun?.runName}" }
    }

    private fun createAcrossRepStatRecord(response: ModelElement, simId: Int, s: StatisticIfc): AcrossRepStatRecord {
        val r = myDSLContext.newRecord(ACROSS_REP_STAT)
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

    private fun insertAcrossRepResponsesForCounters(counters: List<Counter>) {
        for (counter in counters) {
            val s = counter.acrossReplicationStatistic
            val acrossRepCounterRecord = createAcrossRepStatRecord(counter, currentSimRun!!.runId!!, s)
            acrossRepCounterRecord.store()
        }
        DatabaseIfc.logger.trace { "Inserted within across replication counter statistics into ${db.label} for simulation ${currentSimRun?.runName}" }
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

        var jooqExecutionLogging = false

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
    var expId: Int,
    var simName: String,
    var modelName: String,
    var expName: String,
    var numReps: Int,
    var isChunked: Boolean,
    var lengthOfRep: Double?,
    var lengthOfWarmUp: Double?,
    var repAllowedExecTime: Long?,
    var repInitOption: Boolean,
    var resetStartStreamOption: Boolean,
    var antitheticOption: Boolean,
    var advNextSubStreamOption: Boolean,
    var numStreamAdvances: Int,
    var gcAfterRepOption: Boolean
)