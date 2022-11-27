package ksl.utilities.io.tabularfiles

import ksl.utilities.io.dbutil.ColumnMetaData
import ksl.utilities.io.dbutil.DatabaseFactory
import ksl.utilities.io.dbutil.DatabaseIfc
import ksl.utilities.io.dbutil.ResultSetRowIterator
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import kotlin.math.min

class TabularInputFile private constructor(columnTypes: Map<String, DataType>, path: Path) :
    TabularFile(columnTypes, path) {

    constructor(path: Path) : this(getColumnTypes(path), path)

    private val myDb: DatabaseIfc
    private val myDataTableName: String

    var rowBufferSize = DEFAULT_ROW_BUFFER_SIZE // maximum number of records held inside iterators
        set(value){
            if (value <= 0){
                field = DEFAULT_ROW_BUFFER_SIZE
            } else {
                field = value
            }
        }

    val totalNumberRows: Long
    private val myConnection : Connection
    private val myRowSelector: PreparedStatement

    init{
        // determine the name of the data table
        val fileName: String = path.fileName.toString()
        val fixedFileName = fileName.replace("[^a-zA-Z]".toRegex(), "")
        myDataTableName = fixedFileName + "_Data"
        // open up the database file
        myDb = DatabaseFactory.getSQLiteDatabase(path, true)
        totalNumberRows = myDb.numRows(myDataTableName)
        myConnection = myDb.getConnection()
        val rowsSQL = "select * from $myDataTableName where rowid between ? and ?"
        myRowSelector = myConnection.prepareStatement(rowsSQL)
    }

    fun close(){
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
    fun fetchRow(rowNum: Long) : RowGetterIfc {
        require(rowNum > 0) { "The row number must be > 0" }
        require(rowNum <= totalNumberRows) { "The row number must be <= $totalNumberRows" }
        val rows = fetchRows(rowNum, rowNum)
        return if (rows.isEmpty()){
            Row(this)
        } else {
            rows[0]
        }
    }

    private fun convertResultsToRows(selectedRows: ResultSet, startingRowNum: Long): List<RowGetterIfc>{
        val rows = mutableListOf<RowGetterIfc>()
        val iterator = ResultSetRowIterator(selectedRows)
        var i = startingRowNum
        while (iterator.hasNext()){
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
        init{
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

    // make the query string for the prepared statement
    // then make the prepared statement
    // then reuse the prepared statement many times

    private fun selectRows(minRowNum: Long, maxRowNum: Long): ResultSet {
        require(minRowNum > 0) { "The minimum row number must be > 0" }
        require(maxRowNum > 0) { "The maximum row number must be > 0" }
        require(minRowNum <= maxRowNum) { "The minimum row number must be < the maximum row number." }
        myRowSelector.setLong(1, minRowNum)
        myRowSelector.setLong(2, maxRowNum)
        return myRowSelector.executeQuery()!!
    }

    //TODO column selection

    companion object {
        //TODO I do not know why sqlite is leaving the shm and wal files every time this class is used
        // one possible solution is to use DSL.using(connection, SQLDialect.SQLITE) so that the connection can be
        // explicitly opened and closed. The shm and wal files are probably not being left in TabularOutputFile
        // execution because those writes are wrapped in a transaction, which is closing the connection
        // appears to be related to turning on wal option config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        // in DatabaseFactory
        const val DEFAULT_ROW_BUFFER_SIZE = 100

        /**
         * Gets the meta data for an existing TabularInputFile.  The path must lead
         * to a file that has the correct internal representation for tabular data files.
         * Such a file can be created via TabularOutputFile.
         *
         * @param pathToFile the path to the input file, must not be null
         * @return the meta data for the file column names and data type
         */
        fun getColumnTypes(pathToFile: Path): Map<String, DataType> {
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