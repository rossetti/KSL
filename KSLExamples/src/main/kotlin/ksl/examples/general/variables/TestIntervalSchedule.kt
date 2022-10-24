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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.examples.general.variables

import ksl.examples.book.chapter7.DriveThroughPharmacyWithQ
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseInterval
import ksl.modeling.variable.ResponseSchedule
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.SimulationReporter


fun main() {
    val m = Model("DLB_with_Q")
    // create the model element and attach it to the main model
    DriveThroughPharmacyWithQ(m)
    val rs: Response? = m.getResponse("System Time")
    val tw: TWResponse? = m.getResponse("# in System") as TWResponse
    //ResponseSchedule sched = new ResponseSchedule(m, 5.0);
    val sched = ResponseSchedule(m, 0.0)
    //sched.addConsecutiveIntervals(2, 5, "Interval");
    sched.addIntervals(5.0, 2, 5.0)
    sched.addResponseToAllIntervals(rs!!)
    sched.addResponseToAllIntervals(tw!!)
    // sched.setStartTime(5.0);
    //sched.setScheduleRepeatFlag(true);
    sched.scheduleRepeatFlag = false

    val ri = ResponseInterval(m, 1.0, "Hourly")
    ri.repeatFlag = true
    ri.startTime = 0.0
    ri.addResponseToInterval(rs, true)
    ri.addResponseToInterval(tw, true)
    System.out.println(sched)
    m.numberOfReplications = 2
    m.lengthOfReplication = 20.0

    m.lengthOfReplicationWarmUp = 5.0
    //sim.setLengthOfWarmUp(1000);
    val r: SimulationReporter = m.simulationReporter

    //System.out.println(sim);
    // tell the simulation to run
    println("Simulation started.")
    m.simulate()
    println("Simulation completed.")
    r.printAcrossReplicationSummaryStatistics()
}