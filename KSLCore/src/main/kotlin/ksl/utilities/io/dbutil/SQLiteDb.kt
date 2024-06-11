package ksl.utilities.io.dbutil

import ksl.utilities.io.KSL
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteDataSource
import org.sqlite.SQLiteOpenMode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.SQLException
import javax.sql.DataSource

object SQLiteDb : EmbeddedDbIfc {

    /**
     * Checks if a file is a valid SQLite database
     * Strategy:
     * - path must reference a regular file
     * - if file exists, check if it is larger than 100 bytes (SQLite header size)
     * - then check if a database operation works
     *
     * @param path the path to the database file, must not be null
     * @return true if the path points to a valid SQLite database file
     */
    override fun isDatabase(path: Path): Boolean {
        // the path itself must be a directory or a file, i.e. it must exist
        if (!Files.exists(path)) {
            return false
        }
        // now the thing exists, check if it is a regular file
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            // if it is not a regular file, then it cannot be an SQLite database
            return false
        }
        // it must be a regular file, need to check its size
        // now try a database operation specific to SQLite
        val ds = SQLiteDataSource()
        ds.databaseName = path.toString()
        ds.setReadOnly(true)
        try {
            val sqLiteConnection: SQLiteConnection = ds.getConnection("", "")
            sqLiteConnection.libversion()
            sqLiteConnection.close()
        } catch (exception: SQLException) {
            return false
        }
        return true
    }

    /**
     * Deletes a SQLite database
     * Strategy:
     * - simply deletes the file at the end of the path
     * - it may or not be a valid SQLiteDatabase
     *
     * @param pathToDb the path to the database file, must not be null
     */
    override fun deleteDatabase(pathToDb: Path) {
        try {
            Files.deleteIfExists(pathToDb)
            DatabaseIfc.logger.info { "Deleting SQLite database $pathToDb" }
        } catch (e: IOException) {
            DatabaseIfc.logger.error { "Unable to delete SQLite database $pathToDb" }
            throw DataAccessException("Unable to delete SQLite database$pathToDb")
        }
    }

    /**
     * @param pathToDb the path to the database file, must not be null
     * @return the data source
     */
    override fun createDataSource(pathToDb: Path): DataSource {
        val ds = SQLiteDataSource()
        ds.databaseName = pathToDb.toString()
        // there is a bug in the SQLiteDataSource class, it will not work without setting the URL
        // even though the name of the database has been set.
        ds.url = "jdbc:sqlite:$pathToDb"
        ds.config = createDefaultConfiguration()
        DatabaseIfc.logger.info { "Created SQLite data source $pathToDb" }
        return ds
    }

    /** Creates a recommended configuration that has been tested for
     * performance.  https://ericdraken.com/sqlite-performance-testing/
     *
     * @param readOnly indicates read only mode
     * @return the configuration
     */
    fun createDefaultConfiguration(readOnly: Boolean = false): SQLiteConfig {
        val config = SQLiteConfig()
        val cacheSize = 1000
        val pageSize = 8192
        config.setReadOnly(readOnly)
        config.setTempStore(SQLiteConfig.TempStore.MEMORY) // Hold indices in memory
        config.setCacheSize(cacheSize)
        config.setPageSize(pageSize)
        config.setJournalSizeLimit(cacheSize * pageSize)
        config.setOpenMode(SQLiteOpenMode.NOMUTEX)
        config.setLockingMode(SQLiteConfig.LockingMode.NORMAL)
        config.transactionMode = SQLiteConfig.TransactionMode.IMMEDIATE
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        config.enforceForeignKeys(true)
        // If read-only, then use the existing journal, if any
        // removed to prevent creation of shm and wal files
//        if (!readOnly) {
//            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
//        }
        return config
    }

    /**
     * If the database already exists it is deleted
     *
     * @param dbName the name of the SQLite database. Must not be null
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
    fun openDatabaseReadOnly(pathToDb: Path): Database {
        check(isDatabase(pathToDb)) { "The path does represent a valid SQLite database $pathToDb" }
        // must exist and be at path
        val dataSource: SQLiteDataSource = createDataSource(pathToDb) as SQLiteDataSource
        dataSource.setReadOnly(true)
        return Database(dataSource, pathToDb.fileName.toString())
    }

    /**
     * The database file must already exist at the path
     *
     * @param pathToDb the path to the database file, must not be null
     * @return the database
     */
    override fun openDatabase(pathToDb: Path): Database {
        check(isDatabase(pathToDb)) { "The path does represent a valid SQLite database $pathToDb" }
        // must exist and be at path
        val dataSource: SQLiteDataSource = createDataSource(pathToDb) as SQLiteDataSource
        return Database(dataSource, pathToDb.fileName.toString())
    }

}