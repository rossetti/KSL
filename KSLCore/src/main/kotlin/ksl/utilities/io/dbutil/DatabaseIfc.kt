/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.utilities.io.dbutil

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import ksl.utilities.io.*
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.commons.csv.CSVFormat
import org.duckdb.DuckDBDatabaseMetaData
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.DataColumn
import org.jetbrains.kotlinx.dataframe.api.*
import org.jetbrains.kotlinx.dataframe.columns.ValueColumn
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.sql.*
import java.util.*
import java.util.regex.Pattern
import javax.sql.DataSource
import javax.sql.rowset.CachedRowSet
import javax.sql.rowset.RowSetProvider
import kotlin.reflect.*

/**
 * Many databases define database, user, schema in a variety of ways. This abstraction
 * defines this concept as the userSchema.  It is the name of the organizational construct for
 * which the user defined database object are contained. These are not the system abstractions.
 * The database name provided to the construct is for labeling and may or may not have any relationship
 * to the actual file name or database name of the database. The supplied connection has all
 * the information that it needs to access the database.
 */
interface DatabaseIfc : DatabaseIOIfc {

    enum class LineOption {
        COMMENT, CONTINUED, END
    }

    /**
     * the DataSource backing the database
     */
    val dataSource: DataSource

    /**
     *  A connection that is meant to be used many times before manual closing.
     *  Many functions rely on this connection as their default connection.
     *  Do not close this connection unless you are really finished with the database.
     *  Since, this property is final it cannot be restored after closing.
     */
    val longLastingConnection: Connection

    /**
     * It is best to use this function within a try-with-resource construct
     * This method calls the DataSource for a connection from the underlying DataSource.
     * You are responsible for closing the connection.
     *
     * ```
     * getConnection().use { con ->
     *
     * }
     * ```
     *
     * @return a connection to the database
     * @throws SQLException if there is a problem with the connection
     */
    fun getConnection(): Connection {
        try {
            val c = dataSource.connection
            logger.trace { "Established a connection to Database $label " }
            return c
        } catch (ex: SQLException) {
            KSL.logger.error { "Unable to establish connection to $label" }
            throw ex
        }
    }

    /**
     *  The URL used to establish the connection to the database.
     */
    val dbURL: String?

    /**
     * @param schemaName the name of the schema that should contain the tables. If null,
     * then a list of table names not associated with a schema may be returned. Or, if
     * the schema concept does not exist for the database, then the names of any user-defined
     * tables may be returned.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @return a list of table names within the schema. The list may be empty if no tables
     * are defined within the schema.
     */
    fun tableNames(schemaName: String?): List<String> {
        return Companion.tableNames(longLastingConnection, schemaName)
    }

    /**
     * @param schemaName the name of the schema that should contain the views. If null,
     * then a list of view names not associated with a schema may be returned. Or, if
     * the schema concept does not exist for the database, then the names of any views
     *  may be returned.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @return a list of view names within the schema. The list may be empty if no views
     * are defined within the schema.
     */
    fun viewNames(schemaName: String?): List<String> {
        return Companion.viewNames(longLastingConnection, schemaName)
    }

    /**
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @return a list of all table names within the database regardless of schema
     */
    override val userDefinedTables: Map<String?, List<String>>
        get() = dbTableNamesBySchema(longLastingConnection)

    /** The list may be empty if the database does not support the schema concept.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @return a list of all schema names within the database
     */
    override val schemaNames: List<String>
        get() = schemaNames(longLastingConnection)

    /**
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @return a list of all view names within the database
     */
    override val views: Map<String?, List<String>>
        get() = dbViewNamesBySchema(longLastingConnection)

    /**
     * The name of the schema is first checked for an exact lexicographical match.
     * If a match occurs, the schema is returned.  If a lexicographical match fails,
     * then a check for a match ignoring the case of the string is performed.
     * This is done because SQL identifier names should be case-insensitive.
     * If neither matches then false is returned.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the schema name to check
     * @return true if the database contains a schema with the provided name
     */
    fun containsSchema(schemaName: String): Boolean {
        return Companion.containsSchema(longLastingConnection, schemaName)
    }

    /**
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param viewName the unqualified view name to find as a string
     * @return true if the database contains the named view
     */
    fun containsView(
        viewName: String,
        schemaName: String? = defaultSchemaName
    ): Boolean {
        return Companion.containsView(longLastingConnection, viewName, schemaName)
    }

    /**
     * Checks if tables exist in the specified schema.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @return true if at least one table exists in the schema
     */
    fun hasTables(schemaName: String? = defaultSchemaName): Boolean {
        return tableNames(schemaName).isNotEmpty()
    }

    /**
     * Checks if the supplied table exists in the schema.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the table
     * @param tableName      a string representing the unqualified name of the table
     * @return true if it exists
     */
    fun containsTable(tableName: String, schemaName: String? = defaultSchemaName): Boolean {
        return Companion.containsTable(longLastingConnection, tableName, schemaName)
    }

    /**
     * Writes the table as comma separated values.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the name of the table to write
     * @param header true means column names as the header included
     * @param out       the PrintWriter to write to.  The print writer is not closed.
     */
    override fun exportTableAsCSV(
        tableName: String,
        schemaName: String?,
        out: PrintWriter,
        header: Boolean
    ) {
        if (!containsTable(tableName, schemaName) && !containsView(tableName, schemaName)) {
            logger.trace { "Table or View: $tableName does not exist in database $label" }
            return
        }
        val resultSet = selectAll(tableName, schemaName)
        if (resultSet != null) {
            writeAsCSV(resultSet, header, out)
            out.flush()
            resultSet.close()
        }
    }

    /**
     * Prints the table as comma separated values to the console.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the table
     * @param tableName the name of the table to print
     */
    override fun printTableAsCSV(tableName: String, schemaName: String?, header: Boolean) {
        exportTableAsCSV(tableName, schemaName, PrintWriter(System.out), header)
    }

    /**
     * Writes the table as prettified text.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     * @param out       the PrintWriter to write to.  The print writer is not closed
     */
    override fun writeTableAsText(tableName: String, schemaName: String?, out: PrintWriter) {
        if (!containsTable(tableName, schemaName) && !containsView(tableName, schemaName)) {
            logger.info { "Table or View: $tableName does not exist in database $label" }
            return
        }
        val rowSet = selectAll(tableName, schemaName)
        if (rowSet != null) {
            out.println(tableName)
            logger.info { "Writing table: $tableName from schema $schemaName as text to output" }
            writeAsText(rowSet, out)
            out.flush()
            rowSet.close()
        }
    }

    /**
     * Prints the table as prettified text to the console.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     */
    override fun printTableAsText(tableName: String, schemaName: String?) {
        writeTableAsText(tableName, schemaName, PrintWriter(System.out))
    }

    /**
     * Prints all tables as text to the console.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     */
    override fun printAllTablesAsText(schemaName: String?) {
        writeAllTablesAsText(schemaName, PrintWriter(System.out))
    }

    /**
     * Writes all tables as text.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    override fun writeAllTablesAsText(schemaName: String?, out: PrintWriter) {
        //removed dependence on userDefinedTables, tableNames() handles null schemaName
        val tables = tableNames(schemaName)
        for (table in tables) {
            writeTableAsText(table, schemaName, out)
        }
    }

    /**
     * Writes the table as prettified text.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     * @param out       the PrintWriter to write to.  The print writer is not closed
     */
    override fun writeTableAsMarkdown(tableName: String, schemaName: String?, out: PrintWriter) {
        if (!containsTable(tableName, schemaName) && !containsView(tableName, schemaName)) {
            logger.info { "Table or View: $tableName does not exist in database $label" }
            return
        }
        val rowSet = selectAll(tableName, schemaName)
        if (rowSet != null) {
            out.println(MarkDown.bold("Table: $tableName"))
            writeAsMarkdown(rowSet, out)
            out.flush()
            rowSet.close()
        }
    }

    /**
     * Prints the table as prettified text to the console.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     */
    override fun printTableAsMarkdown(tableName: String, schemaName: String?) {
        writeTableAsMarkdown(tableName, schemaName, PrintWriter(System.out))
    }

    /**
     * Prints all tables as text to the console.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     */
    override fun printAllTablesAsMarkdown(schemaName: String?) {
        writeAllTablesAsMarkdown(schemaName, PrintWriter(System.out))
    }

    /**
     * Writes all tables as text.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    override fun writeAllViewsAsMarkdown(schemaName: String?, out: PrintWriter) {
        val viewList = viewNames(schemaName)
        for (view in viewList) {
            writeTableAsMarkdown(view, schemaName, out)
        }
    }

    /**
     * Writes all tables as text.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    override fun writeAllTablesAsMarkdown(schemaName: String?, out: PrintWriter) {
        val tables = tableNames(schemaName)
        for (table in tables) {
            writeTableAsMarkdown(table, schemaName, out)
        }
    }

    /**
     * Writes all tables as separate comma separated value files into the supplied
     * directory. The files are written to text files using the same name as
     * the tables in the database.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param pathToOutPutDirectory the path to the output directory to hold the csv files
     * @param header  true means all files will have the column headers
     */
    override fun exportAllTablesAsCSV(
        schemaName: String?,
        pathToOutPutDirectory: Path,
        header: Boolean
    ) {
        Files.createDirectories(pathToOutPutDirectory)
        val tables = tableNames(schemaName)
        for (table in tables) {
            val path: Path = pathToOutPutDirectory.resolve("$table.csv")
            val writer = KSLFileUtil.createPrintWriter(path)
            exportTableAsCSV(table, schemaName, writer, header)
            writer.close()
        }
    }

    /**
     * Writes all tables as separate comma separated value files into the supplied
     * directory. The files are written to text files using the same name as
     * the tables in the database.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param pathToOutPutDirectory the path to the output directory to hold the csv files
     * @param header  true means all files will have the column headers
     */
    override fun exportAllViewsAsCSV(
        schemaName: String?,
        pathToOutPutDirectory: Path,
        header: Boolean
    ) {
        Files.createDirectories(pathToOutPutDirectory)
        val viewList = viewNames(schemaName)
        for (view in viewList) {
            val path: Path = pathToOutPutDirectory.resolve("$view.csv")
            val writer = KSLFileUtil.createPrintWriter(path)
            exportTableAsCSV(view, schemaName, writer, header)
            writer.close()
        }
    }

    /**
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the schema containing the table
     * @param tableName the name of the table within the schema to get all records from
     * @return a result holding all the records from the table
     */
    fun selectAll(tableName: String, schemaName: String? = defaultSchemaName): CachedRowSet? {
        if (!containsTable(tableName) && !containsView(tableName)) {
            logger.trace { "Table or View: $tableName does not exist in database $label" }
            return null
        }
        return if (schemaName != null) {
            selectAllFromTable("${schemaName}.${tableName}")
        } else {
            selectAllFromTable(tableName)
        }
    }

    /**
     *  Deletes all data from tables within the specified schema. If there
     *  is null, then the tables not associated with a schema are deleted.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     *  @param schemaName the name of the schema containing the table
     */
    fun deleteAllFrom(schemaName: String? = defaultSchemaName) {
        //removed dependence on userDefinedTables; tableNames() handles null schemaName
        val tables = tableNames(schemaName)
        deleteAllFrom(tables, schemaName)
    }

    /**
     *  Deletes all data from the tables in the list.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     *  @param tableNames the table to delete from
     *  @param schemaName the name of the schema containing the table
     */
    fun deleteAllFrom(tableNames: List<String>, schemaName: String? = defaultSchemaName) {
        for (tableName in tableNames) {
            deleteAllFrom(tableName, schemaName)
        }
    }

    /**
     *  Deletes all data from the named table.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     *  @param tableName the table to delete from
     *  @param schemaName the name of the schema containing the table
     *  @return true if the command executed successfully.
     */
    fun deleteAllFrom(tableName: String, schemaName: String? = defaultSchemaName): Boolean {
        if (!containsTable(tableName, schemaName)) {
            return false
        }
        val sql = deleteAllFromTableSQL(tableName, schemaName)
        return executeCommand(sql)
    }

    /**
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param tableName qualified or unqualified name of an existing table in the database
     */
    private fun selectAllFromTable(tableName: String): CachedRowSet? {
        val sql = "select * from $tableName"
        return fetchCachedRowSet(sql)
    }

    /**
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param tableName qualified or unqualified name of an existing table in the database
     */
    fun selectAllIntoOpenResultSet(tableName: String, schemaName: String? = defaultSchemaName): ResultSet? {
        if (!containsTable(tableName) && !containsView(tableName)) {
            logger.trace { "Table or View: $tableName does not exist in database $label" }
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
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the schema containing the table
     * @param tableName the name of the table within the schema
     * @return true if the table contains no records (rows)
     */
    fun isTableEmpty(tableName: String, schemaName: String? = defaultSchemaName): Boolean {
        val rs = selectAll(tableName, schemaName)
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
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @return true if at least one user defined table in the schema has data
     */
    fun hasData(schemaName: String? = defaultSchemaName): Boolean {
        return !areAllTablesEmpty(schemaName)
    }

    /**
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @return true if all user defined tables are empty in the schema
     */
    fun areAllTablesEmpty(schemaName: String? = defaultSchemaName): Boolean {
        val tables = tableNames(schemaName)
        var result = true
        for (t in tables) {
            result = isTableEmpty(t, schemaName)
            if (!result) {
                break
            }
        }
        return result
    }

    /**
     * Prints the insert queries associated with the supplied table to the console
     * @param schemaName the name of the schema that should contain the table
     * @param tableName the unqualified name of the table
     */
    override fun printInsertQueries(tableName: String, schemaName: String?) {
        exportInsertQueries(tableName, schemaName, PrintWriter(System.out))
    }

    /**
     * Writes the insert queries associated with the supplied table to the PrintWriter.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the table
     * @param tableName the unqualified name of the table
     * @param out       the PrintWriter to write to
     */
    override fun exportInsertQueries(tableName: String, schemaName: String?, out: PrintWriter) {
        val rowSet = selectAll(tableName, schemaName)
        if (rowSet != null) {
            logger.info { "Exporting insert queries for table $tableName in schema $schemaName" }
            val resultsAsText = DbResultsAsText(rowSet)
            val sql = if (schemaName == null) {
                "insert into $tableName values "
            } else {
                "insert into ${schemaName}.${tableName} values "
            }
            val iterator = resultsAsText.insertTextRowIterator()
            while (iterator.hasNext()) {
                val rowData = iterator.next()
                val inputs = rowData.joinToString(", ", prefix = "(", postfix = ")")
                out.println(sql + inputs)
                out.flush()
                logger.trace { "Wrote insert statement: ${sql}${inputs}" }
            }
        } else {
            logger.info { "Failed to export insert queries for table $tableName in schema $schemaName" }
        }
    }

    /**
     * Prints all table data as insert queries to the console
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     */
    override fun printAllTablesAsInsertQueries(schemaName: String?) {
        exportAllTablesAsInsertQueries(schemaName, PrintWriter(System.out))
    }

    /**
     * Writes all table data as insert queries to the PrintWriter
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    override fun exportAllTablesAsInsertQueries(schemaName: String?, out: PrintWriter) {
        //removed dependence on userDefinedTables; tableNames() handles null schemaName
        val tables = tableNames(schemaName)
        for (t in tables) {
            exportInsertQueries(t, schemaName, out)
        }
    }

    override fun exportToExcel(
        schemaName: String?,
        wbName: String,
        wbDirectory: Path
    ) {
        val tables = tableNames(schemaName).toMutableList()
        tables.addAll(viewNames(schemaName))
        if (tables.isEmpty()) {
            logger.info { "There were no tables or views when exporting $schemaName to Excel workbook $wbName at $wbDirectory" }
        } else {
            logger.info { "Exporting $schemaName to Excel workbook $wbName at $wbDirectory" }
            exportToExcel(tables, schemaName, wbName, wbDirectory)
        }
    }

    /** Writes each table in the list to an Excel workbook with each table being placed
     *  in a new sheet with the sheet name equal to the name of the table. The column names
     *  for each table are written as the first row of each sheet.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema containing the tables or null
     * @param tableNames the names of the tables to write to a workbook
     * @param wbName the name of the workbook
     * @param wbDirectory the directory to store the workbook
     */
    override fun exportToExcel(
        tableNames: List<String>,
        schemaName: String?,
        wbName: String,
        wbDirectory: Path
    ) {
        // check the table names
        val dbTables = tableNames(schemaName).toMutableList()
        dbTables.addAll(viewNames(schemaName))
        val finalList = mutableListOf<String>()
        for (tableName in tableNames) {
            if (dbTables.contains(tableName)) {
                finalList.add(tableName)
            } else {
                logger.warn { "The table name, $tableName was was not part of the schema ($schemaName) when writing to Excel in database $label" }
            }
        }
        if (finalList.isEmpty()) {
            logger.warn { "The supplied list of table names was empty when writing to Excel in database $label" }
            return
        }
        val wbn = if (!wbName.endsWith(".xlsx")) {
            "$wbName.xlsx"
        } else {
            wbName
        }
        val path = wbDirectory.resolve(wbn)
        ExcelUtil.exportTablesToExcel(this, path, finalList, schemaName)
    }

    /**
     * Opens the workbook for reading only and writes the sheets of the workbook into database tables.
     * The list of names is the names of the
     * sheets in the workbook and the names of the tables that need to be written. They are in the
     * order that is required for entering data so that no integrity constraints are violated. The
     * underlying workbook is closed after the operation.
     *
     *  Uses the longLastingConnection property for the connection for metadata checking.
     *
     * @param pathToWorkbook the path to the workbook. Must be valid workbook with .xlsx extension
     * @param skipFirstRow   if true the first row of each sheet is skipped
     * @param schemaName the name of the schema containing the named tables
     * @param tableNames     the names of the sheets and tables in the order that needs to be written
     * @throws IOException an io exception
     */
    override fun importWorkbookToSchema(
        pathToWorkbook: Path,
        tableNames: List<String>,
        schemaName: String?,
        skipFirstRow: Boolean
    ) {
        ExcelUtil.importWorkbookToSchema(this, pathToWorkbook, tableNames, schemaName, skipFirstRow)
    }

    /**
     * @return returns a DbCreateTask that can be configured to execute on the database
     */
    fun create(): DbCreateTask.DbCreateTaskFirstStepIfc {
        return DbCreateTask.DbCreateTaskBuilder(this)
    }

    /**
     * Executes a single command on a database connection
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param command a valid SQL command
     * @return true if the command executed without an SQLException
     */
    fun executeCommand(command: String): Boolean {
        return Companion.executeCommand(longLastingConnection, command)
    }

    /**
     * Consecutively executes the list of SQL queries supplied as a list of
     * strings The strings must not have ";" semicolon at the end.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param commands the commands
     * @return true if all commands were executed
     */
    fun executeCommands(commands: List<String>): Boolean {
        return Companion.executeCommands(longLastingConnection, commands)
    }

    /**
     * Executes the commands in the script on the database
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param path the path
     * @return true if all commands are executed
     * @throws IOException if there is a problem
     */
    fun executeScript(path: Path): Boolean {
        require(!Files.notExists(path)) { "The script file does not exist" }
        logger.trace { "Executing SQL in file: $path" }
        return executeCommands(parseQueriesInSQLScript(path))
    }

    /** A simple wrapper to ease the use of JDBC for novices. Returns the results of a query in the
     * form of a JDBC CachedRowSet. Errors in the SQL are the user's responsibility. Any exceptions
     * are logged and squashed.  The underlying query is closed.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param sql an SQL text string that is valid
     * @return the results of the query or null if there was a problem
     */
    fun fetchCachedRowSet(sql: String): CachedRowSet? {
        return Companion.fetchCachedRowSet(longLastingConnection, sql)
    }

    /** A simple wrapper to ease the use of JDBC for novices. Returns the results of a query in the
     * form of a JDBC ResultSet that is TYPE_FORWARD_ONLY and CONCUR_READ_ONLY .
     * Errors in the SQL are the user's responsibility. Any exceptions
     * are logged and squashed. It is the user's responsibility to close the ResultSet.  That is,
     * the statement used to create the ResultSet is not automatically closed.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param sql an SQL text string that is valid
     * @return the results of the query or null
     */
    fun fetchOpenResultSet(sql: String): ResultSet? {
        return Companion.fetchOpenResultSet(longLastingConnection, sql)
    }

    /**
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the schema containing the table
     * @param tableName the name of the table within the schema
     * @return the number of rows in the table
     */
    fun numRows(tableName: String, schemaName: String? = defaultSchemaName): Long {
        return Companion.numRows(longLastingConnection, tableName, schemaName)
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
            logger.debug { "The database $label contains the schema $schemaName" }
            logger.debug { "Attempting to drop the schema $schemaName...." }
            val tables = tableNames(schemaName)
            logger.debug { "Schema $schemaName has tables ... " }
            for (t in tables) {
                logger.debug { "table: $t" }
            }
            val views = viewNames(schemaName)
            logger.debug { "Schema $schemaName has views ... " }
            for (v in views) {
                logger.debug { "view: $v" }
            }
            //first drop any views, then the tables
            for (name in viewNames) {
                logger.debug { "Checking for view $name " }
                if (views.contains(name)) {
                    val sql = "drop view $name"
                    val b = executeCommand(sql)
                    if (b) {
                        logger.debug { "Dropped view $name " }
                    } else {
                        logger.debug { "Unable to drop view $name " }
                    }
                }
            }
            for (name in tableNames) {
                logger.debug { "Checking for table $name " }
                if (tables.contains(name)) {
                    val sql = "drop table $name"
                    val b = executeCommand(sql)
                    if (b) {
                        logger.debug { "Dropped table $name " }
                    } else {
                        logger.debug { "Unable to drop table $name " }
                    }
                }
            }
            val sql = "drop schema $schemaName cascade"
            val b = executeCommand(sql)
            if (b) {
                logger.debug { "Dropped schema $schemaName " }
            } else {
                logger.debug { "Unable to drop schema $schemaName " }
            }
            logger.debug { "Completed the dropping of the schema $schemaName" }
        } else {
            logger.debug { "The database $label does not contain the schema $schemaName" }
            logger.debug { "The database $label has the following schemas" }
            for (s in schemaNames) {
                logger.debug { "schema: $s" }
            }
        }
    }

    /**
     * @param schemaName the name of the schema for the table
     * @param tableName the name of the table, unqualified if the schema
     * @return the list of the table's metadata or an empty list if the table or schema is not found
     */
    fun tableMetaData(tableName: String, schemaName: String? = defaultSchemaName): List<ColumnMetaData> {
        if (!containsTable(tableName, schemaName) && !containsView(tableName, schemaName)) {
            return emptyList()
        }
        val sql = if (schemaName != null) {
            "select * from ${schemaName}.${tableName}"
        } else {
            "select * from $tableName"
        }
        var list: List<ColumnMetaData>
        fetchOpenResultSet(sql).use {
            list = if (it != null) {
                columnMetaData(it)
            } else {
                emptyList()
            }
        }
        return list
    }

    /**
     *  Selects data from the database and fills a list with instances
     *  of the data class. The [factory] must produce an instance of a
     *  subclass, [T] of DbData.  Subclasses of type DbData are
     *  data classes that have been configured to hold data from a
     *  named table from the database.  See the documentation on DbData
     *  for further information. The resulting list of data
     *  is not connected to the database in any way.
     */
    fun <T : TabularData> selectTableDataIntoDbData(factory: () -> T): List<T> {
        val template = factory()
        val rowSet: CachedRowSet? = selectAll(template.tableName)
        val list = mutableListOf<T>()
        if (rowSet != null) {
            val iterator = ResultSetRowIterator(rowSet)
            while (iterator.hasNext()) {
                val row: List<Any?> = iterator.next()
                val data = factory()
                data.setPropertyValues(row)
                list.add(data)
            }
        }
        logger.trace { "Database $label: selected DbData data class ${template::class.simpleName} data from table ${template.tableName} " }
        return list
    }

    /**
     *  Inserts the data from the DbData instance into the  supplied table [tableName] and schema [schemaName].
     *  The DbData instance must be designed for the table.
     *
     *  @return the number of rows inserted
     */
    fun <T : DbTableData> insertDbDataIntoTable(
        data: T,
        tableName: String = data.tableName,
        schemaName: String? = defaultSchemaName
    ): Int {
        require(data.tableName == tableName) { "The supplied data was not from table $tableName" }
        require(
            containsTable(
                tableName,
                schemaName
            )
        ) { "Database $label does not contain table $tableName for inserting data!" }
        data.schemaName = schemaName // needed to make the insert statement correctly
        val sql = data.insertDataSQLStatement()
        //e.g. insert into main.Persons (id, name, age) values (?, ?, ?)
        try {
            getConnection().use { con ->
                con.autoCommit = false
                val stmt = if (data.autoIncField) {
                    con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                } else {
                    con.prepareStatement(sql)
                }
                val dataMap = data.extractNonAutoIncPropertyValuesByName()
                val insertFields = data.extractUpdatableFieldNames()
                for ((i, field) in insertFields.withIndex()) {
                    stmt.setObject(i + 1, dataMap[field])
                }
                stmt.executeUpdate()
                con.commit()
                // if necessary, this updates the in-memory data item with the assigned generated key
                if (data.autoIncField) {
                    val rs = stmt.generatedKeys
                    if (rs.next()) {
                        val autoId = rs.getInt(1)
                        data.setAutoIncField(autoId)
                    }
                }
                return 1
            }
        } catch (e: SQLException) {
            logger.warn { "There was an SQLException when trying insert DbData data into : $tableName" }
            logger.warn { "INSERT STATEMENT: $sql" }
            logger.warn { "SQLException: $e" }
            return 0
        }
    }

    /**
     *  Inserts the [data] from the list into the supplied table [tableName] and schema [schemaName].
     *  The DbData instances must be designed for the same table. The data instances are not
     *  updated to reflect any changes imposed by the database such as generated primary keys.
     *
     *  @return the number of rows inserted
     */
    fun <T : DbTableData> insertAllDbDataIntoTable(
        data: List<T>,
        tableName: String,
        schemaName: String? = defaultSchemaName
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        require(containsTable(tableName, schemaName))
            { "Database $label does not contain table $tableName for inserting data!" }
        // data should come from the table
        val first = data.first()
        require(first.tableName == tableName) { "The supplied data was not from table $tableName" }
        // use first to set up the prepared statement
        // assumes the first is representative of the rest in the list
        first.schemaName = schemaName // needed to make the insert statement correctly
        val sql = first.insertDataSQLStatement()
        try {
            // use new connection because of batch processing and auto commit
            getConnection().use { con ->
                con.autoCommit = false
                val stmt = con.prepareStatement(sql)
                for (d in data) {
                    val dataMap = d.extractNonAutoIncPropertyValuesByName()
                    val insertFields = d.extractUpdatableFieldNames()
                    for ((i, field) in insertFields.withIndex()) {
                        stmt.setObject(i + 1, dataMap[field])
                    }
                    stmt.addBatch()
                }
                val ni = stmt.executeBatch()
                con.commit()
                logger.trace { "Inserted ${ni.size} data objects out of ${data.size} to table $tableName" }
                return ni.size
            }
        } catch (e: SQLException) {
            logger.warn { "There was an SQLException when trying insert DbData data into : $tableName" }
            logger.warn { "SQLException: $e" }
            return 0
        }
    }

    /**
     *  Updates the table based on the supplied [data].
     *  The DbData instance must be designed for the table.
     *
     *  @return the number of rows updated
     */
    fun <T : DbTableData> updateDbDataInTable(
        data: T,
        tableName: String = data.tableName,
        schemaName: String? = defaultSchemaName
    ): Int {
        return updateDbDataInTable(listOf(data), tableName, schemaName)
    }

    /**
     *  Updates the table based on the [data] from the list.
     *  The DbData instance must be designed for the table.
     *
     *  @return the number of rows updated
     */
    fun <T : DbTableData> updateDbDataInTable(
        data: List<T>,
        tableName: String,
        schemaName: String? = defaultSchemaName
    ): Int {
        if (data.isEmpty()) {
            return 0
        }
        if (!containsTable(tableName, schemaName)) {
            logger.warn { "Database $label does not contain table $tableName for updating data!" }
            return 0
        }
        // data should come from the table
        val first = data.first()
        // assumes that the first is representative of all in list
        require(first.tableName == tableName) { "The supplied data was not from table $tableName" }
        // use first to set up the prepared statement
        first.schemaName = schemaName // needed to make the update statement correctly
        val sql = first.updateDataSQLStatement()
        val nc = first.numUpdateFields
        try {
            // use new connection because of auto commit and batch processing
            getConnection().use { con ->
                con.autoCommit = false
                val ps = con.prepareStatement(sql)
                for (d in data) {
                    val values: List<Any?> = d.extractUpdateValues()
                    for (colIndex in 1..nc) {
                        ps.setObject(colIndex, values[colIndex - 1])
                    }
                    // need to set key fields for where clause
                    val wv = d.extractKeyValues()
                    for ((i, v) in wv.withIndex()) {
                        ps.setObject(nc + i + 1, v)
                    }
                    ps.addBatch()
                }
                val ni = ps.executeBatch()
                con.commit()
                logger.trace { "Updated ${ni.size} data objects out of ${data.size} in table $tableName" }
                return ni.size
            }
        } catch (e: SQLException) {
            logger.warn { "There was an SQLException when trying update DbData data in : $tableName" }
            logger.warn { "SQLException: $e" }
            return 0
        }
    }

    /**
     *  The purpose of this function is to allow the creation of a simple database table
     *  based on a DbTableData data class.  By defining a data class that is a subclasses of
     *  DbTableData, a limited CREATE TABLE specification can be obtained and the table created.
     *  Then, the database can be used to insert data from instances of
     *  the DbTableData subclass.  The DbTableData data class cannot have a auto-increment type primary key.
     *  In addition, the table will not have foreign key specifications nor referential integrity specifications.
     *  If supported by the underlying database engine, additional specifications could be added via
     *  alter table DDL specifications.
     *
     *  @param tableDefinition a table definition based on DbTableData specifications. The specified
     *  table must not already exist in its specified schema.
     *  @param autoCreateSchema if true, if a table is in a schema that does not exist, the schema
     *  will automatically be created. The default is false. In which case, an error may occur if
     *  the schema does not exist to hold the table. Some databases do not support schemas. In that case,
     *  do not specify a schema for the table. Leave the schema null.
     */
    fun <T : DbTableData> createSimpleDbTable(
        tableDefinition: T,
        autoCreateSchema: Boolean = false
    ) {
        createSimpleDbTables(setOf(tableDefinition), autoCreateSchema)
    }

    /**
     *  The purpose of this function is to allow the creation of simple database tables
     *  based on the DbTableData data classes.  By defining data classes that are subclasses of
     *  DbTableData, a limited CREATE TABLE specification can be obtained and the table created.
     *  Then, the database can be used to insert data from instances of
     *  the DbTableData subclasses.  The DbTableData data classes cannot have auto-increment type primary keys.
     *  In addition, the tables will not have foreign key specifications nor referential integrity specifications.
     *  If supported by the underlying database engine, additional specifications could be added via
     *  alter table DDL specifications.
     *
     *  @param tableDefinitions an example set of table definitions based on DbTableData specifications. The specified
     *  table must not already exist in its specified schema.
     *  @param autoCreateSchema if true, if a table is in a schema that does not exist, the schema
     *  will automatically be created. The default is false. In which case, an error may occur if
     *  the schema does not exist to hold the table. Some databases do not support schemas. In that case,
     *  do not specify a schema for the table. Leave the schema null.
     */
    fun <T : DbTableData> createSimpleDbTables(
        tableDefinitions: Set<T>,
        autoCreateSchema: Boolean = false
    ) {
        // need to check for table name conflict with existing tables
        // need to check schema specification
        val tableInfo = dbTablesFromMetaData(longLastingConnection)
        for (td in tableDefinitions) {
            if (td.schemaName == null) {
                // table is not specified in a schema, just check if table exists
                require(!tableInfo.containsTable(td.tableName)) { "Table ${td.tableName} already exists in database $label" }
            } else {
                // schema was not null, check if schema exists
                if (tableInfo.containsSchema(td.schemaName!!)) {
                    // The schema exists, make sure that the table doesn't already exist
                    require(
                        !tableInfo.containsSchemaAndTable(
                            td.schemaName!!,
                            td.tableName
                        )
                    ) { "Table ${td.tableName} already exists in schema ${td.schemaName} in database $label" }
                } else {
                    // The schema does not exist. Table can be made, but no schema to hold it.
                    if (autoCreateSchema) {
                        val worked = executeCommand("CREATE SCHEMA ${td.schemaName}")
                        if (worked) {
                            logger.info { "Db($label): schema ${td.schemaName} has been created." }
                        } else {
                            logger.info { "Db($label): schema ${td.schemaName} was not created." }
                        }
                    } else {
                        val msg = "Database $label does not contain ${td.schemaName} to hold table $td.tableName"
                        logger.error { msg }
                        throw IllegalStateException(msg)
                    }
                }
            }
        }
        for (tableData in tableDefinitions) {
            require(!tableData.autoIncField) { "The autoIncField for table (${tableData.tableName}) in the simple table must be false." }
            val worked = executeCommand(tableData.createTableSQLStatement())
            if (worked) {
                logger.info { "Database: $label: table ${tableData.tableName} has been created." }
            } else {
                logger.info { "Database: $label: table ${tableData.tableName} was not created." }
            }
        }
        logger.info { "Database: $label: table definitions have been processed." }
    }

    private fun List<DbSchemaInfo>.containsTable(tableName: String): Boolean {
        for (ti in this) {
            if (ti.tableName == tableName) return true
        }
        return false
    }

    private fun List<DbSchemaInfo>.containsSchema(schemaName: String): Boolean {
        for (ti in this) {
            if (ti.schemaName == schemaName) return true
        }
        return false
    }

    private fun List<DbSchemaInfo>.containsSchemaAndTable(schemaName: String, tableName: String): Boolean {
        for (ti in this) {
            if ((ti.schemaName == schemaName) && (ti.tableName == tableName)) return true
        }
        return false
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.appendLine("Database: $label")
        sb.appendLine("The database was connected via url $dbURL")
        sb.appendLine("The database has the following schemas:")
        sb.append("\t")
        sb.append(schemaNames.toString())
        sb.appendLine()
        sb.appendLine("The default schema is $defaultSchemaName")
        sb.appendLine("The database has the following user defined tables:")
        sb.append("\t")
        sb.append(userDefinedTables.toString())
        sb.appendLine()
        sb.appendLine("The database has the following views:")
        sb.append("\t")
        sb.append(views.toString())
        sb.appendLine()
        return sb.toString()
    }

    //TODO select * from table where field = ?, updatable RowSet

    companion object {

        val logger = KotlinLogging.logger {}

        const val DEFAULT_DELIMITER = ";"

        val NEW_DELIMITER_PATTERN: Pattern = Pattern.compile("(?:--|\\/\\/|\\#)?!DELIMITER=(.+)")

        val COMMENT_PATTERN: Pattern = Pattern.compile("^(?:--|\\/\\/|\\#).+")

        /**
         * Executes the SQL provided in the string. Squelches exceptions The string
         * must not have ";" semicolon at the end. The caller is responsible for closing the connection
         *
         * @param connection a connection for preparing the statement
         * @param command the command
         * @return true if the command executed without an exception
         */
        fun executeCommand(connection: Connection, command: String): Boolean {
            var flag = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(command)
                    logger.trace { "Executed SQL:\n$command" }
                    statement.close()
                    flag = true
                }
            } catch (ex: SQLException) {
                logger.error(ex) { "SQLException when executing command $command" }
            }
            return flag
        }

        /**
         * Consecutively executes the list of SQL queries supplied as a list of
         * strings The strings must not have ";" semicolon at the end.
         * The caller is responsible for closing the connection
         *
         * @param connection a connection for preparing the statement
         * @param commands the commands
         * @return true if all commands were executed
         */
        fun executeCommands(connection: Connection, commands: List<String>): Boolean {
            var executed = true
            try {
                connection.autoCommit = false
                for (cmd in commands) {
                    executed = executeCommand(connection, cmd)
                    if (!executed) {
                        logger.trace { "Rolling back command on database: $cmd" }
                        connection.rollback()
                        break
                    }
                }
                if (executed) {
                    logger.trace { "Committing commands on database." }
                    connection.commit()
                }
                connection.autoCommit = true
            } catch (ex: SQLException) {
                executed = false
                logger.trace { "The commands were not executed for database" }
                logger.error(ex) { "SQLException: " }
            }
            return executed
        }

        /**
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *  Retrieves the table and schema information from the database meta-data.
         *  @param connection A valid and open connection to a database.
         */
        fun dbTablesFromMetaData(connection: Connection): List<DbSchemaInfo> {
            try {
                logger.trace { "Getting the metadata for a database" }
                val metaData = connection.metaData
                // fix for duck db due to their bad table type naming
                val type = if (metaData is DuckDBDatabaseMetaData) {
                    "BASE TABLE"
                } else {
                    "TABLE"
                }
                // because schema name pattern is null and table name pattern is null,
                // and type is TABLE we get ALL non-system tables (user defined) and the schema that
                // they are within
                val list = mutableListOf<DbSchemaInfo>()
                val rs = metaData.getTables(null, null, null, arrayOf(type))
                while (rs.next()) {
                    val c = rs.getString("TABLE_CAT")
                    val t = rs.getString("TABLE_NAME")
                    val s = rs.getString("TABLE_SCHEM")
                    list.add(DbSchemaInfo(c, s, t))
                }
                rs.close()
                return list
            } catch (e: SQLException) {
                logger.error { "Unable to get database catalog and schema information. The meta data was not available." }
                logger.error { "$e" }
                throw e
            }
        }

        /**
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *  Retrieves the view information from the database meta-data.
         *  @param connection A valid and open connection to a database.
         */
        fun dbViewsFromMetaData(connection: Connection): List<DbSchemaInfo> {
            try {
                logger.trace { "Getting the metadata for a database" }
                val metaData = connection.metaData
                // because schema name pattern is null and table name pattern is null,
                // and type is VIEW we get ALL non-system views (user defined) and the schema that
                // they are within
                val list = mutableListOf<DbSchemaInfo>()
                val rs = metaData.getTables(null, null, null, arrayOf("VIEW"))
                while (rs.next()) {
                    val c = rs.getString("TABLE_CAT")
                    val t = rs.getString("TABLE_NAME")
                    val s = rs.getString("TABLE_SCHEM")
                    list.add(DbSchemaInfo(c, s, t))
                }
                rs.close()
                return list
            } catch (e: SQLException) {
                logger.error { "Unable to get the meta data was not available for a database." }
                logger.error { "$e" }
                throw e
            }
        }

        /**
         *  Retrieves the names of the schemas from the database meta-data.
         *  The connection should be open and is not closed during this function.
         *
         *  It is the caller's responsibility to close the connection when appropriate.
         *  @param connection A valid and open connection to a database.
         */
        fun schemaNames(connection: Connection): List<String> {
            try {
                logger.trace { "Retrieving the list of schema names in database metadata" }
                val set = mutableSetOf<String>()
                val metaData = connection.metaData
                val rs = metaData.schemas
                while (rs.next()) {
                    set.add(rs.getString("TABLE_SCHEM"))
                }
                rs.close()
                return set.toList()
            } catch (e: SQLException) {
                logger.error { "Unable to get database schemas. The meta data was not available." }
                logger.error { "$e" }
                throw e
            }
        }

        /**
         * The name of the schema is first checked for an exact lexicographical match.
         * If a match occurs, the schema is returned.  If a lexicographical match fails,
         * then a check for a match ignoring the case of the string is performed.
         * This is done because SQL identifier names should be case-insensitive.
         * If neither matches then false is returned.
         *
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *
         *  @param connection A valid and open connection to a database.
         * @param schemaName the schema name to check
         * @return true if the database contains a schema with the provided name
         */
        fun containsSchema(connection: Connection, schemaName: String): Boolean {
            val schemaNames = schemaNames(connection)
            for (name in schemaNames) {
                if (name == schemaName) {
                    return true
                } else if (name.equals(schemaName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        /**  Checks if the named view is within the schema based on the connection.
         *
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *
         *  @param connection A valid and open connection to a database.
         * @param viewName the unqualified view name to find as a string
         * @return true if the database contains the named view
         */
        fun containsView(
            connection: Connection,
            viewName: String,
            schemaName: String?
        ): Boolean {
            val vNames = viewNames(connection, schemaName)
            for (name in vNames) {
                if (name == viewName) {
                    return true
                } else if (name.equals(viewName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        /**  Checks if the named table is within the schema based on the connection.
         *
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *
         *  @param connection A valid and open connection to a database.
         * @param schemaName the name of the schema that should contain the table
         * @param tableName  a string representing the unqualified name of the table
         * @return true if it exists
         */
        fun containsTable(connection: Connection, tableName: String, schemaName: String?): Boolean {
            val tNames = tableNames(connection, schemaName)
            for (n in tNames) {
                if ((n == tableName) || n.equals(tableName, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }

        /**
         *  Returns the user-defined schema names and the table names within each schema,
         *  The key can be null because the database might not support the schema concept.
         *  There can be table names associated with the key "null".
         *
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *  @param connection A valid and open connection to a database.
         */
        fun dbTableNamesBySchema(connection: Connection): Map<String?, List<String>> {
            val map = mutableMapOf<String?, MutableList<String>>()
            val dbs = dbTablesFromMetaData(connection)
            for (info in dbs) {
                // null is okay for a schema to represent db's that don't have schema concepts
                if (!map.containsKey(info.schemaName)) {
                    map[info.schemaName] = mutableListOf()
                }
                map[info.schemaName]!!.add(info.tableName)
            }
            return map
        }

        /**
         * Returns a list of table names associated with the schema based on the
         * supplied connection.
         *
         * The connection should be open and is not closed during this function.
         * It is the caller's responsibility to close the connection when appropriate.
         *
         * @param connection A valid and open connection to a database.
         * @param schemaName the name of the schema that should contain the tables. If null,
         * then a list of table names not associated with a schema may be returned. Or, if
         * the schema concept does not exist for the database, then the names of any user-defined
         * tables may be returned.
         * @return a list of table names within the schema. The list may be empty if no tables
         * are defined within the schema.
         */
        fun tableNames(connection: Connection, schemaName: String?): List<String> {
            val list = mutableListOf<String>()
            val dbSchemas = dbTableNamesBySchema(connection)
            for ((dbSchema, tblNames) in dbSchemas) {
                if (dbSchema == schemaName) {
                    list.addAll(tblNames)
                    return list
                } else if (dbSchema.equals(schemaName, ignoreCase = true)) {
                    list.addAll(tblNames)
                    return list
                }
            }
            return list
        }

        /**
         *  Returns the user-defined schema names and the view names within each schema,
         *  The key can be null because the database might not support the schema concept.
         *  There can be view names associated with the key "null".
         *
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *  @param connection A valid and open connection to a database.
         */
        fun dbViewNamesBySchema(connection: Connection): Map<String?, List<String>> {
            val map = mutableMapOf<String?, MutableList<String>>()
            val dbs = dbViewsFromMetaData(connection) // causes a db connection to occur
            for (info in dbs) {
                // null is okay for a schema to represent db's that don't have schema concepts
                if (!map.containsKey(info.schemaName)) {
                    map[info.schemaName] = mutableListOf()
                }
                map[info.schemaName]!!.add(info.tableName)
            }
            return map
        }

        /**
         *  Returns a list of views associated with the schema based on the supplied
         *  connection.
         *
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *
         *  @param connection A valid and open connection to a database.
         *  @param schemaName the name of the schema that should contain the views. If null,
         *  then a list of view names not associated with a schema may be returned. Or, if
         *  the schema concept does not exist for the database, then the names of any views
         *  may be returned.
         * @return a list of view names within the schema. The list may be empty if no views
         * are defined within the schema.
         */
        fun viewNames(connection: Connection, schemaName: String?): List<String> {
            val list = mutableListOf<String>()
            val dbSchemas = dbViewNamesBySchema(connection)
            for ((dbSchema, viewNames) in dbSchemas) {
                if (dbSchema == schemaName) {
                    list.addAll(viewNames)
                    return list
                } else if (dbSchema.equals(schemaName, ignoreCase = true)) {
                    list.addAll(viewNames)
                    return list
                }
            }
            return list
        }

        /** A simple wrapper to ease the use of JDBC for novices. Returns the results of a query in the
         * form of a JDBC ResultSet that is TYPE_FORWARD_ONLY and CONCUR_READ_ONLY .
         * Errors in the SQL are the user's responsibility. Any exceptions
         * are logged and squashed. It is the user's responsibility to close the ResultSet.  That is,
         * the statement used to create the ResultSet is not automatically closed.
         *
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *
         *  @param connection A valid and open connection to a database.
         * @param sql an SQL text string that is valid
         * @return the results of the query or null
         */
        fun fetchOpenResultSet(connection: Connection, sql: String): ResultSet? {
            var query: PreparedStatement? = null
            try {
                logger.trace { "Fetching open ResultSet for $sql" }
                query = connection.prepareStatement(sql)
                return query.executeQuery()
            } catch (e: SQLException) {
                logger.warn(e) { "The query $sql was not executed." }
                query?.close()
            }
            return null
        }

        /** A simple wrapper to ease the use of JDBC for novices. Returns the results of a query in the
         * form of a JDBC CachedRowSet. Errors in the SQL are the user's responsibility. Any exceptions
         * are logged and squashed.  The underlying query is closed.
         *
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *
         *  @param connection A valid and open connection to a database.
         * @param sql an SQL text string that is valid
         * @return the results of the query or null if there was a problem
         */
        fun fetchCachedRowSet(connection: Connection, sql: String): CachedRowSet? {
            try {
                logger.trace { "Fetching CachedRowSet for $sql" }
                val query = connection.prepareStatement(sql)
                val rs = query.executeQuery()
                val crs = createCachedRowSet(rs)
                query.close()
                return crs
            } catch (e: SQLException) {
                logger.warn(e) { "The query $sql was not executed." }
            }
            return null
        }

        /**
         *  Determines the number of rows in the table within the schema based on
         *  the supplied connection.
         *
         *  The connection should be open and is not closed during this function.
         *  It is the caller's responsibility to close the connection when appropriate.
         *
         *  @param connection A valid and open connection to a database.
         *
         * @param schemaName the schema containing the table
         * @param tableName the name of the table within the schema
         * @return the number of rows in the table
         */
        fun numRows(connection: Connection, tableName: String, schemaName: String?): Long {
            val tblName = if (schemaName != null) {
                "${schemaName}.${tableName}"
            } else {
                tableName
            }
            try {
                val stmt: Statement = connection.createStatement()
                val sql = "select count(*) from $tblName"
                val rs: ResultSet = stmt.executeQuery(sql)
                rs.next()
                val count = rs.getLong(1)
                stmt.close()
                return count
            } catch (e: SQLException) {
                logger.warn { "Could not count the number of rows in $tableName" }
            }
            return 0
        }

        /**
         * Method to parse a SQL script for the database. The script honors SQL
         * comments and separates each SQL command into a list of strings, 1 string
         * for each command. The list of queries is returned.
         *
         * The script should have each command end in a semicolon, ; The best
         * comment to use is #. All characters on a line after # will be stripped.
         * Best to put # as the first character of a line with no further SQL on the
         * line
         *
         * Based on the work described here:
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
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                //boolean end = parseCommandString(line, cmd);
                val option = parseLine(line!!, cmd)
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
         * @param delimiter the end of command indicator
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
                    logger.warn { "Message: ${warning!!.message}" }
                    warning = warning.nextWarning
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
//                delimiter = delimiterMatcher.group(1)
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
                    logger.trace { "Parsed SQL: $command" }
                    return true
                }
            }
            return false
        }

        /** The ResultSet is processed through all rows.
         *
         * @param resultSet the result set to write out as csv delimited
         * @param header true (default) indicates include the header
         * @param writer the writer to use
         */
        fun writeAsCSV(resultSet: ResultSet, header: Boolean = true, writer: Writer) {
//            require(!resultSet.isClosed) { "The supplied ResultSet is closed!" }
            //okay because resultSet is only read from
            val printer = if (header) {
                CSVFormat.DEFAULT.builder()
                    .setHeader(resultSet).build().print(writer)
            } else {
                CSVFormat.DEFAULT.builder().build().print(writer)
            }
            //TODO this is not working OR data is bad to start
            printer.printRecords(resultSet)
            printer.close(true)
        }

        /**
         * @param rowSet the result set to write out as text
         * @param writer the writer to use
         */
        fun writeAsText(rowSet: CachedRowSet, writer: PrintWriter) {
            //okay because rowSet is only read from
            val tw = DbResultsAsText(rowSet)
            writer.println(tw.header)
            val iterator = tw.formattedRowIterator()
            while (iterator.hasNext()) {
                writer.println(iterator.next())
                writer.println(tw.rowSeparator)
            }
            rowSet.beforeFirst()
            writer.flush()
        }

        /**
         * @param rowSet the CachedRowSet to write out as Markdown text
         * @param writer the writer to use
         */
        fun writeAsMarkdown(rowSet: CachedRowSet, writer: PrintWriter) {
            //okay because rowSet is only read from
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
            rowSet.beforeFirst()
            writer.flush()
        }

        /** Populates a CachedRowSet based on the supplied ResultSet
         *
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
            val list = mutableListOf<ColumnMetaData>()
            val md = resultSet.metaData
            if (md != null) {
                val nc = md.columnCount
                for (c in 1..nc) {
                    val catalogName: String = md.getCatalogName(c)?.toString() ?: ""
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

        /** This method inserts the data into the prepared statement as a batch insert.
         *  The statement is not executed.
         *
         * @param rowData the data to be inserted
         * @param numColumns the column metadata for the row set
         * @param preparedStatement the prepared statement to use
         * @return returns true if the data was inserted false if something went wrong and no insert made
         */
        fun addBatch(
            rowData: List<Any?>,
            numColumns: Int,
            preparedStatement: PreparedStatement
        ): Boolean {
            return try {
                for (colIndex in 1..numColumns) {
                    //looks like it does the updates
                    preparedStatement.setObject(colIndex, rowData[colIndex - 1])
                    logger.trace { "Updated column $colIndex with data ${rowData[colIndex - 1]}" }
                }
                preparedStatement.addBatch()
                true
            } catch (e: SQLException) {
                false
            }
        }

        /** This method inserts the data into the prepared statement as a batch insert.
         *  The statement is not executed.
         *
         * @param rowData the data to be inserted
         * @param numColumns the column metadata for the row set
         * @param preparedStatement the prepared statement to use
         * @return returns true if the data was inserted false if something went wrong and no insert made
         */
        fun addBatch(
            rowData: Array<Any?>,
            numColumns: Int,
            preparedStatement: PreparedStatement
        ): Boolean {
            return try {
                for (colIndex in 1..numColumns) {
                    //looks like it does the updates
                    preparedStatement.setObject(colIndex, rowData[colIndex - 1])
                    logger.trace { "Updated column $colIndex with data ${rowData[colIndex - 1]}" }
                }
                preparedStatement.addBatch()
                true
            } catch (e: SQLException) {
                false
            }
        }

        /**
         *
         * @param con an active connection to the database
         * @param tableName the name of the table to be inserted into
         * @param numColumns the number of columns starting from the left to insert into
         * @param schemaName the schema containing the table
         * @return a prepared statement that can perform the insert if given the appropriate column values
         */
        fun makeInsertPreparedStatement(
            con: Connection,
            tableName: String,
            numColumns: Int,
            schemaName: String?
        ): PreparedStatement {
            require(containsTable(con, tableName, schemaName)) { "The database does not contain table $tableName in schema $schemaName" }
            val sql = insertIntoTableStatementSQL(tableName, numColumns, schemaName)
            return con.prepareStatement(sql)
        }

        /**
         *
         *  Uses the longLastingConnection property for the connection for metadata checking.
         *
         * @param con an active connection to the database
         * @param tableName the name of the table to be inserted into
         * @param fieldName the field name controlling the where clause
         * @param schemaName the schema containing the table
         * @return a prepared statement that can perform the deletion if given the appropriate condition value
         */
        fun makeDeleteFromPreparedStatement(
            con: Connection,
            tableName: String,
            fieldName: String,
            schemaName: String?
        ): PreparedStatement {
            require(containsTable(con, tableName,schemaName)) { "The database does not contain table $tableName in schema $schemaName" }
            val sql = deleteFromTableWhereSQL(tableName, fieldName, schemaName)
            return con.prepareStatement(sql)
        }

        enum class ColumnType {
            BOOLEAN, DOUBLE, FLOAT, INSTANT, INTEGER, LOCAL_DATE, LOCAL_TIME,
            LONG, SHORT, STRING
        }

        /**
         * @param resultSet the result set that needs conversion
         * @return the result set as a DataFrame
         */
        fun toDataFrame(resultSet: ResultSet): AnyFrame {
            val columnMetaData = columnMetaData(resultSet)
            val cachedRowSet = createCachedRowSet(resultSet)
            val colList = mutableListOf<ValueColumn<Any?>>()
            for ((index, cmd) in columnMetaData.withIndex()) {
                val data = fillFromColumn(index + 1, cachedRowSet)
                val c = makeDataFrameColumn(cmd, data)
                colList.add(c)
            }
            return dataFrameOf(colList)
        }

        /**
         * Provides a mapping of SQL types to the DataFrame types to assist with type inference
         */
        private fun makeDataFrameColumn(columnMetaData: ColumnMetaData, data: List<Any?>): ValueColumn<Any?> {
            return when (columnMetaData.type) {
                Types.BIT, Types.BOOLEAN -> {
                    DataColumn.createValueColumn(columnMetaData.label, data, typeOf<Boolean>())
                }

                Types.DECIMAL, Types.DOUBLE, Types.FLOAT, Types.NUMERIC, Types.REAL -> {
                    DataColumn.createValueColumn(columnMetaData.label, data, typeOf<Double>())
                }

                Types.TIMESTAMP -> {
                    DataColumn.createValueColumn(columnMetaData.label, data, typeOf<Instant>())
                }

                Types.INTEGER -> {
                    DataColumn.createValueColumn(columnMetaData.label, data, typeOf<Int>())
                }

                Types.DATE -> {
                    DataColumn.createValueColumn(columnMetaData.label, data, typeOf<LocalDate>())
                }

                Types.TIME -> {
                    DataColumn.createValueColumn(columnMetaData.label, data, typeOf<LocalTime>())
                }

                Types.BIGINT -> {
                    DataColumn.createValueColumn(columnMetaData.label, data, typeOf<Long>())
                }

                Types.SMALLINT, Types.TINYINT -> {
                    DataColumn.createValueColumn(columnMetaData.label, data, typeOf<Short>())
                }

                Types.BINARY, Types.CHAR, Types.NCHAR, Types.NVARCHAR,
                Types.VARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR -> {
                    DataColumn.createValueColumn(columnMetaData.label, data, typeOf<String>())
                }

                else -> {
                    DataColumn.createValueColumn(columnMetaData.label, mutableListOf<Any>())
                }
            }
        }

        /**
         *  Fills a list with the indicated column of the CachedRowSet
         */
        fun fillFromColumn(column: Int, cachedRowSet: CachedRowSet): List<Any?> {
            val toCollection: MutableCollection<*> = cachedRowSet.toCollection(column)
            return toCollection.toList()
        }

        /**
         *  Returns a string SQL to be used to delete all records from a table
         *
         *   delete from schemaName.tableName
         */
        fun deleteAllFromTableSQL(tableName: String, schemaName: String?): String {
            require(tableName.isNotEmpty()) { "The table name was empty when making the delete statement" }
            return if (!schemaName.isNullOrEmpty()) {
                "delete from ${schemaName}.$tableName"
            } else {
                "delete from $tableName"
            }
        }

        /**
         *  Returns a string to be used in a prepared statement
         *   delete from schemaName.tableName where fieldName = ?
         */
        fun deleteFromTableWhereSQL(tableName: String, fieldName: String, schemaName: String?): String {
            require(tableName.isNotEmpty()) { "The table name was empty when making the delete statement" }
            require(fieldName.isNotEmpty()) { "The field name was empty when making the delete statement" }
            return deleteAllFromTableSQL(tableName, schemaName) + " where $fieldName = ?"
        }

        /** Creates an SQL string that can be used to insert data into the table
         *
         *  insert into schemaName.tableName values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         *
         *  The number of parameter values is controlled by the number of columns parameter.
         *
         * @param tableName the name of the table to be inserted into
         * @param numColumns the number of columns starting from the left to insert into
         * @param schemaName the schema containing the table
         * @return a generic SQL insert statement with appropriate number of parameters for the table
         */
        fun insertIntoTableStatementSQL(
            tableName: String,
            numColumns: Int,
            schemaName: String? = null
        ): String {
            // assume all columns have the same table name and schema name
            require(tableName.isNotEmpty()) { "The table name was empty when making the insert statement" }
            val qm = CharArray(numColumns)
            qm.fill('?', toIndex = numColumns)
            val inputs = qm.joinToString(", ", prefix = "(", postfix = ")")
            return if ((schemaName != null) && (schemaName.isNotEmpty())) {
                "insert into ${schemaName}.${tableName} values $inputs"
            } else {
                "insert into $tableName values $inputs"
            }
        }

        /** Creates an SQL string that can be used to insert data into the table
         *
         *  insert into schemaName.tableName (fieldName1, fieldName2, fieldName3) values (?, ?, ?)
         *
         *  The number of parameter values is controlled by the size of the field array.
         *  This assumes that the supplied field names are valid for the supplied table name.
         *
         * @param tableName the name of the table to be inserted into
         * @param fields the names of the fields that will receive data within the table
         * @param schemaName the schema containing the table
         * @return a generic SQL insert statement with appropriate number of parameters for the table
         */
        fun insertIntoTableStatementSQL(
            tableName: String,
            fields: List<String>,
            schemaName: String? = null
        ): String {
            // assume all columns have the same table name and schema name
            require(tableName.isNotEmpty()) { "The table name was empty when making the insert statement" }
            require(fields.isNotEmpty()) { "The insert fields was empty when making the insert statement" }
            val qm = CharArray(fields.size)
            qm.fill('?', toIndex = fields.size)
            val inputStr = qm.joinToString(", ", prefix = "(", postfix = ")")
            val fieldStr = fields.joinToString(", ", prefix = "(", postfix = ")")
            return if (!schemaName.isNullOrEmpty()) {
                "insert into ${schemaName}.${tableName} $fieldStr values $inputStr"
            } else {
                "insert into $tableName $fieldStr values $inputStr"
            }
        }

        /**
         * Creates an SQL string for a prepared statement to update records in a table.
         * The update and where field lists should have unique elements and at least 1 element.
         * ```
         *     val fields = listOf("A", "B", "C")
         *     val where = listOf("D", "E")
         *     val sql = DatabaseIfc.updateTableStatementSQL("baseball", fields, where, "league")
         *
         *     Produces:
         *     update league.baseball set A = ?, B = ?, C = ? where D = ? and E = ?
         * ```
         */
        fun updateTableStatementSQL(
            tableName: String,
            updateFields: List<String>,
            whereFields: List<String>,
            schemaName: String?
        ): String {
            require(tableName.isNotEmpty()) { "The table name was empty when making the delete statement" }
            require(updateFields.isNotEmpty()) { "The fields to update was empty" }
            require(whereFields.isNotEmpty()) { "The where clause fields were empty" }
            val start = if (!schemaName.isNullOrEmpty()) {
                "update ${schemaName}.$tableName set "
            } else {
                "update $tableName set "
            }
            val sql = StringBuilder(start)
            sql.append(updateFields.joinToString(separator = " = ?, ", postfix = " = ? "))
            sql.append("where ")
            sql.append(whereFields.joinToString(separator = " = ? and ", postfix = " = ? "))
            return sql.toString()
        }

    }

}


