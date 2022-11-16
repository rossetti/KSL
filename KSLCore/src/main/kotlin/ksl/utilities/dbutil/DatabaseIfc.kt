package ksl.utilities.dbutil

import com.opencsv.CSVWriterBuilder
import ksl.utilities.excel.ExcelUtil
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.MarkDown
import mu.KLoggable
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.*
import java.util.*
import java.util.regex.Pattern
import javax.sql.DataSource
import javax.sql.rowset.CachedRowSet
import javax.sql.rowset.RowSetProvider


/**
 * Many databases define the words database, user, schema in a variety of ways. This abstraction
 * defines this concept as the userSchema.  It is the name of the organizational construct for
 * which the user defined database object are contained. These are not the system abstractions.
 * The database name provided to the construct is for labeling and may or may not have any relationship
 * to the actual file name or database name of the database. The supplied connection has all
 * the information that it needs to access the database.
 */
interface DatabaseIfc {
    enum class LineOption {
        COMMENT, CONTINUED, END
    }

    /**
     * the DataSource backing the database
     */
    val dataSource: DataSource

    /**
     * identifying string representing the database. This has no relation to
     * the name of the database on disk or in the dbms. The sole purpose is for labeling of output
     */
    val label: String

    /**
     * Sets the name of the default schema
     *
     *  name for the default schema, may be null
     */
    var defaultSchemaName: String?

    /**
     * It is best to use this property within a try-with-resource construct
     * This method calls the DataSource for a connection. You are responsible for closing the connection.
     *
     * @return a connection to the database
     * @throws SQLException if there is a problem with the connection
     */
    val connection: Connection
        get() {
            val c = dataSource.connection
            logger.debug { "Established a connection to Database $label " }
            return c
        }

    val dbURL: String?

    /**
     * @param schemaName the name of the schema that should contain the tables
     * @return a list of table names within the schema
     */
    fun tableNames(schemaName: String): List<String> {
        val list = mutableListOf<String>()
        if (containsSchema(schemaName)) {
            try {
                connection.use { connection ->
                    val metaData = connection.metaData
                    val rs = metaData.getTables(null, schemaName, null, arrayOf("TABLE"))
                    while (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"))
                    }
                    rs.close()
                }
            } catch (e: SQLException) {
                logger.warn(
                    "Unable to get table names for schema $schemaName. The meta data was not available for database $label",
                    e
                )
            }
        }
        return list
    }

    /**
     * @param schemaName the name of the schema that should contain the tables
     * @return a list of table names within the schema
     */
    fun viewNames(schemaName: String): List<String> {
        val list = mutableListOf<String>()
        if (containsSchema(schemaName)) {
            try {
                connection.use { connection ->
                    val metaData = connection.metaData
                    val rs = metaData.getTables(null, schemaName, null, arrayOf("VIEW"))
                    while (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"))
                    }
                    rs.close()
                }
            } catch (e: SQLException) {
                logger.warn(
                    "Unable to get view names for schema $schemaName. The meta data was not available for database $label",
                    e
                )
            }
        }
        return list
    }

    /**
     * @return a list of all table names within the database
     */
    val userDefinedTables: List<String>
        get() {
            val list = mutableListOf<String>()
            try {
                connection.use { connection ->
                    val metaData = connection.metaData
                    val rs = metaData.getTables(null, null, null, arrayOf("TABLE"))
                    while (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"))
                    }
                    rs.close()
                }
            } catch (e: SQLException) {
                logger.warn(
                    "Unable to get database user defined tables. The meta data was not available for database $label",
                    e
                )
            }
            return list
        }

    /**
     * @return a list of all schemas within the database
     */
    val schemas: List<String>
        get() {
            val list = mutableListOf<String>()
            try {
                connection.use { connection ->
                    val metaData = connection.metaData
                    val rs = metaData.schemas
                    while (rs.next()) {
                        list.add(rs.getString("TABLE_SCHEM"))
                    }
                    rs.close()
                }
            } catch (e: SQLException) {
                logger.warn("Unable to get database schemas. The meta data was not available for database $label", e)
            }
            return list
        }

    /**
     * @return a list of all view names within the database
     */
    val views: List<String>
        get() {
            val list = mutableListOf<String>()
            try {
                connection.use { connection ->
                    val metaData = connection.metaData
                    val rs = metaData.getTables(null, null, null, arrayOf("VIEW"))
                    while (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"))
                    }
                    rs.close()
                }
            } catch (e: SQLException) {
                logger.warn("Unable to get database views. The meta data was not available for database $label", e)
            }
            return list
        }

    /**
     * The name of the schema is first checked for an exact lexicographical match.
     * If a match occurs, the schema is returned.  If a lexicographical match fails,
     * then a check for a match ignoring the case of the string is performed.
     * This is done because SQL identifier names should be case-insensitive.
     * If neither matches then false is returned.
     *
     * @param schemaName the schema name to check
     * @return true if the database contains a schema with the provided name
     */
    fun containsSchema(schemaName: String): Boolean {
        val schemaNames = schemas
        for (name in schemaNames) {
            if (name == schemaName) {
                return true
            } else if (name.equals(schemaName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * @param tableName the unqualified table name to find as a string
     * @return true if the database contains the named table
     */
    fun containsTable(tableName: String): Boolean {
        val tableNames = userDefinedTables
        for (name in tableNames) {
            if (name == tableName) {
                return true
            } else if (name.equals(tableName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * Checks if tables exist in the specified schema
     *
     * @param schemaName the name of the schema that should contain the tables
     * @return true if at least one table exists in the schema
     */
    fun hasTables(schemaName: String): Boolean {
        return tableNames(schemaName).isNotEmpty()
    }

    /**
     * Checks if the supplied table exists in the schema
     *
     * @param schemaName the name of the schema that should contain the table
     * @param table      a string representing the unqualified name of the table
     * @return true if it exists
     */
    fun containsTable(schemaName: String, table: String): Boolean {
        return tableNames(schemaName).contains(table)
    }

    /**
     * Writes the table as comma separated values
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the name of the table to write
     * @param header true means column names as the header included
     * @param out       the PrintWriter to write to.  The print writer is not closed.
     */
    fun writeTableAsCSV(
        schemaName: String? = defaultSchemaName,
        tableName: String,
        header: Boolean = true,
        out: PrintWriter
    ) {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                logger.trace("Schema: {} does not exist in database {}", schemaName, label)
                return
            }
        }
        if (!containsTable(tableName)) {
            logger.trace("Table: {} does not exist in database {}", tableName, label)
            return
        }
        val resultSet = selectAll(schemaName, tableName)
        if (resultSet != null) {
            writeAsCSV(resultSet, header, out)
            out.flush()
            resultSet.close()
        }

    }

    /**
     * Prints the table as comma separated values to the console
     * @param schemaName the name of the schema that should contain the table
     * @param tableName the name of the table to print
     */
    fun printTableAsCSV(schemaName: String? = defaultSchemaName, tableName: String, header: Boolean = true) {
        writeTableAsCSV(schemaName, tableName, header, PrintWriter(System.out))
    }

    /**
     * Writes the table as prettified text.
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     * @param out       the PrintWriter to write to.  The print writer is not closed
     */
    fun writeTableAsText(schemaName: String? = defaultSchemaName, tableName: String, out: PrintWriter) {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                logger.info("Schema: {} does not exist in database {}", schemaName, label)
                return
            }
        }
        if (!containsTable(tableName)) {
            logger.info("Table: {} does not exist in database {}", tableName, label)
            return
        }
        val rowSet = selectAll(schemaName, tableName)
        if (rowSet != null) {
            out.println(tableName)
            writeAsText(rowSet, out)
            out.flush()
            rowSet.close()
        }
    }

    /**
     * Prints the table as prettified text to the console
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     */
    fun printTableAsText(schemaName: String? = defaultSchemaName, tableName: String) {
        writeTableAsText(schemaName, tableName, PrintWriter(System.out))
    }

    /**
     * Prints all tables as text to the console
     *
     * @param schemaName the name of the schema that should contain the tables
     */
    fun printAllTablesAsText(schemaName: String? = defaultSchemaName) {
        writeAllTablesAsText(schemaName, PrintWriter(System.out))
    }

    /**
     * Writes all tables as text
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    fun writeAllTablesAsText(schemaName: String? = defaultSchemaName, out: PrintWriter) {
        val tables = if (schemaName != null) {
            tableNames(schemaName)
        } else {
            userDefinedTables
        }
        for (table in tables) {
            writeTableAsText(schemaName, table, out)
        }
    }

    /**
     * Writes the table as prettified text.
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     * @param out       the PrintWriter to write to.  The print writer is not closed
     */
    fun writeTableAsMarkdown(schemaName: String? = defaultSchemaName, tableName: String, out: PrintWriter) {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                logger.info("Schema: {} does not exist in database {}", schemaName, label)
                return
            }
        }
        if (!containsTable(tableName)) {
            logger.info("Table: {} does not exist in database {}", tableName, label)
            return
        }
        val rowSet = selectAll(schemaName, tableName)
        if (rowSet != null) {
            out.println(MarkDown.bold("Table: $tableName"))
            writeAsMarkdown(rowSet, out)
            out.flush()
            rowSet.close()
        }
    }

    /**
     * Prints the table as prettified text to the console
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     */
    fun printTableAsMarkdown(schemaName: String? = defaultSchemaName, tableName: String) {
        writeTableAsMarkdown(schemaName, tableName, PrintWriter(System.out))
    }

    /**
     * Prints all tables as text to the console
     *
     * @param schemaName the name of the schema that should contain the tables
     */
    fun printAllTablesAsMarkdown(schemaName: String? = defaultSchemaName) {
        writeAllTablesAsMarkdown(schemaName, PrintWriter(System.out))
    }

    /**
     * Writes all tables as text
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    fun writeAllTablesAsMarkdown(schemaName: String? = defaultSchemaName, out: PrintWriter) {
        val tables = if (schemaName != null) {
            tableNames(schemaName)
        } else {
            userDefinedTables
        }
        for (table in tables) {
            writeTableAsMarkdown(schemaName, table, out)
        }
    }


    /**
     * Writes all tables as separate comma separated value files into the supplied
     * directory. The files are written to text files using the same name as
     * the tables in the database
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param pathToOutPutDirectory the path to the output directory to hold the csv files
     * @param header  true means all files will have the column headers
     */
    fun writeAllTablesAsCSV(
        schemaName: String? = defaultSchemaName,
        pathToOutPutDirectory: Path,
        header: Boolean = true
    ) {
        Files.createDirectories(pathToOutPutDirectory)
        val tables = if (schemaName != null) {
            tableNames(schemaName)
        } else {
            userDefinedTables
        }
        for (table in tables) {
            val path: Path = pathToOutPutDirectory.resolve("$table.csv")
            val writer = KSLFileUtil.createPrintWriter(path)
            writeTableAsCSV(schemaName, table, header, writer)
            writer.close()
        }
    }

    /**
     * @param schemaName the schema containing the table
     * @param tableName the name of the table within the schema to get all records from
     * @return a result holding all the records from the table
     */
    fun selectAll(schemaName: String? = defaultSchemaName, tableName: String): CachedRowSet? {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                return null
            }
        }
        if (!containsTable(tableName)) {
            return null
        }
        return if (schemaName != null) {
            selectAllFromTable("${schemaName}.${tableName}")
        } else {
            selectAllFromTable(tableName)
        }
    }

    /**
     * @param tableName qualified or unqualified name of an existing table in the database
     */
    private fun selectAllFromTable(tableName: String): CachedRowSet? {
        val sql = "select * from $tableName"
        return fetchCachedRowSet(sql)
    }

    /**
     * @param tableName qualified or unqualified name of an existing table in the database
     */
    fun selectAllIntoOpenResultSet(schemaName: String? = defaultSchemaName, tableName: String): ResultSet? {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                return null
            }
        }
        if (!containsTable(tableName)) {
            return null
        }
        val sql: String = if (schemaName == null) {
            "select * from $tableName"
        } else {
            "select * from ${schemaName}.$tableName"
        }
        return fetchOpenResultSet(sql)
    }

    /**
     * @param schemaName the schema containing the table
     * @param tableName the name of the table within the schema
     * @return true if the table contains no records (rows)
     */
    fun isTableEmpty(schemaName: String? = defaultSchemaName, tableName: String): Boolean {
        val rs = selectAll(schemaName, tableName)
        return if (rs == null) {
            true
        } else {
            // first() returns false if there are no rows, so turn it into true
            val b = !rs.first()
            rs.close()
            b
        }
    }

    /**
     * @param schemaName the name of the schema that should contain the tables
     * @return true if at least one user defined table in the schema has data
     */
    fun hasData(schemaName: String? = defaultSchemaName): Boolean {
        return !areAllTablesEmpty(schemaName)
    }

    /**
     * @param schemaName the name of the schema that should contain the tables
     * @return true if all user defined tables are empty in the schema
     */
    fun areAllTablesEmpty(schemaName: String? = defaultSchemaName): Boolean {
        val tables = if (schemaName != null) {
            tableNames(schemaName)
        } else {
            userDefinedTables
        }
        var result = true
        for (t in tables) {
            result = isTableEmpty(schemaName, t)
            if (!result) {
                break
            }
        }
        return result
    }

    /*
        /**
         * @param schemaName the name of the schema that should contain the table
         * @param tableName the unqualified name of the table
         * @return a list that represents all the insert queries for the data that is currently in the
         * supplied table
         */
        fun insertQueries(schemaName: String, tableName: String): List<String> {
            val list = mutableListOf<String>()
            if (!containsTable(schemaName, tableName)) {
                return list
            }
            TODO("Not yet implemented")
            return list
        }

        /**
         * Prints the insert queries associated with the supplied table to the console
         * @param schemaName the name of the schema that should contain the table
         * @param tableName the unqualified name of the table
         */
        fun printInsertQueries(schemaName: String, tableName: String) {
            writeInsertQueries(schemaName, tableName, PrintWriter(System.out))
        }

        /**
         * Writes the insert queries associated with the supplied table to the PrintWriter
         * @param schemaName the name of the schema that should contain the table
         * @param tableName the unqualified name of the table
         * @param out       the PrintWriter to write to
         */
        fun writeInsertQueries(schemaName: String, tableName: String, out: PrintWriter) {
            val list = insertQueries(schemaName, tableName)
            for (query in list) {
                out.println(query)
            }
        }

        /**
         * Prints all table data as insert queries to the console
         *
         * @param schemaName the name of the schema that should contain the tables
         */
        fun printAllTablesAsInsertQueries(schemaName: String) {
            writeAllTablesAsInsertQueries(schemaName, PrintWriter(System.out))
        }

        /**
         * Writes all table data as insert queries to the PrintWriter
         *
         * @param schemaName the name of the schema that should contain the tables
         * @param out        the PrintWriter to write to
         */
        fun writeAllTablesAsInsertQueries(schemaName: String, out: PrintWriter) {
            val tables = tableNames(schemaName)
            for (t in tables) {
                writeInsertQueries(schemaName, t, out)
            }
        }
    */
    /**
     * Writes all the tables in the supplied schema to an Excel workbook
     *
     * @param schemaName  the name of the schema that should contain the tables, must not be null
     * @param wbName      name of the workbook, if null uses name of database
     * @param wbDirectory directory of the workbook, if null uses the working directory
     * @throws IOException if there is a problem
     */
    fun writeDbToExcelWorkbook(
        schemaName: String? = defaultSchemaName,
        wbName: String? = null,
        wbDirectory: Path? = null
    ) {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                logger.warn(
                    "Attempting to write to Excel: The supplied schema name {} is not in database {}",
                    schemaName, label
                )
                return
            }
        }
        val tableNames = if (schemaName != null) {
            tableNames(schemaName)
        } else {
            userDefinedTables
        }
        if (tableNames.isEmpty()) {
            logger.warn(
                "The supplied schema name {} had no tables to write to Excel in database {}",
                schemaName, label
            )
        } else {
            writeDbToExcelWorkbook(tableNames, wbName, wbDirectory)
        }
    }

    /**
     * Writes the tables in the supplied list to an Excel workbook, if they exist in the database.
     *
     * @param tableNames  a list of table names that should be written to Excel, must not be null
     * @param wbName      name of the workbook, if null uses name of database
     * @param wbDirectory directory of the workbook, if null uses the working directory
     * @throws IOException if there is a problem
     */
    fun writeDbToExcelWorkbook(tableNames: List<String>, wbName: String?, wbDirectory: Path?) {
        var wbName = wbName
        var wbDirectory = wbDirectory
        Objects.requireNonNull(tableNames, "The list of table names was null")
        if (wbName == null) {
            wbName = label + ".xlsx"
        } else {
            // name is not null make sure it has .xlsx
            if (!wbName.endsWith(".xlsx")) {
                wbName = "$wbName.xlsx"
            }
        }
        if (wbDirectory == null) {
            wbDirectory = KSL.excelDir
        }
        val path = wbDirectory.resolve(wbName)
        if (tableNames.isEmpty()) {
            logger.warn("The supplied list of table names was empty when writing to Excel in database {}", label)
        } else {
//TODO            ExcelUtil.writeDBAsExcelWorkbook(this, tableNames, path)
            TODO("not implemented yet")
        }
    }

    fun writeToExcel(
        schemaName: String? = defaultSchemaName,
        wbName: String = label,
        wbDirectory: Path = KSL.excelDir
    ) {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                logger.warn {
                    "Attempting to write to Excel: The supplied schema name $schemaName is not in " +
                            "database $label. No workbook named $wbName at $wbDirectory was created"
                }
                return
            }
            val tables = tableNames(schemaName)
            logger.info { "Exporting $schemaName to $wbName at $wbDirectory" }
            writeToExcel(schemaName, tables, wbName, wbDirectory)
        } else {
            logger.info { "The supplied schema to write was null. No workbook named $wbName at $wbDirectory was created" }
        }
    }

    /** Writes each table in the list to an Excel workbook with each table being placed
     *  in a new sheet with the sheet name equal to the name of the table. The column names
     *  for each table are written as the first row of each sheet.
     *
     * @param schemaName the name of the schema containing the tables or null
     * @param tableNames the names of the tables to write to a workbook
     * @param wbName the name of the workbook
     * @param wbDirectory the directory to store the workbook
     */
    fun writeToExcel(
        schemaName: String? = defaultSchemaName,
        tableNames: List<String>,
        wbName: String = label.substringBeforeLast("."),
        wbDirectory: Path = KSL.excelDir
    ) {
        if (tableNames.isEmpty()) {
            logger.warn("The supplied list of table names was empty when writing to Excel in database {}", label)
            return
        }
        val wbn = if (!wbName.endsWith(".xlsx")) {
            "$wbName.xlsx"
        } else {
            wbName
        }
        val path = wbDirectory.resolve(wbn)
        FileOutputStream(path.toFile()).use {
            logger.info { "Writing database $label to workbook at $path" }
            //TODO seems to cause this message to print
//        ERROR StatusLogger Log4j2 could not find a logging implementation.
//        Please add log4j-core to the classpath. Using SimpleLogger to log to the console...
            val workbook = SXSSFWorkbook(100)
            for (tableName in tableNames) {
                if (containsTable(tableName)) {
                    // get result set
                    val rs = selectAllIntoOpenResultSet(schemaName, tableName)
                    if (rs != null) {
                        // write result set to workbook
                        val sheet = ExcelUtil.createSheet(workbook, tableName)
                        ExcelUtil.writeSheet(rs, sheet)
                        // close result set
                        rs.close()
                    }
                }
            }
            workbook.write(it)
            workbook.close()
            workbook.dispose()
            logger.info { "Completed database $label export to workbook at $path" }
        }
    }

    /**
     * @return returns a DbCreateTask that can be configured to execute on the database
     */
    fun create(): DbCreateTask.DbCreateTaskFirstStepIfc? {
        return DbCreateTask.DbCreateTaskBuilder(this)
    }

    /**
     * Executes a single command on a database connection
     *
     * @param cmd a valid SQL command
     * @return true if the command executed without an SQLException
     */
    fun executeCommand(cmd: String): Boolean {
        var flag = false
        try {
            connection.use { con -> flag = executeCommand(con, cmd) }
        } catch (ex: SQLException) {
            logger.error("SQLException when executing {}", cmd, ex)
        }
        return flag
    }

    /**
     * Executes the SQL provided in the string. Squelches exceptions The string
     * must not have ";" semicolon at the end. The caller is responsible for closing the connection
     *
     * @param con a connection for preparing the statement
     * @param cmd the command
     * @return true if the command executed without an exception
     */
    fun executeCommand(con: Connection, cmd: String): Boolean {
        var flag = false
        try {
            con.createStatement().use { statement ->
                statement.execute(cmd)
                logger.info("Executed SQL: {}", cmd)
                statement.close()
                flag = true
            }
        } catch (ex: SQLException) {
            logger.error("SQLException when executing {}", cmd, ex)
        }
        return flag
    }

    /**
     * Consecutively executes the list of SQL queries supplied as a list of
     * strings The strings must not have ";" semi-colon at the end.
     *
     * @param cmds the commands
     * @return true if all commands were executed
     */
    fun executeCommands(cmds: List<String>): Boolean {
        var flag = true
        try {
            connection.use { con ->
                con.autoCommit = false
                for (cmd in cmds) {
                    flag = executeCommand(con, cmd)
                    if (flag == false) {
                        con.rollback()
                        break
                    }
                }
                if (flag == true) {
                    con.commit()
                }
                con.autoCommit = true
            }
        } catch (ex: SQLException) {
            flag = false
            logger.error("SQLException: ", ex)
        }
        return flag
    }

    /**
     * Executes the commands in the script on the database
     *
     * @param path the path
     * @return true if all commands are executed
     * @throws IOException if there is a problem
     */
    fun executeScript(path: Path): Boolean {
        require(!Files.notExists(path)) { "The script file does not exist" }
        logger.trace("Executing SQL in file: {}", path)
        return executeCommands(parseQueriesInSQLScript(path))
    }

    /** A simple wrapper to ease the use of JDBC for novices. Returns the results of a query in the
     * form of a JDBC CachedRowSet. Errors in the SQL are the user's responsibility. Any exceptions
     * are logged and squashed.
     *
     * @param sql an SQL text string that is valid
     * @return the results of the query or null if there was a problem
     */
    fun fetchCachedRowSet(sql: String): CachedRowSet? {
        try {
            connection.use { connection ->
                val query = connection.prepareStatement(sql)
                val rs = query.executeQuery()
                val crs = createCachedRowSet(rs)
                query.close()
                return crs
            }
        } catch (e: SQLException) {
            logger.warn("The query $sql was not executed for database $label", e)
        }
        return null
    }

    /** A simple wrapper to ease the use of JDBC for novices. Returns the results of a query in the
     * form of a JDBC ResultSet that is TYPE_FORWARD_ONLY and CONCUR_READ_ONLY .
     * Errors in the SQL are the user's responsibility. Any exceptions
     * are logged and squashed. It is the user's responsibility to close the ResultSet.  That is,
     * the statement used to create the ResultSet is not automatically closed.
     *
     * @param sql an SQL text string that is valid
     * @return the results of the query or null
     */
    fun fetchOpenResultSet(sql: String): ResultSet? {
        var query: PreparedStatement? = null
        try {
            query = connection.prepareStatement(sql)
            return query.executeQuery()
//            connection.use { connection ->
//                val query = connection.prepareStatement(sql)
//                return query.executeQuery()
//            }
        } catch (e: SQLException) {
            logger.warn("The query $sql was not executed for database $label", e)
            query?.close()
        }
        return null
    }

    /**
     * Drops the named schema from the database. If no such schema exist with the name, then nothing is done.
     *
     * @param schemaName the name of the schema to drop, must not be null
     * @param tableNames the table names in the order that they must be dropped, must not be null
     * @param viewNames  the view names in the order that they must be dropped, must not be null
     */
    fun dropSchema(schemaName: String, tableNames: List<String>, viewNames: List<String>) {
        if (containsSchema(schemaName)) {
            // need to delete the schema and any tables/data
            logger.debug("The database {} contains the schema {}", label, schemaName)
            logger.debug("Attempting to drop the schema {}....", schemaName)

            //first drop any views, then the tables
            val tables = tableNames(schemaName)
            logger.debug("Schema {} has tables ... ", schemaName)
            for (t in tables) {
                logger.debug("table {}", t)
            }
            val views = viewNames(schemaName)
            logger.debug("Schema {} has views ... ", schemaName)
            for (v in views) {
                logger.debug("table {}", v)
            }
            for (name in viewNames) {
                logger.debug("Checking for view {} ", name)
                if (views.contains(name)) {
                    val sql = "drop view $name"
                    val b = executeCommand(sql)
                    if (b) {
                        logger.debug("Dropped view {} ", name)
                    } else {
                        logger.debug("Unable to drop view {} ", name)
                    }
                }
            }
            for (name in tableNames) {
                logger.debug("Checking for table {} ", name)
                if (tables.contains(name)) {
                    val sql = "drop table $name"
                    val b = executeCommand(sql)
                    if (b) {
                        logger.debug("Dropped table {} ", name)
                    } else {
                        logger.debug("Unable to drop table {} ", name)
                    }
                }

            }
            val sql = "drop schema $schemaName cascade"
            val b = executeCommand(sql)
            if (b) {
                logger.debug("Dropped schema {} ", schemaName)
            } else {
                logger.debug("Unable to drop schema {} ", schemaName)
            }
            logger.debug("Completed the dropping of the schema {}", schemaName)
        } else {
            logger.debug("The database {} does not contain the schema {}", label, schemaName)
            logger.debug("The database {} has the following schemas", label)
            for (s in schemas) {
                logger.debug("schema: {}", s)
            }
        }
    }

    /**
     * @param schemaName the name of the schema for the table
     * @param tableName the name of the table, unqualified if the schema
     * @return the list of the table's metadata or an empty list if the table or schema is not found
     */
    fun tableMetaData(schemaName: String? = defaultSchemaName, tableName: String): List<ColumnMetaData> {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                return emptyList()
            }
        }
        if (!containsTable(tableName)) {
            return emptyList()
        }
        val sql = if (schemaName != null) {
            "select * from ${schemaName}.${tableName}"
        } else {
            "select * from $tableName"
        }
        val rs = fetchOpenResultSet(sql)
        val list = if (rs != null) {
            columnMetaData(rs)
        } else {
            emptyList()
        }
        rs?.close()
        return list
    }

    companion object : KLoggable {

        override val logger = logger()

        const val DEFAULT_DELIMITER = ";"

        val NEW_DELIMITER_PATTERN: Pattern = Pattern.compile("(?:--|\\/\\/|\\#)?!DELIMITER=(.+)")

        val COMMENT_PATTERN: Pattern = Pattern.compile("^(?:--|\\/\\/|\\#).+")

        /**
         * Method to parse a SQL script for the database. The script honors SQL
         * comments and separates each SQL command into a list of strings, 1 string
         * for each command. The list of queries is returned.
         *
         *
         * The script should have each command end in a semicolon, ; The best
         * comment to use is #. All characters on a line after # will be stripped.
         * Best to put # as the first character of a line with no further SQL on the
         * line
         *
         *
         * Based on the work described here:
         *
         *
         * https://blog.heckel.xyz/2014/06/22/run-sql-scripts-from-java-on-hsqldb-derby-mysql/
         *
         * @param filePath a path to the file for parsing
         * @return the list of strings of the commands
         * @throws IOException if there is a problem
         */
        fun parseQueriesInSQLScript(filePath: Path): List<String> {
            val queries: MutableList<String> = ArrayList()
            val inFile = Files.newInputStream(filePath)
            val reader = BufferedReader(InputStreamReader(inFile))
            var cmd = StringBuilder()
            var line: String
            while (reader.readLine().also { line = it } != null) {
                //boolean end = parseCommandString(line, cmd);
                val option = parseLine(line, cmd)
                if (option == LineOption.END) {
                    val trimmedString = cmd.toString().trim { it <= ' ' }
                    //System.out.println(trimmedString);
                    queries.add(trimmedString)
                    cmd = StringBuilder()
                }
            }
            return queries
        }

        /**
         * Takes the input string and builds a string to represent the SQL command from
         * the string. Uses EmbeddedDerbyDatabase.DEFAULT_DELIMITER as the delimiter, i.e. ";"
         * Checks for "--", "//" and "#" as start of line comments
         *
         * @param line    the input to parse
         * @param command the parsed output
         * @return the LineOption COMMENT means line was a comment, CONTINUED means that
         * command continues on next line, END means that command was ended with the delimiter
         */
        fun parseLine(line: String, command: StringBuilder): LineOption {
            return parseLine(line, DEFAULT_DELIMITER, command)
        }

        /**
         * Takes the input string and builds a string to represent the SQL command from
         * the string.  Checks for "--", "//" and "#" as start of line comments
         *
         * @param line      the input to parse
         * @param delimiter the end of comand indicator
         * @param command   the parsed output
         * @return the LineOption COMMENT means line was a comment, CONTINUED means that
         * command continues on next line, END means that command was ended with the delimiter
         */
        fun parseLine(line: String, delimiter: String, command: StringBuilder): LineOption {
            var trimmedLine = line.trim { it <= ' ' }
            if (trimmedLine.startsWith("--")
                || trimmedLine.startsWith("//")
                || trimmedLine.startsWith("#")
            ) {
                return LineOption.COMMENT
            }
            // not a comment, could be end of command or continued on next line
            // add the line to the command
            //command.append(trimmedLine);
            if (trimmedLine.endsWith(delimiter)) {
                // remove the delimiter
                trimmedLine = trimmedLine.replaceFirst(delimiter.toRegex(), " ")
                trimmedLine = trimmedLine.trim { it <= ' ' }
                command.append(trimmedLine)
                //            command.delete(command.length() - delimiter.length() - 1, command.length());
                command.append(" ")
                return LineOption.END
            }
            command.append(trimmedLine)
            command.append(" ")
            // already added the line, command must be continued on next line
            return LineOption.CONTINUED
        }

        /**
         * Writes SQLWarnings to log file
         *
         * @param conn the connection
         * @throws SQLException the exception
         */
        fun logWarnings(conn: Connection) {
            var warning: SQLWarning? = conn.warnings
            if (warning != null) {
                while (warning != null) {
                    logger.warn("Message: {}", warning.message)
                    warning = warning.getNextWarning()
                }
            }
        }

        /**
         * Parses the supplied string and breaks it up into a list of strings The
         * string needs to honor SQL comments and separates each SQL command into a
         * list of strings, 1 string for each command. The list of queries is
         * returned.
         *
         *
         * The script should have each command end in a semicolon, ; The best
         * comment to use is #. All characters on a line after # will be stripped.
         * Best to put # as the first character of a line with no further SQL on the
         * line
         *
         * @param str A big string that has SQL queries
         * @return a list of strings representing each SQL command
         * @throws IOException the exception
         */
        fun parseQueriesInString(str: String): List<String> {
            val queries: MutableList<String> = ArrayList()
            val sr = StringReader(str) // wrap your String
            val reader = BufferedReader(sr) // wrap your StringReader
            var cmd = StringBuilder()
            var line: String
            while (reader.readLine().also { line = it } != null) {
                //boolean end = parseCommandString(line, cmd);
                val option = parseLine(line, cmd)
                if (option == LineOption.END) {
                    queries.add(cmd.toString().trim { it <= ' ' })
                    cmd = StringBuilder()
                }
            }
            return queries
        }

        /**
         * Takes the input string and builds a string to represent the command from
         * the string.
         *
         * @param input   the input to parse
         * @param command the parsed output
         * @return true if the parse was successful
         */
        fun parseCommandString(input: String, command: StringBuilder): Boolean {
            var delimiter = DEFAULT_DELIMITER
            val trimmedLine = input.trim { it <= ' ' }
            val delimiterMatcher = NEW_DELIMITER_PATTERN.matcher(trimmedLine)
            val commentMatcher = COMMENT_PATTERN.matcher(trimmedLine)
            if (delimiterMatcher.find()) {
                // a) Delimiter change
                delimiter = delimiterMatcher.group(1)
                //LOGGER.log(Level.INFO, "SQL (new delimiter): {0}", delimiter);
            } else if (commentMatcher.find()) {
                // b) Comment
                //LOGGER.log(Level.INFO, "SQL (comment): {0}", trimmedLine);
            } else { // c) Statement
                command.append(trimmedLine)
                command.append(" ")
                // End of statement
                if (trimmedLine.endsWith(delimiter)) {
                    command.delete(command.length - delimiter.length - 1, command.length)
                    logger.trace("Parsed SQL: {}", command)
                    return true
                }
            }
            return false
        }

        /**
         * @param resultSet the result set to write out as csv delimited
         * @param header true (default) indicates include the header
         * @param writer the writer to use
         */
        fun writeAsCSV(resultSet: ResultSet, header: Boolean = true, writer: Writer) {
            require(!resultSet.isClosed) { "The supplied ResultSet is closed!" }
            val builder = CSVWriterBuilder(writer)
            val csvWriter = builder.build()
            csvWriter.writeAll(resultSet, header)
        }

        /**
         * @param rowSet the result set to write out as text
         * @param writer the writer to use
         */
        fun writeAsText(rowSet: CachedRowSet, writer: PrintWriter) {
            val tw = DbResultsAsText(rowSet)
            writer.println(tw.header)
            val iterator = tw.formattedRowIterator()
            while (iterator.hasNext()) {
                writer.println(iterator.next())
                writer.println(tw.rowSeparator)
            }
        }

        /**
         * @param rowSet the result set to write out as Markdown text
         * @param writer the writer to use
         */
        fun writeAsMarkdown(rowSet: CachedRowSet, writer: PrintWriter) {
            val tw = DbResultsAsText(rowSet)
            val formats = mutableListOf<MarkDown.ColFmt>()
            for (c in tw.columns) {
                if (c.textType == DbResultsAsText.TextType.STRING) {
                    formats.add(MarkDown.ColFmt.LEFT)
                } else {
                    formats.add(MarkDown.ColFmt.CENTER)
                }
            }
            val h = MarkDown.tableHeader(tw.columnNames, formats)
            writer.println(h)
            val iterator = tw.iterator()
            while (iterator.hasNext()) {
                val line = MarkDown.tableRow(iterator.next())
                writer.println(line)
            }
        }

        /**
         * @param resultSet the result set to turn into a CashedRowSet
         */
        fun createCachedRowSet(resultSet: ResultSet): CachedRowSet {
            require(!resultSet.isClosed) { "The supplied ResultSet is closed!" }
            val cachedRowSet = RowSetProvider.newFactory().createCachedRowSet()
            cachedRowSet.populate(resultSet)
            return cachedRowSet
        }

        /** The result set must be open and remains open after this call.
         *
         * @param resultSet the result set from which to grab the metadata
         * @return the metadata for each column with the list ordered by columns of
         * the result set from left to right (0 is column 1, etc.)
         */
        fun columnMetaData(resultSet: ResultSet): List<ColumnMetaData> {
            require(!resultSet.isClosed) { "The supplied ResultSet is closed!" }
            val list = mutableListOf<ColumnMetaData>()
            val md = resultSet.metaData
            if (md != null) {
                val nc = md.columnCount
                for (c in 1..nc) {
                    val catalogName: String = md.getCatalogName(c)
                    val className: String = md.getColumnClassName(c)
                    val label: String = md.getColumnLabel(c)
                    val name: String = md.getColumnName(c)
                    val typeName: String = md.getColumnTypeName(c)
                    val type: Int = md.getColumnType(c)
                    val tableName: String = md.getTableName(c)
                    val schemaName: String = md.getSchemaName(c)
                    val isAutoIncrement: Boolean = md.isAutoIncrement(c)
                    val isCaseSensitive: Boolean = md.isCaseSensitive(c)
                    val isCurrency: Boolean = md.isCurrency(c)
                    val isDefiniteWritable: Boolean = md.isDefinitelyWritable(c)
                    val isReadOnly: Boolean = md.isReadOnly(c)
                    val isSearchable: Boolean = md.isSearchable(c)
                    val isReadable: Boolean = md.isReadOnly(c)
                    val isSigned: Boolean = md.isSigned(c)
                    val isWritable: Boolean = md.isWritable(c)
                    val nullable: Int = md.isNullable(c)
                    val cmd = ColumnMetaData(
                        catalogName, className, label, name, typeName, type, tableName, schemaName,
                        isAutoIncrement, isCaseSensitive, isCurrency, isDefiniteWritable, isReadOnly, isSearchable,
                        isReadable, isSigned, isWritable, nullable
                    )
                    list.add(cmd)
                }
            }
            return list
        }

        /** The result set must be open and remains open after this call.
         *
         * @param resultSet the result set from which to grab the names of the columns
         * @return the name for each column with the list ordered by columns of
         * the result set from left to right (0 is column 1, etc.)
         */
        fun columnNames(resultSet: ResultSet): List<String> {
            val list = mutableListOf<String>()
            val cmdList = columnMetaData(resultSet)
            for (cmd in cmdList) {
                list.add(cmd.label)
            }
            return list
        }
    }
}

data class ColumnMetaData(
    val catalogName: String,
    val className: String,
    val label: String,
    val name: String,
    val typeName: String,
    val type: Int,
    val tableName: String,
    val schemaName: String,
    val isAutoIncrement: Boolean,
    val isCaseSensitive: Boolean,
    val isCurrency: Boolean,
    val isDefiniteWritable: Boolean,
    val isReadOnly: Boolean,
    val isSearchable: Boolean,
    val isReadable: Boolean,
    val isSigned: Boolean,
    val isWritable: Boolean,
    val nullable: Int
)

/**
 * The user can convert the returned rows based on ColumnMetaData
 *
 * @param resultSet the result set to iterate. It must be open and will be closed after iteration.
 */
class ResultSetRowIterator(private val resultSet: ResultSet) : Iterator<List<Any?>> {
    init {
        require(!resultSet.isClosed) { "Cannot iterate. The ResultSet is closed" }
    }

    var currentRow: Int = 0
        private set
    private var didNext: Boolean = false
    private var hasNext: Boolean = false
    val columnCount = resultSet.metaData?.columnCount ?: 0

    override fun hasNext(): Boolean {
        if (!didNext) {
            hasNext = resultSet.next()
            if (!hasNext) resultSet.close()
            didNext = true
        }
        return hasNext
    }

    override fun next(): List<Any?> {
        if (!didNext) {
            resultSet.next()
        }
        didNext = false
        currentRow++
        return makeRow(resultSet)
    }

    private fun makeRow(resultSet: ResultSet): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 1..columnCount) {
            try {
                list.add(resultSet.getObject(i))
            } catch (e: RuntimeException) {
                list.add(null)
                DatabaseIfc.logger.warn { "There was a problem accessing column $i of the result set. Set value to null" }
            }
        }
        return list
    }

}