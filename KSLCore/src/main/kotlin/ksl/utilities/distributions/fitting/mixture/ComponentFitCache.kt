/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.utilities.distributions.fitting.mixture

/**
 *  Memoizes group fits by index range, so that a group range fitted once is never fitted again
 *  for the same sample.
 *
 *  This is the single largest saving available to the search. Refinement moves one cut at a
 *  time, so consecutive partitions share most of their groups, and a search over the number of
 *  components revisits ranges repeatedly. Fitting a group means running the whole estimator
 *  catalog over it, which dominates the cost of everything else in the pipeline.
 *
 *  Failures are cached alongside successes. A group range for which no family fits is expensive
 *  to discover and just as expensive to rediscover; caching only successes would leave the worst
 *  case uncached.
 *
 *  The cache is bound to one sample. It holds no reference to the data and does not verify that
 *  successive calls pass the same array, because an index range is only meaningful relative to a
 *  fixed sample; create a new cache for a new sample.
 *
 *  This class is not safe for use from multiple threads. Each worker in a parallel experiment
 *  constructs its own fitter and cache, which is also required because KSL's scoring models and
 *  metrics are mutable.
 *
 *  @param fitter the fitter to delegate to on a miss
 */
class ComponentFitCache(
    private val fitter: ComponentFitterIfc
) : ComponentFitterIfc {

    private val myCache = HashMap<Long, GroupFitResult>()
    private var myHits: Int = 0
    private var myMisses: Int = 0

    /**
     *  The number of requests answered from the cache.
     */
    val hits: Int
        get() = myHits

    /**
     *  The number of requests that required a fit.
     */
    val misses: Int
        get() = myMisses

    /**
     *  The number of distinct group ranges currently held.
     */
    val size: Int
        get() = myCache.size

    /**
     *  The fraction of requests answered from the cache, or zero when there have been none.
     */
    val hitRate: Double
        get() {
            val total = myHits + myMisses
            return if (total == 0) 0.0 else myHits.toDouble() / total
        }

    override fun fitGroup(sortedData: DoubleArray, startIndex: Int, endIndex: Int): GroupFitResult {
        val key = keyFor(startIndex, endIndex)
        val cached = myCache[key]
        if (cached != null) {
            myHits++
            return cached
        }
        myMisses++
        val result = fitter.fitGroup(sortedData, startIndex, endIndex)
        myCache[key] = result
        return result
    }

    /**
     *  Discards all cached fits and resets the hit and miss counts.
     */
    fun clear() {
        myCache.clear()
        myHits = 0
        myMisses = 0
    }

    override fun toString(): String {
        return "ComponentFitCache(size=$size, hits=$myHits, misses=$myMisses, " +
                "hitRate=${"%.3f".format(hitRate)})"
    }

    companion object {

        /**
         *  The key identifying a group range. Matches the key a partition reports for a group,
         *  so a fit computed for one partition is found by any other partition placing a group
         *  over the same range.
         *
         *  @param startIndex the inclusive start index
         *  @param endIndex the exclusive end index
         */
        internal fun keyFor(startIndex: Int, endIndex: Int): Long {
            return (startIndex.toLong() shl 32) or endIndex.toLong()
        }
    }
}
