package ksl.examples.book.chapter8

import ksl.modeling.elements.EventGeneratorCIfc
import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.*
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.MovableResourcePoolWithQ
import ksl.modeling.variable.*
import ksl.simulation.ModelElement
import ksl.utilities.random.RandomIfc
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.TriangularRV

class TestAndRepairShopWithMovableResources(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

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

    // velocity is in meters/min
    private val myWalkingSpeedRV = TriangularRV(22.86, 45.72, 52.5)
    private val dm = DistancesModel()
    private val diagnosticStation = dm.Location("DiagnosticStation")
    private val testStation1 = dm.Location("TestStation1")
    private val testStation2 = dm.Location("TestStation2")
    private val testStation3 = dm.Location("TestStation3")
    private val repairStation = dm.Location("RepairStation")

    init {
        // distance is in meters
        dm.addDistance(diagnosticStation, testStation1, 40.0)
        dm.addDistance(diagnosticStation, testStation2, 70.0)
        dm.addDistance(diagnosticStation, testStation3, 90.0)
        dm.addDistance(diagnosticStation, repairStation, 100.0)

        dm.addDistance(testStation1, diagnosticStation, 43.0)
        dm.addDistance(testStation1, testStation2, 10.0)
        dm.addDistance(testStation1, testStation3, 60.0)
        dm.addDistance(testStation1, repairStation, 80.0)

        dm.addDistance(testStation2, diagnosticStation, 70.0)
        dm.addDistance(testStation2, testStation1, 15.0)
        dm.addDistance(testStation2, testStation3, 65.0)
        dm.addDistance(testStation2, repairStation, 20.0)

        dm.addDistance(testStation3, diagnosticStation, 90.0)
        dm.addDistance(testStation3, testStation1, 80.0)
        dm.addDistance(testStation3, testStation2, 60.0)
        dm.addDistance(testStation3, repairStation, 25.0)

        dm.addDistance(repairStation, diagnosticStation, 110.0)
        dm.addDistance(repairStation, testStation1, 85.0)
        dm.addDistance(repairStation, testStation2, 25.0)
        dm.addDistance(repairStation, testStation3, 30.0)

        dm.defaultVelocity = myWalkingSpeedRV
        spatialModel = dm
    }

    private val diagnosticWorkers: ResourceWithQ = ResourceWithQ(this, "DiagnosticWorkers", capacity = 2)
    private val myTest1: ResourceWithQ = ResourceWithQ(this, "Test1")
    private val myTest2: ResourceWithQ = ResourceWithQ(this, "Test2")
    private val myTest3: ResourceWithQ = ResourceWithQ(this, "Test3")
    private val repairWorkers: ResourceWithQ = ResourceWithQ(this, "RepairWorkers", capacity = 3)

    // create a pool of movable resources
    private val transportWorkers = MovableResourcePoolWithQ(
        this, 3, diagnosticStation, myWalkingSpeedRV, name = "TransportWorkerPool")

    // define steps to represent a plan, include location information
    inner class TestPlanStep(
        val testMachine: ResourceWithQ,
        val processTime: RandomVariable,
        val testStation: LocationIfc)

    // make all the plans
    private val testPlan1 = listOf(
        TestPlanStep(myTest2, t11, testStation2), TestPlanStep(myTest3, t12, testStation3),
        TestPlanStep(myTest2, t13, testStation2), TestPlanStep(myTest1, t14, testStation1)
    )
    private val testPlan2 = listOf(
        TestPlanStep(myTest3, t21, testStation3),
        TestPlanStep(myTest1, t22, testStation1)
    )
    private val testPlan3 = listOf(
        TestPlanStep(myTest1, t31, testStation1), TestPlanStep(myTest3, t32, testStation3),
        TestPlanStep(myTest1, t33, testStation1)
    )
    private val testPlan4 = listOf(
        TestPlanStep(myTest2, t41, testStation2),
        TestPlanStep(myTest3, t42, testStation3)
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

    // define the random variables
    private val tba = ExponentialRV(20.0)
    private val myArrivalGenerator = EntityGenerator(::Part, tba, tba)
    val generator: EventGeneratorCIfc
        get() = myArrivalGenerator

    // define the responses
    private val wip: TWResponse = TWResponse(this, "NumInSystem")
    val numInSystem: TWResponseCIfc
        get() = wip
    private val timeInSystem: Response = Response(this, "TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem
    private val myContractLimit: IndicatorResponse =
        IndicatorResponse({ x -> x <= 480.0 }, timeInSystem, "ProbWithinLimit")
    val probWithinLimit: ResponseCIfc
        get() = myContractLimit

    // define the process
    private inner class Part : Entity() {
        val plan: List<TestPlanStep> = planList.randomElement

        val testAndRepairProcess: KSLProcess = process(isDefaultProcess = true) {
            currentLocation = diagnosticStation
            wip.increment()
            timeStamp = time
            //every part goes to diagnostics
            use(diagnosticWorkers, delayDuration = diagnosticTime)
            // get the iterator
            val itr = plan.iterator()
            // iterate through the plan
            while (itr.hasNext()) {
                val tp = itr.next()
                // goto the location
                transportWith(transportWorkers, toLoc = tp.testStation)
                // use the tester
                use(tp.testMachine, delayDuration = tp.processTime)
            }
            // visit repair
            transportWith(transportWorkers, toLoc = repairStation)
            use(repairWorkers, delayDuration = repairTimes[plan]!! )
            timeInSystem.value = time - timeStamp
            wip.decrement()
        }
    }
}