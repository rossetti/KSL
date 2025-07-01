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

import ksl.utilities.countGreaterEqualTo
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.ColumnMetaData
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.SQLiteDb
import ksl.utilities.io.dbutil.TabularData
import org.jetbrains.kotlinx.dataframe.AnyFrame
import org.jetbrains.kotlinx.dataframe.api.emptyDataFrame
import java.io.IOException
import java.nio.file.Path
import java.sql.BatchUpdateException
import java.sql.SQLException
import java.util.*
import kotlin.math.max

/**
 *  An abstraction for writing rows of tabular data. Columns of the tabular
 *  data can be of numeric or text.  Using this subclass of TabularFile
 *  users can write rows of data.  The user is responsible for filling rows with
 *  data of the appropriate type for the column and writing the row to the file.
 *
 *  Use the static methods of TabularFile to create and define the columns of the file.
 *  Use the methods of this class to write rows.  After writing the rows, it is important
 *  to call the flushRows() method to ensure that all buffered rows are committed to the file.
 *
 * @param columnTypes a map that defines the column names and their data types
 * @param path the path to the file for writing the data
 * @see ksl.utilities.io.tabularfiles.TabularFile
 * @see ksl.examples.utilities.TestTabularWork  For example code
 */
class TabularOutputFile(
    columnTypes: Map<String, DataType>,
    path: Path
) : TabularFile(columnTypes, path) {

    /**
     * Uses [tabularData] as the schema pattern for defining the columns and their data types
     */
    constructor(tabularData: TabularData, path: Path) : this(tabularData.extractColumnDataTypes(), path)

    internal val myDb: DatabaseIfc

    /** Allows the user to configure the size of the batch writing if performance becomes an issue.
     * This may or may not provide any benefit. The static methods related to this functionality
     * can be used to recommend a reasonable batch size.
     */
    var maxRowsInBatch : Int = 0
        set(numRows) {
            require(numRows > 0) { "The number of rows in a batch must be > 0" }
            field = numRows
        }
    private var myDataBuffer: MutableList<List<Any?>> = mutableListOf()
    private var myRowCount = 0
    private val myRow: RowSetterIfc
    private val myTableMetaData: List<ColumnMetaData>
    val dataTableName: String

    init {
        val fileName = path.fileName.toString()
        val dir = path.parent
        myDb = SQLiteDb.createDatabase(fileName, dir)
        val fixedFileName = fileName.replace("[^a-zA-Z]".toRegex(), "")
        dataTableName = fixedFileName + "_Data"
        val cmd = createTableCommand(dataTableName)
        val executed = myDb.executeCommand(cmd)
        if (!executed) {
            throw IllegalStateException("Unable to create tabular file: $path")
        }
        myTableMetaData = myDb.tableMetaData(dataTableName)
        val numRowBytes = numRowBytes(numNumericColumns, numTextColumns, defaultTextSize)
        val rowBatchSize = recommendedRowBatchSize(numRowBytes)
        maxRowsInBatch = max(MIN_DEFAULT_ROWS_IN_BATCH, rowBatchSize)
        myRow = row()
    }

    private fun createTableCommand(name: String): String {
        val sb = StringBuilder()
        sb.append("create table $name (")
        var i = 0
        for (col in myColumnTypes) {
            val type = if (col.value == DataType.NUMERIC) {
                "double"
            } else {
                "text"
            }
            i++
            if (i < myColumnNames.size) {
                sb.append("${col.key} $type,")
            } else {
                sb.append("${col.key} $type)")
            }
        }
        return sb.toString()
    }

    /**
     * Provides a row that can be used to set individual columns
     * before writing the row to the file
     *
     * @return a RowSetterIfc
     */
    fun row(): RowSetterIfc {
        return Row(this)
    }

    /**
     * A convenience method. This writes the values in the array
     * to the numeric columns in the file in the order of their appearance.
     * Any text columns will have the value null and cannot be unwritten.
     *
     * The recommended use is for files that have all numeric columns.
     *
     * If you have mixed column types, then use getRow() to first
     * set the appropriate columns before writing them.
     *
     * @param data the data to write
     */
    fun writeNumeric(data: DoubleArray) {
        myRow.setNumeric(data)
        writeRow(myRow)
    }

    /**
     * A convenience method. This writes the values in the array
     * to the text columns in the file in the order of their appearance.
     * Any numeric columns will have the value Double.NaN and cannot be unwritten.
     *
     * The recommended use is for files that have all text columns.
     *
     * If you have mixed column types, then use getRow() to first
     * set the appropriate columns before writing them.
     *
     * @param data the data to write
     */
    fun writeText(data: Array<String?>) {
        myRow.setText(data)
        writeRow(myRow)
    }

    /**
     * Writes the data represented by the TabularData instance
     * to the file. The operation cannot be undone.
     *
     * @param data the data represented by an instance of a TabularData
     */
    fun writeRow(data: TabularData) {
        myRow.setElements(data)
        writeRow(myRow)
    }

    /**
     * Writes the data currently in the row to the file. Once
     * written, the operation cannot be undone.
     *
     * @param rowSetter a rowSetter, provided by getRow()
     */
    fun writeRow(rowSetter: RowSetterIfc) {
        val row = rowSetter as Row
        myDataBuffer.add(row.elements)
        myRowCount++
        if (myRowCount == maxRowsInBatch) {
            insertData(myDataBuffer)
            myRowCount = 0
        }
    }

    /** A convenience method if the user has a list of rows to write.
     * All rows in the list are written to the file.
     *
     * @param rows the rows to write, must not be null
     */
    fun writeRows(rows: List<RowSetterIfc>) {
        Objects.requireNonNull(rows, "The list was null")
        for (row in rows) {
            writeRow(row)
        }
    }

    /**
     * After writing all rows, you must call flushRows() to ensure that
     * all buffered row data is committed to the file.
     */
    fun flushRows() {
        if (myRowCount > 0) {
            insertData(myDataBuffer)
        }
    }

    /**
     *  Converts the columns and rows to a Dataframe.
     *  @return the data frame or an empty data frame if conversion does not work
     */
    override fun asDataFrame(): AnyFrame {
        val resultSet = myDb.selectAllIntoOpenResultSet(dataTableName)
        val df = if (resultSet != null) {
            DatabaseIfc.toDataFrame(resultSet)
        } else {
            emptyDataFrame<Nothing>()
        }
        resultSet?.close()
        return df
    }

    /**
     *  Opens the file as a TabularInputFile
     */
    fun asTabularInputFile(): TabularInputFile {
        return TabularInputFile(this.columnTypes, this.path)
    }

    /**
     * @param buffer the array of data to load into the file
     * @return the number of executed statements that occurred during the loading process
     */
    private fun insertData(buffer: MutableList<List<Any?>>): Int {
        try {
            myDb.getConnection().use { connection ->
                connection.autoCommit = false
                val n = numberColumns
                val sql = DatabaseIfc.insertIntoTableStatementSQL(dataTableName, n, schemaName = myDb.defaultSchemaName)
                val ps = connection.prepareStatement(sql)
                for (row in buffer) {
                    DatabaseIfc.addBatch(row, n, ps)
                }
                val numInserts = ps.executeBatch()
                val k = numInserts.countGreaterEqualTo(0)
                if (k < buffer.size) {
                    KSL.logger.error { "Unable to write all rows $k of buffer size ${buffer.size} to tabular file $dataTableName" }
                    throw IOException("Unable to write rows to tabular file $dataTableName")
                } else {
                    KSL.logger.trace { "Inserted $k rows of batch size ${buffer.size} into file $dataTableName" }
                }
                connection.commit()
                buffer.clear()
                ps.clearBatch()
                ps.close()
                return k
            }
        } catch (ex: Exception) {
            when (ex) {
                is BatchUpdateException,
                is SQLException,
                is IOException -> {
                    KSL.logger.error { "Unable to write all rows to tabular file $dataTableName" }
                    throw IOException("Unable to write all rows to tabular file $dataTableName")
                }

                else -> throw ex
            }
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(super.toString())
        sb.append(System.lineSeparator())
        sb.append("Estimated number of bytes per row = ")
        val numRowBytes = numRowBytes(numNumericColumns, numTextColumns, defaultTextSize)
        val rowBatchSize = recommendedRowBatchSize(numRowBytes)
        sb.append(numRowBytes)
        sb.append(System.lineSeparator())
        sb.append("Possible number of rows per batch = ")
        sb.append(rowBatchSize)
        sb.append(System.lineSeparator())
        sb.append("Configured number of rows per batch = ")
        sb.append(maxRowsInBatch)
        sb.append(System.lineSeparator())
        return sb.toString()
    }

    companion object {

        private const val DEFAULT_PAGE_SIZE = 8192
        private const val MIN_DEFAULT_ROWS_IN_BATCH = 32

        /** The assumed length of the longest text column. For performance
         * optimization purposes only. Must be 0 or more
         */
        var defaultTextSize : Int = 32
            set(value) {
                require(value >= 0) { "The text size must be >= 0" }
                field = value
            }

        /**
         *
         * @param numNumericColumns the number of numeric columns
         * @param numTextColumns the number of text columns
         * @param maxTextLength the length of the longest text column
         * @return the number of bytes on such a row
         */
        fun numRowBytes(numNumericColumns: Int, numTextColumns: Int, maxTextLength: Int): Int {
            require(numNumericColumns >= 0) { "The number of numeric columns must be >= 0" }
            require(numTextColumns >= 0) { "The number of text columns must be >= 0" }
            require(maxTextLength >= 0) { "The maximum text length must be >= 0" }
            require((numNumericColumns != 0) || (numTextColumns != 0)) { "The number of numeric columns ($numNumericColumns) and the number of text ($numTextColumns) cannot both be zero" }
            val nb = numNumericColumns * 8
            val tb = numTextColumns * maxTextLength * 2
            return nb + tb
        }

        /**
         *
         * @param rowByteSize the number of bytes in a row, must be greater than 0
         * @return the recommended number of rows in a batch, given the row byte size
         */
        fun recommendedRowBatchSize(rowByteSize: Int): Int {
            require(rowByteSize > 0) { "The row byte size must be > 0" }
            return Math.floorDiv(DEFAULT_PAGE_SIZE, rowByteSize)
        }

    }
}