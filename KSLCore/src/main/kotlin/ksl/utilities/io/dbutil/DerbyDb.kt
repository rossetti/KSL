package ksl.utilities.io.dbutil

import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import org.apache.derby.jdbc.ClientDataSource
import org.apache.derby.jdbc.EmbeddedDataSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.SQLException
import javax.sql.DataSource

object DerbyDb : EmbeddedDbIfc {

    /**
     * If the database already exists it is deleted
     *
     * @param dbName the name of the embedded database. Must not be null
     * @param dbDir  a path to the directory to hold the database. Must not be null
     * @return the created database
     */
    override fun createDatabase(dbName: String, dbDir: Path): Database {
        val pathToDb = dbDir.resolve(dbName)
        deleteDatabase(pathToDb)
        val ds = createDataSource(pathToDb, create = true)
        val db = Database(ds, dbName)
        db.defaultSchemaName = "APP"
        return db
    }

    /**
     * The database must already exist. It is not created. An exception is thrown if it does not exist.
     *
     * @param dbName the name of the embedded database, must not be null
     * @param dbDir  a path to the directory that holds the database, must not be null
     * @return the created database
     */
    fun openDatabase(dbName: String, dbDir: Path): Database {
        val pathToDb = dbDir.resolve(dbName)
        check(databaseExists(dbName, dbDir)) { "The database does not exist at location $pathToDb" }
        val ds = createDataSource(pathToDb, create = false)
        val db = Database(ds, dbName)
        db.defaultSchemaName = "APP"
        return db
    }

    /**
     * The database must already exist. It is not created. An exception is thrown if it does not exist.
     *
     * @param pathToDb the full path to the directory that is the database, must not be null
     * @return the database
     */
    override fun openDatabase(pathToDb: Path): Database {
        check(databaseExists(pathToDb)) { "The database does not exist at location $pathToDb" }
        val ds = createDataSource(pathToDb, create = false)
        val db = Database(ds, pathToDb.fileName.toString())
        db.defaultSchemaName = "APP"
        return db
    }

    /**
     * @param dbName the name of the database
     * @param dbDir  the directory to the database
     * @return true if it exists false if not
     */
    fun databaseExists(dbName: String, dbDir: Path): Boolean {
        val pathToDb = dbDir.resolve(dbName)
        return databaseExists(pathToDb)
    }

    /**
     * @param fullPath the full path to the database including its name (because derby
     * stores the database in a directory
     * @return true if it exists
     */
    fun databaseExists(fullPath: Path): Boolean {
        return Files.exists(fullPath)
    }

    /**
     * This does not check if the database is shutdown.  It simply removes the
     * database from the file system.  If it doesn't exist, then nothing happens.
     *
     * @param pathToDb the path to the embedded database on disk
     */
    override fun deleteDatabase(pathToDb: Path) {
        val b: Boolean = KSLFileUtil.deleteDirectory(pathToDb.toFile())
        if (b) {
            DatabaseIfc.logger.info { "Deleting directory to derby database $pathToDb" }
        } else {
            DatabaseIfc.logger.info { "Unable to delete directory to derby database $pathToDb" }
        }
    }

    override fun createDataSource(pathToDb: Path): DataSource {
        return createDataSource(pathToDb, null, null, false)
    }

    /**
     * @param dbDir a path to the database, must not be null
     * @param user     a username, can be null
     * @param pWord    a password, can be null
     * @param create   a flag to indicate if the database should be created upon first connection
     * @return the created DataSource
     */
    fun createDataSource(
        dbName: String,
        dbDir: Path = KSL.dbDir,
        user: String? = null,
        pWord: String? = null,
        create: Boolean = false
    ): DataSource {
        val path = dbDir.resolve(dbName)
        return createDataSource(path, user, pWord, create)
    }

    /**
     * @param pathToDb the full path to the database as a string, must not be null
     * @param user   a username, can be null
     * @param pWord  a password, can be null
     * @param create a flag to indicate if the database should be created upon first connection
     * @return the created DataSource
     */
    fun createDataSource(
        pathToDb: Path,
        user: String? = null,
        pWord: String? = null,
        create: Boolean = false
    ): DataSource {
        val ds = EmbeddedDataSource()
        ds.databaseName = pathToDb.toString()
        if (user != null) ds.user = user
        if (pWord != null) ds.password = pWord
        if (create) {
            //val path = Paths.get(dbName)
            DatabaseIfc.logger.info { "Create option is on for $pathToDb" }
            if (databaseExists(pathToDb)) {
                DatabaseIfc.logger.info { "Database already exists at location $pathToDb" }
                deleteDatabase(pathToDb)
            }
            ds.createDatabase = "create"
        }
        DatabaseIfc.logger.info { "Created an embedded Derby data source for $pathToDb" }
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
    fun shutDownDatabase(pathToDb: Path, user: String? = null, pWord: String? = null): Boolean {
        val dataSource = shutDownDataSource(pathToDb, user, pWord)
        try {
            dataSource.connection.use { connection -> }
        } catch (e: SQLException) {
            if ("08006" == e.sqlState) {
                DatabaseIfc.logger.info { "Derby shutdown succeeded. SQLState=${e.sqlState}" }
                return true
            }
            DatabaseIfc.logger.error(e) { "Derby shutdown failed" }
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
    fun shutDownDataSource(pathToDb: Path, user: String? = null, pWord: String? = null): DataSource {
        return shutDownDataSource(pathToDb.toString(), user, pWord)
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
    fun shutDownDataSource(dbName: String, user: String? = null, pWord: String? = null): DataSource {
        val ds = EmbeddedDataSource()
        ds.databaseName = dbName
        if (user != null) ds.user = user
        if (pWord != null) ds.password = pWord
        ds.shutdownDatabase = "shutdown"
        DatabaseIfc.logger.info { "Created an embedded Derby shutdown data source for $dbName" }
        return ds
    }

    /**
     * @param dbName the path to the database, must not be null
     * @param user   a username, can be null
     * @param pWord  a password, can be null
     * @param create a flag to indicate if the database should be created upon first connection
     * @return the created DataSource
     */
    fun createClientDataSourceWithLocalHost(
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
        DatabaseIfc.logger.info { "Created a Derby client data source for $dbName" }
        return ds
    }

    /**
     * Duplicates the database into a new database with the supplied name and directory.
     * Assumes that the source database has no active connections and performs a file system copy
     *
     * @param sourceDB  the path to the database that needs duplicating
     * @param dupName   the name of the duplicate database
     * @param directory the directory to place the database in
     */
    fun copyDatabase(sourceDB: Path, dupName: String, directory: Path) {
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
    fun copyDatabase(ds: DataSource, sourceDB: Path, dupName: String, directory: Path) {
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
    override fun isDatabase(path: Path): Boolean {
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

}