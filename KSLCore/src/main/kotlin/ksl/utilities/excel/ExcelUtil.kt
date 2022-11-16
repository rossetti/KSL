/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.excel

import ksl.utilities.dbutil.ColumnMetaData
import ksl.utilities.dbutil.DatabaseIfc
import ksl.utilities.dbutil.ResultSetRowIterator
import ksl.utilities.io.KSLFileUtil
import mu.KLoggable
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.WorkbookUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException
import java.io.PrintWriter
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.*
import java.sql.Date
import java.util.*
import javax.sql.rowset.CachedRowSet

object ExcelUtil : KLoggable {

    override val logger = logger()

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
        logger.info("Created new sheet {} in workbook", sheetName)
        return sheet
    }

    /**
     * @param resultSet the result set to copy from
     * @param sheet the sheet in the workbook to hold the results et values
     * @param writeHeader whether to write a header of the column names into the sheet. The default is true
     */
    fun writeSheet(resultSet: ResultSet, sheet: Sheet, writeHeader: Boolean = true) {
        require(!resultSet.isClosed) { "The supplied ResultSet is closed when trying to write workbook ${sheet.sheetName} " }
        // write the header
        var rowCnt = 0
        if (writeHeader) {
            val names = DatabaseIfc.columnNames(resultSet)
            for (col in names.indices) {
                val header = sheet.createRow(0)
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
                writeCell(row.createCell(col), list[col])
            }
            rowCnt++
        }
        logger.info { "Completed exporting sheet ${sheet.sheetName} to the workbook" }
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
     * Opens the workbook for reading only and writes the sheets of the workbook into database tables.
     * The list of names is the names of the
     * sheets in the workbook and the names of the tables that need to be written. They are in the
     * order that is required for entering data so that no integrity constraints are violated. The
     * underlying workbook is closed after the operation.
     *
     * @param pathToWorkbook the path to the workbook. Must be valid workbook with .xlsx extension
     * @param skipFirstRow   if true the first row of each sheet is skipped
     * @param db             the database to write to
     * @param schemaName the name of the schema containing the named tables
     * @param tableNames     the names of the sheets and tables in the order that needs to be written
     * @throws IOException an io exception
     */
    fun writeWorkbookToDatabase(
        pathToWorkbook: Path,
        skipFirstRow: Boolean = true,
        db: DatabaseIfc,
        schemaName: String? = db.defaultSchemaName,
        tableNames: List<String>
    ) {
        val workbook: XSSFWorkbook = openExistingXSSFWorkbookReadOnly(pathToWorkbook)
            ?: throw IOException("There was a problem opening the workbook at $pathToWorkbook!")

        logger.info("Writing workbook {} to database {}", pathToWorkbook, db.label)
        for (tableName in tableNames) {
            val sheet = workbook.getSheet(tableName)
            if (sheet == null) {
                logger.info("Skipping table {} no corresponding sheet in workbook", tableName)
                continue
            }
            val rs = db.selectAllIntoOpenResultSet(schemaName, tableName)
            if (rs != null) {
                val pathToBadRows = pathToWorkbook.resolve("${tableName}_MissingRows")
                val badRowsFile = KSLFileUtil.createPrintWriter(pathToBadRows)
                val numToSkip = if (skipFirstRow) 1 else 0
                writeSheetToResultSet(sheet, rs, numToSkip, unCompatibleRows = badRowsFile)
            } else {
                logger.info { "Unable to write sheet $tableName to database ${db.label}. Could not form ResultSet for the table" }
            }
        }
        workbook.close()
        logger.info("Closed workbook {} ", pathToWorkbook)
        logger.info("Completed writing workbook {} to database {}", pathToWorkbook, db.label)
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
            logger.warn("The file at {} does not exist", pathToWorkbook)
            return null
        }
        var pkg: OPCPackage? = null
        pkg = try {
            OPCPackage.open(file, PackageAccess.READ)
        } catch (e: InvalidFormatException) {
            logger.error("The workbook has an invalid format. See Apache POI InvalidFormatException")
            return null
        }
        var wb: XSSFWorkbook? = null
        try {
            wb = XSSFWorkbook(pkg)
            logger.info("Opened workbook for reading only at: {}", pathToWorkbook)
        } catch (e: IOException) {
            logger.error("There was an IO error when trying to open the workbook at: {}", pathToWorkbook)
        }
        return wb
    }

    /** Copies the rows from the sheet to the ResultSet.  The copy is assumed to start
     * at row 1, column 1 (i.e. cell A1) and proceed to the right for the number of columns in the
     * result set and the number of rows of the sheet.  The copy is from the perspective of the ResultSet.
     * That is, all columns of a row of the ResultSet are attempted to be filled from a corresponding
     * row of the sheet.  If the row of the sheet does not have cell values for the corresponding column, then
     * the cell is interpreted as a null value when being placed in the corresponding column.  It is up to the client
     * to ensure that the cells in a row of the sheet are data type compatible with the corresponding column
     * in the result set.  Any rows that cannot be transfer in their entirety are logged to the supplied PrintWriter
     *
     * @param sheet the sheet that has the data to transfer to the ResultSet
     * @param resultSet the ResultSet to receive the data. It must be open and have an active connection.  It is
     * the responsibility of the caller to close the result set.
     * @param numRowsToSkip indicates the number of rows to skip from the top of the sheet. Use 1 (default) if the sheet has
     * a header row
     *  @param rowBatchSize the number of rows to accumulate in a batch before completing a transfer
     *  @param unCompatibleRows a file to hold the rows that are not transferred in a string representation
     */
    fun writeSheetToResultSet(
        sheet: Sheet,
        resultSet: ResultSet,
        numRowsToSkip: Int = 1,
        rowBatchSize: Int = 100,
        unCompatibleRows: PrintWriter = KSLFileUtil.createPrintWriter("BadRowsForSheet_${sheet.sheetName}")
    ) {
        require(!resultSet.isClosed) { "The supplied ResultSet is closed" }
        val rowSet = DatabaseIfc.createCachedRowSet(resultSet)
        val rowIterator = sheet.rowIterator()
        for (i in 1..numRowsToSkip) {
            if (rowIterator.hasNext()) {
                rowIterator.next()
            }
        }
        val colMetaData = DatabaseIfc.columnMetaData(rowSet)
        var batchCnt = 0
        var cntBad = 0
        var rowCnt = 0
        var cntGood = 0
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            val rowData = readRowAsObjectList(row, colMetaData.size)
            rowCnt++
            // rowData needs to be placed in row set
            val success = insertNewRow(rowData, colMetaData, rowSet)
            if (!success) {
                unCompatibleRows.println("Sheet: ${sheet.sheetName} row: ${row.rowNum} not written: $rowData")
                cntBad++
            } else {
                batchCnt++
                if (batchCnt.mod(rowBatchSize) == 0) {
                    batchCnt = 0
                    rowSet.acceptChanges()
                }
                cntGood++
            }
        }
        logger.info { "Transferred $cntGood out of $rowCnt rows for ${sheet.sheetName}. There were $cntBad incompatible rows written." }
    }

    /** This method inserts the
     * @param rowData the data to be inserted
     * @param colMetaData the column metadata for the row set
     * @param rowSet a row set to hold the new data
     * @return returns true if the data was inserted false if something went wrong and no insert made
     */
    private fun insertNewRow(rowData: List<Any?>, colMetaData: List<ColumnMetaData>, rowSet: CachedRowSet): Boolean {
        //TODO notice that elements of colMetaData are not used. Consider changing to number of columns
        return try {
            rowSet.moveToInsertRow()
            for (colIndex in colMetaData.indices) {
                //TODO just don't know. not sure if object is translated to type
                rowSet.updateObject(colIndex + 1, rowData[colIndex])
            }
            rowSet.insertRow()
            true
        } catch (e: SQLException) {
            false
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
    fun readRowAsObjectList(row: Row, numColumns: Int): List<Any?> {
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
}