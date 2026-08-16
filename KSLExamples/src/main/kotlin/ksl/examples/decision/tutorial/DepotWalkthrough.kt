package ksl.examples.decision.tutorial

import ksl.examples.decision.GreedyByShortageCost
import ksl.examples.decision.ProportionalShipping
import ksl.examples.decision.ShipNothing
import ksl.examples.decision.ShipmentDepot
import ksl.examples.decision.shipmentCost
import ksl.modeling.decision.ActionValidationException
import ksl.modeling.decision.DecisionContext
import ksl.modeling.decision.PolicyIfc
import ksl.simulation.Model
import kotlin.math.floor

/*
 * Tutorial Part IV -- when the feasible set depends on the state.
 *
 * A depot allocates scarce stock across three regions every review period. Three
 * constraints bind, and two of them MOVE:
 *
 *     ship[i]   <= backlog[i]        you cannot send what nobody has asked for
 *     sum(ship) <= onHand            you cannot ship what you do not have
 *     sum(ship) <= truckCapacity     the only constant among them
 *
 * Regions differ in shortage cost (9 / 3 / 1 per unit per unit time), so the allocation
 * is not symmetric: when stock is short, the expensive region should be served first.
 *
 * The model is `ksl.examples.decision.ShipmentDepot`, and it carries a switch this
 * walkthrough is built around — `stateDependentDeclaration` — so the SAME model can be
 * built with and without the element owning its own conservation law. That A/B is the
 * whole of Part IV.
 */

/**
 *  A rule that serves every region's full backlog, respecting the TRUCK but ignoring the
 *  SHELF. It is the natural first rule to write, and it is wrong in a way that only one of
 *  the two declarations can tell you about.
 *
 *  It deliberately stays inside the constant truck capacity, because that constraint is
 *  declarable under both designs and a violation of it proves nothing about the difference.
 *  What it ignores is stock, which is the constraint that MOVES.
 */
class ServeEveryBacklog(private val truckCapacity: Double) : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val want = DoubleArray(3) { observation[it] }        // observations 0..2 are backlogs
        val total = want.sum()
        if (total <= truckCapacity) return DoubleArray(3) { floor(want[it]) }
        // Scale into the truck, still without ever looking at what is on the shelf.
        return DoubleArray(3) { floor(want[it] * truckCapacity / total) }
    }
}

/**
 *  Probes the feasible set two ways at every epoch and counts disagreements. Ships nothing,
 *  so it does not perturb the run it is measuring.
 */
class FeasibilityProbe : PolicyIfc {
    var probes: Int = 0
        private set
    var disagreements: Int = 0
        private set

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val total = ctx.budgetTotal(0) ?: 0.0
        for (scale in listOf(0.0, 0.5, 1.0, 1.5)) {
            val candidate = DoubleArray(3) { Math.rint(total * scale / 3.0) }
            val isMember = ctx.actions.contains(candidate)
            val noViolations = ctx.actions.violations(candidate).isEmpty()
            probes++
            if (isMember != noViolations) disagreements++
        }
        return DoubleArray(3)
    }
}

/** What the walkthrough computed, so a test can check the claims it prints. */
class DepotWalkthroughResult(
    val overShipmentsUnderOldDeclaration: Int,
    val refusalUnderNewDeclaration: String?,
    val costs: Map<String, Double>,
    val cfaBeatsPfaByPercent: Double,
    val feasibleSetProbes: Int,
    val feasibleSetDisagreements: Int
)

private fun heading(n: Int, title: String) {
    println(); println("─".repeat(78)); println("  $n. $title"); println("─".repeat(78))
}

private fun build(stateDependent: Boolean, rule: PolicyIfc, reps: Int): Pair<Model, ShipmentDepot> {
    val model = Model("DepotWalkthrough")
    val depot = ShipmentDepot(model, stateDependentDeclaration = stateDependent, name = "Depot")
    depot.allocation.policy = rule
    model.numberOfReplications = reps
    model.lengthOfReplication = 2_000.0
    model.lengthOfReplicationWarmUp = 200.0
    return model to depot
}

fun main() { runDepotWalkthrough() }

/** The walkthrough proper. Returns what it computed; `main` simply runs it. */
fun runDepotWalkthrough(reps: Int = 20): DepotWalkthroughResult {

    // ------------------------------------------------------------------ 1. the problem
    heading(1, "A decision whose legal answers change every epoch")

    println("  Parts II and III had constraints that were CONSTANT: a lever's limits, and a")
    println("  budget that summed to eight. Here two of the three constraints are functions")
    println("  of the state — what each region is owed, and what is on the shelf — so the set")
    println("  of legal allocations is different at every review.")
    println()
    println("  That is the case the feasible set exists for. It is an OBJECT the rule can")
    println("  ask questions of, not a predicate the rule is tested against afterwards:")
    println()
    println("      ctx.actions.bounds(i)      what region i may receive, right now")
    println("      ctx.budgetTotal(0)         what may be shipped in total, right now")
    println()
    println("  Three of Powell's four policy classes score candidates drawn from that set,")
    println("  which is why it has to be enumerable rather than merely testable.")

    // ------------------------------------------------------------------ 2. the A/B
    heading(2, "What declaring the constraint buys — the same rule, two declarations")

    // The OLD declaration: only a constant envelope can be stated, so the element cannot
    // know the real bound and a rule that ignores stock ships more than exists.
    val (oldModel, oldDepot) = build(
        stateDependent = false,
        rule = ServeEveryBacklog(truckCapacity = 100.0),
        reps = 1
    )
    oldModel.simulate()
    val absorbed = oldDepot.overShipmentsAbsorbed

    println("  OLD declaration — only a constant truck capacity can be stated. A rule that")
    println("  asks for every region's full backlog proposes an allocation the depot cannot")
    println("  supply, and the ELEMENT CANNOT TELL. The model defends itself by clamping:")
    println()
    println("      over-shipments the model had to absorb: $absorbed")
    println()
    println("  Every one of those is a silent correction inside the model. The trajectory")
    println("  records what was written, so the rows are honest — but the rule was never")
    println("  told it asked for something impossible, and cannot learn from it.")
    println()

    val (newModel, _) = build(
        stateDependent = true,
        rule = ServeEveryBacklog(truckCapacity = 100.0),
        reps = 1
    )
    val refusal = runCatching { newModel.simulate() }.exceptionOrNull()
    val refusalText = (refusal as? ActionValidationException)?.violations?.toString()
        ?: refusal?.message

    println("  §4.4.6 declaration — the model states its own conservation law:")
    println()
    println("      lever(..., bounds = { 0.0..backlog(i) })")
    println("      atMost(*refs, envelope = truckCapacity) { shippableNow }")
    println()
    println("  The same rule now gets an answer instead of a silent clamp:")
    println("      ${refusal?.let { it::class.simpleName } ?: "no refusal — which would be a defect"}")
    refusalText?.lines()?.take(4)?.forEach { println("      $it") }
    println()
    println("  The declaration moved from the POLICY to the MODEL, which is where it always")
    println("  belonged: the conservation law is a fact about the depot, not an opinion of")
    println("  whoever is writing this week's rule. Every future rule inherits it for free,")
    println("  and none of them can violate it quietly.")

    // ------------------------------------------------------------------ 3. the comparison
    heading(3, "A rule that computes, and a rule that solves a small problem")

    val rates = doubleArrayOf(9.0, 3.0, 1.0)
    val costs = LinkedHashMap<String, Double>()

    fun run(label: String, rule: PolicyIfc) {
        val (m, _) = build(stateDependent = true, rule = rule, reps = reps)
        m.simulate()
        costs[label] = shipmentCost(m, "Depot", rates)
    }

    run("ship nothing", ShipNothing)
    run("proportional (PFA)", ProportionalShipping(useFeasibleSet = true))
    run("greedy by cost (CFA)", GreedyByShortageCost(rates, useFeasibleSet = true))

    println("  Shortage cost per unit time — regions charged at 9 / 3 / 1. Smaller is better")
    println("  here because this is a raw cost, not the sign-normalised estimand.")
    println()
    println("  %-24s %14s".format("rule", "cost/time"))
    costs.forEach { (k, v) -> println("  %-24s %14.2f".format(k, v)) }

    val pfa = costs["proportional (PFA)"]!!
    val cfa = costs["greedy by cost (CFA)"]!!
    val improvement = 100.0 * (pfa - cfa) / pfa
    println()
    println("  CFA over PFA: %+.1f%%".format(improvement))
    println()
    println("  The two rules are different POLICY CLASSES, not two tunings of one idea.")
    println()
    println("  `ProportionalShipping` is a policy function approximation: it computes an")
    println("  answer directly from the observation — shares proportional to backlog — and")
    println("  never considers an alternative. It also ignores the differing shortage costs,")
    println("  because a proportional share has nowhere to put them.")
    println()
    println("  `GreedyByShortageCost` is a cost function approximation: at every epoch it")
    println("  solves a small optimization SUBJECT TO the constraints in force, serving the")
    println("  expensive region first while stock lasts. It needs the feasible set to do")
    println("  that, which is why this is Part IV and not Part II.")

    // ------------------------------------------------------------------ 4. re-deriving it
    heading(4, "The same rule, forced to re-derive the constraint for itself")

    val (mReDerived, _) = build(
        stateDependent = false,
        rule = GreedyByShortageCost(rates, useFeasibleSet = false),
        reps = reps
    )
    mReDerived.simulate()
    val reDerived = shipmentCost(mReDerived, "Depot", rates)
    println("  greedy, asking the element      : %.2f".format(cfa))
    println("  greedy, re-deriving from scratch: %.2f".format(reDerived))
    println()
    println("  The arithmetic is IDENTICAL — it is the same class with one flag flipped. What")
    println("  differs is where the conservation law lives. Re-deriving it means the rule")
    println("  carries a literal `100.0` for the truck capacity, because the declaration")
    println("  could not express the real bound, so the rule has to be TOLD a number the")
    println("  model already knows. Change the truck and the model is right and the rule is")
    println("  wrong, silently.")
    println()
    println("  That is the argument for declaring the feasible set, and it is not about")
    println("  performance. It is about which artifact owns a fact.")

    // ------------------------------------------------------------------ 5. one predicate
    heading(5, "Membership and violation are one predicate, not two")

    val probe = FeasibilityProbe()
    val (probeModel, _) = build(stateDependent = true, rule = probe, reps = 2)
    probeModel.simulate()
    val probes = probe.probes
    val disagreements = probe.disagreements
    println("  A rule can ask 'is this allocation legal?' or 'what is wrong with it?'. Those")
    println("  must be the SAME question, or a rule that checks one and reports the other")
    println("  will contradict itself in front of a user.")
    println()
    println("  probes: $probes, disagreements: $disagreements")
    println()
    println("  Zero is the only acceptable number, and it is asserted rather than hoped for.")

    println()
    println("─".repeat(78))
    println("  Read this alongside docs/guides/ksl-decision-tutorial.md, Part IV.")
    println("─".repeat(78))

    return DepotWalkthroughResult(
        overShipmentsUnderOldDeclaration = absorbed,
        refusalUnderNewDeclaration = refusal?.let { it::class.simpleName },
        costs = costs,
        cfaBeatsPfaByPercent = improvement,
        feasibleSetProbes = probes,
        feasibleSetDisagreements = disagreements
    )
}
