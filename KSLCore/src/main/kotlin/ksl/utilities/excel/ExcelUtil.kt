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

import mu.KLoggable
import org.apache.poi.openxml4j.exceptions.InvalidFormatException
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.openxml4j.opc.PackageAccess
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.WorkbookUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.IOException
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Date
import java.sql.Time
import java.sql.Timestamp

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
            logger.warn("The file at {} does not exist", pathToWorkbook)
            return null
        }
        val pkg: OPCPackage? = try {
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