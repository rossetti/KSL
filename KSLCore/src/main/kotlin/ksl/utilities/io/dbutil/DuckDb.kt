package ksl.utilities.io.dbutil

import org.duckdb.DuckDBConnection
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.sql.SQLException
import java.sql.Statement
import javax.sql.DataSource

object DuckDb: EmbeddedDbIfc {
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
        check(SQLiteDb.isDatabase(pathToDb)) { "The path does represent a valid DuckDb database $pathToDb" }
        // must exist and be at path
        val dataSource = createDataSource(pathToDb)
        return Database(dataSource, pathToDb.fileName.toString())
    }
}