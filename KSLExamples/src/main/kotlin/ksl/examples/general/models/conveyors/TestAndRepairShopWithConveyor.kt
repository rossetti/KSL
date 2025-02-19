package ksl.examples.general.models.conveyors

import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.Conveyor
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.KSL
import ksl.utilities.io.MarkDown
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

class TestAndRepairShopWithConveyor(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    // test plan 1, distribution j
    private val t11 = RandomVariable(this, LognormalRV(20.0, 4.1 * 4.1))
    private val t12 = RandomVariable(this, LognormalRV(12.0, 4.2 * 4.2))
    private val t13 = RandomVariable(this, LognormalRV(18.0, 4.3 * 4.3))
    private val t14 = RandomVariable(this, LognormalRV(16.0, 4.0 * 4.0))

    // test plan 2, distribution j
    private val t21 = RandomVariable(this, LognormalRV(12.0, 4.0 * 4.0))
    private val t22 = RandomVariable(this, LognormalRV(15.0, 4.0 * 4.0))

    // test plan 3, distribution j
    private val t31 = RandomVariable(this, LognormalRV(18.0, 4.2 * 4.2))
    private val t32 = RandomVariable(this, LognormalRV(14.0, 4.4 * 4.4))
    private val t33 = RandomVariable(this, LognormalRV(12.0, 4.3 * 4.3))

    // test plan 4, distribution j
    private val t41 = RandomVariable(this, LognormalRV(24.0, 4.0 * 4.0))
    private val t42 = RandomVariable(this, LognormalRV(30.0, 4.0 * 4.0))

    private val r1 = RandomVariable(this, TriangularRV(30.0, 60.0, 80.0))
    private val r2 = RandomVariable(this, TriangularRV(45.0, 55.0, 70.0))
    private val r3 = RandomVariable(this, TriangularRV(30.0, 40.0, 60.0))
    private val r4 = RandomVariable(this, TriangularRV(35.0, 65.0, 75.0))
    private val diagnosticTime = RandomVariable(this, ExponentialRV(30.0))
    private val moveTime = RandomVariable(this, UniformRV(2.0, 4.0))

    // define the resources
    private val myDiagnostics: ResourceWithQ = ResourceWithQ(this, "Diagnostics", capacity = 2)
    private val myTest1: ResourceWithQ = ResourceWithQ(this, "Test1")
    private val myTest2: ResourceWithQ = ResourceWithQ(this, "Test2")
    private val myTest3: ResourceWithQ = ResourceWithQ(this, "Test3")
    private val myRepair: ResourceWithQ = ResourceWithQ(this, "Repair", capacity = 3)

    // define steps to represent a plan
    inner class TestPlanStep(val resource: ResourceWithQ, val processTime: RandomIfc)

    // make all the plans
    private val testPlan1 = listOf(
        TestPlanStep(myTest2, t11), TestPlanStep(myTest3, t12),
        TestPlanStep(myTest2, t13), TestPlanStep(myTest1, t14), TestPlanStep(myRepair, r1)
    )
    private val testPlan2 = listOf(
        TestPlanStep(myTest3, t21),
        TestPlanStep(myTest1, t22), TestPlanStep(myRepair, r2)
    )
    private val testPlan3 = listOf(
        TestPlanStep(myTest1, t31), TestPlanStep(myTest3, t32),
        TestPlanStep(myTest1, t33), TestPlanStep(myRepair, r3)
    )
    private val testPlan4 = listOf(
        TestPlanStep(myTest2, t41),
        TestPlanStep(myTest3, t42), TestPlanStep(myRepair, r4)
    )

    // set up the sequences and the random selection of the plan
    private val sequences = listOf(testPlan1, testPlan2, testPlan3, testPlan4)
    private val planCDf = doubleArrayOf(0.25, 0.375, 0.75, 1.0)
    private val planList = REmpiricalList<List<TestPlanStep>>(this, sequences, planCDf)

    private val cellSizes = mapOf(testPlan1 to 1, testPlan2 to 2, testPlan3 to 2, testPlan4 to 2)

    // define the random variables
    private val tba = ExponentialRV(20.0)

    private val myArrivalGenerator = EntityGenerator(this::Part, tba, tba)

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

    private val loopConveyor: Conveyor = Conveyor.builder(this, "LoopConveyor")
//        .conveyorType(Conveyor.Type.NON_ACCUMULATING)
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(10.0)
        .cellSize(1)
        .maxCellsAllowed(2)
        .firstSegment(myDiagnostics, myTest1, 20)
        .nextSegment(myTest2, 20)
        .nextSegment(myRepair, 15)
        .nextSegment(myTest3, 45)
        .nextSegment(myDiagnostics, 30)
        .build()

    init {
        loopConveyor.accessQueueAt(myRepair).defaultReportingOption = false
//        println(loopConveyor)
    }

    // define the process
    private inner class Part : Entity() {

        // determine the test plan
        val plan: List<TestPlanStep> = planList.randomElement

        val testAndRepairProcess: KSLProcess = process(isDefaultProcess = true) {
            wip.increment()
            timeStamp = time
            //every part goes to diagnostics
            use(myDiagnostics, delayDuration = diagnosticTime)
            // get the iterator
            val itr = plan.iterator()
            //var cr = requestConveyor(loopConveyor, myDiagnostics, cellSizes[plan]!!)
            // iterate through the plan
            var entryLocation = myDiagnostics
            while (itr.hasNext()) {
                val tp = itr.next()
                val cellsNeeded = cellSizes[plan]!!
                val cr = requestConveyor(loopConveyor, entryLocation, cellsNeeded)
                rideConveyor(tp.resource)
                exitConveyor(cr)
                use(tp.resource, delayDuration = tp.processTime)
                entryLocation = tp.resource
            }
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }

}

fun main() {
    val m = Model()
    val tq = TestAndRepairShopWithConveyor(m, name = "TestAndRepairWithConveyor")

    m.numberOfReplications = 10
    m.lengthOfReplication = 52.0 * 5.0 * 2.0 * 480.0
//        m.numberOfReplications = 1
//    m.lengthOfReplication = 1000000.0
    m.simulate()
    m.print()
    val r = m.simulationReporter
    r.writeHalfWidthSummaryReportAsMarkDown(KSL.out, df = MarkDown.D3FORMAT)
}