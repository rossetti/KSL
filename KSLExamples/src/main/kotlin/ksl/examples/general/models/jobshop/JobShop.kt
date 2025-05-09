package ksl.examples.general.models.jobshop

import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.GammaRV
import ksl.utilities.random.rvariable.RVariableIfc

class JobShop(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val myTBA = ExponentialRV(0.25, 1)

    // define the resources
    private val machine1: ResourceWithQ = ResourceWithQ(this, capacity = 3, name = "Machine1")
    private val machine2: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "Machine2")
    private val machine3: ResourceWithQ = ResourceWithQ(this, capacity = 4, name = "Machine3")
    private val machine4: ResourceWithQ = ResourceWithQ(this, capacity = 3, name = "Machine4")
    private val machine5: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Machine5")

    // define steps to represent a sequence
    private inner class JobStep(val resource: ResourceWithQ, random: RVariableIfc) {
        val processTime = RandomVariable(this@JobShop, random)
    }

    private val jobSeq1 = listOf<JobStep>(
        JobStep(machine3, GammaRV(2.0, 0.5 / 2.0, 2)),
        JobStep(machine1, GammaRV(2.0, 0.6 / 2.0, 3)),
        JobStep(machine2, GammaRV(2.0, 0.85 / 2.0, 4)),
        JobStep(machine5, GammaRV(2.0, 0.5 / 2.0, 5))
    )

    private val jobSeq2 = listOf<JobStep>(
        JobStep(machine4, GammaRV(2.0, 1.1 / 2.0, 6)),
        JobStep(machine1, GammaRV(2.0, 0.8 / 2.0, 7)),
        JobStep(machine3, GammaRV(2.0, 0.75 / 2.0, 8))
    )

    private val jobSeq3 = listOf<JobStep>(
        JobStep(machine2, GammaRV(2.0, 1.2 / 2.0, 9)),
        JobStep(machine5, GammaRV(2.0, 0.25 / 2.0, 10)),
        JobStep(machine1, GammaRV(2.0, 0.7 / 2.0, 11)),
        JobStep(machine4, GammaRV(2.0, 0.9 / 2.0, 12)),
        JobStep(machine3, GammaRV(2.0, 1.0 / 2.0, 13))
    )

    // set up the sequences and the random selection of the plan
    private val sequences = listOf(jobSeq1, jobSeq2, jobSeq3)
    private val seqCDF = doubleArrayOf(0.3, 0.8, 1.0)
    private val seqList = REmpiricalList<List<JobStep>>(this, sequences, seqCDF)

    private val myArrivalGenerator = EntityGenerator(::Job, myTBA, myTBA)
    val generator: EventGeneratorCIfc
        get() = myArrivalGenerator

    // define the responses
    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private val systemTimeByType = mapOf<List<JobStep>, Response>(
        jobSeq1 to Response(this, "Type1:TimeInSystem"),
        jobSeq2 to Response(this, "Type2:TimeInSystem"),
        jobSeq3 to Response(this, "Type3:TimeInSystem"),
    )

    private inner class Job : Entity() {
        val jobShopProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            // determine the job sequence
            val sequence: List<JobStep> = seqList.randomElement
            // get the iterator
            val itr = sequence.iterator()
            // iterate through the sequence
            while (itr.hasNext()) {
                val jobStep = itr.next()
                val a = seize(jobStep.resource)
                delay(jobStep.processTime)
                release(a)
            }
            val systemTime = time - timeStamp
            timeInSystem.value = systemTime
            systemTimeByType[sequence]!!.value = systemTime
            wip.decrement()
        }
    }
}

fun main() {
    val m = Model("Job Shop Model")
    JobShop(m, "JobShop")
    m.numberOfReplications = 30
    m.lengthOfReplication = 365.0 * 8.0
    m.simulate()
    m.print()
}