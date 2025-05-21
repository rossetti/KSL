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

package ksl.utilities.collections

/**
 * A table that maps a pair of keys (row key, column key) to a value. Each unique pair of
 * non-null keys maps to a single non-null value.
 *
 * This is similar to Google Guava's Table interface.
 *
 * @param R the type of row keys
 * @param C the type of column keys
 * @param V the type of values
 */
interface Table<R : Any, C : Any, V : Any> {
    /**
     * Returns true if the table contains a mapping with the specified row and column keys.
     */
    fun contains(rowKey: R, columnKey: C): Boolean

    /**
     * Returns true if the table contains a mapping with the specified row key.
     */
    fun containsRow(rowKey: R): Boolean

    /**
     * Returns true if the table contains a mapping with the specified column key.
     */
    fun containsColumn(columnKey: C): Boolean

    /**
     * Returns true if the table contains the specified value.
     */
    fun containsValue(value: V): Boolean

    /**
     * Returns the value corresponding to the given row and column keys, or null if no such mapping exists.
     */
    fun get(rowKey: R, columnKey: C): V?

    /**
     * Returns true if the table contains no mappings.
     */
    fun isEmpty(): Boolean

    /**
     * Returns the number of row key / column key / value mappings in the table.
     */
    val size: Int

    /**
     * Returns a set of all row key / column key / value triplets.
     */
    val cellSet: Set<Cell<R, C, V>>

    /**
     * Returns a view of all mappings that have the given row key.
     */
    fun row(rowKey: R): Map<C, V>

    /**
     * Returns a set of row keys that have one or more values in the table.
     */
    val rowKeySet: Set<R>

    /**
     * Returns a view of all mappings that have the given column key.
     */
    fun column(columnKey: C): Map<R, V>

    /**
     * Returns a set of column keys that have one or more values in the table.
     */
    val columnKeySet: Set<C>

    /**
     * Returns a collection of all values in the table.
     */
    val values: Collection<V>

    /**
     * Returns a view that associates each row key with the corresponding map from column keys to values.
     */
    val rowMap: Map<R, Map<C, V>>

    /**
     * Returns a view that associates each column key with the corresponding map from row keys to values.
     */
    val columnMap: Map<C, Map<R, V>>

    /**
     * Represents a row key / column key / value triplet.
     */
    interface Cell<R, C, V> {
        /**
         * Returns the row key of this cell.
         */
        val rowKey: R

        /**
         * Returns the column key of this cell.
         */
        val columnKey: C

        /**
         * Returns the value of this cell.
         */
        val value: V
    }
}

/**
 * A mutable table that maps a pair of keys (row key, column key) to a value.
 *
 * @param R the type of row keys
 * @param C the type of column keys
 * @param V the type of values
 */
interface MutableTable<R : Any, C : Any, V : Any> : Table<R, C, V> {
    /**
     * Associates the specified value with the specified keys.
     *
     * @return the previous value associated with the keys, or null if there was no mapping
     */
    fun put(rowKey: R, columnKey: C, value: V): V?

    /**
     * Removes the mapping, if any, associated with the given keys.
     *
     * @return the previous value associated with the keys, or null if there was no mapping
     */
    fun remove(rowKey: R, columnKey: C): V?

    /**
     * Removes all mappings from the table.
     */
    fun clear()

    /**
     * Removes all mappings that have the given row key.
     *
     * @return true if any mappings were removed
     */
    fun removeRow(rowKey: R): Boolean

    /**
     * Removes all mappings that have the given column key.
     *
     * @return true if any mappings were removed
     */
    fun removeColumn(columnKey: C): Boolean

    /**
     * Adds all mappings from the specified table to this table.
     */
    fun putAll(table: Table<R, C, V>)
}

/**
 * Abstract implementation of the MutableTable interface.
 */
abstract class AbstractTable<R : Any, C : Any, V : Any> : MutableTable<R, C, V> {
    protected val backingRowMap: MutableMap<R, MutableMap<C, V>> = mutableMapOf()
    protected val backingColumnMap: MutableMap<C, MutableMap<R, V>> = mutableMapOf()

    override fun contains(rowKey: R, columnKey: C): Boolean {
        val columnToValueMap = backingRowMap[rowKey] ?: return false
        return columnKey in columnToValueMap
    }

    override fun containsRow(rowKey: R): Boolean {
        return rowKey in backingRowMap
    }

    override fun containsColumn(columnKey: C): Boolean {
        return columnKey in backingColumnMap
    }

    override fun containsValue(value: V): Boolean {
        for (columnToValueMap in backingRowMap.values) {
            if (value in columnToValueMap.values) {
                return true
            }
        }
        return false
    }

    override fun get(rowKey: R, columnKey: C): V? {
        val columnToValueMap = backingRowMap[rowKey] ?: return null
        return columnToValueMap[columnKey]
    }

    override fun isEmpty(): Boolean {
        return backingRowMap.isEmpty()
    }

    override val size: Int
        get() {
            var size = 0
            for (map in backingRowMap.values) {
                size += map.size
            }
            return size
        }

    override val cellSet: Set<Table.Cell<R, C, V>>
        get() {
            val cells = mutableSetOf<Table.Cell<R, C, V>>()
            for ((rowKey, columnToValueMap) in backingRowMap) {
                for ((columnKey, value) in columnToValueMap) {
                    cells.add(SimpleCell(rowKey, columnKey, value))
                }
            }
            return cells
        }

    override fun row(rowKey: R): Map<C, V> {
        return backingRowMap[rowKey] ?: emptyMap()
    }

    override val rowKeySet: Set<R>
        get() = backingRowMap.keys

    override fun column(columnKey: C): Map<R, V> {
        return backingColumnMap[columnKey] ?: emptyMap()
    }

    override val columnKeySet: Set<C>
        get() = backingColumnMap.keys

    override val values: Collection<V>
        get() {
            val valuesList = mutableListOf<V>()
            for (columnToValueMap in backingRowMap.values) {
                valuesList.addAll(columnToValueMap.values)
            }
            return valuesList
        }

    override val rowMap: Map<R, Map<C, V>>
        get() = backingRowMap.mapValues { it.value as Map<C, V> }

    override val columnMap: Map<C, Map<R, V>>
        get() = backingColumnMap.mapValues { it.value as Map<R, V> }

    override fun put(rowKey: R, columnKey: C, value: V): V? {
        var columnToValueMap = backingRowMap[rowKey]
        if (columnToValueMap == null) {
            columnToValueMap = mutableMapOf()
            backingRowMap[rowKey] = columnToValueMap
        }

        var rowToValueMap = backingColumnMap[columnKey]
        if (rowToValueMap == null) {
            rowToValueMap = mutableMapOf()
            backingColumnMap[columnKey] = rowToValueMap
        }

        val oldValue = columnToValueMap[columnKey]
        columnToValueMap[columnKey] = value
        rowToValueMap[rowKey] = value
        return oldValue
    }

    override fun remove(rowKey: R, columnKey: C): V? {
        val columnToValueMap = backingRowMap[rowKey] ?: return null
        val rowToValueMap = backingColumnMap[columnKey] ?: return null

        val oldValue = columnToValueMap.remove(columnKey)
        rowToValueMap.remove(rowKey)

        if (columnToValueMap.isEmpty()) {
            backingRowMap.remove(rowKey)
        }
        if (rowToValueMap.isEmpty()) {
            backingColumnMap.remove(columnKey)
        }

        return oldValue
    }

    override fun clear() {
        backingRowMap.clear()
        backingColumnMap.clear()
    }

    override fun removeRow(rowKey: R): Boolean {
        val columnToValueMap = backingRowMap.remove(rowKey) ?: return false

        for ((columnKey, _) in columnToValueMap) {
            val rowToValueMap = backingColumnMap[columnKey]
            rowToValueMap?.remove(rowKey)
            if (rowToValueMap?.isEmpty() == true) {
                backingColumnMap.remove(columnKey)
            }
        }

        return true
    }

    override fun removeColumn(columnKey: C): Boolean {
        val rowToValueMap = backingColumnMap.remove(columnKey) ?: return false

        for ((rowKey, _) in rowToValueMap) {
            val columnToValueMap = backingRowMap[rowKey]
            columnToValueMap?.remove(columnKey)
            if (columnToValueMap?.isEmpty() == true) {
                backingRowMap.remove(rowKey)
            }
        }

        return true
    }

    override fun putAll(table: Table<R, C, V>) {
        for (cell in table.cellSet) {
            put(cell.rowKey, cell.columnKey, cell.value)
        }
    }

    private data class SimpleCell<R, C, V>(
        override val rowKey: R,
        override val columnKey: C,
        override val value: V
    ) : Table.Cell<R, C, V>
}

/**
 * Implementation of Table using hash tables.
 */
class HashBasedTable<R : Any, C : Any, V : Any> : AbstractTable<R, C, V>() {
    companion object {
        /**
         * Creates a new empty HashBasedTable.
         */
        fun <R : Any, C : Any, V : Any> create(): HashBasedTable<R, C, V> {
            return HashBasedTable()
        }

        /**
         * Creates a new HashBasedTable containing the same mappings as the specified table.
         */
        fun <R : Any, C : Any, V : Any> create(table: Table<R, C, V>): HashBasedTable<R, C, V> {
            val result = HashBasedTable<R, C, V>()
            result.putAll(table)
            return result
        }
    }
}
