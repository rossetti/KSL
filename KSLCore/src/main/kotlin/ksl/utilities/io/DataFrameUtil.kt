/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.api.rows
import java.io.PrintWriter
import java.lang.Appendable

object DataFrameUtil {

    /**
     *  Writes the data frame as a MarkDown based table
     *  by converting rows to strings.
     */
    fun <T> buildMarkDown(df: DataFrame<T>, appendable: Appendable) {
        val formats = mutableListOf<MarkDown.ColFmt>()
        for (c in df.columnTypes()) {
            if (c.classifier == String::class) {
                formats.add(MarkDown.ColFmt.LEFT)
            } else {
                formats.add(MarkDown.ColFmt.CENTER)
            }
        }
        val h = MarkDown.tableHeader(df.columnNames(), formats)
        appendable.appendLine(h)
        for (row in df.rows()){
            val values: List<Any?> = row.values()
            val string = values.joinToString()
            val strings: List<String> = string.split(",")
            val line = MarkDown.tableRow(strings)
            appendable.appendLine(line)
        }
    }
}

fun <T> DataFrame<T>.writeMarkDownTable(writer: PrintWriter = KSL.createPrintWriter("dataFrame.md")){
    DataFrameUtil.buildMarkDown(this, writer)
    writer.flush()
}

fun <T> DataFrame<T>.asMarkDownTable(): String {
    val sb = StringBuilder()
    DataFrameUtil.buildMarkDown(this, sb)
    return sb.toString()
}

