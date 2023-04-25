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

package ksl.examples.general.models

import ksl.modeling.elements.EventGenerator
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rvariable.*

/**
 *  This is Example 10.1 from Introduction to SIMAN V adn CINEMA V by
 *  Banks, Burnette, Kozloski, and Rose (1995) Wiley and Sons
 *
 *  There are two types of jobs that are processed within a job shop. Of the two types, 70% are
 *  type 1 and 30% are type 2. Type 1 jobs go to drilling, to milling, to grinding, and then to inspection.
 *  Type 2 jobs go to drilling, planing, grinding, and then to inspection. The job arrival process is
 *  a Poisson process with a mean time between arrivals of 5 minutes.
 *
 *  There are 2 drills, 3 mills, 2 grinders, 3 planers, and 1 inspector. The time to process each job is as follows
 *
 *  drilling: type 1 and 2, uniform(6.0, 9.0)  minutes
 *  milling: type 1, triangular(10.0, 14.0, 18.0) minutes
 *  planing: type 2, triangular(20.0, 26.0, 32.0) minutes
 *  grinding:
 *      type 1 = Discrete empirical (10%, 6 minutes), (65%, 7 minutes), (25%, 8 minutes)
 *      type 2 = Discrete empirical (10%, 6 minutes), (25%, 7 minutes), (30%, 8 minutes), (25%, 9 minutes), (10%, 10 minutes)
 *  inspection: type 1, normal (mean = 3.6 minutes, std dev = 0.6)
 *
 *  90% of jobs pass inspection, 5% go to rework, and 5% are scrapped. The rework jobs are sent back to drilling and
 *  start their processing again. At the grinder, type 1 jobs have priority over type 2 jobs.
 *
 *  The queues at drilling, milling, planing, grinding, and inspections have capacities: 2, 3, 3, 2, and 4, respectively.
 *  Any jobs that attempt to enter a queue that has reached its capacity should be sent to an overflow
 *  area and leave the system.
 *
 *  The system has 3 conveyors to assist with moving the jobs within the system.  The jobs require 2 cells when using the conveyor.
 *  The time taken to load or unload the job on or off the conveyors is assumed to be negligible.
 *
 *  The first conveyor (ArrivalConveyor) moves items from the area entrance to the drilling station.
 *  The second conveyor (LoopConveyor) moves items between the work stations.
 *  The third conveyor (ExitConveyor) moves items from inspection to the exit area.
 *
 *  The configuration of the conveyors is as follows:
 *
 *  ArrivalConveyor
 *      Accumulating
 *      From arrival to drilling 60 feet
 *      Initial velocity = 25 feet/minute
 *      Cell size = 10 feet
 *      Maximum number of cells that a part will use = 2
 *
 *  LoopConveyor
 *      Non-accumulating, circular
 *      (Drilling to Milling = 70 feet)
 *      (Milling to Planing = 90 feet)
 *      (Planing to Grinding = 50 feet)
 *      (Grinding to Inspection = 180 feet)
 *      (Inspection to Drilling = 250 feet)
 *      Initial velocity = 30 feet/minute
 *      Cell size = 10 feet
 *      Maximum number of cells that a part will use = 2
 *
 *  ExitConveyor
 *      Accumulating
 *      From inspection to exit = 100 feet
 *      Initial velocity = 45 feet/minute
 *      Cell size = 10 feet
 *      Maximum number of cells that a part will use = 2
 *
 *  Simulate the system for 40 hours and estimate the following:
 *
 *  1. Number of jobs completed
 *  2. Utilization of the resources
 *  3. The number of jobs that overflow due to queue capacity
 *  4. Average number of jobs in the queues
 *  5. Number of jobs that are scrapped
 *  6. Number of reworked jobs
 *  7. Average time in the system for a job
 *  8. Utilization of space on the conveyors
 *
 */
class ConveyorExample(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(5.0, 1)

    private val myArrivalGenerator: EventGenerator = EventGenerator(this, this::partArrivals, myTBArrivals, myTBArrivals)

    private val myDrillingRV = RandomVariable(this, UniformRV(6.0, 9.0, 2))
    private val myMillingRV = RandomVariable(this, TriangularRV(10.0, 14.0, 18.0, 3))
    private val myPlaningRV = RandomVariable(this, TriangularRV(20.0, 26.0, 32.0, 4))
    private val myType1GrindingRV = RandomVariable(this,
        DEmpiricalRV(doubleArrayOf(6.0, 7.0, 8.0), doubleArrayOf(0.1, 0.75, 1.0), 5))
    private val myType2GrindingRV = RandomVariable(this,
        DEmpiricalRV(doubleArrayOf(6.0, 7.0, 8.0, 9.0, 10.0), doubleArrayOf(0.1, 0.35, 0.65, 0.90, 1.0), 6))

    private val myInspectOutcomeRV = RandomVariable(this,
        DEmpiricalRV(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.9, 0.95, 1.0), 7))

    private val myInspectionRV = RandomVariable(this, NormalRV(3.6, 0.6*0.6, 8))

    private val myJobTypeRV = RandomVariable(this, BernoulliRV(0.70, 7))

    private val drillingQCapacity = 2
    private val millingQCapacity = 3
    private val planingQCapacity = 3
    private val grindingQCapacity = 2
    private val inspectionQCapacity = 2

    private val myDrillingResource: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "Drills")
    private val myMillingResource: ResourceWithQ = ResourceWithQ(this, capacity = 3, name = "Mills")
    private val myGrindingResource: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "Grinders")
    private val myInspectionResource: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Inspectors")
    private val myPlaningResource: ResourceWithQ = ResourceWithQ(this, capacity = 3, name = "Planers")

    private val myOverallSystemTime = Response(this, "OverallSystemTime")
    private val myOverflowCounter = Counter(this, "OverFlowCount")
    private val myScrapCounter = Counter(this, "ScrapCount")
    private val myReworkCounter = Counter(this, "ReworkCount")
    private val myCompletedCounter = Counter(this, "CompletedCount")

    private val arrivalConveyor: Conveyor
    private val loopConveyor: Conveyor
    private val exitConveyor: Conveyor
    private val arrivalArea:  IdentityIfc = Identity("ArrivalArea")
    private val exitArea:  IdentityIfc = Identity("ExitArea")

    init {
        arrivalConveyor = Conveyor.builder(this, "ArrivalConveyor")
            .conveyorType(Conveyor.Type.ACCUMULATING)
            .velocity(25.0)
            .cellSize(10)
            .maxCellsAllowed(2)
            .firstSegment(arrivalArea, myDrillingResource, 60)
            .build()

        loopConveyor = Conveyor.builder(this,"LoopConveyor")
            .conveyorType(Conveyor.Type.NON_ACCUMULATING)
            .velocity(30.0)
            .cellSize(10)
            .maxCellsAllowed(2)
            .firstSegment(myDrillingResource, myMillingResource, 70)
            .nextSegment(myPlaningResource, 90)
            .nextSegment(myGrindingResource, 50)
            .nextSegment(myInspectionResource, 180)
            .nextSegment(myDrillingResource, 250)
            .build()

        exitConveyor = Conveyor.builder(this, "ExitConveyor")
            .conveyorType(Conveyor.Type.ACCUMULATING)
            .velocity(45.0)
            .cellSize(10)
            .maxCellsAllowed(2)
            .firstSegment(myInspectionResource, exitArea, 100)
            .build()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine(arrivalConveyor)
        sb.appendLine()
        sb.appendLine(loopConveyor)
        sb.appendLine()
        sb.appendLine(exitConveyor)
        return sb.toString()
    }

    private fun partArrivals(generator: EventGenerator){
        val isType1 = myJobTypeRV.value.toBoolean()
        if (isType1){
            val type1 = PartType1()
            activate(type1.productionProcess)
        } else{
            val type2 = PartType2()
            activate(type2.productionProcess)
        }
    }

    private inner class PartType1 : Entity(){
        val productionProcess = process {
            var conveyorRequest = requestConveyor(arrivalConveyor, arrivalArea, 2)
            rideConveyor(conveyorRequest, myDrillingResource)
            exitConveyor(conveyorRequest)

            if (myDrillingResource.waitingQ.size >= drillingQCapacity){
                myOverflowCounter.increment()
                return@process
            }
            var allocation = seize(myDrillingResource)
            delay(myDrillingRV)
            release(allocation)

            conveyorRequest = requestConveyor(loopConveyor, myDrillingResource, 2)
            rideConveyor(conveyorRequest, myMillingResource)
            exitConveyor(conveyorRequest)

            if (myMillingResource.waitingQ.size >= millingQCapacity){
                myOverflowCounter.increment()
                return@process
            }
            allocation = seize(myMillingResource)
            delay(myMillingRV)
            release(allocation)

            conveyorRequest = requestConveyor(loopConveyor, myMillingResource, 2)
            rideConveyor(conveyorRequest, myGrindingResource)
            exitConveyor(conveyorRequest)

            if (myGrindingResource.waitingQ.size >= grindingQCapacity){
                myOverflowCounter.increment()
                return@process
            }
            allocation = seize(myGrindingResource)
            delay(myType1GrindingRV)
            release(allocation)

            conveyorRequest = requestConveyor(loopConveyor, myGrindingResource, 2)
            rideConveyor(conveyorRequest, myInspectionResource)
            exitConveyor(conveyorRequest)

            if (myInspectionResource.waitingQ.size >= inspectionQCapacity){
                myOverflowCounter.increment()
                return@process
            }
            allocation = seize(myInspectionResource)
            delay(myInspectionRV)
            release(allocation)

            val inspectOutcome = myInspectOutcomeRV.value
            if (inspectOutcome == 1.0){
                // passed
            } else if (inspectOutcome == 2.0) {
                // needs rework
            } else {
                // must be inspectOutcome == 3.0, needs to be scrapped
            }
        }

    }

    private inner class PartType2 : Entity(){
        val productionProcess = process {
            var conveyorRequest = requestConveyor(arrivalConveyor, arrivalArea, 2)
            rideConveyor(conveyorRequest, myDrillingResource)
            exitConveyor(conveyorRequest)

            if (myDrillingResource.waitingQ.size >= drillingQCapacity){
                myOverflowCounter.increment()
                return@process
            }
            var allocation = seize(myDrillingResource)
            delay(myDrillingRV)
            release(allocation)

            conveyorRequest = requestConveyor(loopConveyor, myDrillingResource, 2)
            rideConveyor(conveyorRequest, myPlaningResource)
            exitConveyor(conveyorRequest)

            if (myPlaningResource.waitingQ.size >= planingQCapacity){
                myOverflowCounter.increment()
                return@process
            }
            allocation = seize(myPlaningResource)
            delay(myPlaningRV)
            release(allocation)

            conveyorRequest = requestConveyor(loopConveyor, myPlaningResource, 2)
            rideConveyor(conveyorRequest, myGrindingResource)
            exitConveyor(conveyorRequest)

            if (myGrindingResource.waitingQ.size >= grindingQCapacity){
                myOverflowCounter.increment()
                return@process
            }
            allocation = seize(myGrindingResource)
            delay(myType2GrindingRV)
            release(allocation)

            conveyorRequest = requestConveyor(loopConveyor, myGrindingResource, 2)
            rideConveyor(conveyorRequest, myInspectionResource)
            exitConveyor(conveyorRequest)

            if (myInspectionResource.waitingQ.size >= inspectionQCapacity){
                myOverflowCounter.increment()
                return@process
            }
            allocation = seize(myInspectionResource)
            delay(myInspectionRV)
            release(allocation)

            val inspectOutcome = myInspectOutcomeRV.value
            if (inspectOutcome == 1.0){
                // passed
            } else if (inspectOutcome == 2.0) {
                // needs rework
            } else {
                // must be inspectOutcome == 3.0, needs to be scrapped
            }

        }
    }

    private fun overflow(){

    }
}

fun main(){

    val m = Model()
    val test = ConveyorExample(m)
    println(test)
    m.lengthOfReplication = 60.0*40.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()
}