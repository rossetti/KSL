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


import com.opencsv.CSVReader
import com.opencsv.CSVWriter
import com.opencsv.exceptions.CsvException
import ksl.utilities.KSLArrays
import ksl.utilities.toStrings
import mu.KLoggable
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Path
import java.util.*

/**
 * A class to facilitate some basic CSV processing without having to worry about underlying csv library.
 * Helps with reading and writing arrays to csv files. Generally, exceptions are squashed.
 */
object CSVUtil : KLoggable {

    override val logger = logger()

    /**
     * Reads all rows from a csv file that may have the first row as a header
     * of column labels and each subsequent row as the data for
     * each column, e.g.
     * "x", "y"
     * 1.1, 2.0
     * 4.3, 6.4
     * etc.
     * This method squelches any IOExceptions. Writes warning to log. If there was a problem
     * an empty list is returned.
     *
     * @param skipLines  the number of lines to skip from the top of the file
     * @param pathToFile the path to the file
     * @return the filled list
     */
    fun readRows(pathToFile: Path, skipLines: Int = 0): List<Array<String>> {
        Objects.requireNonNull(pathToFile, "The path to the file must not be null")
        try {
            CSVReader(FileReader(pathToFile.toFile())).use { reader ->
                val list: List<Array<String>> = reader.readAll()
                return if (skipLines > 0) {
                    list.subList(skipLines, list.size)
                } else {
                    list
                }
            }
        } catch (e: IOException) {
            logger.warn("There was a problem reading the rows from file {}", pathToFile)
        } catch (e: CsvException) {
            logger.warn("There was a problem reading the rows from file {}", pathToFile)
        }
        return emptyList()
    }

    /**
     * Returns an iterator to the rows of a csv file that may have the first row as a header
     * of column labels and each subsequent row as the data for
     * each column, e.g.
     * "x", "y"
     * 1.1, 2.0
     * 4.3, 6.4
     * etc.
     * This method squelches any IOExceptions. An iterator with no elements is returned if there is a problem.
     *
     * @param pathToFile the path to the file
     * @return the filled list
     */
    fun csvIterator(pathToFile: Path): Iterator<Array<String>> {
        Objects.requireNonNull(pathToFile, "The path to the file must not be null")
        try {
            CSVReader(FileReader(pathToFile.toFile())).use { reader -> return reader.iterator() }
        } catch (e: IOException) {
            logger.warn("There was a problem getting an iterator from file {}", pathToFile)
        }
        return LinkedList<Array<String>>().iterator()
    }

    /**
     * Reads data from a csv file that has the first row as a header
     * of column labels and each subsequent row as the data for
     * each row, e.g.
     * "x", "y"
     * 1.1, 2.0
     * 4.3, 6.4
     * etc.
     * The List names will hold ("x", "y"). If names has strings it will be cleared.
     * The returned array will hold
     * data[0] = {1.1, 2.0}
     * data[1] = {4.3, 6.4}
     * etc.
     *
     * @param names      the list to fill with header names
     * @param pathToFile the path to the file
     * @return the filled array of arrays
     */
    fun readToRows(names: MutableList<String>, pathToFile: Path): Array<DoubleArray> {
        names.clear()
        val entries = readRows(pathToFile)
        if (entries.isEmpty()) {
            // no header and no data
            return Array(0) { DoubleArray(0) }
        }
        // size at least 1
        names.addAll(listOf(*entries[0]))
        return if (entries.size == 1) {
            // only header, no data
            Array(0) { DoubleArray(0) }
        } else {
            val list: List<Array<String>> = entries.subList(1, entries.size)
            KSLArrays.parseTo2DArray(list)
        }
        // was header and at least 1 other row
    }

    /**
     * Reads data from a csv file that has the first row as a header
     * of column labels and each subsequent row as the data for
     * each column, e.g.
     * "x", "y"
     * 1.1, 2.0
     * 4.3, 6.4
     * etc.
     * The List names will hold ("x", "y"). If names has strings it will be cleared.
     * The returned array will hold
     * data[0] = {1.1, 4.3}
     * data[1] = {2.0, 6.4}
     * etc.
     *
     * @param names      the list to fill with header names
     * @param pathToFile the path to the file
     * @return the filled array of arrays
     */
    fun readToColumns(names: MutableList<String>, pathToFile: Path): Array<DoubleArray> {
        val data = readToRows(names, pathToFile)
        return KSLArrays.transpose(data)
    }

    /**
     * IOException is squelched with a warning to the logger if there was a problem writing to the file.
     *
     * @param header            the names of the columns as strings
     * @param array             the array to write
     * @param applyQuotesToData if true the numeric data will be surrounded by quotes
     * @param pathToFile        the path to the file
     */
    fun writeArrayToCSVFile(
        array: Array<DoubleArray>,
        header: MutableList<String> = mutableListOf(),
        applyQuotesToData: Boolean = false,
        pathToFile: Path = KSL.outDir.resolve("arrayData.csv")
    ) {
        // if header is empty or null get size from array and make col names
        require(KSLArrays.isRectangular(array)) { "The supplied array was not rectangular" }
        if (header.isEmpty()) {
            val nc: Int = KSLArrays.maxNumColumns(array)
            for (i in 0 until nc) {
                val name = "col$i"
                header.add(name)
            }
        }
        try {
            CSVWriter(FileWriter(pathToFile.toFile())).use { writer ->
                writer.writeNext(header.toTypedArray())
                for (i in array.indices) {
                    writer.writeNext(array[i].toStrings(), applyQuotesToData)
                }
            }
        } catch (e: IOException) {
            logger.warn("There was a problem writing an array to csv file {}", pathToFile)
        }
    }
}