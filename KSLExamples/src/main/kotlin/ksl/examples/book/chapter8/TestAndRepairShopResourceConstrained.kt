package ksl.examples.book.chapter8

import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.*
import ksl.modeling.variable.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

class TestAndRepairShopResourceConstrained(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    // define the random variables
    private val tba = ExponentialRV(20.0)
    // test plan 1, distribution j
    private val t11 = RandomVariable(this, LognormalRV(20.0, 4.1*4.1))
    private val t12 = RandomVariable(this, LognormalRV(12.0, 4.2*4.2))
    private val t13 = RandomVariable(this, LognormalRV(18.0, 4.3*4.3))
    private val t14 = RandomVariable(this, LognormalRV(16.0, 4.0*4.0))
    // test plan 2, distribution j
    private val t21 = RandomVariable(this, LognormalRV(12.0, 4.0*4.0))
    private val t22 = RandomVariable(this, LognormalRV(15.0, 4.0*4.0))
    // test plan 3, distribution j
    private val t31 = RandomVariable(this, LognormalRV(18.0, 4.2*4.2))
    private val t32 = RandomVariable(this, LognormalRV(14.0, 4.4*4.4))
    private val t33 = RandomVariable(this, LognormalRV(12.0, 4.3*4.3))
    // test plan 4, distribution j
    private val t41 = RandomVariable(this, LognormalRV(24.0, 4.0*4.0))
    private val t42 = RandomVariable(this, LognormalRV(30.0, 4.0*4.0))

    private val r1 = RandomVariable(this, TriangularRV(30.0, 60.0, 80.0))
    private val r2 = RandomVariable(this, TriangularRV(45.0, 55.0, 70.0))
    private val r3 = RandomVariable(this, TriangularRV(30.0, 40.0, 60.0))
    private val r4 = RandomVariable(this, TriangularRV(35.0, 65.0, 75.0))

    private val diagnosticTime = RandomVariable(this, ExponentialRV(30.0))
    private val moveTime = RandomVariable(this, UniformRV(2.0, 4.0))

    // define the resources
    private val dw1 = Resource(this, name = "DiagnosticsWorker1")
    private val dw2 = Resource(this, name = "DiagnosticsWorker2")
    private val diagnosticWorkers: ResourcePoolWithQ = ResourcePoolWithQ(
        this,
        listOf(dw1, dw2), name = "DiagnosticWorkersPool"
    )
    private val myDiagnosticMachines: ResourceWithQ = ResourceWithQ(this, "DiagnosticMachines", capacity = 2)

    private val tw1 = ResourceWithQ(this, name = "TestWorker1")
    private val myTest1: ResourceWithQ = ResourceWithQ(this, "Test1", capacity = 1)
    private val tw2 = ResourceWithQ(this, name = "TestWorker2")
    private val myTest2: ResourceWithQ = ResourceWithQ(this, "Test2", capacity = 1)
    private val tw3 = ResourceWithQ(this, name = "TestWorker3")
    private val myTest3: ResourceWithQ = ResourceWithQ(this, "Test3", capacity = 1)

    private val rw1 = Resource(this, name = "RepairWorker1")
    private val rw2 = Resource(this, name = "RepairWorker2")
    private val rw3 = Resource(this, name = "RepairWorker3")
    private val repairWorkers: ResourcePoolWithQ = ResourcePoolWithQ(
        this,
        listOf(rw1, rw2, rw3), name = "RepairWorkersPool"
    )

    private val transportWorkers: ResourcePoolWithQ = ResourcePoolWithQ(
        this, listOf(tw1, tw2, tw3, dw1, dw2, rw1, rw2, rw3), name = "TransportWorkersPool"
    )

    // define steps to represent a plan
    inner class TestPlanStep(val testMachine: ResourceWithQ, val processTime: RandomIfc, val tester: ResourceWithQ)

    // make all the plans
    private val testPlan1 = listOf(
        TestPlanStep(myTest2, t11, tw2), TestPlanStep(myTest3, t12, tw3),
        TestPlanStep(myTest2, t13, tw2), TestPlanStep(myTest1, t14, tw1)
    )
    private val testPlan2 = listOf(
        TestPlanStep(myTest3, t21, tw3),
        TestPlanStep(myTest1, t22, tw1)
    )
    private val testPlan3 = listOf(
        TestPlanStep(myTest1, t31, tw1), TestPlanStep(myTest3, t32, tw3),
        TestPlanStep(myTest1, t33, tw1)
    )
    private val testPlan4 = listOf(
        TestPlanStep(myTest2, t41, tw2),
        TestPlanStep(myTest3, t42, tw3)
    )

    private val repairTimes = mapOf(
        testPlan1 to r1,
        testPlan2 to r2,
        testPlan3 to r3,
        testPlan4 to r4
    )

    // set up the sequences and the random selection of the plan
    private val sequences = listOf(testPlan1, testPlan2, testPlan3, testPlan4)
    private val planCDf = doubleArrayOf(0.25, 0.375, 0.75, 1.0)
    private val planList = REmpiricalList<List<TestPlanStep>>(this, sequences, planCDf)

    private val myArrivalGenerator = EntityGenerator(::Part, processName = "Test and Repair Process", tba, tba)
    val generator: EventGeneratorCIfc
        get() = myArrivalGenerator

    // define the responses
    private val wip: TWResponse = TWResponse(this, "${this.name}:NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem
    private val myContractLimit: IndicatorResponse =
        IndicatorResponse({ x -> x <= 480.0 }, timeInSystem, "ProbWithinLimit")
    val probWithinLimit: ResponseCIfc
        get() = myContractLimit

    // define the process
    private inner class Part : Entity() {
        val testAndRepairProcess: KSLProcess = process("Test and Repair Process") {
            wip.increment()
            timeStamp = time
            //every part goes to diagnostics
            val dd1 = seize(diagnosticWorkers) // worker is needed to use the machine
            val d1 = seize(myDiagnosticMachines)
            delay(diagnosticTime)
            release(d1) // release machine first, because worker is needed to release
            release(dd1)
            val twa = seize(transportWorkers)
            delay(moveTime)
            release(twa)
            // determine the test plan
            val plan: List<TestPlanStep> = planList.randomElement
            // get the iterator
            val itr = plan.iterator()
            // iterate through the plan
            while (itr.hasNext()) {
                val tp = itr.next()
                // visit tester
                val tt1 = seize(tp.tester)// tester needed first to work on machine
                val t1 = seize(tp.testMachine)
                delay(tp.processTime)
                release(t1)
                release(tt1)
                val twa1 = seize(transportWorkers)
                delay(moveTime)
                release(twa1)
            }
            // visit repair
            val rw = seize(repairWorkers)
            delay(repairTimes[plan]!!)
            release(rw)
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }

        init {
            initialProcess = testAndRepairProcess
        }
    }
}