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

import ksl.modeling.elements.REmpiricalList
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.RVariableIfc
import kotlin.math.abs

/** Restricts implicit receivers within the station-network builder DSL. */
@DslMarker
annotation class KSLStationDsl

/**
 *  Creates a [StationNetwork] using the type-safe builder DSL. The block declares
 *  sources, stations, sinks, and the routing between them; the network is the
 *  parent of every created node. This is a thin facade over the network's
 *  creation helpers and routing primitives — anything expressible here is
 *  expressible by hand and vice versa.
 *
 *  ```
 *  val net = model.queueingNetwork("pharmacy") {
 *      val exit = sink("exit")
 *      val window = station("window", exponential(4.0, 2))
 *      val arrivals = source("arrivals", exponential(6.0, 1))
 *      arrivals routeTo window
 *      window.routeByChance(0.9 to exit, 0.1 to window)   // 10% rework
 *  }
 *  ```
 */
fun ModelElement.queueingNetwork(
    name: String,
    block: NetworkBuilder.() -> Unit
): StationNetwork {
    val network = StationNetwork(this, name)
    val builder = NetworkBuilder(network)
    builder.block()
    builder.resolveDeferredWiring()
    return network
}

/**
 *  The receiver scope for [queueingNetwork]. Provides node-creation functions and
 *  routing operators. Routing targets may be given as live references or, for the
 *  string overloads, as node names resolved after the block completes (enabling
 *  forward references such as rework loops declared before their target).
 */
@KSLStationDsl
class NetworkBuilder internal constructor(
    /** The network being built; also its read-only view via [StationNetworkCIfc]. */
    val network: StationNetwork
) {

    private val deferredWiring = mutableListOf<() -> Unit>()
    private var routerCount = 0

    // ---- node creation -------------------------------------------------------

    /** Declares an arrival source. See [StationNetwork.source]. */
    fun source(
        name: String,
        interArrivalRV: RVariableIfc,
        firstReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        timeUntilFirstRV: RVariableIfc = interArrivalRV,
        maxArrivals: Long = Long.MAX_VALUE,
        qObjectClass: QObjectClass? = null,
        marking: ((ModelElement.QObject) -> Unit)? = null
    ): SourceStation = network.source(name, interArrivalRV, firstReceiver, timeUntilFirstRV, maxArrivals, qObjectClass, marking)

    /**
     * Declares a non-homogeneous Poisson arrival source from parallel [durations]
     * and [rates] arrays. See [StationNetwork.nhppSource].
     */
    fun nhppSource(
        name: String,
        durations: DoubleArray,
        rates: DoubleArray,
        firstReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        streamNum: Int = 0,
        maxArrivals: Long = Long.MAX_VALUE,
        marking: ((ModelElement.QObject) -> Unit)? = null
    ): NHPPSource = network.nhppSource(name, durations, rates, firstReceiver, streamNum, maxArrivals, marking)

    /** Declares a single-queue station. See [StationNetwork.singleQStation]. */
    fun station(
        name: String,
        activityTime: RVariableIfc = ConstantRV.ZERO,
        capacity: Int = 1,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): SingleQStation = network.singleQStation(name, activityTime, capacity, nextReceiver)

    /** Declares a pure-delay (infinite-server) station. See [StationNetwork.activityStation]. */
    fun delay(
        name: String,
        activityTime: RVariableIfc = ConstantRV.ZERO,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): ActivityStation = network.activityStation(name, activityTime, nextReceiver)

    /** Declares a disposal sink. See [StationNetwork.sink]. */
    fun sink(name: String): SinkStation = network.sink(name)

    /** Declares a shared resource pool. See [StationNetwork.resourcePool]. */
    fun pool(name: String, capacity: Int = 1): SResourcePool = network.resourcePool(name, capacity)

    /** Declares a station that seizes from a shared [pool]. See [StationNetwork.resourcePoolStation]. */
    fun pooledStation(
        name: String,
        pool: SResourcePool,
        activityTime: RVariableIfc = ConstantRV.ZERO,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): ResourcePoolStation = network.resourcePoolStation(name, pool, activityTime, nextReceiver)

    /** Declares a free-standing resource. See [StationNetwork.resource]. */
    fun resource(name: String, capacity: Int = 1): SResource = network.resource(name, capacity)

    /** Declares an atomic seize station. See [StationNetwork.seizeStation]. */
    fun seize(
        name: String,
        resource: SResource,
        amount: Int = 1,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): SeizeStation = network.seizeStation(name, resource, amount, nextReceiver)

    /** Declares an atomic release station. See [StationNetwork.releaseStation]. */
    fun release(
        name: String,
        resource: SResource,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): ReleaseStation = network.releaseStation(name, resource, nextReceiver)

    /** Declares a batch-forming station. See [StationNetwork.batchStation]. */
    fun batch(
        name: String,
        batchSize: Int,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): BatchStation = network.batchStation(name, batchSize, nextReceiver)

    /** Declares a batch-separating station. See [StationNetwork.separateStation]. */
    fun separate(
        name: String,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): SeparateStation = network.separateStation(name, nextReceiver)

    /** Declares a gate station (holds instances while closed). See [StationNetwork.gateStation]. */
    fun gate(
        name: String,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        initiallyOpen: Boolean = true
    ): GateStation = network.gateStation(name, nextReceiver, initiallyOpen)

    /** Declares a finite-buffer blocking station. See [StationNetwork.blockingStation]. */
    fun blocking(
        name: String,
        bufferCapacity: Int,
        activityTime: RVariableIfc,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): BlockingStation = network.blockingStation(name, bufferCapacity, activityTime, nextReceiver)

    /** Declares the join side of a fork-join pair. See [StationNetwork.joinStation]. */
    fun join(
        name: String,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): JoinStation = network.joinStation(name, nextReceiver)

    /** Declares the fork side of a fork-join pair. See [StationNetwork.forkStation]. */
    fun fork(
        name: String,
        join: JoinStation,
        childCount: ChildCountIfc,
        childFactory: ChildFactoryIfc? = null,
        childReceiver: QObjectReceiverIfc = NotImplementedReceiver,
        nextReceiver: QObjectReceiverIfc = NotImplementedReceiver
    ): ForkStation = network.forkStation(name, join, childCount, childFactory, childReceiver, nextReceiver)

    /** Declares and registers a named [Route] over the given ordered [steps]. */
    fun route(name: String, vararg steps: QObjectReceiverIfc): Route {
        val r = Route(name, steps.toList())
        network.registerRoute(r)
        return r
    }

    // ---- direct routing ------------------------------------------------------

    /** Routes a station's output directly to [receiver]. */
    infix fun Station.routeTo(receiver: QObjectReceiverIfc) {
        this.nextReceiver(receiver)
    }

    /** Routes a station's output to the node registered under [nodeName] (resolved after the block). */
    infix fun Station.routeTo(nodeName: String) {
        deferredWiring.add { this.nextReceiver(resolve(nodeName)) }
    }

    /** Routes a source's output directly to [receiver]. */
    infix fun SourceStation.routeTo(receiver: QObjectReceiverIfc) {
        this.firstReceiver(receiver)
    }

    /** Routes a source's output to the node registered under [nodeName] (resolved after the block). */
    infix fun SourceStation.routeTo(nodeName: String) {
        deferredWiring.add { this.firstReceiver(resolve(nodeName)) }
    }

    // ---- conditional / type / selection routing ------------------------------

    /**
     *  Routes probabilistically among [branches] (each `probability to receiver`).
     *  Probabilities must sum to 1.0.
     */
    fun Station.routeByChance(vararg branches: Pair<Double, QObjectReceiverIfc>, streamNum: Int = 0) {
        require(branches.isNotEmpty()) { "routeByChance requires at least one branch." }
        val elements = branches.map { it.second }
        val picker = REmpiricalList(network, elements, cumulativeCdf(branches.map { it.first }), streamNum)
        attachRouter(this, ProbabilisticRouter(picker, elements))
    }

    /** Routes by the instance's [ModelElement.QObject.qObjectType] among [branches], falling back to [default]. */
    fun Station.routeByType(vararg branches: Pair<Int, QObjectReceiverIfc>, default: QObjectReceiverIfc) {
        attachRouter(this, ByTypeRouter(branches.toMap(), default))
    }

    /** Routes by predicate, first match wins, falling back to [default]. */
    fun Station.routeByCondition(default: QObjectReceiverIfc, block: ConditionBuilder.() -> Unit) {
        val cb = ConditionBuilder().apply(block)
        attachRouter(this, ConditionalRouter(cb.cases, default))
    }

    /** Routes to whichever of [stations] currently has the least work in process. */
    fun Station.routeToShortestQueueOf(vararg stations: Station) {
        attachRouter(this, ShortestQueueRouter(stations.toList()))
    }

    /** Routes to [receivers] in cyclic (round-robin) order. */
    fun Station.routeRoundRobinOf(vararg receivers: QObjectReceiverIfc) {
        attachRouter(this, RoundRobinRouter(receivers.toList()))
    }

    /** Scope for declaring predicate-based routing cases. */
    @KSLStationDsl
    class ConditionBuilder internal constructor() {
        internal val cases = mutableListOf<RoutingCase>()

        /** Adds a case: when [predicate] holds for the instance, route it to [goTo]. */
        fun whenever(predicate: QObjectPredicate, goTo: QObjectReceiverIfc) {
            cases.add(RoutingCase(predicate, goTo))
        }
    }

    // ---- internals -----------------------------------------------------------

    private fun attachRouter(from: Station, router: Router) {
        network.register("router_${routerCount++}", router)
        from.nextReceiver(router)
    }

    private fun resolve(nodeName: String): QObjectReceiverIfc =
        network.node(nodeName) ?: error("Unknown node '$nodeName' referenced in network ${network.name}.")

    private fun cumulativeCdf(probabilities: List<Double>): DoubleArray {
        var cumulative = 0.0
        val cdf = DoubleArray(probabilities.size)
        for (i in probabilities.indices) {
            require(probabilities[i] >= 0.0) { "A branch probability must be non-negative." }
            cumulative += probabilities[i]
            cdf[i] = cumulative
        }
        require(abs(cdf.last() - 1.0) < 1.0E-9) { "Branch probabilities must sum to 1.0 (got $cumulative)." }
        cdf[cdf.size - 1] = 1.0
        return cdf
    }

    internal fun resolveDeferredWiring() {
        deferredWiring.forEach { it() }
    }
}
