/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.utilities

import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.Normal
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.SampleIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.statistic.*

fun main() {
//    example1();
    //example2();
   // example3()

    testBootStrapSampler()
}

fun testBootStrapSampler(){
    val ed = ExponentialRV(10.0)
    val data = ed.sample(50)
    val stat = Statistic(data)
    println(stat)
    println()
    val rns = KSLRandom.nextRNStream()
    val bss = BootstrapSampler(data, BasicStatistics(), rns)
    val estimates = bss.bootStrapEstimates(300)
    for(e in estimates){
        println(e.asString())
    }
    val bs = Bootstrap(data, rns, name = "average")
    bs.resetStartStream()
    bs.generateSamples(300)
    println(bs)

}

fun example1() {
    val n = Normal(10.0, 3.0)
    val rv: RVariableIfc = n.randomVariable
    val bs = Bootstrap(rv.sample(50))
    bs.generateSamples(10, saveBootstrapSamples = true)
    println(bs)
    val list: List<Statistic> = bs.statisticForEachBootstrapSample
    for (s in list) {
        println(s.asString())
    }
    println()
    bs.generateSamples(10, saveBootstrapSamples = true)
    println(bs)
    val list2: List<Statistic> = bs.statisticForEachBootstrapSample
    for (s in list2) {
        println(s.asString())
    }
}

fun example2() {
    val n = Lognormal(10.0, 3.0)
    val rv: RVariableIfc = n.randomVariable
    val bs = Bootstrap(rv.sample(50))
    bs.generateSamples(1000, estimator = BSEstimatorIfc.Minimum())
    println(bs)
}

fun example3() {
    val n1 = Normal(10.0, 3.0)
    val n2 = Normal(5.0, 1.5)
    val smap = mutableMapOf<String, SampleIfc>()
    smap["n1"] = n1.randomVariable
    smap["n2"] = n2.randomVariable
    val multiBootstrap: MultiBootstrap = MultiBootstrap.create(100, smap)
    multiBootstrap.generateSamples(20, saveBootstrapSamples = true)
    println(multiBootstrap)
}


