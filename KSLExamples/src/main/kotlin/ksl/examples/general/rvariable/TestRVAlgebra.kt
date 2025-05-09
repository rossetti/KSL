/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.rvariable

import ksl.utilities.observers.Emitter
import ksl.utilities.random.rvariable.*
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistics
import kotlin.math.pow
import kotlin.math.sin

fun main() {

//    test1()
//   test2()

    test4()

    val rvTypes: Array<RVType> = enumValues<RVType>()
    for(t in rvTypes){
        println(t.name)
    }
}

fun test4(){
    val rv = ExponentialRV(20.0)
    val s = Statistic()
//        val something: Emitter.Connection = rv.emitter.attach { s.collector() }
    val something: Emitter.Connection = rv.emitter.attach { x -> s.collect(x) }
    for(i in 1..10){
        rv.value
    }
    println()
    println(s)
    rv.emitter.detach(something)

}

fun test1() {
    val rv1 = ExponentialRV(10.0)
    val rv2 = ExponentialRV(20.0)
    // default is sum
    val rv = RVFunction(rv1, rv2)
    // divide them
    val rv3 = RVFunction(rv1, rv2, { f: Double, s: Double -> f / s })

    print(rv.sample(100).statistics())

    println()

    print(rv3.sample(100).statistics())
}

fun test2() {
    val rv1 = ExponentialRV(10.0)
    val n = (NormalRV(10.0, 2.0) + ExponentialRV(100.0)) / NormalRV(1.0, 2.0)

    print(n.sample(100).statistics())

    val rv4 = RVUFunction(rv1, { f: Double -> sin(f) })

    print(rv4.sample(100).statistics())

    val x = rv1 + 3.0

    val y = 3.0 + rv1

    val z = 3.0 * rv1

    print(z.sample(100).statistics())

    val w = sin(rv1)
    print(w.sample(100).statistics())

    val p = rv1.pow(2.0)

    print(p.sample(100).statistics())
}
