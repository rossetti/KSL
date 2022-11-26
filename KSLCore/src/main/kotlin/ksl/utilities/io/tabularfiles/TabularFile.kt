package ksl.utilities.io.tabularfiles

import ksl.utilities.maps.HashBiMap
import ksl.utilities.maps.MutableBiMap
import java.nio.file.Path

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
abstract class TabularFile(columnTypes: Map<String, DataType>, val path: Path) {
//TODO kotlin names and property conventions
//TODO use a builder pattern to define and add the columns

    protected val myColumnTypes: Map<String, DataType> = columnTypes.toMap()
    protected var myNameAndIndex: MutableBiMap<String, Int> = HashBiMap()
    protected var myNumericIndices: MutableBiMap<Int, Int> = HashBiMap()
    protected var myTextIndices: MutableBiMap<Int, Int> = HashBiMap()
    protected val myColumnNames: MutableList<String> = mutableListOf()
    protected var myDataTypes: MutableList<DataType> = mutableListOf()

    init {
        require(columnTypes.isNotEmpty()) { "The number of columns must be > 0" }
        for (name in myColumnTypes.keys) {
            myColumnNames.add(name)
        }
        var i = 0
        var cntNumeric = 0
        var cntText = 0
        for (name in myColumnNames) {
            myNameAndIndex[name] = i
            val type = myColumnTypes[name]!!
            if (type == DataType.NUMERIC) {
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
        sb.appendLine(myNameAndIndex)
        sb.appendLine("Numeric Indices:")
        sb.appendLine(myNumericIndices)
        sb.appendLine("Text Indices:")
        sb.appendLine(myTextIndices)
        return sb.toString()
    }

    /** Returns the storage index of the numeric column at column index
     *
     * @param colNum the column index
     * @return the assigned storage-index for the numeric value
     */
    fun getNumericStorageIndex(colNum: Int): Int {
        return myNumericIndices[colNum]!!
    }

    /** Returns the storage index of the text column at column index
     *
     * @param colNum the column index
     * @return the assigned storage-index for the text value
     */
    fun getTextStorageIndex(colNum: Int): Int {
        return myTextIndices[colNum]!!
    }

    /** Returns the column index associated with the storage index
     *
     * @param storageIndex the storage index to find
     * @return the column index
     */
    fun getColumnIndexForNumeric(storageIndex: Int): Int {
        return myNumericIndices.inverse[storageIndex]!!
    }

    /** Returns the column index associated with the storage index
     *
     * @param storageIndex the storage index to find
     * @return the column index
     */
    fun getColumnIndexForText(storageIndex: Int): Int {
        return myTextIndices.inverse[storageIndex]!!
    }

    /**
     *
     * @param colNum 0 based indexing
     * @return the name of the column at the index
     */
    fun getColumnName(colNum: Int): String {
        return myNameAndIndex.inverse[colNum]!!
    }

    /**
     *
     * @param colNum 0 based indexing
     * @return the data type of the column at the index
     */
    fun getDataType(colNum: Int): DataType {
        return getDataTypes().get(colNum)
    }

    /**
     * @return the number of columns of tabular data
     */
    fun getNumberColumns(): Int {
        return getColumnTypes().size
    }

    /**
     *
     * @param name the name of the column
     * @return the index or -1 if not found
     */
    fun getColumn(name: String): Int {
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
    fun getNumNumericColumns(): Int {
        return myNumericIndices.size
    }

    /**
     *
     * @return the total number of text columns
     */
    fun getNumTextColumns(): Int {
        return myTextIndices.size
    }

    /**
     *
     * @return the map of columns associated with this tabular file
     */
    fun getColumnTypes(): Map<String, DataType> {
        return myColumnTypes.toMap()
    }

    /**
     * @return an ordered list of the column names
     */
    fun getColumnNames(): List<String> {
        return myColumnNames
    }

    /**
     *
     * @return  A list of all the numeric column names
     */
    fun getNumericColumnNames(): List<String> {
        val theNames: MutableList<String> = ArrayList()
        val allNames = getColumnNames()
        for (name in allNames) {
            if (isNumeric(getColumn(name))) {
                theNames.add(name)
            }
        }
        return theNames
    }

    /**
     *
     * @return  A list of all the text column names
     */
    fun getTextColumnNames(): List<String> {
        val theNames: MutableList<String> = ArrayList()
        val allNames = getColumnNames()
        for (name in allNames) {
            if (isText(getColumn(name))) {
                theNames.add(name)
            }
        }
        return theNames
    }

    /**
     * @return an ordered list of the column data types
     */
    fun getDataTypes(): List<DataType> {
        return myDataTypes
    }

    /**
     *
     * @return true if all columns have type NUMERIC
     */
    fun isAllNumeric(): Boolean {
        return myDataTypes.size == getNumNumericColumns()
    }

    /**
     *
     * @return true if all columns have type TEXT
     */
    fun isAllText(): Boolean {
        return myDataTypes.size == getNumTextColumns()
    }

    /**
     *
     * @param elements the elements to check
     * @return true if all elements match the correct types
     */
    fun checkTypes(elements: Array<Any?>): Boolean {
        if (elements.size != getNumberColumns()) {
            return false
        }
        val dataTypes = getDataTypes()
        for ((i, type) in dataTypes.withIndex()) {
            if (type == DataType.NUMERIC) {
                if (elements[i] == null){
                    return false
                } else {
                    if (!isNumeric(elements[i]!!)) {
                        return false
                    }
                }
            } else {
                // must be text
                if (elements[i] != null){
                    if (isNumeric(elements[i]!!)) {
                        return false
                    }
                }
            }
        }
        return true
    }

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
         * Test if the object is any of {Double, Long, Integer, Boolean, Float, Short, Byte}
         *
         * @param element the element to test
         * @return true if it is numeric
         */
        fun isNumeric(element: Any?): Boolean {
            if (element == null){
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
    }
}