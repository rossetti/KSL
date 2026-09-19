package ksl.examples.book.chapter7

import ksl.modeling.elements.EventGeneratorRVCIfc
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceCIfc
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.IdleDisposition
import ksl.modeling.guidedpath.rules.IdleDispositionRuleIfc
import ksl.modeling.guidedpath.rules.MoveToStagingAreaRule
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.modeling.guidedpath.rules.ZoneControlRuleIfc
import ksl.modeling.queue.QueueCIfc
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.TriangularRV
import ksl.utilities.random.rvariable.UniformRV

/**
 *  Exercise 7.15: the two-colour painting flow line of Exercise 5.180, with every part movement
 *  made by one of three AGVs running on a one-way guide path.
 *
 *  The shop is the one the earlier exercise describes and is unchanged here. Parts arrive
 *  exponentially with a mean of 28 minutes; 30% of them are the new colour. Every part is worked at
 *  the workstation, then painted -- old parts at the old paint station, new parts at a second one
 *  that exists only for them -- then packed at the single packing station, then leaves. Each station
 *  has one worker.
 *
 *  What is new is that a part cannot move unless a vehicle carries it, and a vehicle cannot move
 *  unless the aisle ahead of it is free.
 *
 *  ## The guide path
 *
 *  Sixteen links, every one divided into zones of ten feet, and all of them one-way except the spur
 *  -- which is what stops three vehicles deadlocking against each other. Vehicles travel at 100 feet
 *  per minute and are one zone long, and a zone is released as a vehicle *starts* to leave it rather
 *  than when it has cleared it.
 *
 *  ```
 *              I6  (Enter)
 *              |
 *              | L15, 230, spur
 *              |
 *  I1 --L1--> I7 --L2--> I2 --L5--> I8 --L6--> I3 --L7--> I5
 *  ^  (Wkstn)  |  (Paint)            |  (Pack)              | (Exit)
 *  |           +---L3--> I4 --L4-----+                      |
 *  |               (New paint)       | L9                   | L8
 *  |                                 v                      v
 *  |    L16          L14        L13  +--------------------> I9
 *  +--- I11 <------- I10 <-----------+  <-- L13 runs I10 -> I7
 *                    ^  ^                   L11 runs I9 -> I12
 *                    |  | L12                             |
 *                    +- I12  (Staging)  <-----------------+
 *  ```
 *
 *  The drawing is a sketch; the link table below is the model. Two things about it matter and are
 *  easy to miss. The **spur** to the entry station is 230 feet, and a vehicle down it holds the
 *  whole of it, so collecting an arriving part shuts the only way in and out of I6 for the round
 *  trip. And the **staging area** at I12 is deliberately off every shortest path: I9 to I10 is 110
 *  feet direct and 130 through I12, so a vehicle waiting there is in nobody's way -- which is the
 *  entire reason the exercise puts it there.
 *
 *  ## Where an idle vehicle goes, and what "pending" means
 *
 *  "Whenever a vehicle completes a transport, a check should be made of the number of requests
 *  pending. If there are no requests pending, the AGV should be sent to the staging area."
 *
 *  A request that is queued for a vehicle is plainly pending. A request that has already been given
 *  a vehicle, which is at that moment driving empty across the network to collect it, is arguably
 *  pending too -- the part has asked and has not been picked up. The two readings are different
 *  policies and they are far apart in this system, because an empty move here averages about five
 *  minutes while a part is only rarely left queueing at all. [PendingRequestTest] is which reading
 *  to use, and the two are compared rather than one being assumed.
 *
 *  Only one vehicle fits at I12. A second one sent there arrives at the end of link 11 and stops,
 *  and a third stops behind it -- so the fleet queues along link 11 exactly as the exercise says it
 *  should, without anything in the model arranging it.
 *
 *  A vehicle waiting in that queue is still in the pool and can still be allocated, which is worth
 *  understanding before reading the fleet statistics. It is not free to leave: link 11 is one-way,
 *  so the only route out of the queue is forward through I12, and until whatever is sitting there
 *  moves, a vehicle that has been allocated out of the queue cannot go and collect anybody. It also
 *  reports itself as being *at* I12 while it waits, because a transporter part way along a link is
 *  located at that link's far end, so the allocation rule ranks it exactly as it ranks the vehicle
 *  that has actually arrived.
 *
 *  @param parent the containing model element
 *  @param numAgvs how many vehicles to run, three in the exercise
 *  @param timeBtwArrivals mean minutes between part arrivals
 *  @param name a name for the model
 *  @param guidePath the network to run on, defaulting to the exercise's own
 *  @param agvVelocity how fast a vehicle travels, in feet per minute
 *  @param agvPhysicalLength a vehicle's length in feet, when it is to be sized by length rather than
 *    by whole zones
 *  @param zoneControlRule when a vehicle gives up the zone behind it
 *  @param pendingRequestTest which reading of "no requests pending" sends a vehicle to the staging
 *    area
 *  @param idleDispositionRule where a vehicle waits when it has nothing to carry, overriding
 *    [pendingRequestTest] entirely when one is given
 */
class PaintingFlowLineWithAgvs @JvmOverloads constructor(
    parent: ModelElement,
    numAgvs: Int = 3,
    timeBtwArrivals: Double = 28.0,
    name: String? = null,
    guidePath: GuidedPathNetwork? = null,
    agvVelocity: RVariableIfc? = null,
    agvPhysicalLength: Double? = null,
    zoneControlRule: ZoneControlRuleIfc = StartOfZoneControl(),
    pendingRequestTest: PendingRequestTest = PendingRequestTest.UNTIL_COLLECTED,
    idleDispositionRule: IdleDispositionRuleIfc? = null
) : ProcessModel(parent, name) {

    /**
     *  Which requests count as pending when an idle vehicle decides whether to leave for the
     *  staging area.
     *
     *  The exercise does not say, and the choice is not a detail: it decides how often the fleet
     *  makes the trip at all, and three vehicles converging on a staging area that holds one spend
     *  the difference queueing for it.
     */
    enum class PendingRequestTest {

        /**
         *  A part counts only while it is queued with no vehicle assigned. A vehicle already on its
         *  way to collect somebody leaves nothing pending, so the next vehicle to come free departs
         *  for the staging area.
         */
        QUEUED_ONLY,

        /**
         *  A part counts from the moment it asks until a vehicle reaches it. A vehicle that comes
         *  free while another is still driving out to a pickup stays where it is.
         */
        UNTIL_COLLECTED
    }

    companion object {

        /** Station names, which double as the guide path's addresses. */
        const val ENTER: String = "Enter"
        const val WORK_STATION: String = "WorkStation"
        const val PAINT: String = "Paint"
        const val NEW_PAINT: String = "NewPaint"
        const val PACK: String = "Pack"
        const val EXIT: String = "Exit"
        const val STAGING: String = "StagingArea"

        /** Feet per minute. */
        const val VELOCITY: Double = 100.0

        /** "The links should all be divided into zones of 10 feet each." */
        const val ZONE_LENGTH: Double = 10.0

        /** "Once the truck reaches the pickup/drop-off station, it requires a load/unload time of two minutes." */
        const val LOAD_UNLOAD_MINUTES: Double = 2.0

        /**
         *  The sixteen links of the exercise's figure, declared by zone count as the source does.
         *
         *  Link lengths are the zone count times ten feet. Directions are the bearings the source
         *  gives, with 0 east and 90 north; they are recorded because they are part of the layout
         *  even though the router works on lengths.
         */
        fun createNetwork(networkName: String = "AgvNetwork"): GuidedPathNetwork =
            GuidedPathNetwork.builder(networkName)
                .linkWithZoneCount("L1", "I1", "I7", 80.0, 8, beginDirection = 270.0)
                .linkWithZoneCount("L2", "I7", "I2", 40.0, 4, beginDirection = 270.0)
                .linkWithZoneCount("L3", "I7", "I4", 40.0, 4, beginDirection = 90.0)
                .linkWithZoneCount("L4", "I4", "I8", 80.0, 8, beginDirection = 0.0)
                .linkWithZoneCount("L5", "I2", "I8", 60.0, 6, beginDirection = 0.0)
                .linkWithZoneCount("L6", "I8", "I3", 70.0, 7, beginDirection = 0.0)
                .linkWithZoneCount("L7", "I3", "I5", 250.0, 25, beginDirection = 90.0)
                .linkWithZoneCount("L8", "I5", "I9", 220.0, 22, beginDirection = 180.0)
                .linkWithZoneCount("L9", "I8", "I9", 100.0, 10, beginDirection = 90.0)
                .linkWithZoneCount("L10", "I9", "I10", 110.0, 11, beginDirection = 180.0)
                .linkWithZoneCount("L11", "I9", "I12", 100.0, 10, beginDirection = 90.0)
                .linkWithZoneCount("L12", "I12", "I10", 30.0, 3, beginDirection = 180.0)
                .linkWithZoneCount("L13", "I10", "I7", 80.0, 8, beginDirection = 270.0)
                .linkWithZoneCount("L14", "I10", "I11", 40.0, 4, beginDirection = 180.0)
                .linkWithZoneCount("L15", "I11", "I6", 230.0, 23, type = LinkType.SPUR, beginDirection = 90.0)
                .linkWithZoneCount("L16", "I11", "I1", 80.0, 8, beginDirection = 180.0)
                .station(ENTER, "I6")
                .station(WORK_STATION, "I1")
                .station(PAINT, "I2")
                .station(NEW_PAINT, "I4")
                .station(PACK, "I3")
                .station(EXIT, "I5")
                .station(STAGING, "I12")
                .build()

        /**
         *  "The initial position of the vehicles should be along Link 11" -- the approach to the
         *  staging area, which is where an idle fleet ends up anyway.
         */
        fun startingZones(numAgvs: Int): List<String> =
            (0 until numAgvs).map { "L11.Zone${9 - it}" }
    }

    val network: GuidedPathNetwork = guidePath ?: createNetwork()

    init {
        spatialModel = network
    }

    val transportSystem: GuidedPathTransportSystem =
        GuidedPathTransportSystem(this, network, name = "AgvSystem")

    private val vehicles: List<GuidedTransporter> = startingZones(numAgvs).mapIndexed { i, zone ->
        GuidedTransporter(
            transportSystem, TransporterPlacement.OnZone(zone),
            agvVelocity ?: ConstantRV(VELOCITY), 1, zoneControlRule, name = "AGV${i + 1}",
            physicalLength = agvPhysicalLength
        )
    }

    /**
     *  Parts that have asked for a vehicle and have not yet been collected.
     *
     *  Counted here rather than read off the pool's queue because the queue empties the moment a
     *  vehicle is assigned, and under [PendingRequestTest.UNTIL_COLLECTED] the empty move is
     *  exactly the interval that matters.
     */
    private var outstandingRequests: Int = 0

    override fun initialize() {
        super.initialize()
        outstandingRequests = 0
    }

    /**
     *  Sends a released vehicle to the staging area only when nothing at all is outstanding.
     *
     *  The pool has already established that its queue is empty before this is consulted, so under
     *  [PendingRequestTest.QUEUED_ONLY] there would be nothing left to test and the plain
     *  [MoveToStagingAreaRule] is used instead. This rule exists for the other reading.
     */
    private inner class StageWhenNothingOutstanding : IdleDispositionRuleIfc {
        override fun disposition(transporter: GuidedTransporter): IdleDisposition =
            if (outstandingRequests > 0) IdleDisposition.ParkInPlace
            else IdleDisposition.MoveTo(STAGING)

        override fun toString(): String = "StageWhenNothingOutstanding($STAGING)"
    }

    /** The fleet. Asked for by the group: a part wants a vehicle, not a particular vehicle. */
    val agvs: GuidedTransporterPoolWithQ = GuidedTransporterPoolWithQ(
        this, transportSystem, vehicles, ClosestByNetworkDistanceRule(),
        idleDispositionRule ?: when (pendingRequestTest) {
            PendingRequestTest.QUEUED_ONLY -> MoveToStagingAreaRule(STAGING)
            PendingRequestTest.UNTIL_COLLECTED -> StageWhenNothingOutstanding()
        },
        "AGVPool"
    )

    // ---- the shop -------------------------------------------------------------------------------

    private val myWorker = ResourceWithQ(this, "WORKER")
    private val myPainter = ResourceWithQ(this, "PAINTER")
    private val myNewPainter = ResourceWithQ(this, "NEWPAINTER")
    private val myPacker = ResourceWithQ(this, "PACKER")

    val worker: ResourceCIfc get() = myWorker
    val painter: ResourceCIfc get() = myPainter
    val newPainter: ResourceCIfc get() = myNewPainter
    val packer: ResourceCIfc get() = myPacker

    val workStationQ: QueueCIfc<ProcessModel.Entity.Request> get() = myWorker.waitingQ
    val paintQ: QueueCIfc<ProcessModel.Entity.Request> get() = myPainter.waitingQ
    val newPaintQ: QueueCIfc<ProcessModel.Entity.Request> get() = myNewPainter.waitingQ
    val packQ: QueueCIfc<ProcessModel.Entity.Request> get() = myPacker.waitingQ

    private val workStationTime = RandomVariable(this, UniformRV(21.0, 25.0))
    private val paintTime = RandomVariable(this, LognormalRV(22.0, 4.0 * 4.0))
    private val newPaintTime = RandomVariable(this, LognormalRV(49.0, 7.0 * 7.0))
    private val packTime = RandomVariable(this, TriangularRV(20.0, 22.0, 26.0))
    private val newPackTime = RandomVariable(this, TriangularRV(21.0, 23.0, 26.0))

    /** 30% of arrivals are the new colour. */
    private val isNewPart = RandomVariable(this, BernoulliRV(0.3))

    private val loadUnload = ConstantRV(LOAD_UNLOAD_MINUTES)

    private val tba = ExponentialRV(timeBtwArrivals)
    private val myArrivalGenerator = EntityGenerator(::Part, tba, tba)
    val generator: EventGeneratorRVCIfc get() = myArrivalGenerator

    // ---- what the exercise asks to be reported --------------------------------------------------

    private val myWip = TWResponse(this, "NumInSystem")
    val numInSystem: TWResponseCIfc get() = myWip

    private val mySystemTime = Response(this, "SystemTime")
    val systemTime: ResponseCIfc get() = mySystemTime

    private val myOldPartSystemTime = Response(this, "PartType1SysTime")
    val oldPartSystemTime: ResponseCIfc get() = myOldPartSystemTime

    private val myNewPartSystemTime = Response(this, "PartType2SysTime")
    val newPartSystemTime: ResponseCIfc get() = myNewPartSystemTime

    private val myNumberIn = Counter(this, "NumberIn")
    val numberIn: CounterCIfc get() = myNumberIn

    private val myNumberOut = Counter(this, "NumberOut")
    val numberOut: CounterCIfc get() = myNumberOut

    /**
     *  How long a part waited for a vehicle to be allocated to it, kept separately for each place a
     *  part is collected from.
     *
     *  The wait is measured, rather than read off the pool's queue, because the reference model
     *  reports a request queue per station and this is the quantity those five queues hold. The
     *  pool's own queue is the same waits pooled, and both are reported: they are two views of one
     *  queue, and the fact that the five reconcile with the one is worth being able to see.
     */
    private val myEnterRequestWait = Response(this, "EnterRequestWait")
    private val myWorkStationRequestWait = Response(this, "WorkStationRequestWait")
    private val myPaintRequestWait = Response(this, "PaintRequestWait")
    private val myNewPaintRequestWait = Response(this, "NewPaintRequestWait")
    private val myPackRequestWait = Response(this, "PackRequestWait")

    val enterRequestWait: ResponseCIfc get() = myEnterRequestWait
    val workStationRequestWait: ResponseCIfc get() = myWorkStationRequestWait
    val paintRequestWait: ResponseCIfc get() = myPaintRequestWait
    val newPaintRequestWait: ResponseCIfc get() = myNewPaintRequestWait
    val packRequestWait: ResponseCIfc get() = myPackRequestWait

    /**
     *  A part's time aboard a vehicle, summed over its four journeys: from the moment a vehicle is
     *  allocated to it until the moment the vehicle is given back, loading and unloading included.
     *  The wait *for* a vehicle is not part of it -- that is queueing, and is measured above.
     */
    private val myTransferTime = Response(this, "TransferTime")
    val transferTime: ResponseCIfc get() = myTransferTime

    // The empty-move time is deliberately *not* declared here. The transport system already reports
    // it, and its own loaded counterpart, from every completed journey -- see
    // `transportSystem.approachTime`. A second response fed from the same `GuidedTransportResult`
    // would be the same number under a second name, which is the duplication the guide warns about
    // one paragraph after the one that would have suggested writing it.

    private inner class Part : Entity() {

        private val isNew = isNewPart.value.toInt() == 1
        private val paintStation = if (isNew) NEW_PAINT else PAINT
        private val painterUsed = if (isNew) myNewPainter else myPainter
        private val paintDuration = if (isNew) newPaintTime else paintTime
        private val packDuration = if (isNew) newPackTime else packTime

        /** Time aboard a vehicle, accumulated over this part's four journeys. */
        private var carried = 0.0

        val flowLine: KSLProcess = process(isDefaultProcess = true) {
            currentLocation = network.requireLocation(ENTER)
            myWip.increment()
            myNumberIn.increment()
            timeStamp = time

            carry(ENTER, WORK_STATION, myEnterRequestWait)
            use(myWorker, delayDuration = workStationTime)

            carry(WORK_STATION, paintStation, myWorkStationRequestWait)
            use(painterUsed, delayDuration = paintDuration)

            carry(paintStation, PACK, if (isNew) myNewPaintRequestWait else myPaintRequestWait)
            use(myPacker, delayDuration = packDuration)

            carry(PACK, EXIT, myPackRequestWait)

            val flowTime = time - timeStamp
            mySystemTime.value = flowTime
            if (isNew) myNewPartSystemTime.value = flowTime else myOldPartSystemTime.value = flowTime
            myTransferTime.value = carried
            myNumberOut.increment()
            myWip.decrement()
        }

        /**
         *  One journey, in the three-verb form so that the wait for a vehicle can be told apart from
         *  the journey it precedes. [guidedTransport] does the same work in one call but gives back
         *  only the total, and the wait is half of what this model is being asked about.
         */
        private suspend fun KSLProcessBuilder.carry(from: String, to: String, requestWait: Response) {
            val askedAt = time
            outstandingRequests++
            val request = requestGuidedTransporter(agvs, pickupLocation = from)
            // Given up once the vehicle is standing here, which is where the reference
            // implementation's own counter of pending requests is decremented: its transport
            // request does not finish until then.
            outstandingRequests--
            requestWait.value = request.timeAllocated - askedAt
            val leg = transportBy(
                request, destination = to,
                loadingDelay = loadUnload, unLoadingDelay = loadUnload
            )
            releaseGuidedTransporter(request, agvs)
            carried += leg.approachTime + leg.rideTime + 2.0 * LOAD_UNLOAD_MINUTES
        }
    }
}
