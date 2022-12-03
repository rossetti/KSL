/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
/**
 *
 */
package ksl.examples.book.chapter5

import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Statistic

/**
 * @author rossetti
 */
class LindleyEquation(
    var tba: RandomIfc = ExponentialRV(1.0),
    var st: RandomIfc = ExponentialRV(0.7),
    var numReps: Int = 30,
    var numObs: Int = 100000,
    var warmUp: Int = 10000
) {
    init {
        require(numReps > 0) { "The number of replications must be > 0" }
        require(numObs > 0) { "The number of replications must be > 0" }
        require(warmUp >= 0) { "The number of replications must be > 0" }
    }

    val avgw = Statistic("Across rep avg waiting time")
    val avgpw = Statistic("Across rep prob of wait")
    val wbar = Statistic("Within rep avg waiting time")
    val pw = Statistic("Within rep prob of wait")

    fun simulate(r: Int = numReps, n: Int = numObs, d: Int = warmUp) {
        require(r > 0) { "The number of replications must be > 0" }
        require(n > 0) { "The number of replications must be > 0" }
        require(d >= 0) { "The number of replications must be > 0" }
        for (i in 1..r) {
            var w = 0.0 // initial waiting time
            for (j in 1..n) {
                w = Math.max(0.0, w + st.value - tba.value)
                wbar.collect(w) // collect waiting time
                pw.collect(w > 0.0) // collect P(W>0)
                if (j == d) { // clear stats at warmup
                    wbar.reset()
                    pw.reset()
                }
            }
            //collect across replication statistics
            avgw.collect(wbar.average)
            avgpw.collect(pw.average)
            // clear within replication statistics for next rep
            wbar.reset()
            pw.reset()
        }

    }

    fun print() {
        println("Replication/Deletion Lindley Equation Example")
        println(avgw)
        println(avgpw)
        println()
        val sr = StatisticReporter(mutableListOf(avgw, avgpw))
        print(sr.halfWidthSummaryReport())
    }

}