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

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ksl.utilities.Interval
import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc
import java.util.*

object KSLMaps {

    /**
     * Creates a LinkedHashMap to preserve order. If the value is NaN,
     * then the entry is not added to the map.
     *
     * @param keys   the keys to add to the map, must not be null. Length should be same as values.
     * @param values the values to add to the map, must not be null
     * @return the created map
     */
    fun makeMap(keys: Array<String>, values: DoubleArray): LinkedHashMap<String, Double> {
        if (keys.size != values.size) {
            throw IllegalArgumentException("The supplied arrays must have the same length")
        }
        val map = LinkedHashMap<String, Double>()
        for (i in keys.indices) {
            if (!values[i].isNaN()) {
                map[keys[i]] = values[i]
            }
        }
        return map
    }

    /**
     * Takes the double map and creates a single map by forming unique keys from the double
     * map keys.  Use a concatenation string that does not appear within the keys of the inMap and
     * for which a unique key will be formed from the two keys.
     *
     * @param inMap    the incoming map to flatten, must not be null
     * @param catChars the concatenation string used to form new unique string, must not be null
     * and must result in unique keys
     * @return the new flattened map
     */
    fun flattenMap(inMap: Map<String, Map<String, Double>>, catChars: String): LinkedHashMap<String, Double> {
        val outMap = LinkedHashMap<String, Double>()
        for (outerEntry: Map.Entry<String, Map<String, Double>> in inMap.entries) {
            val outerKey = outerEntry.key
            val innerMap = outerEntry.value
            for (e: Map.Entry<String, Double> in innerMap.entries) {
                val innerKey = e.key
                val value = e.value
                val newKey = outerKey + catChars + innerKey
                if (outMap.containsKey(newKey)) {
                    throw IllegalStateException(
                        "The concatenation character resulted in a duplicate " +
                                "key, " + newKey + ", when trying to flatten the map"
                    )
                } else {
                    outMap[newKey] = value
                }
            }
        }
        return outMap
    }

    /**  Reverses the operation of un-flatten using the provided catChars.  Assumes that the catChars will
     * slit the keys of the map into two separate strings that can be used as keys into the returned double
     * map. A duplicate key within the inner map will result in an exception.
     *
     * @param inMap the map to un-flatten, must not be null
     * @param catChars the concatenation character, must not be null
     * @return the un-flattened map
     */
    fun unflattenMap(inMap: Map<String, Double>, catChars: String): LinkedHashMap<String, MutableMap<String, Double>> {
        Objects.requireNonNull(inMap, "The incoming map cannot be null")
        Objects.requireNonNull(catChars, "The concatenation string cannot be null")
        val outMap = LinkedHashMap<String, MutableMap<String, Double>>()
        for (e: Map.Entry<String, Double> in inMap.entries) {
            val theKey = e.key
            val value = e.value
            //split the key
            val keys = theKey.split(catChars.toRegex(), limit = 2).toTypedArray()
            if (!outMap.containsKey(keys[0])) {
                // make the inner map for first key occurrence
                val innerMap = LinkedHashMap<String, Double>()
                outMap[keys[0]] = innerMap
            }
            val innerMap = (outMap[keys[0]])!!
            if (innerMap.containsKey(keys[1])) {
                throw IllegalStateException(
                    ("The concatenation character resulted in a duplicate " +
                            "key, " + keys[1] + " for primary key " + keys[0] + ", when trying to unflatten the map")
                )
            }
            innerMap[keys[1]] = value
        }
        return outMap
    }

    /**
     * Converts a JSON [string] representation to a Map
     * with keys that are strings and values that are doubles.
     * @return the map from the string
     */
    fun stringDoubleMapFromJson(string: String): Map<String, Double> {
        return Json.decodeFromString(string)
    }

    /**
     *  Converts a [map] that has (String, Double) pairs to
     *  a JSON string
     *  @return the JSON string
     */
    fun stringDoubleMapToJson(map: Map<String, Double>): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(map)
    }

    /**
     *  Converts a [map] that has (String, Double) pairs to
     *  a JSON string
     *  @return the JSON string
     */
    fun stringDoubleArrayMapToJson(map: Map<String, DoubleArray>): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(map)
    }

}

fun Map<String, Double>.toJson(): String {
    return KSLMaps.stringDoubleMapToJson(this)
}

/**
 *  Converts the inner DoubleArray to List<Double>
 */
fun Map<String, DoubleArray>.toMapOfLists(): Map<String, List<Double>> {
    val map = mutableMapOf<String, List<Double>>()
    for ((name, array) in this) {
        map[name] = array.toList()
    }
    return map
}

/**
 *  Computes the box plot summaries for the data within the map
 */
fun Map<String, DoubleArray>.boxPlotSummaries(): Map<String, BoxPlotSummary> {
    return Statistic.boxPlotSummaries(this)
}

/**
 *  Computes the statistical summaries for the data within the map
 */
fun Map<String, DoubleArray>.statisticalSummaries(): Map<String, StatisticIfc> {
    return Statistic.statisticalSummaries(this)
}

/**
 *  Computes the confidence intervals for the data in the map
 */
fun Map<String, DoubleArray>.confidenceIntervals(level: Double = 0.95): Map<String, Interval> {
    return Statistic.confidenceIntervals(this)
}

