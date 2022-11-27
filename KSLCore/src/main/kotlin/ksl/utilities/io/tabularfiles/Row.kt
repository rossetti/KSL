package ksl.utilities.io.tabularfiles

import java.util.*
import kotlin.math.min

interface RowIfc {
    /**
     * @return the number of bytes stored in the row
     */
    val bytes: Int

    /**
     * @return the total number of numeric columns
     */
    val numNumericColumns: Int

    /**
     * @return the total number of text columns
     */
    val numTextColumns: Int

    /**
     * @return the map of columns associated with this row
     */
    val columnTypes: Map<String, DataType>

    /**
     * @return an ordered list of the column names for the row
     */
    val columnNames: List<String>

    /**
     * @return an ordered list of the column data types
     */
    val dataTypes: List<DataType>

    /**
     * @param colNum 0 based indexing
     * @return the data type of the column at the index
     */
    fun getDataType(colNum: Int): DataType

    /**
     * @return the number of columns of tabular data
     */
    val numberColumns: Int

    /**
     * @return true if all cells are NUMERIC
     */
    val isAllNumeric: Boolean

    /**
     * @return true if all cells are TEXT
     */
    val isAllText: Boolean

    /**
     * @param col the index of the column, 0 based
     * @return the data type of the column associated with this cell
     */
    fun getType(col: Int): DataType

    /**
     * @param col the index of the column, 0 based
     * @return the name of the column associated with this cell
     */
    fun getColumnName(col: Int): String

    /**
     * @param name the name to look up
     * @return the index or -1 if not found
     */
    fun getColumn(name: String): Int

    /**
     * @param i the index into the row (0 based)
     * @return true if the cell at location i is NUMERIC
     */
    fun isNumeric(i: Int): Boolean

    /**
     * @param i i the index into the row (0 based)
     * @return true if the cell at location i is TEXT
     */
    fun isText(i: Int): Boolean
}

/**
 * An abstraction for getting information and setting data for a row within a tabular file.
 * The access to the columns is 0-based.  Why? Because most if not all of kotlin's
 * data containers (arrays, lists, etc.) are 0-based.  The first column has index 0,
 * 2nd column has index 1, etc.
 */
interface RowSetterIfc : RowIfc {
    /**
     * @param colNum the index into the row (0 based)
     * @param value  the value to set, will throw an exception of the cell is not NUMERIC
     */
    fun setNumeric(colNum: Int, value: Double)

    /**
     * @param colNum the index into the row (0 based)
     * @param value  the value to set, will throw an exception of the cell is not NUMERIC
     */
    fun setNumeric(colNum: Int, value: Boolean) {
        if (value) {
            setNumeric(colNum, 1.0)
        } else {
            setNumeric(colNum, 0.0)
        }
    }

    /**
     * @param colNum the index into the row (0 based)
     * @param value  the value to set, will throw an exception of the cell is not TEXT
     */
    fun setText(colNum: Int, value: String?)

    /**
     * Sets the numeric columns according to the data in the array.
     * If the array has more elements than the number of columns, then the columns
     * are filled with first elements of the array up to the number of columns.
     * If the array has less elements than the number of columns, then only
     * the first data.length columns are set.
     *
     * @param data an array of data for the numeric rows. The array must not be null.
     * @return the number of columns that were set
     */
    fun setNumeric(data: DoubleArray): Int

    /**
     * Sets the text columns according to the data in the array.
     * If the array has more elements than the number of columns, then the columns
     * are filled with first elements of the array up to the number of columns.
     * If the array has less elements than the number of columns, then only
     * the first data.length columns are set.
     *
     * @param data an array of data for the text rows. The array must not be null.
     * @return the number of columns that were set
     */
    fun setText(data: Array<String?>): Int

    /**
     * Sets the text columns according to the data in the list.
     * If the list has more elements than the number of columns, then the columns
     * are filled with first elements of the list up to the number of columns.
     * If the list has less elements than the number of columns, then only
     * the first data.size() columns are set.
     *
     * @param data a list of data for the text rows. The list must not be null.
     * @return the number of columns that were set
     */
    fun setText(data: List<String?>): Int

    /**
     * @param columnName the name of the column to set
     * @param value      the value to set
     */
    fun setNumeric(columnName: String, value: Double)

    /**
     * @param columnName the name of the column to set
     * @param value      the value to set
     */
    fun setText(columnName: String, value: String?)

    /**
     * The row is filled with the elements. Numeric elements are saved in
     * numeric columns in the order presented. Non-numeric elements are all converted
     * to strings and stored in the order presented. Numeric elements are of types
     * {Double, Long, Integer, Boolean, Float, Short, Byte}. Any other type is
     * converted to text via toString().
     *
     *
     * The order and types of the elements must match the order and types associated
     * with the columns.
     *
     * @param elements the elements to add to the row. The number of elements must
     * be equal to the number of columns
     */
    fun setElements(elements: Array<Any?>)

    /**
     * The row is filled with the elements. Numeric elements are saved in
     * numeric columns in the order presented. Non-numeric elements are all converted
     * to strings and stored in the order presented. Numeric elements are of types
     * {Double, Long, Integer, Boolean, Float, Short, Byte}. Any other type is
     * converted to text via toString().
     *
     *
     * The order and types of the elements must match the order and types associated
     * with the columns.
     *
     * @param elements the elements to add to the row. The number of elements must
     * be equal to the number of columns
     */
    fun setElements(elements: List<Any?>)

    /**
     * @param colNum  the column number to set
     * @param element the element to set
     */
    fun setElement(colNum: Int, element: Any?)
}

/**
 * An abstraction for getting information and data from a row within a tabular file.
 * The access to the columns is 0-based.  Why? Because most if not all of java's
 * data containers (arrays, lists, etc.) are 0-based.  The the first column has index 0,
 * 2nd column has index 1, etc.
 */
interface RowGetterIfc : RowIfc {
    /**
     * @param colNum the index into the row (0 based)
     * @return the value as a double, will throw an exception if the cell is not NUMERIC
     */
    fun getNumeric(colNum: Int): Double

    /**
     * @param columnName the name of the column
     * @return the value of the column
     */
    fun getNumeric(columnName: String): Double

    /**
     *
     * @return the numeric columns as an array
     */
    val numeric: DoubleArray

    /**
     * @param colNum the index into the row (0 based)
     * @return the value as a double, will throw an exception if the cell is not TEXT
     */
    fun getText(colNum: Int): String?

    /**
     *
     * @return the text columns in order of appearance as an array
     */
    val text: Array<String?>

    /**
     * @param columnName the name of the column
     * @return the value of the column
     */
    fun getText(columnName: String): String?

    /**
     * @return the elements of the row as Objects
     */
    val elements: List<Any?>

    /**
     * @param colNum the column number
     * @return an object representation of the element at the column
     */
    fun getElement(colNum: Int): Any?

    /**
     *
     * @return the row as an array of strings
     */
    fun asStringArray(): Array<String?>

    /**
     *
     * @return the row as comma separated values. The row does not contain a line separator.
     */
    fun toCSV(): String
}

/**
 * An abstraction for a row within a tabular file.
 * The access to the columns is 0-based.  Why? Because most if not all of java's
 * data containers (arrays, lists, etc.) are 0-based.  The first column has index 0,
 * 2nd column has index 1, etc.
 */
class Row(tabularFile: TabularFile) : RowGetterIfc, RowSetterIfc, RowIfc {
    private val myTabularFile: TabularFile
    private val textData: Array<String?>
    private val numericData: DoubleArray

    /**
     * @return the number of this row if it was returned from a tabular file
     */
    var rowNum: Long = 0

    init {
        myTabularFile = tabularFile
        textData = arrayOfNulls(tabularFile.getNumTextColumns())
        numericData = DoubleArray(tabularFile.getNumNumericColumns())
        numericData.fill(Double.NaN)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        val formatter = Formatter(sb)
        val n = numberColumns
        formatter.format("|%-20d|", rowNum)
        for (i in 0 until n) {
            if (getDataType(i) === DataType.NUMERIC) {
                formatter.format("%-20f|", getNumeric(i))
            } else {
                // must be string
                formatter.format("%-20s|", getText(i))
            }
        }
        return sb.toString()
    }

    override val bytes: Int
        get() {
            var n = numericData.size * 8
            for (s in textData) {
                if (s != null) {
                    n = n + s.toByteArray().size
                }
            }
            return n
        }
    override val numNumericColumns: Int
        get() = myTabularFile.getNumNumericColumns()
    override val numTextColumns: Int
        get() = myTabularFile.getNumTextColumns()
    override val columnTypes: Map<String, DataType>
        get() = myTabularFile.getColumnTypes()
    override val columnNames: List<String>
        get() = myTabularFile.getColumnNames()
    override val dataTypes: List<DataType>
        get() = myTabularFile.getDataTypes()

    override fun getDataType(colNum: Int): DataType {
        return myTabularFile.getDataType(colNum)
    }

    override val numberColumns: Int
        get() = myTabularFile.getNumberColumns()
    override val isAllNumeric: Boolean
        get() = myTabularFile.isAllNumeric()
    override val isAllText: Boolean
        get() = myTabularFile.isAllText()

    override fun getType(col: Int): DataType {
        return myTabularFile.getDataType(col)
    }

    override fun getColumnName(col: Int): String {
        return myTabularFile.getColumnName(col)
    }

    override fun getColumn(name: String): Int {
        return myTabularFile.getColumn(name)
    }

    override fun isNumeric(i: Int): Boolean {
        return myTabularFile.isNumeric(i)
    }

    override fun isText(i: Int): Boolean {
        return myTabularFile.isText(i)
    }

    override fun setNumeric(colNum: Int, value: Double) {
        check(!isText(colNum)) { "The row does not contain a double value at this index" }
        // colNum is the actual index across all columns
        // must store the double at it's appropriate index in the storage array
        numericData[myTabularFile.getNumericStorageIndex(colNum)] = value
    }

    override fun setText(colNum: Int, value: String?) {
        check(!isNumeric(colNum)) { "The row does not contain a text value at this index" }
        // colNum is the actual index across all columns
        // must store the string at it's appropriate index in the storage array
        textData[myTabularFile.getTextStorageIndex(colNum)] = value
    }

    override fun getNumeric(colNum: Int): Double {
        check(!isText(colNum)) { "The row does not contain a double value at this index" }
        return numericData[myTabularFile.getNumericStorageIndex(colNum)]
    }

    override val numeric: DoubleArray
        get() = numericData.copyOf()

    override fun getText(colNum: Int): String? {
        check(!isNumeric(colNum)) { "The row does not contain a text value at this index" }
        return textData[myTabularFile.getTextStorageIndex(colNum)]
    }

    override val text: Array<String?>
        get() = textData.copyOf()

    override fun setNumeric(data: DoubleArray): Int {
        val n = min(data.size, numericData.size)
        data.copyInto(numericData, endIndex = n)
        return n
    }

    override fun setText(data: Array<String?>): Int {
        val n = min(data.size, textData.size)
        data.copyInto(textData, endIndex = n)
        return n
    }

    override fun setText(data: List<String?>): Int {
        val stringArray = data.toTypedArray()
        return setText(stringArray)
    }

    override fun getNumeric(columnName: String): Double {
        return getNumeric(getColumn(columnName))
    }

    override fun getText(columnName: String): String? {
        return getText(getColumn(columnName))
    }

    override fun setNumeric(columnName: String, value: Double) {
        setNumeric(getColumn(columnName), value)
    }

    override fun setText(columnName: String, value: String?) {
        setText(getColumn(columnName), value)
    }

    // look up the index of the column// look up the index of the column

    // need to copy elements from storage arrays to correct object location
    // go from storage arrays to elements because type is known
    override val elements: List<Any?>
        get() {
            val elements = mutableListOf<Any?>()
            // need to copy elements from storage arrays to correct object location
            // go from storage arrays to elements because type is known
            for (i in numericData.indices) {
                // look up the index of the column
                val col: Int = myTabularFile.getColumnIndexForNumeric(i)
                elements[col] = numericData[i]
            }
            for (i in textData.indices) {
                // look up the index of the column
                val col: Int = myTabularFile.getColumnIndexForText(i)
                elements[col] = textData[i]
            }
            return elements
        }

    override fun toCSV(): String {
        val sb = StringJoiner(",")
        val elements = elements
        for (element in elements) {
            sb.add(element.toString())
        }
        return sb.toString()
    }

    override fun asStringArray(): Array<String?> {
        val strings = arrayOfNulls<String>(numberColumns)
        val elements = elements
        for (i in strings.indices) {
            strings[i] = elements[i].toString()
        }
        return strings
    }

    override fun getElement(colNum: Int): Any? {
        return if (getDataType(colNum) == DataType.NUMERIC) {
            getNumeric(colNum)
        } else {
            getText(colNum)
        }
    }

    override fun setElements(elements: Array<Any?>) {
        require(elements.size == numberColumns) { "The number of elements does not equal the number of columns" }
        require(myTabularFile.checkTypes(elements)) { "The elements do not match the types for each column" }
        // the type of the elements are unknown and must be tested
        // must convert numeric elements to doubles, non-numeric to strings
        for (i in elements.indices) {
            setElement(i, elements[i])
        }
    }

    override fun setElements(elements: List<Any?>) {
        require(elements.size == numberColumns) { "The number of elements does not equal the number of columns" }
        require(myTabularFile.checkTypes(elements.toTypedArray())) { "The elements do not match the types for each column" }
        // the type of the elements are unknown and must be tested
        // must convert numeric elements to doubles, non-numeric to strings
        for (i in elements.indices) {
            setElement(i, elements[i])
        }
    }

    override fun setElement(colNum: Int, element: Any?) {
        if (TabularFile.isNumeric(element)) {
            setNumeric(colNum, TabularFile.asDouble(element))
        } else {
            // not NUMERIC
            if (element == null) {
                setText(colNum, null)
            } else {
                setText(colNum, element.toString())
            }
        }
    }
}