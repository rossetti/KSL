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

package ksl.utilities.maps


interface BiMap<K : Any, V : Any> : Map<K, V> {
    override val values: Set<V>
    val inverse: BiMap<V, K>
}

interface MutableBiMap<K : Any, V : Any> : BiMap<K, V>, MutableMap<K, V> {
    override val values: MutableSet<V>
    override val inverse: MutableBiMap<V, K>

    fun forcePut(key: K, value: V): V?
}

abstract class AbstractBiMap<K : Any, V : Any> protected constructor(
    private val direct: MutableMap<K, V>,
    private val reverse: MutableMap<V, K>
) : MutableBiMap<K, V> {
    override val size: Int
        get() = direct.size

    override val inverse: MutableBiMap<V, K> by lazy {
        object : AbstractBiMap<V, K>(reverse, direct) {
            override val inverse: MutableBiMap<K, V>
                get() = this@AbstractBiMap
        }
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> by lazy {
        BiMapSet(direct.entries, { it.key }, { BiMapEntry(it) })
    }
    override val keys: MutableSet<K> by lazy {
        BiMapSet(direct.keys, { it }, { it })
    }
    override val values: MutableSet<V>
        get() = inverse.keys

    constructor() : this(LinkedHashMap(), LinkedHashMap())

    override fun forcePut(key: K, value: V): V? {
        val oldValue = direct.put(key, value)
        oldValue?.let { reverse.remove(it) }
        val oldKey = reverse.put(value, key)
        oldKey?.let { direct.remove(it) }
        return oldValue
    }

    override fun put(key: K, value: V): V? {
        require(value !in reverse) { "BiMap already contains value $value" }
        return forcePut(key, value)
    }

    override fun putAll(from: Map<out K, V>) {
        from.values.forEach { value ->
            require(value !in reverse) { "BiMap already contains value $value" }
        }
        from.entries.forEach { forcePut(it.key, it.value) }
    }

    override fun remove(key: K): V? {
        val oldValue = direct.remove(key)
        oldValue?.let { reverse.remove(it) }
        return oldValue
    }

    override fun clear() {
        direct.clear()
        reverse.clear()
    }

    override fun get(key: K): V? {
        return direct[key]
    }

    override fun containsKey(key: K): Boolean {
        return key in direct
    }

    override fun containsValue(value: V): Boolean {
        return value in reverse
    }

    override fun isEmpty(): Boolean {
        return direct.isEmpty()
    }

    private inner class BiMapSet<T : Any>(
        private val elements: MutableSet<T>,
        private val keyGetter: (T) -> K,
        private val elementWrapper: (T) -> T
    ) : MutableSet<T> by elements {
        override fun remove(element: T): Boolean {
            if (element !in this) {
                return false
            }

            val key = keyGetter(element)
            val value = direct.remove(key) ?: return false
            try {
                reverse.remove(value)
            } catch (throwable: Throwable) {
                direct[key] = value
                throw throwable
            }
            return true
        }

        override fun clear() {
            direct.clear()
            reverse.clear()
        }

        override fun iterator(): MutableIterator<T> {
            val iterator = elements.iterator()
            return BiMapSetIterator(iterator, keyGetter, elementWrapper)
        }
    }

    private inner class BiMapSetIterator<T : Any>(
        private val iterator: MutableIterator<T>,
        private val keyGetter: (T) -> K,
        private val elementWrapper: (T) -> T
    ) : MutableIterator<T> {
        private var last: T? = null

        override fun hasNext(): Boolean {
            return iterator.hasNext()
        }

        override fun next(): T {
            val element = iterator.next().apply {
                last = this
            }
            return elementWrapper(element)
        }

        override fun remove() {
            checkNotNull(last) { "Move to an element before removing it" }
            try {
                val key = keyGetter(last!!)
                val value = direct[key] ?: error("BiMap doesn't contain key $key ")
                reverse.remove(value)
                try {
                    iterator.remove()
                } catch (throwable: Throwable) {
                    reverse[value] = key
                    throw throwable
                }
            } finally {
                last = null
            }
        }
    }

    private inner class BiMapEntry(
        private val entry: MutableMap.MutableEntry<K, V>
    ) : MutableMap.MutableEntry<K, V> by entry {
        override fun setValue(newValue: V): V {
            if (entry.value == newValue) {
                reverse[newValue] = entry.key
                try {
                    return entry.setValue(newValue)
                } catch (throwable: Throwable) {
                    reverse[entry.value] = entry.key
                    throw throwable
                }
            } else {
                check(newValue !in reverse) { "BiMap already contains value $newValue" }
                reverse[newValue] = entry.key
                try {
                    return entry.setValue(newValue)
                } catch (throwable: Throwable) {
                    reverse.remove(newValue)
                    throw throwable
                }
            }
        }
    }
}

/**
 * Returns a new mutable bimap with the specified contents, given as a list of pairs
 * where the first value is the key and the second is the value.
 *
 * If multiple pairs have the same key or the same value, the resulting bimap will contain
 * the last of those pairs.
 *
 * Entries of the bimap are iterated in the order they were specified.
 *
 * @param pairs the specified contents for the returned bimap
 * @return a new mutable bimap
 */
fun <K:Any, V:Any> mutableBiMapOf(vararg pairs: Pair<K, V>): MutableBiMap<K, V> = HashBiMap.create(pairs.toMap())

class HashBiMap<K : Any, V : Any>(capacity: Int = 16) : AbstractBiMap<K, V>(LinkedHashMap(capacity), LinkedHashMap(capacity)) {

    constructor(): this(0)

    companion object {
        fun <K : Any, V : Any> create(map: Map<K, V>): HashBiMap<K, V> {
            val bimap = HashBiMap<K, V>()
            bimap.putAll(map)
            return bimap
        }
    }
}
