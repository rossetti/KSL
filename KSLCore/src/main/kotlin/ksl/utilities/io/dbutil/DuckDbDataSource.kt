package ksl.utilities.io.dbutil


import ksl.utilities.io.KSLFileUtil
import org.apache.commons.compress.harmony.pack200.PackingUtils.config
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLFeatureNotSupportedException
import java.util.*
import java.util.logging.Logger
import javax.sql.DataSource


class DuckDbDataSource : DataSource {

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

    private var logger: PrintWriter = KSLFileUtil.createPrintWriter("DuckDb.log")
    private var loginTimeout = 1

    var url: String = PREFIX // use memory database in default

    var databaseName = "" // the name of the current database

    override fun getConnection(): Connection {
        return getConnection(null, null)
    }

    override fun getConnection(username: String?, password: String?): Connection {
        val p: Properties = Properties()
        if (username != null) p["user"] = username
        if (password != null) p["pass"] = password
        return DriverManager.getConnection(url, p)
    }

    fun getConnection(readOnly: Boolean) : Connection {
        val p: Properties = Properties()
        if (readOnly){
            p["duckdb.read_only"] = "true"
        }
        return DriverManager.getConnection(url, p)
    }

    override fun getLogWriter(): PrintWriter {
        return logger
    }

    override fun setLogWriter(out: PrintWriter) {
        logger = out
    }

    override fun setLoginTimeout(seconds: Int) {
        require(seconds > 0){"The value of seconds must be > 0 "}
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