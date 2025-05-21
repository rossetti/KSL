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

package ksl.utilities.io.tabularfiles

import ksl.utilities.io.CSVRowIterator
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.SQLiteDb
import ksl.utilities.collections.HashBiMap
import ksl.utilities.collections.MutableBiMap
import org.apache.commons.csv.CSVRecord
import org.jetbrains.kotlinx.dataframe.AnyFrame
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.reflect.KType

enum class DataType {
    NUMERIC, TEXT
}

/**
 * Describes a type of column within the tabular file.  There are only two types: numeric and text
 * The numeric type should be used for numeric data (float, double, long, int, etc.). In addition,
 * use the numeric type for boolean values, which are stored 1.0 = true, 0.0 = false).  The text type
 * should be used for strings and date/time data.  Date/time data is saved
 * as ISO8601 strings ("YYYY-MM-DD HH:MM:SS.SSS").  If you need more type complexity, you should use
 * a database.
 */
data class ColumnType(val name: String, val dataType: DataType)

/**
 * An abstraction for holding tabular data in a single file. That is, a list of columns
 * with specified data types and rows containing the values of every column stored within rows within a file.
 * The order of the columns is important. (first column, second column, etc.). The order of the
 * rows is relevant (first row, second row, etc.).
 *
 * There are only two types: numeric and text.
 * The numeric type should be used for numeric data (float, double, long, int, etc.). In addition,
 * use the numeric type for boolean values, which are stored 1.0 = true, 0.0 = false).  The text type
 * should be used for strings and date/time data.  Date/time data is saved
 * as ISO8601 strings ("YYYY-MM-DD HH:MM:SS.SSS").  If you need more type complexity, you should use
 * a database.
 *
 */
abstract class TabularFile(columns: Map<String, DataType>, val path: Path) {

//TODO use a builder pattern to define and add the columns

    protected val myColumnTypes: Map<String, DataType> = columns.toMap()
    protected var myNameAndIndex: MutableBiMap<String, Int> = HashBiMap()
    protected var myNumericIndices: MutableBiMap<Int, Int> = HashBiMap()
    protected var myTextIndices: MutableBiMap<Int, Int> = HashBiMap()
    protected val myColumnNames: MutableList<String> = mutableListOf()
    protected var myDataTypes: MutableList<DataType> = mutableListOf()

    init {
        require(columns.isNotEmpty()) { "The number of columns must be > 0" }
        for ((k, _) in myColumnTypes) {
            myColumnNames.add(k)
        }
        var i = 0
        var cntNumeric = 0
        var cntText = 0
        for (name in myColumnNames) {
            myNameAndIndex[name] = i
            val type = myColumnTypes[name]!!
            if (type === DataType.NUMERIC) {
                myNumericIndices[i] = cntNumeric
                cntNumeric++
            } else {
                myTextIndices[i] = cntText
                cntText++
            }
            myDataTypes.add(type)
            i++
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Tabular File")
        sb.appendLine("Path = $path ")
        sb.appendLine("Column Names:")
        sb.appendLine(myColumnNames)
        sb.appendLine("Column Types:")
        sb.appendLine(myDataTypes)
        sb.appendLine("Column Indices:")
        for ((k, v) in myNameAndIndex) {
            sb.appendLine("column $k = index $v")
        }
        sb.appendLine("Numeric Indices:")
        for ((k, v) in myNumericIndices) {
            sb.appendLine("column index $k = numeric index $v")
        }
        sb.appendLine("Text Indices:")
        for ((k, v) in myTextIndices) {
            sb.appendLine("column index $k = text index $v")
        }
        return sb.toString()
    }

    /** Returns the storage index of the numeric column at column index
     *
     * @param colNum the column index
     * @return the assigned storage-index for the numeric value
     */
    fun numericStorageIndex(colNum: Int): Int {
        return myNumericIndices[colNum]!!
    }

    /** Returns the storage index of the text column at column index
     *
     * @param colNum the column index
     * @return the assigned storage-index for the text value
     */
    fun textStorageIndex(colNum: Int): Int {
        return myTextIndices[colNum]!!
    }

    /** Returns the column index associated with the storage index
     *
     * @param storageIndex the storage index to find
     * @return the column index
     */
    fun columnIndexForNumeric(storageIndex: Int): Int {
        return myNumericIndices.inverse[storageIndex]!!
    }

    /** Returns the column index associated with the storage index
     *
     * @param storageIndex the storage index to find
     * @return the column index
     */
    fun columnIndexForText(storageIndex: Int): Int {
        return myTextIndices.inverse[storageIndex]!!
    }

    /**
     *
     * @param colNum 0 based indexing
     * @return the name of the column at the index
     */
    fun columnName(colNum: Int): String {
        return myNameAndIndex.inverse[colNum]!!
    }

    /**
     *
     * @param colNum 0 based indexing
     * @return the data type of the column at the index
     */
    fun dataType(colNum: Int): DataType {
        return dataTypes[colNum]
    }

    /**
     * @return the number of columns of tabular data
     */
    val numberColumns: Int
        get() {
            return columnTypes.size
        }

    /**
     *
     * @param name the name of the column
     * @return the index or -1 if not found
     */
    fun columnIndex(name: String): Int {
        return myNameAndIndex[name] ?: return -1
    }

    /**
     *
     * @param colNum the index into the row (0 based)
     * @return true if the cell at location i is NUMERIC
     */
    fun isNumeric(colNum: Int): Boolean {
        return myDataTypes[colNum] == DataType.NUMERIC
    }

    /**
     *
     * @param colNum the index into the row (0 based)
     * @return true if the cell at location i is TEXT
     */
    fun isText(colNum: Int): Boolean {
        return myDataTypes[colNum] == DataType.TEXT
    }

    /**
     *
     * @return the total number of numeric columns
     */
    val numNumericColumns: Int
        get() {
            return myNumericIndices.size
        }

    /**
     *
     * @return the total number of text columns
     */
    val numTextColumns: Int
        get() {
            return myTextIndices.size
        }

    /**
     *
     * @return the map of columns associated with this tabular file
     */
    val columnTypes: Map<String, DataType>
        get() {
            return myColumnTypes.toMap()
        }

    /**
     * @return an ordered list of the column names
     */
    val columnNames: List<String>
        get() {
            return myColumnNames
        }

    /**
     *
     * @return  A list of all the numeric column names
     */
    val numericColumnNames: List<String>
        get() {
            val theNames: MutableList<String> = ArrayList()
            val allNames = columnNames
            for (name in allNames) {
                if (isNumeric(columnIndex(name))) {
                    theNames.add(name)
                }
            }
            return theNames
        }

    /**
     *
     * @return  A list of all the text column names
     */
    val textColumnNames: List<String>
        get() {
            val theNames: MutableList<String> = ArrayList()
            val allNames = columnNames
            for (name in allNames) {
                if (isText(columnIndex(name))) {
                    theNames.add(name)
                }
            }
            return theNames
        }

    /**
     * @return an ordered list of the column data types
     */
    val dataTypes: List<DataType>
        get() {
            return myDataTypes
        }

    /**
     *
     * @return true if all columns have type NUMERIC
     */
    val isAllNumeric: Boolean
        get() {
            return myDataTypes.size == numNumericColumns
        }

    /**
     *
     * @return true if all columns have type TEXT
     */
    val isAllText: Boolean
        get() {
            return myDataTypes.size == numTextColumns
        }

    /**
     *
     * @param elements the elements to check
     * @return true if all elements match the correct types
     */
    fun checkTypes(elements: Array<Any?>): Boolean {
        if (elements.size != numberColumns) {
            return false
        }
        val dataTypes = dataTypes
        for ((i, type) in dataTypes.withIndex()) {
            if (type == DataType.NUMERIC) {
                if (elements[i] == null) {
                    return false
                } else {
                    if (!isNumeric(elements[i]!!)) {
                        return false
                    }
                }
            } else {
                // must be text
                if (elements[i] != null) {
                    if (isNumeric(elements[i]!!)) {
                        return false
                    }
                }
            }
        }
        return true
    }

    /**
     * Transforms the file into an SQLite database file
     *
     * @return a reference to the database
     * @throws IOException if something goes wrong
     */
    fun asDatabase(): DatabaseIfc {
        val parent: Path = path.parent
        val dbFile: Path = parent.resolve(path.fileName.toString() + ".sqlite")
        Files.copy(path, dbFile, StandardCopyOption.REPLACE_EXISTING)
        return SQLiteDb.openDatabase(dbFile)
    }

    /**
     *  Converts the columns and rows to a Dataframe.
     *  @return the data frame or an empty data frame if conversion does not work
     */
    abstract fun asDataFrame(): AnyFrame

    companion object {
        /**
         * Creates a double column
         *
         * @param name the name of the column, must not be null
         * @return the created column
         */
        fun numericColumn(name: String): ColumnType {
            return ColumnType(name, DataType.NUMERIC)
        }

        /**
         * Creates a text column
         *
         * @param name the name of the column, must not be null
         * @return the created column
         */
        fun textColumn(name: String): ColumnType {
            return ColumnType(name, DataType.TEXT)
        }

        /**
         * Creates a  column with the given data type
         *
         * @param name     the name of the column, must not be null
         * @param dataType the type of the column, must not be null
         * @return the created column
         */
        fun column(name: String, dataType: DataType): ColumnType {
            return ColumnType(name, dataType)
        }

        /**
         * Makes a list of strings containing, prefix1, prefix2,..., prefixN, where N = number
         *
         * @param prefix the prefix for each name, defaults to C1, C2, ..., CN, where N = number
         * @param number the number of names, must be 1 or more
         * @return the list of names
         */
        fun columnNames(number: Int, prefix: String = "C"): List<String> {
            require(number > 0) { "The number of names must be > 0" }
            val names = mutableListOf<String>()
            for (i in 0 until number) {
                names.add(prefix + (i + 1))
            }
            return names
        }

        /**
         * Creates names.size() columns with the provided names and data type
         *
         * @param names    the names for the columns, must not be null or empty
         * @param dataType the data type to associated with each column
         * @return a map with the column names all assigned the same data type
         */
        fun columns(names: List<String>, dataType: DataType): Map<String, DataType> {
            require(names.isNotEmpty()) { "The number of names must be > 0" }
            val nameSet: Set<String> = HashSet(names)
            require(nameSet.size == names.size) { "The names in the list are not unique!" }
            val map = mutableMapOf<String, DataType>()
            for (name in names) {
                map[name] = dataType
            }
            return map
        }

        /**
         * Creates n = numColumns of columns all with the same data type, with names C1, C2, ..., Cn
         *
         * @param numColumns the number of columns to make, must be greater than 0
         * @param dataType   the type of all  the columns
         * @return a map with the column names all assigned the same data type
         */
        fun columns(numColumns: Int, dataType: DataType): Map<String, DataType> {
            require(numColumns > 0) { "The number of columns must be > 0" }
            return columns(columnNames(numColumns), dataType)
        }

        /**
         *  Converts the list of KType instances to a
         *  list of DataType instances.
         *  If the type is {Double, Long, Integer, Boolean, Float, Short, Byte}
         *  then it is NUMERIC otherwise it is considered TEXT.
         *
         */
        fun toDataTypes(types: List<KType>): List<DataType> {
            val list = mutableListOf<DataType>()
            for (type in types) {
                list.add(toDataType(type))
            }
            return list
        }

        /**
         *  If the [kType] is {Double, Long, Integer, Boolean, Float, Short, Byte}
         *  then it is NUMERIC otherwise it is considered TEXT.
         */
        fun toDataType(kType: KType): DataType {
            if (kType.classifier == Double::class) {
                return DataType.NUMERIC
            } else if (kType.classifier == Int::class) {
                return DataType.NUMERIC
            } else if (kType.classifier == Long::class) {
                return DataType.NUMERIC
            } else if (kType.classifier == Boolean::class) {
                return DataType.NUMERIC
            } else if (kType.classifier == Float::class) {
                return DataType.NUMERIC
            } else if (kType.classifier == Short::class) {
                return DataType.NUMERIC
            } else if (kType.classifier == Byte::class) {
                return DataType.NUMERIC
            } else {
                return DataType.TEXT
            }
        }

        /**
         * Test if the object is any of {Double, Long, Integer, Boolean, Float, Short, Byte}
         *
         * @param element the element to test
         * @return true if it is numeric
         */
        fun isNumeric(element: Any?): Boolean {
            if (element == null) {
                return false
            } else if (element is Double) {
                return true
            } else if (element is Int) {
                return true
            } else if (element is Long) {
                return true
            } else if (element is Boolean) {
                return true
            } else if (element is Float) {
                return true
            } else if (element is Short) {
                return true
            } else if (element is Byte) {
                return true
            }
            return false
        }

        /**
         *
         * @param element the element to convert
         * @return the element as a double, the element must be numeric
         */
        fun asDouble(element: Any?): Double {
            require(isNumeric(element)) { "The element was not of numeric type" }
            return if (element is Double) {
                element.toDouble()
            } else if (element is Int) {
                element.toDouble()
            } else if (element is Long) {
                element.toDouble()
            } else if (element is Boolean) {
                if (element) 1.0 else 0.0
            } else if (element is Float) {
                element.toDouble()
            } else if (element is Short) {
                element.toDouble()
            } else if (element is Byte) {
                element.toDouble()
            } else {
                throw IllegalArgumentException("The element was not of numeric type")
            }
        }


        /** Reads in a CSV file and converts it to a tabular output file. The separator must be a comma.
         *
         *  Each row is individually processed. The number of columns for each row must be equal to the number
         *  of (name, data type) pairs supplied to define the columns.
         *
         *  @param pathToCSVFile the path to the CSV files for reading in
         *  @param columnTypes the specification for each column of its name and data type (NUMERIC, TEXT)
         *  @param outFileName the name of the output file. It will be created in the parent directory
         *  specified by [pathToCSVFile]
         *  @param hasHeader indicates if the file has a header row. It will be skipped during conversion.
         *  The default is true to skip the header. The column names become the names of the columns in the tabular output file.
         *  @return the created tabular output file
         */
        fun createFromCSVFile(
            pathToCSVFile: Path,
            columnTypes: Map<String, DataType>,
            outFileName: String,
            hasHeader: Boolean = true
        ): TabularOutputFile {
            return createFromCSVFile(pathToCSVFile, columnTypes, pathToCSVFile.parent.resolve(outFileName), hasHeader)
        }

        /** Reads in a CSV file and converts it to a tabular output file. The separator must be a comma.
         *
         *  Each row is individually processed. The number of columns for each row must be equal to the number
         *  of (name, data type) pairs supplied to define the columns.
         *
         *  @param pathToCSVFile the path to the CSV files for reading in
         *  @param columnTypes the specification for each column of its name and data type (NUMERIC, TEXT)
         *  @param pathToOutputFile the path to the TabularOutputFile that is created. The default
         *  is a file name the same as specified by [pathToCSVFile] with _TabularFile appended and in
         *  the same parent directory of [pathToCSVFile]
         *  @param hasHeader indicates if the file has a header row. It will be skipped during conversion.
         *  The default is true to skip the header. The column names become the names of the columns in the tabular output file.
         *  @return the created tabular output file
         */
        fun createFromCSVFile(
            pathToCSVFile: Path,
            columnTypes: Map<String, DataType>,
            pathToOutputFile: Path = pathToCSVFile.parent.resolve(
                "${KSLFileUtil.removeLastFileExtension(pathToCSVFile.fileName.toString())}_TabularFile"
            ),
            hasHeader: Boolean = true
        ): TabularOutputFile {
            val itr = CSVRowIterator(pathToCSVFile)
            var row = 0
            if (hasHeader) {
                if (itr.hasNext()) {
                    val data: CSVRecord = itr.next()
                    row++
                    require(data.size() == columnTypes.size) { "Row ($row) had (${data.size()}) columns: expected (${columnTypes.size}) columns." }
                }
            }
            val tof = TabularOutputFile(columnTypes, pathToOutputFile)
            val rs = tof.row()
            while (itr.hasNext()) {
                val data = itr.next()
                row++
                require(data.size() == columnTypes.size) { "Row ($row) had (${data.size()}) columns: expected (${columnTypes.size} columns." }
                for (i in 0..<data.size()) {
                    if (tof.dataType(i) == DataType.NUMERIC) {
                        val d = data[i].toDouble()
                        rs.setElement(i, TabularFile.asDouble(d))
                    } else {
                        rs.setElement(i, data[i])
                    }
                }
                tof.writeRow(rs)
            }
            tof.flushRows()
            itr.close()
            return tof
        }
    }
}