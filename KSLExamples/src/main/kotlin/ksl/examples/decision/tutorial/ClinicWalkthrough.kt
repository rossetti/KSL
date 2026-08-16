package ksl.examples.decision.tutorial

import ksl.examples.decision.ClinicSubsystem
import ksl.examples.decision.ProportionalStaffing
import ksl.modeling.decision.FixedPolicy
import ksl.modeling.decision.NeutralPolicy
import ksl.modeling.decision.PolicyIfc
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.station.StationNetwork
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ExponentialRV

/*
 * Tutorial Part III -- several levers under a constraint, and an objective worth trusting.
 *
 * The clinic has two staffing levers that must sum to a fixed budget, both of them
 * SETTINGS rather than transactions, and a mixed-sense objective: revenue for patients
 * treated, charges for patients waiting. It is the smallest model in this tutorial where
 * "what is the right decision" is not obvious by inspection.
 *
 * The model itself is `ksl.examples.decision.ClinicSubsystem` and is not repeated here.
 * This file is the walkthrough: it runs the arms, prints what they produce, and — the
 * part that matters most — checks the objective against a measure the objective never
 * reads.
 */

/** What the walkthrough computed, so a test can check the claims it prints. */
class ClinicWalkthroughResult(
    val profits: Map<String, Double>,
    val halfWidths: Map<String, Double>,
    val systemTimes: Map<String, Double>,
    val rewardTerms: List<Triple<String, Double, RewardSense>>,
    val bestByProfit: String,
    val bestBySystemTime: String
)

private fun heading(n: Int, title: String) {
    println(); println("─".repeat(78)); println("  $n. $title"); println("─".repeat(78))
}

/** One arm: build a clinic, install a rule, run it, report both measures. */
private fun arm(label: String, rule: PolicyIfc, reps: Int): Triple<Double, Double, Double> {
    val model = Model("ClinicWalkthrough")
    val flow = StationNetwork(model, "ClinicFlow")
    val clinic = ClinicSubsystem(model, exit = flow.sink("Exit"), name = "Clinic")
    flow.source("Patients", ExponentialRV(5.0, streamNum = 3), firstReceiver = clinic.entry)
    clinic.shiftReview.policy = rule
    clinic.shiftReview.policyLabel = label
    model.numberOfReplications = reps
    model.lengthOfReplication = 43_200.0
    model.lengthOfReplicationWarmUp = 4_320.0
    model.simulate()

    val profit = clinic.shiftReview.estimand.acrossReplicationStatistic
    // The independent measure: mean time in the system, which the profit never reads.
    val systemTime = model.responses
        .first { it.name == "ClinicFlow:SystemTime" }
        .acrossReplicationStatistic.average
    return Triple(profit.average, profit.halfWidth, systemTime)
}

fun main() { runClinicWalkthrough() }

/** The walkthrough proper. Returns what it computed; `main` simply runs it. */
fun runClinicWalkthrough(reps: Int = 20): ClinicWalkthroughResult {

    // ------------------------------------------------------------------ 1. read the surface
    heading(1, "Two levers under a budget, and three reward terms")

    val probe = Model("ClinicSurface")
    val probeFlow = StationNetwork(probe, "ClinicFlow")
    val probeClinic = ClinicSubsystem(probe, exit = probeFlow.sink("Exit"), name = "Clinic")
    probeFlow.source("Patients", ExponentialRV(5.0, streamNum = 3), firstReceiver = probeClinic.entry)
    val surface = probeClinic.shiftReview.descriptor()

    surface.observations.forEachIndexed { i, o ->
        println("  observation[$i] = %-16s (%s)".format(o.name, o.unit ?: "unstated units"))
    }
    surface.levers.forEachIndexed { i, l ->
        println("  action[$i]      = %-16s a %s within %.0f..%.0f".format(
            l.name, l.kind, l.lowerBound, l.upperBound))
    }
    surface.constraints.forEach { println("  constraint      = $it") }
    println()
    println("  Both levers are SETTINGS. A capacity is a level the model HOLDS, so doing")
    println("  nothing means writing nothing, and the neutral carries a reader:")
    println("      neutral = Neutral.Current { capacity.toDouble() }")
    println("  That reader is what lets the element skip a write that would change nothing,")
    println("  which is what makes the do-nothing arm byte-identical to the model without a")
    println("  decision element at all. Part II's order quantity was the other kind — a")
    println("  TRANSACTION, whose neutral is the value zero, because there is no such thing")
    println("  as 'the current order'.")
    println()

    val terms = surface.rewards.map { Triple(it.name, it.rate, it.sense) }
    println("  The objective is a PROFIT, declared as one revenue and two charges:")
    terms.forEach { (n, rate, sense) -> println("      %-12s rate %5.1f  %s".format(n, rate, sense)) }
    println()
    println("  Every rate is a positive number in the units the modeler thinks in. `sense`")
    println("  carries the direction, and a COST is negated ONCE, here at declaration, so")
    println("  nothing downstream tracks a sign. Larger is better, everywhere, always.")

    // ------------------------------------------------------------------ 2. the arms
    heading(2, "Three allocations of the same eight staff")

    val arms = listOf(
        "static 4/4" to NeutralPolicy,
        "static 3/5" to FixedPolicy(doubleArrayOf(3.0, 5.0)),
        "static 2/6" to FixedPolicy(doubleArrayOf(2.0, 6.0)),
        "proportional" to ProportionalStaffing
    )

    println("  %-14s %14s %12s %16s".format("rule", "profit", "± half-w", "mean sys. time"))
    val profits = LinkedHashMap<String, Double>()
    val halves = LinkedHashMap<String, Double>()
    val times = LinkedHashMap<String, Double>()
    for ((label, rule) in arms) {
        val (p, h, t) = arm(label, rule, reps)
        profits[label] = p; halves[label] = h; times[label] = t
        println("  %-14s %14.1f %12.1f %16.2f".format(label, p, h, t))
    }

    val bestProfit = profits.maxByOrNull { it.value }!!.key
    val bestTime = times.minByOrNull { it.value }!!.key

    // ------------------------------------------------------------------ 3. the check
    heading(3, "Does the objective measure the clinic, or just itself?")

    println("  Declaring an objective is easy. Declaring one that DISCRIMINATES is the part")
    println("  worth checking, and the check is to compare it against a measure it never")
    println("  reads. Mean time in the system is that measure: the profit is built from")
    println("  patients processed and queue lengths, and never looks at a system time.")
    println()
    println("  best by profit         : $bestProfit")
    println("  best by mean sys. time : $bestTime")
    println()
    if (bestProfit == bestTime) {
        println("  They agree. 3/5 is the M/M/c optimum for this clinic — 4 staff against an")
        println("  offered load of 2.0 at triage and 2.4 at exam is the wrong split, and")
        println("  moving one server across is the right correction. The composite found it")
        println("  without being told, which is the evidence that it is measuring the clinic.")
    } else {
        println("  They DISAGREE, and that is a finding rather than a formatting problem.")
        println("  One of the two is wrong and it matters which — do not proceed until you")
        println("  know. This is the check earning its place.")
    }
    println()
    println("  Notice which term the decision actually moves. Throughput is arrival-limited,")
    println("  so revenue is much the same whatever the allocation; what reallocating staff")
    println("  changes is where patients WAIT. Real objectives usually look like this —")
    println("  several terms, of which the decision touches one — and it is worth seeing")
    println("  that written down rather than simplified away.")
    println()
    println("  And the over-cut arm should be bad enough to go NEGATIVE: with triage starved")
    println("  the waiting charges outweigh the revenue. A composite that stayed positive")
    println("  everywhere would be revenue wearing a costume.")

    // ------------------------------------------------------------------ 4. the failure
    heading(4, "The rule that oscillated, and why the arithmetic was never the problem")

    println("  `ProportionalStaffing` divides the budget in proportion to observed demand.")
    println("  Its first version allocated in proportion to QUEUE LENGTH and was a disaster:")
    println()
    println("      proportional to queue length              187.45   mean system time")
    println("      proportional to instantaneous busy units   68.84")
    println("      proportional to time-averaged busy units   19.03   <- the rule above")
    println("      static 3/5, the M/M/c optimum             19.01")
    println()
    println("  Two separate defects, and fixing either alone is not enough.")
    println()
    println("  DEMAND, NOT CONGESTION. Queue length is a CONSEQUENCE of the allocation, so")
    println("  allocating on it closes a positive feedback loop with a 480-unit delay. Busy")
    println("  units are what the arrival process makes them — while a station has enough")
    println("  capacity, its busy count does not depend on what the rule decided. Allocating")
    println("  on a quantity the rule does not itself move is what breaks the loop.")
    println()
    println("  AN AVERAGE, NOT A SNAPSHOT. Instantaneous busy units at triage are 0, 1, 2 or")
    println("  3 around a mean of 1.2 — the station is idle about 30% of the time. A")
    println("  snapshot of (0, 3) sends every spare unit to exam and leaves triage below its")
    println("  own offered load for a full shift.")
    println()
    println("  Damping the original — moving partway toward the target, or capping the step")
    println("  — would reduce the amplitude without removing the loop. The signal was wrong,")
    println("  in two ways, and the fix is to the DECLARATION rather than to the arithmetic.")

    println()
    println("─".repeat(78))
    println("  Read this alongside docs/guides/ksl-decision-tutorial.md, Part III.")
    println("─".repeat(78))

    return ClinicWalkthroughResult(
        profits = profits,
        halfWidths = halves,
        systemTimes = times,
        rewardTerms = terms,
        bestByProfit = bestProfit,
        bestBySystemTime = bestTime
    )
}
