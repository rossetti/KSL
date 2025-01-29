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

package ksl.examples.general.montecarlo

import ksl.utilities.KSLArrays
import ksl.utilities.io.write
import ksl.utilities.io.writeToFile
import ksl.utilities.random.rvariable.*
import ksl.utilities.statistic.BoxPlotSummary
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.IntegerFrequency
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistics

fun main(){
    //example1()
   // example2()

 //   mvnExample()

    estimatePI()
}

fun example1(){
    // illustrate common random numbers
    // normals are dependent, because same stream
    val n1Stream = KSLRandom.rnStream(1)
    // n2Stream is a clone of n1Stream but not the same object
    val n2Stream = n1Stream.instance()
    val n1 = NormalRV(2.0, 0.64, n1Stream)
    val n2 = NormalRV(2.2, 0.36, n2Stream)
    val s1 = n1.sample(100)
    val s2 = n2.sample(100)
    val d = KSLArrays.subtractElements(s1, s2)
    d.writeToFile("Differences")
    val stats = d.statistics()
    println(stats)
    // compute statistics found on a box plot
    val boxPlotSummary = BoxPlotSummary(d)
    println(boxPlotSummary)
    val breakPoints = Histogram.recommendBreakPoints(stats)
    val h = Histogram(breakPoints)
    h.collect(d)
    println(h)
}

fun example2(){
    // make 10 Bernoulli random variables
    // and one random variable that is the sum of the 10
    var binomial: RVariableIfc = BernoulliRV(0.4)
    for (i in 1..9){
        binomial = binomial + BernoulliRV(0.4)
    }
    val data = binomial.sample(10000)
    val f = IntegerFrequency(name = "Frequency Tabulation")
    f.collect(data)
    println(f)
}

fun mvnExample(){
    val cov = arrayOf(
        doubleArrayOf(3.0, 2.0, 1.0),
        doubleArrayOf(2.0, 5.0, 3.0),
        doubleArrayOf(1.0, 3.0, 4.0),
    )
    val means = doubleArrayOf(1.0, 2.0, 3.0)

    println("covariances")
    cov.write()

    val rv = MVNormalRV(means, cov)
    for (i in 1..5) {
        val sample: DoubleArray = rv.sample()
        println(KSLArrays.toCSVString(sample))
    }

    println()
    println("Correlations")
    rv.correlations.write()

    val rho = MVNormalRV.convertToCorrelation(cov)
    println()
    rho.write()
    println(rho)
}

fun estimatePI(){
    val a = 0.0
    val b = 1.0
    val ucdf = UniformRV(a, b)
    val stat = Statistic("Area Estimator")
    val n = 100 // sample size
    for (i in 1..n) {
        val x = ucdf.value
        val gx = 4.0*kotlin.math.sqrt(1.0- x*x)
        val y = (b - a) * gx
        stat.collect(y)
    }
    System.out.printf("True Area = %10.3f %n", Math.PI)
    System.out.printf("Area estimate = %10.3f %n", stat.average)
    println("Confidence Interval")
    println(stat.confidenceInterval)
}