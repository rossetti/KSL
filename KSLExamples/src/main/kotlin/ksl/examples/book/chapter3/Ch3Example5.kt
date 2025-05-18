/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.book.chapter3

import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.BatchStatistic
import ksl.utilities.statistic.Statistic

/**
 * Example 3.5
 * This example illustrates how to create an instance of a
 * BatchStatistic.  A BatchStatistic will collect statistics on observations
 * and while doing so form batches on which batch average are computed.
 * The batching algorithm form batches that are based on the supplied
 * minimum number of batches, maximum number of batches, and the number of
 * batches multiple.
 */
fun main() {
    val d = ExponentialRV(2.0, streamNum = 2)

    // number of observations
    val n = 1000

    // minimum number of batches permitted
    // there will not be less than this number of batches
    val minNumBatches = 40

    // minimum batch size permitted
    // the batch size can be no smaller than this amount
    val minBatchSize = 25

    // maximum number of batch multiple
    //  The multiple of the minimum number of batches
    //  that determines the maximum number of batches
    //  e.g. if the min. number of batches is 20
    //  and the max number batches multiple is 2,
    //  then we can have at most 40 batches
    val maxNBMultiple = 2

    // In this example, since 40*25 = 1000, the batch multiple does not matter
    val bm = BatchStatistic(minNumBatches, minBatchSize, maxNBMultiple)
    for (i in 1..n) {
        bm.collect(d.value)
    }
    println(bm)
    val bma = bm.batchMeans
    var i = 0
    for (x in bma) {
        println("bm($i) = $x")
        i++
    }

    // this re-batches the 40 down to 10
    val reformed = bm.reformBatches(10)
    println(Statistic(reformed))
}
