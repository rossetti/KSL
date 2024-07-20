package ksl.utilities.io.dbutil

import ksl.utilities.io.KSL
import java.io.File
import java.nio.file.Path
import javax.sql.DataSource

enum class EmbeddedDbType {
    SQLITE, DERBY, DUCKDB
}

interface EmbeddedDbIfc {

    /**
     * Checks if a file is an embedded database
     *
     * @param path the path to the database file, must not be null
     * @return true if the path points to a valid SQLite database file
     */
    fun isDatabase(path: Path): Boolean

    /**
     * A convenience method for those that use File instead of Path.
     * Calls isEmbeddedDerbyDatabase(file.toPath())
     *
     * @param file the file to check, must not be null
     * @return true if it could be an embedded derby database
     */
    fun isDatabase(file: File): Boolean {
        return DerbyDb.isDatabase(file.toPath())
    }

    /**
     * Deletes the embedded database
     *
     * @param pathToDb the path to the database file, must not be null
     */
    fun deleteDatabase(pathToDb: Path)

    /**
     * @param pathToDb the path to the database file, must not be null
     * @return the data source
     */
    fun createDataSource(pathToDb: Path): DataSource

    /**
     * If the database already exists it is deleted and recreated
     *
     * @param dbName the name of the embedded database. Must not be null
     * @param dbDir  a path to the directory to hold the database. Must not be null
     * @return the created database
     */
    fun createDatabase(dbName: String, dbDir: Path = KSL.dbDir): Database

    /**
     * The database file must already exist at the path
     *
     * @param pathToDb the path to the database file, must not be null
     * @return the database
     */
    fun openDatabase(pathToDb: Path): Database

    /**
     * The database file must already exist within the KSLDatabase.dbDir directory
     * It is opened for reading and writing.
     *
     * @param fileName the name of database file, must not be null
     * @return the database
     */
    fun openDatabase(fileName: String): Database {
        return openDatabase(KSL.dbDir.resolve(fileName))
    }

}