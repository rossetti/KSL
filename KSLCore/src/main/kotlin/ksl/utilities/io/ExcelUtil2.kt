package ksl.utilities.io

import ksl.utilities.io.ExcelUtil.logger
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.ResultSetRowIterator
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.ReadableWorkbook
import java.io.*
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.*

object ExcelUtil2 {

    /** Writes each table in the list to an Excel workbook with each table being placed
     *  in a new sheet with the sheet name equal to the name of the table. The column names
     *  for each table are written as the first row of each sheet.
     *
     *  Uses the longLastingConnection property for the connection.
     *
     * @param schemaName the name of the schema containing the tables or null
     * @param tableNames the names of the tables to write to a workbook
     * @param path the path to the workbook
     * @param db the database containing the tables
     */
    fun exportTablesToExcel(db: DatabaseIfc, path: Path, tableNames: List<String>, schemaName: String?) {
        FileOutputStream(path.toFile()).use {
            logger.info { "Opened workbook $path for writing database ${db.label} output" }
            DatabaseIfc.logger.info { "Writing database ${db.label} to workbook at $path" }
            val wb = Workbook(it, "KSL", "1.0")
            for (tableName in tableNames) {
                // get result set
                val rs = db.selectAllIntoOpenResultSet(tableName, schemaName)
                if (rs != null) {
                    // write result set to workbook
                    exportAsWorkSheet(rs, wb, tableName)
                    // close result set
                    rs.close()
                }
            }
            wb.finish()
            wb.close()
            logger.info { "Closed workbook $path after writing database ${db.label} output" }
            DatabaseIfc.logger.info { "Completed database ${db.label} export to workbook at $path" }
        }
    }

    /** Exports the data in the ResultSet to an Excel worksheet. The ResultSet is assumed to be forward
     * only and each row is processed until all rows are exported. The ResultSet is closed after
     * the processing.
     *
     * @param resultSet the result set to copy from
     * @param workbook the workbook to write into
     * @param sheetName the name of the sheet in the workbook to hold the results set values
     * @param writeHeader whether to write a header of the column names into the sheet. The default is true
     */
    fun exportAsWorkSheet(resultSet: ResultSet, workbook: Workbook, sheetName: String, writeHeader: Boolean = true) {
        require(!resultSet.isClosed) { "The supplied ResultSet is closed when trying to write workbook $sheetName " }
        // write the header
        val sheet = workbook.newWorksheet(sheetName) as org.dhatim.fastexcel.Worksheet
        var rowCnt = 0
        val names = DatabaseIfc.columnNames(resultSet)
        if (writeHeader) {
            for (col in names.indices) {
                sheet.value(rowCnt, col, names[col])
                sheet.width(col, minOf(names[col].length, 256).toDouble())
//                sheet.setColumnWidth(col, ((names[col].length + 2) * 256))
            }
            rowCnt++
        }
        // write all the rows
        val iterator = ResultSetRowIterator(resultSet)
        while (iterator.hasNext()) {
            val list = iterator.next()
            writeRow(sheet, rowCnt, list)
            rowCnt++
            sheet.flush()
        }
        resultSet.close()
        sheet.flush()
        sheet.finish()
        sheet.close()
        DatabaseIfc.logger.info { "Completed exporting ResultSet to Excel worksheet $sheetName" }
    }

    private fun writeRow(sheet: org.dhatim.fastexcel.Worksheet, rowNum: Int, list: List<Any?>) {
        for ((colNum, value) in list.withIndex()) {
            writeCell(sheet, rowNum, colNum, value)
        }
    }

    private fun writeCell(sheet: org.dhatim.fastexcel.Worksheet, rowNum: Int, colNum: Int, value: Any?) {
        when (value) {
            null -> { // nothing to write
            }

            is String -> {
                sheet.value(rowNum, colNum, value.trim())
            }

            is Boolean -> {
                sheet.value(rowNum, colNum, value)
            }

            is Int -> {
                sheet.value(rowNum, colNum, value)
            }

            is Double -> {
                sheet.value(rowNum, colNum, value)
            }

            is Float -> {
                sheet.value(rowNum, colNum, value)
            }

            is BigDecimal -> {
                sheet.value(rowNum, colNum, value)
            }

            is Long -> {
                sheet.value(rowNum, colNum, value)
            }

            is Short -> {
                sheet.value(rowNum, colNum, value)
            }

            is Date -> {
                sheet.value(rowNum, colNum, value)
            }

            is Time -> {
                sheet.value(rowNum, colNum, value)
            }

            is Timestamp -> {
                sheet.value(rowNum, colNum, value)
            }

            else -> {
                logger.error { "Could not cast type ${value.javaClass.name} to Excel type." }
                throw ClassCastException("Could not cast database type to Excel type: ${value.javaClass.name}")
            }
        }
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
     * @param db the database containing the tables
     * @param pathToWorkbook the path to the workbook. Must be valid workbook with .xlsx extension
     * @param skipFirstRow   if true the first row of each sheet is skipped
     * @param schemaName the name of the schema containing the named tables
     * @param tableNames     the names of the sheets and tables in the order that needs to be written
     * @throws IOException an io exception
     */
    fun importWorkbookToSchema(
        db: DatabaseIfc,
        pathToWorkbook: Path,
        tableNames: List<String>,
        schemaName: String?,
        skipFirstRow: Boolean
    ) {
        FileInputStream(pathToWorkbook.toFile()).use {
            val workbook = ReadableWorkbook(it)
            //do the work
            DatabaseIfc.logger.info { "Writing workbook $pathToWorkbook to database ${db.label}" }
            for (tableName in tableNames) {
                val sheetOption = workbook.findSheet(tableName)
                if (sheetOption.isEmpty) {
                    DatabaseIfc.logger.info { "Skipping table $tableName no corresponding sheet in workbook" }
                    continue
                }
                // option is not empty
                val sheet = sheetOption.get() as org.dhatim.fastexcel.reader.Sheet
                DatabaseIfc.logger.trace { "Processing the sheet for table $tableName." }
                val tblMetaData = db.tableMetaData(tableName, schemaName)
                DatabaseIfc.logger.trace { "Constructing path for bad rows file for table $tableName." }
                val dirStr = pathToWorkbook.toString().substringBeforeLast(".")
                val path = Path.of(dirStr)
                val pathToBadRows = path.resolve("${tableName}_MissingRows.txt")
                DatabaseIfc.logger.trace { "The file to hold bad data for table $tableName is $pathToBadRows" }
                val badRowsFile = KSLFileUtil.createPrintWriter(pathToBadRows)
                val numToSkip = if (skipFirstRow) 1 else 0
                val success = importSheetToTable(
                    db,
                    sheet,
                    tableName,
                    tblMetaData.size,
                    schemaName,
                    numToSkip,
                    incompatibleRows = badRowsFile
                )
                if (!success) {
                    DatabaseIfc.logger.info { "Unable to write sheet $tableName to database ${db.label}. See trace logs for details" }
                } else {
                    DatabaseIfc.logger.info { "Wrote sheet $tableName to database ${db.label}." }
                }
            }
            workbook.close()
            DatabaseIfc.logger.info { "Closed workbook $pathToWorkbook " }
            DatabaseIfc.logger.info { "Completed writing workbook $pathToWorkbook to database ${db.label}" }
        }
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
     * @param db the database holding the table
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
        db: DatabaseIfc,
        sheet: org.dhatim.fastexcel.reader.Sheet,
        tableName: String,
        numColumns: Int,
        schemaName: String?,
        numRowsToSkip: Int,
        rowBatchSize: Int = 100,
        incompatibleRows: PrintWriter
    ): Boolean {
        return try {
            //TODO make the db connection and insert statement
            DatabaseIfc.logger.trace { "Getting connection to import ${sheet.name} into table $tableName of schema $schemaName of database ${db.label}" }
            DatabaseIfc.logger.trace { "Table $tableName to hold data for sheet ${sheet.name} has $numColumns columns to fill." }
            sheet.openStream().use {
                val rowList = mutableListOf<org.dhatim.fastexcel.reader.Row>()
                var rowCnt = 0
                it.forEach { row: org.dhatim.fastexcel.reader.Row ->
                    rowCnt++
                    if (rowCnt > numRowsToSkip){
                        rowList.add(row)
                        if (rowList.size == rowBatchSize) {
                            //TODO write the rows in the row list to the database or to the incompatible row file
                            rowList.clear()
                        }
                    }
                }
                if (rowList.isNotEmpty()){
                    // form the last batch and insert
                    //TODO write the rows in the row list to the database or to the incompatible row file
                }
            }
            true
        } catch (ex: SQLException){
            DatabaseIfc.logger.error(ex)
            { "SQLException when importing ${sheet.name} into table $tableName of schema $schemaName of database ${db.label}" }
            false
        }
    }
}