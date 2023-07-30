package ksl.utilities.io.dbutil


import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource

class DuckDbDataSource : DataSource{

    @Transient
    private val logger: PrintWriter? = null
    private var loginTimeout = 1
//    private val url: String = JDBC.PREFIX // use memory database in default

    private val databaseName = "" // the name of the current database

    override fun getLogWriter(): PrintWriter {
        TODO("Not yet implemented")
    }

    override fun setLogWriter(out: PrintWriter?) {
        TODO("Not yet implemented")
    }

    override fun setLoginTimeout(seconds: Int) {
        loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int {
        return loginTimeout
    }

    override fun getParentLogger(): Logger {
        throw SQLFeatureNotSupportedException("getParentLogger");
    }

    override fun <T : Any?> unwrap(iface: Class<T>?): T {
        TODO("Not yet implemented")
    }

    override fun isWrapperFor(iface: Class<*>?): Boolean {
        TODO("Not yet implemented")
    }

    override fun getConnection(): Connection {
        TODO("Not yet implemented")
    }

    override fun getConnection(username: String?, password: String?): Connection {
        TODO("Not yet implemented")
    }
}