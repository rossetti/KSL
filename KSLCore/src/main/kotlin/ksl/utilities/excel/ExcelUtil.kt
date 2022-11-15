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
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.WorkbookUtil
import java.io.PrintWriter
import java.math.BigDecimal
import java.sql.Date
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp
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
     * @param workbook the workbook to copy to
     * @param sheetName the name of the sheet in the workbook to hold the results et values
     * @param writeHeader whether to write a header of the column names into the sheet. The default is true
     */
    fun writeSheet(resultSet: ResultSet, workbook: Workbook, sheetName: String, writeHeader: Boolean = true) {
        val ws = createSheet(workbook, sheetName)
        // write the header
        var rowCnt = 0
        if (writeHeader) {
            val names = DatabaseIfc.columnNames(resultSet)
            for (col in names.indices) {
                val header = ws.createRow(0)
                val cell = header.createCell(col)
                cell.setCellValue(names[col])
                ws.setColumnWidth(col, ((names[col].length + 2) * 256))
            }
            rowCnt++
        }
        // write all the rows
        val iterator = ResultSetRowIterator(resultSet)
        while (iterator.hasNext()) {
            val list = iterator.next()
            val row = ws.createRow(rowCnt)
            for (col in list.indices) {
                writeCell(row.createCell(col), list[col])
            }
            rowCnt++
        }
        logger.info { "Completed exporting sheet $sheetName to the workbook" }
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

    /** Copies the rows from the sheet to the CachedRowSet.  The copy is assumed to start
     * at row 1, column 1 (i.e. cell A1) and proceed to the right for the number of columns in the
     * row set and the number of rows of the sheet.  The copy is from the perspective of the CachedRowSet.
     * That is, all columns of a row of the CachedRowSet are attempted to be filled from a corresponding
     * row of the sheet.  If the row of the sheet does not have cell values for the corresponding column, then
     * the cell is interpreted as a null value when being placed in the corresponding column.  It is up to the client
     * to ensure that the cells in a row of the sheet are data type compatible with the corresponding column
     * in the row set.  Any rows that cannot be transfer in their entirety are logged to the supplied PrintWriter
     *
     * @param sheet the sheet that has the data to transfer to the CachedRowSet
     * @param rowSet the CachedRowSet to receive the data
     * @param numRowsToSkip indicates the number of rows to skip from the top of the sheet. Use 1 (default) if the sheet has
     * a header row
     *  @param rowBatchSize the number of rows to accumulate in a batch before completing a transfer
     *  @param unCompatibleRows a file to hold the rows that are not transferred in a string representation
     */
    fun writeSheetToCachedRowSet(
        sheet: Sheet,
        rowSet: CachedRowSet,
        numRowsToSkip: Int = 1,
        rowBatchSize: Int = minOf(rowSet.size(), 100),
        unCompatibleRows: PrintWriter = KSLFileUtil.createPrintWriter("BadRowsForSheet_${sheet.sheetName}")
    ) {
        val rowIterator = sheet.rowIterator()
        for (i in 1..numRowsToSkip) {
            if (rowIterator.hasNext()) {
                rowIterator.next()
            }
        }
        val colMetaData = DatabaseIfc.columnMetaData(rowSet)
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            val rowData = readRowAsObjectList(row, colMetaData.size)
            // rowData needs to be placed in row set

        }

    }

    private fun insertNewRow(rowData: List<Any?>, colMetaData: List<ColumnMetaData>, rowSet: CachedRowSet) {
        rowSet.moveToInsertRow()
        for (colIndex in colMetaData.indices) {
//TODO just don't know. not sure if object is translated to type
            rowSet.updateObject(colIndex + 1, rowData[colIndex])
        }
        rowSet.insertRow()
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