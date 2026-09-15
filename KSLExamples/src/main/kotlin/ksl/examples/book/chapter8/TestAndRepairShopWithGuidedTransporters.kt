package ksl.examples.book.chapter8

import ksl.modeling.elements.EventGeneratorRVCIfc
import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.*
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.IdleDispositionRuleIfc
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.modeling.variable.*
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.TriangularRV

/**
 *  The test-and-repair shop of chapter eight, with its three transport workers moved off a
 *  distance model and onto a guide path.
 *
 *  This is the comparison the guided-path subsystem exists to make possible, so it is worth being
 *  exact about what differs and what does not. **Everything about the work is identical** to
 *  [TestAndRepairShopWithMovableResources]: the same four test plans with the same probabilities,
 *  the same processing-time distributions, the same repair times, the same arrival process, the
 *  same five stations, the same three transporters, and the same walking-speed distribution. Only
 *  the *space* changes.
 *
 *  In the free-path model a transport worker travels from wherever it is to wherever it is needed,
 *  through anything in the way, over a declared point-to-point distance. Here it must follow a
 *  physical aisle, and the aisle is one-way, so a worker that has just passed a station has to go
 *  the whole way round to reach it again -- and it can be stopped by another worker ahead of it.
 *
 *  Both of those are real. Neither appears in the free-path model at any fleet size, which is why a
 *  free-path answer is optimistic about congestion rather than merely approximate: adding
 *  transporters to a distance model always helps, and adding them to an aisle eventually does not.
 *
 *  **The layout.** A single one-way loop through the five stations, with the leg lengths taken from
 *  the free-path model's own distances along that cycle, so the two models agree about how far
 *  apart things are and disagree only about what a worker must do to get between them. Each
 *  transporter has a parking spur of its own, which is the source text's remedy for the idle
 *  vehicle standing in the traffic: without them the first worker to finish would stop wherever it
 *  happened to be, and everything behind it would stop too.
 *
 *  @param parent the containing model element
 *  @param numTransporters how many carts to run, which is the parameter worth sweeping
 *  @param name a name for the model
 */
class TestAndRepairShopWithGuidedTransporters @JvmOverloads constructor(
    parent: ModelElement,
    numTransporters: Int = 3,
    timeBtwArrivals: Double = 20.0,
    name: String? = null,
    aisleNetwork: GuidedPathNetwork? = null,
    transporterVelocity: RVariableIfc? = null,
    transporterHomes: List<String>? = null,
    transporterPhysicalLength: Double? = null,
    zoneControlRule: ZoneControlRuleIfc = EndOfZoneControl(),
    idleDispositionRule: IdleDispositionRuleIfc = ReturnToHomeBaseRule()
) : ProcessModel(parent, name) {

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

    // Named, so that a study -- or the vehicle-examples bundle's catalog -- can nominate its mean
    // as an input. An unnamed random variable has no stable key to nominate.
    private val diagnosticTime = RandomVariable(this, ExponentialRV(30.0), name = "DiagnosticTime")

    /** How long diagnosis takes; its mean is the shop's headline load parameter. */
    val diagnosticTimeRV: RandomVariableCIfc
        get() = diagnosticTime

    // The same walking speed as the free-path model, in meters per minute. Sharing it is what makes
    // the comparison about the space rather than about how fast anybody walks.
    private val myWalkingSpeedRV = TriangularRV(22.86, 45.72, 52.5)

    /** Station names, which double as the guide path's addresses. */
    companion object {
        const val DIAGNOSTIC: String = "DiagnosticStation"
        const val TEST1: String = "TestStation1"
        const val TEST2: String = "TestStation2"
        const val TEST3: String = "TestStation3"
        const val REPAIR: String = "RepairStation"

        /** The aisle is discretized at five meters, which divides every leg of the loop exactly. */
        const val ZONE_LENGTH: Double = 5.0

        /**
         *  The one-way aisle through the five stations, plus a parking spur per transporter.
         *
         *  Leg lengths are the free-path model's own distances along this cycle, so the two models
         *  place the stations the same distance apart. What differs is that here a worker can only
         *  travel one way round, and can be held up by another worker in front of it.
         */
        fun createNetwork(numSpurs: Int, networkName: String = "ShopAisle"): GuidedPathNetwork {
            var b = GuidedPathNetwork.builder(networkName)
                .intersection(DIAGNOSTIC, x = 0.0, y = 0.0)
                .intersection(TEST1, x = 40.0, y = 0.0)
                .intersection(TEST2, x = 50.0, y = 0.0)
                .intersection(TEST3, x = 50.0, y = -65.0)
                .intersection(REPAIR, x = 25.0, y = -65.0)
                .link("Aisle1", DIAGNOSTIC, TEST1, length = 40.0, zoneLength = ZONE_LENGTH, beginDirection = 0.0)
                .link("Aisle2", TEST1, TEST2, length = 10.0, zoneLength = ZONE_LENGTH, beginDirection = 0.0)
                .link("Aisle3", TEST2, TEST3, length = 65.0, zoneLength = ZONE_LENGTH, beginDirection = 270.0)
                .link("Aisle4", TEST3, REPAIR, length = 25.0, zoneLength = ZONE_LENGTH, beginDirection = 180.0)
                .link("Aisle5", REPAIR, DIAGNOSTIC, length = 110.0, zoneLength = ZONE_LENGTH, beginDirection = 90.0)
            // A parking spur per transporter, off the diagnostic end of the aisle. An idle worker
            // left standing in the aisle would block everything behind it, with no error and a run
            // that finishes looking entirely reasonable.
            for (i in 1..numSpurs) {
                b = b.intersection("Park$i", x = -10.0, y = -10.0 * i)
                b = b.link(
                    "ParkSpur$i", DIAGNOSTIC, "Park$i", length = ZONE_LENGTH, zoneLength = ZONE_LENGTH,
                    type = LinkType.SPUR, beginDirection = 180.0
                )
            }
            return b.build()
        }
    }

    /**
     *  The aisle the workers walk. Defaults to this chapter's own layout; a caller may supply
     *  another, which is how the same shop is compared against the guided-path model of the same
     *  system built in the reference implementation. The process below is untouched by the choice:
     *  what changes is the
     *  space, which is the whole point of comparing.
     */
    val network: GuidedPathNetwork = aisleNetwork ?: createNetwork(numTransporters)

    init {
        spatialModel = network
    }

    val transportSystem = GuidedPathTransportSystem(this, network, name = "ShopTransport")

    /** Where each worker starts, and returns to when the idle rule says so. */
    private val homes: List<String> =
        transporterHomes ?: (1..numTransporters).map { "Park$it" }

    init {
        require(homes.size == numTransporters) {
            "There are $numTransporters transporters but ${homes.size} home locations were given."
        }
    }

    private val carts: List<GuidedTransporter> = (1..numTransporters).map { i ->
        GuidedTransporter(
            transportSystem, TransporterPlacement.At(homes[i - 1]),
            transporterVelocity ?: myWalkingSpeedRV, 1, zoneControlRule, name = "Worker$i",
            physicalLength = transporterPhysicalLength
        ).apply { homeBase = homes[i - 1] }
    }

    /** The fleet, asked for by the group rather than by name, as in the free-path model. */
    val transportWorkers = GuidedTransporterPoolWithQ(
        this, transportSystem, carts,
        ClosestByNetworkDistanceRule(), idleDispositionRule, "TransportWorkerPool"
    )

    private val diagnosticWorkers: ResourceWithQ = ResourceWithQ(this, "DiagnosticWorkers", capacity = 2)
    private val myTest1: ResourceWithQ = ResourceWithQ(this, "Test1")
    private val myTest2: ResourceWithQ = ResourceWithQ(this, "Test2")
    private val myTest3: ResourceWithQ = ResourceWithQ(this, "Test3")
    private val repairWorkers: ResourceWithQ = ResourceWithQ(this, "RepairWorkers", capacity = 3)

    // Readable so that a study can ask what each station cost, which a model whose resources are
    // all private cannot be asked at all.
    val diagnostics: ResourceCIfc get() = diagnosticWorkers
    val test1: ResourceCIfc get() = myTest1
    val test2: ResourceCIfc get() = myTest2
    val test3: ResourceCIfc get() = myTest3
    val repair: ResourceCIfc get() = repairWorkers

    val diagnosticsQ: QueueCIfc<ProcessModel.Entity.Request> get() = diagnosticWorkers.waitingQ
    val test1Q: QueueCIfc<ProcessModel.Entity.Request> get() = myTest1.waitingQ
    val test2Q: QueueCIfc<ProcessModel.Entity.Request> get() = myTest2.waitingQ
    val test3Q: QueueCIfc<ProcessModel.Entity.Request> get() = myTest3.waitingQ
    val repairQ: QueueCIfc<ProcessModel.Entity.Request> get() = repairWorkers.waitingQ

    /** One step of a test plan: which machine, how long, and where it is on the aisle. */
    inner class TestPlanStep(
        val testMachine: ResourceWithQ,
        val processTime: RandomVariable,
        val testStation: String
    )

    private val testPlan1 = listOf(
        TestPlanStep(myTest2, t11, TEST2), TestPlanStep(myTest3, t12, TEST3),
        TestPlanStep(myTest2, t13, TEST2), TestPlanStep(myTest1, t14, TEST1)
    )
    private val testPlan2 = listOf(
        TestPlanStep(myTest3, t21, TEST3),
        TestPlanStep(myTest1, t22, TEST1)
    )
    private val testPlan3 = listOf(
        TestPlanStep(myTest1, t31, TEST1), TestPlanStep(myTest3, t32, TEST3),
        TestPlanStep(myTest1, t33, TEST1)
    )
    private val testPlan4 = listOf(
        TestPlanStep(myTest2, t41, TEST2),
        TestPlanStep(myTest3, t42, TEST3)
    )

    private val repairTimes = mapOf(
        testPlan1 to r1,
        testPlan2 to r2,
        testPlan3 to r3,
        testPlan4 to r4
    )

    private val sequences = listOf(testPlan1, testPlan2, testPlan3, testPlan4)
    private val planCDf = doubleArrayOf(0.25, 0.375, 0.75, 1.0)
    private val planList = REmpiricalList<List<TestPlanStep>>(this, sequences, planCDf)

    private val tba = ExponentialRV(timeBtwArrivals)
    private val myArrivalGenerator = EntityGenerator(::Part, tba, tba)
    val generator: EventGeneratorRVCIfc
        get() = myArrivalGenerator

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

    /**
     *  How long a part spent aboard a worker, summed over its journeys: from the instant a worker
     *  was allocated to it until it was set down, which is what the reference implementation books
     *  as an entity's transfer
     *  time. The wait *for* a worker is not part of it -- that is queueing, and is measured by the
     *  transport pool's own queue.
     */
    private val myTransferTime: Response = Response(this, "TransferTime")
    val transferTime: ResponseCIfc
        get() = myTransferTime

    private val myNumberIn: Counter = Counter(this, "NumberIn")
    val numberIn: CounterCIfc
        get() = myNumberIn
    private val myNumberOut: Counter = Counter(this, "NumberOut")
    val numberOut: CounterCIfc
        get() = myNumberOut

    private inner class Part : Entity() {
        val plan: List<TestPlanStep> = planList.randomElement

        val testAndRepairProcess: KSLProcess = process(isDefaultProcess = true) {
            // Where the part is has to be tracked explicitly: a transporter is asked to come to a
            // named junction, and the part is not carried from wherever it happens to be but from
            // the station it is standing at.
            var at = DIAGNOSTIC
            var carried = 0.0
            currentLocation = network.requireLocation(DIAGNOSTIC)
            wip.increment()
            myNumberIn.increment()
            timeStamp = time
            use(diagnosticWorkers, delayDuration = diagnosticTime)
            for (tp in plan) {
                val leg = guidedTransport(
                    transportWorkers, destination = tp.testStation, pickupLocation = at
                )
                carried += leg.approachTime + leg.rideTime
                at = tp.testStation
                use(tp.testMachine, delayDuration = tp.processTime)
            }
            val lastLeg = guidedTransport(transportWorkers, destination = REPAIR, pickupLocation = at)
            carried += lastLeg.approachTime + lastLeg.rideTime
            use(repairWorkers, delayDuration = repairTimes[plan]!!)
            myTransferTime.value = carried
            timeInSystem.value = time - timeStamp
            myNumberOut.increment()
            wip.decrement()
        }
    }
}
