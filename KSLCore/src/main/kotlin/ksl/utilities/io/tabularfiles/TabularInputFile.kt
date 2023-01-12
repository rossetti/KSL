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

import ksl.utilities.io.dbutil.ColumnMetaData
import ksl.utilities.io.dbutil.DatabaseFactory
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.ResultSetRowIterator
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.emptyDataFrame
import java.io.IOException
import java.io.PrintWriter
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.rowset.CachedRowSet
import kotlin.math.min

/**
 * An abstraction for reading rows of tabular data. Columns of the tabular
 * data can be of numeric or text.  Using this subclass of TabularFile
 * users can read rows of data.  The user is responsible for iterating rows with
 * data of the appropriate type for the column and reading the row into their program.
 *
 * Use the static methods of TabularFile to create and define the columns of the file.
 * Use the methods of this class to read rows.
 *
 * @see ksl.utilities.io.tabularfiles.TabularFile
 * @see ksl.utilities.io.tabularfiles.TestTabularWork  For example code
 */
class TabularInputFile private constructor(columnTypes: Map<String, DataType>, path: Path) :
    TabularFile(columnTypes, path) {

    constructor(path: Path) : this(columnTypes(path), path)

    private val myDb: DatabaseIfc
    private val dataTableName: String

    var rowBufferSize = DEFAULT_ROW_BUFFER_SIZE // maximum number of records held inside iterators
        set(value) {
            if (value <= 0) {
                field = DEFAULT_ROW_BUFFER_SIZE
            } else {
                field = value
            }
        }

    val totalNumberRows: Long
    private val myConnection: Connection
    private val myRowSelector: PreparedStatement

    init {
        // determine the name of the data table
        val fileName: String = path.fileName.toString()
        val fixedFileName = fileName.replace("[^a-zA-Z]".toRegex(), "")
        dataTableName = fixedFileName + "_Data"
        // open up the database file
        myDb = DatabaseFactory.getSQLiteDatabase(path, true)
        totalNumberRows = myDb.numRows(dataTableName)
        myConnection = myDb.getConnection()
        val rowsSQL = "select * from $dataTableName where rowid between ? and ?"
        myRowSelector = myConnection.prepareStatement(rowsSQL)
    }

    /**
     * Remember to close the file after all processing
     */
    fun close() {
        myConnection.close()
        myRowSelector.close()
    }

    /**
     * @param startingRow the starting row for the iteration
     * @return an iterator for moving through the rows
     */
    fun iterator(startingRow: Long = 1): RowIterator {
        return RowIterator(startingRow)
    }

    /**
     * Returns the rows between minRowNum and maxRowNum, inclusive. Since there may be
     * memory implications when using this method, please use it wisely. In fact,
     * use the provided iterator instead.
     *
     * @param minRowNum the minimum row number, must be less than maxRowNum, and 1 or bigger
     * @param maxRowNum the maximum row number, must be greater than minRowNum, and 2 or bigger
     * @return the list of rows, the list may be empty, if there are no rows in the row number range
     */
    fun fetchRows(minRowNum: Long, maxRowNum: Long): List<RowGetterIfc> {
        return convertResultsToRows(selectRows(minRowNum, maxRowNum), minRowNum)
    }

    /**
     * Returns the row.
     * If the provided row number is larger than the number of rows in the file
     * then an exception is thrown. Use fetchRow() if you do not check the number of rows.
     *
     * @param rowNum the row number, must be 1 or more and less than totalNumberRows
     * @return the row
     */
    fun fetchRow(rowNum: Long): RowGetterIfc {
        require(rowNum > 0) { "The row number must be > 0" }
        require(rowNum <= totalNumberRows) { "The row number must be <= $totalNumberRows" }
        val rows = fetchRows(rowNum, rowNum)
        return if (rows.isEmpty()) {
            Row(this)
        } else {
            rows[0]
        }
    }

    private fun convertResultsToRows(selectedRows: ResultSet, startingRowNum: Long): List<RowGetterIfc> {
        val rows = mutableListOf<RowGetterIfc>()
        val iterator = ResultSetRowIterator(selectedRows)
        var i = startingRowNum
        while (iterator.hasNext()) {
            val next: List<Any?> = iterator.next()
            val row = Row(this)
            row.rowNum = i
            row.setElements(next)
            rows.add(row)
            i++
        }
        return rows
    }

    inner class RowIterator(startingRowNum: Long = 1) : Iterator<RowGetterIfc> {
        init {
            require(startingRowNum > 0) { "The starting row number must be > 0" }
        }

        var currentRowNum = startingRowNum - 1
            private set
        var remainingNumRows = totalNumberRows - currentRowNum
            private set
        private var myBufferedRows: List<RowGetterIfc>
        private var myRowIterator: Iterator<RowGetterIfc>

        init {
            // fill the initial buffer
            val n = min(rowBufferSize.toLong(), remainingNumRows)
            myBufferedRows = fetchRows(startingRowNum, startingRowNum + n)
            myRowIterator = myBufferedRows.listIterator()
        }

        override fun hasNext(): Boolean {
            return remainingNumRows > 0
        }

        override fun next(): RowGetterIfc {
            return if (myRowIterator.hasNext()) {
                // some rows left in the buffer
                // decrement number of rows remaining and return the next row
                // after next the cursor is ready to return next row
                // so current is the one just returned
                currentRowNum = currentRowNum + 1
                remainingNumRows = remainingNumRows - 1
                myRowIterator.next()
            } else {
                // buffer has no more rows, need to check if more rows remain
                if (hasNext()) {
                    // refill the buffer
                    val n = min(rowBufferSize.toLong(), remainingNumRows)
                    val startingRow = currentRowNum + 1
                    myBufferedRows = fetchRows(startingRow, startingRow + n)
                    myRowIterator = myBufferedRows.listIterator()
                    // buffer must have rows
                    // move to first row in new buffer and return it
                    currentRowNum = currentRowNum + 1
                    remainingNumRows = remainingNumRows - 1
                    myRowIterator.next()
                } else {
                    // empty row
                    Row(this@TabularInputFile)
                }
            }
        }

    }

    // reuse the prepared statement many times
    private fun selectRows(minRowNum: Long, maxRowNum: Long): ResultSet {
        require(minRowNum > 0) { "The minimum row number must be > 0" }
        require(maxRowNum > 0) { "The maximum row number must be > 0" }
        require(minRowNum <= maxRowNum) { "The minimum row number must be < the maximum row number." }
        myRowSelector.setLong(1, minRowNum)
        myRowSelector.setLong(2, maxRowNum)
        return myRowSelector.executeQuery()!!
    }

    /**
     * @param maxRows       the total number of rows to extract starting at row 1
     * @param removeMissing if true, then missing (NaN values) are removed
     * @return a map of the data keyed by column name
     */
    fun numericColumns(maxRows: Int = 0, removeMissing: Boolean = false): Map<String, DoubleArray> {
        val map = mutableMapOf<String, DoubleArray>()
        val names = numericColumnNames
        for (name in names) {
            val values = fetchNumericColumn(name, maxRows, removeMissing)
            map[name] = values
        }
        return map
    }

    /**
     * @param maxRows       the total number of rows to extract starting at row 1
     * @param removeMissing if true, then missing (NaN values) are removed
     * @return a map of the data keyed by column name
     */
    fun textColumns(maxRows: Int = 0, removeMissing: Boolean = false): Map<String, Array<String?>> {
        val map = mutableMapOf<String, Array<String?>>()
        val names = textColumnNames
        for (name in names) {
            val values: Array<String?> = fetchTextColumn(name, maxRows, removeMissing)
            map[name] = values
        }
        return map
    }

    /**
     * Obviously, there are memory issues if there are a lot of rows.
     *
     * @param columnName        the column name to retrieve, must be a numeric column
     * @param maxRows       the total number of rows to extract starting at row 1
     * @param removeMissing if true, then missing (NaN values) are removed
     * @return the array of values
     */
    fun fetchNumericColumn(columnName: String, maxRows: Int = 0, removeMissing: Boolean = false): DoubleArray {
        val columnNum = columnIndex(columnName)
        if (columnNum == -1) {
            return emptyArray<Double>().toDoubleArray()
        }
        return fetchNumericColumn(columnNum, maxRows, removeMissing)
    }

    /**
     * Obviously, there are memory issues if there are a lot of rows.
     *
     * @param columnNum        the column number to retrieve, must be between [0,getNumberColumns())
     * @param maxRows       the total number of rows to extract starting at row 1
     * @param removeMissing if true, then missing (NaN values) are removed
     * @return the array of values
     */
    fun fetchNumericColumn(columnNum: Int, maxRows: Int = 0, removeMissing: Boolean = false): DoubleArray {
        require((columnNum >= 0) && (columnNum < numberColumns)) { "The supplied column number was out of range" }
        if (!isNumeric(columnNum)) {
            return emptyArray<Double>().toDoubleArray()
        }
        require(isNumeric(columnNum)) { "The indicated column is not numeric." }
        // build the query
        val colName = myColumnNames[columnNum]
        val sql = if (maxRows <= 0) {
            "select $colName from $dataTableName"
        } else {
            "select $colName from $dataTableName limit $maxRows"
        }
        val rowSet: CachedRowSet? = myDb.fetchCachedRowSet(sql)
        val list = mutableListOf<Double>()
        if (rowSet != null) {
            val collection = rowSet.toCollection(1)
            for (item in collection) {
                if (item is Double) {
                    if (removeMissing) {
                        if (item.isNaN()) {
                            continue
                        } else {
                            list.add(item)
                        }
                    } else {
                        list.add(item)
                    }
                }
            }
        }
        return list.toDoubleArray()
    }

    /**
     * Obviously, there are memory issues if there are a lot of rows.
     *
     * @param columnName        the column name to retrieve, must be a text column
     * @param maxRows       the total number of rows to extract starting at row 1
     * @param removeMissing if true, then missing (null values) are removed
     * @return the array of values
     */
    fun fetchTextColumn(columnName: String, maxRows: Int = 0, removeMissing: Boolean = false): Array<String?> {
        val columnNum = columnIndex(columnName)
        if (columnNum == -1) {
            return emptyArray()
        }
        return fetchTextColumn(columnNum, maxRows, removeMissing)
    }

    /**
     * Obviously, there are memory issues if there are a lot of rows.
     *
     * @param columnNum        the column number to retrieve, must be between [0,getNumberColumns())
     * @param maxRows       the total number of rows to extract starting at row 1
     * @param removeMissing if true, then missing (null values) are removed
     * @return the array of values
     */
    fun fetchTextColumn(columnNum: Int, maxRows: Int = 0, removeMissing: Boolean = false): Array<String?> {
        require((columnNum >= 0) && (columnNum < numberColumns)) { "The supplied column number was out of range" }
        if (!isText(columnNum)) {
            return emptyArray()
        }
        // build the query
        val colName = myColumnNames[columnNum]
        val sql = if (maxRows <= 0) {
            "select $colName from $dataTableName"
        } else {
            "select $colName from $dataTableName limit $maxRows"
        }
        val rowSet: CachedRowSet? = myDb.fetchCachedRowSet(sql)
        val list = mutableListOf<String?>()
        if (rowSet != null) {
            val collection = rowSet.toCollection(1)
            for (item in collection) {
                if (item is String?) {
                    if (removeMissing) {
                        if (item == null) {
                            continue
                        } else {
                            list.add(item)
                        }
                    } else {
                        list.add(item)
                    }
                }
            }
        }
        return list.toTypedArray()
    }

    /**
     *  Converts the columns and rows to a Dataframe.
     *  @return the data frame or an empty data frame if conversion does not work
     */
    fun asDataFrame(): AnyFrame {
        val resultSet = myDb.selectAllIntoOpenResultSet(dataTableName)
        return if (resultSet!= null){
            DatabaseIfc.toDataFrame(resultSet)
        }else{
            emptyDataFrame<Nothing>()
        }
    }

    /**
     * This is not optimized for large files and may have memory and performance issues.
     *
     * @param minRowNum the row to start the printing
     * @param maxRowNum the row to end the printing
     */
    fun printAsText(minRowNum: Long = 1, maxRowNum: Long = minRowNum + 10){
        val resultSet = selectRows(minRowNum, maxRowNum)
        DatabaseIfc.writeAsText(DatabaseIfc.createCachedRowSet(resultSet), PrintWriter(System.out, true))
    }

    /**
     * This is not optimized for large files and may have memory and performance issues.
     *
     * @param minRowNum the row to start the printing
     * @param maxRowNum the row to end the printing
     */
    fun writeAsText(out: PrintWriter, minRowNum: Long = 1, maxRowNum: Long = totalNumberRows){
        val resultSet = selectRows(minRowNum, maxRowNum)
        DatabaseIfc.writeAsText(DatabaseIfc.createCachedRowSet(resultSet), out)
    }

    /** Writes the data in the file to a CSV file
     *
     * @param out the print write to write the data to
     * @param header true means the file will contain a header of column names
     */
    fun exportToCSV(out: PrintWriter, header: Boolean = true){
        myDb.exportTableAsCSV(dataTableName, out, schemaName = null, header)
    }

    /**
     * This is not optimized for large files and may have memory and performance issues.
     *
     * @param wbName      the name of the workbook, must not be null
     * @param wbDirectory the path to the directory to contain the workbook, must not be null
     * @throws IOException if something goes wrong with the writing
     */
    fun exportToExcelWorkbook(wbName: String, wbDirectory: Path) {
        val names: MutableList<String> = ArrayList()
        names.add(dataTableName)
        myDb.exportToExcel(names, wbName = wbName, wbDirectory = wbDirectory)
    }

    companion object {
        const val DEFAULT_ROW_BUFFER_SIZE = 100

        /**
         * Gets the metadata for an existing TabularInputFile.  The path must lead
         * to a file that has the correct internal representation for tabular data files.
         * Such a file can be created via TabularOutputFile.
         *
         * @param pathToFile the path to the input file, must not be null
         * @return the metadata for the file column names and data type
         */
        fun columnTypes(pathToFile: Path): Map<String, DataType> {
            check(DatabaseFactory.isSQLiteDatabase(pathToFile)) { "The path does represent a valid TabularInputFile $pathToFile" }
            // determine the name of the data table
            val fileName = pathToFile.fileName.toString()
            val fixedFileName = fileName.replace("[^a-zA-Z]".toRegex(), "")
            val dataTableName = fixedFileName + "_Data"
            // open up the database file
            val database: DatabaseIfc = DatabaseFactory.getSQLiteDatabase(pathToFile, true)
            // get the table metadata from the database
            val tableMetaData = database.tableMetaData(dataTableName)
            if (tableMetaData.isEmpty()) {
                throw IllegalStateException("The path does represent a valid TabularInputFile $pathToFile")
            }
            // process the columns to determine the column names and data types
            val columnTypes = mutableMapOf<String, DataType>()
            for (colMetaData in tableMetaData) {
                val colName = colMetaData.name
                val colType = mapSQLTypeToDataType(colMetaData)
                columnTypes[colName] = colType
            }
            return columnTypes
        }

        private fun mapSQLTypeToDataType(columnMetaData: ColumnMetaData): DataType {
            return when (columnMetaData.type) {
                Types.BIT, Types.BOOLEAN, Types.INTEGER, Types.TINYINT, Types.BIGINT, Types.SMALLINT,
                Types.DECIMAL, Types.DOUBLE, Types.FLOAT, Types.NUMERIC, Types.REAL -> {
                    DataType.NUMERIC
                }
                Types.TIMESTAMP, Types.DATE, Types.TIME, Types.BINARY, Types.CHAR, Types.NCHAR, Types.NVARCHAR,
                Types.VARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR -> {
                    DataType.TEXT
                }
                else -> {
                    DataType.TEXT
                }
            }
        }
    }
}