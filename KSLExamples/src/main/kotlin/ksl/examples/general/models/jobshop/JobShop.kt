package ksl.examples.general.models.jobshop

import ksl.examples.book.chapter7.TestAndRepairShop
import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.GammaRV

class JobShop(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val myTBA = RandomVariable(this, ExponentialRV(0.25, 1))

    // define the resources
    private val machine1: ResourceWithQ = ResourceWithQ(this, capacity = 3, name = "Machine1")
    private val machine2: ResourceWithQ = ResourceWithQ(this, capacity = 2, name = "Machine2")
    private val machine3: ResourceWithQ = ResourceWithQ(this, capacity = 4, name = "Machine3")
    private val machine4: ResourceWithQ = ResourceWithQ(this, capacity = 3, name = "Machine4")
    private val machine5: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "Machine5")

    // define steps to represent a sequence
    inner class JobStep(val resource: ResourceWithQ, random: RandomIfc) {
        val processTime = RandomVariable(this@JobShop, random)
    }

    val jobSeq1 = listOf<JobStep>(
        JobStep(machine3, GammaRV(2.0, 0.5 / 2.0)),
        JobStep(machine1, GammaRV(2.0, 0.6 / 2.0)),
        JobStep(machine2, GammaRV(2.0, 0.85 / 2.0)),
        JobStep(machine5, GammaRV(2.0, 0.5 / 2.0))
    )

    val jobSeq2 = listOf<JobStep>(
        JobStep(machine4, GammaRV(2.0, 1.1 / 2.0)),
        JobStep(machine1, GammaRV(2.0, 0.8 / 2.0)),
        JobStep(machine3, GammaRV(2.0, 0.75 / 2.0))
    )

    val jobSeq3 = listOf<JobStep>(
        JobStep(machine2, GammaRV(2.0, 1.2 / 2.0)),
        JobStep(machine5, GammaRV(2.0, 0.25 / 2.0)),
        JobStep(machine1, GammaRV(2.0, 0.7 / 2.0)),
        JobStep(machine4, GammaRV(2.0, 0.9 / 2.0)),
        JobStep(machine3, GammaRV(2.0, 1.0 / 2.0))
    )

    // set up the sequences and the random selection of the plan
    private val sequences = listOf(jobSeq1, jobSeq2, jobSeq3)
    private val seqCDF = doubleArrayOf(0.3, 0.8, 1.0)
    private val seqList = REmpiricalList<List<JobStep>>(this, sequences, seqCDF)
}