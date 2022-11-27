package ksl.utilities.io.tabularfiles

import ksl.utilities.countGreaterEqualTo
import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.ColumnMetaData
import ksl.utilities.io.dbutil.DatabaseFactory
import ksl.utilities.io.dbutil.DatabaseIfc
import java.io.IOException
import java.nio.file.Path
import java.sql.BatchUpdateException
import java.sql.SQLException
import java.util.*
import kotlin.math.max

class TabularOutputFile(columnTypes: Map<String, DataType>, path: Path) : TabularFile(columnTypes, path) {
    //TODO consider permitting the appending of rows to an existing file

    private val myDb: DatabaseIfc
    private var myMaxRowsInBatch = 0
    private var myDataBuffer: MutableList<List<Any?>> = mutableListOf()
    private var myRowCount = 0
    private val myRow: RowSetterIfc
    private val myTableMetaData: List<ColumnMetaData>
    private val dataTableName: String

    init {
        val fileName = path.fileName.toString()
        val dir = path.parent
        myDb = DatabaseFactory.createSQLiteDatabase(fileName, dir)
        val fixedFileName = fileName.replace("[^a-zA-Z]".toRegex(), "")
        dataTableName = fixedFileName + "_Data"
        val cmd = createTableCommand(dataTableName)
        val executed = myDb.executeCommand(cmd)
        if (!executed) {
            throw IllegalStateException("Unable to create tabular file: $path")
        }
        myTableMetaData = myDb.tableMetaData(dataTableName)
        val numRowBytes = getNumRowBytes(getNumNumericColumns(), getNumTextColumns(), DEFAULT_TEXT_SIZE)
        val rowBatchSize = getRecommendedRowBatchSize(numRowBytes)
        myMaxRowsInBatch = max(MIN_DEFAULT_ROWS_IN_BATCH, rowBatchSize)
        myRow = getRow()
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

    /** Allows the user to configure the size of the batch writing if performance becomes an issue.
     * This may or may not provide any benefit. The static methods related to this functionality
     * can be used to recommend a reasonable batch size.
     *
     * @param numRows the number of rows to use when writing a batch to disk, must be greater than 0
     */
    fun setMaxRowsInBatch(numRows: Int) {
        require(numRows > 0) { "The number of rows in a batch must be > 0" }
        myMaxRowsInBatch = numRows
    }

    /**
     * Provides a row that can be used to set individual columns
     * before writing the row to the file
     *
     * @return a RowSetterIfc
     */
    fun getRow(): RowSetterIfc {
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
     * Writes the data currently in the row to the file. Once
     * written, the operation cannot be undone.
     *
     * @param rowSetter a rowSetter, provided by getRow()
     */
    fun writeRow(rowSetter: RowSetterIfc) {
        val row = rowSetter as Row
        myDataBuffer.add(row.elements)
        myRowCount++
        if (myRowCount == myMaxRowsInBatch) {
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
     * @param buffer the array of data to load into the file
     * @return the number of executed statements that occurred during the loading process
     */
    private fun insertData(buffer: MutableList<List<Any?>>): Int {
        try {
            myDb.getConnection().use { connection ->
                connection.autoCommit = false
                val n = getNumberColumns()
                val sql = myDb.createTableInsertStatement(dataTableName, n)
                val ps = connection.prepareStatement(sql)
                for (row in buffer) {
                    myDb.addBatch(row, n, ps)
                }
                val numInserts = ps.executeBatch()
                val k = numInserts.countGreaterEqualTo(0)
                if (k < buffer.size) {
                    KSL.logger.error("Unable to write all rows $k of buffer size ${buffer.size} to tabular file $dataTableName")
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
                    KSL.logger.error("Unable to write all rows to tabular file $dataTableName")
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
        val numRowBytes = getNumRowBytes(getNumNumericColumns(), getNumTextColumns(), DEFAULT_TEXT_SIZE)
        val rowBatchSize = getRecommendedRowBatchSize(numRowBytes)
        sb.append(numRowBytes)
        sb.append(System.lineSeparator())
        sb.append("Possible number of rows per batch = ")
        sb.append(rowBatchSize)
        sb.append(System.lineSeparator())
        sb.append("Configured number of rows per batch = ")
        sb.append(myMaxRowsInBatch)
        sb.append(System.lineSeparator())
        return sb.toString()
    }

    companion object {

        private const val DEFAULT_PAGE_SIZE = 8192
        private const val MIN_DEFAULT_ROWS_IN_BATCH = 32
        private var DEFAULT_TEXT_SIZE = 32

        /**
         *
         * @return the assumed default length of the longest text column
         */
        fun getDefaultTextSize(): Int {
            return DEFAULT_TEXT_SIZE
        }

        /** The assumed length of the longest text column. For performance
         * optimization purposes only.
         *
         * @param defaultTextSize must be 0 or more
         */
        fun setDefaultTextSize(defaultTextSize: Int) {
            require(defaultTextSize >= 0) { "The text size must be >= 0" }
            DEFAULT_TEXT_SIZE = defaultTextSize
        }

        /**
         *
         * @param numNumericColumns the number of numeric columns
         * @param numTextColumns the number of text columns
         * @param maxTextLength the length of the longest text column
         * @return the number of bytes on such a row
         */
        fun getNumRowBytes(numNumericColumns: Int, numTextColumns: Int, maxTextLength: Int): Int {
            require(numNumericColumns >= 0) { "The number of numeric columns must be >= 0" }
            require(numTextColumns >= 0) { "The number of text columns must be >= 0" }
            require(maxTextLength >= 0) { "The maximum text length must be >= 0" }
            require((numNumericColumns != 0) && (numTextColumns) != 0) { "The number of numeric columns and the number of text cannot both be zero" }
            val nb = numNumericColumns * 8
            val tb = numTextColumns * maxTextLength * 2
            return nb + tb
        }

        /**
         *
         * @param rowByteSize the number of bytes in a row, must be greater than 0
         * @return the recommended number of rows in a batch, given the row byte size
         */
        fun getRecommendedRowBatchSize(rowByteSize: Int): Int {
            require(rowByteSize > 0) { "The row byte size must be > 0" }
            return Math.floorDiv(DEFAULT_PAGE_SIZE, rowByteSize)
        }

    }
}