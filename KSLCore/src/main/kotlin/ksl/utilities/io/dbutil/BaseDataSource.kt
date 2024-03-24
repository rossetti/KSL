package ksl.utilities.io.dbutil

import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource

/**
 *  Represents a default base implementation of a DataSource which
 *  can be customized for a particular Driver.
 *
 *  @param connectionURL a valid connection string for the driver. No
 *  checking for validity is performed unless a subclass overrides the
 *  validateURL() function
 *  @param properties an optional set of properties for the driver
 *  @param logger an optional Logger that could be used as part of the JDBC DataSource interface
 *  @param loginTimeout an optional timeout specification in seconds used as part of
 *  the JDBC DataSource interface
 *  @param logWriter an optional PrintWriter that could be used as part of the JDBC DataSource interface
 */
open class BaseDataSource(
    val connectionURL: String,
    val properties: Properties = Properties(),
    private var logger: Logger? = null,
    private var loginTimeout : Int = 0,
    private var logWriter: PrintWriter? = null
) : DataSource {

    init {
        validateURL(connectionURL)
    }

    /**
     *  Consider overriding this function to check if
     *  the url is valid for the specific driver. The current
     *  implementation does nothing.
     */
    protected open fun validateURL(url: String){

    }

    @Throws(SQLException::class)
    protected open fun getConnection(properties: Properties) : Connection {
        return DriverManager.getConnection(connectionURL, properties)
    }

    @Throws(SQLException::class)
    override fun getConnection(): Connection {
        return getConnection(null, null)
    }

    @Throws(SQLException::class)
    override fun getConnection(username: String?, password: String?): Connection {
        if (username != null){
            properties.setProperty("user", username)
        }
        if (password != null){
            properties.setProperty("password", password)
        }
        return getConnection(properties)
    }

    override fun getLogWriter(): PrintWriter? {
        return logWriter
    }

    override fun setLogWriter(out: PrintWriter?) {
        logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        require(seconds >= 0) {"The time out must be >= 0"}
        loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int {
        return loginTimeout
    }

    @Throws(SQLFeatureNotSupportedException::class)
    override fun getParentLogger(): Logger {
        if (logger == null){
            throw SQLFeatureNotSupportedException()
        }
        return logger!!
    }

    @Throws(SQLException::class)
    override fun isWrapperFor(iface: Class<*>): Boolean {
        return iface.isInstance(this)
    }

    @Throws(SQLException::class)
    override fun <T> unwrap(iface: Class<T>?): T {
        return this as T
    }

}