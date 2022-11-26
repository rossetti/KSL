package ksl.utilities.io.tabularfiles

import ksl.utilities.io.KSL
import ksl.utilities.io.dbutil.ColumnMetaData
import ksl.utilities.io.dbutil.DatabaseFactory
import ksl.utilities.io.dbutil.DatabaseIfc
import java.io.IOException
import java.nio.file.Path
import java.sql.Types
import javax.sql.rowset.CachedRowSet

class TabularInputFile private constructor(columnTypes: Map<String, DataType>, path: Path) :
    TabularFile(columnTypes, path) {

    constructor(path: Path) : this(getColumnTypes(path), path)

    private val myDb: DatabaseIfc
    private val myDataTableName: String
   // private val myRowIterator: ResultSetRowIterator

    var myRowBufferSize = DEFAULT_ROW_BUFFER_SIZE // maximum number of records held inside iterators
        set(value){
            if (value <= 0){
                field = DEFAULT_ROW_BUFFER_SIZE
            } else {
                field = value
            }
        }

    private val myTotalNumberRows: Long

    init{
        // determine the name of the data table
        val fileName: String = path.fileName.toString()
        val fixedFileName = fileName.replace("[^a-zA-Z]".toRegex(), "")
        myDataTableName = fixedFileName + "_Data"
        // open up the database file
        myDb = DatabaseFactory.getSQLiteDatabase(path, true)
        val rs = myDb.selectAllIntoOpenResultSet(myDataTableName)
        if (rs == null){
            KSL.logger.error{"Unable to open tabular file $myDataTableName"}
            throw IOException("Unable to open tabular file $myDataTableName")
        }
        myTotalNumberRows = myDb.numRows(myDataTableName)
//        myRowIterator = ResultSetRowIterator(rs)
    }

    inner class RowIterator : Iterator<RowGetterIfc> {

        override fun hasNext(): Boolean {
            TODO("Not yet implemented")
        }

        override fun next(): RowGetterIfc {
            TODO("Not yet implemented")
        }

    }

    private fun selectRows(minRowNum: Long, maxRowNum: Long): CachedRowSet {
        require(minRowNum > 0) { "The minimum row number must be > 0" }
        require(maxRowNum > 0) { "The maximum row number must be > 0" }
        require(minRowNum <= maxRowNum) { "The minimum row number must be < the maximum row number." }
        TODO("Not implemented yet")
        // construct the query and execute it
    }

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