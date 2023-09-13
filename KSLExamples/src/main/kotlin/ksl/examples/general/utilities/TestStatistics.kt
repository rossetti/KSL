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

import ksl.utilities.distributions.KolmogorovSmirnovDist
import ksl.utilities.distributions.Normal
import ksl.utilities.io.asDataFrame
import ksl.utilities.io.writeToFile
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.U01Test

val testData = doubleArrayOf(
    9.57386907765005, 12.2683505035727, 9.57737208532118, 9.46483590382401, 10.7426270820019, 13.6417539779286,
    14.4009905460358, 11.9644504015896, 6.26967756749078, 11.6697189446463, 8.05817835081046, 9.15420225990855,
    12.6661856696446, 5.55898016788418, 11.5509859097328, 8.09378382643764, 10.2800698254101, 11.8820042371248,
    6.83122972495244, 7.76415517242856, 8.07037124078289, 10.1936926483873, 6.6056340897386, 8.67523311054818,
    10.2860106642238, 7.18655355368101, 13.7326532837148, 10.8384432167312, 11.20127362594, 9.10597298849603,
    13.1143167471166, 11.461547274424, 12.8686686397317, 11.6123823346184, 11.1766595994422, 9.96640484955756,
    7.60884520541602, 10.4027823841526, 13.6119110527044, 10.1927388924956, 11.0479192016999, 10.8335646086984,
    11.3464245020951, 11.7370035652721, 7.86882502350181, 10.1677674083453, 7.19107507247878, 10.3219440236855,
    11.8751033160937, 12.0507178860171, 10.2452271541559, 12.3574170333615, 8.61783541196255, 10.8759327855332,
    10.8965790925989, 9.78508632755152, 9.57354838522572, 10.668697248695, 10.4413115727436, 11.7056055258128,
    10.6836383463882, 9.00275936849233, 11.1546020461964, 11.5327569604436, 12.6632213399552, 9.04144921258077,
    8.34070478790018, 8.90537066541892, 8.9538251666728, 10.6587406131769, 9.46657058183544, 11.9067728468743,
    7.31151723229678, 10.3473820074211, 8.51409684117935, 15.061683701397, 7.67016173387284, 9.63463245914518,
    11.9544975062154, 8.75291180980926, 10.5902626954236, 10.7290328701981, 11.6103046633603, 9.18588529341066,
    11.7832770526927, 11.5803842329369, 8.77282669099311, 11.1605258465085, 9.87370336332192, 11.0792461569289,
    12.1457106152585, 8.16900025019337, 12.0963212801111, 10.7943060404262, 10.6648080893662, 10.7821384837463,
    9.20756684199006, 13.0421837951471, 8.50476579169282, 7.7653569673433
)

class TestStatistics {
}

fun main() {
    //   testData.writeToFile("testData.txt")
    // testAutoCorrelation()
    //   testPearsonCorrelation()

    //println(Statistic(testData))

//    testKSStatistic()
    val k = U01Test.recommendNumChiSquaredIntervals(100, 0.95)
    println("k = $k")
//    testStatistics()
}

fun testStatistics(){
    val s = Statistic(testData)
    println(s)
    println()
    println(s.asDataFrame())
}

fun testPearsonCorrelation() {
    val wt = doubleArrayOf(
        2.620, 2.875, 2.320, 3.215, 3.440, 3.460, 3.570, 3.190, 3.150, 3.440, 3.440,
        4.070, 3.730, 3.780, 5.250, 5.424, 5.345, 2.200, 1.615, 1.835, 2.465, 3.520, 3.435, 3.840, 3.845, 1.935,
        2.140, 1.513, 3.170, 2.770, 3.570, 2.780
    )
    val mpg = doubleArrayOf(
        21.0, 21.0, 22.8, 21.4, 18.7, 18.1, 14.3, 24.4, 22.8, 19.2, 17.8, 16.4, 17.3, 15.2, 10.4, 10.4, 14.7,
        32.4, 30.4, 33.9, 21.5, 15.5, 15.2, 13.3, 19.2, 27.3, 26.0, 30.4, 15.8, 19.7, 15.0, 21.4
    )
    val pc = Statistic.pearsonCorrelation(wt, mpg)
    println("pearson correlation = $pc")

    val x1 = testData.dropLast(1).toDoubleArray()
    val x2 = testData.copyOfRange(1, testData.size)

    val pc2 = Statistic.pearsonCorrelation(x1, x2)
    println("pearson correlation = $pc2")
}

fun testAutoCorrelation() {
    /*
     From R:
      [1,]  1.000000000
 [2,] -0.118558728
 [3,]  0.098812083
 [4,]  0.006255065
 [5,] -0.063506143
 [6,]  0.162430983
 [7,] -0.063894818
 [8,]  0.048417316
 [9,] -0.107041444
[10,] -0.034641810
[11,]  0.039512419
     results match to at least 7 decimal places
     */
    val ac = Statistic.autoCorrelations(testData, 10)
    for (k in ac.indices) {
        println("ac[$k] = ${ac[k]}")
    }
}

fun testKSStatistic(){
    val n = Normal(10.3, 3.484)
    val cdf: ((Double) -> Double) = n::cdf

    val ks = Statistic.ksTestStatistic(testData, cdf)
    println("KS Test Statistic = $ks")
    val pv = KolmogorovSmirnovDist.complementaryCDF(testData.size, ks)
    val cv = KolmogorovSmirnovDist.cdf(testData.size, ks)
    // this matches R if "exact" is specified: ks.test(x, "pnorm", exact=TRUE, 10.3, sqrt(3.484))
    println("KS p-value = $pv")
    println("cv = $cv")
}