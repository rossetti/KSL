package ksl.modeling.fleet

import ksl.controls.KSLStringControl
import ksl.modeling.fleet.policies.createAssignmentPolicy
import ksl.modeling.fleet.policies.nameOfAssignmentPolicy
import ksl.modeling.fleet.exceptions.FleetDispatchException
import ksl.modeling.fleet.exceptions.FleetProtocolException
import ksl.modeling.fleet.policies.TourPolicyIfc
import ksl.modeling.fleet.policies.CheapestInsertionTourPolicy
import ksl.modeling.fleet.policies.TourContext
import ksl.modeling.fleet.policies.validateTour
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.TaskSelectionRuleIfc
import ksl.modeling.entity.ProcessModel
import ksl.modeling.queue.Queue
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc

/**
 * Decides which vehicle goes where, and owns the line of work waiting to be done.
 *
 * The existence of this object is the design. Under the passive paradigm the equivalent decision is
 * made inside a pool's allocation rule, at the moment an entity happens to ask, over whichever
 * vehicles happen to be free -- which makes batching, look-ahead and auctions inexpressible, not
 * because they are hard but because there is nowhere to put them. Here the decision has a home, a
 * process of its own, and the ability to consume simulated time while deciding.
 *
 * It owns the [TaskQ] rather than delegating it to the system or accepting one from the modeller,
 * because that queue is not a holding pen the subsystem happens to need. It is this object's
 * pending-work list: the collection its policy ranks, a batching policy batches, and an auction
 * announces from. A queue supplied from outside would force the dispatcher to reach into a foreign
 * object to do its one job.
 */
open class Dispatcher @JvmOverloads constructor(
    val system: FleetSystem,
    assignmentPolicy: AssignmentPolicyIfc = NearestVehiclePolicy(),
    tourPolicy: TourPolicyIfc = CheapestInsertionTourPolicy(),
    discipline: Queue.Discipline = Queue.Discipline.FIFO,
    name: String? = null
) : ModelElement(system, name ?: "Dispatcher") {

    /** Substitutable while the model is not running. */
    var assignmentPolicy: AssignmentPolicyIfc = assignmentPolicy
        set(value) {
            require(model.isNotRunning) { "The assignment policy cannot be changed while the model is running." }
            field = value
        }

    /**
     * The assignment policy by name, so a scenario or an app can change it without holding a policy
     * object.
     *
     * **Only the policies that a name fully defines have names.** A batching window, a contract-net
     * deadline, a re-assignment threshold and a scoring function are not tunings of a policy -- they
     * are the policy -- so a name standing for one of them would let a study vary the label while
     * freezing the number. Assign the policy to use those, and read this property to see what is in
     * force: it reports the policy's own `toString` when no name stands for it.
     */
    @set:KSLStringControl(
        allowedValues = ["PullFromBoard", "NearestVehicle", "FurthestVehicle", "LeastUsedVehicle", "Consolidating"],
        comment = "Which rule the dispatcher uses to assign a vehicle to a task"
    )
    var assignmentPolicyName: String
        get() = nameOfAssignmentPolicy(assignmentPolicy) ?: assignmentPolicy.toString()
        set(value) {
            assignmentPolicy = createAssignmentPolicy(value)
        }

    /**
     * The order a vehicle visits the stops it has committed to. Substitutable while not running.
     *
     * The dispatcher's, not the vehicle's: with more than one task the order decides which loads a
     * vehicle takes and in what sequence, and that is a dispatching decision (`A7`). A vehicle
     * executes tours and never authors one.
     */
    var tourPolicy: TourPolicyIfc = tourPolicy
        set(value) {
            require(model.isNotRunning) { "The tour policy cannot be changed while the model is running." }
            field = value
        }

    /**
     * Fits a newly committed task into a vehicle's itinerary, and checks the answer.
     *
     * Validation is not distrust of the shipped policies; it is what makes a *modeller's* policy
     * safe to write. An insertion can be the cheapest available and still infeasible, and a
     * framework that quietly repaired the order would have taken the decision away from the policy
     * that made it.
     */
    internal fun planTour(
        vehicle: FleetVehicle,
        remaining: List<TourStop>,
        insert: List<TourStop>
    ): List<TourStop> {
        val context = TourContext(vehicle, system.space)
        val planned = tourPolicy.plan(context, remaining, insert)
        validateTour(
            tourPolicy, remaining + insert, planned,
            vehicle.loadCapacity, vehicle.numLoadsAboard, vehicle.name
        )
        return planned
    }

    private val myTaskQ: TaskQ = TaskQ(this, "${this.name}:TaskQ", discipline)

    /**
     * The waiting line, and the only queue this subsystem reports.
     *
     * Its time in queue is the load's wait for transport: posting to pickup. Its number in queue is
     * the outstanding work.
     */
    val taskQ: QueueCIfc<Task>
        get() = myTaskQ

    /** Orders what a policy sees. Null means the queue discipline alone decides. */
    var taskSelectionRule: TaskSelectionRuleIfc?
        get() = myTaskQ.taskSelectionRule
        set(value) {
            myTaskQ.taskSelectionRule = value
        }

    /** The read-only view handed to policies. Holds no tasks of its own. */
    val board: TaskBoard = TaskBoard(myTaskQ)

    // ---- the available set ---------------------------------------------------------------------
    // A vehicle is available because it said so, never because the dispatcher worked it out (A6).
    // `newlyDeclared` is drained on every pass: a vehicle that has declared and not been given work
    // is resumed once, with no assignment, which is its cue to consider a disposition. It then goes
    // dormant and stays in `available` until a task arrives for it.

    private val myAvailable = mutableListOf<FleetVehicle>()
    private val myNewlyDeclared = mutableListOf<FleetVehicle>()

    val availableVehicles: List<FleetVehicle>
        get() = myAvailable.toList()

    internal fun isAvailable(vehicle: FleetVehicle): Boolean = myAvailable.contains(vehicle)

    /**
     * The live assignment for a task, or null when nobody is committed to it.
     *
     * A vehicle holds its assignment, so this is a search over the fleet rather than a lookup. That
     * is deliberate: an index would be a second place the pairing is recorded, and two records of
     * one fact is exactly the arrangement that lets a revocation update one and not the other. The
     * fleet is small enough that the search costs nothing worth the risk.
     */
    fun assignmentFor(task: Task): Assignment? =
        system.vehicles.firstNotNullOfOrNull { v ->
            // Every commitment, not the first. A vehicle that holds several has a second, and a
            // search that stopped at the first would report a live assignment as absent -- which is
            // how a task ends up looking abandoned while a vehicle is on its way to collect it.
            v.assignments.firstOrNull { it.task === task }
        }

    internal var agent: FleetSystem.DispatcherAgent? = null

    /** Set when the dispatcher is woken while it is not dormant, so the wake is not lost. */
    private var wakePending: Boolean = false

    /**
     * Instructions a controller has left for particular vehicles, consumed on the vehicle's next
     * ask.
     *
     * The push half of the stop-control seam. It is a pending slot rather than a queue because a
     * controller that has changed its mind has changed its mind: the second instruction replaces
     * the first, and an instruction the vehicle never got round to asking for is stale by the time
     * it would have been read.
     */
    private val myInstructions = mutableMapOf<FleetVehicle, StopInstruction>()

    /**
     * Tells [vehicle] what to do about the next stop it asks about.
     *
     * The "be told" direction of stop control: a controller that has decided this vehicle is
     * running late leaves a `Skip` here, and the vehicle collects it when its
     * [ksl.modeling.fleet.policies.DispatcherStopControl] next asks. Nothing happens until it does,
     * which is deliberate -- a vehicle mid-leg is somewhere, and an instruction that took effect
     * between stops would be an instruction about nothing.
     *
     * Has no effect on a vehicle whose control does not consult the dispatcher.
     */
    fun instruct(vehicle: FleetVehicle, instruction: StopInstruction) {
        myInstructions[vehicle] = instruction
    }

    /** Takes whatever was left for this vehicle, if anything. */
    internal fun takeInstruction(vehicle: FleetVehicle): StopInstruction? =
        myInstructions.remove(vehicle)


    // ---- tasks -----------------------------------------------------------------------------
    // Task types are inner classes because QObject is an inner class of ModelElement, exactly as
    // Conveyor.ConveyorRequest is an inner class of Conveyor. A useful consequence: a task cannot
    // exist without a dispatcher to record its wait, so there is no window in which a caller holds
    // an unposted task.

    /**
     * Something a vehicle may be asked to do.
     *
     * A `QObject`, so the task itself waits in the [TaskQ] and carries the waiting statistics. The
     * load does not wait in a reported queue; its task does. That is also what gives an assignment
     * policy a first-class object to rank and a bidding policy something to bid on.
     *
     * Not `sealed`: Kotlin refuses `sealed inner`, and `inner` is forced by `QObject`. The
     * hierarchy is closed at two members by construction, and the exhaustiveness that matters lives
     * on [ServiceKind] instead.
     */
    abstract inner class Task internal constructor(aName: String? = null) : QObject(aName) {

        /** Where the vehicle must end up. */
        abstract val destination: String

        /** Where the vehicle must go first. The destination itself for a task with nothing to
         *  collect, which is what makes a service task a one-stop tour. */
        abstract val pickupLocation: String

        /** The entity suspended on this task, if any. Null for a task a vehicle raised for itself. */
        abstract val waitingEntity: ProcessModel.Entity?

        var state: TaskState = TaskState.POSTED
            internal set

        /** When a vehicle committed. NaN until then. */
        var assignedAt: Double = Double.NaN
            internal set

        val isTerminal: Boolean
            get() = state == TaskState.COMPLETED || state == TaskState.CANCELLED

        /** The dispatcher that created it, so a task always knows where its wait is recorded. */
        val dispatcher: Dispatcher
            get() = this@Dispatcher

        internal fun transitionTo(next: TaskState) {
            val legal = when (state) {
                TaskState.POSTED -> next == TaskState.ASSIGNED || next == TaskState.CANCELLED
                // ASSIGNED -> COMPLETED is legal only for a task with nothing waiting on it.
                // IN_PROGRESS means *the load is aboard*, which is also the instant the assignment
                // stops being revocable (`A4`). A task with no load never has that instant, so
                // requiring it to pass through IN_PROGRESS would demand a transition that can never
                // legitimately happen -- and did: a service task that ran to completion raised here.
                // The consequence is right as well as necessary: a vehicle on a self-directed errand
                // stays re-taskable until the errand is done, which is exactly what `cancel` promises.
                TaskState.ASSIGNED ->
                    next == TaskState.IN_PROGRESS || next == TaskState.POSTED ||
                            next == TaskState.CANCELLED ||
                            (next == TaskState.COMPLETED && waitingEntity == null)
                TaskState.IN_PROGRESS -> next == TaskState.COMPLETED
                TaskState.COMPLETED, TaskState.CANCELLED -> false
            }
            if (!legal) {
                throw FleetProtocolException(
                    "Task (${this.name}) cannot go from $state to $next."
                )
            }
            state = next
            if (next == TaskState.CANCELLED) {
                // Counted where the task is cancelled rather than where someone asked for it,
                // because two different callers can ask: the dispatcher's own `cancel`, and
                // `TaskQ.removeAndTerminate`, which is the *only* way to withdraw a transport
                // request. Counting at the call site meant every load withdrawn by termination left
                // the accounts one short -- posted, and then neither completed, nor cancelled, nor
                // waiting anywhere. A count of how many tasks ended cancelled must not depend on
                // which route they took to get there.
                myNumTasksCancelled.increment()
            }
        }

        /** `QObject` supplies `id`, `name`, `priority` (a var, so a ranked discipline needs no
         *  extra field), `timeEnteredQueue`, `timeExitedQueue` and `timeInQueue`. A `postedAt`
         *  field is therefore deliberately absent: it *is* `timeEnteredQueue`, and duplicating it
         *  would create a second source of truth for the wait. */
    }

    /** Carries a load for a requesting entity. */
    inner class TransportTask internal constructor(
        val load: ProcessModel.Entity,
        val origin: String,
        override val destination: String,
        val loadingDelay: GetValueIfc,
        val unLoadingDelay: GetValueIfc
    ) : Task("${load.name}:Transport") {

        override val pickupLocation: String
            get() = origin

        override val waitingEntity: ProcessModel.Entity
            get() = load

        /** Counts revocations, so the load's result can report them. */
        var numReassignments: Int = 0
            internal set

        /**
         * Set by the carrying vehicle so the load's result, and the guide path's per-carry
         * statistics, can report what the journey cost.
         *
         * The two move times are the **travel legs only**: the leg that ended at this task's pickup
         * and the leg that ended at its set-down, each taken before the loading or unloading delay
         * that follows it. That is what the passive subsystem means by the same two names, and the
         * point of recording them here is that the two paradigms then report the same quantity.
         * `waitForArrival` and `timeAboard` on the result are the wider intervals that include
         * those delays, and neither is derivable from the other.
         */
        internal var carriedBy: FleetVehicle? = null

        // Marks taken when *this* load went aboard, so that its ride is measured from its own
        // pickup. With one load per tour the pickup and the start of the loaded leg are the same
        // instant; with several they are not, and a load collected second would otherwise be
        // credited with the whole tour since the last leg began.
        internal var pickedUpAt: Double = Double.NaN
        internal var distanceAtPickup: Double = 0.0
        internal var zonesAtPickup: Int = 0
        internal var blockedAtPickup: Double = 0.0
        internal var loadedRouteLength: Double = 0.0
        internal var blockedWhileLoaded: Double = 0.0
        internal var approachTime: Double = 0.0
        internal var rideTime: Double = 0.0
        internal var loadedZonesTraversed: Int = 0
        internal var failedBeforePickup: Double = 0.0
        internal var failedWhileLoaded: Double = 0.0

        /**
         * How much of the journey the vehicle spent unable to claim the space ahead.
         *
         * Computed here rather than at each of the two places that want it, so that the load's
         * result and the guide path's statistic cannot come to disagree about what blocked means.
         */
        internal val blockedTime: Double
            get() = blockedAtPickup + blockedWhileLoaded

        /**
         * How much of the journey the vehicle spent out of service.
         *
         * The same shape as [blockedTime] and for the same reason. It is a *part of* the approach
         * and ride times rather than something outside them: those are protocol intervals and the
         * load was waiting, or aboard, throughout. This is what lets a study separate a fleet that
         * is slow from one that is unreliable, which the two intervals alone cannot distinguish.
         */
        internal val failedTime: Double
            get() = failedBeforePickup + failedWhileLoaded
    }

    /**
     * One cycle of a declared service.
     *
     * The third way a tour comes into existence, and the one that makes a route system a
     * configuration rather than a special case: where a transport task becomes two stops and an
     * errand becomes one, this becomes the line's own list of them. Nothing is suspended on it --
     * the riders are suspended at their stops, not on the vehicle's work -- so it behaves like an
     * errand in every respect that matters to the protocol: it may be cancelled, and it never
     * passes through `IN_PROGRESS`.
     *
     * **A vehicle is assigned a line each cycle rather than owning one.** That is what keeps a
     * fleet's allocation across its services visible as a dispatching decision, and what puts a
     * decision point at the end of every cycle instead of committing a vehicle to a service for the
     * whole run.
     */
    inner class LineTask internal constructor(
        val line: Line
    ) : Task("Line:${line.name}") {

        override val destination: String
            get() = line.terminus

        override val pickupLocation: String
            get() = line.origin

        override val waitingEntity: ProcessModel.Entity?
            get() = null
    }

    /** Something a vehicle does for itself. Nothing is suspended on it. */
    inner class ServiceTask internal constructor(
        override val destination: String,
        val kind: ServiceKind
    ) : Task("Service:$destination") {

        override val pickupLocation: String
            get() = destination

        override val waitingEntity: ProcessModel.Entity?
            get() = null
    }

    // ---- statistics -----------------------------------------------------------------------
    // Counters, not a per-decision trace: the general form of "record what was decided and why"
    // belongs to the sequential-decision-making subsystem's trajectory sink, and duplicating a
    // weaker version of it here would have to be thrown away later.

    private val myNumTasksPosted = Counter(this, "${this.name}:NumTasksPosted")
    val numTasksPosted: CounterCIfc get() = myNumTasksPosted

    private val myNumTasksCompleted = Counter(this, "${this.name}:NumTasksCompleted")
    val numTasksCompleted: CounterCIfc get() = myNumTasksCompleted

    private val myNumTasksCancelled = Counter(this, "${this.name}:NumTasksCancelled")
    val numTasksCancelled: CounterCIfc get() = myNumTasksCancelled

    private val myNumAssignmentsMade = Counter(this, "${this.name}:NumAssignmentsMade")
    val numAssignmentsMade: CounterCIfc get() = myNumAssignmentsMade

    private val myNumAssignmentsRevoked = Counter(this, "${this.name}:NumAssignmentsRevoked")
    val numAssignmentsRevoked: CounterCIfc get() = myNumAssignmentsRevoked

    private val myNumAuctionsRun = Counter(this, "${this.name}:NumAuctionsRun")
    val numAuctionsRun: CounterCIfc get() = myNumAuctionsRun

    /**
     * Auctions in which every vehicle declined.
     *
     * Counted rather than raised. A fleet that is out of range, out of charge or simply all busy has
     * nothing to offer, and that is ordinary operation of a negotiated system rather than a fault --
     * the task stays on the board and is auctioned again on the next pass. It is worth counting
     * because a rising unfilled rate is the earliest sign that a bidding rule has been set too
     * strictly, and nothing else in the output would say so.
     */
    private val myNumAuctionsUnfilled = Counter(this, "${this.name}:NumAuctionsUnfilled")
    val numAuctionsUnfilled: CounterCIfc get() = myNumAuctionsUnfilled

    internal fun auctionRun() = myNumAuctionsRun.increment()
    internal fun auctionUnfilled() = myNumAuctionsUnfilled.increment()

    /**
     * How long a task waited before a vehicle committed to it.
     *
     * Disjoint from the queue's time in queue, which runs past this to pickup, and from the
     * system's transport time, which begins after it. None of the three is derivable from another,
     * which is why all three are measured rather than two being computed.
     */
    private val myWaitForAssignment = Response(this, "${this.name}:WaitForAssignment")
    val waitForAssignment: ResponseCIfc get() = myWaitForAssignment

    // ---- posting --------------------------------------------------------------------------

    internal fun postTransport(
        load: ProcessModel.Entity,
        origin: String,
        destination: String,
        loadingDelay: GetValueIfc,
        unLoadingDelay: GetValueIfc,
        priority: Int
    ): TransportTask {
        system.space.requireLocation(origin)
        system.space.requireLocation(destination)
        val task = TransportTask(load, origin, destination, loadingDelay, unLoadingDelay)
        task.priority = priority
        myTaskQ.enqueue(task)
        myNumTasksPosted.increment()
        wake()
        return task
    }

    /**
     * Asks the fleet to send some vehicle somewhere, for its own reasons rather than to carry
     * anything.
     *
     * The errand goes on the same board as transport requests and is decided by the same policy, so
     * it competes with them and is subject to the same selection rule. That is the point: "go and
     * fetch an empty pallet from the yard" is work the fleet does, and a model in which it did not
     * compete for vehicles would understate what the fleet is being asked to do.
     *
     * **Any available vehicle may take it**, which is what distinguishes an errand from the things
     * that look like errands and are not. Charging is *not* one: no other vehicle can charge this
     * one, so it is a [ksl.modeling.fleet.policies.Disposition]. Repair is not one either, for the
     * same reason — it is an [InterruptionPolicyIfc]. If only one particular vehicle can do it, it
     * does not belong here.
     *
     * **Nothing is suspended on it**, and three consequences follow from that alone:
     *
     * - It may be [cancel]led, unlike a transport request. "You were going to park, but work has
     *   arrived" is a real thing to want and is safe, because cancelling strands nobody.
     * - The vehicle stays re-taskable for the whole errand, not merely until it arrives. There is no
     *   load to take possession of, so nothing makes the assignment irrevocable.
     * - It contributes nothing to `waitForAssignment`, which decomposes what a *load* waited for.
     *
     * **What it does count towards.** The dispatcher's `NumTasksPosted` and `NumTasksCompleted`, and
     * the carrying vehicle's own `NumTasksCompleted` — so an errand is a duty cycle like any other,
     * which is what a rule such as `LeastUsedVehiclePolicy` and a `FailureBasis.TASKS_COMPLETED`
     * failure model should both see.
     *
     * **It shares the reported waiting line, and that is worth knowing before you use it.** `A11`
     * says `TaskQ`'s time in queue *is* the wait for transport, which holds exactly while every task
     * in it is a transport request. Post errands and the row becomes the wait for *any* work the
     * fleet was asked to do. That is the honest reading of one queue serving one fleet, and the
     * alternative — a second queue — would give a policy two boards to allocate over and this
     * subsystem two waiting lines to explain. `NumTasksPosted` against the deliveries a model counts
     * for itself is how to see the mix.
     *
     * @param destination where the vehicle is to end up. Checked against the network now.
     * @param kind what the errand is. `ServiceKind.Reposition` is the one implemented.
     * @param priority the task's queue priority, for a ranked selection rule.
     * @return the posted task, which may be passed to [cancel]
     */
    @JvmOverloads
    fun postService(
        destination: String,
        kind: ServiceKind = ServiceKind.Reposition,
        priority: Int = 1
    ): ServiceTask {
        system.space.requireLocation(destination)
        val task = ServiceTask(destination, kind)
        task.priority = priority
        myTaskQ.enqueue(task)
        myNumTasksPosted.increment()
        wake()
        return task
    }

    /**
     * Asks the fleet to run one cycle of a line.
     *
     * Posted onto the same board as everything else and decided by the same policy, so a fleet that
     * runs services and also carries posted loads allocates between the two through one decision
     * rather than two -- which is the whole reason a route system is a configuration here and not a
     * second subsystem.
     *
     * **One post is one cycle.** A service that runs all day is a model that posts again when the
     * previous cycle ends, which is a decision the modeller can make on headway, on a timetable, or
     * on whatever else the study is about. A cycle that is never re-posted simply stops running,
     * visibly, rather than a vehicle silently continuing to circle.
     *
     * Nothing is suspended on it, so it may be [cancel]led: taking a service off for the rest of an
     * hour strands nobody aboard, because a rider whose vehicle is withdrawn is set down by the
     * end of the tour and its own process asks again.
     *
     * @param line the service to run one cycle of
     * @param priority orders this against other work posted at the same instant
     * @return the posted task, which may be passed to [cancel]
     */
    @JvmOverloads
    fun postLine(line: Line, priority: Int = 1): LineTask {
        for (ls in line.stops) {
            require(ls.stop.system === system) {
                "Line (${line.name}) calls at stop (${ls.stop.name}), which belongs to a " +
                        "different fleet than ${this.name}."
            }
        }
        val task = LineTask(line)
        task.priority = priority
        myTaskQ.enqueue(task)
        myNumTasksPosted.increment()
        wake()
        return task
    }

    /**
     * Abandons a task a vehicle raised for itself.
     *
     * **A [TransportTask] cannot be cancelled**, and the refusal is deliberate rather than an
     * omission. A load that asked for transport is suspended waiting for it, and there is no safe
     * thing to do with that load: leaving it suspended strands it for the rest of the replication;
     * terminating its process kills work that may have had nothing to do with the transport; and
     * resuming it with an outcome only helps if the modeller handles that outcome, which Kotlin
     * cannot oblige them to do -- a discarded return value is not a compile error, and the resulting
     * model carries on as though the load had been delivered.
     *
     * `MovableResource` declines to offer cancellation for the same reason, so a modeller learns one
     * rule rather than two. If a load must give up entirely, [TaskQ.removeAndTerminate] ends its
     * process outright -- blunt, but honest about being blunt, and it leaves nothing suspended.
     *
     * A [ServiceTask] is different in the way that matters: a vehicle raised it for itself, so
     * nothing is waiting on it and cancelling one strands nobody. "You were going to park, but work
     * has arrived" is a real thing to want, and it is safe.
     *
     * A vehicle already committed to the task is released first, so it does not go on to a task that
     * no longer exists.
     *
     * @throws FleetProtocolException when the task is a transport request.
     */
    fun cancel(task: Task) {
        require(task.dispatcher === this) { "Task (${task.name}) does not belong to ${this.name}." }
        if (task is TransportTask) {
            throw FleetProtocolException(
                "Task (${task.name}) is a transport request and cannot be cancelled: entity " +
                        "(${task.load.name}) is suspended waiting for it, and there is no outcome " +
                        "this subsystem can give that entity which a model is obliged to handle. " +
                        "MovableResource declines cancellation for the same reason. To make the " +
                        "load give up entirely, use TaskQ.removeAndTerminate, which ends its " +
                        "process and leaves nothing suspended."
            )
        }
        releaseAnyVehicleFrom(task)
        myTaskQ.remove(task, false)
        task.transitionTo(TaskState.CANCELLED)
    }

    /**
     * Releases any vehicle committed to the task, so the task can be abandoned without the vehicle
     * going on to collect a load that is no longer there.
     *
     * Called by [cancel] and by [TaskQ.removeAndTerminate]. Both abandon a task, and both would
     * otherwise leave a vehicle en route to a pickup whose task has gone -- surfacing much later, and
     * far from the cause, as an illegal state transition thrown from the control loop.
     *
     * @throws FleetAssignmentException when the load is already aboard. There is nowhere to set it
     *   down, so the delivery must finish; abandoning the task at that point would leave a vehicle
     *   carrying something that no longer exists.
     */
    internal fun releaseAnyVehicleFrom(task: Task) {
        assignmentFor(task)?.let { live ->
            live.requireRevocable()
            releaseFrom(live)
        }
    }

    /**
     * Detaches a vehicle from an assignment and makes it assignable again, without deciding what
     * becomes of the task.
     *
     * Shared by [revoke], which returns the task to the board, and [cancel], which does not. Keeping
     * the vehicle-side steps in one place is what stops the two operations drifting apart: they
     * differ in what happens to the *task*, and should not differ in what happens to the vehicle.
     */
    private fun releaseFrom(assignment: Assignment) {
        assignment.state = AssignmentState.REVOKED
        // Counted here rather than in `revoke`, for the same reason cancellations are counted in
        // `Task.transitionTo`: this is the one place an assignment becomes revoked, and two callers
        // reach it -- a policy re-tasking a vehicle, and a task being abandoned under one. Counting
        // only the first meant an assignment taken back because its task was withdrawn was made and
        // then never accounted for anywhere.
        myNumAssignmentsRevoked.increment()
        // Available again in the same breath, so a pass that releases and reassigns can do both
        // without an intervening wake -- unless the vehicle is out of service, in which case
        // `declareAvailable` declines on its behalf and it declares itself when it is fit again.
        declareAvailable(assignment.vehicle)
        assignment.vehicle.agent?.abandonAssignment(assignment)
    }

    /**
     * Takes a task back from a vehicle that has not yet collected its load, and gives the vehicle
     * something else to do.
     *
     * This is the capability the passive paradigm has no place for. There, a transporter belongs to
     * the entity that seized it for the whole journey, so a cart three-quarters of the way to a far
     * pickup cannot be turned round for a nearer one that has just appeared — not because the
     * movement machinery could not do it, but because there is no object whose business it would be
     * to decide. Here there is.
     *
     * The task returns to `POSTED` and is **not re-enqueued**: it never left the queue, so its
     * accumulated wait survives. Re-enqueuing would reset the wait and make a load that has been
     * waiting longest look as though it had just arrived — corrupting both the statistic and any
     * age-based selection rule, in the one case where the load has most cause to complain.
     *
     * The vehicle is not told to stop. It is redirected, and the space layer decides when: a vehicle
     * mid-traversal defers to the next zone boundary, because something between two places cannot
     * turn round; a blocked vehicle gives up its wait first, so it is not left on a waiter list for
     * a journey it is no longer making.
     *
     * @throws FleetAssignmentException when the load is already aboard, naming both participants.
     */
    fun revoke(assignment: Assignment) {
        assignment.requireRevocable()
        require(assignment.task.dispatcher === this) {
            "Assignment of task (${assignment.task.name}) does not belong to ${this.name}."
        }
        assignment.task.transitionTo(TaskState.POSTED)
        assignment.task.assignedAt = Double.NaN
        (assignment.task as? TransportTask)?.let { it.numReassignments++ }
        releaseFrom(assignment)
    }

    // ---- the vehicle protocol ----------------------------------------------------------------

    /**
     * A vehicle declares that it will take work.
     *
     * Non-suspending, and it does not assign: it records availability and wakes the dispatcher,
     * whose next pass decides. The vehicle then goes dormant, and is resumed either with an
     * assignment or, if there is nothing for it, without one -- which is its cue to consider a
     * disposition. That ordering is what makes "work beats disposition" structural: the branch that
     * consults a disposition policy is unreachable until the dispatcher has had its pass and
     * declined.
     */
    internal fun declareAvailable(vehicle: FleetVehicle) {
        // A vehicle that has stopped and is being dealt with cannot take work, whatever else has
        // just happened to it. The case that makes this necessary rather than defensive is a
        // revocation: taking a task back from a broken vehicle declares it available in the same
        // breath, and without this the dispatcher would hand it something else it equally cannot
        // start. It re-declares itself when its policy has put it right, which is the first thing
        // its control loop does.
        if (vehicle.isOutOfService) {
            withdraw(vehicle)
            return
        }
        if (!myAvailable.contains(vehicle)) myAvailable.add(vehicle)
        if (!myNewlyDeclared.contains(vehicle)) myNewlyDeclared.add(vehicle)
        wake()
    }

    /** A vehicle takes itself out of consideration -- it is about to move for its own reasons. */
    internal fun withdraw(vehicle: FleetVehicle) {
        myAvailable.remove(vehicle)
        myNewlyDeclared.remove(vehicle)
    }

    /**
     * A vehicle has the load. Dequeues the task, ending its recorded wait.
     *
     * This call, and not the assignment, is what defines the queue's time in queue as the wait for
     * transport. Dequeuing at assignment instead would silently redefine the subsystem's headline
     * statistic to mean something narrower.
     */
    internal fun tookPossession(assignment: Assignment) {
        val task = assignment.task
        myTaskQ.remove(task)
        task.transitionTo(TaskState.IN_PROGRESS)
        assignment.state = AssignmentState.IN_PROGRESS
    }

    internal fun completed(assignment: Assignment) {
        assignment.state = AssignmentState.COMPLETED
        if (assignment.task.state != TaskState.COMPLETED) {
            assignment.task.transitionTo(TaskState.COMPLETED)
        }
        // A transport task left the queue at pickup, which is what makes the queue's time in queue
        // the load's wait. A task with no load has no pickup, so this is where it leaves -- and it
        // has to leave somewhere, or a completed errand would sit in the reported waiting line for
        // the rest of the replication, inflating its length and never recording a wait at all.
        if (myTaskQ.contains(assignment.task)) {
            myTaskQ.remove(assignment.task)
        }
        myNumTasksCompleted.increment()
    }

    // ---- the loop ------------------------------------------------------------------------------

    /**
     * Wakes the dispatcher's process, or records that it should not go back to sleep.
     *
     * The flag matters at the start of a replication, when vehicles may declare availability before
     * the dispatcher's process has reached its first `hold`. Without it that first wake is lost and
     * a fleet sits idle with work on the board until something else happens to wake it -- which in
     * a lightly-loaded model may be never.
     */
    private fun wake() {
        val a = agent
        if (a != null && system.dispatcherIdleQ.contains(a)) {
            system.dispatcherIdleQ.removeAndResume(a)
        } else {
            wakePending = true
        }
    }

    /**
     * Asks the dispatcher to look at the board again.
     *
     * The dispatcher is woken by the two things that ordinarily change a decision from *inside*
     * this subsystem: a task being posted, and a vehicle declaring itself available. Between them
     * they cover the working fleet, because a vehicle that moves — finishing a task, finishing a
     * disposition — re-declares when it stops, so anything that alters what a vehicle can reach
     * already causes a fresh pass.
     *
     * **They do not cover a vehicle that stops.** One that breaks down or runs flat keeps its
     * assignment, declares nothing and posts nothing, so nothing here wakes and a task committed to
     * a vehicle that will not reach it for an hour is not reconsidered. That costs nothing to a
     * fleet whose rule would not take the task back anyway, and it costs a re-tasking policy the
     * decision it exists to make. `ReconsiderOnInterruption` is the listener that closes it, and it
     * is opt-in because waking on every breakdown changes the event sequence of every model that
     * has them.
     *
     * What they do not cover is a decision that changes for reasons **outside** the subsystem. A
     * policy or a bidding rule is written by the modeller and may depend on anything in their model
     * — a machine coming back up, a buffer draining, a flag an operator sets — and the dispatcher
     * has no way to know when any of it changes. It must not go looking, either: a dispatcher that
     * woke on a timer to re-ask a question whose answer had not changed would be polling, which in a
     * discrete-event model is both wasteful and a sign that a state change has gone unmodelled.
     *
     * So the model says so, by calling this at the event where the change actually happens — which
     * is an event the model already has.
     *
     * Safe to call at any time, including while the dispatcher is mid-pass — the wake is remembered
     * rather than lost. Calling it when nothing has changed costs one dispatching pass that assigns
     * nothing.
     */
    fun reconsider() {
        wake()
    }

    /** True when the loop should skip its `hold` because a wake arrived while it was awake. */
    internal fun consumeWake(): Boolean {
        val pending = wakePending
        wakePending = false
        return pending
    }

    /**
     * One dispatching pass: consult the policy, act on what it proposes, and then release every
     * vehicle that declared and got nothing, so it can consider a disposition.
     *
     * Split out from the process body so that the process body stays a loop and a `hold`, with
     * every suspension visible in it. This function does not suspend, which is why the policy call
     * itself stays in the loop.
     */
    internal fun applyProposals(proposals: List<AssignmentProposal>) {
        // How many this pass has already given each vehicle. A vehicle with room may take several
        // tasks in one pass and make one round of them; what it may not do is take more than it can
        // carry, and `spareCapacity` alone would not notice because nothing is aboard yet.
        val takenThisPass = mutableMapOf<FleetVehicle, Int>()
        for (p in proposals) {
            if (!myAvailable.contains(p.vehicle)) {
                // Two quite different causes, and a modeller should not be told the wrong one. The
                // ordinary case is a policy naming a vehicle it was never offered, which is a
                // defect in the policy. The other is a policy that consumed simulated time -- an
                // auction deadline, a batching window -- during which a vehicle it *was* offered
                // stopped. That is nobody's mistake, and the message says so.
                if (p.vehicle.isOutOfService) {
                    throw FleetDispatchException(
                        "Policy ($assignmentPolicy) proposed vehicle (${p.vehicle.name}) for task " +
                                "(${p.task.name}), but that vehicle stopped while the policy was " +
                                "deciding and is out of service. A policy that takes simulated time " +
                                "to decide -- an auction deadline, a batching window -- must re-read " +
                                "the available set before it proposes, because the fleet can change " +
                                "under it. FleetVehicle.isOutOfService is the test."
                    )
                }
                throw FleetDispatchException(
                    "Policy ($assignmentPolicy) proposed vehicle (${p.vehicle.name}) for task " +
                            "(${p.task.name}), but that vehicle has not declared itself available. " +
                            "A policy may only propose vehicles from the available set it was given (A6)."
                )
            }
            if (p.task.state != TaskState.POSTED) {
                throw FleetDispatchException(
                    "Policy ($assignmentPolicy) proposed task (${p.task.name}) for vehicle " +
                            "(${p.vehicle.name}), but that task is ${p.task.state} and already has a " +
                            "vehicle committed to it (A1)."
                )
            }
            val roomLeft = p.vehicle.spareCapacity - (takenThisPass[p.vehicle] ?: 0)
            if (roomLeft <= 0) {
                throw FleetDispatchException(
                    "Policy ($assignmentPolicy) proposed task (${p.task.name}) for vehicle " +
                            "(${p.vehicle.name}), which already has everything it can carry this " +
                            "pass. A vehicle holds ${p.vehicle.loadCapacity} load(s); use " +
                            "DispatchContext.feasible, which excludes a vehicle with no room."
                )
            }
            val a = Assignment(p.vehicle, p.task, time, assignmentPolicy.toString(), p.terms)
            // The task STAYS in the queue: it is dequeued at pickup, not here.
            p.task.transitionTo(TaskState.ASSIGNED)
            p.task.assignedAt = time
            // Only a task with a load aboard-to-be. This response decomposes what a *load* waited
            // for, and a self-directed errand has nobody waiting on it; letting one contribute
            // would quietly redefine a headline figure the moment a model posted its first errand.
            if (p.task is TransportTask) {
                myWaitForAssignment.value = time - p.task.timeEnteredQueue
            }
            myNumAssignmentsMade.increment()
            system.emitAssignment(a)
            // Recorded now, woken once at the end of the pass. Waking here would start the tour
            // before the rest of this batch had been added to it, and the vehicle would make one
            // round per task instead of one round for all of them.
            p.vehicle.agent?.assignments?.add(a)
                ?: throw FleetProtocolException("Vehicle (${p.vehicle.name}) has no agent for this replication.")
            takenThisPass[p.vehicle] = (takenThisPass[p.vehicle] ?: 0) + 1
        }
        // Committed now, and not assignable again until the round is over. Growing a tour that is
        // already under way is a separate capability with its own decisions to make.
        for (vehicle in takenThisPass.keys) {
            withdraw(vehicle)
            val agent = vehicle.agent ?: continue
            system.deliverAssignments(agent)
        }
        // Everyone who declared and got nothing hears so, exactly once.
        val leftover = myNewlyDeclared.toList()
        myNewlyDeclared.clear()
        for (v in leftover) resume(v, null)
    }

    private fun resume(vehicle: FleetVehicle, assignment: Assignment?) {
        val a = vehicle.agent ?: throw FleetProtocolException(
            "Vehicle (${vehicle.name}) has no agent for this replication."
        )
        if (assignment != null) {
            a.assignments.add(assignment)
            withdraw(vehicle)
            system.deliverAssignments(a)
            return
        }
        // "Nothing for you" is only meaningful to a vehicle that is waiting to hear it. One that is
        // already moving for its own reasons has nothing to be told.
        if (system.availabilityQ.contains(a)) {
            // Nothing is cleared here. A vehicle waiting to hear this holds no commitments, and if
            // it somehow did, dropping them would be exactly the wrong thing: the tasks would stay
            // recorded as assigned with nothing coming for them.
            system.availabilityQ.removeAndResume(a)
        }
    }

    /** Between replications the board and the available set are emptied. The queue itself is
     *  cleared by `Queue.afterReplication`; this drops the references that outlive it. */
    override fun afterReplication() {
        super.afterReplication()
        myAvailable.clear()
        myNewlyDeclared.clear()
        myInstructions.clear()
        wakePending = false
        agent = null
    }

    override fun toString(): String = "Dispatcher($name, policy=$assignmentPolicy, board=$board)"
}
