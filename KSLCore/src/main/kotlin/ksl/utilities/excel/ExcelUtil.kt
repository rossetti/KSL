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

import ksl.utilities.dbutil.DatabaseIfc
import ksl.utilities.dbutil.ResultSetRowIterator
import mu.KLoggable
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import java.math.BigDecimal
import java.sql.Date
import java.sql.ResultSet
import java.sql.Time
import java.sql.Timestamp

object ExcelUtil : KLoggable {

    override val logger = logger()

    fun writeSheet(workbook: Workbook, resultSet: ResultSet, sheetName: String, header: Boolean = true) {
        val ws = workbook.newWorksheet(sheetName.substring(0, minOf(30, sheetName.length)))
        // write the header
        var row = 0
        if (header){
            val names = DatabaseIfc.columnNames(resultSet)
            for(col in names.indices){
                ws.value(row, col, names[col])
                ws.width(col, ((names[col].length+5)).toDouble())
            }
            row++
        }
        // write all the rows
        val iterator = ResultSetRowIterator(resultSet)
        while (iterator.hasNext()) {
            val list = iterator.next()
            for (col in list.indices) {
                writeCell(ws, row, col, list[col])
            }
            row++
        }
        logger.info{"Completed exporting sheet $sheetName to the workbook"}
    }

    fun writeCell(ws: Worksheet, row: Int, col: Int, value: Any?) {
        if (value == null) {
            // nothing to write
        } else if (value is String) {
            ws.value(row, col, value.trim())
        } else if (value is Boolean) {
            ws.value(row, col, value)
        } else if (value is Int) {
            ws.value(row, col, value)
        } else if (value is Double) {
            ws.value(row, col, value)
        } else if (value is Float) {
            ws.value(row, col, value)
        } else if (value is BigDecimal) {
            ws.value(row, col, value)
        } else if (value is Long) {
            ws.value(row, col, value)
        } else if (value is Short) {
            ws.value(row, col, value)
        } else if (value is Date) {
            ws.value(row, col, value)
        } else if (value is Time) {
            ws.value(row, col, value)
        } else if (value is Timestamp) {
            ws.value(row, col, value)
        } else {
            logger.error("Could not cast type {} to Excel type.", value.javaClass.name)
            throw ClassCastException("Could not cast database type to Excel type: " + value.javaClass.name)
        }
    }
}