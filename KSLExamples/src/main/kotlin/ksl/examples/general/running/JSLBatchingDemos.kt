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
package ksl.examples.general.running

import ksl.examples.book.chapter7.DriveThroughPharmacyWithQ
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWBatchingElement
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.utilities.io.StatisticReporter
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.BatchStatisticIfc


/**
 * Illustrates performing a batch means analysis
 *
 * @author rossetti
 */
fun main() {
//    runBatchingExample();

    batchingASingleResponse()
}

/**
 * Creates a single StatisticalBatchingElement for the simulation. This causes all
 * responses within the simulation to be batched.  This uses the default settings
 * for the batching.  A StatisticalReporter is created after the simulation to
 * report the batch results.  Using the name of a response variable, specific
 * batch results/data can be accessed.
 */
fun runBatchingExample() {
    val m = Model("Drive Through Pharmacy")
    // add DriveThroughPharmacy to the main model
    val driveThroughPharmacy = DriveThroughPharmacyWithQ(m)
    driveThroughPharmacy.arrivalRV.initialRandomSource = ExponentialRV(1.0, 1)
    driveThroughPharmacy.serviceRV.initialRandomSource = ExponentialRV(0.7, 2)

    // create the batching element for the simulation
    val be = m.statisticalBatching()

    // set the parameters of the experiment
    m.lengthOfReplication = 1300000.0
    m.lengthOfReplicationWarmUp = 100000.0
    println("Simulation started.")
    m.simulate()
    println("Simulation completed.")

    // get a StatisticReport from the batching element
    val statisticReporter: StatisticReporter = be.statisticReporter

    // print out the report
    println(statisticReporter.halfWidthSummaryReport())
    //System.out.println(be.asString());

    // use the name of a response to get a reference to a particular response variable
    val systemTime: Response? = m.getResponse("System Time")
    // access the batch statistic from the batching element
    val batchStatistic: BatchStatisticIfc = be.batchStatisticFor(systemTime!!)
    // get the actual batch mean values
    val batchMeans: DoubleArray = batchStatistic.batchMeans
    println(batchMeans.contentToString())
}

/**
 * Shows how to batch a single response by creating a single batching element.
 */
fun batchingASingleResponse() {
    val m = Model("Drive Through Pharmacy")
    // getTWBatchStatisticObserver the model
    // add DriveThroughPharmacy to the main model
    val driveThroughPharmacy = DriveThroughPharmacyWithQ(m)
    driveThroughPharmacy.arrivalRV.initialRandomSource = ExponentialRV(6.0, 1)
    driveThroughPharmacy.serviceRV.initialRandomSource = ExponentialRV(3.0, 2)

    // make a time weighted batching element, accepting the default batching parameters
    val twbe = TWBatchingElement(driveThroughPharmacy)
    // get the reference to the response
    val tw: TWResponse? = m.getTWResponse("# in System")
    // tell the batching element to observe the time weighted variable
    twbe.add(tw!!)
    // set the parameters of the experiment
    m.lengthOfReplication = 200000.0
    m.lengthOfReplicationWarmUp = 5000.0
    println("Simulation started.")
    m.simulate()
    println("Simulation completed.")

    m.print()
    println(twbe.asString())
}