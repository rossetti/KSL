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

package ksl.modeling.station.config

import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.station.*
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.math.abs

/**
 * Builds a live [StationNetwork] from a [QueueingNetworkSpec]. The builder is the
 * resolution half of the two-layer design: the spec is pure data, and this turns
 * it into model elements parented to a supplied [ModelElement], wiring routing by
 * node name in a second pass so forward references resolve.
 *
 * @param spec the network description to build
 * @param predicates a hook registry mapping each name used by a [RoutingSpec.ByCondition]
 *                   case to a [QObjectPredicate]. Unknown names fail the build loudly.
 */
class StationNetworkBuilder(
    private val spec: QueueingNetworkSpec,
    private val predicates: Map<String, QObjectPredicate> = emptyMap(),
    private val childFactories: Map<String, ChildFactoryIfc> = emptyMap(),
    private val childCounts: Map<String, ChildCountIfc> = emptyMap(),
    private val markings: Map<String, MarkingHookIfc> = emptyMap()
) {

    private var routerCount = 0

    // pools, free-standing resources, and multi-input stations are not
    // name-registered network nodes, so the builder tracks them itself
    private val pools = mutableMapOf<String, SResourcePool>()
    private val resources = mutableMapOf<String, SResource>()
    private val nWays = mutableMapOf<String, NWayStation>()
    private val matches = mutableMapOf<String, MatchStation>()

    /** Builds the network under [parent], returning the live [StationNetwork]. */
    fun build(parent: ModelElement): StationNetwork {
        val net = StationNetwork(parent, spec.name)

        // 1. create all nodes so routing can resolve names afterward
        for (sink in spec.sinks) {
            net.sink(sink.name)
        }
        for (station in spec.stations) {
            val s = net.singleQStation(
                name = station.name,
                activityTime = station.activityTime?.asRVariable() ?: ConstantRV.ZERO,
                capacity = station.capacity
            )
            applyResourceEnrichment(net, s, station)
        }
        for (pool in spec.pools) {
            pools[pool.name] = net.resourcePool(pool.name, pool.capacity)
        }
        for (s in spec.pooledStations) {
            val pool = pools[s.pool] ?: error("pooled station '${s.name}' references unknown pool '${s.pool}'")
            net.resourcePoolStation(s.name, pool, s.activityTime?.asRVariable() ?: ConstantRV.ZERO)
        }
        for (s in spec.batchStations) {
            net.batchStation(s.name, s.batchSize)
        }
        for (s in spec.separateStations) {
            net.separateStation(s.name)
        }
        for (s in spec.gateStations) {
            net.gateStation(s.name, initiallyOpen = s.initiallyOpen)
        }
        for (s in spec.blockingStations) {
            net.blockingStation(s.name, s.bufferCapacity, s.activityTime.asRVariable())
        }
        for (s in spec.nWayStations) {
            nWays[s.name] = net.nWayStation(
                name = s.name,
                numQueues = s.numQueues,
                activityTime = s.activityTime.asRVariable(),
                capacity = s.capacity,
                selectionRule = when (s.selection) {
                    QueueSelection.PRIORITY -> PriorityQueueSelection()
                    QueueSelection.ROUND_ROBIN -> RoundRobinQueueSelection()
                }
            )
        }
        for (s in spec.matchStations) {
            val keyExtractor: ((ModelElement.QObject) -> Any)? =
                if (s.keyByType) { q -> q.qObjectType } else null
            matches[s.name] = net.matchStation(s.name, s.numInputs, keyExtractor)
        }
        // joins must exist before forks so a fork can reference its paired join by name
        for (s in spec.joinStations) {
            net.joinStation(s.name)
        }
        for (s in spec.forkStations) {
            val join = net.node(s.join) as? JoinStation
                ?: error("forkStation '${s.name}' references unknown join '${s.join}'")
            val countHook = childCounts[s.childCount]
                ?: error("forkStation '${s.name}' references unknown childCount hook '${s.childCount}'; supply it via StationNetworkBuilder(..., childCounts)")
            val factoryHook = s.childFactory?.let {
                childFactories[it]
                    ?: error("forkStation '${s.name}' references unknown childFactory hook '$it'; supply it via StationNetworkBuilder(..., childFactories)")
            }
            net.forkStation(s.name, join, countHook, factoryHook)
        }
        // free-standing resources, then seize/release stations that reference them
        for (r in spec.resources) {
            resources[r.name] = net.resource(r.name, r.capacity)
        }
        for (s in spec.seizeStations) {
            val r = resources[s.resource]
                ?: error("seizeStation '${s.name}' references unknown resource '${s.resource}'")
            net.seizeStation(s.name, r, s.amount)
        }
        for (s in spec.releaseStations) {
            val r = resources[s.resource]
                ?: error("releaseStation '${s.name}' references unknown resource '${s.resource}'")
            net.releaseStation(s.name, r)
        }
        for (s in spec.activityStations) {
            net.activityStation(s.name, s.activityTime.asRVariable())
        }

        // 2. classes
        val classes = spec.classes.associate { c ->
            require(c.typeId >= 1) { "class '${c.name}' typeId must be >= 1" }
            c.name to QObjectClass(
                className = c.name,
                typeId = c.typeId,
                priority = c.priority,
                valueObject = c.serviceTime?.asRVariable()
            )
        }

        // 2b. routes -- built and registered before sources so that marking hooks
        //               can attach them as the QObject's sender. Each step name is
        //               resolved against the network (with the "name#index" syntax
        //               supported for multi-input stations).
        for (r in spec.routes) {
            require(r.steps.isNotEmpty()) { "route '${r.name}' must have at least one step" }
            val resolved = r.steps.map { stepName -> resolve(net, stepName) }
            net.registerRoute(Route(r.name, resolved))
        }

        // 3. sources
        for (source in spec.sources) {
            val qoClass = source.entityClass?.let {
                classes[it] ?: error("source '${source.name}' references unknown class '$it'")
            }
            val markingHook = source.marking?.let {
                markings[it] ?: error("source '${source.name}' references unknown marking hook '$it'; supply it via StationNetworkBuilder(..., markings)")
            }
            net.source(
                name = source.name,
                interArrivalRV = source.interArrivalTime.asRVariable(),
                timeUntilFirstRV = (source.timeUntilFirst ?: source.interArrivalTime).asRVariable(),
                maxArrivals = source.maxArrivals,
                qObjectClass = qoClass,
                marking = markingHook?.let { hook -> { q -> hook.mark(q, net) } }
            )
        }
        for (nhpp in spec.nhppSources) {
            require(nhpp.durations.size == nhpp.rates.size) {
                "nhppSource '${nhpp.name}': durations (${nhpp.durations.size}) and rates (${nhpp.rates.size}) must have the same length"
            }
            require(nhpp.durations.isNotEmpty()) { "nhppSource '${nhpp.name}': must have at least one segment" }
            val markingHook = nhpp.marking?.let {
                markings[it] ?: error("nhppSource '${nhpp.name}' references unknown marking hook '$it'; supply it via StationNetworkBuilder(..., markings)")
            }
            net.nhppSource(
                name = nhpp.name,
                durations = nhpp.durations.toDoubleArray(),
                rates = nhpp.rates.toDoubleArray(),
                streamNum = nhpp.streamNum,
                maxArrivals = nhpp.maxArrivals,
                marking = markingHook?.let { hook -> { q -> hook.mark(q, net) } }
            )
        }

        // 4. wire routing now that every node exists
        for (s in spec.stations) {
            s.routing?.let { (net.node(s.name) as Station).nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.pooledStations) {
            s.routing?.let { (net.node(s.name) as Station).nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.batchStations) {
            s.routing?.let { (net.node(s.name) as BatchStation).nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.separateStations) {
            s.routing?.let { (net.node(s.name) as SeparateStation).nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.gateStations) {
            s.routing?.let { (net.node(s.name) as GateStation).nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.blockingStations) {
            s.routing?.let { (net.node(s.name) as BlockingStation).nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.nWayStations) {
            s.routing?.let { nWays[s.name]!!.nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.matchStations) {
            s.routing?.let { matches[s.name]!!.nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.joinStations) {
            s.routing?.let { (net.node(s.name) as JoinStation).nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.forkStations) {
            val fork = net.node(s.name) as ForkStation
            s.routing?.let { fork.nextReceiver(buildReceiver(net, it)) }
            s.childRouting?.let { fork.childReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.seizeStations) {
            s.routing?.let { (net.node(s.name) as SeizeStation).nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.releaseStations) {
            s.routing?.let { (net.node(s.name) as ReleaseStation).nextReceiver(buildReceiver(net, it)) }
        }
        for (s in spec.activityStations) {
            s.routing?.let { (net.node(s.name) as ActivityStation).nextReceiver(buildReceiver(net, it)) }
        }
        for (source in spec.sources) {
            source.routing?.let { (net.node(source.name) as SourceStation).firstReceiver(buildReceiver(net, it)) }
        }
        for (nhpp in spec.nhppSources) {
            nhpp.routing?.let { (net.node(nhpp.name) as NHPPSource).firstReceiver(buildReceiver(net, it)) }
        }
        return net
    }

    private fun buildReceiver(net: StationNetwork, routing: RoutingSpec): QObjectReceiverIfc {
        return when (routing) {
            is RoutingSpec.Direct -> resolve(net, routing.to)
            is RoutingSpec.ByChance -> {
                require(routing.branches.isNotEmpty()) { "byChance routing requires at least one branch" }
                val elements = routing.branches.map { resolve(net, it.to) }
                val picker = REmpiricalList(net, elements, cumulativeCdf(routing.branches.map { it.probability }))
                registerRouter(net, ProbabilisticRouter(picker, elements))
            }
            is RoutingSpec.ByType -> {
                val map = routing.branches.associate { it.type to resolve(net, it.to) }
                registerRouter(net, ByTypeRouter(map, resolve(net, routing.default)))
            }
            is RoutingSpec.ShortestQueue -> {
                require(routing.among.isNotEmpty()) { "shortestQueue routing requires at least one station" }
                val stations = routing.among.map {
                    resolve(net, it) as? Station ?: error("shortestQueue target '$it' is not a station")
                }
                registerRouter(net, ShortestQueueRouter(stations))
            }
            is RoutingSpec.ByCondition -> {
                require(routing.cases.isNotEmpty()) { "byCondition routing requires at least one case" }
                val cases = routing.cases.map { case ->
                    val predicate = predicates[case.predicate]
                        ?: error("byCondition references unknown predicate hook '${case.predicate}' in network '${spec.name}'; supply it via StationNetworkBuilder(spec, predicates)")
                    RoutingCase(predicate, resolve(net, case.to))
                }
                registerRouter(net, ConditionalRouter(cases, resolve(net, routing.default)))
            }
        }
    }

    /** Applies optional Phase-2 resource enrichment (schedule, failures, setups) to a station. */
    private fun applyResourceEnrichment(net: StationNetwork, station: SingleQStation, spec: StationSpec) {
        spec.capacitySchedule?.let { sched ->
            require(sched.items.isNotEmpty()) { "capacity schedule for '${spec.name}' must have at least one item" }
            val schedule = CapacitySchedule(
                net, sched.startTime, autoStartOption = true, repeatable = sched.repeatable,
                name = "${station.name}:CapacitySchedule"
            )
            for (item in sched.items) {
                schedule.addItem(capacity = item.capacity, duration = item.duration)
            }
            station.useCapacitySchedule(schedule)
        }
        spec.failure?.let { f ->
            when (f) {
                is FailureSpec.TimeBased ->
                    station.useTimeBasedFailures(f.timeToFailure.asRVariable(), f.timeToRepair.asRVariable())
                is FailureSpec.CountBased ->
                    station.useCountBasedFailures(f.countToFailure.asRVariable(), f.timeToRepair.asRVariable())
                is FailureSpec.OperatingTimeBased ->
                    station.useOperatingTimeBasedFailures(f.operatingTimeToFailure.asRVariable(), f.timeToRepair.asRVariable())
            }
            when (f.effect) {
                FailureEffectSpec.PREEMPT_RESUME -> station.usePreemptResumeEffect()
                FailureEffectSpec.FINISH_THEN_FAIL -> station.useFinishThenFailEffect()
            }
        }
        spec.setup?.let { setup ->
            station.setupTimeRule = when (setup) {
                is SetupSpec.Changeover -> ChangeoverSetupTime(setup.setupTime)
                is SetupSpec.SequenceDependent -> SequenceDependentSetupTime(
                    setups = setup.setups.associate { (it.fromType to it.toType) to it.setupTime },
                    initialSetups = setup.initialSetups.associate { it.toType to it.setupTime },
                    defaultSetup = setup.defaultSetup
                )
            }
        }
    }

    private fun registerRouter(net: StationNetwork, router: Router): Router {
        net.register("router_${routerCount++}", router)
        return router
    }

    private fun resolve(net: StationNetwork, name: String): QObjectReceiverIfc {
        // a "name#index" target addresses a specific input of a multi-input station
        if ('#' in name) {
            val base = name.substringBefore('#')
            val index = name.substringAfter('#').toIntOrNull()
                ?: error("invalid input index in routing target '$name'")
            nWays[base]?.let { return it.input(index) }
            matches[base]?.let { return it.input(index) }
            // joins have two inputs: #0 = parent, #1 = child
            (net.node(base) as? JoinStation)?.let { return it.input(index) }
            error("routing target '$name' refers to '$base', which is not a multi-input station")
        }
        return net.node(name) ?: error("routing references unknown node '$name' in network '${spec.name}'")
    }

    private fun cumulativeCdf(probabilities: List<Double>): DoubleArray {
        var cumulative = 0.0
        val cdf = DoubleArray(probabilities.size)
        for (i in probabilities.indices) {
            require(probabilities[i] >= 0.0) { "a branch probability must be non-negative" }
            cumulative += probabilities[i]
            cdf[i] = cumulative
        }
        require(abs(cdf.last() - 1.0) < 1.0E-9) { "branch probabilities must sum to 1.0 (got $cumulative)" }
        cdf[cdf.size - 1] = 1.0
        return cdf
    }
}
