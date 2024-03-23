package ksl.utilities.io.dbutil

import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import javax.sql.DataSource

class DataSourceImp(
    private var myLogger: Logger? = null,
    private var myLoginTimeout : Int = 0,
    private var myLogWriter: PrintWriter? = null
) : DataSource {

    override fun getConnection(): Connection {
        TODO("Not yet implemented")
    }

    override fun getConnection(username: String?, password: String?): Connection {
        TODO("Not yet implemented")
    }

    override fun getLogWriter(): PrintWriter? {
        return myLogWriter
    }

    override fun setLogWriter(out: PrintWriter?) {
        myLogWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        require(seconds >= 0) {"The time out must be >= 0"}
        myLoginTimeout = seconds
    }

    override fun getLoginTimeout(): Int {
        return myLoginTimeout
    }

    override fun getParentLogger(): Logger {
        if (myLogger == null){
            throw SQLFeatureNotSupportedException()
        }
        return myLogger!!
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