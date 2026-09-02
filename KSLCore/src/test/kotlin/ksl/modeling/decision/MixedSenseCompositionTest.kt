package ksl.modeling.decision

import ksl.examples.general.decision.reviewEvery
import ksl.modeling.decision.descriptor.RewardKind
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.variable.Counter
import ksl.modeling.variable.TWResponse
import ksl.modeling.decision.capture.MemorySink
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  §4.2.5 — **a `COST` term and a `REWARD` term compose into one estimand, and the modeler tracks no
 *  signs.**
 *
 *  That is the distinctive promise of the composite-objective work, and until this class existed it
 *  had never been run. `RewardSense.REWARD` appeared exactly once in the whole tree outside its own
 *  enum declaration, and that one use was a data structure inside a codec round-trip test — never a
 *  running model. Every example, benchmark and simulating test used `COST`, which is also the
 *  default. So the sign convention introduced specifically so that *nothing downstream tracks signs*
 *  had one of its two branches with zero runtime coverage: covered-looking because the other branch
 *  is covered heavily. The same shape as the Level-2 stream assertion that could not fail.
 *
 *  ### Why the arithmetic here is exact
 *
 *  Nothing in this fixture is random. The observed level is held at a constant, and the counter
 *  advances on a fixed schedule offset off the epoch boundaries so no event is ever coincident with
 *  a decision. Every epoch therefore accrues the *same* hand-computable number, which means the
 *  assertion can be an equality rather than a tolerance — and an equality is what catches a sign.
 */
class MixedSenseCompositionTest {

    /**
     *  Level is pinned at [LEVEL] and never moves, so a `TIME_INTEGRAL` over an interval of length
     *  `τ` accrues exactly `LEVEL × τ`. The counter ticks once per time unit at the half-units, so
     *  a `COUNTER_TOTAL` over an interval of length 5 accrues exactly 5 and never lands on an epoch.
     */
    private class Shop(parent: ModelElement, name: String, val sense: RewardSense, val revenue: Double) :
        ModelElement(parent, name) {

        val level = TWResponse(this, name = "${this.name}:Level", initialValue = LEVEL)
        val served = Counter(this, name = "${this.name}:Served")
        var setting: Double = 0.0

        val sink = MemorySink()

        private fun tick(event: KSLEvent<Nothing>) {
            served.increment()
            schedule(this::tick, 1.0)
        }

        override fun initialize() {
            schedule(this::tick, 0.5)          // 0.5, 1.5, 2.5, … never coincident with an epoch
        }

        val review: DecisionElement = decisionElement("${this.name}:Review") {
            observe(level)
            lever(this@Shop, 0.0..10.0, neutral = Neutral.Value(0.0), alias = "L") { v -> setting = v }
            // A cost: holding the level is charged per unit per unit time.
            reward(level, rate = HOLDING, sense = RewardSense.COST, kind = RewardKind.TIME_INTEGRAL,
                alias = "Holding")
            // A revenue: every unit served earns. [sense] is a parameter so a test can flip it.
            reward(served, rate = revenue, sense = sense, kind = RewardKind.COUNTER_TOTAL,
                alias = "Revenue")
            captureTo { sink }
            policy = NeutralPolicy
        }.reviewEvery(this, EPOCH)
    }

    private fun run(sense: RewardSense = RewardSense.REWARD, revenue: Double = REVENUE): Shop {
        val m = Model("Mixed")
        val shop = Shop(m, "S", sense, revenue)
        m.numberOfReplications = 1
        m.lengthOfReplication = HORIZON
        m.simulate()
        return shop
    }

    /**
     *  The claim, checked by equality against a number computed by hand.
     *
     *  Per epoch of length 5:
     *  - holding costs `0.5 × (2 × 5) = 5`, and a `COST` is negated once, so it contributes `−5`
     *  - revenue earns `4 × 5 = 20`, and a `REWARD` is not negated, so it contributes `+20`
     *
     *  The composed reward for every emitted interval is therefore exactly **+15**, and the modeler
     *  wrote two positive rates and no minus sign anywhere.
     */
    @Test
    fun aCostAndARewardComposeIntoOneEstimandAndTheModelerTracksNoSigns() {
        val shop = run()
        val rows = shop.sink.records

        val holdingPerEpoch = -HOLDING * (LEVEL * EPOCH)          // -0.5 * 10 = -5
        val revenuePerEpoch = REVENUE * TICKS_PER_EPOCH           // +4 * 5   = +20
        val expected = holdingPerEpoch + revenuePerEpoch          // +15

        println()
        println("per epoch: holding ${holdingPerEpoch}, revenue +${revenuePerEpoch}, composed $expected")
        println("emitted intervals: ${rows.size}")
        rows.take(4).forEach {
            println("  epoch ${it.epochIndex} over tau=${it.tau}: reward = ${it.reward}")
        }

        assertTrue(rows.isNotEmpty(), "no interval was emitted, so nothing is being checked")
        for (r in rows) {
            assertEquals(EPOCH, r.tau, 1e-9, "every interval in this fixture is a full epoch")
            assertEquals(expected, r.reward, 1e-9,
                "epoch ${r.epochIndex} accrued ${r.reward} where the hand computation says " +
                    "$expected. A sign applied twice, or not at all, lands here")
        }

        // And the published estimand is the sum of what the rows say.
        val estimand = shop.review.estimand.withinReplicationStatistic.lastValue
        println("estimand = $estimand; sum of row rewards = ${rows.sumOf { it.reward }}")
        assertEquals(rows.sumOf { it.reward }, estimand, 1e-6,
            "the estimand must be the sum of the emitted interval rewards — if it is not, either " +
                "composition or emission is dropping something")
        assertTrue(estimand > 0.0,
            "revenue outweighs holding in this fixture, so the composite must come out POSITIVE. " +
                "A subsystem that negated both senses would report a plausible-looking negative")
    }

    /**
     *  The mutation, done in the fixture rather than by hand: declare the very same term as a `COST`
     *  and the composed reward must move by exactly twice that term's contribution.
     *
     *  This is what makes the test above a check on the *sense* rather than on the arithmetic in
     *  general. If sense were ignored, both arms would agree.
     */
    @Test
    fun flippingOneTermsSenseFlipsThatTermAndNothingElse() {
        val asRevenue = run(RewardSense.REWARD).sink.records.first().reward
        val asCharge = run(RewardSense.COST).sink.records.first().reward

        val contribution = REVENUE * TICKS_PER_EPOCH
        println()
        println("served as REWARD: $asRevenue")
        println("served as COST  : $asCharge")
        println("difference = ${asRevenue - asCharge}, twice the term's ${contribution}")

        assertEquals(2.0 * contribution, asRevenue - asCharge, 1e-9,
            "flipping a term's sense must move the composite by exactly twice that term, leaving " +
                "the other term alone. If these two arms agreed, `sense` would be decoration")
        assertTrue(asRevenue > 0 && asCharge < 0,
            "with both terms charged the composite must be negative, and with one earning it must " +
                "be positive — the two arms should not merely differ, they should differ in sign")
    }

    /**
     *  §4.2.5 again: the sense is applied **once, at declaration**, so the descriptor reports the
     *  rate the modeler wrote rather than the signed one the machinery uses.
     *
     *  A descriptor that reported `−0.5` would be describing the implementation instead of the
     *  declaration, and a consumer echoing it back into a configuration file would flip the sign a
     *  second time.
     */
    @Test
    fun theDescriptorReportsRatesAsWrittenNotAsSigned() {
        val d = run().review.descriptor()
        val holding = d.rewards.first { it.name == "Holding" }
        val revenue = d.rewards.first { it.name == "Revenue" }

        println()
        d.rewards.forEach { println("  ${it.name}: rate=${it.rate} sense=${it.sense} kind=${it.kind}") }

        assertEquals(HOLDING, holding.rate, "declared 0.5 as a COST; the descriptor must say 0.5")
        assertEquals(RewardSense.COST, holding.sense)
        assertEquals(RewardKind.TIME_INTEGRAL, holding.kind)
        assertEquals(REVENUE, revenue.rate)
        assertEquals(RewardSense.REWARD, revenue.sense)
        assertEquals(RewardKind.COUNTER_TOTAL, revenue.kind,
            "a counter accumulates as a total, and declaring it as a TIME_INTEGRAL is refused")
    }

    /** A term whose rate is zero is still declared and still reported — it contributes nothing. */
    @Test
    fun aTermWithARateOfZeroIsStillDeclaredAndStillReported() {
        val shop = run(RewardSense.REWARD, revenue = 0.0)
        val d = shop.review.descriptor()
        val row = shop.sink.records.first()

        println()
        println("with revenue rate 0.0: descriptor still lists ${d.rewards.map { it.name }}; " +
            "reward = ${row.reward}")

        assertEquals(2, d.rewards.size,
            "a zero-rated term must remain in the description — dropping it would make the " +
                "estimand's meaning depend on a value rather than on the declaration")
        assertEquals(-HOLDING * LEVEL * EPOCH, row.reward, 1e-9,
            "with revenue switched off, only the holding cost remains")
    }

    private companion object {
        const val LEVEL = 2.0             // the observed quantity, held constant
        const val EPOCH = 5.0             // decision interval
        const val HORIZON = 25.0
        const val HOLDING = 0.5           // per unit per unit time, declared as a COST
        const val REVENUE = 4.0           // per unit served, declared as a REWARD
        const val TICKS_PER_EPOCH = 5.0   // the counter ticks once per time unit
    }
}
