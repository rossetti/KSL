package ksl.utilities.io.dbutil

import java.sql.ResultSet

/**
 * The user can convert the returned rows based on ColumnMetaData
 *
 * @param resultSet the result set to iterate. It must be open and will be closed after iteration.
 */
class ResultSetRowIterator(private val resultSet: ResultSet) : Iterator<List<Any?>> {
    init {
//TODO cause SQL not supported error        require(!resultSet.isClosed) { "Cannot iterate. The ResultSet is closed" }
    }

    var currentRow: Int = 0
        private set
    private var didNext: Boolean = false
    private var hasNext: Boolean = false
    val columnCount = resultSet.metaData?.columnCount ?: 0

    override fun hasNext(): Boolean {
        if (!didNext) {
            hasNext = resultSet.next()
            if (!hasNext) resultSet.close()
            didNext = true
        }
        return hasNext
    }

    override fun next(): List<Any?> {
        if (!didNext) {
            resultSet.next()
        }
        didNext = false
        currentRow++
        return makeRow(resultSet)
    }

    private fun makeRow(resultSet: ResultSet): List<Any?> {
        val list = mutableListOf<Any?>()
        for (i in 1..columnCount) {
            try {
                list.add(resultSet.getObject(i))
            } catch (e: RuntimeException) {
                list.add(null)
                DatabaseIfc.logger.warn { "There was a problem accessing column $i of the result set. Set value to null" }
            }
        }
        return list
    }

}