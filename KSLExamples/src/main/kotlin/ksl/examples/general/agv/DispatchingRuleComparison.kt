/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.agv

import ksl.controls.experiments.ScenarioRunner
import ksl.controls.KSLStringControl
import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.ContractNetAssignmentPolicy
import ksl.modeling.fleet.policies.FurthestVehiclePolicy
import ksl.modeling.fleet.policies.LeastUsedVehiclePolicy
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.MultipleComparisonAnalyzer

/**
 *  The same shop under six dispatching rules, on common random numbers.
 *
 *  This is what the active paradigm is *for*. Deciding who goes where is a substitutable object, so
 *  a study can change the rule and nothing else, and the fleet, the layout and the arrival stream
 *  stay exactly as they were.
 *
 *  ## The rules, and what each is trying to do
 *
 *  - **Nearest vehicle** — send whoever can get there soonest. The default, and what most people
 *    mean by "sensible".
 *  - **Furthest vehicle** — deliberately poor, and useful for exactly that: a study needs a bad rule
 *    to measure a good one against, or "nearest is better" is an assertion rather than a finding.
 *  - **Least used** — send whoever has done the least work. A *balancing* rule rather than a
 *    travel-minimising one, and the two genuinely conflict.
 *  - **Batched** — wait for a window, then decide over everything that accumulated. Pays a delay to
 *    decide with more information.
 *  - **Contract net (instant)** — the vehicles bid and the best bid wins. Here the knowledge moves:
 *    each vehicle answers from what it knows about itself, rather than the dispatcher computing on
 *    its behalf.
 *  - **Contract net (deliberate)** — the same, with a deadline the auction actually costs. A
 *    negotiation that took no time would be a convenient fiction.
 *
 *  ## Two pickup points, and why that matters
 *
 *  Loads arrive at two different stations. On a single-origin layout every task costs a given
 *  vehicle the same, so rules that rank *tasks* differently cannot be told apart — the comparison
 *  would be unfalsifiable, and would quietly report that the choice of rule does not matter. It is
 *  worth knowing that a layout can hide a difference this way.
 *
 *  ## How the study is run, and why it is run that way
 *
 *  Each rule is a [ksl.controls.experiments.Scenario] inside a [ScenarioRunner]. That is not
 *  ceremony: the runner gives every rule the same run parameters, captures each run in a KSL
 *  database, prints a half-width summary report per scenario, and — the part this study actually
 *  needs — hands back the *per-replication* observations through
 *  [ScenarioRunner.observationsAsMap].
 *
 *  Those observations go to a [MultipleComparisonAnalyzer], and that is what makes the throughput
 *  claim below honest. The rules run on common random numbers, so the right comparison is the
 *  **paired difference**, replication by replication. An earlier version of this example printed
 *  six point estimates of loads delivered, observed that they lay within a load of one another,
 *  and concluded that the rules were equivalent in throughput. The half-width on a single rule's
 *  throughput is about seven loads. Nothing on that page could have distinguished a real
 *  difference of five loads from no difference at all; the pairing can, and the pairing is what
 *  is reported now. *
 *  ## Reading the three tables
 *
 *  **Throughput.** The half-width on any one rule's delivered count is about seven loads, and the
 *  paired half-width is under one. Five of the six rules are indistinguishable from
 *  nearest-vehicle in throughput -- a finding here, and an assertion back when this example
 *  printed six unpaired averages. Batching is the exception and is detectably worse: on a
 *  saturated fleet the window delays every decision.
 *
 *  **Time in system and imbalance** are where the rules actually differ, and both differences are
 *  far outside their intervals. Least-used trades time for evenness on purpose; furthest-vehicle
 *  is deliberately poor so that "nearest is better" can be measured rather than asserted.
 *
 *  **The instant auction reproduces nearest-vehicle replication for replication** -- a difference
 *  of zero with a half-width of zero. That is a check rather than a coincidence: with distance
 *  bidding the vehicles quote what the rule would have computed, so the negotiation machinery is
 *  shown not to change the answer by itself. The deadline row then shows what it costs once
 *  negotiating is charged for.
 *
 *  Where a table says no, the honest statement is "no detectable difference at this sample size",
 *  not "the rules are the same".
 */
/**
 *  Three carts on the ring, dispatched by the rule [ruleName] names.
 *
 *  The rule is taken **by name** rather than as a policy object, and the reason is in [rules]:
 *  two of the six are the same class with different terms, so the class cannot tell them apart
 *  and only a name can. That the name is what varies is also what lets the whole study be
 *  expressed as one model with one input -- see [ruleName].
 *
 *  @param parent the containing model element
 *  @param ruleName the rule under test; the only thing that differs between the six runs
 *  @param name a name for the model element
 */
class DispatchingRuleComparison(
    parent: ModelElement,
    ruleName: String = "NearestVehicle",
    name: String? = "Shop"
) : ProcessModel(parent, name) {

    companion object {

        const val NORTH_PICKUP: String = "NorthPickup"
        const val SOUTH_PICKUP: String = "SouthPickup"
        const val SHIPPING: String = "Shipping"
        const val DEPOT_A: String = "DepotA"
        const val DEPOT_B: String = "DepotB"
        const val DEPOT_C: String = "DepotC"

        /**
         *  A one-way ring of four legs with a depot spur for each of the three carts.
         *
         *  Two pickup stations, at opposite corners, for the reason in this file's header.
         */
        fun createNetwork(): GuidedPathNetwork = GuidedPathNetwork.builder("RingShop")
            .intersection("N", x = 0.0, y = 100.0)
            .intersection("E", x = 100.0, y = 0.0)
            .intersection("S", x = 0.0, y = -100.0)
            .intersection("W", x = -100.0, y = 0.0)
            .intersection("PA", x = 0.0, y = 150.0)
            .intersection("PB", x = 150.0, y = 0.0)
            .intersection("PC", x = 0.0, y = -150.0)
            .link("NE", "N", "E", length = 120.0, zoneLength = 12.0, beginDirection = 315.0)
            .link("ES", "E", "S", length = 120.0, zoneLength = 12.0, beginDirection = 225.0)
            .link("SW", "S", "W", length = 120.0, zoneLength = 12.0, beginDirection = 135.0)
            .link("WN", "W", "N", length = 120.0, zoneLength = 12.0, beginDirection = 45.0)
            .link("SpurA", "N", "PA", length = 24.0, zoneLength = 24.0,
                type = LinkType.SPUR, beginDirection = 90.0)
            .link("SpurB", "E", "PB", length = 24.0, zoneLength = 24.0,
                type = LinkType.SPUR, beginDirection = 0.0)
            .link("SpurC", "S", "PC", length = 24.0, zoneLength = 24.0,
                type = LinkType.SPUR, beginDirection = 270.0)
            .station(NORTH_PICKUP, "N")
            .station(SOUTH_PICKUP, "S")
            .station(SHIPPING, "W")
            .station(DEPOT_A, "PA")
            .station(DEPOT_B, "PB")
            .station(DEPOT_C, "PC")
            .build()

        const val MEAN_TIME_BETWEEN_ARRIVALS: Double = 26.0
        const val ARRIVAL_STREAM: Int = 1
        const val NUM_ARRIVALS: Int = 600

        const val REPLICATIONS: Int = 15
        const val HORIZON: Double = 10_000.0
        const val WARM_UP: Double = 1_500.0

        /**
         *  The six rules, in the order they are reported. Scenario names are also experiment names in
         *  the runner's database and directory names on disk, so they carry no punctuation.
         */
        /** The policy object a rule name stands for, made fresh. */
        fun policyFor(ruleName: String): AssignmentPolicyIfc =
            requireNotNull(rules().toMap()[ruleName]) {
                "unknown dispatching rule '$ruleName'; expected one of ${rules().map { it.first }}"
            }

        fun rules(): List<Pair<String, AssignmentPolicyIfc>> = listOf(
            "NearestVehicle" to NearestVehiclePolicy(),
            "FurthestVehicle" to FurthestVehiclePolicy(),
            "LeastUsed" to LeastUsedVehiclePolicy(),
            "BatchedWindow30" to BatchedAssignmentPolicy(30.0),
            "ContractNetInstant" to ContractNetAssignmentPolicy(0.0),
            "ContractNetDeadline5" to ContractNetAssignmentPolicy(5.0)
        )

        /**
         *  Builds the runner with one scenario per rule. Every scenario gets its own model and the same
         *  run parameters, and the runner leaves the random streams alone, so the six runs see the same
         *  arrivals -- which is what makes the paired comparison below valid.
         */
        fun buildRunner(): ScenarioRunner {
            val runner = ScenarioRunner("DispatchingRules")
            for ((label, _) in rules()) {
                val m = Model("DispatchRules_$label")
                DispatchingRuleComparison(m, label)
                runner.addScenario(
                    model = m,
                    name = label,
                    inputs = emptyMap(),
                    numberReplications = REPLICATIONS,
                    lengthOfReplication = HORIZON,
                    lengthOfReplicationWarmUp = WARM_UP
                )
            }
            return runner
        }
    }

    val network: GuidedPathNetwork = createNetwork()

    init {
        spatialModel = network
    }

    val agv: AgvSystem = AgvSystem(this, network, assignmentPolicy = policyFor(ruleName), name = "Agv")

    /**
     *  Which of the six rules the dispatcher runs, by name.
     *
     *  [ksl.controls.KSLStringControl] declares the names it will accept, so the six-way comparison
     *  in `main` can equally be run as one model with one input -- which is what a scenario, or an
     *  app's input panel, wants. A rule is made fresh on assignment for the same reason [rules]
     *  makes them fresh: a batching or contract-net policy carries state between decisions.
     */
    @set:KSLStringControl(
        allowedValues = [
            "NearestVehicle", "FurthestVehicle", "LeastUsed",
            "BatchedWindow30", "ContractNetInstant", "ContractNetDeadline5"
        ],
        comment = "Which dispatching rule the dispatcher runs"
    )
    var ruleName: String = ruleName
        set(value) {
            agv.dispatcher.assignmentPolicy = policyFor(value)
            field = value
        }

    val fleet: List<AgvVehicle> = listOf(DEPOT_A, DEPOT_B, DEPOT_C).mapIndexed { i, depot ->
        AgvVehicle(agv, TransporterPlacement.At(depot), ConstantRV(12.0), name = "Cart${i + 1}")
            .apply { homeBase = depot }
    }

    private val myWaitForVehicle = Response(this, "${this.name}:WaitForVehicle")
    val waitForVehicle: ResponseCIfc
        get() = myWaitForVehicle

    private val myTimeInSystem = Response(this, "${this.name}:TimeInSystem")
    val timeInSystem: ResponseCIfc
        get() = myTimeInSystem

    private val myDelivered = Counter(this, "${this.name}:Delivered")
    val delivered: CounterCIfc
        get() = myDelivered

    /** Largest minus smallest per-vehicle completions: how unevenly the work fell. Observed at
     *  the horizon, so a Response rather than a Counter -- it is one measurement of the finished
     *  replication, not a total that accumulated during it. */
    private val myFleetImbalance = Response(this, "${this.name}:FleetImbalance")
    val fleetImbalance: ResponseCIfc
        get() = myFleetImbalance

    // A model element rather than a bare random variable, so that the arrival rate is a named
    // input a scenario can override and the report says what it was.
    private val myTimeBetweenArrivals = RandomVariable(
        this, ExponentialRV(MEAN_TIME_BETWEEN_ARRIVALS, ARRIVAL_STREAM), name = "${this.name}:TBA"
    )
    val timeBetweenArrivals: RandomVariableCIfc
        get() = myTimeBetweenArrivals

    inner class Load(private val from: String) : Entity() {
        val production = process(isDefaultProcess = true) {
            val arrived = time
            currentLocation = network.requireLocation(from)
            val result = transportByFleet(agv, destination = SHIPPING, origin = from)
            myWaitForVehicle.value = result.waitForAssignment + result.waitForArrival
            myTimeInSystem.value = time - arrived
            myDelivered.increment()
        }
    }

    inner class Source : Entity() {
        val arrivals = process(isDefaultProcess = true) {
            repeat(NUM_ARRIVALS) {
                delay(myTimeBetweenArrivals)
                // Alternating origins, so that which task is nearest genuinely varies.
                val from = if (it % 2 == 0) NORTH_PICKUP else SOUTH_PICKUP
                activate(Load(from).production)
            }
        }
    }

    override fun initialize() {
        activate(Source().arrivals)
    }

    override fun replicationEnded() {
        super.replicationEnded()
        val counts = fleet.map { it.numTasksCompleted.value }
        myFleetImbalance.value = counts.max() - counts.min()
    }
}

fun main() {
    val runner = DispatchingRuleComparison.buildRunner()
    runner.simulate()

    // The standard half-width summary report for every scenario -- every response the model keeps,
    // with its confidence interval, rather than the four columns the author happened to think of.
    // Written to the KSL output file rather than the console: six scenarios of full reports is
    // several hundred lines, and the console is where the comparison belongs.
    runner.write()
    println()
    println("Full half-width summary reports for all six rules: ${KSL.outDir}")

    // The analyzer forms each paired difference once, in the order the data was inserted, so the
    // pair that exists is "first inserted - later". NearestVehicle is inserted first, so every
    // difference below is reported in that direction rather than being silently absent.
    val base = DispatchingRuleComparison.rules().first().first
    for ((response, label) in listOf(
        "Shop:Delivered" to "loads delivered",
        "Shop:TimeInSystem" to "time in system",
        "Shop:FleetImbalance" to "fleet imbalance"
    )) {
        val observations = runner.observationsAsMap(response)
        check(observations.size == DispatchingRuleComparison.rules().size) {
            "expected per-replication observations of $response for every scenario, got " +
                "${observations.keys}. An empty or partial map would print an empty table, which " +
                "is exactly the sort of silence this study exists to avoid."
        }
        val mca = MultipleComparisonAnalyzer(observations, label)
        println()
        println("Paired differences in $label, $base minus each rule")
        println("(common random numbers, ${DispatchingRuleComparison.REPLICATIONS} replications, 95% intervals)")
        println()
        println("  %-22s %12s %12s %12s".format("rule", "difference", "half-width", "detectable?"))
        for ((name, _) in DispatchingRuleComparison.rules()) {
            if (name == base) continue
            val d = checkNotNull(mca.pairedDifferenceStatistic(base, name)) {
                "no paired difference for '$base - $name'"
            }
            val detectable = if (kotlin.math.abs(d.average) > d.halfWidth) "yes" else "no"
            println("  %-22s %12.3f %12.3f %12s".format(name, d.average, d.halfWidth, detectable))
        }
    }
}
