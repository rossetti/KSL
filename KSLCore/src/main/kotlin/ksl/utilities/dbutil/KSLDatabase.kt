package ksl.utilities.dbutil

import ksl.simulation.Model
import ksl.utilities.io.KSL
import org.ktorm.dsl.isNotNull
import org.ktorm.entity.Entity
import org.ktorm.logging.Slf4jLoggerAdapter
import org.ktorm.schema.*
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.*

class KSLDatabase(private val db: Database, clearDataOption: Boolean = false) {

    private val kDb =
        org.ktorm.database.Database.connect(db.dataSource, logger = Slf4jLoggerAdapter(DatabaseIfc.logger))

    internal var simulationRun: SimulationRun? = null

    val label = db.label

    init {
//TODO        validateDatabase()
        if (clearDataOption) {
//TODO            clearAllData()
        }
    }

    private fun validateDatabase() {
        //TODO check if supplied database is configured as KSL database
        TODO("Not yet implemented")
    }

    fun clearAllData() {
        //TODO remove all data from user tables
        TODO("Not yet implemented")
    }

    internal fun beforeExperiment(model: Model) {
        TODO("Not yet implemented")
        // start simulation run record

        // insert the model elements into the database

    }

    internal fun afterReplication(model: Model) {
        TODO("Not yet implemented")
        // insert the within replication statistics

        // insert the within replication counters

        // insert the batch statistics if available

    }

    internal fun afterExperiment(model: Model) {
        TODO("Not yet implemented")
        // finalize current simulation run record

        // insert across replication response statistics

        // insert across replication counter statistics
    }

    fun clearSimulationData(model: Model) {
        TODO("Not yet implemented")
    }

    fun doesSimulationRunRecordExist(simName: String, expName: String): Boolean {
        TODO("Not yet implemented")
    }

    object SimulationRuns : Table<SimulationRun>("SIMULATION_RUN") {
        var id = int("ID").primaryKey().bindTo { it.id }
        var simName = varchar("SIM_NAME").bindTo { it.simName }.isNotNull()
        var modelName = varchar("MODEL_NAME").bindTo { it.modelName }.isNotNull()
        var expName = varchar("EXP_NAME").bindTo { it.expName }.isNotNull()
        var expStartTimeStamp = timestamp("EXP_START_TIME_STAMP").bindTo { it.expStartTimeStamp }
        var expEndTimeStamp = timestamp("EXP_END_TIME_STAMP").bindTo { it.expEndTimeStamp }
        var numReps = int("NUM_REPS").bindTo { it.numReps }.isNotNull()
        var lastRep = int("LAST_REP").bindTo { it.lastRep }
        var lengthOfRep = double("LENGTH_OF_REP").bindTo { it.lengthOfRep }
        var lengthOfWarmUp = double("LENGTH_OF_WARM_UP").bindTo { it.lengthOfWarmUp }
        var hasMoreReps = boolean("HAS_MORE_REPS").bindTo { it.hasMoreReps }
        var repAllowedExecTime = long("REP_ALLOWED_EXEC_TIME").bindTo { it.repAllowedExecTime }
        var repInitOption = boolean("REP_INIT_OPTION").bindTo { it.repInitOption }
        var repResetStartStreamOption = boolean("RESET_START_STREAM_OPTION").bindTo { it.repResetStartStreamOption }
        var antitheticOption = boolean("ANTITHETIC_OPTION").bindTo { it.antitheticOption }
        var advNextSubStreamOption = boolean("ADV_NEXT_SUB_STREAM_OPTION").bindTo { it.advNextSubStreamOption }
        var numStreamAdvances = int("NUM_STREAM_ADVANCES").bindTo { it.numStreamAdvances }
    }

    object DbModelElements : Table<DbModelElement>("MODEL_ELEMENT") {
        var simRunIDFk = int("SIM_RUN_ID_FK").primaryKey().bindTo { it.simRunIDFk } //TODO not sure how to do references
        var elementId = int("ELEMENT_ID").primaryKey().bindTo { it.elementId }
        var elementName = varchar("ELEMENT_NAME").bindTo { it.elementName }.isNotNull()
        var elementClassName = varchar("CLASS_NAME").bindTo { it.elementClassName }.isNotNull()
        var parentIDFk = int("PARENT_ID_FK").bindTo { it.parentIDFk }
        var parentName = varchar("PARENT_NAME").bindTo { it.parentName }
        var leftCount = int("LEFT_COUNT").bindTo { it.leftCount }
        var rightCount = int("RIGHT_COUNT").bindTo { it.rightCount }
    }

    object WithRepStats : Table<WithinRepStat>("WITHIN_REP_STAT") {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //TODO not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //TODO not sure how to do references
        var repNum = int("REP_NUM").bindTo { it.repNum }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var minimum = double("MINIMUM").bindTo { it.minimum }
        var maximum = double("MAXIMUM").bindTo { it.maximum }
        var weightedSum = double("WEIGHTED_SUM").bindTo { it.weightedSum }
        var sumOfWeights = double("SUM_OF_WEIGHTS").bindTo { it.sumOfWeights }
        var weightedSSQ = double("WEIGHTED_SSQ").bindTo { it.weightedSSQ }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
        var lastWeight = double("LAST_WEIGHT").bindTo { it.lastWeight }
    }

    object AcrossRepStats : Table<AcrossRepStat>("ACROSS_REP_STAT") {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //TODO not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //TODO not sure how to do references
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var stdDev = double("STD_DEV").bindTo { it.stdDev }
        var stdError = double("STD_ERR").bindTo { it.stdError }
        var halfWidth = double("HALF_WIDTH").bindTo { it.halfWidth }
        var confLevel = double("CONF_LEVEL").bindTo { it.confLevel }
        var minimum = double("MINIMUM").bindTo { it.minimum }
        var maximum = double("MAXIMUM").bindTo { it.maximum }
        var sumOfObs = double("SUM_OF_OBS").bindTo { it.sumOfObs }
        var devSSQ = double("DEV_SSQ").bindTo { it.devSSQ }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
        var kurtosis = double("KURTOSIS").bindTo { it.kurtosis }
        var skewness = double("SKEWNESS").bindTo { it.skewness }
        var lag1Cov = double("LAG1_COV").bindTo { it.lag1Cov }
        var lag1Corr = double("LAG1_CORR").bindTo { it.lag1Corr }
        var vonNeumanLag1Stat = double("VON_NEUMAN_LAG1_STAT").bindTo { it.vonNeumanLag1Stat }
        var numMissingObs = double("NUM_MISSING_OBS").bindTo { it.numMissingObs }
    }

    object WithinRepCounterStats : Table<WithinRepCounterStat>("WITHIN_REP_COUNTER_STAT") {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //TODO not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //TODO not sure how to do references
        var repNum = int("REP_NUM").bindTo { it.repNum }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
    }

    object BatchStats : Table<BatchStat>("BATCH_STAT") {
        var id = int("ID").primaryKey().bindTo { it.id }
        var elementIdFk = int("ELEMENT_ID_FK").bindTo { it.elementIdFk } //TODO not sure how to do references
        var simRunIdFk = int("SIM_RUN_ID_FK").bindTo { it.simRunIdFk } //TODO not sure how to do references
        var repNum = int("REP_NUM").bindTo { it.repNum }
        var statName = varchar("STAT_NAME").bindTo { it.statName }
        var statCount = double("STAT_COUNT").bindTo { it.statCount }
        var average = double("AVERAGE").bindTo { it.average }
        var stdDev = double("STD_DEV").bindTo { it.stdDev }
        var stdError = double("STD_ERR").bindTo { it.stdError }
        var halfWidth = double("HALF_WIDTH").bindTo { it.halfWidth }
        var confLevel = double("CONF_LEVEL").bindTo { it.confLevel }
        var minimum = double("MINIMUM").bindTo { it.minimum }
        var maximum = double("MAXIMUM").bindTo { it.maximum }
        var sumOfObs = double("SUM_OF_OBS").bindTo { it.sumOfObs }
        var devSSQ = double("DEV_SSQ").bindTo { it.devSSQ }
        var lastValue = double("LAST_VALUE").bindTo { it.lastValue }
        var kurtosis = double("KURTOSIS").bindTo { it.kurtosis }
        var skewness = double("SKEWNESS").bindTo { it.skewness }
        var lag1Cov = double("LAG1_COV").bindTo { it.lag1Cov }
        var lag1Corr = double("LAG1_CORR").bindTo { it.lag1Corr }
        var vonNeumanLag1Stat = double("VON_NEUMAN_LAG1_STAT").bindTo { it.vonNeumanLag1Stat }
        var numMissingObs = double("NUM_MISSING_OBS").bindTo { it.numMissingObs }
        var minBatchSize = double("MIN_BATCH_SIZE").bindTo { it.minBatchSize }
        var minNumBatches = double("MIN_NUM_BATCHES").bindTo { it.minNumBatches }
        var numRebatches = double("NUM_REBATCHES").bindTo { it.numRebatches }
        var currentBatchSize = double("CURRENT_BATCH_SIZE").bindTo { it.currentBatchSize }
        var amtUnbatched = double("AMT_UNBATCHED").bindTo { it.amtUnbatched }
        var totalNumObs = double("TOTAL_NUM_OBS").bindTo { it.totalNumObs }
    }

    interface SimulationRun : Entity<SimulationRun> {
        companion object : Entity.Factory<SimulationRun>()

        var id: Int
        var simName: String
        var modelName: String
        var expName: String
        var expStartTimeStamp: Instant?
        var expEndTimeStamp: Instant?
        var numReps: Int
        var lastRep: Int?
        var lengthOfRep: Double?
        var lengthOfWarmUp: Double?
        var hasMoreReps: Boolean?
        var repAllowedExecTime: Long?
        var repInitOption: Boolean?
        var repResetStartStreamOption: Boolean?
        var antitheticOption: Boolean?
        var advNextSubStreamOption: Boolean?
        var numStreamAdvances: Int?
    }

    interface DbModelElement : Entity<DbModelElement> {
        companion object : Entity.Factory<DbModelElement>()

        var simRunIDFk: Int
        var elementId: Int
        var elementName: String
        var elementClassName: String
        var parentIDFk: Int?
        var parentName: String?
        var leftCount: Int
        var rightCount: Int
    }

    interface WithinRepStat : Entity<WithinRepStat> {
        companion object : Entity.Factory<WithinRepStat>()

        var id: Int
        var elementIdFk: Int
        var simRunIdFk: Int
        var repNum: Int
        var statName: String?
        var statCount: Double?
        var average: Double?
        var minimum: Double?
        var maximum: Double?
        var weightedSum: Double?
        var sumOfWeights: Double?
        var weightedSSQ: Double?
        var lastValue: Double?
        var lastWeight: Double?
    }

    interface AcrossRepStat : Entity<AcrossRepStat> {
        companion object : Entity.Factory<AcrossRepStat>()

        var id: Int
        var elementIdFk: Int
        var simRunIdFk: Int
        var statName: String
        var statCount: Double
        var average: Double
        var stdDev: Double
        var stdError: Double
        var halfWidth: Double
        var confLevel: Double
        var minimum: Double
        var maximum: Double
        var sumOfObs: Double
        var devSSQ: Double
        var lastValue: Double
        var kurtosis: Double
        var skewness: Double
        var lag1Cov: Double
        var lag1Corr: Double
        var vonNeumanLag1Stat: Double
        var numMissingObs: Double
    }

    interface WithinRepCounterStat : Entity<WithinRepCounterStat> {
        companion object : Entity.Factory<WithinRepCounterStat>()

        var id: Int
        var elementIdFk: Int
        var simRunIdFk: Int
        var repNum: Int
        var statName: String?
        var lastValue: Double?
    }

    interface BatchStat : Entity<BatchStat> {
        companion object : Entity.Factory<BatchStat>()

        var id: Int
        var elementIdFk: Int
        var simRunIdFk: Int
        var repNum: Int
        var statName: String?
        var statCount: Double?
        var average: Double?
        var stdDev: Double?
        var stdError: Double?
        var halfWidth: Double?
        var confLevel: Double?
        var minimum: Double?
        var maximum: Double?
        var sumOfObs: Double?
        var devSSQ: Double?
        var lastValue: Double?
        var kurtosis: Double?
        var skewness: Double?
        var lag1Cov: Double?
        var lag1Corr: Double?
        var vonNeumanLag1Stat: Double?
        var numMissingObs: Double?
        var minBatchSize: Double?
        var minNumBatches: Double?
        var numRebatches: Double?
        var currentBatchSize: Double?
        var amtUnbatched: Double?
        var totalNumObs: Double?
    }

    companion object {
        private val TableNames = listOf(
            "batch_stat", "within_rep_counter_stat",
            "across_rep_stat", "within_rep_stat", "model_element", "simulation_run"
        )

        private val ViewNames = listOf(
            "within_rep_response_view",
            "within_rep_counter_view",
            "across_rep_view",
            "batch_stat_view",
            "within_rep_view",
            "pw_diff_within_rep_view"
        )

        private const val SchemaName = "KSL_DB"

        val dbDir: Path = KSL.dbDir
        val dbScriptsDir: Path = KSL.createSubDirectory("dbScript")

        init {
            //TODO some logging
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
                }
                if (dbDrop != null) {
                    Files.copy(
                        dbDrop, dbScriptsDir.resolve("KSL_DbDropScript.sql"),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
                if (dbSQLiteCreate != null) {
                    Files.copy(
                        dbSQLiteCreate, dbScriptsDir.resolve("KSL_SQLite.sql"),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        fun createSQLiteKSLDatabase(dbName: String, dbDirectory: Path = dbDir): Database {
            val database = DatabaseFactory.createSQLiteDatabase(dbName, dbDirectory)
            val executed = database.executeScript(dbScriptsDir.resolve("KSL_SQLite.sql"))
            if (!executed) {
                DatabaseIfc.logger.error("Unable to execute KSL_SQLite.sql creation script")
                throw DataAccessException("The execution script KSL_SQLite.sql did not fully execute")
            }
            return database
        }

        /** This method creates the database on disk as configures it to hold KSL simulation data.
         *
         * @param dbName the name of the database
         * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
         * @return an empty embedded Derby database configured to hold KSL simulation results
         */
        fun createEmbeddedDerbyKSLDatabase(dbName: String, dbDirectory: Path = dbDir) : Database {
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
    }
}

