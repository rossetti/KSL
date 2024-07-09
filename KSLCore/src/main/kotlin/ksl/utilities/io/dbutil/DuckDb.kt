package ksl.utilities.io.dbutil

import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.DatabaseIfc.Companion.executeCommand
import org.duckdb.DuckDBConnection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource

/**
 *  Facilitates the creation of a database backed by DuckDb. The database
 *  will be empty.
 *
 * @param dbName the name of the database
 * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
 * @param deleteIfExists If true, an existing database in the supplied directory with
 * the same name will be deleted and an empty database will be constructed.
 * @return a DuckDb configured database
 */
@Suppress("LeakingThis")
class DuckDb(
    dbName: String,
    dbDirectory: Path = KSL.dbDir,
    deleteIfExists: Boolean = true
) : Database(dataSource = createDataSource(dbDirectory.resolve(dbName)), label = dbName) {

    init {
        if (deleteIfExists) {
            val pathToDb = dbDirectory.resolve(dbName)
            // if it exists, delete it
            if (Files.exists(pathToDb)) {
                deleteDatabase(pathToDb)
            }
        }
        this.defaultSchemaName = "main"
    }

    /** This constructs a simple DuckDb database on disk.
     * The database will contain empty tables based on the table definitions.
     *  If the database already exists on disk, it will be deleted and recreated.
     *
     * @param tableDefinitions an example set of table definitions based on DbTableData specifications
     * @param dbName the name of the database
     * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
     * @param deleteIfExists If true, an existing database in the supplied directory with
     * the same name will be deleted and an empty database will be constructed.
     * @return an embedded DuckDb database
     */
    constructor(
        tableDefinitions: Set<DbTableData>,
        dbName: String,
        dbDirectory: Path = KSL.dbDir,
        deleteIfExists: Boolean = true
    ) : this(dbName, dbDirectory, deleteIfExists) {
        createSimpleDbTables(tableDefinitions)
    }

    /**
     *  Applies DuckDb's [summarize](https://duckdb.org/docs/guides/meta/summarize.html)
     *  query to the table/view
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the table
     * @param tableName      a string representing the unqualified name of the table
     * @return the summary result set
     */
    fun summarize(tableName: String, schemaName: String? = defaultSchemaName): ResultSet? {
        require(
            containsTable(
                tableName,
                schemaName
            )
        ) { "The table/view $tableName does not exist in schema $schemaName the database." }
        val sql = if (schemaName == null) {
            "SUMMARIZE SELECT * FROM $tableName"
        } else {
            "SUMMARIZE SELECT * FROM ${schemaName}.${tableName}"
        }
        return fetchOpenResultSet(sql)
    }

    /**
     *  Exports the database to a directory with loadable default CSV
     *  format. See DuckDb [documentation](https://duckdb.org/docs/sql/statements/export).
     *  @param dirName the name of the export directory within KSL.dbDir
     */
    fun exportAsLoadableCSV(dirName: String): Path {
        require(dirName.isNotBlank()) { "dirName must not be blank" }
        val path = KSL.dbDir.resolve(dirName)
        exportAsLoadableCSV(path)
        return path
    }

    /**
     *  Exports the database to a directory with loadable default CSV
     *  format. See DuckDb [documentation](https://duckdb.org/docs/sql/statements/export).
     *  @param exportDir the path to the export directory
     */
    fun exportAsLoadableCSV(exportDir: Path = KSL.dbDir) {
        val exportCmd = "EXPORT DATABASE '$exportDir'"
        executeCommand(exportCmd)
        DatabaseIfc.logger.info { "DuckDb: Exported database $label to $exportDir" }
    }


    /**
     *  Exports the database to a directory with loadable default Parquet format.
     *  See DuckDb [documentation](https://duckdb.org/docs/sql/statements/export).
     *  @param fileName the name of the directory within KSL.dbDir
     */
    fun exportAsLoadableParquetFiles(fileName: String): Path {
        require(fileName.isNotBlank()) { "fileName must not be blank" }
        val path = KSL.dbDir.resolve(fileName)
        exportAsLoadableParquetFiles(path)
        return path
    }

    /**
     *  Exports the database to a directory with loadable default Parquet file
     *  format. See DuckDb [documentation](https://duckdb.org/docs/sql/statements/export).
     *  @param exportDir the path to the export directory
     */
    fun exportAsLoadableParquetFiles(exportDir: Path = KSL.dbDir) {
        val exportCmd = "EXPORT DATABASE '$exportDir' (FORMAT PARQUET)"
        executeCommand(exportCmd)
        DatabaseIfc.logger.info { "DuckDb: Exported database $label to $exportDir" }
    }

    companion object : EmbeddedDbIfc {

        /**
         *  Facilitates the creation of a database backed by DuckDb. The database
         *  will be loaded based on the SQLite database specified via the path.
         *
         * @param pathToSQLiteFile the path to the file holding the SQLite database
         * @param dbName the name of the DuckDb database
         * @param dbDirectory the directory containing the DuckDb database. By default, KSL.dbDir.
         * @param deleteIfExists If true, an existing database in the supplied directory with
         * the same name will be deleted and an empty database will be constructed. The default is true.
         * @return a DuckDb configured database
         */
        fun convertFromSQLiteToDuckDb(
            pathToSQLiteFile: Path,
            dbName: String,
            dbDirectory: Path = KSL.dbDir,
            deleteIfExists: Boolean = true
        ) : DuckDb {
            require(SQLiteDb.isDatabase(pathToSQLiteFile)){"The file was not an SQLite database"}
            // create the DuckDb database
            val db = DuckDb(dbName, dbDirectory, deleteIfExists)
            // first attach the SQLite file
            val attachSQLite = "ATTACH '$pathToSQLiteFile' as sqlite_db (TYPE SQLITE)"
            // copy from the SQLite database
            val copyCmd = "COPY FROM DATABASE sqlite_db to $dbName"
            val detachSQLite = "DETACH DATABASE IF EXISTS sqlite_db"
            val cmdList = listOf(attachSQLite, copyCmd, detachSQLite)
            db.executeCommands(cmdList)
            return db
        }

        /**
         *  Facilitates the creation of a database backed by DuckDb. The database
         *  will be loaded based on the load scripts found in the exported database
         *  directory
         *
         * @param exportedDbDir the directory holding the import data and scripts
         * @param dbName the name of the database
         * @param dbDirectory the directory containing the database. By default, KSL.dbDir.
         * @param deleteIfExists If true, an existing database in the supplied directory with
         * the same name will be deleted and an empty database will be constructed.
         * @return a DuckDb configured database
         */
        fun importFromLoadableCSV(
            exportedDbDir: Path,
            dbName: String,
            dbDirectory: Path = KSL.dbDir,
            deleteIfExists: Boolean = true
        ): DuckDb {
            val importCmd = "IMPORT DATABASE '$exportedDbDir'"
            val db = DuckDb(dbName, dbDirectory, deleteIfExists)
            db.getConnection().use { connection: Connection ->
                executeCommand(connection, importCmd)
            }
            DatabaseIfc.logger.info { "DuckDb: Imported database ${db.label} from $exportedDbDir" }
            return db
        }

        /**
         * Checks if a file is a valid DuckDb database
         * Strategy:
         * - path must reference a regular file
         * - if file exists
         * - then check if a database operation works
         *
         * @param path the path to the database file, must not be null
         * @return true if the path points to a valid DuckDb database file
         */
        override fun isDatabase(path: Path): Boolean {
            // the path itself must be a directory or a file, i.e. it must exist
            if (!Files.exists(path)) {
                return false
            }
            // now the thing exists, check if it is a regular file
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                // if it is not a regular file, then it cannot be an DuckDb database
                return false
            }
            // it must be a regular file, need to check its size
            // now try a database operation specific to SQLite
            val ds = DuckDbDataSource(path.toString())
            try {
                val conn: DuckDBConnection = ds.getConnection("", "")
                val stmt: Statement = conn.createStatement()
                stmt.execute("PRAGMA version")
                stmt.close()
            } catch (exception: SQLException) {
                return false
            }
            return true
        }

        /**
         * Deletes a DuckDb database
         * Strategy:
         * - simply deletes the file at the end of the path
         * - it may or not be a valid DuckDb database
         *
         * @param pathToDb the path to the database file, must not be null
         */
        override fun deleteDatabase(pathToDb: Path) {
            try {
                Files.deleteIfExists(pathToDb)
                DatabaseIfc.logger.info { "Deleting DuckDb database $pathToDb" }
            } catch (e: IOException) {
                DatabaseIfc.logger.error { "Unable to delete DuckDb database $pathToDb" }
                throw DataAccessException("Unable to delete DuckDb database$pathToDb")
            }
        }

        /**
         * @param pathToDb the path to the database file, must not be null
         * @return the data source
         */
        override fun createDataSource(pathToDb: Path): DuckDbDataSource {
            val ds = DuckDbDataSource(pathToDb.toString())
            DatabaseIfc.logger.info { "Created DuckDb data source $pathToDb" }
            return ds
        }

        /**
         * If the database already exists it is deleted
         *
         * @param dbName the name of the DuckDb database. Must not be null
         * @param dbDir  a path to the directory to hold the database. Must not be null
         * @return the created database
         */
        override fun createDatabase(dbName: String, dbDir: Path): Database {
            val pathToDb = dbDir.resolve(dbName)
            // if it exists, delete it
            if (Files.exists(pathToDb)) {
                deleteDatabase(pathToDb)
            }
            val ds: DataSource = createDataSource(pathToDb)
            return Database(ds, dbName)
        }

        /**
         * The database file must already exist at the path
         *
         * @param pathToDb the path to the database file, must not be null
         * @return the database
         */
        override fun openDatabase(pathToDb: Path): Database {
            check(isDatabase(pathToDb)) { "The path does represent a valid DuckDb database $pathToDb" }
            // must exist and be at path
            val dataSource = createDataSource(pathToDb)
            return Database(dataSource, pathToDb.fileName.toString())
        }
    }
}