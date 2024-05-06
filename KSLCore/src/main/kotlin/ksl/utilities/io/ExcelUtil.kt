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

import com.opencsv.CSVWriterBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.DatabaseIfc.Companion
import ksl.utilities.io.dbutil.DatabaseIfc.Companion.exportAsWorkSheet
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.WorkbookUtil
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Date
import java.sql.Time
import java.sql.Timestamp
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
        val writer = CSVWriterBuilder(fileWriter).build()
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            val strings = readRowAsStringArray(row, numCol, maxChar)
            writer.writeNext(strings)
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

    /** Writes each entry in the map to an Excel workbook.
     *
     * @param map the map of information to export
     * @param wbName the name of the workbook
     * @param wbDirectory the directory to store the workbook
     */
    fun exportToExcel(
        map: Map<String, Double>,
        sheetName: String,
        wbName: String = sheetName,
        wbDirectory: Path = KSL.excelDir
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
            val headerRow = sheet.createRow(0)
            val nameHeader = headerRow.createCell(0)
            nameHeader.setCellValue("Element Name")
            val valueHeader = headerRow.createCell(1)
            valueHeader.setCellValue("Element Value")
            rowCnt++
            for((n,v) in map) {
                val nextRow = sheet.createRow(rowCnt)
                val nameCell = nextRow.createCell(0)
                nameCell.setCellValue(n)
                val valueCell = nextRow.createCell(1)
                writeCell(valueCell, v)
                rowCnt++
            }
            workbook.write(it)
            workbook.close()
            workbook.dispose()
            logger.info { "Closed workbook $path after writing map $sheetName to Excel" }
        }
    }

}