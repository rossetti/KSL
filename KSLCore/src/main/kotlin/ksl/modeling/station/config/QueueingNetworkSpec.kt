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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ksl.utilities.random.rvariable.parameters.RVData

/**
 * Serializable, hand-authorable description of a queueing network. This is pure
 * data: distributions are captured as [RVData] (reusing the KSL random-variable
 * serialization), and routing is a closed set of [RoutingSpec] variants. A
 * `ksl.modeling.station.config.StationNetworkBuilder` turns a spec into a live
 * `ksl.modeling.station.StationNetwork`.
 *
 * Behavior that cannot be serialized (custom lambdas, entry/exit actions) is out
 * of scope; the spec captures structure, distributions, and named routing.
 *
 * @property name the network's name
 * @property sources arrival sources
 * @property stations single-queue stations
 * @property sinks disposal sinks
 * @property classes QObject class templates (for multi-class models)
 */
@Serializable
data class QueueingNetworkSpec(
    val name: String,
    val sources: List<SourceSpec> = emptyList(),
    val stations: List<StationSpec> = emptyList(),
    val sinks: List<SinkSpec> = emptyList(),
    val classes: List<QObjectClassSpec> = emptyList(),
    // Phase-3 station archetypes (and shared pools), all additive/optional
    val pools: List<PoolSpec> = emptyList(),
    val pooledStations: List<PooledStationSpec> = emptyList(),
    val batchStations: List<BatchStationSpec> = emptyList(),
    val separateStations: List<SeparateStationSpec> = emptyList(),
    val gateStations: List<GateStationSpec> = emptyList(),
    val blockingStations: List<BlockingStationSpec> = emptyList(),
    val nWayStations: List<NWayStationSpec> = emptyList(),
    val matchStations: List<MatchStationSpec> = emptyList(),
    val joinStations: List<JoinStationSpec> = emptyList(),
    val forkStations: List<ForkStationSpec> = emptyList(),
    val resources: List<ResourceSpec> = emptyList(),
    val seizeStations: List<SeizeStationSpec> = emptyList(),
    val releaseStations: List<ReleaseStationSpec> = emptyList(),
    val activityStations: List<ActivityStationSpec> = emptyList(),
    val nhppSources: List<NHPPSourceSpec> = emptyList(),
    val routes: List<RouteSpec> = emptyList()
)

/** A disposal sink node. */
@Serializable
data class SinkSpec(val name: String)

/**
 * A single-queue station.
 *
 * @property name unique node name within the network
 * @property activityTime the processing-time distribution; null means a zero delay
 * @property capacity the station's resource capacity (>= 1)
 * @property routing where processed instances go next
 */
@Serializable
data class StationSpec(
    val name: String,
    val activityTime: RVData? = null,
    val capacity: Int = 1,
    val routing: RoutingSpec? = null,
    val capacitySchedule: CapacityScheduleSpec? = null,
    val failure: FailureSpec? = null,
    val setup: SetupSpec? = null
)

/**
 * An arrival source.
 *
 * @property name unique node name within the network
 * @property interArrivalTime the time-between-arrivals distribution
 * @property timeUntilFirst the time-until-first-arrival distribution; null reuses [interArrivalTime]
 * @property maxArrivals the maximum number of arrivals to generate
 * @property entityClass the name of a [QObjectClassSpec] applied to created instances; null for untyped
 * @property routing where created instances are first sent
 */
@Serializable
data class SourceSpec(
    val name: String,
    val interArrivalTime: RVData,
    val timeUntilFirst: RVData? = null,
    val maxArrivals: Long = Long.MAX_VALUE,
    val entityClass: String? = null,
    /**
     * Optional name of a [ksl.modeling.station.MarkingHookIfc] resolved at build
     * time from the builder's `markings` registry. The hook is applied to each
     * freshly created QObject after the class template (if any). Unknown name
     * fails the build loudly.
     */
    val marking: String? = null,
    val routing: RoutingSpec? = null
)

/**
 * A QObject class template for multi-class networks.
 *
 * @property name unique class name
 * @property typeId the integer type id stamped on instances
 * @property priority the instance priority
 * @property serviceTime an optional per-class service-time distribution attached as the value object
 */
@Serializable
data class QObjectClassSpec(
    val name: String,
    val typeId: Int,
    val priority: Int = 1,
    val serviceTime: RVData? = null
)

/** How a node routes processed instances. A closed set of variants. */
@Serializable
sealed class RoutingSpec {

    /** Route directly to the node named [to]. */
    @Serializable
    @SerialName("direct")
    data class Direct(val to: String) : RoutingSpec()

    /** Route probabilistically; [branches] probabilities must sum to 1. */
    @Serializable
    @SerialName("byChance")
    data class ByChance(val branches: List<ChanceBranch>) : RoutingSpec()

    /** Route by the instance's type id, falling back to [default]. */
    @Serializable
    @SerialName("byType")
    data class ByType(val branches: List<TypeBranch>, val default: String) : RoutingSpec()

    /** Route to whichever of [among] currently has the least work in process. */
    @Serializable
    @SerialName("shortestQueue")
    data class ShortestQueue(val among: List<String>) : RoutingSpec()

    /**
     * Route by predicate, first matching case wins, falling back to [default]. Each
     * case names a predicate resolved at build time from the builder's predicate
     * registry (behavior is not serialized — only the hook name). An unknown name
     * fails the build loudly.
     */
    @Serializable
    @SerialName("byCondition")
    data class ByCondition(val cases: List<ConditionCase>, val default: String) : RoutingSpec()
}

/** A by-condition routing case: when the named [predicate] holds, route to [to]. */
@Serializable
data class ConditionCase(val predicate: String, val to: String)

/** A probabilistic routing branch: send to [to] with the given [probability]. */
@Serializable
data class ChanceBranch(val to: String, val probability: Double)

/** A by-type routing branch: instances of [type] go to [to]. */
@Serializable
data class TypeBranch(val type: Int, val to: String)

// ---- Phase-2 resource enrichment (per single-queue station) ---------------------

/** One step of a capacity schedule: hold [capacity] for [duration]. */
@Serializable
data class CapacityItemSpec(val capacity: Int, val duration: Double)

/**
 * A capacity (shift) schedule for a station's resource. The items are applied in
 * order from [startTime]; if [repeatable], the sequence repeats.
 */
@Serializable
data class CapacityScheduleSpec(
    val items: List<CapacityItemSpec>,
    val repeatable: Boolean = false,
    val startTime: Double = 0.0
)

/** How a failure treats in-service work; mirrors the runtime failure effect. */
@Serializable
enum class FailureEffectSpec { PREEMPT_RESUME, FINISH_THEN_FAIL }

/**
 * A failure (breakdown) specification for a station's resource. All variants carry
 * a repair-time distribution and an [effect]; they differ in the trigger.
 */
@Serializable
sealed class FailureSpec {
    abstract val timeToRepair: RVData
    abstract val effect: FailureEffectSpec

    /** Fails after a calendar-time-to-failure (runs whether busy or idle). */
    @Serializable
    @SerialName("timeBased")
    data class TimeBased(
        val timeToFailure: RVData,
        override val timeToRepair: RVData,
        override val effect: FailureEffectSpec = FailureEffectSpec.PREEMPT_RESUME
    ) : FailureSpec()

    /** Fails after a sampled number of completed services. */
    @Serializable
    @SerialName("countBased")
    data class CountBased(
        val countToFailure: RVData,
        override val timeToRepair: RVData,
        override val effect: FailureEffectSpec = FailureEffectSpec.PREEMPT_RESUME
    ) : FailureSpec()

    /** Fails after a sampled amount of accumulated busy (operating) time. */
    @Serializable
    @SerialName("operatingTimeBased")
    data class OperatingTimeBased(
        val operatingTimeToFailure: RVData,
        override val timeToRepair: RVData,
        override val effect: FailureEffectSpec = FailureEffectSpec.PREEMPT_RESUME
    ) : FailureSpec()
}

/** A (fromType, toType) setup-time entry for a sequence-dependent setup matrix. */
@Serializable
data class SetupEntry(val fromType: Int, val toType: Int, val setupTime: Double)

/** An initial (first-job) setup-time entry by type. */
@Serializable
data class InitialSetupEntry(val toType: Int, val setupTime: Double)

/** A sequence-dependent setup (changeover) specification for a station. */
@Serializable
sealed class SetupSpec {
    /** A fixed setup incurred whenever the served type changes (including the first job). */
    @Serializable
    @SerialName("changeover")
    data class Changeover(val setupTime: Double) : SetupSpec()

    /** A (fromType, toType) setup matrix with optional initial setups and a default. */
    @Serializable
    @SerialName("sequenceDependent")
    data class SequenceDependent(
        val setups: List<SetupEntry>,
        val initialSetups: List<InitialSetupEntry> = emptyList(),
        val defaultSetup: Double = 0.0
    ) : SetupSpec()
}

// ---- Phase-3 station archetypes -------------------------------------------------

/** A shared resource pool (not a routing node); referenced by [PooledStationSpec.pool]. */
@Serializable
data class PoolSpec(val name: String, val capacity: Int = 1)

/** A station that seizes from a shared [pool] instead of its own resource. */
@Serializable
data class PooledStationSpec(
    val name: String,
    val pool: String,
    val activityTime: RVData? = null,
    val routing: RoutingSpec? = null
)

/** A station that accumulates [batchSize] instances into one batch. */
@Serializable
data class BatchStationSpec(
    val name: String,
    val batchSize: Int,
    val routing: RoutingSpec? = null
)

/** A station that separates a batch back into its members. */
@Serializable
data class SeparateStationSpec(
    val name: String,
    val routing: RoutingSpec? = null
)

/**
 * A gate that holds instances while closed. The [initiallyOpen] state serializes;
 * the open/close control is behavior (not serialized) and must be supplied in code.
 */
@Serializable
data class GateStationSpec(
    val name: String,
    val initiallyOpen: Boolean = true,
    val routing: RoutingSpec? = null
)

/** A finite-buffer station with block-after-service semantics. */
@Serializable
data class BlockingStationSpec(
    val name: String,
    val bufferCapacity: Int,
    val activityTime: RVData,
    val routing: RoutingSpec? = null
)

/** The cross-queue selection rule for an [NWayStationSpec]. */
@Serializable
enum class QueueSelection { PRIORITY, ROUND_ROBIN }

/**
 * A multi-queue single-server-group station. Route to a specific input with the
 * target syntax "name#index" (for example, "Assembly#0"). Its single output is
 * given by [routing].
 */
@Serializable
data class NWayStationSpec(
    val name: String,
    val numQueues: Int,
    val activityTime: RVData,
    val capacity: Int = 1,
    val selection: QueueSelection = QueueSelection.PRIORITY,
    val routing: RoutingSpec? = null
)

/**
 * An assembly/synchronization station joining one instance from each input. Route
 * to a specific input with the target syntax "name#index". With [keyByType] true,
 * instances are matched by their type id; otherwise any one from each input is
 * matched.
 */
@Serializable
data class MatchStationSpec(
    val name: String,
    val numInputs: Int,
    val keyByType: Boolean = false,
    val routing: RoutingSpec? = null
)

/**
 * The receive side of a fork-join pair. Its parent input is reached as
 * `"name#0"` and its child input as `"name#1"` (the join's default receive is
 * the parent input, so the bare `"name"` target works for parents too).
 */
@Serializable
data class JoinStationSpec(
    val name: String,
    val routing: RoutingSpec? = null
)

/**
 * The send side of a fork-join pair, paired with the join named [join].
 * Behavior (count and child configuration) is supplied via named hooks resolved
 * at build time against the builder's `ChildFactoryIfc` / `ChildCountIfc`
 * registries; an unknown hook fails the build loudly.
 *
 * @property childCount hook name for a `ChildCountIfc`; required
 * @property childFactory optional hook name for a `ChildFactoryIfc`; null means
 *                       children are unconfigured QObjects
 * @property childRouting optional routing for newly spawned children (where they go first)
 * @property routing optional routing for the parent's onward path
 */
@Serializable
data class ForkStationSpec(
    val name: String,
    val join: String,
    val childCount: String,
    val childFactory: String? = null,
    val childRouting: RoutingSpec? = null,
    val routing: RoutingSpec? = null
)

/**
 * A free-standing resource (not a routing node); referenced by [SeizeStationSpec]
 * and [ReleaseStationSpec] for atomic Arena-style seize/release.
 */
@Serializable
data class ResourceSpec(val name: String, val capacity: Int = 1)

/**
 * An atomic seize station: acquires [amount] units of [resource], or queues the
 * entity if not available. Multiple seize stations may share the same resource;
 * each has its own queue.
 */
@Serializable
data class SeizeStationSpec(
    val name: String,
    val resource: String,
    val amount: Int = 1,
    val routing: RoutingSpec? = null
)

/**
 * An atomic release station: releases this entity's oldest outstanding allocation
 * on [resource]. Release without a prior seize on that resource fails loudly.
 */
@Serializable
data class ReleaseStationSpec(
    val name: String,
    val resource: String,
    val routing: RoutingSpec? = null
)

/**
 * An infinite-server delay station (no resource contention beyond its activity
 * time). Useful for pure delays between seize/release pairs and for class-based
 * paths that don't need queueing.
 */
@Serializable
data class ActivityStationSpec(
    val name: String,
    val activityTime: RVData,
    val routing: RoutingSpec? = null
)

/**
 * A non-homogeneous Poisson arrival source driven by a piecewise-constant rate
 * function. The rate function is specified as parallel [durations] (length of
 * each interval) and [rates] (arrival rate during each interval). Both arrays
 * must have the same length and at least one element.
 *
 * @property streamNum the stream number for the underlying NHPP
 * @property marking optional hook name (resolved against the marking registry)
 *                  to mark each newly created instance before it enters the network
 */
@Serializable
data class NHPPSourceSpec(
    val name: String,
    val durations: List<Double>,
    val rates: List<Double>,
    val streamNum: Int = 0,
    val maxArrivals: Long = Long.MAX_VALUE,
    val marking: String? = null,
    val routing: RoutingSpec? = null
)

/**
 * A first-class named [ksl.modeling.station.Route] — an ordered list of receivers
 * a QObject traverses via its attached sender. Each step is a node name (with the
 * `"node#index"` syntax supported for the multi-input stations NWay/Match/Join).
 *
 * The builder constructs and registers each route after all nodes exist. A
 * marking hook can look up routes by name via `network.route(name)` (the second
 * parameter to [ksl.modeling.station.MarkingHookIfc.mark]) and attach the route's
 * sender to the instance for per-instance routing.
 *
 * @property name the route's name; must be unique within the network
 * @property steps an ordered list of node names; each may be `"name"` or
 *                 `"name#index"` for a specific input of a multi-input station
 */
@Serializable
data class RouteSpec(
    val name: String,
    val steps: List<String>
)
