package ksl.examples.decision

import ksl.modeling.decision.*
import ksl.modeling.variable.RandomVariable
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.UniformRV
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 *  Coverage, not callers. The declaration surface admits action spaces that no single
 *  search strategy can handle, so the library needs more than one — and which ones is
 *  determined by what a modeler may declare, not by which have been used here.
 *
 *  The shipment depot declares three integer levers with state-dependent bounds and a
 *  state-dependent joint total. §8.2.10 measured the consequence: the feasible set ranges
 *  from 60 to 999,698 actions and exceeds the enumeration ceiling in 97% of epochs. So on
 *  a model this design already permits, `ExhaustiveSearch` is not merely slow — it throws.
 */
class SearchStrategyCoverageTest {

    /** A policy that is a ModelElement so it can own its randomness, per §4.5.6. */
    private class SamplingAllocator(
        parent: ModelElement,
        private val rates: DoubleArray,
        candidates: Int,
        streamNum: Int = 41
    ) : ModelElement(parent), PolicyIfc {

        private val uniform = RandomVariable(this, UniformRV(0.0, 1.0, streamNum))
        private val search = SampledSearch(candidates, uniform)

        var exhaustiveWouldHaveThrown = 0
        var searchFoundNothing = 0
        var epochs = 0
        var maxSize = 0L

        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            epochs++
            val n = ctx.actions.size
            if (n == null) exhaustiveWouldHaveThrown++ else maxSize = maxOf(maxSize, n)
            val best = search.best(ctx.actions) { a ->
                // Shortage left unserved, weighted by each region's cost rate.
                var unserved = 0.0
                for (i in a.indices) unserved += rates[i] * (observation[i] - a[i])
                unserved
            }
            if (best == null) searchFoundNothing++
            return best ?: DoubleArray(ctx.leverNames.size)
        }
    }

    @Test
    fun samplingSearchesASetThatEnumerationCannotWalk() {
        val model = Model("SearchCoverage")
        val depot = ShipmentDepot(model, stateDependentDeclaration = true, name = "Depot")
        val rule = SamplingAllocator(model, depot.shortageRates, candidates = 150)
        model.numberOfReplications = 10
        model.lengthOfReplication = 5_000.0
        model.lengthOfReplicationWarmUp = 1_000.0
        depot.allocation.policy = rule
        model.simulate()

        val cost = shipmentCost(model, "Depot", depot.shortageRates)
        println()
        println("Sampled search over a set enumeration cannot walk:")
        println("  epochs                                    : ${rule.epochs}")
        println("  largest feasible set seen                 : %,d actions".format(rule.maxSize))
        println("  epochs where ExhaustiveSearch would throw : ${rule.exhaustiveWouldHaveThrown}")
        println("  epochs where sampling found nothing       : ${rule.searchFoundNothing}")
        println("  shortage cost per unit time               : %.2f".format(cost))

        // The honest claim: sampling searches this set successfully and the set is far too
        // large to want to walk. Whether it CROSSES the ceiling depends on the policy — a
        // competent allocator keeps backlogs small, a do-nothing one lets them explode
        // (§8.2.10). That the regime depends on the rule is precisely why the library
        // cannot pick a search strategy on the modeler's behalf.
        assertTrue(rule.maxSize > 1_000,
            "the largest set was only ${rule.maxSize}; this model does not exercise sampling")
        assertTrue(rule.searchFoundNothing == 0,
            "rejection sampling starved on ${rule.searchFoundNothing} of ${rule.epochs} epochs")
        assertTrue(cost > 0.0)
    }

    /**
     *  The honest limit of rejection sampling, stated because a library that ships a
     *  strategy should say where it fails. A `SumEquals` budget is a measure-zero slice of
     *  the box, so drawing uniformly and rejecting essentially never lands on it.
     *
     *  This is not a reason to omit sampling; it is a reason for `ActionSet.sample` to be a
     *  member of the SET — a constraint-aware sampler can replace the default per
     *  constraint kind without any policy changing.
     */
    @Test
    fun rejectionSamplingStarvesUnderAnEqualityBudget() {
        val model = Model("SamplerLimit")
        val flow = ksl.modeling.station.StationNetwork(model, "Flow")
        val exit = flow.sink("Exit")
        var observed = Double.NaN

        val clinic = object : ModelElement(model, "Clinic") {
            private val a = ksl.modeling.station.SResource(this, capacity = 4, name = "A")
            private val b = ksl.modeling.station.SResource(this, capacity = 4, name = "B")
            private val sB = ksl.modeling.station.SingleQStation(
                this, ksl.utilities.random.rvariable.ExponentialRV(12.0, streamNum = 2),
                resource = b, nextReceiver = exit, name = "SB")
            private val sA = ksl.modeling.station.SingleQStation(
                this, ksl.utilities.random.rvariable.ExponentialRV(6.0, streamNum = 1),
                resource = a, nextReceiver = sB, name = "SA")
            val entry: ksl.modeling.station.QObjectReceiverIfc get() = sA
            val element = decisionElement("Review") {
                observe(sA.waitingQ.numInQ); observe(sB.waitingQ.numInQ)
                val ra = lever(a, limits = 0..10, read = { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
                val rb = lever(b, limits = 0..10, read = { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
                budget(ra, rb, total = 8.0)          // SumEquals: a measure-zero slice
                every(480.0)
                policy = HoldCurrentPolicy
            }
        }
        flow.source("In", ksl.utilities.random.rvariable.ExponentialRV(5.0, streamNum = 3),
            firstReceiver = clinic.entry)

        val prober = object : ModelElement(model, "Prober"), PolicyIfc {
            private val uniform = RandomVariable(this, UniformRV(0.0, 1.0, streamNum = 51))
            override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
                ctx.actions.sample(uniform, 50).count()
                observed = ctx.actions.acceptanceRate
                return ctx.currentAction
            }
        }
        model.numberOfReplications = 2
        model.lengthOfReplication = 43_200.0
        clinic.element.policy = prober
        model.simulate()

        println()
        println("Rejection sampling under a SumEquals budget: acceptance rate %.4f".format(observed))
        assertTrue(observed < 0.25,
            "acceptance was %.3f — if rejection now works under an equality budget, this documented limit is stale"
                .format(observed))
    }
}
