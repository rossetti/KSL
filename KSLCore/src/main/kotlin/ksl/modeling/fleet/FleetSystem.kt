package ksl.modeling.fleet

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.agent.AgentMessage
import ksl.modeling.agent.AgentModel
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.CallForProposals
import ksl.modeling.fleet.policies.DispatchContext
import ksl.modeling.fleet.policies.Disposition
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.internal.DispatchAudit
import ksl.modeling.fleet.exceptions.FleetProtocolException
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.spatial.MovePurpose
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV

/**
 * A fleet of self-directing vehicles, and the dispatcher that tasks them.
 *
 * Decision-making moved out of the entity's process and into objects that have processes of their
 * own. An entity asks for transport and suspends; it never chooses a vehicle, never waits for a
 * particular one, and cannot tell which one came.
 *
 * **Substrate-independent.** Nothing here names a guide path, a projection or a plane. What a
 * subclass supplies is a [space] -- named places and distances -- and, if its substrate measures
 * anything about a journey, the three hooks below that let it report what it measured. Everything
 * else -- the dispatcher, the tours, the stops, the lines, the control loop that walks them, and
 * the audit that checks the whole account adds up -- is the same whatever the vehicles run on.
 *
 * `AgentModel`, and therefore `ProcessModel`, because the vehicles and the dispatcher are agents
 * with processes and mailboxes, and only an `AgentModel` can host them. A modeller's own
 * `ProcessModel` holds this as a child element, and its entities suspend in this system's queues.
 */
abstract class FleetSystem @JvmOverloads constructor(
    parent: ModelElement,
    assignmentPolicy: AssignmentPolicyIfc = NearestVehiclePolicy(),
    name: String? = null
) : AgentModel(parent, name) {

    /**
     * The layout this fleet works over: named places, distances between them, and reachability.
     *
     * Supplied by the subclass, which is what binds this fleet to a substrate. Nothing here asks it
     * anything else, and nothing here names a guide path.
     */
    abstract val space: ksl.modeling.spatial.FleetSpaceIfc

    val dispatcher: Dispatcher = Dispatcher(this, assignmentPolicy, name = "${this.name}:Dispatcher")

    private val myAudit = DispatchAudit(this)

    /**
     * Whether this system audits its own account of itself once, as each replication ends.
     *
     * On by default. A subclass whose substrate has its own invariants extends this to cover them,
     * because the two halves of a model are not separately auditable in any useful sense: a vehicle
     * that has lost track of its assignment and one that has lost track of the space it is standing
     * on are the same kind of failure seen from two sides.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    open var auditAtReplicationEnd: Boolean = true

    /**
     * Reports what one delivered load cost the substrate underneath.
     *
     * A no-op here, because a substrate that measures nothing about a carry has nothing to report.
     * The guide path overrides it and feeds the five per-carry rows both paradigms share.
     */
    protected open fun collectCarry(
        approachTime: Double,
        rideTime: Double,
        blockedTime: Double,
        zonesTraversed: Int,
        routeLength: Double
    ) {
    }

    /** Switches reporting on the substrate's own queues, if it has any. */
    protected open fun substrateReporting(option: Boolean) {}

    /** What space a vehicle is denying to everyone else, for the horizon diagnostic. Empty where
     *  a vehicle standing still is in nobody's way. */
    protected open fun spaceHeldBy(vehicle: FleetVehicle): String = ""

    private val myVehicles = mutableListOf<FleetVehicle>()

    /** In declaration order, which is the order ties are broken in. */
    val vehicles: List<FleetVehicle>
        get() = myVehicles

    internal fun addVehicle(vehicle: FleetVehicle) {
        require(model.isNotRunning) { "A vehicle cannot be added while the model is running." }
        myVehicles.add(vehicle)
        // Registered when the first vehicle that can fail joins the fleet, and not before. A model
        // whose vehicles cannot break down would otherwise carry a row that is zero in every
        // replication of every run -- which is a question its reader has to answer for themselves
        // every time, and the defect PerCarryStatisticsTest exists because of.
        if (vehicle.failureModel != null && myFailedTimePerTransport == null) {
            myFailedTimePerTransport = Response(this, "${this.name}:FailedTimePerTransport")
        }
    }

    private val myInterruptionListeners = mutableListOf<VehicleInterruptionListenerIfc>()

    /**
     * Asks to be told whenever a vehicle in this fleet stops and cannot carry on by itself.
     *
     * Any number of listeners; see [VehicleInterruptionListenerIfc] for why observation is plural
     * where the policy that decides is singular. [ReconsiderOnInterruption] is the one most models
     * want.
     */
    fun attachInterruptionListener(listener: VehicleInterruptionListenerIfc) {
        myInterruptionListeners.add(listener)
    }

    /** Stops telling a listener about interruptions. */
    fun detachInterruptionListener(listener: VehicleInterruptionListenerIfc) {
        myInterruptionListeners.remove(listener)
    }

    // Copied before iterating, so that a listener may attach or detach one while being told.
    internal fun notifyStopped(interruption: Interruption) {
        for (l in myInterruptionListeners.toList()) l.stopped(interruption)
    }

    internal fun notifyReturnedToService(interruption: Interruption, outOfServiceFor: Double) {
        for (l in myInterruptionListeners.toList()) l.returnedToService(interruption, outOfServiceFor)
    }

    internal fun notifyOutOfService(interruption: Interruption) {
        for (l in myInterruptionListeners.toList()) l.outOfService(interruption)
    }

    private var myFailedTimePerTransport: Response? = null

    /**
     * Per delivered load: how much of its journey the carrying vehicle spent out of service.
     *
     * Present only when at least one vehicle in the fleet has a failure model. It is a part of
     * [approachTime] and [timeAboard] rather than something outside them, and it is what separates
     * a fleet that is slow from one that is unreliable.
     */
    val failedTimePerTransport: ResponseCIfc?
        get() = myFailedTimePerTransport

    private val myChargers = mutableListOf<String>()

    /**
     * Where a vehicle can charge, in declaration order.
     *
     * Ordinary places in the layout -- an intersection, a station alias, a named point -- because a charger
     * is a place a vehicle drives to and nothing else. Nothing in the space layer changes to have
     * one, and a charger on a spur behaves like any other spur: one vehicle at a time, with the rest
     * queueing on the approach.
     *
     * Fleet-wide rather than per vehicle, since a charger belongs to the layout. A fleet whose
     * vehicles take different chargers says so through its disposition policies.
     */
    val chargers: List<String>
        get() = myChargers

    /**
     * Declares a place in the layout as a charger.
     *
     * Checked against the layout now rather than when a vehicle is sent there, so a misspelled
     * charger fails at build time instead of stranding a vehicle several thousand simulated minutes
     * into a run.
     */
    fun addCharger(locationName: String) {
        require(model.isNotRunning) { "A charger cannot be added while the model is running." }
        space.requireLocation(locationName)
        if (locationName !in myChargers) myChargers.add(locationName)
    }

    /**
     * The declared charger nearest to a place along the layout's own paths, or null when none is
     * reachable from there.
     *
     * Along the layout's paths and not as the crow flies, because a one-way network's distances are not
     * symmetric and the nearest charger by position may be the furthest to actually reach.
     */
    fun nearestCharger(fromLocationName: String): String? {
        val here = space.location(fromLocationName) ?: return null
        return myChargers
            .mapNotNull { name -> space.location(name)?.let { name to it } }
            .filter { (_, there) -> space.isReachable(here, there) }
            .minByOrNull { (_, there) -> space.distance(here, there) }
            ?.first
    }

    // ---- the six hold queues -------------------------------------------------------------------
    //
    // Suspension plumbing, not the model's waiting line -- that is the dispatcher's task queue.
    // All six are internal so only this subsystem can suspend anything in them, and all six
    // report nothing, which is what `Conveyor` does for the same reason: a hold queue is how a
    // suspended entity is found again, and letting it double as the statistic conflates a mechanism
    // with a measurement.

    internal val awaitingPickupHoldQ = HoldQueue(this, "${this.name}:AwaitingPickupHoldQ")

    /**
     * Where a rider standing at a stop is suspended.
     *
     * Distinct from [awaitingPickupHoldQ] because the two waits are for different things and end in
     * different ways: a posted load is woken by the vehicle the dispatcher sent for it, a rider by
     * whichever vehicle happened to come past with room. Keeping them apart is also what lets the
     * closing audit say which of the two a stranded load was doing.
     */
    internal val awaitingBoardingHoldQ = HoldQueue(this, "${this.name}:AwaitingBoardingHoldQ")
    internal val inTransitHoldQ = HoldQueue(this, "${this.name}:InTransitHoldQ")
    internal val availabilityQ = HoldQueue(this, "${this.name}:AvailabilityQ")
    internal val dispatcherIdleQ = HoldQueue(this, "${this.name}:DispatcherIdleQ")

    /**
     * Where a vehicle waits when its interruption policy did not put it right.
     *
     * **Nothing resumes this queue.** A vehicle here is out of service for the rest of the
     * replication, standing where it stopped and keeping whatever space it holds -- which is the honest model of
     * a vehicle that has run flat or broken down beyond what anybody did about it, and is why the
     * fleet's throughput falls rather than the run raising. `NumVehiclesStranded` and
     * `NumVehiclesFailedAtHorizon` are what say it happened.
     */
    internal val outOfServiceQ = HoldQueue(this, "${this.name}:OutOfServiceQ")

    /** The loads suspended waiting to be collected. For the closing audit; the queues stay shut. */
    internal val loadsAwaitingPickup: List<ProcessModel.Entity>
        get() = awaitingPickupHoldQ.immutableList

    /** The loads suspended aboard a vehicle. */
    internal val loadsInTransit: List<ProcessModel.Entity>
        get() = inTransitHoldQ.immutableList

    /** The riders suspended at a stop, waiting for something to come by. */
    internal val loadsAwaitingBoarding: List<ProcessModel.Entity>
        get() = awaitingBoardingHoldQ.immutableList

    private val myStops = mutableListOf<Stop>()

    /**
     * The permanent stops declared on this fleet.
     *
     * A registry rather than a lookup: nothing here resolves a stop by name, because a stop is
     * named by holding the object. It exists so that the closing audit can ask every stop whether
     * anybody is still standing at it, and so that a report can be walked.
     */
    val stops: List<Stop>
        get() = myStops

    internal fun register(stop: Stop) {
        myStops.add(stop)
    }

    init {
        // This system's own queues only. The substrate's are switched by whoever owns them, in its
        // own constructor: an open method called from a base class's initializer runs before the
        // subclass exists, and would find a substrate that has not been built yet.
        switchOwnHoldQueues(false)
    }

    /**
     * Switches reporting for the hold queues. Off by default.
     *
     * The rows this adds are diagnostic. They are not a second opinion on the numbers the task
     * queue and the two responses report, and reading them as though they were will mislead: the
     * in-transit queue in particular looks like a waiting line and is not one, since nothing is
     * contended while riding.
     *
     * This also covers the **space layer's** movement hold queue, which the passive subsystem
     * reports by default and which this one must not. Under the passive paradigm that queue holds
     * loads being carried; here it holds *vehicle agents*, so its number in queue is the number of
     * vehicles under way. Left on, it would put a row on the report that looks like a line of loads
     * waiting and is in fact a count of moving carts -- the most misleading row this subsystem
     * could produce, and the one a reader is least likely to question. It is switched here rather
     * than in the space layer because this is a property of that one instance, which this system
     * owns; a passive model's own movement queue keeps reporting exactly as before.
     *
     * @param option true means the hold queues appear on the summary report
     */
    fun statisticalReportingForHoldQueues(option: Boolean) {
        switchOwnHoldQueues(option)
        // The substrate's own queues are switched through its own method rather than reached into
        // from here, so that this system does not have to know how many there are.
        substrateReporting(option)
    }

    private fun switchOwnHoldQueues(option: Boolean) {
        val queues = listOf(
            awaitingPickupHoldQ, awaitingBoardingHoldQ, inTransitHoldQ, availabilityQ,
            dispatcherIdleQ, outOfServiceQ
        )
        for (q in queues) {
            q.waitTimeStatOption = option
            q.defaultReportingOption = option
        }
    }

    // ---- statistics ----------------------------------------------------------------------------
    // The number of tasks waiting is deliberately absent: it is the dispatcher's task queue's
    // number in queue, and declaring it here would be a second source of truth for one quantity.

    private val myNumVehiclesIdle = TWResponse(
        this, "${this.name}:NumVehiclesIdle", allowedDomain = ksl.utilities.Interval(0.0, Double.MAX_VALUE)
    )

    /**
     * How many vehicles carry no task: **idle to the dispatcher**.
     *
     * Not the same question as the space layer's `numTransportersIdle`, which counts vehicles
     * standing still, and the two rows appear together on an active model's report. A vehicle
     * repositioning to its home base is the case that separates them: it is moving, so it is not
     * idle in the space layer's sense, and it carries no task, so it is idle in this one. Each row
     * uses the word of the layer that owns it -- transporter for the shared space, vehicle here --
     * because renaming either would make the shared layer speak one consumer's dialect.
     */
    val numVehiclesIdle: TWResponseCIfc get() = myNumVehiclesIdle

    private val myNumVehiclesOnTask = TWResponse(
        this, "${this.name}:NumVehiclesOnTask", allowedDomain = ksl.utilities.Interval(0.0, Double.MAX_VALUE)
    )

    /** How many vehicles are working a task. */
    val numVehiclesOnTask: TWResponseCIfc get() = myNumVehiclesOnTask

    private val myNumVehiclesOutOfService = TWResponse(
        this, "${this.name}:NumVehiclesOutOfService",
        allowedDomain = ksl.utilities.Interval(0.0, Double.MAX_VALUE)
    )

    /**
     * How many vehicles are stopped and being dealt with -- broken down, waiting for a technician,
     * being pushed out of the way, or out for the rest of the replication.
     *
     * The three counts partition the fleet, which is the point of this one existing. Without it a
     * vehicle that broke down between tours held no assignment and was therefore counted **idle**,
     * so a reader of [numVehiclesIdle] would see spare capacity that was not there.
     */
    val numVehiclesOutOfService: TWResponseCIfc get() = myNumVehiclesOutOfService

    /**
     * How long a load spent aboard, observed once at delivery.
     *
     * A `Response`, so warm-up handles it. Not derivable from the task queue, which measures the
     * disjoint interval that ends where this one begins.
     *
     * **Named for the interval and not for the journey**, because the passive subsystem reports a
     * `TransportTime` of its own and means something else by it: request to set-down, the whole
     * story including the wait for a cart. This one is a strict sub-interval of that. The two
     * subsystems' own row names are kept disjoint, so a study that contains both -- a paradigm
     * comparison, which lines rows up by name -- compares like with like.
     */
    private val myTimeAboard = Response(this, "${this.name}:TimeAboard")
    val timeAboard: ResponseCIfc get() = myTimeAboard

    internal fun recordTimeAboard(value: Double) {
        myTimeAboard.value = value
    }

    /**
     * Reports what one delivered load cost the substrate underneath.
     *
     * Where the substrate registers such rows -- as a guide path does, and as both paradigms over
     * one share -- they are figures about this fleet rather than about nobody. A substrate that
     * measures nothing about a carry discards this, which is [collectCarry]'s default.
     */
    internal fun recordCarry(task: Dispatcher.TransportTask) {
        myFailedTimePerTransport?.value = task.failedTime
        collectCarry(
            approachTime = task.approachTime,
            rideTime = task.rideTime,
            blockedTime = task.blockedTime,
            zonesTraversed = task.loadedZonesTraversed,
            routeLength = task.loadedRouteLength
        )
    }

    // ---- horizon diagnostics -------------------------------------------------------------------
    //
    // `Response`, not `Counter`, and the distinction is semantic rather than a workaround.
    //
    // A counter holds a running total whose value is only meaningful *while* a replication is
    // running -- which is why it records itself in `replicationEnded`, infinitesimally before the
    // replication ends and while it is still live. What is measured here is not a running total. It
    // is a single observation, taken at the last instant of the replication, of a quantity that does
    // not exist until then: how much work was left undone. That is what a `Response` is for, and a
    // `Response` records itself in `afterReplication`, summarizing whatever was observed during the
    // run -- including an observation made at the very end of it.
    //
    // Written unconditionally, including zero. A replication that stranded nothing is an observation
    // of zero, not the absence of an observation; recording only the bad replications would make the
    // across-replication average a mean over those, which is a number that looks like a fleet's
    // performance and is not.

    // ---- what the substrate underneath costs ----------------------------------------------------
    //
    // Delegated rather than re-derived. The space layer registers all of these already, and they
    // appear on the report under this system's `:Space` child either way -- but a study that wants
    // to *read* one has no route to it, because the runtime that owns it is internal. That
    // asymmetry is not a modelling difference between the two paradigms and should not read as one:
    // the same questions are worth asking of a fleet whichever way it is dispatched, and an active
    // model that could not answer "how much of the time was somebody blocked" would be the poorer
    // of the two for a reason that is purely an accident of ownership.
    //
    // The ten fleet-level figures need nothing further: the movement engine feeds them whichever
    // paradigm is steering, because it is the engine that moves the vehicle. The five **per-carry**
    // figures did, and did not have it -- they are observed once per delivered load, and only the
    // passive subsystem was observing. `recordCarry` above is the missing half, and
    // `PerCarryStatisticsTest` holds the two paradigms' answers against each other so that a future
    // change cannot quietly empty them again.


    private val myNumTasksNeverAssigned = Response(this, "${this.name}:NumTasksNeverAssigned")
    val numTasksNeverAssigned: ResponseCIfc get() = myNumTasksNeverAssigned

    private val myNumEntitiesNeverResumed = Response(this, "${this.name}:NumEntitiesNeverResumed")
    val numEntitiesNeverResumed: ResponseCIfc get() = myNumEntitiesNeverResumed

    private val myNumVehiclesFailed = Response(this, "${this.name}:NumVehiclesFailedAtHorizon")

    /**
     * How many vehicles were broken down when the replication ended.
     *
     * Reported apart from an assignment left open, and both are needed to read the other. A failed
     * vehicle keeps its load, so its task shows as an open assignment and its entity as one never
     * resumed -- which, without this row, reads as a run that was merely too short. It was not: the
     * work was stopped, and how long the fleet spent stopped is [FleetVehicle.fracTimeFailed].
     */
    val numVehiclesFailedAtHorizon: ResponseCIfc get() = myNumVehiclesFailed

    private val myNumVehiclesStranded = Response(this, "${this.name}:NumVehiclesStranded")

    /**
     * How many vehicles were stopped mid-route for want of charge when the replication ended.
     *
     * Written for every replication, zero included, so the across-replication average is the mean
     * number stranded per run rather than a mean over the runs that went wrong. A figure that is
     * not zero says the fleet's charging policy does not work at this demand, and every other
     * statistic in the run was measured on a layout that was quietly missing some of its aisles.
     */
    val numVehiclesStranded: ResponseCIfc get() = myNumVehiclesStranded

    private val myNumAssignmentsStillOpen = Response(this, "${this.name}:NumAssignmentsStillOpen")
    val numAssignmentsStillOpen: ResponseCIfc get() = myNumAssignmentsStillOpen

    /** Emits the one thing a viewer cannot infer from watching vehicles move: that a decision was
     *  made. Guarded, so it costs nothing when no animation sink is installed. */
    internal fun emitAssignment(assignment: Assignment) {
        val sink = model.animationSink
        if (!sink.isActive) return
        sink.emit(
            ksl.animation.AnimationEvent.AgvAssignmentMade(
                time, this.name, assignment.vehicle.name, assignment.task.id,
                assignment.task.pickupLocation, assignment.task.destination
            )
        )
    }

    /**
     * Hands an assignment to a vehicle's agent, wherever that agent currently is.
     *
     * Two cases, and the second is what keeps this paradigm's answers equal to the passive one's.
     *
     * A vehicle dormant in the availability queue is simply resumed. A vehicle part-way through a
     * *disposition* move -- going home, repositioning -- is suspended in the space layer's movement
     * queue instead, and is **redirected in flight**: the body is sent to the new pickup and the
     * agent is resumed when it arrives there rather than where it was heading. A vehicle on its way
     * to a parking spur is doing work nobody needs while a load waits, and the passive subsystem
     * has always been able to turn one round, because its cart is unallocated while returning and
     * the next entity simply seizes it. Withdrawing the vehicle for the duration of its disposition
     * looked tidier and cost roughly ten per cent of the mean time in system -- which Gate A caught.
     *
     * A vehicle part-way through a *revoked* task reaches this the same way and for the same reason:
     * `revoke` re-declares it before abandoning its tour, so it is available again while still
     * somewhere, and turning it round is exactly what re-tasking means. A vehicle
     * holding a live assignment is never in this position -- it is withdrawn for the whole of a tour.
     */
    internal fun deliverAssignments(agent: VehicleAgent) {
        val first = agent.assignments.firstOrNull() ?: return
        if (availabilityQ.contains(agent)) {
            availabilityQ.removeAndResume(agent)
            return
        }
        // Under way on a disposition. Turn it round. The target is the first commitment's pickup;
        // the vehicle's own loop plans the whole round and issues its own leg on waking, so this
        // only has to stop it going where it no longer needs to go.
        val pickup = first.task.pickupLocation
        val travelling = agent.vehicle.beginTravelTo(pickup, MovePurpose.SERVICE, agent)
        if (travelling == null) {
            // Already standing where it is now needed. Its journey is over, so nothing will arrive
            // to resume it; without this the agent would wait in the driving queue for an arrival
            // that has already happened.
            agent.vehicle.body.movementQueue.removeAndResume(agent)
        }
    }

    /** The three counts partition the fleet: on task, out of service, and neither. */
    internal fun refreshFleetCounts() {
        val out = myVehicles.count { it.isOutOfService }
        val onTask = myVehicles.count { it.hasAssignment && !it.isOutOfService }
        myNumVehiclesOnTask.value = onTask.toDouble()
        myNumVehiclesOutOfService.value = out.toDouble()
        myNumVehiclesIdle.value = (myVehicles.size - onTask - out).toDouble()
    }

    // ---- lifecycle -----------------------------------------------------------------------------

    /**
     * Creates a fresh agent for every vehicle and for the dispatcher, and activates them.
     *
     * Fresh, not reused: a `KSLProcess` is a coroutine that runs once, so an agent cannot be
     * restarted. And because these are created while the model is running, they are deliberately
     * *not* in `AgentModel.agents` -- which is why the fleet is enumerated from [vehicles] and why
     * the handle is assigned unconditionally rather than reused. A retained agent would carry the
     * previous replication's mailbox, which nothing resets for a runtime agent.
     */
    override fun initialize() {
        // The dispatcher first, so its process reaches its first hold before vehicles declare.
        // The dispatcher's pending-wake flag makes this an optimization rather than a requirement.
        val d = DispatcherAgent()
        dispatcher.agent = d
        activate(d.dispatch)
        for (v in myVehicles) {
            val a = VehicleAgent(v)
            v.agent = a
            activate(a.control)
        }
        refreshFleetCounts()
    }

    /**
     * Reports whatever was still in flight when the horizon fell, and wakes nothing.
     *
     * This runs for every element before `afterReplication` runs for any, so what it sees is the
     * live state. Resuming anything here would schedule a resume on a calendar that is about to be
     * discarded, and run model logic after the run has ended.
     */
    override fun replicationEnded() {
        super.replicationEnded()
        myUnfinishedTasks = dispatcher.board.tasks.size
        myLoadsAwaitingPickup = awaitingPickupHoldQ.size
        myLoadsInTransit = inTransitHoldQ.size
        // Pure reads of live state, and nothing is woken. This runs for every element before
        // afterReplication runs for any, which is both the only window in which the state below
        // still exists to be read and -- because a Response records itself in afterReplication --
        // safely before these observations are summarized.
        reportTasksNeverAssigned()
        reportEntitiesNeverResumed()
        reportAssignmentsStillOpen()
        reportVehiclesStranded()
        reportVehiclesFailed()
        // After the diagnostics rather than before them: if the audit is about to raise, the three
        // reports above are the context a reader will want, and they are already in the log.
        if (auditAtReplicationEnd) {
            myAudit.checkClosing()
        }
        // Vehicles still blocked are reported by the space layer's own horizon diagnostic, which
        // this subsystem inherits. Repeating it would put two warnings in the log for one condition
        // and invite a reader to think they were two.
    }

    /**
     * Tasks that were posted and never given to anyone.
     *
     * The quiet failure of a dispatching subsystem. A model whose fleet is too small, whose bidding
     * rule is too strict, or whose layout leaves a station unreachable produces a run that completes
     * normally, reports plausible statistics for the work it *did* do, and says nothing at all about
     * the work it did not. The averages are computed over the loads that were served, so a fleet
     * that served a third of its demand can look better than one that served all of it.
     */
    private fun reportTasksNeverAssigned() {
        val orphaned = dispatcher.board.tasks.filter { it.assignedAt.isNaN() }
        myNumTasksNeverAssigned.value = orphaned.size.toDouble()
        if (orphaned.isEmpty()) return
        logger.warn {
            buildString {
                append("Fleet ($name): ${orphaned.size} task(s) were posted and never assigned ")
                append("before replication ${model.currentReplicationNumber} ended. ")
                append("Statistics are computed over the loads that were served, so this run's ")
                append("averages describe a smaller problem than the one posed.")
                for (t in orphaned) {
                    append(System.lineSeparator())
                    append("  (${t.name}) posted at ${t.timeEnteredQueue}, ")
                    append("waiting ${time - t.timeEnteredQueue} for a vehicle to ")
                    append("(${t.pickupLocation})")
                }
            }
        }
    }

    /**
     * Entities suspended in this subsystem's hold queues when the horizon fell.
     *
     * Distinct from a task never assigned: these are loads that *were* being dealt with. Reported
     * separately because the two have different causes -- one says the fleet never got to the work,
     * the other says the run was too short for the work it did get to -- and a modeller acting on
     * the wrong one changes the wrong thing.
     */
    private fun reportEntitiesNeverResumed() {
        val stranded = awaitingPickupHoldQ.size + inTransitHoldQ.size
        myNumEntitiesNeverResumed.value = stranded.toDouble()
        if (stranded == 0) return
        logger.warn {
            buildString {
                append("Fleet ($name): $stranded entit(ies) were still suspended when ")
                append("replication ${model.currentReplicationNumber} ended -- ")
                append("${awaitingPickupHoldQ.size} awaiting collection, ")
                append("${inTransitHoldQ.size} aboard a vehicle. ")
                append("Their waits are not observations and are not in the statistics.")
                for (e in awaitingPickupHoldQ.immutableList) {
                    append(System.lineSeparator())
                    append("  (${e.name}) awaiting collection")
                }
                for (e in inTransitHoldQ.immutableList) {
                    append(System.lineSeparator())
                    append("  (${e.name}) aboard")
                }
            }
        }
    }

    /** Vehicles that were part-way through a task. Reported so a run that looks like it ran out of
     *  work can be told from one that was cut off in the middle of some. */
    private fun reportAssignmentsStillOpen() {
        val open = myVehicles.flatMap { v -> v.assignments.map { v to it } }
        myNumAssignmentsStillOpen.value = open.size.toDouble()
        if (open.isEmpty()) return
        logger.warn {
            buildString {
                append("Fleet ($name): ${open.size} assignment(s) were still open when ")
                append("replication ${model.currentReplicationNumber} ended.")
                for ((v, a) in open) {
                    append(System.lineSeparator())
                    append("  (${v.name}) was ${a.state} on task (${a.task.name}), ")
                    append("committed at ${a.madeAt} by ${a.decidedBy}")
                }
            }
        }
    }

    /**
     * Vehicles that ran out of charge and stopped where they stood.
     *
     * Reported apart from an assignment left open, because the two say different things: an open
     * assignment is work the run was too short to finish, while a stranded vehicle is a hole in the
     * layout that was there for part of the run. A reader who takes the second for the first
     * lengthens the run and gets the same answer.
     */
    private fun reportVehiclesStranded() {
        val stranded = myVehicles.filter { it.isStranded }
        myNumVehiclesStranded.value = stranded.size.toDouble()
        if (stranded.isEmpty()) return
        logger.warn {
            buildString {
                append("Fleet ($name): ${stranded.size} vehicle(s) ran out of charge during ")
                append("replication ${model.currentReplicationNumber} and stopped where they were. ")
                append("They kept whatever space they held for the rest of the run, so every route ")
                append("through it was closed and this run's congestion statistics describe a smaller ")
                append("layout than the one modelled.")
                for (v in stranded) {
                    append(System.lineSeparator())
                    append("  (${v.name}) at (${v.currentLocationName}), holding ")
                    append(spaceHeldBy(v))
                }
            }
        }
    }

    /** Vehicles broken down when the horizon fell, so that the work they were holding is visible. */
    private fun reportVehiclesFailed() {
        val failed = myVehicles.filter { it.isFailed }
        myNumVehiclesFailed.value = failed.size.toDouble()
        if (failed.isEmpty()) return
        logger.warn {
            buildString {
                append("Fleet ($name): ${failed.size} vehicle(s) were under repair when ")
                append("replication ${model.currentReplicationNumber} ended. A failed vehicle keeps ")
                append("its load, so any assignment and any suspended entity reported above may ")
                append("belong to one of these rather than to a run that was simply too short.")
                for (v in failed) {
                    append(System.lineSeparator())
                    append("  (${v.name}) at (${v.currentLocationName})")
                    for (a in v.assignments) append(", holding task (${a.task.name})")
                }
            }
        }
    }

    private var myUnfinishedTasks: Int = 0
    private var myLoadsAwaitingPickup: Int = 0
    private var myLoadsInTransit: Int = 0

    /** How many tasks were still outstanding when the last replication ended. */
    val unfinishedTasksAtHorizon: Int get() = myUnfinishedTasks

    /** How many loads were still waiting to be collected when the last replication ended. */
    val loadsAwaitingPickupAtHorizon: Int get() = myLoadsAwaitingPickup

    /** How many loads were still aboard a vehicle when the last replication ended. */
    val loadsInTransitAtHorizon: Int get() = myLoadsInTransit

    /**
     * Drops this replication's agent references.
     *
     * **Calls `super`**, and that is the whole of the teardown that matters: `ProcessModel`'s
     * implementation is what terminates every suspended participant, including a vehicle agent
     * dormant inside an unbounded loop. Overriding without calling it would leave those coroutines
     * suspended across the replication boundary, holding allocations, in queues that were then
     * cleared out from under them.
     */
    override fun afterReplication() {
        super.afterReplication()
        for (v in myVehicles) v.agent = null
    }

    // ---- the active participants ---------------------------------------------------------------
    // Inner classes because `AgentModel.Agent` is an inner class, so an agent can only be declared
    // inside its own agent model.

    /**
     * A vehicle's control loop: the object that makes this subsystem what it is.
     *
     * Under the passive paradigm there is nothing this could be a rewrite of. A passive transporter
     * has no loop, because it has no behaviour -- it is moved by whatever seized it. Here the
     * vehicle runs, decides when to declare itself available, drives its own body, and returns into
     * a loop that belongs to it.
     */
    internal inner class VehicleAgent(val vehicle: FleetVehicle) : Agent("${vehicle.name}:Agent") {

        /**
         * Everything this vehicle is committed to, in the order it was committed.
         *
         * A list rather than a slot because a vehicle with capacity will hold several, and because
         * the readers that mean *any commitment at all* -- the fleet counts, the horizon
         * diagnostic, `FracTimeOnTask` -- should say so rather than testing a slot for null. It
         * never holds more than one today: `FleetVehicle` refuses a load capacity above one, so the
         * plural storage is in place and unused, which is what makes this step inert.
         *
         * Ordered, never a set: `C1` requires that declaration order cannot change an answer, and a
         * set would make the order of a vehicle's commitments an accident of hashing.
         */
        internal val assignments = mutableListOf<Assignment>()

        /**
         * The one commitment this vehicle holds, or null.
         *
         * The control loop is written for a single assignment and stays that way until the tour
         * becomes a plan over several. Reading and writing through here keeps that loop unchanged
         * while the storage underneath it is already plural.
         */
        internal val assignment: Assignment?
            get() = assignments.firstOrNull()

        /** True when this vehicle is committed to anything at all. */
        internal val hasAssignment: Boolean
            get() = assignments.isNotEmpty()

        /** How many calls for proposals this vehicle answered, and how many it declined. Kept on the
         *  agent rather than the vehicle because they are per-replication facts about a negotiation,
         *  and because a test that could not see them would have to infer declining from silence. */
        internal var bidsSubmitted: Int = 0
            private set
        internal var callsDeclined: Int = 0
            private set

        init {
            // Answering a call for proposals is done by a mailbox arrival handler rather than by the
            // control loop, and the reason is structural. A vehicle spends almost all of its time
            // suspended -- dormant, travelling, loading -- so a loop that had to be *at* a receive
            // point to hear a call would only ever bid when it happened to be idle, which is exactly
            // the vehicle a dispatcher least needs to ask about. An arrival handler answers wherever
            // the vehicle is.
            //
            // It also has to be non-suspending, and that is not a limitation to work around: a bid
            // is delivered synchronously inside the initiator's broadcast, which is what lets an
            // auction with a zero deadline collect every bid rather than none. `BidPolicyIfc.bid`
            // is a plain function, so the type system enforces this rather than a comment.
            mailbox.onArrival { message -> respond(message) }
        }

        /**
         * Gives up the current assignment, wherever the vehicle has got to with it.
         *
         * Called only from `Dispatcher.revoke`, which has already checked that the load is not
         * aboard. The vehicle keeps its body allocation and its place in the movement queue: it is
         * still a vehicle that is somewhere and may be moving, and the loop it will return into is
         * the same one. What it loses is the reason it was going there.
         *
         * The tour is dropped rather than advanced, because a revoked tour's remaining stops belong
         * to a task this vehicle no longer holds. Nothing is redirected here: the vehicle's own loop
         * discovers the change when its current leg ends, and the dispatcher's next pass -- which
         * `revoke` has already arranged by re-declaring availability -- either turns it round in
         * flight through `deliverAssignment` or leaves it to finish where it was going and then ask
         * for work. Redirecting from here as well would race with that.
         */
        internal fun abandonAssignment(assignment: Assignment) {
            assignments.removeAll { it === assignment }
            // The tour keeps its stops. `stillOurs` passes over any whose task is no longer this
            // vehicle's, and editing a tour while it is being walked would shift the very stop the
            // loop is pointing at. A vehicle carrying two loads that has one taken back still has
            // the other to deliver, and its round is still its round.
        }

        private fun respond(message: AgentMessage) {
            when (message) {
                is AgentMessage.Request<*> -> {
                    val cfp = message.payload as? CallForProposals ?: return
                    // Handled: take it out of the mailbox so calls do not accumulate over a run.
                    mailbox.consume(message)
                    val initiator = message.from as? Agent ?: return
                    val bid = vehicle.bidPolicy.bid(vehicle, cfp, space)
                    if (bid == null) {
                        // Declining is ordinary operation, not a failure. Saying nothing is how a
                        // vehicle declines; there is deliberately no "I decline" message, because a
                        // dispatcher that received one would have to distinguish it from a bid.
                        callsDeclined++
                        return
                    }
                    bidsSubmitted++
                    initiator.mailbox.deliver(
                        AgentMessage.Propose(this, bid, message.conversationId!!)
                    )
                }
                // The outcome of a negotiation reaches this vehicle as an assignment through the
                // ordinary dispatching path, so these carry no information it needs. They are
                // consumed rather than ignored: an unread message is a slow leak within a
                // replication, and a mailbox that fills is a bug that only shows up in long runs.
                is AgentMessage.Accept -> mailbox.consume(message)
                is AgentMessage.Reject -> mailbox.consume(message)
                else -> Unit
            }
        }

        internal var tour: Tour? = null
            private set

        /** True once a disposition has been considered since the last task, so that a vehicle with
         *  nothing to do settles instead of reconsidering forever at the same instant. */
        private var disposed: Boolean = false

        // Every suspension in this loop is written at the call site. There are no private
        // suspending helpers, so a reader can see every point at which simulated time passes and
        // the world can change underneath the vehicle; the non-suspending bookkeeping is on
        // FleetVehicle and Dispatcher as ordinary methods. The one call that runs somebody else's
        // suspending code -- the interruption policy -- appears twice rather than being factored
        // out, which is the price of the rule and is worth paying: the two places are the two
        // moments a vehicle can stop, and a reader who cannot see both cannot see the feature.
        //
        // That restraint takes effort, because the language pushes the other way. KSLProcessBuilder
        // is @RestrictsSuspension, so an extension on the builder is the *only* way to factor a
        // suspending helper out of a process body -- which makes reaching for one the path of least
        // resistance precisely where it does the most damage to readability.
        val control: KSLProcess = process("${vehicle.name}:control") {
            while (true) {                                  // terminated by afterReplication
                // Before anything else, and in particular before declaring availability. Two things
                // arrive here: an interruption a movement gate raised part way through a journey
                // that ended somewhere other than where it was going, and a failure that has come
                // due since the last one. Handling both at this point is what makes "nothing may be
                // assigned to a vehicle under repair" true by construction rather than a condition
                // somebody has to check -- the vehicle has not said it is available yet.
                val settle = vehicle.takeInterruption()
                if (settle != null) {
                    with(vehicle.interruptionPolicy) { handle(settle) }     // SUSPENDS, usually
                    vehicle.interruptionEnded(settle)
                    if (!vehicle.isFitToContinue) {
                        // The policy did not put it right, so it is out for the rest of the
                        // replication -- standing where it stopped, keeping its space. Nothing
                        // resumes this queue.
                        hold(outOfServiceQ,                                 // SUSPENDS, forever
                            suspensionName = "${vehicle.name}:outOfService")
                    }
                }
                if (assignment == null) {
                    dispatcher.declareAvailable(vehicle)
                    // SUSPENDS. The dispatcher resumes us either with work or, having none for us,
                    // without -- which is the only way to reach the disposition branch below.
                    hold(availabilityQ, suspensionName = "${vehicle.name}:awaitingWork")
                }
                val a = assignment
                if (a == null) {
                    // The dispatcher has had its pass and has nothing, so anybody still aboard is
                    // set down where the vehicle stands. This is the only place it happens: between
                    // one cycle of a service and the next the vehicle passes through the
                    // availability hold above with people aboard, and setting them down there would
                    // make a circular service put its passengers off every lap.
                    putDownRidersNotServedBy(emptySet())
                    // Work beats disposition, structurally: we are only here because the dispatcher
                    // has already had its pass and declined.
                    if (disposed) {
                        // Nothing to do and nowhere to go. Dormant until an assignment arrives.
                        hold(availabilityQ, suspensionName = "${vehicle.name}:dormant")
                    } else {
                        disposed = true
                        // Deliberately NOT withdrawn. A vehicle moving for its own reasons stays
                        // assignable and is turned round in flight if work arrives; see
                        // deliverAssignment. Withdrawing here costs real time in system.
                        when (val d = vehicle.dispositionPolicy.disposition(vehicle)) {
                            is Disposition.ParkInPlace -> Unit
                            is Disposition.ReturnToHomeBase -> {
                                val home = vehicle.homeBase
                                val q = if (home == null) null else vehicle.beginTravelTo(
                                    home, MovePurpose.HOME, this@VehicleAgent)
                                if (q != null) {
                                    hold(q,                                 // SUSPENDS
                                        suspensionName = "${vehicle.name}:returningHome")
                                }
                            }
                            is Disposition.MoveTo -> {
                                val q = vehicle.beginTravelTo(
                                    d.locationName, MovePurpose.HOME, this@VehicleAgent)
                                if (q != null) {
                                    hold(q,                                 // SUSPENDS
                                        suspensionName = "${vehicle.name}:repositioning")
                                }
                            }
                            is Disposition.GoCharge -> {
                                val q = vehicle.beginTravelTo(
                                    d.locationName, MovePurpose.HOME, this@VehicleAgent)
                                if (q != null) {
                                    hold(q,                                 // SUSPENDS
                                        suspensionName = "${vehicle.name}:goingToCharge")
                                }
                                // Work may have arrived while it drove and turned it round in
                                // flight, in which case it is somewhere else on somebody's business
                                // and there is nothing here to charge. A vehicle stopped short of
                                // the charger has not reached one either; the top of the loop
                                // settles whatever stopped it.
                                if (assignment == null && !vehicle.hasPendingInterruption) {
                                    val duration = vehicle.beginCharging()
                                    if (duration > 0.0) {
                                        delay(duration,                     // SUSPENDS
                                            suspensionName = "${vehicle.name}:charging")
                                    }
                                    vehicle.endCharging()
                                }
                            }
                        }
                    }
                    continue
                }

                disposed = false
                dispatcher.withdraw(vehicle)   // committed now; not assignable until the tour ends
                vehicle.taskStarted()
                refreshFleetCounts()
                val allocation = seize(vehicle.body.seizable, 1, queue = vehicle.bodyQ)
                // Everything committed at this moment, planned as one itinerary. A vehicle given
                // several tasks in one dispatching pass makes one round rather than several.
                val committed = assignments.toList()
                val t = tourFor(committed).also { it.startedAt = time; tour = it }
                // A rider does not get off at the end of a cycle. A circular service that set
                // everybody down each lap could never carry anybody the long way round, which is
                // most of what a circular service is for; and a load aboard is aboard, whatever the
                // vehicle's paperwork says. What it cannot do is stay on a vehicle that is no
                // longer going where it is going, so this round's stops decide who keeps their
                // seat.
                putDownRidersNotServedBy(t.stops.map { it.location }.toSet())
                val blockedAtStart = vehicle.body.cumulativeBlockedTime
                val failedAtStart = vehicle.cumulativeFailedTime
                while (!t.isComplete) {
                    val stop = t.nextStop!!
                    // Asked BEFORE the leg, which is what makes a skip mean "do not make this
                    // journey" rather than "drive all the way there and then refuse". The vehicle
                    // may still pass through the place, because the route between the two remaining
                    // stops belongs to the space layer and knows nothing about tours -- and that is
                    // exactly express running.
                    val instruction =
                        with(vehicle.stopControl) { instruct(vehicle, stop, t) }   // MAY SUSPEND
                    if (instruction is StopInstruction.Skip) {
                        if (stop.action.task != null) {
                            throw FleetProtocolException(
                                "Vehicle (${vehicle.name}) was instructed to skip a stop serving " +
                                        "task (${stop.action.task!!.name}). A control may run past " +
                                        "a service stop; it may not skip a commitment the " +
                                        "dispatcher made, because there is a load suspended on it " +
                                        "that nothing else would ever set down."
                            )
                        }
                        val carried = vehicle.ridersBoundFor(stop.location).size
                        vehicle.stopSkipped(carried)
                        stop.action.servesStop?.passedBySkipped()
                        t.advance()
                        continue
                    }
                    // A leg is re-issued until the vehicle actually gets there. Under an ordinary
                    // journey this loop runs once. It runs again whenever a movement gate stopped
                    // the vehicle short and its policy dealt with whatever stopped it -- which may
                    // have left it somewhere else entirely, on a spur it was pushed to. The tour
                    // survives that because a tour names *stops*, not routes.
                    while (true) {
                        // beginTravelTo commands the body and returns whether a journey is under
                        // way. `this@VehicleAgent` is the waiter: the AGENT sits in the space
                        // layer's movement queue, never the load.
                        val travelQ = vehicle.beginTravelTo(
                            stop.location, MovePurpose.SERVICE, this@VehicleAgent
                        ) ?: break                                          // already there
                        hold(travelQ,                                       // SUSPENDS
                            suspensionName = "${vehicle.name}:travellingTo:${stop.location}")
                        // The assignment can be revoked while we travel, and if it was, this stop
                        // belongs to a task we no longer hold. `t` is a local, so without this
                        // check the loop would go on to collect a load that has been given to
                        // someone else -- and the model would keep running, with two vehicles
                        // believing they had it.
                        if (!stillOurs(stop)) break
                        // Nothing pending means the journey ended the ordinary way: it arrived.
                        val interruption = vehicle.takeInterruption() ?: break
                        with(vehicle.interruptionPolicy) { handle(interruption) }   // SUSPENDS
                        vehicle.interruptionEnded(interruption)
                        if (!vehicle.isFitToContinue) {
                            hold(outOfServiceQ,                             // SUSPENDS, forever
                                suspensionName = "${vehicle.name}:outOfService")
                        }
                        // A dispatcher told about the breakdown may have taken the task off this
                        // vehicle while its policy ran, and if it did, the stop we were travelling
                        // to belongs to somebody else now.
                        if (!stillOurs(stop)) break
                    }
                    if (!stillOurs(stop)) {
                        // Revoked while we travelled. Its stops are no longer ours to make, so pass
                        // over this one; the others in the tour are still this vehicle's work.
                        t.advance()
                        continue
                    }
                    // The action decides what arriving means. The loop knows only that the
                    // vehicle is here, which is why a tour of stops written by somebody else runs
                    // through exactly this code.
                    val visit = StopVisit(stop, t, blockedAtStart, failedAtStart)
                    with(stop.action) { perform(visit) }    // MAY SUSPEND
                    // Held after the action rather than before it, because a departure time is a
                    // statement about leaving and a vehicle that has not served the stop has not
                    // arrived in any sense that matters. An instant already past holds nothing,
                    // which is what a service running late does.
                    if (instruction is StopInstruction.Serve && time < instruction.departNotBefore) {
                        delay(instruction.departNotBefore - time,        // SUSPENDS
                            suspensionName = "${vehicle.name}:holdingFor:${stop.location}")
                    }
                    t.advance()
                    // A short turn ends the round here. Whatever it had left is discharged below,
                    // exactly as a tour abandoned any other way is.
                    if (instruction is StopInstruction.ServeAndEndTour) break
                }
                release(allocation)
                for (open in committed) if (assignments.any { it === open }) {
                    // Whatever no stop of its own discharged. An errand has no set-down, so this is
                    // where one ends. A tour abandoned part-way through is not a completion, and
                    // counting it as one would let a fleet report more deliveries than there were
                    // loads -- which is why the guard is still on holding the assignment.
                    completeAssignment(open)
                }
                // What this round actually carried, counted from the tour rather than from the
                // manifest, which is empty again by now.
                vehicle.tourCompleted(t.stops.sumOf { maxOf(it.action.loadChange, 0) })
                tour = null
                vehicle.taskEnded()
                refreshFleetCounts()
            }
        }

        /**
         * Sets down every rider bound for somewhere not in [served], where the vehicle stands.
         *
         * Not a suspension: unloading a rider whose service was withdrawn is not the unloading
         * delay of a planned set-down, and charging one would put time on the clock for an event
         * that did not happen.
         */
        private fun putDownRidersNotServedBy(served: Set<String>) {
            for (ride in vehicle.ridersAboard.toList()) {
                if (ride.destination in served) continue
                val here = vehicle.currentLocationName
                ride.rider.currentLocation = space.requireLocation(here)
                vehicle.body.alight(ride.rider)
                vehicle.rideAlighted(ride)
                stops.firstOrNull { it.location == here }?.alighted()
                inTransitHoldQ.removeAndResume(ride.rider)
            }
        }

        /** This vehicle's commitment to [task]. */
        private fun assignmentFor(task: Dispatcher.Task): Assignment =
            assignments.first { it.task === task }

        /**
         * True when the task this stop acts on is still one of this vehicle's commitments.
         *
         * A stop for a task taken back is not this vehicle's to make. Asking per stop rather than
         * per tour is what lets a revocation take one task off a vehicle that is carrying others.
         */
        private fun stillOurs(stop: TourStop): Boolean {
            val task = stop.action.task ?: return true
            return assignments.any { it.task === task }
        }

        /**
         * Ends one commitment: tells the dispatcher, drops it, and counts the delivery.
         *
         * Called from the stop that discharges it, or from the end of the tour for a commitment no
         * stop discharges. Idempotent, so the two paths cannot double-count.
         */
        private fun completeAssignment(a: Assignment) {
            if (a.state == AssignmentState.COMPLETED) return
            dispatcher.completed(a)
            assignments.removeAll { it === a }
            vehicle.taskCompleted()
        }

        /**
         * What an action is handed when the vehicle reaches a stop.
         *
         * The two verbs are here rather than on the action because they must be done in exactly one
         * way and that way reaches into things an action written outside this library cannot see:
         * the manifest, the dispatcher's account of the commitment, and the hold queues the loads
         * are suspended in. An action says *that* a load goes aboard; this says what going aboard
         * consists of.
         *
         * The two baselines are the vehicle's blocked and failed clocks as the round began, so that
         * every interval reported about a load is measured from the vehicle's commitment to it
         * rather than from the leg that happened to reach it.
         */
        private inner class StopVisit(
            override val stop: TourStop,
            override val tour: Tour,
            private val blockedAtTourStart: Double,
            private val failedAtTourStart: Double
        ) : StopContextIfc {

            override val vehicle: FleetVehicle
                get() = this@VehicleAgent.vehicle

            override val onwardLocations: Set<String>
                get() = if (tour.cyclic) {
                    // A round trip comes back, so everywhere it calls is still ahead of it -- on
                    // this lap or the next one, which is the same vehicle either way. Only where
                    // the vehicle is standing is behind it.
                    tour.stops.map { it.location }.toSet() - stop.location
                } else {
                    tour.remainingStops.drop(1).map { it.location }.toSet()
                }

            override suspend fun KSLProcessBuilder.takeAboard(task: Dispatcher.TransportTask) {
                task.blockedAtPickup = vehicle.body.cumulativeBlockedTime - blockedAtTourStart
                // From the moment a vehicle was committed to this load, not from the moment it
                // began the leg. The two differ by however long the vehicle took to disengage from
                // what it was doing, and the passive subsystem counts that: its clock starts when
                // the transporter is allocated. The row is shared, so it has to mean one thing --
                // and measured this way the two paradigms agree to the digit on the same shop.
                task.approachTime = time - task.assignedAt
                task.failedBeforePickup = vehicle.cumulativeFailedTime - failedAtTourStart
                if (task.loadingDelay != ConstantRV.ZERO) {
                    delay(task.loadingDelay,                // SUSPENDS
                        suspensionName = "${vehicle.name}:loading")
                }
                task.carriedBy = vehicle
                // Aboard. From here the body's moving state is derived rather than asserted: the
                // next leg is loaded because something is on it.
                vehicle.body.board(task.load)
                // This load's own marks, taken now. A vehicle carrying several sets them down at
                // different stops, and each ride is measured from the moment *that* load went
                // aboard.
                task.pickedUpAt = time
                task.distanceAtPickup = vehicle.body.distanceTravelled
                task.zonesAtPickup = vehicle.body.zonesEntered
                // Dequeuing the TASK is what ends its recorded wait, and doing it here rather than
                // at assignment is what makes the queue's time in queue the load's wait for
                // transport.
                dispatcher.tookPossession(assignmentFor(task))
                awaitingPickupHoldQ.removeAndResume(task.load)
            }

            override suspend fun KSLProcessBuilder.setDown(task: Dispatcher.TransportTask) {
                task.loadedRouteLength = vehicle.body.distanceTravelled - task.distanceAtPickup
                task.loadedZonesTraversed = vehicle.body.zonesEntered - task.zonesAtPickup
                // Includes any time the vehicle spent broken down with the load aboard. These are
                // protocol intervals rather than statements about the vehicle's state, and the load
                // was aboard throughout; `failedWhileLoaded` below is what separates the two out
                // for a study that needs it.
                task.rideTime = time - task.pickedUpAt
                if (task.unLoadingDelay != ConstantRV.ZERO) {
                    delay(task.unLoadingDelay,              // SUSPENDS
                        suspensionName = "${vehicle.name}:unloading")
                }
                task.blockedWhileLoaded =
                    vehicle.body.cumulativeBlockedTime - blockedAtTourStart - task.blockedAtPickup
                task.failedWhileLoaded = vehicle.cumulativeFailedTime -
                        failedAtTourStart - task.failedBeforePickup
                task.load.currentLocation = space.requireLocation(task.destination)
                vehicle.body.alight(task.load)
                task.transitionTo(TaskState.COMPLETED)
                // Discharged by its own last stop rather than by the tour ending. With one task
                // those are the same instant; with several, a vehicle that waited for the tour
                // would report four deliveries at the moment of the fourth, and the three loads set
                // down earlier would each have been delivered without the fleet counting it yet.
                completeAssignment(assignmentFor(task))
                inTransitHoldQ.removeAndResume(task.load) // the verb returns
            }

            override suspend fun KSLProcessBuilder.takeAboard(ride: Stop.Ride) {
                if (vehicle.spareCapacity <= 0) {
                    throw FleetProtocolException(
                        "Vehicle (${vehicle.name}) was asked to board (${ride.rider.name}) at " +
                                "(${ride.stop.name}) with no room: it holds " +
                                "${vehicle.numLoadsAboard} of ${vehicle.loadCapacity}. A boarding " +
                                "action must check spareCapacity as it takes people, because how " +
                                "many it can take is not known until it arrives."
                    )
                }
                // Out of the stop's line first, so the wait it reports ends at the instant the
                // vehicle took the rider rather than at the end of the whole visit.
                ride.stop.departing(ride)
                ride.boardedAt = time
                ride.carriedBy = vehicle
                vehicle.body.board(ride.rider)
                vehicle.rideBoarded(ride)
                awaitingBoardingHoldQ.removeAndResume(ride.rider)
            }

            override suspend fun KSLProcessBuilder.setDown(ride: Stop.Ride) {
                val here = space.requireLocation(ride.destination)
                ride.rider.currentLocation = here
                vehicle.body.alight(ride.rider)
                vehicle.rideAlighted(ride)
                ride.stop.system.stops.firstOrNull { it.location == ride.destination }
                    ?.alighted()
                inTransitHoldQ.removeAndResume(ride.rider)      // the verb returns
            }

            override suspend fun KSLProcessBuilder.holdAt(stop: Stop, until: Double) {
                stop.wakeHoldingVehiclesAt(until)
                hold(stop.vehiclesHolding,                      // SUSPENDS
                    suspensionName = "${vehicle.name}:holdingAt:${stop.name}")
            }
        }

        /** The route metadata the setdown stop reads is captured before the route is cleared; this
         *  turns one task into the stops that discharge it. A two-stop tour today, and the loop
         *  above does not know that. */
        private fun stopsFor(task: Dispatcher.Task): List<TourStop> = when (task) {
            is Dispatcher.TransportTask -> listOf(
                TourStop(task.origin, PickUp(task)),
                TourStop(task.destination, SetDown(task))
            )
            is Dispatcher.ServiceTask -> listOf(TourStop(task.destination, Reposition))
            // A declared service contributes its whole cycle, which is the point: the loop that
            // walks a two-stop transport is the loop that walks a twelve-stop bus route.
            is Dispatcher.LineTask -> task.line.cycle()
            else -> throw IllegalStateException("Unknown task type ${task::class.simpleName}")
        }

        /**
         * Turns everything this vehicle is committed to into one itinerary.
         *
         * The tasks are folded in one at a time through the dispatcher's tour policy, which is what
         * makes the order a *decision* rather than a consequence of the order the dispatcher
         * happened to hand them over. With one task the policy has nothing to choose and returns
         * pickup then set-down, which is the tour a single-load transport has always had.
         */
        private fun tourFor(committed: List<Assignment>): Tour {
            var stops = emptyList<TourStop>()
            for (a in committed) {
                stops = dispatcher.planTour(vehicle, stops, stopsFor(a.task))
            }
            // A round trip only where the whole round is one declared loop. A cycle with other work
            // spliced into it is no longer a promise to come back, so a boarding action must not be
            // told that it is.
            val line = (committed.singleOrNull()?.task as? Dispatcher.LineTask)?.line
            return Tour(stops, cyclic = line?.cyclic == true)
        }

    }

    /** The dispatcher's process. Dormant until something happens that could change a decision. */
    internal inner class DispatcherAgent : Agent("${dispatcher.name}:Agent") {

        val dispatch: KSLProcess = process("${dispatcher.name}:dispatch") {
            while (true) {                                  // terminated by afterReplication
                if (!dispatcher.consumeWake()) {
                    // SUSPENDS. Woken by a posting, an availability declaration, or a policy timer.
                    hold(dispatcherIdleQ, suspensionName = "${dispatcher.name}:idle")
                }
                val context = DispatchContext(
                    dispatcher.board, dispatcher.availableVehicles, space, time, dispatcher
                )
                // The policy may consume simulated time -- that is what makes batching and auctions
                // expressible -- so this call is a suspension point even though Phase 1's policy
                // returns immediately. `with` supplies this process builder as the policy's
                // receiver, which KSLProcessBuilder's @RestrictsSuspension requires and which is
                // also what lets a policy delay, hold or run an auction.
                val proposals = with(dispatcher.assignmentPolicy) { assign(context) }
                dispatcher.applyProposals(proposals)
            }
        }
    }

    override fun toString(): String =
        "${this::class.simpleName}($name, ${myVehicles.size} vehicles, over ${space.name})"
}
