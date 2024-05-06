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

import ksl.controls.experiments.SimulationRun
import ksl.controls.experiments.SimulationRunner
import ksl.examples.book.chapter5.PalletWorkCenter
import ksl.examples.book.chapter6.StemFairMixer
import ksl.simulation.Model
import ksl.utilities.io.ExcelUtil
import ksl.utilities.io.dbutil.KSLDatabase
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import org.jetbrains.kotlinx.dataframe.api.print
import org.jetbrains.kotlinx.dataframe.api.toDataFrame

fun main(){
    showControls()
//    testSimulationRunner()
}

fun showControls(){
    val model = Model("STEM_Fair_Test", autoCSVReports = true)
    model.numberOfReplications = 10
    model.experimentName = "StemFairExp"
    // add the model element to the main model
    val stemFairMixer = StemFairMixer(model)
    // demonstrate capturing data to database with an observer
    val kslDatabaseObserver = KSLDatabaseObserver(model)
    // get the controls
    val controls = model.controls()

//    controls.controlData().toDataFrame().print(rowsLimit = 36)

//    val cmap = controls.asMap()
//    ExcelUtil.writeToExcel(cmap, "Controls")
//    val rmap = ExcelUtil.readToMap("Controls")
//    for ((k, v) in rmap) {
 //       println("$k: $v")
//    }
//    println(controls)
    println()
    val control = controls.control("JHBuntR.initialCapacity")
    println("Control value ${control?.value}")
    control?.value = 5.0
    println(control)
    println(" JHBunt initial capacity = ${stemFairMixer.jhBuntRecruiters.initialCapacity}")
    println()
    val rvParams = model.rvParameterSetter
//    println(rvParams)

    val pm = rvParams.flatParametersAsDoubles
    for ((k, v) in pm) {
        println("$k: $v")
    }

 //  println(rvParams.parametersAsJson())

    println()
//    val list = rvParams.rvParametersData
//    for(thing in list){
//        println(thing)
//    }

}

/**
 *  This example is not testing the input and parameter setting.
 */
fun testSimulationRunner(){
    val model = Model("ControlsTest", autoCSVReports = true)
    model.lengthOfReplication = 6.0 * 60.0
    model.numberOfReplications = 10
    model.experimentName = "StemFairExp"
    // add the model element to the main model
    val stemFairMixer = StemFairMixer(model)
    // demonstrate capturing data to database with an observer
    val kslDatabaseObserver = KSLDatabaseObserver(model)
    val sdb = KSLDatabase.createEmbeddedDerbyKSLDatabase("TestDerbyKSLDb", model.outputDirectory.dbDir)
//        val sdb = KSLDatabase.createPostgreSQLKSLDatabase(dbName = "postgres")
    val kdb = KSLDatabase(sdb)
    KSLDatabaseObserver(model, kdb)

    val controls = model.controls()
    val control = controls.control("JHBuntR.initialCapacity")
    control?.value = 5.0
    model.experimentalControls = controls.asMap()
    val rvParams = model.rvParameterSetter

    val sr = SimulationRunner(model)

    val reps = sr.chunkReplications(10, 4)
    val srList = mutableListOf<SimulationRun>()
    for(rep in reps){
        println("simulating simulation run = ${rep.runName}  ...")
        val simulationRun = sr.simulate(experimentRunParameters = rep)
        println("Completed simulation run = ${rep.runName}")
        srList.add(simulationRun)
    }

    for(sRun in srList){
        println(sRun.toJson())
    }

}