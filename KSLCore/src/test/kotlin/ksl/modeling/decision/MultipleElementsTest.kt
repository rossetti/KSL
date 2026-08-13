package ksl.modeling.decision

import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.variable.TWResponse
import ksl.sdm.capture.MemorySink
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  §4.1.9 — several decision elements in one model, which the design supported structurally and
 *  nothing exercised. Written by an interrogation pass before the port (§7.2.0), on the reasoning
 *  that the marginal cost of finding a defect on a working copy is a commit and the cost of finding
 *  it after the port is a commit against the library's real history.
 *
 *  It found nothing wrong with the subsystem and one thing wrong with a shipped example, which is
 *  recorded here because the cause is an ergonomic property of the DSL rather than a typo:
 *  `decisionElement("Review")` reads as though the name were local to the subsystem, while every
 *  other KSL child construction — `TWResponse(this, name = …)` — visibly demands a model-wide
 *  identifier. `SsInventory` qualified every one of its other children and left this one bare.
 */
class MultipleElementsTest {

    /** A subsystem that qualifies its child names, as KSL requires and as this class demonstrates. */
    private class Station(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "${this.name}:Level", initialValue = 2.0)
        var setting: Double = 0.0
        lateinit var review: DecisionElement

        fun declare(sink: MemorySink?, value: Double, interval: Double, qualified: Boolean = true) {
            val elementName = if (qualified) "${this.name}:Review" else "Review"
            review = decisionElement(elementName) {
                observe(level)
                lever(this@Station, 0.0..10.0, neutral = Neutral.Current { setting },
                    alias = "L") { v -> setting = v }
                reward(level, rate = 1.0, sense = RewardSense.COST, alias = "R")
                if (sink != null) captureTo { sink }
                every(interval)
                policy = PolicyIfc { _, _ -> doubleArrayOf(value) }
            }
        }
    }

    /**
     *  The trap, asserted so the convention has a reason on record rather than a habit.
     *
     *  KSL requires element names to be unique model-wide, so a subsystem that names its decision
     *  element with a bare literal cannot be instantiated twice. The failure is loud and at
     *  construction, which is the right place — this test exists to document *why* the examples
     *  qualify, not because the behavior is wrong.
     */
    @Test
    fun anUnqualifiedElementNameMakesASubsystemSingleUse() {
        val model = Model("Unqualified")
        Station(model, "A").declare(null, 1.0, 10.0, qualified = false)
        val t = assertFailsWith<IllegalArgumentException> {
            Station(model, "B").declare(null, 2.0, 10.0, qualified = false)
        }
        assertTrue(t.message!!.contains("already been added"),
            "KSL's own duplicate-name refusal is what fires, and it names the offender")
    }

    /** Two instances of one subsystem, correctly named, are fully independent. */
    @Test
    fun twoElementsRunIndependentlyAndTheirRowsStayDistinguishable() {
        val model = Model("TwoElements")
        val shared = MemorySink()
        val a = Station(model, "A").apply { declare(shared, 1.0, 10.0) }
        val b = Station(model, "B").apply { declare(shared, 2.0, 10.0) }
        model.numberOfReplications = 1
        model.lengthOfReplication = 55.0
        model.simulate()

        val byElement = shared.records.groupBy { it.elementName }
        println()
        println("two elements sharing one sink: ${byElement.mapValues { it.value.size }}")

        assertEquals(setOf("A:Review", "B:Review"), byElement.keys)
        assertTrue(byElement.getValue("A:Review").all { it.action[0] == 1.0 })
        assertTrue(byElement.getValue("B:Review").all { it.action[0] == 2.0 })
        assertEquals(1.0, a.setting, 1e-9)
        assertEquals(2.0, b.setting, 1e-9)

        // Each element publishes its own estimand, named after itself.
        val rewards = model.responses.map { it.name }.filter { it.contains("TotalReward") }.toSet()
        assertEquals(setOf("A:Review:TotalReward", "B:Review:TotalReward"), rewards,
            "an element that declares a reward adds exactly one response, named after itself")
    }

    /** Narrowing one element must not reach another, and a foreign reference must be refused. */
    @Test
    fun narrowingIsPerElementAndForeignReferencesAreRefused() {
        val model = Model("Narrowing")
        val a = Station(model, "A").apply { declare(null, 1.0, 10.0) }
        val b = Station(model, "B").apply { declare(null, 2.0, 10.0) }

        a.review.narrow(a.review.leverRef("L"), 0.0..3.0)
        assertEquals(0..3, a.review.limitsOf(a.review.leverRef("L")))
        assertEquals(0..10, b.review.limitsOf(b.review.leverRef("L")),
            "narrowing one element's lever must not reach an identically-aliased lever on another")

        assertFailsWith<BindingException> { b.review.narrow(a.review.leverRef("L"), 0.0..2.0) }
        assertEquals(0..10, b.review.limitsOf(b.review.leverRef("L")),
            "and the refused call changes nothing")
    }

    /** Descriptors describe one element each and do not leak. */
    @Test
    fun eachElementDescribesOnlyItself() {
        val model = Model("Descriptors")
        val a = Station(model, "A").apply { declare(null, 1.0, 10.0) }
        val b = Station(model, "B").apply { declare(null, 2.0, 20.0) }

        val da = a.review.descriptor()
        val db = b.review.descriptor()
        assertEquals("A:Review", da.name)
        assertEquals("B:Review", db.name)
        assertEquals(listOf("A:Level"), da.observations.map { it.name })
        assertEquals(listOf("B:Level"), db.observations.map { it.name })
        assertEquals(10.0, da.epochs.interval)
        assertEquals(20.0, db.epochs.interval, "each carries its own epoch timing")
    }

    /**
     *  §4.10.2's descriptor is `@Serializable`, and this is the assertion that it actually is —
     *  the JSON and TOML *codecs* are unwritten (§7.3.0 step 4), but whether the types round-trip
     *  is a separate question from whether the convenience API exists, and it is the one that
     *  would invalidate the design rather than merely delay it.
     */
    @Test
    fun theDescriptorRoundTripsThroughJsonWithoutLoss() {
        val model = Model("RoundTrip")
        val s = Station(model, "A")
        val e = s.decisionElement("A:Review") {
            observe(s.level, unit = "units")
            lever(s, 0..10, neutral = Neutral.Current { setting }, alias = "L", unit = "staff") { v -> setting = v }
            lever(s, 0.0..5.0, neutral = Neutral.Value(0.0), alias = "Q") { v -> setting += v }
            reward(s.level, rate = 1.5, sense = RewardSense.COST, alias = "R")
            every(10.0)
            maxEpochs(7)
            policy = NeutralPolicy
        }

        val d = e.descriptor()
        val json = Json { encodeDefaults = true }
        val text = json.encodeToString(DecisionSurfaceDescriptor.serializer(), d)
        val back = json.decodeFromString(DecisionSurfaceDescriptor.serializer(), text)

        println()
        println("descriptor round trip: ${text.length} chars, equal = ${back == d}")
        assertEquals(d, back,
            "the descriptor must survive a round trip exactly — it is the authority that gives a " +
                "positional action vector its meaning (§4.2.3), so a lossy encoding would make a " +
                "stored trajectory uninterpretable")

        // And it really carries the things a consumer needs, rather than round-tripping an empty shell.
        assertTrue(text.contains("\"unit\":\"staff\""), "declared units survive")
        assertTrue(text.contains("TRANSACTION") && text.contains("SETTING"), "both lever kinds survive")
        assertTrue(text.contains("\"maxEpochs\":7"), "the episode cap survives")
    }
}
