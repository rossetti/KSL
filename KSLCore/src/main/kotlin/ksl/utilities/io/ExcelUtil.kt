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

package ksl.utilities.io

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.ResultSetRowIterator
import org.apache.commons.csv.CSVFormat
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.util.WorkbookUtil
import org.apache.poi.xssf.streaming.SXSSFSheet
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.*
import java.sql.Date
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

object ExcelUtil  {

    val logger = KotlinLogging.logger {}

    const val DEFAULT_MAX_CHAR_IN_CELL = 512

    val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    /** Creates a sheet within the workbook with the name.  If a sheet already exists with the
     * same name then a new sheet with name sheetName_n, where n is the current number of sheets
     * in the workbook is created. Sheet names must follow Excel naming conventions.
     *
     * @param workbook the workbook, must not be null
     * @param sheetName the name of the sheet
     * @return the created sheet
     */
    fun createSheet(workbook: Workbook, sheetName: String): Sheet {
        var sheet = workbook.getSheet(sheetName)
        sheet = if (sheet == null) {
            workbook.createSheet(WorkbookUtil.createSafeSheetName(sheetName))
        } else {
            // sheet already exists
            val n = workbook.numberOfSheets
            val name = sheetName + "_" + n
            workbook.createSheet(WorkbookUtil.createSafeSheetName(name))
        }
        logger.info { "Created new sheet $sheetName in workbook" }
        return sheet
    }

    /**
     * Writes the Java Object to the Excel cell
     *
     * @param cell   the cell to write
     * @param `object` a Java object
     */
    fun writeCell(cell: Cell, value: Any?) {
        when (value) {
            null -> { // nothing to write
            }

            is String -> {
                cell.setCellValue(value.trim())
            }

            is Boolean -> {
                cell.setCellValue(value)
            }

            is Int -> {
                cell.setCellValue(value.toDouble())
            }

            is Double -> {
                cell.setCellValue(value)
            }

            is Float -> {
                cell.setCellValue(value.toDouble())
            }

            is BigDecimal -> {
                cell.setCellValue(value.toDouble())
            }

            is Long -> {
                cell.setCellValue(value.toDouble())
            }

            is Short -> {
                cell.setCellValue(value.toDouble())
            }

            is Date -> {
                cell.setCellValue(value)
                val wb = cell.sheet.workbook
                val cellStyle = wb.createCellStyle()
                val createHelper = wb.creationHelper
                cellStyle.dataFormat = createHelper.createDataFormat().getFormat("m/d/yy")
                cell.cellStyle = cellStyle
            }

            is Time -> {
                cell.setCellValue(value)
                val wb = cell.sheet.workbook
                val cellStyle = wb.createCellStyle()
                val createHelper = wb.creationHelper
                cellStyle.dataFormat = createHelper.createDataFormat().getFormat("h:mm:ss AM/PM")
                cell.cellStyle = cellStyle
            }

            is Timestamp -> {
                val dateFromTimeStamp = java.util.Date.from(value.toInstant())
                val excelDate = DateUtil.getExcelDate(dateFromTimeStamp)
                cell.setCellValue(excelDate)
                val wb = cell.sheet.workbook
                val cellStyle = wb.createCellStyle()
                val createHelper = wb.creationHelper
                cellStyle.dataFormat = createHelper.createDataFormat().getFormat("yyyy-MM-dd HH:mm:ss")
                cell.cellStyle = cellStyle
            }

            else -> {
                logger.error { "Could not cast type ${value.javaClass.name} to Excel type." }
                throw ClassCastException("Could not cast database type to Excel type: ${value.javaClass.name}")
            }
        }
    }

    /**
     * IO exceptions are squelched in this method.  If there is a problem, then null is returned.
     * Opens an Apache POI XSSFWorkbook instance. The user is responsible for closing the workbook
     * when done. Do not try to write to the returned workbook.
     *
     * @param pathToWorkbook the path to a valid Excel xlsx workbook
     * @return an Apache POI XSSFWorkbook or null if there was a problem opening the workbook.
     */
    fun openExistingXSSFWorkbookReadOnly(pathToWorkbook: Path): XSSFWorkbook? {
        val file = pathToWorkbook.toFile()
        if (!file.exists()) {
            logger.warn { "The file at $pathToWorkbook does not exist" }
            return null
        }
        val pkg: OPCPackage? = try {
            OPCPackage.open(file, PackageAccess.READ)
        } catch (e: InvalidFormatException) {
            logger.error { "The workbook has an invalid format. See Apache POI InvalidFormatException" }
            return null
        }
        var wb: XSSFWorkbook? = null
        try {
            wb = XSSFWorkbook(pkg)
            logger.info { "Opened workbook for reading only at: $pathToWorkbook" }
        } catch (e: IOException) {
            logger.error { "There was an IO error when trying to open the workbook at: $pathToWorkbook" }
        }
        return wb
    }

    /**
     * @param sheet  the sheet to process
     * @param numColumns the number of columns for each row
     * @param skipFirstRow true means first row is skipped
     * @return a list of lists of the objects representing each cell of each row of the sheet
     */
    fun readSheetAsObjects(
        sheet: Sheet,
        numColumns: Int = numberColumnsForCSVHeader(sheet),
        skipFirstRow: Boolean = false
    ): List<List<Any?>> {
        val rowIterator = sheet.rowIterator()
        if (skipFirstRow) {
            if (rowIterator.hasNext()) {
                rowIterator.next()
            }
        }
        val list: MutableList<List<Any?>> = ArrayList()
        while (rowIterator.hasNext()) {
            list.add(readRowAsObjectList(rowIterator.next(), numColumns))
        }
        return list
    }

    /**
     * Read a row assuming a fixed number of columns.  Cells that
     * are missing/null in the row are read as null objects.
     *
     * @param row    the Excel row
     * @param numColumns the number of columns to read in the row
     * @return a list of objects representing the contents of the cells for each column
     */
    fun readRowAsObjectList(row: Row, numColumns: Int = numberColumns(row)): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 0 until numColumns) {
            val cell = row.getCell(i)
            if (cell == null) {
                list.add(null)
            } else {
                list.add(readCellAsObject(cell))
            }
        }
        return list
    }

    /**
     * Reads the Excel cell and translates it into a Java object
     *
     * @param cell the Excel cell to read data from
     * @return the data in the form of a Java object
     */
    fun readCellAsObject(cell: Cell): Any? {
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim()
            CellType.NUMERIC -> if (DateUtil.isCellDateFormatted(cell)) {
                cell.dateCellValue
            } else {
                cell.numericCellValue
            }

            CellType.BOOLEAN -> cell.booleanCellValue
            CellType.FORMULA -> cell.cellFormula
            else -> null
        }
    }

    /**
     * Reads the Excel cell and translates it into a String
     *
     * @param cell the Excel cell to read data from
     * @return the data in the form of a String
     */
    fun readCellAsString(cell: Cell): String {
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> if (DateUtil.isCellDateFormatted(cell)) {
                val date = cell.dateCellValue
                DATE_TIME_FORMATTER.format(date.toInstant())
            } else {
                val value = cell.numericCellValue
                value.toString()
            }
            CellType.BOOLEAN -> {
                val value = cell.booleanCellValue
                value.toString()
            }
            CellType.FORMULA -> cell.cellFormula
            else -> ""
        }
    }

    /**
     * Read a row assuming a fixed number of columns.  Cells that
     * are missing/null in the row are read as null Strings.
     *
     * @param row     the Excel row
     * @param numCol  the number of columns in the row
     * @param maxChar the maximum number of characters permitted for any string
     * @return a list of java Strings representing the contents of the cells
     */
    fun readRowAsStringList(row: Row, numCol: Int, maxChar: Int = DEFAULT_MAX_CHAR_IN_CELL): List<String?> {
        require(numCol > 0) { "The number of columns must be >= 1" }
        require(maxChar > 0) { "The maximum number of characters must be >= 1" }
        val list: MutableList<String?> = ArrayList()
        for (i in 0 until numCol) {
            val cell = row.getCell(i)
            var s: String? = null
            if (cell != null) {
                s = readCellAsString(cell)
                if (s.length > maxChar) {
                    s = s.substring(0, maxChar - 1)
                    logger.warn { "The cell ${cell.stringCellValue} was truncated to $maxChar characters" }
                }
            }
            list.add(s)
        }
        return list
    }

    /**
     * Read a row assuming a fixed number of columns.  Cells that
     * are missing/null in the row are read as null Strings.
     *
     * @param row     the Excel row
     * @param numCol  the number of columns in the row
     * @param maxChar the maximum number of characters permitted for any string
     * @return an array of java Strings representing the contents of the cells
     */
    fun readRowAsStringArray(row: Row, numCol: Int, maxChar: Int = DEFAULT_MAX_CHAR_IN_CELL): Array<String?> {
        return readRowAsStringList(row, numCol, maxChar).toTypedArray()
    }

    /**
     * Starts as the last row number of the sheet and looks up in the column to find the first non-null cell
     *
     * @param sheet       the sheet holding the column, must not be null
     * @param columnIndex the column index, must be 0 or greater, since POI is 0 based columns
     * @return the number of rows that have data in the particular column as defined by not having
     * a null cell.
     */
    fun columnSize(sheet: Sheet, columnIndex: Int): Int {
        var lastRow = sheet.lastRowNum
        while (lastRow >= 0 && isCellEmpty(sheet.getRow(lastRow).getCell(columnIndex))) {
            lastRow--
        }
        return lastRow + 1
    }

    /**
     * @param cell the cell to check
     * @return true if it null or blank or string and empty
     */
    fun isCellEmpty(cell: Cell): Boolean {
        return if (cell.cellType == CellType.BLANK) {
            true
        } else cell.cellType == CellType.STRING && cell.stringCellValue.isEmpty()
    }

    /**
     * Treats the columns as fields in a csv file, writes each row as a separate csv row
     * in the resulting csv file
     *
     * @param sheet        the sheet to write, must not be null
     * @param skipFirstRow if true, the first row is skipped in the sheet
     * @param pathToCSV    a Path to the file to write as csv, must not be null
     * @param numCol       the number of columns to write from each row, must be at least 1
     * @param maxChar      the maximum number of characters that can be in any cell, must be at least 1
     * @throws IOException an IO exception
     */
    fun writeSheetToCSV(
        sheet: Sheet,
        numCol: Int = numberColumnsForCSVHeader(sheet),
        skipFirstRow: Boolean = false,
        pathToCSV: Path = KSL.outDir.resolve("${sheet.sheetName}.csv"),
        maxChar: Int = DEFAULT_MAX_CHAR_IN_CELL
    ) {
        require(numCol > 0) { "The number of columns must be >= 1" }
        require(maxChar > 0) { "The maximum number of characters must be >= 1" }
        val rowIterator = sheet.rowIterator()
        if (skipFirstRow) {
            if (rowIterator.hasNext()) {
                rowIterator.next()
            }
        }
        val fileWriter = FileWriter(pathToCSV.toFile())
        val writer = CSVFormat.DEFAULT.builder().build().print(fileWriter)
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            val strings = readRowAsStringArray(row, numCol, maxChar)
            writer.printRecord(strings)
        }
        writer.close()
    }

    /**
     * Assumes that the first row is a header for a CSV like file and
     * returns the number of columns (1 for each header)
     *
     * @param sheet the sheet to write, must not be null
     * @return the number of header columns
     */
    fun numberColumnsForCSVHeader(sheet: Sheet): Int {
        val row = sheet.getRow(0)
        return row?.lastCellNum?.toInt() ?: 0
    }

    /**
     * Assumes that the first row is a header for a CSV like file and
     * returns the number of columns (1 for each header)
     *
     * @param sheet the sheet to write, must not be null
     * @return the number of header columns
     */
    fun numberColumns(row: Row): Int {
        return row.lastCellNum.toInt()
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
    fun writeToExcel(
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
            val workbook = SXSSFWorkbook(100)
            val sheet = workbook.createSheet(sheetName)
            if (header) {
                val headerRow = sheet.createRow(0)
                val nameHeader = headerRow.createCell(0)
                nameHeader.setCellValue("Element Name")
                val valueHeader = headerRow.createCell(1)
                valueHeader.setCellValue("Element Value")
                rowCnt++
            }
            for((n,v) in map) {
                val nextRow = sheet.createRow(rowCnt)
                val nameCell = nextRow.createCell(0)
                nameCell.setCellValue(n)
                val valueCell = nextRow.createCell(1)
                if (v.isNaN()){
                    valueCell.setCellValue("NaN")
                } else if (v == Double.POSITIVE_INFINITY){
                    valueCell.setCellValue("+Infinity")
                } else if (v == Double.NEGATIVE_INFINITY){
                    valueCell.setCellValue("-Infinity")
                } else {
                    writeCell(valueCell, v)
                }
                rowCnt++
            }
            workbook.write(it)
            workbook.close()
            workbook.dispose()
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
        val workbook: XSSFWorkbook = openExistingXSSFWorkbookReadOnly(pathToWorkbook)
            ?: throw IOException("There was a problem opening the workbook at $pathToWorkbook!")
        val sheet = workbook.getSheet(sheetName)
        if (sheet == null) {
            logger.info { "No corresponding sheet named $sheetName in workbook $pathToWorkbook" }
            return emptyMap()
        }
        val rowIterator = sheet.rowIterator()
        if (skipFirstRow) {
            if (rowIterator.hasNext()) {
                rowIterator.next()
            }
        }
        val map = mutableMapOf<String, Double>()
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            val rowData = readRowAsStringList(row, 2)
            if (rowData[0] != null) {
                val sn = rowData[0]!!
                if (rowData[1] != null) {
                    if (rowData[1].equals("NaN")){
                        map[sn] = Double.NaN
                    } else if (rowData[1].equals("+Infinity")){
                        map[sn] = Double.POSITIVE_INFINITY
                    } else if (rowData[1].equals("-Infinity")){
                        map[sn] = Double.NEGATIVE_INFINITY
                    } else {
                        map[sn] = rowData[1]!!.toDouble()
                    }
                }
            }
        }
        return map
    }

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
        val names = DatabaseIfc.columnNames(resultSet)
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
        val s = sheet as SXSSFSheet
        s.flushRows()
        DatabaseIfc.logger.info { "Completed exporting ResultSet to Excel worksheet ${sheet.sheetName}" }
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
            val workbook = SXSSFWorkbook(100)
            for (tableName in tableNames) {
                // get result set
                val rs = db.selectAllIntoOpenResultSet(tableName, schemaName)
                if (rs != null) {
                    // write result set to workbook
                    val sheet = ExcelUtil.createSheet(workbook, tableName)
                    ExcelUtil.exportAsWorkSheet(rs, sheet)
                    // close result set
                    rs.close()
                }
            }
            workbook.write(it)
            workbook.close()
            workbook.dispose()
            logger.info { "Closed workbook $path after writing database ${db.label} output" }
            DatabaseIfc.logger.info { "Completed database ${db.label} export to workbook at $path" }
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
        val workbook: XSSFWorkbook = openExistingXSSFWorkbookReadOnly(pathToWorkbook)
            ?: throw IOException("There was a problem opening the workbook at $pathToWorkbook!")

        DatabaseIfc.logger.info { "Writing workbook $pathToWorkbook to database ${db.label}" }
        for (tableName in tableNames) {
            val sheet = workbook.getSheet(tableName)
            if (sheet == null) {
                DatabaseIfc.logger.info { "Skipping table $tableName no corresponding sheet in workbook" }
                continue
            }
            DatabaseIfc.logger.trace { "Processing the sheet for table $tableName." }
            val tblMetaData = db.tableMetaData(tableName, schemaName)
            DatabaseIfc.logger.trace { "Constructing path for bad rows file for table $tableName." }
            val dirStr = pathToWorkbook.toString().substringBeforeLast(".")
            val path = Path.of(dirStr)
            val pathToBadRows = path.resolve("${tableName}_MissingRows.txt")
            DatabaseIfc.logger.trace { "The file to hold bad data for table $tableName is $pathToBadRows" }
            val badRowsFile = KSLFileUtil.createPrintWriter(pathToBadRows)
            val numToSkip = if (skipFirstRow) 1 else 0
            val success = importSheetToTable(db,
                sheet,
                tableName,
                tblMetaData.size,
                schemaName,
                numToSkip,
                unCompatibleRows = badRowsFile
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
        sheet: Sheet,
        tableName: String,
        numColumns: Int,
        schemaName: String?,
        numRowsToSkip: Int,
        rowBatchSize: Int = 100,
        unCompatibleRows: PrintWriter
    ): Boolean {
        return try {
            val rowIterator = sheet.rowIterator()
            for (i in 1..numRowsToSkip) {
                if (rowIterator.hasNext()) {
                    rowIterator.next()
                }
            }
            DatabaseIfc.logger.trace { "Getting connection to import ${sheet.sheetName} into table $tableName of schema $schemaName of database ${db.label}" }
            DatabaseIfc.logger.trace { "Table $tableName to hold data for sheet ${sheet.sheetName} has $numColumns columns to fill." }
            db.getConnection().use { con ->
                con.autoCommit = false
                // make prepared statement for inserts
                val insertStatement = DatabaseIfc.makeInsertPreparedStatement(con, tableName, numColumns, schemaName)
                var batchCnt = 0
                var cntBad = 0
                var rowCnt = 0
                var cntGood = 0
                while (rowIterator.hasNext()) {
                    val row = rowIterator.next()
                    val rowData = ExcelUtil.readRowAsObjectList(row, numColumns)
                    rowCnt++
                    DatabaseIfc.logger.trace { "Read ${rowData.size} elements from sheet ${sheet.sheetName}" }
                    DatabaseIfc.logger.trace { "Sheet Data: $rowData" }
                    // rowData needs to be placed into insert statement
                    val success = DatabaseIfc.addBatch(rowData, numColumns, insertStatement)
                    if (!success) {
                        DatabaseIfc.logger.trace { "Wrote row number ${row.rowNum} of sheet ${sheet.sheetName} to bad data file" }
                        unCompatibleRows.println("Sheet: ${sheet.sheetName} row: ${row.rowNum} not written: $rowData")
                        cntBad++
                    } else {
                        DatabaseIfc.logger.trace { "Inserted data into batch for insertion" }
                        batchCnt++
                        if (batchCnt.mod(rowBatchSize) == 0) {
                            val ni = insertStatement.executeBatch()
                            con.commit()
                            DatabaseIfc.logger.trace { "Wrote batch of size ${ni.size} to table $tableName" }
                            batchCnt = 0
                        }
                        cntGood++
                    }
                }
                if (batchCnt > 0) {
                    val ni = insertStatement.executeBatch()
                    con.commit()
                    DatabaseIfc.logger.trace { "Wrote batch of size ${ni.size} to table $tableName" }
                }
                DatabaseIfc.logger.info { "Transferred $cntGood out of $rowCnt rows for ${sheet.sheetName}. There were $cntBad incompatible rows written." }
            }
            true
        } catch (ex: SQLException) {
            DatabaseIfc.logger.error(
                ex
            ) { "SQLException when importing ${sheet.sheetName} into table $tableName of schema $schemaName of database ${db.label}" }
            false
        }
    }

}