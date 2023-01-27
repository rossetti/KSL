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

package ksl.examples.general.running

import ksl.controls.experiments.SimulationRunner
import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.examples.book.chapter6.StemFairMixer
import ksl.simulation.Model
import ksl.utilities.io.dbutil.KSLDatabaseObserver

fun main(){
    showControls()

}

fun showControls(){
    val model = Model("ControlsTest", autoCSVReports = true)
    model.numberOfReplications = 10
    model.experimentName = "StemFairExp"
    // add the model element to the main model
    val stemFairMixer = StemFairMixer(model)
    // demonstrate capturing data to database with an observer
    val kslDatabaseObserver = KSLDatabaseObserver(model)
    // get the controls
    val controls = model.controls()
//    println(controls)
    println()
    val control = controls.control("JHBuntR.initialCapacity")
    println("Control value ${control?.value}")
    control?.value = 5.0
    println(control)
    println(" JHBunt initial capacity = ${stemFairMixer.jhBuntRecruiters.initialCapacity}")
    println()
    val rvParams = model.rvParameterSetter
    println(rvParams)

   println(rvParams.parametersAsJson())

}

fun testSimulationRunner(){
    val model = Model("ControlsTest", autoCSVReports = true)
    model.numberOfReplications = 10
    model.experimentName = "StemFairExp"
    // add the model element to the main model
    val stemFairMixer = StemFairMixer(model)

    // demonstrate capturing data to database with an observer
    val kslDatabaseObserver = KSLDatabaseObserver(model)

    val sr = SimulationRunner(model)

    val reps = sr.chunkReplications(10, 4)
    for(rep in reps){
        sr.simulate(experimentRunParameters = rep)
    }
}