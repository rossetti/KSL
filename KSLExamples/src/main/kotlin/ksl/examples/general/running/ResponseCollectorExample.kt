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

package ksl.examples.general.running

import ksl.examples.book.chapter7.DriveThroughPharmacyWithQ
import ksl.observers.ReplicationDataCollector
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {
    responseCollectorDemo();
}

fun responseCollectorDemo() {

    val m = Model("Drive Through Pharmacy")
    // add DriveThroughPharmacy to the main model
    val driveThroughPharmacy = DriveThroughPharmacyWithQ(m)
    driveThroughPharmacy.arrivalRV.initialRandomSource = ExponentialRV(1.0, 1)
    driveThroughPharmacy.serviceRV.initialRandomSource = ExponentialRV(0.7, 2)
    // set the parameters of the experiment
    m.numberOfReplications = 10
    m.lengthOfReplication = 3000.0
    m.lengthOfReplicationWarmUp = 1000.0

    // define a list of the names of the responses
    val responseNames = listOf("System Time", "# in System")

    val dc = ReplicationDataCollector(m)
    for (s in responseNames) {
        dc.addResponse(s)
    }
    dc.addCounterResponse("Num Served")
    println("Simulation started.")
    m.simulate()
    println("Simulation completed.")
    m.print()
    println()
    println(dc)
}

//fun controlVariateCollectorDemo() {
//    val sim = Simulation("Drive Through Pharmacy")
//    val m: Model = sim.getModel()
//    // add DriveThroughPharmacy to the main model
//    val driveThroughPharmacy = DriveThroughPharmacyWithQ(m)
//    // set the parameters of the experiment
//    sim.setNumberOfReplications(10)
//    sim.setLengthOfWarmUp(1000.0)
//    sim.setLengthOfReplication(3000.0)
//    val cv = ControlVariateDataCollector(m)
//    // add the response, must use the name from within the model
//    cv.addResponse("System Time")
//    // add the controls, must use the names from within the model
//    cv.addControlVariate("Arrival RV", 1.0)
//    cv.addControlVariate("Service RV", 0.5)
//    System.out.println(cv.getControlNames())
//    println("Simulation started.")
//    sim.run()
//    println("Simulation completed.")
//    sim.printHalfWidthSummaryReport()
//    println()
//    System.out.println(cv)
//    // write the data to a file
//    val writer: PrintWriter = JSL.getInstance().makePrintWriter("CVData.csv")
//    writer.printf("%s,%s, %s %n", "System Time", "Arrival RV", "Service RV")
//    val cvData: Array<DoubleArray> = cv.getData()
//    for (i in cvData.indices) {
//        writer.printf("%f, %f, %f %n", cvData[i][0], cvData[i][1], cvData[i][2])
//    }
//    val pathToFile: Path = JSL.getInstance().getOutDir().resolve("Another_CVData.csv")
//    CSVUtil.writeArrayToCSVFile(cv.getAllNames(), cv.getData(), pathToFile)
//}