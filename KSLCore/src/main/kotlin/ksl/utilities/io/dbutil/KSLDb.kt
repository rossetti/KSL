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
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.ksldbjooq.tables.records.*
import ksl.utilities.io.dbutil.ksldbjooq.tables.references.*
import ksl.utilities.random.rvariable.RVParameterData
import ksl.utilities.random.rvariable.RVParameterSetter
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.ktorm.dsl.like
import org.ktorm.entity.add
import org.ktorm.entity.find
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
        KSLDatabase.createEmbeddedDerbyKSLDatabase(dbName, dbDirectory), clearDataOption)

    /**
     *  If true the underlying database was configured as a KSLDatabase
     */
    val configured: Boolean
    private val myDSLContext: DSLContext
    private var currentExpId: Int? = null
    private var currentSimRun : SimulationRunRecord? = null

    init {
        val sqlDialect = JOOQ.getSQLDialect(db.dataSource)
        if (sqlDialect == SQLDialect.DEFAULT){
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

    private val dbExperiments: List<ExperimentData>
        get() = myDSLContext.selectFrom(EXPERIMENT).fetch().into(ExperimentData::class.java)
    
    /**
     * Returns the names of the experiments in the EXPERIMENT table.
     */
    val experimentNames: List<String>
        get() {
            val list = mutableListOf<String>()
            val iterator = dbExperiments.iterator()
            while (iterator.hasNext()) {
                val e = iterator.next()
                list.add(e.expName)
            }
            return list
        }

    fun clearAllData() {
        // remove all data from user tables
        var i = 0
        for(tblName in TableNames){
            val b = db.deleteAllFrom(tblName, db.defaultSchemaName)
            if (b){
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

    internal fun beforeExperiment(model: Model){
        var experimentRecord: ExperimentRecord? =
            myDSLContext.selectFrom(EXPERIMENT).where(EXPERIMENT.EXP_NAME.eq(model.experimentName)).fetchOne()
        if (experimentRecord != null){
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
        ): KSLDb{
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