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

import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import ksl.controls.ControlIfc
import ksl.controls.Controls
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.parameters.RVParameterData
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import ksl.utilities.statistic.*
import org.jetbrains.kotlinx.dataframe.AnyFrame
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.*
import java.sql.SQLException
import java.sql.Timestamp

class KSLDatabase(private val db: Database, clearDataOption: Boolean = false) : DatabaseIOIfc by db {

    /** This constructs a SQLite database on disk and configures it to hold KSL simulation data.
     * The database will be empty.
     *
     * @param dbName the name of the database
     * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
     * @param clearDataOption indicates if the data should be cleared. The default is true.
     * @return an empty embedded SQLite database configured to hold KSL simulation results
     */
    constructor(dbName: String, dbDirectory: Path = dbDir, clearDataOption: Boolean = true) : this(
        createSQLiteKSLDatabase(dbName, dbDirectory), clearDataOption
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
        DatabaseIfc.logger.info { "KSLDatabase ${db.label} at initialization: Clear data option = $clearDataOption" }
        if (clearDataOption) {
            DatabaseIfc.logger.info { "Started clearing all data for KSLDatabase ${db.label} at initialization." }
            clearAllData()
            DatabaseIfc.logger.info { "Completed clearing all data for KSLDatabase ${db.label} at initialization." }
        }
    }

    /**
     * Clears all data from user defined tables
     */
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
        for (name in TableNames) {
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
                list.add(d.exp_name)
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
            if (d.exp_name == expName) {
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
     */
    fun deleteExperimentWithName(expName: String) {
        try {
            DatabaseIfc.logger.trace { "Getting a connection to delete experiment $expName in database: $label" }
            db.getConnection().use { connection ->
                val ps = db.makeDeleteFromPreparedStatement(connection, "experiment", "exp_name", defaultSchemaName)
                ps.setString(1, expName)
                ps.execute()
                //val deleted = ps.execute()
//                if (deleted) {
//                    DatabaseIfc.logger.info { "Deleted Experiment, $expName, for simulation." }
//                } else {
//                    DatabaseIfc.logger.info { "PreparedStatement: Experiment, $expName, was not deleted." }
//                }
//                return deleted
            }
        } catch (e: SQLException) {
            DatabaseIfc.logger.warn { "There was an SQLException when trying to delete experiment $expName" }
            DatabaseIfc.logger.warn { "SQLException: $e" }
            // return false
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
                var sql = DatabaseIfc.deleteFromTableWhereSQL("simulation_run", "run_name", defaultSchemaName)
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
        get() {
            var df = db.selectTableDataIntoDbData(::WithinRepResponseViewData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    val withinRepCounterViewStatistics: DataFrame<WithinRepCounterViewData>
        get() {
            var df = db.selectTableDataIntoDbData(::WithinRepCounterViewData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    val withinRepViewStatistics: DataFrame<WithinRepViewData>
        get() {
            var df = db.selectTableDataIntoDbData(::WithinRepViewData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    /**
     *  Returns a data frame that has columns (exp_name, rep_id, [responseName]) where
     *  the values in the [responseName] column have the value of the response for the named experiments
     *  and the replication id (number) for the value.
     */
    fun withRepViewStatistics(responseName: String): AnyFrame {
        val stat_name by column<String>()
        var dm = withinRepViewStatistics.filter { stat_name() == responseName }
        val rep_value by column<Double>()
        val exp_name by column<String>()
        val rep_id by column<Int>()
        dm = dm.select(exp_name, rep_id, rep_value)
        dm = dm.rename { rep_value }.into { responseName }
        return dm
    }

    val acrossReplicationStatistics: DataFrame<AcrossRepStatTableData>
        get() {
            var df = db.selectTableDataIntoDbData(::AcrossRepStatTableData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName", "autoIncField",
                "keyFields", "numInsertFields", "numUpdateFields")
            return df
        }

    val acrossReplicationViewStatistics: DataFrame<AcrossRepViewData>
        get() {
            var df = db.selectTableDataIntoDbData(::AcrossRepViewData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    val withinReplicationResponseStatistics: DataFrame<WithinRepStatTableData>
        get() {
            var df = db.selectTableDataIntoDbData(::WithinRepStatTableData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    val withinReplicationCounterStatistics: DataFrame<WithinRepCounterStatTableData>
        get() {
            var df = db.selectTableDataIntoDbData(::WithinRepCounterStatTableData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    val batchingStatistics: DataFrame<BatchStatTableData>
        get() {
            var df = db.selectTableDataIntoDbData(::BatchStatTableData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    val batchStatViewStatistics: DataFrame<BatchStatViewData>
        get() {
            var df = db.selectTableDataIntoDbData(::BatchStatViewData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    val expStatRepViewStatistics: DataFrame<ExpStatRepViewData>
        get() {
            var df = db.selectTableDataIntoDbData(::ExpStatRepViewData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    val pairWiseDiffViewStatistics: DataFrame<PWDiffWithinRepViewData>
        get() {
            var df = db.selectTableDataIntoDbData(::PWDiffWithinRepViewData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    val histogramResults: DataFrame<HistogramTableData>
        get() {
            var df = db.selectTableDataIntoDbData(::HistogramTableData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    val frequencyResults: DataFrame<FrequencyTableData>
        get() {
            var df = db.selectTableDataIntoDbData(::FrequencyTableData).toDataFrame()
            df = df.remove("numColumns", "schemaName", "tableName")
            return df
        }

    internal fun beforeExperiment(model: Model) {
        val experimentRecord = fetchExperimentData(model.experimentName)
        if (experimentRecord == null) {
//            println("**** The experiment record was null. Insert new experiment record: Database: ${db.label} Experiment: ${model.experimentName}")
            // this is a new experiment
            // create and insert the new experiment
            currentExp = createExperimentData(model)
            val k = db.insertDbDataIntoTable(currentExp)
            if (k == 0) {
                throw DataAccessException("The experiment was not inserted")
            }
            // create the simulation run associated with the new experiment
            // start simulation run record
            currentSimRun = createSimulationRunData(model)
            db.insertDbDataIntoTable(currentSimRun!!)
            // a new experiment requires capturing the model elements, controls, and rv parameters
            // capture the model elements associated with the experiment
            val modelElements: List<ModelElement> = model.getModelElements()
            insertModelElementRecords(modelElements)
            // if the model has controls, capture them
            if (model.hasExperimentalControls()) {
                // insert controls if they are there
                val controls: Controls = model.controls()
                insertDbControlRecords(controls.asList())
            }
            // if the model has a rv parameter setter, capture the parameters
            if (model.hasParameterSetter()) {
                // insert the random variable parameters
                val ps: RVParameterSetter = model.rvParameterSetter
                insertDbRvParameterRecords(ps.rvParametersData)
            }
        } else {
//            println("**** The experiment record was not null. Database: ${db.label} Experiment: ${model.experimentName}")
            // there was already and existing record for this experiment
            // the experiment must be chunked or there is a potential user error
            if (model.numChunks > 1) {
                // run is a chunk, make sure there is not an existing simulation run
                // just assume user wants to write over any existing simulation runs with the same name for this
                // experiment during this simulation execution
                currentExp = experimentRecord
                deleteSimulationRunWithName(experimentRecord.exp_id, model.runName)
                // create the simulation run associated with the chunked experiment
                // because if it was there by mistake, we just deleted it
                // start simulation run record
                currentSimRun = createSimulationRunData(model)
                db.insertDbDataIntoTable(currentSimRun!!)
            } else {
                println(experimentRecord)
                // not a chunk, same experiment but not chunked, this is a potential user error
                reportExistingExperimentRecordError(model)
            }
        }
        /*
        if (experimentRecord != null) {
            // experiment record exists, this must be a simulation run related to a chunk
            if (model.isChunked) {
                // run is a chunk, make sure there is not an existing simulation run
                // just assume user wants to write over any existing chunks with the same name for this
                // simulation execution,
                currentExp = experimentRecord
                deleteSimulationRunWithName(experimentRecord.exp_id, model.runName)
            } else {
                // not a chunk, same experiment but not chunked, this is a potential user error
                reportExistingExperimentRecordError(model)
            }
        } else {
            currentExp = createExperimentData(model)
            val k = db.insertDbDataIntoTable(currentExp)
            if (k == 0) {
                throw DataAccessException("The experiment was not inserted")
            }
        }
        // start simulation run record
        currentSimRun = createSimulationRunData(model)
        db.insertDbDataIntoTable(currentSimRun!!)
        // insert the model elements into the database

        //TODO issue: when experiment is chunked, we do not need to re-insert model elements
        // should also not need to insert controls and parameters
        // need to delete them when deleting run or not try to re-insert them here

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

         */
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
        // insert the histogram data
        insertHistogramResponses(model.histograms)
        // insert the frequency data
        insertFrequencyResponses(model.frequencies)
    }

    private fun insertFrequencyResponses(frequencies: List<IntegerFrequencyResponse>) {
        val list = mutableListOf<FrequencyTableData>()
        for (freq in frequencies) {
            val freqRecords = createFrequencyDataRecords(freq, currentSimRun!!.run_id)
            list.addAll(freqRecords)
        }
       // println("db.insertDbDataIntoTable(list, \"frequency\")")
        db.insertDbDataIntoTable(list, "frequency")
    }

    private fun createFrequencyDataRecords(
        freq: IntegerFrequencyResponse,
        simId: Int
    ): List<FrequencyTableData> {
        val list = mutableListOf<FrequencyTableData>()
        val freqData = freq.frequencyData()
        for (fd in freqData) {
            val record = FrequencyTableData()
            record.element_id_fk = freq.id
            record.sim_run_id_fk = simId
            record.variable_id_fk = freq.myVariable.id
            record.variable_name = freq.myVariable.name
            record.cell_label = fd.cellLabel
            record.value = fd.value
            if (!fd.count.isNaN() && fd.count.isFinite()) {
                record.count = fd.count
            }
            if (!fd.cum_count.isNaN() && fd.cum_count.isFinite()) {
                record.cum_count = fd.cum_count
            }
            if (!fd.proportion.isNaN() && fd.proportion.isFinite()) {
                record.proportion = fd.proportion
            }
            if (!fd.cumProportion.isNaN() && fd.cumProportion.isFinite()) {
                record.cum_proportion = fd.cumProportion
            }
            list.add(record)
        }
        return list;
    }

    private fun insertHistogramResponses(histograms: List<HistogramResponse>) {
        val list = mutableListOf<HistogramTableData>()
        for (h in histograms) {
            val histRecords = createHistogramDataRecords(h, currentSimRun!!.run_id)
            list.addAll(histRecords)
        }
        db.insertDbDataIntoTable(list, "histogram")
    }

    private fun createHistogramDataRecords(
        histResponse: HistogramResponse,
        simId: Int
    ): List<HistogramTableData> {
        val list = mutableListOf<HistogramTableData>()
        val histData = histResponse.histogram.histogramData()
        for (hd in histData) {
            val record = HistogramTableData()
            record.element_id_fk = histResponse.id
            record.sim_run_id_fk = simId
            record.response_id_fk = histResponse.response.id
            record.response_name = histResponse.response.name
            record.bin_label = hd.binLabel
            record.bin_num = hd.binNum
            if (!hd.binLowerLimit.isNaN() && hd.binLowerLimit.isFinite()) {
                record.bin_lower_limit = hd.binLowerLimit
            }
            if (!hd.binUpperLimit.isNaN() && hd.binUpperLimit.isFinite()) {
                record.bin_upper_limit = hd.binUpperLimit
            }
            if (!hd.binCount.isNaN() && hd.binCount.isFinite()) {
                record.bin_count = hd.binCount
            }
            if (!hd.cumCount.isNaN() && hd.cumCount.isFinite()) {
                record.bin_cum_count = hd.cumCount
            }
            if (!hd.proportion.isNaN() && hd.proportion.isFinite()) {
                record.bin_proportion = hd.proportion
            }
            if (!hd.cumProportion.isNaN() && hd.cumProportion.isFinite()) {
                record.bin_cum_proportion = hd.cumProportion
            }
            list.add(record)
        }
        return list;
    }

    private fun finalizeCurrentSimulationRun(model: Model) {
        currentSimRun?.last_rep_id = model.startingRepId + model.numberReplicationsCompleted - 1
        currentSimRun?.run_end_time_stamp = Timestamp.from(Clock.System.now().toJavaInstant())
        currentSimRun?.run_error_msg = model.runErrorMsg
        db.updateDbDataInTable(currentSimRun!!)
        DatabaseIfc.logger.trace { "Finalized SimulationRun record for simulation: ${model.simulationName}" }
    }

    private fun reportExistingExperimentRecordError(model: Model) {
        val simName: String = model.simulationName
        val expName: String = model.experimentName
        KSL.logger.error { "An experiment record exists for simulation: $simName, and experiment: $expName in database ${db.label}" }
        KSL.logger.error { "The user attempted to run a simulation for an experiment that has " }
        KSL.logger.error { " the same name as an existing experiment without allowing its data to be cleared." }
        KSL.logger.error { "The user should consider explicitly clearing data within the database associated with experiment $expName." }
        KSL.logger.error { " This can be accomplished by using the clearAllData() or deleteExperimentWithName(expName=$expName) functions prior to rerunning." }
        KSL.logger.error { "Or, the user might change the name of the experiment before calling simulating the model." }
        KSL.logger.error { "This error is to prevent the user from accidentally losing data associated with simulation: $simName, and experiment: $expName in database ${db.label}" }
        throw DataAccessException("An experiment record already exists with the experiment name $expName. Check the ksl.log for details.")
    }

    private fun createExperimentData(model: Model): ExperimentTableData {
        val record = ExperimentTableData()
        record.sim_name = model.simulationName
        record.exp_name = model.experimentName
        record.model_name = model.name
        record.num_chunks = model.numChunks
        if (!model.lengthOfReplication.isNaN() && model.lengthOfReplication.isFinite()) {
            record.length_of_rep = model.lengthOfReplication
        }
        record.length_of_warm_up = model.lengthOfReplicationWarmUp
        record.rep_allowed_exec_time = model.maximumAllowedExecutionTime.inWholeMilliseconds
        record.rep_init_option = model.replicationInitializationOption
        record.reset_start_stream_option = model.resetStartStreamOption
        record.antithetic_option = model.antitheticOption
        record.adv_next_sub_stream_option = model.advanceNextSubStreamOption
        record.num_stream_advances = model.numberOfStreamAdvancesPriorToRunning
        record.gc_after_rep_option = model.garbageCollectAfterReplicationFlag
        return record
    }

    private fun createSimulationRunData(model: Model): SimulationRunTableData {
        val record = SimulationRunTableData()
        record.exp_id_fk = currentExp.exp_id
        record.num_reps = model.numberOfReplications
        record.run_name = model.runName
        record.start_rep_id = model.startingRepId
        record.run_start_time_stamp = Timestamp.from(Clock.System.now().toJavaInstant())
        return record
    }

    private fun createDbModelElement(element: ModelElement, expId: Int): ModelElementTableData {
        val dbm = ModelElementTableData()
        dbm.exp_id_fk = expId
        dbm.element_name = element.name
        dbm.element_id = element.id
        dbm.class_name = element::class.simpleName!!
        if (element.myParentModelElement != null) {
            dbm.parent_id_fk = element.myParentModelElement!!.id
            dbm.parent_name = element.myParentModelElement!!.name
        }
        dbm.left_count = element.leftTraversalCount
        dbm.right_count = element.rightTraversalCount
        return dbm
    }

    private fun createDbControlRecord(control: ControlIfc, expId: Int): ControlTableData {
        val c = ControlTableData()
        c.exp_id_fk = expId
        c.element_id_fk = control.elementId
        c.key_name = control.keyName
        if (!control.value.isNaN() && control.value.isFinite()) {
            c.control_value = control.value
        }
        if (!control.lowerBound.isNaN() && control.lowerBound.isFinite()) {
            c.lower_bound = control.lowerBound
        }
        if (!control.upperBound.isNaN() && control.upperBound.isFinite()) {
            c.upper_bound = control.upperBound
        }
        c.property_name = control.propertyName
        c.control_type = control.type.toString()
        c.comment = control.comment
        return c
    }

    private fun createDbRvParameterRecord(rvParamData: RVParameterData, expId: Int): RvParameterTableData {
        val rvp = RvParameterTableData()
        rvp.exp_id_fk = expId
        rvp.element_id_fk = rvParamData.elementId
        rvp.class_name = rvParamData.clazzName
        rvp.data_type = rvParamData.dataType
        rvp.rv_name = rvParamData.rvName
        rvp.param_name = rvParamData.paramName
        rvp.param_value = rvParamData.paramValue
        return rvp
    }

    private fun createWithinRepStatRecord(response: Response, simId: Int): WithinRepStatTableData {
        val r = WithinRepStatTableData()
        r.element_id_fk = response.id
        r.sim_run_id_fk = simId
        r.rep_id = response.model.currentReplicationId
        val s = response.withinReplicationStatistic
        r.stat_name = s.name
        if (!s.count.isNaN() && s.count.isFinite()) {
            r.stat_count = s.count
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
            r.weighted_sum = s.weightedSum
        }
        if (!s.sumOfWeights.isNaN() && s.sumOfWeights.isFinite()) {
            r.sum_of_weights = s.sumOfWeights
        }
        if (!s.weightedSumOfSquares.isNaN() && s.weightedSumOfSquares.isFinite()) {
            r.weighted_ssq = s.weightedSumOfSquares
        }
        if (!s.lastValue.isNaN() && s.lastValue.isFinite()) {
            r.last_value = s.lastValue
        }
        if (!s.lastWeight.isNaN() && s.lastWeight.isFinite()) {
            r.last_weight = s.lastWeight
        }
        return r
    }

    private fun createWithinRepCounterRecord(counter: Counter, simId: Int): WithinRepCounterStatTableData {
        val r = WithinRepCounterStatTableData()
        r.element_id_fk = counter.id
        r.sim_run_id_fk = simId
        r.rep_id = counter.model.currentReplicationId
        r.stat_name = counter.name
        if (!counter.value.isNaN() && counter.value.isFinite()) {
            r.last_value = counter.value
        }
        return r
    }

    private fun createAcrossRepStatRecord(response: ModelElement, simId: Int, s: StatisticIfc): AcrossRepStatTableData {
        val r = AcrossRepStatTableData()
        r.element_id_fk = response.id
        r.sim_run_id_fk = simId
        r.stat_name = s.name
        if (!s.count.isNaN() && s.count.isFinite()) {
            r.stat_count = s.count
        }
        if (!s.average.isNaN() && s.average.isFinite()) {
            r.average = s.average
        }
        if (!s.standardDeviation.isNaN() && s.standardDeviation.isFinite()) {
            r.std_dev = s.standardDeviation
        }
        if (!s.standardError.isNaN() && s.standardError.isFinite()) {
            r.std_err = s.standardError
        }
        if (!s.halfWidth.isNaN() && s.halfWidth.isFinite()) {
            r.half_width = s.halfWidth
        }
        if (!s.confidenceLevel.isNaN() && s.confidenceLevel.isFinite()) {
            r.conf_level = s.confidenceLevel
        }
        if (!s.min.isNaN() && s.min.isFinite()) {
            r.minimum = s.min
        }
        if (!s.max.isNaN() && s.max.isFinite()) {
            r.maximum = s.max
        }
        if (!s.sum.isNaN() && s.sum.isFinite()) {
            r.sum_of_obs = s.sum
        }
        if (!s.deviationSumOfSquares.isNaN() && s.deviationSumOfSquares.isFinite()) {
            r.dev_ssq = s.deviationSumOfSquares
        }
        if (!s.lastValue.isNaN() && s.lastValue.isFinite()) {
            r.last_value = s.lastValue
        }
        if (!s.kurtosis.isNaN() && s.kurtosis.isFinite()) {
            r.kurtosis = s.kurtosis
        }
        if (!s.skewness.isNaN() && s.skewness.isFinite()) {
            r.skewness = s.skewness
        }
        if (!s.lag1Covariance.isNaN() && s.lag1Covariance.isFinite()) {
            r.lag1_cov = s.lag1Covariance
        }
        if (!s.lag1Correlation.isNaN() && s.lag1Correlation.isFinite()) {
            r.lag1_corr = s.lag1Correlation
        }
        if (!s.vonNeumannLag1TestStatistic.isNaN() && s.vonNeumannLag1TestStatistic.isFinite()) {
            r.von_neumann_lag1_stat = s.vonNeumannLag1TestStatistic
        }
        if (!s.numberMissing.isNaN() && s.numberMissing.isFinite()) {
            r.num_missing_obs = s.numberMissing
        }
        return r
    }

    private fun createBatchStatRecord(response: Response, simId: Int, s: BatchStatisticIfc): BatchStatTableData {
        val r = BatchStatTableData()
        r.element_id_fk = response.id
        r.sim_run_id_fk = simId
        r.rep_id = response.model.currentReplicationId
        r.stat_name = s.name
        if (!s.count.isNaN() && s.count.isFinite()) {
            r.stat_count = s.count
        }
        if (!s.average.isNaN() && s.average.isFinite()) {
            r.average = s.average
        }
        if (!s.standardDeviation.isNaN() && s.standardDeviation.isFinite()) {
            r.std_dev = s.standardDeviation
        }
        if (!s.standardError.isNaN() && s.standardError.isFinite()) {
            r.std_err = s.standardError
        }
        if (!s.halfWidth.isNaN() && s.halfWidth.isFinite()) {
            r.half_width = s.halfWidth
        }
        if (!s.confidenceLevel.isNaN() && s.confidenceLevel.isFinite()) {
            r.conf_level = s.confidenceLevel
        }
        if (!s.min.isNaN() && s.min.isFinite()) {
            r.minimum = s.min
        }
        if (!s.max.isNaN() && s.max.isFinite()) {
            r.maximum = s.max
        }
        if (!s.sum.isNaN() && s.sum.isFinite()) {
            r.sum_of_obs = s.sum
        }
        if (!s.deviationSumOfSquares.isNaN() && s.deviationSumOfSquares.isFinite()) {
            r.dev_ssq = s.deviationSumOfSquares
        }
        if (!s.lastValue.isNaN() && s.lastValue.isFinite()) {
            r.last_value = s.lastValue
        }
        if (!s.kurtosis.isNaN() && s.kurtosis.isFinite()) {
            r.kurtosis = s.kurtosis
        }
        if (!s.skewness.isNaN() && s.skewness.isFinite()) {
            r.skewness = s.skewness
        }
        if (!s.lag1Covariance.isNaN() && s.lag1Covariance.isFinite()) {
            r.lag1_cov = s.lag1Covariance
        }
        if (!s.lag1Correlation.isNaN() && s.lag1Correlation.isFinite()) {
            r.lag1_corr = s.lag1Correlation
        }
        if (!s.vonNeumannLag1TestStatistic.isNaN() && s.vonNeumannLag1TestStatistic.isFinite()) {
            r.von_neumann_lag1_stat = s.vonNeumannLag1TestStatistic
        }
        if (!s.numberMissing.isNaN() && s.numberMissing.isFinite()) {
            r.num_missing_obs = s.numberMissing
        }
        r.min_batch_size = s.minBatchSize.toDouble()
        r.min_num_batches = s.minNumBatches.toDouble()
        r.max_num_batches_multiple = s.minNumBatchesMultiple.toDouble()
        r.max_num_batches = s.maxNumBatches.toDouble()
        r.num_rebatches = s.numRebatches.toDouble()
        r.current_batch_size = s.currentBatchSize.toDouble()
        if (!s.amountLeftUnbatched.isNaN() && s.amountLeftUnbatched.isFinite()) {
            r.amt_unbatched = s.amountLeftUnbatched
        }
        if (!s.totalNumberOfObservations.isNaN() && s.totalNumberOfObservations.isFinite()) {
            r.total_num_obs = s.totalNumberOfObservations
        }
        return r
    }

    private fun insertModelElementRecords(elements: List<ModelElement>) {
        val list = mutableListOf<ModelElementTableData>()
        for (element in elements) {
            val dbModelElement = createDbModelElement(element, currentExp.exp_id)
            list.add(dbModelElement)
        }
        db.insertDbDataIntoTable(list, "model_element")
    }

    private fun insertDbControlRecords(controls: List<ControlIfc>) {
        val list = mutableListOf<ControlTableData>()
        for (c in controls) {
            val cr = createDbControlRecord(c, currentExp.exp_id)
            list.add(cr)
        }
        db.insertDbDataIntoTable(list, "control")
    }

    private fun insertDbRvParameterRecords(pData: List<RVParameterData>) {
        val list = mutableListOf<RvParameterTableData>()
        for (param in pData) {
            val r = createDbRvParameterRecord(param, currentExp.exp_id)
            list.add(r)
        }
        db.insertDbDataIntoTable(list, "rv_parameter")
    }

    private fun insertWithinRepResponses(responses: List<Response>) {
        val list = mutableListOf<WithinRepStatTableData>()
        for (response in responses) {
            val withinRepStatRecord = createWithinRepStatRecord(response, currentSimRun!!.run_id)
            list.add(withinRepStatRecord)
        }
        db.insertDbDataIntoTable(list, "within_rep_stat")
    }

    private fun insertWithinRepCounters(counters: List<Counter>) {
        val list = mutableListOf<WithinRepCounterStatTableData>()
        for (counter in counters) {
            val withinRepCounterRecord = createWithinRepCounterRecord(counter, currentSimRun!!.run_id)
            list.add(withinRepCounterRecord)
        }
        db.insertDbDataIntoTable(list, "within_rep_counter_stat")
    }

    private fun insertAcrossRepResponses(responses: List<Response>) {
        val list = mutableListOf<AcrossRepStatTableData>()
        for (response in responses) {
            val s = response.acrossReplicationStatistic
            val acrossRepStatRecord = createAcrossRepStatRecord(response, currentSimRun!!.run_id, s)
            list.add(acrossRepStatRecord)
        }
        db.insertDbDataIntoTable(list, "across_rep_stat")
    }

    private fun insertAcrossRepResponsesForCounters(counters: List<Counter>) {
        val list = mutableListOf<AcrossRepStatTableData>()
        for (counter in counters) {
            val s = counter.acrossReplicationStatistic
            val acrossRepCounterRecord = createAcrossRepStatRecord(counter, currentSimRun!!.run_id, s)
            list.add(acrossRepCounterRecord)
        }
        db.insertDbDataIntoTable(list, "across_rep_stat")
    }

    private fun insertResponseVariableBatchStatistics(rMap: Map<Response, BatchStatisticIfc>) {
        val list = mutableListOf<BatchStatTableData>()
        for (entry in rMap.entries.iterator()) {
            val r = entry.key
            val bs = entry.value
            val batchStatRecord = createBatchStatRecord(r, currentSimRun!!.run_id, bs)
            list.add(batchStatRecord)
        }
        db.insertDbDataIntoTable(list, "batch_stat")
    }

    private fun insertTimeWeightedBatchStatistics(twMap: Map<TWResponse, BatchStatisticIfc>) {
        val list = mutableListOf<BatchStatTableData>()
        for (entry in twMap.entries.iterator()) {
            val tw = entry.key
            val bs = entry.value
            val batchStatRecord = createBatchStatRecord(tw, currentSimRun!!.run_id, bs)
            list.add(batchStatRecord)
        }
        db.insertDbDataIntoTable(list, "batch_stat")
    }

    /**
     * Returns the observations for the named experiment and the named statistical response
     * from within
     */
    fun withinReplicationObservationsFor(expNameStr: String, statNameStr: String): DoubleArray {
        var df = withinRepViewStatistics
        val exp_name by column<String>()
        val stat_name by column<String>()
        val rep_value by column<Double>()
        val rep_id by column<Double>()
        df = df.filter { exp_name() == expNameStr && stat_name() == statNameStr }
        df = df.select(rep_id, rep_value).sortBy(rep_id).select(rep_value)
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
        require(expNames.isNotEmpty()) { "The list of experiment names was empty" }
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
        val TableNames = listOf("frequency", "histogram",
            "batch_stat", "within_rep_counter_stat", "across_rep_stat", "within_rep_stat",
            "rv_parameter", "control", "model_element", "simulation_run", "experiment"
        )

        val ViewNames = listOf(
            "within_rep_response_view", "within_rep_counter_view", "within_rep_view", "exp_stat_rep_view",
            "across_rep_view", "batch_stat_view", "pw_diff_within_rep_view"
        )

        private const val SCHEMA_NAME = "KSL_DB"

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
            val database = SQLiteDb.createDatabase(dbName, dbDirectory)
            val executed = database.executeScript(dbScriptsDir.resolve("KSL_SQLite.sql"))
            // database.defaultSchemaName = "main"
            if (!executed) {
                DatabaseIfc.logger.error { "Unable to execute KSL_SQLite.sql creation script" }
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
            val derbyDatabase = DerbyDb.createDatabase(dbName, dbDirectory)
            executeKSLDbCreationScriptOnDatabase(derbyDatabase)
            derbyDatabase.defaultSchemaName = SCHEMA_NAME
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
            if (!db.containsSchema(SCHEMA_NAME)) {
                DatabaseIfc.logger.warn { "The database ${db.label} does not contain schema $SCHEMA_NAME" }
                try {
                    DatabaseIfc.logger.warn { "Assume the schema has not be made and execute the creation script KSL_Db.sql" }
                    val executed = db.executeScript(dbScriptsDir.resolve("KSL_Db.sql"))
                    if (!executed) {
                        throw DataAccessException("The execution script KSL_Db.sql did not fully execute")
                    } else {
                        DatabaseIfc.logger.info { "Executed the creation script KSL_Db.sql for ${db.label}" }
                    }
                } catch (e: IOException) {
                    DatabaseIfc.logger.error { "Unable to execute KSL_Db.sql creation script" }
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
            dbName: String,
            dbServerName: String = "localhost",
            user: String = "postgres",
            pWord: String = ""
        ): Database {
            val props: Properties = PostgresDb.createProperties(dbServerName, dbName, user, pWord)
            val db = Database.createDatabaseFromProperties(props)
            db.executeCommand("DROP SCHEMA IF EXISTS ksl_db CASCADE")
            executeKSLDbCreationScriptOnDatabase(db)
            db.defaultSchemaName = "ksl_db"
            return db
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
            dbName: String,
            dbServerName: String = "localhost",
            user: String = "postgres",
            pWord: String = ""
        ): KSLDatabase {
            val props: Properties = PostgresDb.createProperties(dbServerName, dbName, user, pWord)
            val kslDatabase: KSLDatabase = connectKSLDatabase(clearDataOption, props)
            DatabaseIfc.logger.info { "Connected to a postgres KSL database ${kslDatabase.db.dbURL} " }
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
            val db: Database = Database.createDatabaseFromProperties(dBProperties)
            return KSLDatabase(db, clearDataOption)
        }

    }
}

data class ExperimentTableData(
    var exp_id: Int = -1,
    var sim_name: String = "",
    var model_name: String = "",
    var exp_name: String = "",
    var num_chunks: Int = 1,
    var length_of_rep: Double? = null,
    var length_of_warm_up: Double? = null,
    var rep_allowed_exec_time: Long? = null,
    var rep_init_option: Boolean = true,
    var reset_start_stream_option: Boolean = false,
    var antithetic_option: Boolean = false,
    var adv_next_sub_stream_option: Boolean = true,
    var num_stream_advances: Int = -1,
    var gc_after_rep_option: Boolean = false
) : DbTableData("experiment", keyFields = listOf("exp_id"), autoIncField = true)

data class SimulationRunTableData(
    var run_id: Int = -1,
    var exp_id_fk: Int = -1,
    var run_name: String = "",
    var num_reps: Int = -1,
    var start_rep_id: Int = -1,
    var last_rep_id: Int? = null,
    var run_start_time_stamp: Timestamp? = null,
    var run_end_time_stamp: Timestamp? = null,
    var run_error_msg: String? = null
) : DbTableData("simulation_run", keyFields = listOf("run_id"), autoIncField = true)

data class ModelElementTableData(
    var exp_id_fk: Int = -1,
    var element_id: Int = -1,
    var element_name: String = "",
    var class_name: String = "",
    var parent_id_fk: Int? = null,
    var parent_name: String? = null,
    var left_count: Int = -1,
    var right_count: Int = -1
) : DbTableData("model_element", keyFields = listOf("exp_id_fk", "element_id"))

data class ControlTableData(
    var control_id: Int = -1,
    var exp_id_fk: Int = -1,
    var element_id_fk: Int = -1,
    var key_name: String = "",
    var control_value: Double? = null,
    var lower_bound: Double? = null,
    var upper_bound: Double? = null,
    var property_name: String = "",
    var control_type: String = "",
    var comment: String? = null
) : DbTableData("control", keyFields = listOf("control_id"), autoIncField = true)

data class RvParameterTableData(
    var rv_param_id: Int = -1,
    var exp_id_fk: Int = -1,
    var element_id_fk: Int = -1,
    var class_name: String = "",
    var data_type: String = "",
    var rv_name: String = "",
    var param_name: String = "",
    var param_value: Double = Double.NaN
) : DbTableData("rv_parameter", keyFields = listOf("rv_param_id"), autoIncField = true)

data class WithinRepStatTableData(
    var id: Int = -1,
    var element_id_fk: Int = -1,
    var sim_run_id_fk: Int = -1,
    var rep_id: Int = -1,
    var stat_name: String = "",
    var stat_count: Double? = null,
    var average: Double? = null,
    var minimum: Double? = null,
    var maximum: Double? = null,
    var weighted_sum: Double? = null,
    var sum_of_weights: Double? = null,
    var weighted_ssq: Double? = null,
    var last_value: Double? = null,
    var last_weight: Double? = null
) : DbTableData("within_rep_stat", keyFields = listOf("id"), autoIncField = true)

data class WithinRepCounterStatTableData(
    var id: Int = -1,
    var element_id_fk: Int = -1,
    var sim_run_id_fk: Int = -1,
    var rep_id: Int = -1,
    var stat_name: String = "",
    var last_value: Double? = null
) : DbTableData("within_rep_counter_stat", keyFields = listOf("id"), autoIncField = true)

data class AcrossRepStatTableData(
    var id: Int = -1,
    var element_id_fk: Int = -1,
    var sim_run_id_fk: Int = -1,
    var stat_name: String = "",
    var stat_count: Double? = null,
    var average: Double? = null,
    var std_dev: Double? = null,
    var std_err: Double? = null,
    var half_width: Double? = null,
    var conf_level: Double? = null,
    var minimum: Double? = null,
    var maximum: Double? = null,
    var sum_of_obs: Double? = null,
    var dev_ssq: Double? = null,
    var last_value: Double? = null,
    var kurtosis: Double? = null,
    var skewness: Double? = null,
    var lag1_cov: Double? = null,
    var lag1_corr: Double? = null,
    var von_neumann_lag1_stat: Double? = null,
    var num_missing_obs: Double? = null
) : DbTableData("across_rep_stat", keyFields = listOf("id"), autoIncField = true)

data class BatchStatTableData(
    var id: Int = -1,
    var element_id_fk: Int = -1,
    var sim_run_id_fk: Int = -1,
    var rep_id: Int = -1,
    var stat_name: String = "",
    var stat_count: Double? = null,
    var average: Double? = null,
    var std_dev: Double? = null,
    var std_err: Double? = null,
    var half_width: Double? = null,
    var conf_level: Double? = null,
    var minimum: Double? = null,
    var maximum: Double? = null,
    var sum_of_obs: Double? = null,
    var dev_ssq: Double? = null,
    var last_value: Double? = null,
    var kurtosis: Double? = null,
    var skewness: Double? = null,
    var lag1_cov: Double? = null,
    var lag1_corr: Double? = null,
    var von_neumann_lag1_stat: Double? = null,
    var num_missing_obs: Double? = null,
    var min_batch_size: Double? = null,
    var min_num_batches: Double? = null,
    var max_num_batches_multiple: Double? = null,
    var max_num_batches: Double? = null,
    var num_rebatches: Double? = null,
    var current_batch_size: Double? = null,
    var amt_unbatched: Double? = null,
    var total_num_obs: Double? = null
) : DbTableData("batch_stat", keyFields = listOf("id"), autoIncField = true)

data class HistogramTableData(
    var id: Int = -1,
    var element_id_fk: Int = -1,
    var sim_run_id_fk: Int = -1,
    var response_id_fk: Int = -1,
    var response_name: String = "",
    var bin_label: String = "",
    var bin_num: Int = -1,
    var bin_lower_limit: Double? = null,
    var bin_upper_limit: Double? = null,
    var bin_count: Double? = null,
    var bin_cum_count: Double? = null,
    var bin_proportion: Double? = null,
    var bin_cum_proportion: Double? = null
) : DbTableData("histogram", keyFields = listOf("id"), autoIncField = true)

data class FrequencyTableData(
    var id: Int = -1,
    var element_id_fk: Int = -1,
    var sim_run_id_fk: Int = -1,
    var variable_id_fk: Int = -1,
    var variable_name: String = "",
    var cell_label: String = "",
    var value: Int = -1,
    var count: Double? = null,
    var cum_count: Double? = null,
    var proportion: Double? = null,
    var cum_proportion: Double? = null
) : DbTableData("frequency", keyFields = listOf("id"), autoIncField = true)

data class WithinRepResponseViewData(
    var exp_name: String = "",
    var run_name: String = "",
    var num_reps: Int = -1,
    var start_rep_id: Int = -1,
    var last_rep_id: Int = -1,
    var stat_name: String = "",
    var rep_id: Int = -1,
    var average: Double? = null
) : TabularData("within_rep_response_view")

data class WithinRepCounterViewData(
    var exp_name: String = "",
    var run_name: String = "",
    var num_reps: Int = -1,
    var start_rep_id: Int = -1,
    var last_rep_id: Int = -1,
    var stat_name: String = "",
    var rep_id: Int = -1,
    var last_value: Double? = null
) : TabularData("within_rep_counter_view")

data class WithinRepViewData(
    var exp_name: String = "",
    var run_name: String = "",
    var num_reps: Int = -1,
    var start_rep_id: Int = -1,
    var last_rep_id: Int = -1,
    var stat_name: String = "",
    var rep_id: Int = -1,
    var rep_value: Double? = null
) : TabularData("within_rep_view")

data class ExpStatRepViewData(
    var exp_name: String = "",
    var stat_name: String = "",
    var rep_id: Int = -1,
    var rep_value: Double? = null
) : TabularData("exp_stat_rep_view")

data class AcrossRepViewData(
    var exp_name: String = "",
    var stat_name: String = "",
    var stat_count: Double? = null,
    var average: Double? = null,
    var std_dev: Double? = null
) : TabularData("across_rep_view")

data class BatchStatViewData(
    var exp_name: String = "",
    var run_name: String = "",
    var rep_id: Int = -1,
    var stat_name: String = "",
    var stat_count: Double? = null,
    var average: Double? = null,
    var std_dev: Double? = null
) : TabularData("batch_stat_view")

data class PWDiffWithinRepViewData(
    var sim_name: String = "",
    var stat_name: String = "",
    var rep_id: Int = -1,
    var a_exp_name: String = "",
    var a_value: Double? = null,
    var b_exp_name: String = "",
    var b_value: Double? = null,
    var diff_name: String = "",
    var a_minus_b: Double? = null
) : TabularData("pw_diff_within_rep_view")

class KSLDatabaseNotConfigured(msg: String = "KSLDatabase: The supplied database was not configured as a KSLDatabase!") :
    RuntimeException(msg)