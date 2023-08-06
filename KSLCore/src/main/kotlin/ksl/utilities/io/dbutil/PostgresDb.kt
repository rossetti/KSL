package ksl.utilities.io.dbutil

import java.util.*
import javax.sql.DataSource

object PostgresDb {

    /**
     * @param dbName the name of the database, must not be null
     * @param user   the user
     * @param pWord  the password
     * @return the DataSource for getting connections
     */
    fun createDataSourceWithLocalHost(
        dbName: String,
        user: String = "",
        pWord: String = "",
        portNumber: Int = 5432
    ): DataSource {
        return createDataSource("localhost", dbName, user, pWord, portNumber)
    }

    /**
     * @param dbServerName the name of the database server, must not be null
     * @param dbName       the name of the database, must not be null
     * @param user         the user
     * @param pWord        the password
     * @param portNumber   a valid port number
     * @return the DataSource for getting connections
     */
    fun createDataSource(
        dbServerName: String = "localhost",
        dbName: String,
        user: String = "",
        pWord: String = "",
        portNumber: Int = 5432
    ): DataSource {
        val props = createProperties(dbServerName, dbName, user, pWord, portNumber)
        return Database.dataSource(props)
    }

    /**
     * @param dbServerName the database server name, must not be null
     * @param dbName       the database name, must not be null
     * @param user         the user, must not be null
     * @param pWord        the password, must not be null
     * @return the Properties instance
     */
    fun createProperties(
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

}