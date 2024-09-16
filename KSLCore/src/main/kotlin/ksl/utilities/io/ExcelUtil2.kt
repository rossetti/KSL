package ksl.utilities.io

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.utilities.io.ExcelUtil2.isValidExcelSheetName
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.ResultSetRowIterator
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.reader.Row
import org.dhatim.fastexcel.reader.Cell
import org.dhatim.fastexcel.reader.CellType
import org.dhatim.fastexcel.reader.ReadableWorkbook
import java.io.*
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayList

fun main() {
    val sheetName = "ExampleSheet * ExampleSheet"
    println("Is '$sheetName' a valid Excel sheet name? ${isValidExcelSheetName(sheetName)}")
}

object ExcelUtil2 {
    //TODO ensure proper worksheet names
    //TODO creating a workbook for writing
    //TODO opening a workbook for reading
    //TODO writing an 1-D array, 2-D array, map of arrays (data map)
    // TODO testing, testing, testing

    val logger = KotlinLogging.logger {}

    const val DEFAULT_MAX_CHAR_IN_CELL = 512

    val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun isValidExcelSheetName(sheetName: String): Boolean {
        // Check if the sheet name is within the allowed length
        if (sheetName.length > 31) return false
        // Define the invalid characters
        val invalidChars = listOf('\\', '/', '*', '[', ']', ':', '?')
        // Check if the sheet name contains any invalid characters
        for (char in invalidChars) {
            if (sheetName.contains(char)) return false
        }
        // If all checks pass, the sheet name is valid
        return true
    }

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

    /**
     *  Writes the data in the list to the indicated row in the sheet
     *  @param sheet the sheet to hold the data
     *  @param rowNum the row to write
     *  @param list the data to write to the row
     */
    fun writeRow(sheet: org.dhatim.fastexcel.Worksheet, rowNum: Int, list: List<Any?>) {
        for ((colNum, value) in list.withIndex()) {
            writeCell(sheet, rowNum, colNum, value)
        }
    }

    /**
     *  Writes the information to a cell in a sheet.
     *  @param sheet the sheet holding the cell
     *  @param rowNum the row number for the cell (0 based)
     *  @param colNum the column number for the cell (0 based)
     *  @param value the value to write to the cell
     */
    fun writeCell(sheet: org.dhatim.fastexcel.Worksheet, rowNum: Int, colNum: Int, value: Any?) {
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
        rowBatchSize: Int = 1000,
        incompatibleRows: PrintWriter
    ): Boolean {
        return try {
            //make the db connection and insert statement
            db.getConnection().use { con ->
                DatabaseIfc.logger.trace { "Getting connection to import ${sheet.name} into table $tableName of schema $schemaName of database ${db.label}" }
                DatabaseIfc.logger.trace { "Table $tableName to hold data for sheet ${sheet.name} has $numColumns columns to fill." }
                con.autoCommit = false
                // make prepared statement for inserts
                val insertStatement = DatabaseIfc.makeInsertPreparedStatement(
                    con, tableName, numColumns, schemaName
                )
                sheet.openStream().use {
                    val rowList = mutableListOf<Row>()
                    val badRows = mutableListOf<Row>()
                    var rowCnt = 0
                    var cntGood = 0
                    it.forEach { row: Row ->
                        rowCnt++
                        if (rowCnt > numRowsToSkip) {
                            rowList.add(row)
                            if (rowList.size == rowBatchSize) {
                                // write the rows in the row list to the database or to the incompatible row file
                                insertRows(rowList, numColumns, insertStatement, badRows)
                                rowList.clear()
                                // commit the batch
                                val ni = insertStatement.executeBatch()
                                con.commit()
                                cntGood = cntGood + ni.size
                                DatabaseIfc.logger.trace { "Wrote batch of size ${ni.size} to table $tableName" }
                            }
                        }
                    }
                    // there may be some left over from batch processing, insert these
                    if (rowList.isNotEmpty()) {
                        // form the last batch and insert
                        //write the rows in the row list to the database or to the incompatible row file
                        insertRows(rowList, numColumns, insertStatement, badRows)
                        rowList.clear()
                        // commit the batch
                        val ni = insertStatement.executeBatch()
                        con.commit()
                        cntGood = cntGood + ni.size
                        DatabaseIfc.logger.trace { "Wrote batch of size ${ni.size} to table $tableName" }
                    }
                    // write bad rows to the file
                    for (row in badRows) {
                        DatabaseIfc.logger.trace { "Wrote row number ${row.rowNum} of sheet ${sheet.name} to bad data file" }
                        incompatibleRows.println("Sheet: ${sheet.name} row: ${row.rowNum} not written: $row")
                    }
                    DatabaseIfc.logger.info { "Transferred $cntGood out of $rowCnt rows for ${sheet.name}. There were ${badRows.size} incompatible rows written." }
                }
            }
            true
        } catch (ex: SQLException) {
            DatabaseIfc.logger.error(ex)
            { "SQLException when importing ${sheet.name} into table $tableName of schema $schemaName of database ${db.label}" }
            false
        }
    }

    /**
     *  Rows are either placed in a prepared statement or
     *  saved as bad rows.
     *  @param rowList the list of rows to process
     *  @param numColumns the number of columns for all rows
     *  @param insertStatement the prepared statement
     *  @param badRows the rows that could not be inserted
     */
    private fun insertRows(
        rowList: List<Row>,
        numColumns: Int,
        insertStatement: PreparedStatement,
        badRows: MutableList<Row>
    ) {
        for (row in rowList) {
            val rowData = readRowAsObjectList(row, numColumns)
            // rowData needs to be placed into insert statement
            val success = DatabaseIfc.addBatch(rowData, numColumns, insertStatement)
            if (!success) {
                badRows.add(row)
            }
        }
    }

    /**
     * Read a row assuming a fixed number of columns.  Cells that
     * are missing/null in the row are read as null objects.
     *
     * @param row    the Excel row
     * @param numColumns the number of columns to read in the row
     * @return a list of objects representing the contents of the cells for each column
     */
    fun readRowAsObjectList(
        row: Row,
        numColumns: Int = row.cellCount
    ): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until numColumns) {
            if (row.hasCell(i)) {
                val cell = row.getCell(i)
                if (cell != null) {
                    list.add(readCellAsObject(cell))
                } else {
                    list.add(null)
                }
            } else {
                list.add(null)
            }
        }
        return list
    }

    /**
     * Reads the Excel cell and translates it into an object
     *
     * @param cell the Excel cell to read data from
     * @return the data in the form of an object
     */
    fun readCellAsObject(cell: Cell): Any? {
        return when (cell.type) {
            CellType.STRING -> cell.asString().trim()
            CellType.BOOLEAN -> cell.asBoolean()
            CellType.FORMULA -> cell.formula
            CellType.NUMBER -> {
                cell.asNumber().toDouble()
            }

            else -> null
        }
    }

    /** Writes each entry in the map to an Excel workbook. The mapping
     * of problematic double values is as follows:
     *
     *  - Double.NaN is written as the string "NaN"
     *  - Double.POSITIVE_INFINITY is written as a string "+Infinity"
     *  - Double.NEGATIVE_INFINITY is written as a string "-Infinity"
     *
     * Note that NULL is not a possible value in the map.
     *
     * @param sheetName the name of the sheet within the workbook. This should
     * follow the sheet naming conventions of Excel
     * @param map the map of information to export
     * @param wbName the name of the workbook. By default, assumes that
     * the workbook name is the same as the sheet name.
     * @param wbDirectory the directory to store the workbook. By default,
     * this is KSL.excelDir.
     * @param header if true a header of (Element Name, Element Value) is the first
     * row in the sheet. By default, no header is written.
     */
    fun writeMapToExcel(
        map: Map<String, Double>,
        sheetName: String,
        wbName: String = sheetName,
        wbDirectory: Path = KSL.excelDir,
        header: Boolean = false
    ) {
        val wbn = if (!wbName.endsWith(".xlsx")) {
            "$wbName.xlsx"
        } else {
            wbName
        }
        val path = wbDirectory.resolve(wbn)
        FileOutputStream(path.toFile()).use {
            logger.info { "Opened workbook $path for writing map $sheetName to Excel" }
            var rowCnt = 0
            val workbook = Workbook(it, "KSL", "1.0")
            val sheet = workbook.newWorksheet(sheetName)
            if (header) {
                sheet.value(0, 0, "Element Name")
                sheet.value(0, 1, "Element Value")
                rowCnt++
            }
            for ((n, v) in map) {
                sheet.value(rowCnt, 0, n)
                if (v.isNaN()) {
                    sheet.value(rowCnt, 1, "NaN")
                } else if (v == Double.POSITIVE_INFINITY) {
                    sheet.value(rowCnt, 1, "+Infinity")
                } else if (v == Double.NEGATIVE_INFINITY) {
                    sheet.value(rowCnt, 1, "-Infinity")
                } else {
                    writeCell(sheet, rowCnt, 1, v)
                }
                rowCnt++
                sheet.flush()
            }
            sheet.finish()
            workbook.finish()
            workbook.close()
            logger.info { "Closed workbook $path after writing map $sheetName to Excel" }
        }
    }

    /** This is the reverse operation to the function writeToExcel() for a Map<String, Double>
     *  The strings "+Infinity", "-Infinity", and "NaN" in the value column are correctly converted
     *  to the appropriate double representation.  Any rows that have empty cells (null) are skipped in the
     *  processing.
     *
     *  @param sheetName the name of the sheet holding the map. If the workbook does not
     *  contain the named sheet then an empty map is returned
     *  @param pathToWorkbook the path to the workbook file. By default, this is assumed
     *  to be a workbook in the KSL.excelDir directory with the name "sheetName.xlsx"
     *  @param skipFirstRow if true the first row of the sheet is skipped because it
     *  contains a header. The default is false.
     */
    fun readToMap(
        sheetName: String,
        pathToWorkbook: Path = KSL.excelDir.resolve("${sheetName}.xlsx"),
        skipFirstRow: Boolean = false,
    ): Map<String, Double> {

        FileInputStream(pathToWorkbook.toFile()).use {
            val workbook = ReadableWorkbook(it)
            val sheetOptional = workbook.findSheet(sheetName)
            if (sheetOptional.isEmpty) {
                logger.info { "No corresponding sheet named $sheetName in workbook $pathToWorkbook" }
                return emptyMap()
            }
            // sheet must exist
            val sheet = sheetOptional.get()
            val map = mutableMapOf<String, Double>()
            sheet.openStream().use {
                var rowCnt = 0
                it.forEach { row: Row ->
                    rowCnt++
                    if ((rowCnt == 1) && (skipFirstRow)) {
                        // skip
                    } else {
                        // process
                        val rowData = readRowAsStringList(row, 2)
                        if (rowData[0] != null) {
                            val sn = rowData[0]!!
                            if (rowData[1] != null) {
                                if (rowData[1].equals("NaN")) {
                                    map[sn] = Double.NaN
                                } else if (rowData[1].equals("+Infinity")) {
                                    map[sn] = Double.POSITIVE_INFINITY
                                } else if (rowData[1].equals("-Infinity")) {
                                    map[sn] = Double.NEGATIVE_INFINITY
                                } else {
                                    map[sn] = rowData[1]!!.toDouble()
                                }
                            }
                        }
                    }
                }
            }
            return map
        }
    }

    /**
     * Read a row assuming a fixed number of columns.  Cells that
     * are missing/null in the row are read as null Strings.
     *
     * @param row     the Excel row
     * @param numCol  the number of columns in the row
     * @param maxChar the maximum number of characters permitted for any string
     * @return a list of Strings representing the contents of the cells
     */
    fun readRowAsStringList(
        row: Row,
        numCol: Int,
        maxChar: Int = DEFAULT_MAX_CHAR_IN_CELL
    ): List<String?> {
        require(numCol > 0) { "The number of columns must be >= 1" }
        require(maxChar > 0) { "The maximum number of characters must be >= 1" }
        val list: MutableList<String?> = ArrayList()
        for (i in 0 until numCol) {
            var s: String? = null
            if (row.hasCell(i)) {
                val cell = row.getCell(i)
                if (cell != null) {
                    s = readCellAsString(cell)
                    if (s.length > maxChar) {
                        s = s.substring(0, maxChar - 1)
                        logger.warn { "The cell $cell was truncated to $maxChar characters" }
                    }
                }
            }
            list.add(s)
        }
        return list
    }

    /**
     * Reads the Excel cell and translates it into a String
     *
     * @param cell the Excel cell to read data from
     * @return the data in the form of a String
     */
    fun readCellAsString(cell: Cell): String {
        return when (cell.type) {
            CellType.STRING -> cell.asString()
            CellType.NUMBER -> {
                val v = cell.asNumber()
                v.toDouble().toString()
            }
            CellType.BOOLEAN -> {
                val value = cell.asBoolean()
                value.toString()
            }
            CellType.FORMULA -> cell.formula
            else -> ""
        }
    }
}