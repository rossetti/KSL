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

package ksl.examples.general.spatial

import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.modeling.spatial.RectangularGridSpatialModel2D
import ksl.simulation.Model
import ksl.utilities.KSLArrays
import ksl.utilities.random.rvariable.NormalRV

fun main(){
// test1()
// test2()
test3()
}

fun test1(){
    val sm = Euclidean2DPlane()
    val loc1 = sm.Point(2.0, 4.0)
    println(loc1)
    val loc2 = sm.Point(3.0, 3.0)
    println(loc2)
    val d = sm.distance(loc1, loc2)
    println("distance = $d")
}

fun test2(){
    val sm = RectangularGridSpatialModel2D(100.0, 100.0, 10, 10)
    println(sm)
}

fun test3(){
    val x = NormalRV(50.0, 16.0)
    val dm = KSLArrays.matrixOfDoubles(5, 5, x)
    val d = DistancesModel()
    d.addDistances(dm)
    println(d)
}