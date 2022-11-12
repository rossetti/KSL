package ksl.utilities.dbutil

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import org.apache.derby.jdbc.ClientDataSource
import org.apache.derby.jdbc.EmbeddedDataSource
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection
import org.sqlite.SQLiteDataSource
import org.sqlite.SQLiteOpenMode
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.SQLException
import java.util.*
import javax.sql.DataSource

object DatabaseFactory {

    /**
     * If the database already exists it is deleted
     *
     * @param dbName the name of the embedded database. Must not be null
     * @param dbDir  a path to the directory to hold the database. Must not be null
     * @return the created database
     */
    fun createEmbeddedDerbyDatabase(dbName: String, dbDir: Path = KSL.dbDir): DatabaseIfc {
        val pathToDb = dbDir.resolve(dbName)
        deleteEmbeddedDerbyDatabase(pathToDb)
        val ds = createEmbeddedDerbyDataSource(pathToDb, create = true)
        return DatabaseImp(ds, dbName)
    }

    /**
     * The database must already exist. It is not created. An exception is thrown if it does not exist.
     *
     * @param dbName the name of the embedded database, must not be null
     * @param dbDir  a path to the directory that holds the database, must not be null
     * @return the created database
     */
    fun getEmbeddedDerbyDatabase(dbName: String, dbDir: Path = KSL.dbDir): DatabaseIfc {
        val pathToDb = dbDir.resolve(dbName)
        check(isEmbeddedDerbyDatabaseExists(dbName, dbDir)) { "The database does not exist at location $pathToDb" }
        val ds = createEmbeddedDerbyDataSource(pathToDb, create = false)
        return DatabaseImp(ds, dbName)
    }

    /**
     * The database must already exist. It is not created. An exception is thrown if it does not exist.
     *
     * @param pathToDb the full path to the directory that is the database, must not be null
     * @return the database
     */
    fun getEmbeddedDerbyDatabase(pathToDb: Path): DatabaseIfc {
        check(isEmbeddedDerbyDatabaseExists(pathToDb)) { "The database does not exist at location $pathToDb" }
        val ds = createEmbeddedDerbyDataSource(pathToDb, create = false)
        return DatabaseImp(ds, pathToDb.fileName.toString())
    }

    /**
     * @param dbName the name of the database
     * @param dbDir  the directory to the database
     * @return true if it exists false if not
     */
    fun isEmbeddedDerbyDatabaseExists(dbName: String, dbDir: Path = KSL.dbDir): Boolean {
        val pathToDb = dbDir.resolve(dbName)
        return isEmbeddedDerbyDatabaseExists(pathToDb)
    }

    /**
     * @param fullPath the full path to the database including its name (because derby
     * stores the database in a directory
     * @return true if it exists
     */
    fun isEmbeddedDerbyDatabaseExists(fullPath: Path): Boolean {
        return Files.exists(fullPath)
    }

    /**
     * This does not check if the database is shutdown.  It simply removes the
     * database from the file system.  If it doesn't exist, then nothing happens.
     *
     * @param pathToDb the path to the embedded database on disk
     */
    fun deleteEmbeddedDerbyDatabase(pathToDb: Path) {
        val b: Boolean = KSLFileUtil.deleteDirectory(pathToDb.toFile())
        if (b) {
            DatabaseIfc.logger.info("Deleting directory to derby database {}", pathToDb)
        } else {
            DatabaseIfc.logger.error("Unable to delete directory to derby database {}", pathToDb)
        }
    }

    /**
     * @param pathToDb a path to the database, must not be null
     * @param user     a username, can be null
     * @param pWord    a password, can be null
     * @param create   a flag to indicate if the database should be created upon first connection
     * @return the created DataSource
     */
    fun createEmbeddedDerbyDataSource(
        pathToDb: Path,
        user: String? = null,
        pWord: String? = null,
        create: Boolean = false
    ): DataSource {
        return createEmbeddedDerbyDataSource(pathToDb.toString(), user, pWord, create)
    }

    /**
     * @param dbName the path to the database, must not be null
     * @param user   a username, can be null
     * @param pWord  a password, can be null
     * @param create a flag to indicate if the database should be created upon first connection
     * @return the created DataSource
     */
    fun createEmbeddedDerbyDataSource(
        dbName: String,
        user: String? = null,
        pWord: String? = null,
        create: Boolean = false
    ): DataSource {
        val ds = EmbeddedDataSource()
        ds.databaseName = dbName
        if (user != null) ds.user = user
        if (pWord != null) ds.password = pWord
        if (create) {
            val path = Paths.get(dbName)
            DatabaseIfc.logger.info("Create option is on for {}", dbName)
            if (isEmbeddedDerbyDatabaseExists(path)) {
                DatabaseIfc.logger.info("Database already exists at location {}", dbName)
                deleteEmbeddedDerbyDatabase(path)
            }
            ds.createDatabase = "create"
        }
        DatabaseIfc.logger.info("Created an embedded Derby data source for {}", dbName)
        return ds
    }

    /**
     * Sends a shutdown connection to the database.
     *
     * @param pathToDb a path to the database, must not be null
     * @param user     a username, can be null
     * @param pWord    a password, can be null
     * @return true if successfully shutdown
     */
    fun shutDownEmbeddedDerbyDatabase(pathToDb: Path, user: String? = null, pWord: String? = null): Boolean {
        val dataSource = shutDownEmbeddedDerbyDataSource(pathToDb, user, pWord)
        try {
            dataSource.connection.use { connection -> }
        } catch (e: SQLException) {
            if ("08006" == e.sqlState) {
                DatabaseIfc.logger.info("Derby shutdown succeeded. SQLState={}", e.sqlState)
                return true
            }
            DatabaseIfc.logger.error("Derby shutdown failed", e)
        }
        return false
    }

    /**
     * Creates a data source that can be used to shut down an embedded derby database upon
     * first connection.
     *
     * @param pathToDb a path to the database, must not be null
     * @param user     a username, can be null
     * @param pWord    a password, can be null
     * @return the created DataSource
     */
    fun shutDownEmbeddedDerbyDataSource(pathToDb: Path, user: String? = null, pWord: String? = null): DataSource {
        return shutDownEmbeddedDerbyDataSource(pathToDb.toString(), user, pWord)
    }

    /**
     * Creates a data source that can be used to shut down an embedded derby database upon
     * first connection.
     *
     * @param dbName the path to the database, must not be null
     * @param user   a username, can be null
     * @param pWord  a password, can be null
     * @return the created DataSource
     */
    fun shutDownEmbeddedDerbyDataSource(dbName: String, user: String? = null, pWord: String? = null): DataSource {
        val ds = EmbeddedDataSource()
        ds.databaseName = dbName
        if (user != null) ds.user = user
        if (pWord != null) ds.password = pWord
        ds.shutdownDatabase = "shutdown"
        DatabaseIfc.logger.info("Created an embedded Derby shutdown data source for {}", dbName)
        return ds
    }

    /**
     * @param dbName the path to the database, must not be null
     * @param user   a username, can be null
     * @param pWord  a password, can be null
     * @param create a flag to indicate if the database should be created upon first connection
     * @return the created DataSource
     */
    fun createClientDerbyDataSourceWithLocalHost(
        dbName: String,
        user: String?,
        pWord: String?,
        create: Boolean
    ): DataSource {
        val ds = ClientDataSource()
        ds.databaseName = dbName
        ds.serverName = "localhost"
        ds.portNumber = 1527
        if (user != null) ds.user = user
        if (pWord != null) ds.password = pWord
        if (create) {
            ds.createDatabase = "create"
        }
        DatabaseIfc.logger.info("Created a Derby client data source for {}", dbName)
        return ds
    }

    /**
     * @param dbName the name of the database, must not be null
     * @param user   the user
     * @param pWord  the password
     * @return the DataSource for getting connections
     */
    fun getPostGresDataSourceWithLocalHost(
        dbName: String,
        user: String = "",
        pWord: String = "",
        portNumber: Int = 5432
    ): DataSource {
        return getPostGresDataSource("localhost", dbName, user, pWord, portNumber)
    }

    /**
     * @param dbServerName the name of the database server, must not be null
     * @param dbName       the name of the database, must not be null
     * @param user         the user
     * @param pWord        the password
     * @param portNumber   a valid port number
     * @return the DataSource for getting connections
     */
    fun getPostGresDataSource(
        dbServerName: String = "localhost",
        dbName: String,
        user: String = "",
        pWord: String = "",
        portNumber: Int = 5432
    ): DataSource {
        val props = makePostGresProperties(dbServerName, dbName, user, pWord, portNumber)
        return getDataSource(props)
    }

    /**
     * @param dbServerName the database server name, must not be null
     * @param dbName       the database name, must not be null
     * @param user         the user, must not be null
     * @param pWord        the password, must not be null
     * @return the Properties instance
     */
    fun makePostGresProperties(
        dbServerName: String,
        dbName: String,
        user: String = "",
        pWord: String = "",
        portNumber: Int = 5432
    ): Properties {
        val props = Properties()
        props.setProperty("dataSourceClassName", "org.postgresql.ds.PGSimpleDataSource")
        props.setProperty("dataSource.user", user)
        props.setProperty("dataSource.password", pWord)
        props.setProperty("dataSource.databaseName", dbName)
        props.setProperty("dataSource.serverName", dbServerName)
        props.setProperty("dataSource.portNumber", portNumber.toString())
        return props
    }

    /**
     * Assumes that the properties are appropriately configured to create a DataSource
     * via  HikariCP
     *
     * @param properties the properties
     * @return a pooled connection DataSource
     */
    fun getDataSource(properties: Properties): DataSource {
        val config = HikariConfig(properties)
        return HikariDataSource(config)
    }

    /**
     * @param pathToPropertiesFile must not be null
     * @return a DataSource for making a database
     */
    fun getDataSource(pathToPropertiesFile: Path): DataSource {
        val config = HikariConfig(pathToPropertiesFile.toString())
        return HikariDataSource(config)
    }

    /**
     * Duplicates the database into a new database with the supplied name and directory.
     * Assumes that the source database has no active connections and performs a file system copy
     *
     * @param sourceDB  the path to the database that needs duplicating
     * @param dupName   the name of the duplicate database
     * @param directory the directory to place the database in
     */
    fun copyEmbeddedDerbyDatabase(sourceDB: Path, dupName: String, directory: Path) {
        require(Files.isDirectory(directory)) { "The directory path was not a directory!" }
        require(!Files.exists(directory.resolve(dupName))) { "A database with the supplied name already exists in the directory! db name = $dupName" }
        val target = directory.resolve(dupName).toFile()
        val source = sourceDB.toFile()
        KSLFileUtil.copyDirectory(source, target)
    }

    /**
     * Uses an active database connection and derby system commands to freeze the database,
     * uses system OS commands to copy the database, and then unfreezes the database.  The duplicate name
     * and directory path must not already exist
     *
     * @param ds        a DataSource to the embedded derby database, obviously it must point to the derby database
     * @param sourceDB  the path to the source database
     * @param dupName   the name of the duplicate database, obviously it must reference the same database that is
     * referenced by the DataSource
     * @param directory the directory to place the database in
     * @throws SQLException thrown if the derby commands fail
     * @throws IOException  thrown if the system file copy commands fail
     */
    fun copyEmbeddedDerbyDatabase(ds: DataSource, sourceDB: Path, dupName: String, directory: Path) {
        require(Files.isDirectory(directory)) { "The directory path was not a directory!" }
        require(!Files.exists(directory.resolve(dupName))) { "A database with the supplied name already exists in the directory! db name = $dupName" }
        val s = ds.connection.createStatement()
        // freeze the database
        s.executeUpdate("CALL SYSCS_UTIL.SYSCS_FREEZE_DATABASE()")
        //copy the database directory during this interval
        // translate paths to files
        val target = directory.resolve(dupName).toFile()
        val source = sourceDB.toFile()
        KSLFileUtil.copyDirectory(source, target)
        s.executeUpdate("CALL SYSCS_UTIL.SYSCS_UNFREEZE_DATABASE()")
        s.close()
    }

    /**
     * There is no way to guarantee with 100 percent certainty that the path
     * is in fact an embedded derby database because someone could be faking
     * the directory structure.  The database directory of an embedded derby database
     * must have a service.properties file, a log directory, and a seg0 directory.
     * If these exist and the supplied path is a directory, then the method
     * returns true.
     *
     * @param path the path to check, must not be null
     * @return true if it could be an embedded derby database
     */
    fun isEmbeddedDerbyDatabase(path: Path): Boolean {
        // the path itself must be a directory not a file
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            // not a directory cannot be an embedded derby database
            return false
        }
        // the path is a directory, could be an embedded derby database
        // it must have log directory, a seg0 directory and a services.properties file
        val log = path.resolve("log")
        // log must exist and be a directory
        if (!Files.isDirectory(log, LinkOption.NOFOLLOW_LINKS)) {
            // no log directory, can't be embedded derby db
            return false
        }
        // seq0 must exist and be a directory
        val seg0 = path.resolve("seg0")
        if (!Files.isDirectory(seg0, LinkOption.NOFOLLOW_LINKS)) {
            // no seg0 directory, can't be embedded derby db
            return false
        }
        // service.properties must exist and be a file
        val sp = path.resolve("service.properties")
        return Files.isRegularFile(sp, LinkOption.NOFOLLOW_LINKS)
        // likely to be be embedded derby db
    }

    /**
     * A convenience method for those that use File instead of Path.
     * Calls isEmbeddedDerbyDatabase(file.toPath())
     *
     * @param file the file to check, must not be null
     * @return true if it could be an embedded derby database
     */
    fun isEmbeddedDerbyDatabase(file: File): Boolean {
        return isEmbeddedDerbyDatabase(file.toPath())
    }

    /**
     * Checks if a file is a valid SQLite database
     * Strategy:
     * - path must reference a regular file
     * - if file exists, check if it is larger than 100 bytes (SQLite header size)
     * - then check if a database operation works
     *
     * @param pathToFile the path to the database file, must not be null
     * @return true if the path points to a valid SQLite database file
     */
    fun isSQLiteDatabase(pathToFile: Path): Boolean {
        // the path itself must be a directory or a file, i.e. it must exist
        if (!Files.exists(pathToFile)) {
            return false
        }
        // now the thing exists, check if it is a regular file
        if (!Files.isRegularFile(pathToFile, LinkOption.NOFOLLOW_LINKS)) {
            // if it is not a regular file, then it cannot be an SQLite database
            return false
        }
        // it must be a regular file, need to check its size
        // now try a database operation specific to SQLite
        val ds = SQLiteDataSource()
        ds.databaseName = pathToFile.toString()
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
    fun deleteSQLiteDatabase(pathToDb: Path) {
        try {
            Files.deleteIfExists(pathToDb)
            DatabaseIfc.logger.info("Deleting SQLite database {}", pathToDb)
        } catch (e: IOException) {
            DatabaseIfc.logger.error("Unable to delete SQLite database {}", pathToDb)
            throw DataAccessException("Unable to delete SQLite database$pathToDb")
        }
    }

    /**
     * @param pathToDb the path to the database file, must not be null
     * @return the data source
     */
    fun createSQLiteDataSource(pathToDb: Path): SQLiteDataSource {
        val ds = SQLiteDataSource()
        ds.databaseName = pathToDb.toString()
        // there is a bug in the SQLiteDataSource class, it will not work without setting the URL
        // even though the name of the database has been set.
        ds.url = "jdbc:sqlite:$pathToDb"
        ds.config = createDefaultSQLiteConfiguration()
        DatabaseIfc.logger.info("Created SQLite data source {}", pathToDb)
        return ds
    }

    /** Creates a recommended configuration that has been tested for
     * performance.  https://ericdraken.com/sqlite-performance-testing/
     *
     * @param readOnly indicates read only mode
     * @return the configuration
     */
    fun createDefaultSQLiteConfiguration(readOnly: Boolean = false): SQLiteConfig {
        val config = SQLiteConfig()
        val cacheSize = 1000
        val pageSize = 8192
        config.setReadOnly(readOnly)
        config.setTempStore(SQLiteConfig.TempStore.MEMORY) // Hold indices in memory
        config.setCacheSize(cacheSize)
        config.setPageSize(pageSize)
        config.setJounalSizeLimit(cacheSize * pageSize)
        config.setOpenMode(SQLiteOpenMode.NOMUTEX)
        config.setLockingMode(SQLiteConfig.LockingMode.NORMAL)
        config.transactionMode = SQLiteConfig.TransactionMode.IMMEDIATE
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        // If read-only, then use the existing journal, if any
        // removed to prevent creation of shm and wal files
//        if (!readOnly) {
//            config.setJournalMode(SQLiteConfig.JournalMode.WAL);
//        }
        return config
    }

    /**
     * @param dbLabel    a label for the database
     * @param dataSource the data source for connections
     * @return the created database
     */
    fun createSQLiteDatabase(dbLabel: String, dataSource: SQLiteDataSource): DatabaseIfc {
        return DatabaseImp(dataSource, dbLabel)
    }

    /**
     * If the database already exists it is deleted
     *
     * @param dbName the name of the SQLite database. Must not be null
     * @param dbDir  a path to the directory to hold the database. Must not be null
     * @return the created database
     */
    fun createSQLiteDatabase(dbName: String, dbDir: Path = KSL.dbDir): DatabaseIfc {
        val pathToDb = dbDir.resolve(dbName)
        // if it exists, delete it
        if (Files.exists(pathToDb)) {
            deleteSQLiteDatabase(pathToDb)
        }
        val ds: SQLiteDataSource = createSQLiteDataSource(pathToDb)
        return createSQLiteDatabase(dbName, ds)
    }

    /**
     * The database file must already exist at the path
     *
     * @param pathToDb the path to the database file, must not be null
     * @param readOnly true indicates that the database is read only
     * @return the database
     */
    fun getSQLiteDatabase(pathToDb: Path, readOnly: Boolean = false): DatabaseIfc {
        check(isSQLiteDatabase(pathToDb)) { "The path does represent a valid SQLite database $pathToDb" }
        // must exist and be at path
        val dataSource: SQLiteDataSource = createSQLiteDataSource(pathToDb)
        dataSource.setReadOnly(readOnly)
        return DatabaseImp(dataSource, pathToDb.fileName.toString())
    }

    /**
     * The database file must already exist within the JSLDatabase.dbDir directory
     * It is opened for reading and writing.
     *
     * @param fileName the name of database file, must not be null
     * @return the database
     */
    fun getSQLiteDatabase(fileName: String): DatabaseIfc {
        return getSQLiteDatabase(KSL.dbDir.resolve(fileName))
    }
}