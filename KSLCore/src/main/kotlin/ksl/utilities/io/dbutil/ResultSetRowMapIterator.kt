package ksl.utilities.io.dbutil

import java.sql.ResultSet
import java.sql.ResultSetMetaData

/**
 * The user can convert the returned rows based on ColumnMetaData.
 * The rows contain a map that is indexed by the column name and the value of the column
 *
 * @param resultSet the result set to iterate. It must be open and will be closed after iteration.
 */
class ResultSetRowMapIterator(private val resultSet: ResultSet) : Iterator<Map<String, Any?>> {
    init {
//TODO cause SQL not supported error       require(!resultSet.isClosed) { "Cannot iterate. The ResultSet is closed" }
    }

    var currentRow: Int = 0
        private set
    private var didNext: Boolean = false
    private var hasNext: Boolean = false
    val columnCount: Int
    val columnNames: List<String>

    init {
        val metaData: ResultSetMetaData = resultSet.metaData
        columnCount = metaData.columnCount
        val list = mutableListOf<String>()
        for (i in 1..columnCount) {
            list.add(metaData.getColumnName(i))
        }
        columnNames = list.toList()
    }

    override fun hasNext(): Boolean {
        if (!didNext) {
            hasNext = resultSet.next()
            if (!hasNext) resultSet.close()
            didNext = true
        }
        return hasNext
    }

    override fun next(): Map<String, Any?> {
        if (!didNext) {
            resultSet.next()
        }
        didNext = false
        currentRow++
        return makeRow(resultSet)
    }

    private fun makeRow(resultSet: ResultSet): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (i in 1..columnCount) {
            try {
                map[columnNames[i - 1]] = resultSet.getObject(i)
            } catch (e: RuntimeException) {
                DatabaseIfc.logger.warn { "There was a problem accessing column $i of the result set. Set value to null" }
            }
        }
        return map
    }

}