package ksl.utilities.io.dbutil

import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.DatabaseIfc.Companion.executeCommand
import org.duckdb.DuckDBConnection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.Connection
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

    fun exportAsLoadableCSV(dirName: String): Path {
        require(dirName.isNotBlank()) { "dirName must not be blank" }
        val path = KSL.dbDir.resolve(dirName)
        exportAsLoadableCSV(path)
        return path
    }

    fun exportAsLoadableCSV(exportDir: Path = KSL.dbDir){
        val exportCmd = "EXPORT DATABASE '$exportDir'"
        executeCommand(exportCmd)
        DatabaseIfc.logger.info {"DuckDb: Exported database $label to $exportDir"}
    }

    companion object : EmbeddedDbIfc {

        fun importFromLoadableCSV(
            exportedDbDir: Path,
            dbName: String,
            dbDirectory: Path = KSL.dbDir,
            deleteIfExists: Boolean = true
        ) : DuckDb {
            val importCmd = "IMPORT DATABASE '$exportedDbDir'"
            val db = DuckDb(dbName, dbDirectory, deleteIfExists)
            db.getConnection().use { connection: Connection ->
                executeCommand(connection, importCmd)
            }
            DatabaseIfc.logger.info {"DuckDb: Imported database ${db.label} from $exportedDbDir"}
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