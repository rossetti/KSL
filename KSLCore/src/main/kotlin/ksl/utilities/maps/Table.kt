package ksl.utilities.maps

/**
This Table class offers core functionalities like put, get, remove, contains, row, column, cellSet,
rowKeySet, columnKeySet, values, clear, and size, mimicking the behavior of Guava's Table.
Note that this is a basic implementation and might not cover all the features of Guava's Table.
 */
class Table<R, C, V> {
    private val data: MutableMap<R, MutableMap<C, V>> = mutableMapOf()

    fun put(rowKey: R, columnKey: C, value: V) {
        data.computeIfAbsent(rowKey) { mutableMapOf() }[columnKey] = value
    }

    fun get(rowKey: R, columnKey: C): V? {
        return data[rowKey]?.get(columnKey)
    }

    fun remove(rowKey: R, columnKey: C): V? {
        return data[rowKey]?.remove(columnKey)
    }

    fun contains(rowKey: R, columnKey: C): Boolean {
        return data[rowKey]?.containsKey(columnKey) ?: false
    }

    fun row(rowKey: R): Map<C, V>? {
        return data[rowKey]
    }

    fun column(columnKey: C): Map<R, V> {
        return data.mapNotNull { (rowKey, columnMap) ->
            columnMap[columnKey]?.let { rowKey to it }
        }.toMap()
    }

    fun cellSet(): Set<Cell<R, C, V>> {
        return data.flatMap { (rowKey, columnMap) ->
            columnMap.map { (columnKey, value) ->
                Cell(rowKey, columnKey, value)
            }
        }.toSet()
    }

    fun rowKeySet(): Set<R> {
        return data.keys
    }

    fun columnKeySet(): Set<C> {
        return data.values.flatMap { it.keys }.toSet()
    }

    fun values(): Collection<V> {
        return data.values.flatMap { it.values }
    }

    fun clear() {
        data.clear()
    }

    val size: Int
        get() = data.values.sumOf { it.size }

    data class Cell<R, C, V>(val rowKey: R, val columnKey: C, val value: V)
}