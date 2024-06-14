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

package ksl.examples.book.chapter4

import ksl.simulation.Model
import ksl.utilities.io.dbutil.KSLDatabaseObserver
import ksl.utilities.random.rvariable.ExponentialRV

/**
 * Example 4.5
 *
 * This example illustrates the running of the DriveThroughPharmacyWithQ instance.
 * This example uses a Queue instance to report statistics on the time
 * spent in the queue.
 *
 * The model is run for 30 replications, of length 20,000 minutes, with a
 * warmup of 5000.0 minutes. The number of servers can be supplied. In
 * addition, the user can supply the distribution associated with the time
 * between arrivals and the service time distribution.
 */
fun main() {
    val sim = Model("Drive Through PharmacyQ")
    sim.numberOfReplications = 30
    sim.lengthOfReplication = 20000.0
    sim.lengthOfReplicationWarmUp = 5000.0
    // add DriveThroughPharmacy to the main model
    val dtp = DriveThroughPharmacyWithQ(sim, 1)
    dtp.arrivalRV.initialRandomSource = ExponentialRV(6.0, 1)
    dtp.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)
//    val kslDatabaseObserver = KSLDatabaseObserver(sim)
//    val testDb = KSLDatabaseObserver.createDerbyKSLDatabaseObserver(sim)
    sim.simulate()
    sim.print()
    println()
//    println(dtp.systemTimeHistogram)
    val hp = dtp.systemTimeHistogram.histogramPlot()
    hp.showInBrowser("System Time Histogram")
}