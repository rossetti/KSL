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

import ksl.utilities.io.KSL
import org.ktorm.dsl.deleteAll
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
        KSLDatabase.createSQLiteKSLDatabase(dbName, dbDirectory), clearDataOption)

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
        ): KSLDb{
            val db: Database = DatabaseFactory.createDatabaseFromProperties(dBProperties)
            return KSLDb(db, clearDataOption)
        }
    }
}