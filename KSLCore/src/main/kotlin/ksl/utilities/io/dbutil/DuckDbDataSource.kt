package ksl.utilities.io.dbutil


import ksl.utilities.io.KSLFileUtil
import org.duckdb.DuckDBConnection
import java.io.PrintWriter
import java.sql.DriverManager
import java.sql.SQLFeatureNotSupportedException
import java.sql.Statement
import java.util.*
import java.util.logging.Logger
import javax.sql.DataSource

/**
The client should set the databaseName property appropriately before establishing a connection.
The databaseName property should be a path to the file that represents the database on disk.
If no database name is provided then an in-memory database is created.
 */
class DuckDbDataSource(var databaseName: String = "") : DataSource {

    companion object {
        val PREFIX = "jdbc:duckdb:"

        init {
            Class.forName("org.duckdb.DuckDBDriver")
        }

        /**
         * Validates a URL
         *
         * @param url
         * @return true if the URL is valid, false otherwise
         */
        fun isValidURL(url: String): Boolean {
            return url.lowercase(Locale.getDefault()).startsWith(PREFIX)
        }

        /**
         * Gets the location to the database from a given URL.
         *
         * @param url The URL to extract the location from.
         * @return The location to the database.
         */
        fun extractAddress(url: String): String {
            return url.substring(PREFIX.length)
        }
    }

    private var logger: PrintWriter = KSLFileUtil.createPrintWriter(
        KSLFileUtil.programLaunchDirectory.resolve("logs/DuckDb.log"))
    private var loginTimeout = 1

    val url: String
        get() = PREFIX + databaseName

    override fun getConnection(): DuckDBConnection {
        return getConnection(null, null)
    }

    override fun getConnection(username: String?, password: String?): DuckDBConnection {
        val p: Properties = Properties()
        if (username != null) p["user"] = username
        if (password != null) p["pass"] = password
        return DriverManager.getConnection(url, p) as DuckDBConnection
    }

    fun getReadOnlyConnection(): DuckDBConnection {
        val p: Properties = Properties()
        p["duckdb.read_only"] = "true"
        return DriverManager.getConnection(url, p) as DuckDBConnection
    }

    override fun getLogWriter(): PrintWriter {
        return logger
    }

    override fun setLogWriter(out: PrintWriter) {
        logger = out
    }

    override fun setLoginTimeout(seconds: Int) {
        require(seconds > 0) { "The value of seconds must be > 0 " }
        loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int {
        return loginTimeout
    }

    /**
     * @throws SQLFeatureNotSupportedException
     */
    override fun getParentLogger(): Logger {
        throw SQLFeatureNotSupportedException("getParentLogger");
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> unwrap(iface: Class<T>): T {
        return this as T
    }

    override fun isWrapperFor(iface: Class<*>): Boolean {
        return iface.isInstance(this)
    }

}

fun main() {
//    val ds = DuckDbDataSource("TestDuckDb")
//    val conn = ds.connection
//
//// create a table
//    val stmt: Statement = conn.createStatement()
//    stmt.execute("CREATE TABLE items (item VARCHAR, value DECIMAL(10, 2), count INTEGER)")
//
//// insert two items into the table
//    stmt.execute("INSERT INTO items VALUES ('jeans', 20.0, 1), ('hammer', 42.2, 2)")
//
//    stmt.executeQuery("SELECT * FROM items").use { rs ->
//        while (rs.next()) {
//            System.out.println(rs.getString(1))
//            System.out.println(rs.getInt(3))
//        }
//    }
//    stmt.close()

    val db = DuckDb.createDatabase("TestDuckDb_Database.db")
    db.executeCommand("CREATE TABLE items (item VARCHAR, value DECIMAL(10, 2), count INTEGER)")
    db.executeCommand("INSERT INTO items VALUES ('jeans', 20.0, 1), ('hammer', 42.2, 2)")
    println("Done!")
}