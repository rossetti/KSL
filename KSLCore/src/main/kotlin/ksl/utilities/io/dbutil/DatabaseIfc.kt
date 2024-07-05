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
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
 *  A data class to hold meta-data information about the tables and the containing schema
 */
data class DbSchemaInfo(var catalogName: String?, var schemaName: String?, var tableName: String)

/**
 *  An interface that defines basic I/O capabilities for a database.
 */
interface DatabaseIOIfc {

    var outputDirectory: OutputDirectory

    /**
     * identifying string representing the database. This has no relation to
     * the name of the database on disk or in the dbms. The sole purpose is for labeling of output
     */
    var label: String

    /**
     * Sets the name of the default schema
     *
     *  name for the default schema, may be null
     */
    var defaultSchemaName: String?

    /**
     * @return a list of all schemas within the database
     */
    val schemas: List<String>

    /**
     * @return a list of all view names within the database
     */
    val views: List<String>

    /**
     * @return a list of all table names within the database
     */
    val userDefinedTables: List<String>

    /**
     * Writes the table as comma separated values
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the name of the table to write
     * @param header true means column names as the header included
     * @param out       the PrintWriter to write to.  The print writer is not closed.
     */
    fun exportTableAsCSV(
        tableName: String,
        out: PrintWriter = outputDirectory.createPrintWriter("${tableName}.csv"),
        schemaName: String? = defaultSchemaName,
        header: Boolean = true
    )

    /**
     * Prints the table as comma separated values to the console
     * @param schemaName the name of the schema that should contain the table
     * @param tableName the name of the table to print
     */
    fun printTableAsCSV(tableName: String, schemaName: String? = defaultSchemaName, header: Boolean = true)

    /**
     * Writes the table as prettified text.
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     * @param out       the PrintWriter to write to.  The print writer is not closed
     */
    fun writeTableAsText(
        tableName: String,
        out: PrintWriter = outputDirectory.createPrintWriter("${tableName}.txt"),
        schemaName: String? = defaultSchemaName
    )

    /**
     * Prints the table as prettified text to the console
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     */
    fun printTableAsText(tableName: String, schemaName: String? = defaultSchemaName)

    /**
     * Prints all tables as text to the console
     *
     * @param schemaName the name of the schema that should contain the tables
     */
    fun printAllTablesAsText(schemaName: String? = defaultSchemaName)

    /**
     * Writes all tables as text
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    fun writeAllTablesAsText(
        out: PrintWriter = outputDirectory.createPrintWriter("${label}.txt"),
        schemaName: String? = defaultSchemaName
    )

    /**
     * Writes the table as prettified text.
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     * @param out       the PrintWriter to write to.  The print writer is not closed
     */
    fun writeTableAsMarkdown(
        tableName: String,
        out: PrintWriter = outputDirectory.createPrintWriter("${tableName}.md"),
        schemaName: String? = defaultSchemaName
    )

    /**
     * Prints the table as prettified text to the console
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     */
    fun printTableAsMarkdown(tableName: String, schemaName: String? = defaultSchemaName)

    /**
     * Prints all tables as text to the console
     *
     * @param schemaName the name of the schema that should contain the tables
     */
    fun printAllTablesAsMarkdown(schemaName: String? = defaultSchemaName)

    /**
     * Writes all tables as text
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    fun writeAllTablesAsMarkdown(
        out: PrintWriter = outputDirectory.createPrintWriter("${label}.md"),
        schemaName: String? = defaultSchemaName
    )

    /**
     * Writes all tables as text
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    fun writeAllViewsAsMarkdown(out: PrintWriter, schemaName: String?)

    /**
     * Writes all tables as separate comma separated value files into the supplied
     * directory. The files are written to text files using the same name as
     * the tables in the database
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param pathToOutPutDirectory the path to the output directory to hold the csv files
     * @param header  true means all files will have the column headers
     */
    fun exportAllTablesAsCSV(
        pathToOutPutDirectory: Path = outputDirectory.csvDir,
        schemaName: String? = defaultSchemaName,
        header: Boolean = true
    )

    /**
     * Writes all tables as separate comma separated value files into the supplied
     * directory. The files are written to text files using the same name as
     * the tables in the database
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param pathToOutPutDirectory the path to the output directory to hold the csv files
     * @param header  true means all files will have the column headers
     */
    fun exportAllViewsAsCSV(
        pathToOutPutDirectory: Path,
        schemaName: String?,
        header: Boolean
    )

    /**
     * Prints the insert queries associated with the supplied table to the console
     * @param schemaName the name of the schema that should contain the table
     * @param tableName the unqualified name of the table
     */
    fun printInsertQueries(tableName: String, schemaName: String? = defaultSchemaName)

    /**
     * Writes the insert queries associated with the supplied table to the PrintWriter
     * @param schemaName the name of the schema that should contain the table
     * @param tableName the unqualified name of the table
     * @param out       the PrintWriter to write to
     */
    fun exportInsertQueries(
        tableName: String,
        out: PrintWriter = outputDirectory.createPrintWriter("${tableName}.sql"),
        schemaName: String? = defaultSchemaName
    )

    /**
     * Prints all table data as insert queries to the console
     *
     * @param schemaName the name of the schema that should contain the tables
     */
    fun printAllTablesAsInsertQueries(schemaName: String? = defaultSchemaName)

    /**
     * Writes all table data as insert queries to the PrintWriter
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    fun exportAllTablesAsInsertQueries(schemaName: String? = defaultSchemaName, out: PrintWriter)

    /** Writes each table in the schema to an Excel workbook with each table being placed
     *  in a new sheet with the sheet name equal to the name of the table. The column names
     *  for each table are written as the first row of each sheet.
     *
     * @param schemaName the name of the schema containing the tables or null
     * @param wbName the name of the workbook
     * @param wbDirectory the directory to store the workbook
     */
    fun exportToExcel(
        schemaName: String? = defaultSchemaName,
        wbName: String = label,
        wbDirectory: Path = outputDirectory.excelDir
    )

    /** Writes each table in the list to an Excel workbook with each table being placed
     *  in a new sheet with the sheet name equal to the name of the table. The column names
     *  for each table are written as the first row of each sheet.
     *
     * @param schemaName the name of the schema containing the tables or null
     * @param tableNames the names of the tables to write to a workbook
     * @param wbName the name of the workbook
     * @param wbDirectory the directory to store the workbook
     */
    fun exportToExcel(
        tableNames: List<String>,
        schemaName: String? = defaultSchemaName,
        wbName: String = label.substringBeforeLast("."),
        wbDirectory: Path = outputDirectory.excelDir
    )

    /**
     * Opens the workbook for reading only and writes the sheets of the workbook into database tables.
     * The list of names is the names of the
     * sheets in the workbook and the names of the tables that need to be written. They are in the
     * order that is required for entering data so that no integrity constraints are violated. The
     * underlying workbook is closed after the operation.
     *
     * @param pathToWorkbook the path to the workbook. Must be valid workbook with .xlsx extension
     * @param skipFirstRow   if true the first row of each sheet is skipped
     * @param schemaName the name of the schema containing the named tables
     * @param tableNames     the names of the sheets and tables in the order that needs to be written
     * @throws IOException an io exception
     */
    fun importWorkbookToSchema(
        pathToWorkbook: Path,
        skipFirstRow: Boolean = true,
        schemaName: String? = defaultSchemaName,
        tableNames: List<String>
    )

    /** Copies the rows from the sheet to the table.  The copy is assumed to start
     * at row 1, column 1 (i.e. cell A1) and proceed to the right for the number of columns in the
     * table and the number of rows of the sheet.  The copy is from the perspective of the table.
     * That is, all columns of a row of the table are attempted to be filled from a corresponding
     * row of the sheet.  If the row of the sheet does not have cell values for the corresponding column, then
     * the cell is interpreted as a null value when being placed in the corresponding column.  It is up to the client
     * to ensure that the cells in a row of the sheet are data type compatible with the corresponding column
     * in the table.  Any rows that cannot be transfer in their entirety are logged to the supplied PrintWriter
     *
     * @param sheet the sheet that has the data to transfer to the ResultSet
     * @param tableName the table to copy into
     * @param numColumns the number of columns in the sheet to copy into the table
     * @param schemaName the name of the schema containing the tabel
     * @param numRowsToSkip indicates the number of rows to skip from the top of the sheet. Use 1 (default) if the sheet has
     * a header row
     *  @param rowBatchSize the number of rows to accumulate in a batch before completing a transfer
     *  @param unCompatibleRows a file to hold the rows that are not transferred in a string representation
     */
    fun importSheetToTable(
        sheet: Sheet,
        tableName: String,
        numColumns: Int,
        schemaName: String? = defaultSchemaName,
        numRowsToSkip: Int = 1,
        rowBatchSize: Int = 100,
        unCompatibleRows: PrintWriter = outputDirectory.createPrintWriter("BadRowsForSheet_${sheet.sheetName}")
    ): Boolean
}

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

    val dbURL: String?

    /**
     * @param schemaName the name of the schema that should contain the tables. If null,
     * then a list of table names not associated with a schema is returned. Or, if
     * the schema concept does not exist for the database, then the names of any user-defined
     * tables are returned.
     * @return a list of table names within the schema. The list may be empty if no tables
     * are defined within the schema.
     */
    fun tableNames(schemaName: String?): List<String> {
        val list = mutableListOf<String>()
        val dbSchemas = dbSchemas() // this makes a connection to the db and gets the metadata
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
//        val list = mutableListOf<String>()
//        if (containsSchema(schemaName)) {
//            try {
//                logger.trace { "Getting a connection to retrieve the list of table names for schema $schemaName in database $label" }
//                getConnection().use { connection ->
//                    val metaData = connection.metaData
//                    val rs = metaData.getTables(null, schemaName, null, arrayOf("TABLE"))
//                    while (rs.next()) {
//                        list.add(rs.getString("TABLE_NAME"))
//                    }
//                    rs.close()
//                }
//            } catch (e: SQLException) {
//                logger.warn(e) { "Unable to get table names for schema $schemaName. The meta data was not available for database $label" }
//            }
//        }
//        return list
    }

//    /**
//     * @param schemaName the name of the schema that should contain the tables
//     * @return a list of table names within the schema
//     */
//    fun tableNames2(schemaName: String? = null): List<String> {
//        return dbSchemas()[schemaName]?.toList() ?: emptyList()
//    }

    /**
     * @param schemaName the name of the schema that should contain the views
     * @return a list of view names within the schema
     */
    fun viewNames(schemaName: String): List<String> {
        val list = mutableListOf<String>()
        if (containsSchema(schemaName)) { //TODO this check should be unnecessary
            try {
                logger.trace { "Getting a connection to retrieve the list of view names for schema $schemaName in database $label" }
                getConnection().use { connection ->
                    val metaData = connection.metaData
                    val rs = metaData.getTables(null, schemaName, null, arrayOf("VIEW"))
                    while (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"))
                    }
                    rs.close()
                }
            } catch (e: SQLException) {
                logger.warn(e) { "Unable to get view names for schema $schemaName. The meta data was not available for database $label" }
            }
        }
        return list
    }

    /**
     * @return a list of all table names within the database regardless of schema
     */
    override val userDefinedTables: List<String>
        get() {
            //TODO what if multiple schemas contain the same table name
            val list = mutableListOf<String>()
            try {
                logger.trace { "Getting a connection to retrieve the list of user defined table names in database $label" }
                getConnection().use { connection ->
                    val metaData = connection.metaData
                    val rs = metaData.getTables(null, null, null, arrayOf("TABLE"))
                    while (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"))
                    }
                    rs.close()
                }
            } catch (e: SQLException) {
                logger.warn { "Unable to get database user defined tables. The meta data was not available for database $label" }
                logger.warn { "$e" }
            }
            return list
        }

    /**
     * @return a list of all schemas within the database
     */
    override val schemas: List<String>
        get() {
            val list = mutableListOf<String>()
            val dbSchema = dbSchemas() // this connects to the database to get the meta data
            for (s in dbSchema.keys) {
                if (s != null) list.add(s)
            }
//            try {
//                logger.trace { "Getting a connection to retrieve the list of schema names in database $label" }
//                getConnection().use { connection ->
//                    val metaData = connection.metaData
//                    val rs = metaData.schemas
//                    while (rs.next()) {
//                        list.add(rs.getString("TABLE_SCHEM"))
//                    }
//                    rs.close()
//                }
//            } catch (e: SQLException) {
//                logger.warn { "Unable to get database schemas. The meta data was not available for database $label" }
//                logger.warn { "$e" }
//            }
            return list
        }

    /**
     * @return a list of all view names within the database
     */
    override val views: List<String>
        get() {
            val list = mutableListOf<String>()
            try {
                logger.trace { "Getting a connection to retrieve the list of views in database $label" }
                getConnection().use { connection ->
                    val metaData = connection.metaData
                    val rs = metaData.getTables(null, null, null, arrayOf("VIEW"))
                    while (rs.next()) {
                        list.add(rs.getString("TABLE_NAME"))
                    }
                    rs.close()
                }
            } catch (e: SQLException) {
                logger.warn { "Unable to get database views. The meta data was not available for database $label" }
                logger.warn { "$e" }
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
     * @param viewName the unqualified view name to find as a string
     * @return true if the database contains the named view
     */
    fun containsView(viewName: String): Boolean {
        val viewNames = views
        for (name in viewNames) {
            if (name == viewName) {
                return true
            } else if (name.equals(viewName, ignoreCase = true)) {
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
     * @param tableName      a string representing the unqualified name of the table
     * @return true if it exists
     */
    fun containsTable(schemaName: String, tableName: String): Boolean {
        val tNames = tableNames(schemaName)
        for (n in tNames) {
            if ((n == tableName) || n.equals(tableName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     *  Returns the user-defined schema names and the table names within each schema,
     */
    fun dbSchemas(): Map<String?, Set<String>> {
        val map = mutableMapOf<String?, MutableSet<String>>()
        val dbs = dbTablesFromMetaData()
        for (info in dbs) {
            // null is okay for a schema to represent db's that don't have schema concepts
            if (!map.containsKey(info.schemaName)) {
                map[info.schemaName] = mutableSetOf()
            }
            map[info.schemaName]!!.add(info.tableName)
        }
        return map
    }

    /**
     *  Retrieves the table and schema information from the database meta-data
     */
    fun dbTablesFromMetaData(): List<DbSchemaInfo> {
        val list = mutableListOf<DbSchemaInfo>()
        try {
            logger.trace { "Getting a connection to retrieve the catalog and schema information in database $label" }
            getConnection().use { connection ->
                val metaData = connection.metaData
                // fix for duck db due to their bad table type naming
                val type = if (metaData is DuckDBDatabaseMetaData) {
                    "BASE TABLE"
                } else {
                    "TABLE"
                }
                val rs = metaData.getTables(null, null, null, arrayOf(type))
                while (rs.next()) {
                    val c = rs.getString("TABLE_CAT")
                    val t = rs.getString("TABLE_NAME")
                    val s = rs.getString("TABLE_SCHEM")
                    list.add(DbSchemaInfo(c, s, t))
                }
                rs.close()
            }
        } catch (e: SQLException) {
            logger.warn { "Unable to get database catalog and schema information. The meta data was not available for database $label" }
            logger.warn { "$e" }
        }
        return list
    }

    /**
     * Writes the table as comma separated values
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the name of the table to write
     * @param header true means column names as the header included
     * @param out       the PrintWriter to write to.  The print writer is not closed.
     */
    override fun exportTableAsCSV(
        tableName: String,
        out: PrintWriter,
        schemaName: String?,
        header: Boolean
    ) {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                logger.trace { "Schema: $schemaName does not exist in database $label" }
                return
            }
        }
        if (!containsTable(tableName) && !containsView(tableName)) {
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
     * Prints the table as comma separated values to the console
     * @param schemaName the name of the schema that should contain the table
     * @param tableName the name of the table to print
     */
    override fun printTableAsCSV(tableName: String, schemaName: String?, header: Boolean) {
        exportTableAsCSV(tableName, PrintWriter(System.out), schemaName, header)
    }

    /**
     * Writes the table as prettified text.
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     * @param out       the PrintWriter to write to.  The print writer is not closed
     */
    override fun writeTableAsText(tableName: String, out: PrintWriter, schemaName: String?) {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                logger.info { "Schema: $schemaName does not exist in database $label" }
                return
            }
        }
        if (!containsTable(tableName) && !containsView(tableName)) {
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
     * Prints the table as prettified text to the console
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     */
    override fun printTableAsText(tableName: String, schemaName: String?) {
        writeTableAsText(tableName, PrintWriter(System.out), schemaName)
    }

    /**
     * Prints all tables as text to the console
     *
     * @param schemaName the name of the schema that should contain the tables
     */
    override fun printAllTablesAsText(schemaName: String?) {
        writeAllTablesAsText(PrintWriter(System.out), schemaName)
    }

    /**
     * Writes all tables as text
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    override fun writeAllTablesAsText(out: PrintWriter, schemaName: String?) {
        val tables = if (schemaName != null) {
            tableNames(schemaName)
        } else {
            userDefinedTables
        }
        for (table in tables) {
            writeTableAsText(table, out, schemaName)
        }
    }

    /**
     * Writes the table as prettified text.
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     * @param out       the PrintWriter to write to.  The print writer is not closed
     */
    override fun writeTableAsMarkdown(tableName: String, out: PrintWriter, schemaName: String?) {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                logger.info { "Schema: $schemaName does not exist in database $label" }
                return
            }
        }
        if (!containsTable(tableName) && !containsView(tableName)) {
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
     * Prints the table as prettified text to the console
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     */
    override fun printTableAsMarkdown(tableName: String, schemaName: String?) {
        writeTableAsMarkdown(tableName, PrintWriter(System.out), schemaName)
    }

    /**
     * Prints all tables as text to the console
     *
     * @param schemaName the name of the schema that should contain the tables
     */
    override fun printAllTablesAsMarkdown(schemaName: String?) {
        writeAllTablesAsMarkdown(PrintWriter(System.out), schemaName)
    }

    /**
     * Writes all tables as text
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    override fun writeAllViewsAsMarkdown(out: PrintWriter, schemaName: String?) {
        val viewList = if (schemaName != null) {
            viewNames(schemaName)
        } else {
            views
        }
        for (view in viewList) {
            writeTableAsMarkdown(view, out, schemaName)
        }
    }

    /**
     * Writes all tables as text
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    override fun writeAllTablesAsMarkdown(out: PrintWriter, schemaName: String?) {
        val tables = if (schemaName != null) {
            tableNames(schemaName)
        } else {
            userDefinedTables
        }
        for (table in tables) {
            writeTableAsMarkdown(table, out, schemaName)
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
    override fun exportAllTablesAsCSV(
        pathToOutPutDirectory: Path,
        schemaName: String?,
        header: Boolean
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
            exportTableAsCSV(table, writer, schemaName, header)
            writer.close()
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
    override fun exportAllViewsAsCSV(
        pathToOutPutDirectory: Path,
        schemaName: String?,
        header: Boolean
    ) {
        Files.createDirectories(pathToOutPutDirectory)
        val viewList = if (schemaName != null) {
            viewNames(schemaName)
        } else {
            views
        }
        for (view in viewList) {
            val path: Path = pathToOutPutDirectory.resolve("$view.csv")
            val writer = KSLFileUtil.createPrintWriter(path)
            exportTableAsCSV(view, writer, schemaName, header)
            writer.close()
        }
    }

    /**
     * @param schemaName the schema containing the table
     * @param tableName the name of the table within the schema to get all records from
     * @return a result holding all the records from the table
     */
    fun selectAll(tableName: String, schemaName: String? = defaultSchemaName): CachedRowSet? {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                return null
            }
        }
        if (!containsTable(tableName) && !containsView(tableName)) {
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
     *  is null, then the tables in the property [userDefinedTables] are used.
     *
     *  @param schemaName the name of the schema containing the table
     */
    fun deleteAllFrom(schemaName: String? = defaultSchemaName) {
        val tables = if (schemaName != null) {
            tableNames(schemaName)
        } else {
            userDefinedTables
        }
        deleteAllFrom(tables, defaultSchemaName)
    }

    /**
     *  Deletes all data from the tables in the list
     *  @param tableNames the table to delete from
     *  @param schemaName the name of the schema containing the table
     */
    fun deleteAllFrom(tableNames: List<String>, schemaName: String? = defaultSchemaName) {
        for (tableName in tableNames) {
            deleteAllFrom(tableName, schemaName)
        }
    }

    /**
     *  Deletes all data from the named table
     *  @param tableName the table to delete from
     *  @param schemaName the name of the schema containing the table
     *  @return true if the command executed successfully.
     */
    fun deleteAllFrom(tableName: String, schemaName: String? = defaultSchemaName): Boolean {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                return false
            }
        }
        if (!containsTable(tableName)) {
            return false
        }
        val sql = deleteAllFromTableSQL(tableName, schemaName)
        return executeCommand(sql)
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
    fun selectAllIntoOpenResultSet(tableName: String, schemaName: String? = defaultSchemaName): ResultSet? {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                return null
            }
        }
        if (!containsTable(tableName) && !containsView(tableName)) {
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
        exportInsertQueries(tableName, PrintWriter(System.out), schemaName)
    }

    /**
     * Writes the insert queries associated with the supplied table to the PrintWriter
     * @param schemaName the name of the schema that should contain the table
     * @param tableName the unqualified name of the table
     * @param out       the PrintWriter to write to
     */
    override fun exportInsertQueries(tableName: String, out: PrintWriter, schemaName: String?) {
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
     * @param schemaName the name of the schema that should contain the tables
     */
    override fun printAllTablesAsInsertQueries(schemaName: String?) {
        exportAllTablesAsInsertQueries(schemaName, PrintWriter(System.out))
    }

    /**
     * Writes all table data as insert queries to the PrintWriter
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    override fun exportAllTablesAsInsertQueries(schemaName: String?, out: PrintWriter) {
        val tables = if (schemaName == null) {
            userDefinedTables
        } else {
            tableNames(schemaName)
        }
        for (t in tables) {
            exportInsertQueries(t, out, schemaName)
        }
    }

    override fun exportToExcel(
        schemaName: String?,
        wbName: String,
        wbDirectory: Path
    ) {
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                logger.warn {
                    "Attempting to write to Excel: The supplied schema name $schemaName is not in " +
                            "database $label. No workbook named $wbName at $wbDirectory was created"
                }
                return
            }
            val tables = tableNames(schemaName).toMutableList()
            tables.addAll(viewNames(schemaName))
            logger.info { "Exporting $schemaName to $wbName at $wbDirectory" }
            exportToExcel(tables, schemaName, wbName, wbDirectory)
        } else {
            logger.info { "The supplied schema to write was null. Exporting all user defined tables and views to $wbName at $wbDirectory" }
            val list = mutableListOf<String>()
            list.addAll(userDefinedTables)
            list.addAll(views)
            exportToExcel(list, null, wbName, wbDirectory)
        }
    }

    /**
     *  This is needed because SQLite has no schemas
     */
    private fun sqliteExportToExcel(wbName: String, wbDirectory: Path) {
        val list = mutableListOf<String>()
        list.addAll(userDefinedTables)
        list.addAll(views)
        logger.info { "SQLite: Exporting user defined tables and views to $wbName at $wbDirectory" }
        exportToExcel(list, null, wbName, wbDirectory)
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
    override fun exportToExcel(
        tableNames: List<String>,
        schemaName: String?,
        wbName: String,
        wbDirectory: Path
    ) {
        if (tableNames.isEmpty()) {
            logger.warn { "The supplied list of table names was empty when writing to Excel in database $label" }
            return
        }
        val wbn = if (!wbName.endsWith(".xlsx")) {
            "$wbName.xlsx"
        } else {
            wbName
        }
        val path = wbDirectory.resolve(wbn)
        FileOutputStream(path.toFile()).use {
            ExcelUtil.logger.info { "Opened workbook $path for writing database $label output" }
            logger.info { "Writing database $label to workbook at $path" }
            val workbook = SXSSFWorkbook(100)
            for (tableName in tableNames) {
                if (containsTable(tableName) || containsView(tableName)) {
                    // get result set
                    val rs = selectAllIntoOpenResultSet(tableName, schemaName)
                    if (rs != null) {
                        // write result set to workbook
                        val sheet = ExcelUtil.createSheet(workbook, tableName)
                        exportAsWorkSheet(rs, sheet)
                        // close result set
                        rs.close()
                    }
                }
            }
            workbook.write(it)
            workbook.close()
            workbook.dispose()
            ExcelUtil.logger.info { "Closed workbook $path after writing database $label output" }
            logger.info { "Completed database $label export to workbook at $path" }
        }
    }

    /**
     * Opens the workbook for reading only and writes the sheets of the workbook into database tables.
     * The list of names is the names of the
     * sheets in the workbook and the names of the tables that need to be written. They are in the
     * order that is required for entering data so that no integrity constraints are violated. The
     * underlying workbook is closed after the operation.
     *
     * @param pathToWorkbook the path to the workbook. Must be valid workbook with .xlsx extension
     * @param skipFirstRow   if true the first row of each sheet is skipped
     * @param schemaName the name of the schema containing the named tables
     * @param tableNames     the names of the sheets and tables in the order that needs to be written
     * @throws IOException an io exception
     */
    override fun importWorkbookToSchema(
        pathToWorkbook: Path,
        skipFirstRow: Boolean,
        schemaName: String?,
        tableNames: List<String>
    ) {
        val workbook: XSSFWorkbook = ExcelUtil.openExistingXSSFWorkbookReadOnly(pathToWorkbook)
            ?: throw IOException("There was a problem opening the workbook at $pathToWorkbook!")

        logger.info { "Writing workbook $pathToWorkbook to database $label" }
        for (tableName in tableNames) {
            val sheet = workbook.getSheet(tableName)
            if (sheet == null) {
                logger.info { "Skipping table $tableName no corresponding sheet in workbook" }
                continue
            }
            logger.trace { "Processing the sheet for table $tableName." }
            val tblMetaData = tableMetaData(tableName, schemaName)
            logger.trace { "Constructing path for bad rows file for table $tableName." }
            val dirStr = pathToWorkbook.toString().substringBeforeLast(".")
            val path = Path.of(dirStr)
            val pathToBadRows = path.resolve("${tableName}_MissingRows.txt")
            logger.trace { "The file to hold bad data for table $tableName is $pathToBadRows" }
            val badRowsFile = KSLFileUtil.createPrintWriter(pathToBadRows)
            val numToSkip = if (skipFirstRow) 1 else 0
            val success = importSheetToTable(
                sheet,
                tableName,
                tblMetaData.size,
                schemaName,
                numToSkip,
                unCompatibleRows = badRowsFile
            )
            if (!success) {
                logger.info { "Unable to write sheet $tableName to database ${label}. See trace logs for details" }
            } else {
                logger.info { "Wrote sheet $tableName to database ${label}." }
            }
        }
        workbook.close()
        logger.info { "Closed workbook $pathToWorkbook " }
        logger.info { "Completed writing workbook $pathToWorkbook to database $label" }
    }

    /** Copies the rows from the sheet to the table.  The copy is assumed to start
     * at row 1, column 1 (i.e. cell A1) and proceed to the right for the number of columns in the
     * table and the number of rows of the sheet.  The copy is from the perspective of the table.
     * That is, all columns of a row of the table are attempted to be filled from a corresponding
     * row of the sheet.  If the row of the sheet does not have cell values for the corresponding column, then
     * the cell is interpreted as a null value when being placed in the corresponding column.  It is up to the client
     * to ensure that the cells in a row of the sheet are data type compatible with the corresponding column
     * in the table.  Any rows that cannot be transfer in their entirety are logged to the supplied PrintWriter
     *
     * @param sheet the sheet that has the data to transfer to the ResultSet
     * @param tableName the table to copy into
     * @param numColumns the number of columns in the sheet to copy into the table
     * @param schemaName the name of the schema containing the tabel
     * @param numRowsToSkip indicates the number of rows to skip from the top of the sheet. Use 1 (default) if the sheet has
     * a header row
     *  @param rowBatchSize the number of rows to accumulate in a batch before completing a transfer
     *  @param unCompatibleRows a file to hold the rows that are not transferred in a string representation
     */
    override fun importSheetToTable(
        sheet: Sheet,
        tableName: String,
        numColumns: Int,
        schemaName: String?,
        numRowsToSkip: Int,
        rowBatchSize: Int,
        unCompatibleRows: PrintWriter
    ): Boolean {
        return try {
            val rowIterator = sheet.rowIterator()
            for (i in 1..numRowsToSkip) {
                if (rowIterator.hasNext()) {
                    rowIterator.next()
                }
            }
            logger.trace { "Getting connection to import ${sheet.sheetName} into table $tableName of schema $schemaName of database $label" }
            logger.trace { "Table $tableName to hold data for sheet ${sheet.sheetName} has $numColumns columns to fill." }
            getConnection().use { con ->
                con.autoCommit = false
                // make prepared statement for inserts
                val insertStatement = makeInsertPreparedStatement(con, tableName, numColumns, schemaName)
                var batchCnt = 0
                var cntBad = 0
                var rowCnt = 0
                var cntGood = 0
                while (rowIterator.hasNext()) {
                    val row = rowIterator.next()
                    val rowData = ExcelUtil.readRowAsObjectList(row, numColumns)
                    rowCnt++
                    logger.trace { "Read ${rowData.size} elements from sheet ${sheet.sheetName}" }
                    logger.trace { "Sheet Data: $rowData" }
                    // rowData needs to be placed into insert statement
                    val success = addBatch(rowData, numColumns, insertStatement)
                    if (!success) {
                        logger.trace { "Wrote row number ${row.rowNum} of sheet ${sheet.sheetName} to bad data file" }
                        unCompatibleRows.println("Sheet: ${sheet.sheetName} row: ${row.rowNum} not written: $rowData")
                        cntBad++
                    } else {
                        logger.trace { "Inserted data into batch for insertion" }
                        batchCnt++
                        if (batchCnt.mod(rowBatchSize) == 0) {
                            val ni = insertStatement.executeBatch()
                            con.commit()
                            logger.trace { "Wrote batch of size ${ni.size} to table $tableName" }
                            batchCnt = 0
                        }
                        cntGood++
                    }
                }
                if (batchCnt > 0) {
                    val ni = insertStatement.executeBatch()
                    con.commit()
                    logger.trace { "Wrote batch of size ${ni.size} to table $tableName" }
                }
                logger.info { "Transferred $cntGood out of $rowCnt rows for ${sheet.sheetName}. There were $cntBad incompatible rows written." }
            }
            true
        } catch (ex: SQLException) {
            logger.error(
                ex
            ) { "SQLException when importing ${sheet.sheetName} into table $tableName of schema $schemaName of database $label" }
            false
        }
    }

    /**
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
        require(containsTable(tableName)) { "The database $label does not contain table $tableName" }
        val sql = insertIntoTableStatementSQL(tableName, numColumns, schemaName)
        return con.prepareStatement(sql)
    }

    /**
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
        require(containsTable(tableName)) { "The database $label does not contain table $tableName" }
        val sql = deleteFromTableWhereSQL(tableName, fieldName, schemaName)
        return con.prepareStatement(sql)
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
     * @return returns a DbCreateTask that can be configured to execute on the database
     */
    fun create(): DbCreateTask.DbCreateTaskFirstStepIfc {
        return DbCreateTask.DbCreateTaskBuilder(this)
    }

    /**
     * Executes a single command on a database connection
     *
     * @param command a valid SQL command
     * @return true if the command executed without an SQLException
     */
    fun executeCommand(command: String): Boolean {
        var flag = false
        try {
            logger.info { "Getting connection to execute command on database $label \n$command" }
            getConnection().use { con -> flag = executeCommand(con, command) }
        } catch (ex: SQLException) {
            logger.error(ex) { "SQLException when executing $command" }
        }
        return flag
    }

    /**
     * Consecutively executes the list of SQL queries supplied as a list of
     * strings The strings must not have ";" semi-colon at the end.
     *
     * @param commands the commands
     * @return true if all commands were executed
     */
    fun executeCommands(commands: List<String>): Boolean {
        var executed = true
        try {
            getConnection().use { con ->
                con.autoCommit = false
                for (cmd in commands) {
                    executed = executeCommand(con, cmd)
                    if (!executed) {
                        logger.trace { "Rolling back command on database $label" }
                        con.rollback()
                        break
                    }
                }
                if (executed) {
                    logger.trace { "Committing commands on database $label" }
                    con.commit()
                }
                con.autoCommit = true
            }
        } catch (ex: SQLException) {
            executed = false
            logger.trace { "The commands were not executed for database $label" }
            logger.error(ex) { "SQLException: " }
        }
        return executed
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
        logger.trace { "Executing SQL in file: $path" }
        return executeCommands(parseQueriesInSQLScript(path))
    }

    /** A simple wrapper to ease the use of JDBC for novices. Returns the results of a query in the
     * form of a JDBC CachedRowSet. Errors in the SQL are the user's responsibility. Any exceptions
     * are logged and squashed.  The underlying query is closed.
     *
     * @param sql an SQL text string that is valid
     * @return the results of the query or null if there was a problem
     */
    fun fetchCachedRowSet(sql: String): CachedRowSet? {
        try {
            logger.trace { "Database $label: Getting connection to fetch CachedRowSet for $sql" }
            getConnection().use { connection ->
                val query = connection.prepareStatement(sql)
                val rs = query.executeQuery()
                val crs = createCachedRowSet(rs)
                query.close()
                return crs
            }
        } catch (e: SQLException) {
            logger.warn(e) { "The query $sql was not executed for database $label" }
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
            logger.trace { "Database $label: Getting connection to fetch open ResultSet for $sql" }
            query = getConnection().prepareStatement(sql)
            return query.executeQuery()
        } catch (e: SQLException) {
            logger.warn(e) { "The query $sql was not executed for database $label" }
            query?.close()
        }
        return null
    }

    /**
     * @param schemaName the schema containing the table
     * @param tableName the name of the table within the schema
     * @return the number of rows in the table
     */
    fun numRows(tableName: String, schemaName: String? = defaultSchemaName): Long {
        val tblName = if (schemaName != null) {
            "${schemaName}.${tableName}"
        } else {
            tableName
        }
        try {
            getConnection().use { connection ->
                val stmt: Statement = connection.createStatement()
                val sql = "select count(*) from $tblName"
                val rs: ResultSet = stmt.executeQuery(sql)
                rs.next()
                val count = rs.getLong(1)
                stmt.close()
                return count
            }
        } catch (e: SQLException) {
            logger.warn { "Could not count the number of rows in $tableName" }
        }
        return 0
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

            //first drop any views, then the tables
            val tables = tableNames(schemaName)
            logger.debug { "Schema $schemaName has tables ... " }
            for (t in tables) {
                logger.debug { "table $t" }
            }
            val views = viewNames(schemaName)
            logger.debug { "Schema $schemaName has views ... " }
            for (v in views) {
                logger.debug { "table $v" }
            }
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
            for (s in schemas) {
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
        if (schemaName != null) {
            if (!containsSchema(schemaName)) {
                return emptyList()
            }
        }
        if (!containsTable(tableName) && !containsView(tableName)) {
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
        if (schemaName == null) {
            require(containsTable(tableName)) { "Database $label does not contain table $tableName in schema $schemaName for inserting data!" }
        } else {
            require(
                containsTable(
                    schemaName,
                    tableName
                )
            ) { "Database $label does not contain table $tableName in schema $schemaName for inserting data!" }
        }
        require(data.tableName == tableName) { "The supplied data was not from table $tableName" }
        data.schemaName = schemaName // needed to make the insert statement correctly
        val sql = data.insertDataSQLStatement()
        //insert into main.Persons (id, name, age) values (?, ?, ?)
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
                if (data.autoIncField) {
                    val rs = stmt.generatedKeys
                    rs.next()
                    //val autoId = rs.getObject(1)
                    val autoId = rs.getInt(1)
                    //println("autoId = $autoId  class = ${autoId::class}")
                    data.setAutoIncField(autoId)
                }
                return 1
            }
        } catch (e: SQLException) {
            logger.warn { "There was an SQLException when trying insert DbData data into : $tableName" }
            logger.warn { "SQLException: $e" }
            return 0
        }
    }

    /**
     *  Inserts the [data] from the list into the supplied table [tableName] and schema [schemaName].
     *  The DbData instance must be designed for the table.
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
        require(containsTable(tableName)) { "Database $label does not contain table $tableName for inserting data!" }
        // data should come from the table
        val first = data.first()
        require(first.tableName == tableName) { "The supplied data was not from table $tableName" }
        // use first to set up the prepared statement
        first.schemaName = schemaName // needed to make the insert statement correctly
        val sql = first.insertDataSQLStatement()
        try {
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
        require(containsTable(tableName)) { "Database $label does not contain table $tableName for updating data!" }
        // data should come from the table
        val first = data.first()
        require(first.tableName == tableName) { "The supplied data was not from table $tableName" }
        // use first to set up the prepared statement
        first.schemaName = schemaName // needed to make the update statement correctly
        val sql = first.updateDataSQLStatement()
        val nc = first.numUpdateFields
        try {
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
        val tableInfo = dbTablesFromMetaData()
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
        sb.append(schemas.toString())
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

        /** Exports the data in the ResultSet to an Excel worksheet. The ResultSet is assumed to be forward
         * only and each row is processed until all rows are exported. The ResultSet is closed after
         * the processing.
         *
         * @param resultSet the result set to copy from
         * @param sheet the sheet in the workbook to hold the results set values
         * @param writeHeader whether to write a header of the column names into the sheet. The default is true
         */
        fun exportAsWorkSheet(resultSet: ResultSet, sheet: Sheet, writeHeader: Boolean = true) {
            require(!resultSet.isClosed) { "The supplied ResultSet is closed when trying to write workbook ${sheet.sheetName} " }
            // write the header
            var rowCnt = 0
            val names = columnNames(resultSet)
            if (writeHeader) {
                val header = sheet.createRow(0)
                for (col in names.indices) {
                    val cell = header.createCell(col)
                    cell.setCellValue(names[col])
                    sheet.setColumnWidth(col, ((names[col].length + 2) * 256))
                }
                rowCnt++
            }
            // write all the rows
            val iterator = ResultSetRowIterator(resultSet)
            while (iterator.hasNext()) {
                val list = iterator.next()
                val row = sheet.createRow(rowCnt)
                for (col in list.indices) {
                    ExcelUtil.writeCell(row.createCell(col), list[col])
                }
                rowCnt++
            }
            resultSet.close()
            logger.info { "Completed exporting ResultSet to Excel worksheet ${sheet.sheetName}" }
        }

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
                    logger.info { "Executed SQL:\n$command" }
                    statement.close()
                    flag = true
                }
            } catch (ex: SQLException) {
                logger.error(ex) { "SQLException when executing command $command" }
            }
            return flag
        }

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
//TODO cause SQL not supported error        require(!resultSet.isClosed) { "Cannot iterate. The ResultSet is closed" }
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


/**
 * The user can convert the returned rows based on ColumnMetaData.
 * The rows contain a map that is indexed by the column name and the value of the column
 *
 * @param resultSet the result set to iterate. It must be open and will be closed after iteration.
 */
class ResultSetRowMapIterator(private val resultSet: ResultSet) : Iterator<Map<String, Any?>> {
    init {
//TODO cause SQL not supported error       require(!resultSet.isClosed) { "Cannot iterate. The ResultSet is closed" }
    }

    var currentRow: Int = 0
        private set
    private var didNext: Boolean = false
    private var hasNext: Boolean = false
    val columnCount: Int
    val columnNames: List<String>

    init {
        val metaData: ResultSetMetaData = resultSet.metaData
        columnCount = metaData.columnCount
        val list = mutableListOf<String>()
        for (i in 1..columnCount) {
            list.add(metaData.getColumnName(i))
        }
        columnNames = list.toList()
    }

    override fun hasNext(): Boolean {
        if (!didNext) {
            hasNext = resultSet.next()
            if (!hasNext) resultSet.close()
            didNext = true
        }
        return hasNext
    }

    override fun next(): Map<String, Any?> {
        if (!didNext) {
            resultSet.next()
        }
        didNext = false
        currentRow++
        return makeRow(resultSet)
    }

    private fun makeRow(resultSet: ResultSet): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (i in 1..columnCount) {
            try {
                map[columnNames[i - 1]] = resultSet.getObject(i)
            } catch (e: RuntimeException) {
                DatabaseIfc.logger.warn { "There was a problem accessing column $i of the result set. Set value to null" }
            }
        }
        return map
    }

}