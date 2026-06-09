/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.station

import ksl.modeling.variable.*
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  A read-only, observation-oriented view of a [StationNetwork]. Exposes the
 *  network-level responses and the introspectable set of node names, and allows
 *  attaching/detaching lifecycle observers, while hiding construction and
 *  mutation operations (the creation helpers, node registration, and the
 *  internal entry/exit bookkeeping). Expose this interface to clients of a
 *  network rather than the concrete class.
 */
interface StationNetworkCIfc {

    /** The number of QObject instances currently within the network. */
    val numInSystem: TWResponseCIfc

    /** The total time that a QObject instance spends from creation to leaving the network. */
    val systemTime: ResponseCIfc

    /** The number of QObject instances that have left the network. */
    val numCompleted: CounterCIfc

    /**
     *  Diagnostic: the number of entities still holding allocations at the end of
     *  each replication. Non-zero typically reflects work-in-process at the end of
     *  a terminating run (normal); a steadily growing value across replications
     *  suggests a leak (an entity that seized but never released — surfaced more
     *  precisely by the exit-time validation in sinks/transfers/joins/separates).
     */
    val holdingsAtRunEnd: ResponseCIfc

    /** The registered node names within the network. */
    val nodeNames: Set<String>

    /** The directed connections between registered nodes (best-effort static graph). */
    fun arcs(): List<NetworkArc>

    /** The registered QObject class names within the network. */
    val classNames: Set<String>

    /** The per-class total time in the system, or null if [className] is not registered. */
    fun classSystemTime(className: String): ResponseCIfc?

    /** The per-class number completed, or null if [className] is not registered. */
    fun classNumCompleted(className: String): CounterCIfc?

    /** Looks up a registered [Route] by its [Route.routeName] or null if not registered. */
    fun route(name: String): Route?

    /** Attaches an observer for the network's lifecycle events. */
    fun attachNetworkObserver(observer: NetworkObserver)

    /** Detaches a previously attached observer. */
    fun detachNetworkObserver(observer: NetworkObserver)
}

/**
 *  A StationNetwork is a container that models a queueing network as a graph of
 *  receivers (stations) connected by senders/routers. It owns the network-level
 *  responses that essentially every queueing model re-derives by hand — the
 *  number in the system, the total time in the system, and the number completed
 *  — and it provides a name registry, named boundary ports (ingress/egress), and
 *  a lifecycle event stream so that the network is an open, observable system.
 *
 *  Stations are created through the helper functions ([source], [singleQStation],
 *  [activityStation], [sink]) so that each station is parented to the network,
 *  named consistently, and registered for lookup and validation. The helpers are
 *  intentionally plain functions; a type-safe builder DSL is layered on top of
 *  them separately.
 *
 *  Expose [StationNetworkCIfc] to clients that only need to observe the network.
 *
 *  @param parent the model element serving as this network's parent
 *  @param name the name of the network
 */
class StationNetwork(
    parent: ModelElement,
    name: String? = null
) : ModelElement(parent, name), StationNetworkCIfc {

    private val myNumInSystem: TWResponse = TWResponse(this, "${this.name}:NumInSystem")

    /** The number of QObject instances currently within the network. */
    override val numInSystem: TWResponseCIfc
        get() = myNumInSystem

    private val mySystemTime: Response = Response(this, "${this.name}:SystemTime")

    /** The total time that a QObject instance spends from creation to leaving the network. */
    override val systemTime: ResponseCIfc
        get() = mySystemTime

    private val myNumCompleted: Counter = Counter(this, "${this.name}:NumCompleted")

    /** The number of QObject instances that have left the network. */
    override val numCompleted: CounterCIfc
        get() = myNumCompleted

    // ---- per-entity allocation registry (for SeizeStation / ReleaseStation) ----
    // keyed by QObject.id; each entity may hold multiple allocations (possibly
    // multiple on the same resource), kept in seize order so that release-by-
    // resource releases the oldest first (FIFO).
    private val holdings = mutableMapOf<Long, MutableList<Allocation>>()

    private val myHoldingsAtRunEnd: Response = Response(this, "${this.name}:HoldingsAtRunEnd")

    override val holdingsAtRunEnd: ResponseCIfc
        get() = myHoldingsAtRunEnd

    /** Records that [qObject] has acquired the units described by [allocation]. */
    internal fun recordAllocation(qObject: QObject, allocation: Allocation) {
        holdings.getOrPut(qObject.id) { mutableListOf() }.add(allocation)
    }

    /**
     *  Removes and returns this [qObject]'s oldest outstanding allocation on the
     *  given [resource], or null if it holds none. Releases by resource use this
     *  FIFO path; modelers needing token-level precision could be added later.
     */
    internal fun takeAllocation(qObject: QObject, resource: SResource): Allocation? {
        val list = holdings[qObject.id] ?: return null
        val idx = list.indexOfFirst { it.resource === resource }
        if (idx < 0) return null
        val a = list.removeAt(idx)
        if (list.isEmpty()) holdings.remove(qObject.id)
        return a
    }

    /** The allocations [qObject] currently holds, in seize order (possibly empty). */
    fun allocationsOf(qObject: QObject): List<Allocation> =
        holdings[qObject.id]?.toList() ?: emptyList()

    /**
     *  Throws if [qObject] has any outstanding allocations. Called at exit points
     *  (sinks, transfers, join child-absorbs, separate batch-wrappers) — the entity
     *  must release everything it holds before leaving the network or being
     *  destroyed. The error names [qObject] and the resources it still holds.
     */
    fun verifyNoAllocations(qObject: QObject, at: String) {
        val held = holdings[qObject.id]
        if (held != null && held.isNotEmpty()) {
            val held_names = held.joinToString { "${it.resource.name}(${it.amount})" }
            error(
                "Entity ${qObject.name} (id=${qObject.id}) arrived at '$at' " +
                    "in network ${this.name} holding allocations: $held_names. " +
                    "Release them before exit."
            )
        }
    }

    private val myNodes = mutableMapOf<String, QObjectReceiverIfc>()

    /** The registered node names within this network. */
    override val nodeNames: Set<String>
        get() = myNodes.keys.toSet()

    /** Per-class responses, keyed by the class type id. */
    private class ClassResponses(
        val className: String,
        val systemTime: Response,
        val numCompleted: Counter
    )

    private val myClassesByType = mutableMapOf<Int, ClassResponses>()
    private val myTypeByClassName = mutableMapOf<String, Int>()

    override val classNames: Set<String>
        get() = myTypeByClassName.keys.toSet()

    override fun classSystemTime(className: String): ResponseCIfc? =
        myTypeByClassName[className]?.let { myClassesByType[it]?.systemTime }

    override fun classNumCompleted(className: String): CounterCIfc? =
        myTypeByClassName[className]?.let { myClassesByType[it]?.numCompleted }

    /**
     *  Registers a QObject class for per-class statistics. The creation helpers
     *  call this automatically when a class is supplied to a source. Registering
     *  the same class (same name and type id) more than once is a no-op;
     *  registering a conflicting class is an error.
     */
    fun registerClass(qObjectClass: QObjectClass) {
        val existing = myClassesByType[qObjectClass.typeId]
        if (existing != null) {
            require(existing.className == qObjectClass.className) {
                "Type id ${qObjectClass.typeId} is already registered to class " +
                    "'${existing.className}' in network ${this.name}."
            }
            return
        }
        require(!myTypeByClassName.containsKey(qObjectClass.className)) {
            "Duplicate class name '${qObjectClass.className}' in network ${this.name}."
        }
        val st = Response(this, "${this.name}:${qObjectClass.className}:SystemTime")
        val nc = Counter(this, "${this.name}:${qObjectClass.className}:NumCompleted")
        myClassesByType[qObjectClass.typeId] = ClassResponses(qObjectClass.className, st, nc)
        myTypeByClassName[qObjectClass.className] = qObjectClass.typeId
        qObjectClass.route?.let { registerRoute(it) }
    }

    private val myObservers = mutableListOf<NetworkObserver>()

    /**
     *  Registers a node (receiver) under a name that is unique within the network.
     *  The creation helpers call this automatically; supply a custom receiver or
     *  router here to make it part of the network's graph and validation.
     */
    fun register(name: String, node: QObjectReceiverIfc) {
        require(name.isNotBlank()) { "A network node name must not be blank." }
        require(!myNodes.containsKey(name)) {
            "Duplicate node name '$name' in network ${this.name}."
        }
        myNodes[name] = node
    }

    /** Returns the registered node with the given [name] or null if not present. */
    fun node(name: String): QObjectReceiverIfc? = myNodes[name]

    private val myRoutes = mutableListOf<Route>()
    private val myRoutesByName = mutableMapOf<String, Route>()

    /**
     *  Registers a [Route] so its step-to-step connections are included in the
     *  network graph and so its non-terminal steps are exempt from the dangling
     *  check (their onward routing is carried dynamically on each instance). The
     *  route also becomes lookup-able by name via [route]. The creation/registration
     *  helpers register class routes automatically.
     */
    fun registerRoute(route: Route) {
        if (!myRoutes.contains(route)) {
            myRoutes.add(route)
            myRoutesByName[route.routeName] = route
        }
    }

    /** Looks up a registered [Route] by its [Route.routeName] or null if not registered. */
    override fun route(name: String): Route? = myRoutesByName[name]

    /** The receivers that appear as a non-terminal step of some registered route. */
    private fun nonTerminalRouteSteps(): Set<QObjectReceiverIfc> =
        myRoutes.flatMap { it.steps.dropLast(1) }.toSet()

    override fun arcs(): List<NetworkArc> {
        val nameOf: Map<QObjectReceiverIfc, String> = myNodes.entries.associate { (n, node) -> node to n }
        val result = LinkedHashSet<NetworkArc>()
        // edges from each node's static outlets, restricted to registered nodes
        for ((fromName, node) in myNodes) {
            if (node is RoutingOutletsIfc) {
                for (outlet in node.outlets()) {
                    nameOf[outlet]?.let { toName -> result.add(NetworkArc(fromName, toName)) }
                }
            }
        }
        // edges from registered routes
        for (route in myRoutes) {
            for (i in 0 until route.steps.size - 1) {
                val a = nameOf[route.steps[i]]
                val b = nameOf[route.steps[i + 1]]
                if (a != null && b != null) result.add(NetworkArc(a, b))
            }
        }
        return result.toList()
    }

    /** Attaches an observer for the network's lifecycle events. */
    override fun attachNetworkObserver(observer: NetworkObserver) {
        if (!myObservers.contains(observer)) {
            myObservers.add(observer)
        }
    }

    /** Detaches a previously attached observer. */
    override fun detachNetworkObserver(observer: NetworkObserver) {
        myObservers.remove(observer)
    }

    /**
     *  Called by an ingress when a QObject enters the network. Increments the
     *  number in the system and publishes the entered-network event. The
     *  [isNew] flag distinguishes a freshly generated arrival from a transfer;
     *  in either case the QObject's creation time is preserved so end-to-end
     *  system time across networks is well defined.
     */
    internal fun objectEntered(qObject: QObject, ingress: NetworkIngress, @Suppress("UNUSED_PARAMETER") isNew: Boolean) {
        myNumInSystem.increment()
        for (o in myObservers) {
            o.enteredNetwork(qObject, ingress)
        }
    }

    /**
     *  Called by an egress when a QObject leaves the network. Decrements the
     *  number in the system, records the system time (relative to creation),
     *  counts the completion, and publishes the appropriate event.
     */
    internal fun objectExited(qObject: QObject, egress: NetworkEgress, transferred: Boolean) {
        myNumInSystem.decrement()
        if (transferred) {
            // the instance leaves this network for another; completion/system-time are
            // recorded only on true disposal, so end-to-end time is captured at the
            // final network's sink (createTime is global)
            for (o in myObservers) {
                o.transferred(qObject, egress)
            }
        } else {
            myNumCompleted.increment()
            val systemTime = time - qObject.createTime
            mySystemTime.value = systemTime
            myClassesByType[qObject.qObjectType]?.let {
                it.systemTime.value = systemTime
                it.numCompleted.increment()
            }
            for (o in myObservers) {
                o.exitedNetwork(qObject, egress)
            }
        }
    }

    /**
     *  Creates a [SourceStation] that generates arriving QObject instances and
     *  injects them into the network. The created station is registered under
     *  [name] and returned for further wiring.
     *
     *  @param name the node name, unique within the network
     *  @param interArrivalRV the time between successive arrivals
     *  @param firstReceiver the receiver that processes generated instances; may be
     *  wired later via [SourceStation.firstReceiver]
     *  @param timeUntilFirstRV the time until the first arrival; defaults to [interArrivalRV]
     *  @param maxArrivals the maximum number of arrivals to generate
     *  @param qObjectClass optional class template applied to each created instance; when
     *  supplied, the class is registered for per-class statistics
     *  @param marking optional action applied to each newly created instance (type, attributes)
     */
    fun source(
        name: String,
        interArrivalRV: RVariableIfc,
        firstReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        timeUntilFirstRV: RVariableIfc = interArrivalRV,
        maxArrivals: Long = Long.MAX_VALUE,
        qObjectClass: QObjectClass? = null,
        marking: ((QObject) -> Unit)? = null
    ): SourceStation {
        qObjectClass?.let { registerClass(it) }
        val s = SourceStation(this, interArrivalRV, firstReceiver, timeUntilFirstRV, maxArrivals, qObjectClass, marking, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates an [NHPPSource] (non-homogeneous Poisson arrival source driven by a
     *  piecewise rate function), parented to the network and registered under [name].
     *
     *  @param name the node name, unique within the network
     *  @param rateFunction the piecewise rate function (constant or linear)
     *  @param firstReceiver the receiver that processes generated instances
     *  @param streamNum the stream number for the underlying NHPP
     *  @param maxArrivals the maximum number of arrivals to generate
     *  @param marking optional action applied to each newly created instance
     */
    fun nhppSource(
        name: String,
        rateFunction: ksl.modeling.nhpp.PiecewiseRateFunction,
        firstReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        streamNum: Int = 0,
        maxArrivals: Long = Long.MAX_VALUE,
        marking: ((QObject) -> Unit)? = null
    ): NHPPSource {
        val s = NHPPSource(this, rateFunction, firstReceiver, streamNum, maxArrivals, marking, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Convenience overload that creates an [NHPPSource] from parallel
     *  [durations] and [rates] arrays (piecewise-constant rate function), parented
     *  to the network and registered under [name].
     */
    fun nhppSource(
        name: String,
        durations: DoubleArray,
        rates: DoubleArray,
        firstReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        streamNum: Int = 0,
        maxArrivals: Long = Long.MAX_VALUE,
        marking: ((QObject) -> Unit)? = null
    ): NHPPSource {
        val rf = ksl.modeling.nhpp.PiecewiseConstantRateFunction(durations, rates)
        return nhppSource(name, rf, firstReceiver, streamNum, maxArrivals, marking)
    }

    /**
     *  Creates a [SingleQStation] (single queue, simple resource) parented to the
     *  network and registered under [name].
     *
     *  @param name the node name, unique within the network
     *  @param activityTime the processing time at the station
     *  @param capacity the capacity of the station's resource
     *  @param nextReceiver the receiver of processed instances; may be wired later
     */
    fun singleQStation(
        name: String,
        activityTime: RVariableIfc = ConstantRV.ZERO,
        capacity: Int = 1,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): SingleQStation {
        val s = SingleQStation(this, activityTime, initialCapacity = capacity, nextReceiver = nextReceiver, name = nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates an [ActivityStation] (pure delay, no resource contention) parented
     *  to the network and registered under [name].
     */
    fun activityStation(
        name: String,
        activityTime: RVariableIfc = ConstantRV.ZERO,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): ActivityStation {
        val s = ActivityStation(this, activityTime, nextReceiver, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates a [SinkStation] (egress that disposes of received instances)
     *  parented to the network and registered under [name].
     */
    fun sink(name: String): SinkStation {
        val s = SinkStation(this, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates a shared [SResourcePool] of [capacity] units parented to the network.
     *  The pool is not a routing node; pass it to [resourcePoolStation]s that share it.
     */
    fun resourcePool(name: String, capacity: Int = 1): SResourcePool =
        SResourcePool(this, capacity, nodeName(name))

    /**
     *  Creates a free-standing [SResource] of [capacity] units parented to the network.
     *  The resource is not a routing node; pass it to [seizeStation] / [releaseStation]
     *  for Arena-style atomic seize/release.
     */
    fun resource(name: String, capacity: Int = 1): SResource =
        SResource(this, capacity, nodeName(name))

    /**
     *  Creates a [SeizeStation] that acquires [amount] units of [resource] (or queues
     *  the entity if not available), parented to the network and registered under [name].
     */
    fun seizeStation(
        name: String,
        resource: SResource,
        amount: Int = 1,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): SeizeStation {
        val s = SeizeStation(this, resource, amount, nextReceiver, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates a [ReleaseStation] that releases this entity's oldest outstanding
     *  allocation on [resource], parented to the network and registered under [name].
     */
    fun releaseStation(
        name: String,
        resource: SResource,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): ReleaseStation {
        val s = ReleaseStation(this, resource, nextReceiver, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates a [ResourcePoolStation] that seizes from a shared [pool], parented to
     *  the network and registered under [name].
     */
    fun resourcePoolStation(
        name: String,
        pool: SResourcePool,
        activityTime: RVariableIfc = ConstantRV.ZERO,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): ResourcePoolStation {
        val s = ResourcePoolStation(this, pool, activityTime, nextReceiver, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates a [BatchStation] that forms batches of [batchSize] instances,
     *  parented to the network and registered under [name].
     */
    fun batchStation(
        name: String,
        batchSize: Int,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): BatchStation {
        val s = BatchStation(this, batchSize, nextReceiver, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates a [SeparateStation] that recovers batch members, parented to the
     *  network and registered under [name].
     */
    fun separateStation(
        name: String,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): SeparateStation {
        val s = SeparateStation(this, nextReceiver, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates a [GateStation] (holds instances while closed), parented to the
     *  network and registered under [name].
     */
    fun gateStation(
        name: String,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        initiallyOpen: Boolean = true
    ): GateStation {
        val s = GateStation(this, nextReceiver, initiallyOpen, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates a [BlockingStation] (finite buffer, block-after-service), parented to
     *  the network and registered under [name].
     */
    fun blockingStation(
        name: String,
        bufferCapacity: Int,
        activityTime: RVariableIfc,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): BlockingStation {
        val s = BlockingStation(this, bufferCapacity, activityTime, nextReceiver, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates an [NWayStation] (several input queues sharing one server group),
     *  parented to the network and registered under [name]. Wire upstream nodes to
     *  the station's [NWayStation.input] endpoints.
     */
    fun nWayStation(
        name: String,
        numQueues: Int,
        activityTime: RVariableIfc,
        capacity: Int = 1,
        selectionRule: NWayQueueSelectionRuleIfc = PriorityQueueSelection(),
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): NWayStation {
        // A multi-input station is wired through its input() endpoints rather than as a
        // single routing node, so it is parented to the network but not name-registered.
        return NWayStation(this, numQueues, activityTime, capacity, selectionRule, nextReceiver, nodeName(name))
    }

    /**
     *  Creates a [MatchStation] (assembles one instance from each input, optionally
     *  by a matching key). Wired through its [MatchStation.input] endpoints, so it is
     *  network-parented but not a single routing node.
     */
    fun matchStation(
        name: String,
        numInputs: Int,
        keyExtractor: ((QObject) -> Any)? = null,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): MatchStation {
        return MatchStation(this, numInputs, keyExtractor, nextReceiver, nodeName(name))
    }

    /**
     *  Creates a [JoinStation] (the receive side of a fork-join pair), parented to the
     *  network and registered under [name]. Route to a specific input with the
     *  `"name#0"` (parent) or `"name#1"` (child) target syntax.
     */
    fun joinStation(
        name: String,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): JoinStation {
        val s = JoinStation(this, nextReceiver, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates a [ForkStation] (the send side of a fork-join pair) tied to a paired
     *  [join], parented to the network and registered under [name].
     */
    fun forkStation(
        name: String,
        join: JoinStation,
        childCount: ChildCountIfc,
        childFactory: ChildFactoryIfc? = null,
        childReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): ForkStation {
        val s = ForkStation(this, join, childCount, childFactory, childReceiver, nextReceiver, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates an [IngressStation] (a non-generating entry port for transferred or
     *  externally created instances), parented to the network and registered under [name].
     */
    fun ingress(
        name: String,
        firstReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): IngressStation {
        val s = IngressStation(this, firstReceiver, nodeName(name))
        register(name, s)
        return s
    }

    /**
     *  Creates a [TransferStation] that hands instances off to [target] (typically
     *  another network's ingress), parented to this network and registered under [name].
     *
     *  @param transferDelay optional transport time before delivery
     *  @param transform optional action applied on hand-off
     */
    fun transferStation(
        name: String,
        target: QObjectReceiverIfc,
        transferDelay: ksl.utilities.GetValueIfc? = null,
        transform: ((QObject) -> Unit)? = null
    ): TransferStation {
        val s = TransferStation(this, target, transferDelay, transform, nodeName(name))
        register(name, s)
        return s
    }

    /** Builds the model-element name for a child node from its network-unique [name]. */
    private fun nodeName(name: String): String = "${this.name}:$name"

    override fun initialize() {
        validate()
        // Reset per-replication routing state for any registered stateful router.
        for (node in myNodes.values) {
            if (node is Router) {
                node.resetRouter()
            }
        }
        // Clear per-entity allocation registry — replications do not share state.
        holdings.clear()
    }

    override fun replicationEnded() {
        // Diagnostic: how many entities still held at least one allocation at run end.
        // For terminating sims this is just WIP; for steady-state sims, growth across
        // replications signals a leak (caught more precisely by exit-time validation).
        myHoldingsAtRunEnd.value = holdings.size.toDouble()
    }

    /**
     *  Validates the network structure before a run.
     *
     *  Hard checks (throw): at least one ingress and one egress are present; every
     *  registered router has a destination; and no registered non-egress node is
     *  dangling (it must have onward routing configured or be a non-terminal step
     *  of a registered route).
     *
     *  Soft check (logged warning): registered nodes that are not reachable from any
     *  ingress over the static graph and registered routes. This is best-effort:
     *  connections to unregistered receivers are opaque and can make the check
     *  conservative, so it warns rather than fails.
     */
    private fun validate() {
        val nodes = myNodes.values
        require(nodes.any { it is NetworkIngress }) {
            "Network ${this.name} has no ingress (e.g., a SourceStation)."
        }
        require(nodes.any { it is NetworkEgress }) {
            "Network ${this.name} has no egress (e.g., a SinkStation)."
        }
        val routeSteps = nonTerminalRouteSteps()
        for ((nodeName, node) in myNodes) {
            if (node is Router) {
                require(node.destinations().isNotEmpty()) {
                    "Router '$nodeName' in network ${this.name} has no destinations."
                }
            }
            if (node !is NetworkEgress) {
                val routed = (node as? RoutingOutletsIfc)?.hasOnwardRouting ?: true
                require(routed || node in routeSteps) {
                    "Node '$nodeName' in network ${this.name} is dangling: it has no onward " +
                        "routing and is not a step of any registered route."
                }
            }
        }
        warnIfUnreachable()
    }

    private fun warnIfUnreachable() {
        val adjacency: Map<String, List<String>> = arcs().groupBy({ it.from }, { it.to })
        val reachable = mutableSetOf<String>()
        val stack = ArrayDeque<String>()
        for ((n, node) in myNodes) {
            if (node is NetworkIngress) {
                reachable.add(n); stack.addLast(n)
            }
        }
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            for (next in adjacency[cur].orEmpty()) {
                if (reachable.add(next)) stack.addLast(next)
            }
        }
        val unreachable = myNodes.keys - reachable
        if (unreachable.isNotEmpty()) {
            Model.logger.warn {
                "Network ${this.name}: nodes not reachable from any ingress over the static graph: " +
                    "$unreachable (this check is best-effort and ignores opaque connections)."
            }
        }
    }
}
