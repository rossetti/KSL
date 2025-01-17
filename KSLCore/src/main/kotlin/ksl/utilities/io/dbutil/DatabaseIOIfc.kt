package ksl.utilities.io.dbutil

import ksl.utilities.io.OutputDirectory
import java.io.PrintWriter
import java.nio.file.Path

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
    val schemaNames: List<String>

    /**
     *  The returned map may have a null key because not all databases support
     *  the schema concept. There can be view names associated with null as the key.
     * @return a list of all view names within the database
     */
    val views: Map<String?,List<String>>

    /**
     *  The returned map may have a null key because not all databases support
     *  the schema concept. There can be table names associated with null as the key.
     *
     * @return a map of all table names by schema within the database
     */
    val userDefinedTables: Map<String?,List<String>>

    /**
     * Writes the table as comma separated values
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the name of the table to write
     * @param header true means column names as the header included
     * @param out       the PrintWriter to write to.  The print writer is not closed.
     */
    fun exportTableAsCSV(
        tableName: String,
        schemaName: String? = defaultSchemaName,
        out: PrintWriter = outputDirectory.createPrintWriter("${tableName}.csv"),
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
        schemaName: String? = defaultSchemaName,
        out: PrintWriter = outputDirectory.createPrintWriter("${label}_${tableName}.txt")
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
        schemaName: String? = defaultSchemaName,
        out: PrintWriter = outputDirectory.createPrintWriter("${label}_Tables.txt")
    )

    /**
     * Writes the table as prettified text.
     * @param schemaName the name of the schema that should contain the tables
     * @param tableName the unqualified name of the table to write
     * @param out       the PrintWriter to write to.  The print writer is not closed
     */
    fun writeTableAsMarkdown(
        tableName: String,
        schemaName: String? = defaultSchemaName,
        out: PrintWriter = outputDirectory.createPrintWriter("${label}_${tableName}.md")
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
        schemaName: String? = defaultSchemaName,
        out: PrintWriter = outputDirectory.createPrintWriter("${label}_Tables.md")
    )

    /**
     * Writes all tables as text
     *
     * @param schemaName the name of the schema that should contain the tables
     * @param out        the PrintWriter to write to
     */
    fun writeAllViewsAsMarkdown(
        schemaName: String? = defaultSchemaName,
        out: PrintWriter = outputDirectory.createPrintWriter("${label}_Views.md")
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
    fun exportAllTablesAsCSV(
        schemaName: String? = defaultSchemaName,
        pathToOutPutDirectory: Path = outputDirectory.csvDir,
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
        schemaName: String? = defaultSchemaName,
        pathToOutPutDirectory: Path = outputDirectory.csvDir,
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
    fun exportInsertQueries(
        tableName: String,
        schemaName: String? = defaultSchemaName,
        out: PrintWriter = outputDirectory.createPrintWriter("${label}_${tableName}_Inserts.sql")
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
    fun exportAllTablesAsInsertQueries(
        schemaName: String? = defaultSchemaName,
        out: PrintWriter = outputDirectory.createPrintWriter("${label}_TableInserts.sql"))

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
        tableNames: List<String>,
        schemaName: String? = defaultSchemaName,
        skipFirstRow: Boolean = true
    )
}