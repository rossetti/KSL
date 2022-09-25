/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
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