package ksl.utilities.io.dbutil

import com.opencsv.CSVWriterBuilder
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import ksl.utilities.io.ExcelUtil
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.MarkDown
import mu.KLoggable
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
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
import kotlin.reflect.typeOf


interface DatabaseIOIfc {

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
     * Writes the table as comma separated values
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the name of the table to write
     * @param header true means column names as the header included
     * @param out       the PrintWriter to write to.  The print writer is not closed.
     */
    fun writeTableAsCSV(
        tableName: String,
        out: PrintWriter,
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
    fun writeTableAsText(tableName: String, out: PrintWriter, schemaName: String? = defaultSchemaName)

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
    fun writeAllTablesAsText(out: PrintWriter, schemaName: String? = defaultSchemaName)

    /**
     * Writes the table as prettified text.
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     * @param out       the PrintWriter to write to.  The print writer is not closed
     */
    fun writeTableAsMarkdown(tableName: String, out: PrintWriter, schemaName: String? = defaultSchemaName)

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
    fun writeAllTablesAsMarkdown(out: PrintWriter, schemaName: String? = defaultSchemaName)

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
        pathToOutPutDirectory: Path,
        schemaName: String? = defaultSchemaName,
        header: Boolean = true
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
    fun exportInsertQueries(tableName: String, out: PrintWriter, schemaName: String? = defaultSchemaName)

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
        wbDirectory: Path = KSL.excelDir
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
        wbDirectory: Path = KSL.excelDir
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
        unCompatibleRows: PrintWriter = KSLFileUtil.createPrintWriter("BadRowsForSheet_${sheet.sheetName}")
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
     * @return a connection to the database
     * @throws SQLException if there is a problem with the connection
     */
    fun getConnection(): Connection {
        try {
            val c = dataSource.connection
            logger.trace { "Established a connection to Database $label " }
            return c
        } catch (ex: SQLException) {
            KSL.logger.error("Unable to establish connection to $label")
            throw ex
        }
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
                logger.trace { "Getting a connection to retrieve the list of table names for schema $schemaName in database $label" }
                getConnection().use { connection ->
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
                logger.trace { "Getting a connection to retrieve the list of schema names in database $label" }
                getConnection().use { connection ->
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
    override fun writeTableAsCSV(
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
        if (!containsTable(tableName)) {
            logger.trace { "Table: $tableName does not exist in database $label" }
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
        writeTableAsCSV(tableName, PrintWriter(System.out), schemaName, header)
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
        if (!containsTable(tableName)) {
            logger.info { "Table: $tableName does not exist in database $label" }
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
                logger.info("Schema: {} does not exist in database {}", schemaName, label)
                return
            }
        }
        if (!containsTable(tableName)) {
            logger.info("Table: {} does not exist in database {}", tableName, label)
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
    override fun writeAllTablesAsCSV(
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
            writeTableAsCSV(table, writer, schemaName, header)
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
    fun selectAllIntoOpenResultSet(tableName: String, schemaName: String? = defaultSchemaName): ResultSet? {
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
            val tables = tableNames(schemaName)
            logger.info { "Exporting $schemaName to $wbName at $wbDirectory" }
            exportToExcel(tables, schemaName, wbName, wbDirectory)
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
    override fun exportToExcel(
        tableNames: List<String>,
        schemaName: String?,
        wbName: String,
        wbDirectory: Path
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
            val workbook = SXSSFWorkbook(100)
            for (tableName in tableNames) {
                if (containsTable(tableName)) {
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

        logger.info("Writing workbook {} to database {}", pathToWorkbook, label)
        for (tableName in tableNames) {
            val sheet = workbook.getSheet(tableName)
            if (sheet == null) {
                logger.info("Skipping table {} no corresponding sheet in workbook", tableName)
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
        logger.info("Closed workbook {} ", pathToWorkbook)
        logger.info("Completed writing workbook {} to database {}", pathToWorkbook, label)
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
                "SQLException when importing ${sheet.sheetName} into table $tableName of schema $schemaName of database $label",
                ex
            )
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
    private fun makeInsertPreparedStatement(
        con: Connection,
        tableName: String,
        numColumns: Int,
        schemaName: String?
    ): PreparedStatement {
        val sql = createTableInsertStatement(tableName, numColumns, schemaName)
        return con.prepareStatement(sql)
    }

    /**
     * @param tableName the name of the table to be inserted into
     * @param numColumns the number of columns starting from the left to insert into
     * @param schemaName the schema containing the table
     * @return a generic SQL insert statement with appropriate number of parameters for the table
     */
    fun createTableInsertStatement(
        tableName: String,
        numColumns: Int,
        schemaName: String? = defaultSchemaName
    ): String {
        // assume all columns have the same table name and schema name
        require(tableName.isNotEmpty()) { "The table name was empty when making the insert statement" }
        val qm = CharArray(numColumns)
        qm.fill('?', toIndex = numColumns)
        val inputs = qm.joinToString(", ", prefix = "(", postfix = ")")
        val sql = if (schemaName == null) {
            "insert into $tableName values $inputs"
        } else {
            "insert into ${schemaName}.${tableName} values $inputs"
        }
        return sql
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
            logger.trace { "Getting connection to execute command $command on database $label" }
            getConnection().use { con -> flag = executeCommand(con, command) }
        } catch (ex: SQLException) {
            logger.error("SQLException when executing {}", command, ex)
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
            logger.error("SQLException: ", ex)
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
        logger.trace("Executing SQL in file: {}", path)
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
            logger.trace { "Database $label: Getting connection to fetch open ResultSet for $sql" }
            query = getConnection().prepareStatement(sql)
            return query.executeQuery()
        } catch (e: SQLException) {
            logger.warn("The query $sql was not executed for database $label", e)
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
            logger.warn("Could not count the number of rows in $tableName")
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
    fun tableMetaData(tableName: String, schemaName: String? = defaultSchemaName): List<ColumnMetaData> {
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

        //TODO create Dataframe from ResultSet

        override val logger = logger()

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
            if (writeHeader) {
                val names = columnNames(resultSet)
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
                    logger.trace("Executed SQL: {}", command)
                    statement.close()
                    flag = true
                }
            } catch (ex: SQLException) {
                logger.error("SQLException when executing {}", command, ex)
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

        /** The ResultSet is processed through all rows.
         *
         * @param resultSet the result set to write out as csv delimited
         * @param header true (default) indicates include the header
         * @param writer the writer to use
         */
        fun writeAsCSV(resultSet: ResultSet, header: Boolean = true, writer: Writer) {
            require(!resultSet.isClosed) { "The supplied ResultSet is closed!" }
            //okay because resultSet is only read from
            val builder = CSVWriterBuilder(writer)
            val csvWriter = builder.build()
            csvWriter.writeAll(resultSet, header)
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
//TODO            require(!resultSet.isClosed) { "The supplied ResultSet is closed!" }  maybe Derby does not support this!
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
        private fun fillFromColumn(column: Int, cachedRowSet: CachedRowSet): List<Any?> {
            val toCollection: MutableCollection<*> = cachedRowSet.toCollection(column)
            return toCollection.toList()
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