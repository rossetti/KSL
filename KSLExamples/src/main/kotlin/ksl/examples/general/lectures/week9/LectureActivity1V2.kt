package ksl.examples.general.lectures.week9

import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV

/*
Activity 1 Basic Process Simulation
*/
fun main() {
    val m = Model()
    val dtp = MfgSystemActivityV2(m, name = "MfgSystem")
    m.numberOfReplications = 20
    m.lengthOfReplication = 60.0 * 60.0
    m.simulate()
    m.print()
}

/*
A small manufacturing system has a problem with parts that require rework.  The system consists of three separate machines.
  Each machine has its own queue of waiting parts.  Parts arrive to the system at a rate of 6 parts per hour according to a Poisson process (stream 1).
    After arriving, a part randomly selects one of the machines according to the following distribution:
    20% of the arriving parts select machine 1, 30% of the arriving parts select machine 2 and the remaining 50% select machine 3 (stream 2).

Parts can be processed on the machines with a processing time that is lognormally distributed with a mean of 27 minutes
 and a standard deviation of 11 minutes (stream 3).  After a part completes the processing on the machine it is
 inspected for defects.  The inspection time is negligible.  Three percent of the parts require rework (stream 4).
 The parts requiring rework again randomly choose the machine for processing using the same selection distribution.
 The processing time distribution for a part requiring rework is lognormally distributed with a mean of 27 minutes and
  a standard deviation of 11 minutes (stream 3).  A part that was reworked never requires additional work.
  That is, reworked parts are guaranteed defect free.  Build an KSL model for this situation.

  Run your model for 60 hours and 20 replications.
The base time unit for the simulation should be in MINUTES
*/

class MfgSystemActivityV2(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val timeBetweenArrivalRV: RandomVariable = RandomVariable(parent, ExponentialRV(10.0, 1))
    private val processingTimeRV: RandomVariable = RandomVariable(parent, LognormalRV(27.0, 11.0 * 11.0, 3))
    private val reworkRV: RandomVariable = RandomVariable(parent, BernoulliRV(0.03, 4))

    private val machine1: ResourceWithQ = ResourceWithQ(this, "machine1")
    private val machine2: ResourceWithQ = ResourceWithQ(this, "machine2")
    private val machine3: ResourceWithQ = ResourceWithQ(this, "machine3")

    private val machineSelector = REmpiricalList<ResourceWithQ>(this,
        elements = listOf(machine1, machine2, machine3), theCDF = doubleArrayOf(0.2, 0.5, 1.0), streamNum = 2)

    override fun initialize() {
        schedule(this::arrival, timeBetweenArrivalRV)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        val part = Part()
        activate(part.mfgProcess)
        schedule(this::arrival, timeBetweenArrivalRV)
    }

    private inner class Part : Entity() {
        val mfgProcess: KSLProcess = process() {
            use(machineSelector.randomElement, amountNeeded = 1, delayDuration = processingTimeRV)
            // since it can happen only once, why not just repeat the code if it needs rework?
            if (reworkRV.value == 1.0){
                use(machineSelector.randomElement, amountNeeded = 1, delayDuration = processingTimeRV)
            }
        }
    }

}