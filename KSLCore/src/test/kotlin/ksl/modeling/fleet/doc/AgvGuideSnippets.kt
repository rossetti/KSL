package ksl.modeling.fleet.doc

import ksl.modeling.spatial.FleetSpaceIfc
import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.fleet.FleetVehicle
import ksl.modeling.fleet.Battery
import ksl.modeling.fleet.FailureBasis
import ksl.modeling.fleet.FailureModel
import ksl.modeling.fleet.Interruption
import ksl.modeling.fleet.InterruptionPolicyIfc
import ksl.modeling.fleet.VehicleInterruptionListenerIfc
import ksl.modeling.fleet.AssignmentProposal
import ksl.modeling.fleet.Dispatcher
import ksl.modeling.fleet.exceptions.FleetInvariantViolation
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.Bid
import ksl.modeling.fleet.policies.BidPolicyIfc
import ksl.modeling.fleet.policies.ByPriorityTaskSelection
import ksl.modeling.fleet.policies.ChargeReservePolicy
import ksl.modeling.fleet.policies.ChargeWhenLowDisposition
import ksl.modeling.fleet.policies.CallForProposals
import ksl.modeling.fleet.policies.CompletionTimeBid
import ksl.modeling.fleet.policies.ContractNetAssignmentPolicy
import ksl.modeling.fleet.policies.DeclineWhenBusyBid
import ksl.modeling.fleet.policies.DispatchContext
import ksl.modeling.fleet.policies.Disposition
import ksl.modeling.fleet.policies.DispositionPolicyIfc
import ksl.modeling.fleet.policies.LeastUsedVehiclePolicy
import ksl.modeling.fleet.policies.MoveToStagingDisposition
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.ReconsiderOnInterruption
import ksl.modeling.fleet.policies.ReassigningPolicy
import ksl.modeling.fleet.policies.ReturnToHomeBaseDisposition
import ksl.modeling.fleet.policies.ScoringAssignmentPolicy
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.entity.tow
import ksl.modeling.fleet.FreePathFleet
import ksl.modeling.fleet.FreePathVehicle
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.modeling.fleet.Line
import ksl.modeling.fleet.LineStop
import ksl.modeling.fleet.Stop
import ksl.modeling.fleet.StopContextIfc
import ksl.modeling.fleet.StopInstruction
import ksl.modeling.fleet.TourStopActionIfc
import ksl.modeling.fleet.TransitResult
import ksl.modeling.fleet.policies.AppendTourPolicy
import ksl.modeling.fleet.policies.CheapestInsertionTourPolicy
import ksl.modeling.fleet.policies.ConsolidatingPolicy
import ksl.modeling.fleet.policies.DispatcherStopControl
import ksl.modeling.fleet.policies.PickUpAllThenDeliverAllPolicy
import ksl.modeling.fleet.policies.TimetableControl
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.exceptions.GuidedPathDeadlockException
import ksl.modeling.variable.Counter
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Compile-only host for every code snippet in `docs/guides/ksl-fleet.md`.
 * Each `fun` body is a verbatim snippet (or its body); compiling this file
 * proves every example in the guide references real public APIs.
 *
 * This file is not run as a test — the build only needs to compile it.
 */
@Suppress("UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")
private object AgvGuideSnippets {

    const val ENTRY: String = "EntryStation"
    const val EXIT: String = "ExitStation"
    const val DEPOT: String = "CartDepot"

    // -- §3 Quick start: the network -------------------------------------

    fun buildNetwork(): GuidedPathNetwork = GuidedPathNetwork.builder("ShopFloor")
        .intersection("I1", x = 0.0, y = 72.0)
        .intersection("I2", x = 48.0, y = 72.0)
        .intersection("I3", x = 48.0, y = 0.0)
        .intersection("I4", x = 0.0, y = 0.0)
        .intersection("I5", x = 0.0, y = -36.0)
        .intersection("I6", x = 54.0, y = 72.0)
        // A one-way loop, so two vehicles cannot meet head-on.
        .link("Link1", "I1", "I2", length = 48.0, zoneLength = 12.0)
        .link("Link2", "I2", "I3", length = 72.0, zoneLength = 12.0)
        .link("Link3", "I3", "I4", length = 48.0, zoneLength = 12.0)
        .link("Link4", "I4", "I1", length = 72.0, zoneLength = 12.0)
        .link("ExitSpur", "I4", "I5", length = 36.0, zoneLength = 12.0, type = LinkType.SPUR)
        // A parking spur per vehicle, so an idle one is out of the traffic.
        .link("DepotSpur", "I2", "I6", length = 6.0, zoneLength = 6.0, type = LinkType.SPUR)
        .station(ENTRY, "I1")
        .station(EXIT, "I5")
        .station(DEPOT, "I6")
        .build()

    // -- §3 Quick start: the model ---------------------------------------

    class AgvShop(parent: ModelElement) : ProcessModel(parent, "AgvShop") {

        val network = buildNetwork()

        init {
            spatialModel = network
        }

        // The fleet and its dispatcher. A child of this model; its entities suspend in its queues.
        val agv = AgvSystem(this, network, name = "Agv")

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(DEPOT), ConstantRV(10.0), name = "Cart"
        ).apply { homeBase = DEPOT }

        val timeInSystem = Response(this, "TimeInSystem")
        val delivered = Counter(this, "Delivered")

        private val timeBetweenArrivals = ExponentialRV(40.0, 1)

        inner class Part : Entity() {
            val production = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(ENTRY)
                // States what it needs and suspends. It never chooses a vehicle.
                transportByFleet(agv, destination = EXIT, origin = ENTRY)
                timeInSystem.value = time - arrived
                delivered.increment()
            }
        }

        inner class Source : Entity() {
            val arrivals = process(isDefaultProcess = true) {
                repeat(400) {
                    delay(timeBetweenArrivals)
                    activate(Part().production)
                }
            }
        }

        override fun initialize() {
            activate(Source().arrivals)
        }
    }

    fun runIt() {
        val m = Model("AgvShop")
        val shop = AgvShop(m)
        m.numberOfReplications = 20
        m.lengthOfReplication = 8_000.0
        m.lengthOfReplicationWarmUp = 1_000.0
        m.simulate()
        m.print()
    }

    // -- §4 …ask for transport without waiting for it --------------------

    class Decoupled(parent: ModelElement) : ProcessModel(parent, "Decoupled") {

        val network = buildNetwork()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(agv, TransporterPlacement.At(DEPOT), ConstantRV(10.0), name = "Cart")

        inner class Part : Entity() {
            val production = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(ENTRY)
                // Post the request now, so the vehicle is on its way while the work finishes.
                val task = requestFleetTransport(agv, destination = EXIT, origin = ENTRY)
                delay(5.0)                       // finish the operation, release the machine
                val result = awaitFleetTransport(task)
                val waited = result.waitForAssignment
                val fetched = result.waitForArrival
                val rode = result.timeAboard
                val who = result.vehicleName
                val turnedRound = result.numReassignments
            }
        }
    }

    // -- §4 …change the dispatching rule ---------------------------------

    fun chooseTheRule(parent: ModelElement, network: GuidedPathNetwork) {
        val agv = AgvSystem(parent, network, assignmentPolicy = LeastUsedVehiclePolicy())
        // Or later, while the model is not running:
        agv.dispatcher.assignmentPolicy = NearestVehiclePolicy()
        // What order the policy sees the waiting tasks in:
        agv.dispatcher.taskSelectionRule = ByPriorityTaskSelection()
    }

    // -- §4 …wait and decide over a batch --------------------------------

    fun batchIt(parent: ModelElement, network: GuidedPathNetwork) {
        AgvSystem(
            parent, network,
            assignmentPolicy = BatchedAssignmentPolicy(window = 10.0, inner = NearestVehiclePolicy()),
            name = "Agv"
        )
    }

    // -- §4 …let the vehicles bid ----------------------------------------

    fun auctionIt(parent: ModelElement, network: GuidedPathNetwork, fleet: List<AgvVehicle>) {
        AgvSystem(
            parent, network,
            assignmentPolicy = ContractNetAssignmentPolicy(deadline = 0.5),
            name = "Agv"
        )
        // What each vehicle offers is its own business, and may differ across the fleet.
        for (vehicle in fleet) {
            vehicle.bidPolicy = DeclineWhenBusyBid(CompletionTimeBid())
        }
    }

    /** A bidding rule cannot suspend: a bid is a quote, and quoting must not consume time. */
    class LeastLoadedBid : BidPolicyIfc {
        override fun bid(
            vehicle: FleetVehicle,
            cfp: CallForProposals,
            space: FleetSpaceIfc
        ): Bid? = Bid(vehicle, vehicle.numTasksCompleted.value, note = "tasks done so far")
    }

    // -- §4 …take a task back --------------------------------------------

    fun retask(parent: ModelElement, network: GuidedPathNetwork) {
        AgvSystem(
            parent, network,
            // A swap must save more than 50 units of guide path before it is worth making.
            assignmentPolicy = ReassigningPolicy(improvementThreshold = 50.0),
            name = "Agv"
        )
    }

    // -- §4 …write your own policy ---------------------------------------

    /** Send whichever available vehicle is nearest, but never turn one round for less than a leg. */
    class AlphabeticalPolicy : AssignmentPolicyIfc {
        override suspend fun KSLProcessBuilder.assign(
            context: DispatchContext
        ): List<AssignmentProposal> {
            val free = context.available.sortedBy { it.name }.toMutableList()
            val proposals = mutableListOf<AssignmentProposal>()
            for (task in context.board.unassigned) {
                if (free.isEmpty()) break
                proposals.add(AssignmentProposal(free.removeAt(0), task))
            }
            return proposals
        }
    }

    /** The same shape as a cost-function rule: enumerate the actions, score each, take the best. */
    fun scoreEveryPairing(): AssignmentPolicyIfc = ScoringAssignmentPolicy { proposal, feasible ->
        val travel = feasible.cost(proposal.vehicle, proposal.task)
        // Lower is better, so a task declaring a lower priority number is worth going further for.
        travel + 100.0 * proposal.task.priority
    }

    // -- §4 …decide where an idle vehicle goes ---------------------------

    fun idleVehicles(fleet: List<AgvVehicle>) {
        fleet[0].dispositionPolicy = ReturnToHomeBaseDisposition()
        fleet[1].dispositionPolicy = MoveToStagingDisposition("StagingSpur2")
    }

    /** Per vehicle, so a fleet can be heterogeneous, and free to look at the vehicle. */
    class GoHomeWhenTiredDisposition(private val after: Double) : DispositionPolicyIfc {
        override fun disposition(vehicle: FleetVehicle): Disposition =
            if (vehicle.numTasksCompleted.value >= after) Disposition.ReturnToHomeBase
            else Disposition.ParkInPlace
    }

    // -- §4 …abandon an outstanding request ------------------------------

    fun abandon(agv: AgvSystem, task: Dispatcher.Task) {
        agv.dispatcher.cancel(task)
    }

    // -- §4 …check the subsystem's own bookkeeping -----------------------

    fun audit(agv: AgvSystem) {
        agv.checkInvariants = true          // every clock advance; expensive, for development
        agv.auditAtReplicationEnd = true    // once per replication; on by default
    }

    // -- §4 Telling the rest of the model about a breakdown --------------

    fun attachABreakdownLog(agv: AgvSystem, breakdownLog: MutableList<Pair<Double, String>>) {
        agv.attachInterruptionListener(object : VehicleInterruptionListenerIfc {
            override fun stopped(interruption: Interruption) {
                breakdownLog.add(interruption.at to interruption.vehicle.name)
            }
        })
    }

    fun tellTheDispatcher(agv: AgvSystem) {
        agv.attachInterruptionListener(ReconsiderOnInterruption(agv.dispatcher))
    }

    // -- §4 An errand -----------------------------------------------------

    fun postAnErrand(agv: AgvSystem) {
        val errand = agv.dispatcher.postService("YardSpur")
        agv.dispatcher.cancel(errand)
    }

    // -- §6 A sweep that must survive a deadlock -------------------------

    fun sweep(record: (Int, Double) -> Unit, recordInfeasible: (Int, Any) -> Unit) {
        for (fleetSize in 1..12) {
            val model = Model("Sweep$fleetSize")
            val shop = AgvShop(model)
            model.numberOfReplications = 30
            try {
                model.simulate()
                record(fleetSize, shop.timeInSystem.acrossReplicationStatistic.average)
            } catch (e: GuidedPathDeadlockException) {
                // A domain outcome, not a defect: this fleet size cannot run on this layout.
                recordInfeasible(fleetSize, e.report)
            } catch (e: FleetInvariantViolation) {
                // Not a domain outcome. The subsystem's account of itself did not add up.
                throw e
            }
        }
    }


    // -- §4 Batteries and charging ---------------------------------------

    class ChargedShop(parent: ModelElement) : ProcessModel(parent, "ChargedShop") {

        val network = buildNetwork()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, name = "Agv",
            assignmentPolicy = ChargeReservePolicy(NearestVehiclePolicy())
        )

        init {
            agv.addCharger("I6")
        }

        val cart = AgvVehicle(
            agv, TransporterPlacement.At("I6"), ConstantRV(3.0), name = "Cart",
            battery = Battery(
                capacity = 1000.0,
                chargePerDistance = 0.5,   // traction: drawn per foot travelled
                chargePerTime = 0.02,      // hotel load: drawn always, parked included
                chargingRate = 100.0
            )
        ).apply {
            dispositionPolicy = ChargeWhenLowDisposition(threshold = 0.6)
        }
    }

    // -- §4 Breakdowns ---------------------------------------------------

    fun aVehicleThatBreaksDown(agv: AgvSystem): AgvVehicle = AgvVehicle(
        agv, TransporterPlacement.At("I6"), ConstantRV(3.0), name = "FailingCart",
        failureModel = FailureModel.clockBased(
            timeBetweenFailures = ExponentialRV(400.0, streamNum = 7),
            repairTime = LognormalRV(20.0, 25.0, streamNum = 8),
            basis = FailureBasis.OPERATING_TIME
        )
    )

    // -- §4 What a site actually does when a vehicle breaks down ---------

    class VisitAndAssess(
        private val technicians: ResourceWithQ,
        private val refuge: String,
        private val reportingDelay: RVariableIfc,
        private val walkingTime: RVariableIfc,
        private val assessmentTime: RVariableIfc
    ) : InterruptionPolicyIfc {

        override suspend fun KSLProcessBuilder.handle(interruption: Interruption) {
            val vehicle = interruption.vehicle
            delay(reportingDelay)                        // nobody noticed for a while
            val tech = seize(technicians)                // somebody has to be free
            delay(walkingTime)                           // and walk to it
            delay(assessmentTime)                        // and look at it
            if (interruption.isObstructingNow) {            // decided at the vehicle, not before
                tow(vehicle, refuge, atVelocity = 1.0)   // pushed out of the aisle
            }
            if (interruption is Interruption.Failed) delay(interruption.repairTime)
            release(tech)
        }
    }

    fun installTheRecoveryPolicy(cart: AgvVehicle, technicians: ResourceWithQ) {
        cart.interruptionPolicy = VisitAndAssess(
            technicians, refuge = "MaintenanceSpur",
            reportingDelay = ConstantRV(2.0),
            walkingTime = ExponentialRV(8.0, streamNum = 9),
            assessmentTime = ConstantRV(5.0)
        )
    }


    // -- §6 What the horizon left undone ---------------------------------

    fun readTheHorizon(agv: AgvSystem) {
        val stranded = agv.numTasksNeverAssigned.acrossReplicationStatistic.average
        val hanging = agv.numEntitiesNeverResumed.acrossReplicationStatistic.average
        val open = agv.numAssignmentsStillOpen.acrossReplicationStatistic.average
    }

    // -- §4 Carrying more than one load at a time -------------------------

    fun aFleetThatConsolidates(parent: ModelElement, network: GuidedPathNetwork) {
        // A vehicle only carries several if it is *given* several. A batching window collects the
        // tasks; ConsolidatingPolicy is what fills a vehicle that still has room.
        val agv = AgvSystem(
            parent, network,
            assignmentPolicy = BatchedAssignmentPolicy(window = 5.0, inner = ConsolidatingPolicy())
        )

        val cart = AgvVehicle(
            agv, TransporterPlacement.At("Depot"), ConstantRV(60.0),
            name = "Cart", loadCapacity = 4
        )
    }

    fun chooseTheTourPolicy(agv: AgvSystem) {
        agv.dispatcher.tourPolicy = CheapestInsertionTourPolicy()   // the default
        // or PickUpAllThenDeliverAllPolicy() -- a literal milk run
        // or AppendTourPolicy()             -- the naive baseline, useful as a comparison
        agv.dispatcher.tourPolicy = PickUpAllThenDeliverAllPolicy()
        agv.dispatcher.tourPolicy = AppendTourPolicy()
    }

    // -- §4 Running a fixed route -----------------------------------------

    class MilkRunShop(parent: ModelElement) : ProcessModel(parent, "MilkRunShop") {

        val network = buildNetwork()

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(this, network, name = "Fleet")

        val depot = Stop(fleet, ENTRY)
        val cell1 = Stop(fleet, EXIT)
        val cell2 = Stop(fleet, DEPOT)

        // A LineStop's default action is what serving a stop ordinarily means: put down everyone
        // bound for here, then take whoever is waiting for somewhere further along.
        val milkRun = Line("MilkRun", listOf(LineStop(depot), LineStop(cell1), LineStop(cell2)))

        fun postOneCycle() {
            // One post is one cycle. A service that runs all day is a model that posts again --
            // on a headway, on a timetable, or when the previous cycle ends.
            fleet.dispatcher.postLine(milkRun)
        }

        val timeToCross = Response(this, "TimeToCross")

        inner class Rider : Entity() {
            val part = process {
                currentLocation = network.requireLocation(EXIT)
                val r = rideFrom(cell1, DEPOT)      // waits at the stop, boards, is set down
                timeToCross.value = r.totalTime
            }
        }
    }

    // -- §4 Writing your own stop action ----------------------------------

    class BoardOnePerMinute(val stop: Stop) : TourStopActionIfc {
        override val servesStop = stop
        override suspend fun KSLProcessBuilder.perform(context: StopContextIfc) {
            // Name the place before entering the context: inside `with(context)`, `stop` is the
            // context's own `TourStop` -- somewhere on this tour -- not the permanent `Stop`.
            val here = this@BoardOnePerMinute.stop
            with(context) {
                for (ride in here.waitingFor(onwardLocations)) {
                    if (vehicle.spareCapacity <= 0) break
                    delay(1.0)
                    takeAboard(ride)
                }
            }
        }
    }

    // -- §4 Holding, running express, turning short -----------------------

    fun controlTheService(fleet: AgvSystem, bus: AgvVehicle, stopA: Stop, stopB: Stop, stopC: Stop) {
        // Hold at each stop until its scheduled departure, measured from the start of the cycle.
        bus.stopControl = TimetableControl(mapOf(stopA to 0.0, stopB to 8.0, stopC to 17.0))

        // Or let a controller decide, cycle by cycle.
        bus.stopControl = DispatcherStopControl()
        fleet.dispatcher.instruct(bus, StopInstruction.Skip)             // run it past the next stop
        fleet.dispatcher.instruct(bus, StopInstruction.ServeAndEndTour)  // short-turn it
    }

    // -- §4 A transfer needs no machinery ---------------------------------

    class TransferShop(parent: ModelElement) : ProcessModel(parent, "TransferShop") {

        val network = buildNetwork()

        init {
            spatialModel = network
        }

        val fleet = AgvSystem(this, network, name = "Fleet")
        val originStop = Stop(fleet, ENTRY)
        val hubStop = Stop(fleet, EXIT)

        val connectionTime = Response(this, "ConnectionTime")
        val numTransfers = Response(this, "NumTransfers")

        inner class Shipment : Entity() {
            val shipment = process {
                currentLocation = network.requireLocation(ENTRY)
                val toHub: TransitResult = rideFrom(originStop, EXIT)
                val onward: TransitResult = rideFrom(hubStop, DEPOT)
                connectionTime.value = onward.waitForVehicle
                numTransfers.value = 1.0
            }
        }
    }

    // -- §4 Running a fleet without a guide path --------------------------

    class Yard(parent: ModelElement) : ProcessModel(parent, "Yard") {

        val plane = Euclidean2DPlane()

        // A fleet is written in named places; the spatial model supplies the geometry between them.
        val places = listOf(
            plane.Point(0.0, 0.0, "Depot"),
            plane.Point(300.0, 0.0, "Press"),
            plane.Point(0.0, 200.0, "Ship")
        )

        init {
            spatialModel = plane
        }

        val fleet = FreePathFleet(this, plane, places, name = "Yard")

        val cart = FreePathVehicle(
            fleet, "Depot", ConstantRV(30.0), name = "Cart", loadCapacity = 4, stepSize = 10.0
        ).apply { homeBase = "Depot" }

        inner class Pallet : Entity() {
            val movement = process(isDefaultProcess = true) {
                currentLocation = fleet.space.requireLocation("Press")
                transportByFleet(fleet, destination = "Ship", origin = "Press")
            }
        }
    }

    // -- §4 Reading the capacity statistics off the vehicle ---------------

    fun readTheCapacityRows(cart: AgvVehicle) {
        val used = cart.capacityUtilization?.acrossReplicationStatistic?.average
        val full = cart.fracTimeAtCapacity?.acrossReplicationStatistic?.average
        val perMove = cart.loadsPerLoadedMove?.acrossReplicationStatistic?.average
        val aboard = cart.numLoadsAboardResponse?.acrossReplicationStatistic?.average
        val perTour = cart.loadsPerTour?.acrossReplicationStatistic?.average
    }

    // -- §4 Finding where the congestion is -------------------------------

    fun switchOnTheFinerTiers(agv: AgvSystem) {
        agv.collectLinkStatistics = true    // a response per link
        agv.collectZoneStatistics = true    // a response per zone: the finest, and the most expensive
    }

    // -- §4 Asking what a vehicle is doing right now ----------------------

    fun readTheVehiclesState(cart: AgvVehicle) {
        val aboard = cart.manifest                  // a read-only view, in boarding order
        val full = cart.isAtCapacity
        val carrying = cart.isCarryingALoad
        val speed = cart.currentVelocity            // now, not the mean of its distribution
        val stuckFor = cart.cumulativeBlockedTime   // the running total behind FracTimeBlocked
        val zones = cart.zonesEntered               // zero on a substrate with no zones
        val beingPushed = cart.isUnderTow
        val outOfService = cart.isOutOfService
    }
}
