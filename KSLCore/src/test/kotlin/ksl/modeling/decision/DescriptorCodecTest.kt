package ksl.modeling.decision

import ksl.modeling.decision.descriptor.CounterRef
import ksl.modeling.decision.descriptor.DESCRIPTOR_SCHEMA_VERSION
import ksl.modeling.decision.descriptor.DecisionSurfaceDescriptor
import ksl.modeling.decision.descriptor.EpisodeDescriptor
import ksl.modeling.decision.descriptor.EpochKind
import ksl.modeling.decision.descriptor.FeasibilityPolicy
import ksl.modeling.decision.descriptor.LeverDescriptor
import ksl.modeling.decision.descriptor.LeverDomain
import ksl.modeling.decision.descriptor.LeverKind
import ksl.modeling.decision.descriptor.ObservationDescriptor
import ksl.modeling.decision.descriptor.ResponseRef
import ksl.modeling.decision.descriptor.RewardDescriptor
import ksl.modeling.decision.descriptor.RewardKind
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.decision.descriptor.SchemaVersion
import ksl.modeling.decision.descriptor.SchemaVersionException
import ksl.modeling.decision.descriptor.SumAtMost
import ksl.modeling.decision.descriptor.SumEquals
import ksl.modeling.decision.descriptor.fromJson
import ksl.modeling.decision.descriptor.fromToml
import ksl.modeling.decision.descriptor.toJson
import ksl.modeling.decision.descriptor.toToml
import ksl.modeling.decision.descriptor.validationProblems
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  §14.1 — the descriptor's JSON and TOML codecs (§18.2 item 1).
 *
 *  `MultipleElementsTest` already asserted that the descriptor *types* round-trip through
 *  `kotlinx.serialization`, which is the question that could have invalidated the design. This is
 *  the separate and lesser question of whether the convenience API exists and behaves: two formats,
 *  both directions, a version gate, and a structural gate.
 *
 *  Three things are asserted that a plain "it round-trips" test would not reach, and each exists
 *  because of a specific way a stored artifact goes wrong later:
 *
 *  1. **The discriminators are stable short names.** A sealed hierarchy encodes with a type tag, and
 *     the default tag is the fully-qualified Kotlin class name. A file written with
 *     `ksl.modeling.decision.descriptor.ResponseRef` in it stops loading the day the class moves
 *     package — which for an experimental subsystem is a matter of when, not whether.
 *  2. **A stored descriptor says what wrote it**, so a future reader can refuse it rather than
 *     misread it.
 *  3. **What the declaration DSL refuses, a decoded file also refuses.** Reading is not authoring
 *     (ADR-4), but text from outside can still assert a surface no model could have declared, and
 *     the decode is the only place that catches it before every consumer has to.
 */
class DescriptorCodecTest {

    /**
     *  A descriptor exercising every feature the format has: all three lever domains, both lever
     *  kinds, both constraint kinds, both source kinds, units, a narrowing, a state-dependent
     *  envelope, and an unbounded limit.
     */
    private fun rich() = DecisionSurfaceDescriptor(
        name = "Clinic:Review",
        observations = listOf(
            ObservationDescriptor("Clinic:NInQ", LeverDomain.CONTINUOUS, unit = "patients"),
            ObservationDescriptor("Clinic:Idle", LeverDomain.INTEGER)
        ),
        levers = listOf(
            LeverDescriptor(
                name = "Nurses", domain = LeverDomain.INTEGER, kind = LeverKind.SETTING,
                modelLowerLimit = 0.0, modelUpperLimit = 10.0,
                lowerBound = 2.0, upperBound = 8.0, unit = "staff"
            ),
            LeverDescriptor(
                name = "Doctors", domain = LeverDomain.INTEGER, kind = LeverKind.SETTING,
                modelLowerLimit = 0.0, modelUpperLimit = 10.0,
                lowerBound = 0.0, upperBound = 10.0, unit = "staff"
            ),
            LeverDescriptor(
                name = "Order", domain = LeverDomain.CONTINUOUS, kind = LeverKind.TRANSACTION,
                modelLowerLimit = 0.0, modelUpperLimit = Double.POSITIVE_INFINITY,
                lowerBound = 0.0, upperBound = Double.POSITIVE_INFINITY,
                stateDependent = true, unit = "units"
            ),
            LeverDescriptor(
                name = "Mode", domain = LeverDomain.CATEGORICAL, kind = LeverKind.SETTING,
                modelLowerLimit = 0.0, modelUpperLimit = 2.0,
                lowerBound = 0.0, upperBound = 2.0,
                levels = listOf("slow", "normal", "fast")
            )
        ),
        constraints = listOf(
            SumEquals(listOf("Nurses", "Doctors"), total = 10.0),
            SumAtMost(listOf("Order", "Mode"), total = 4.0)
        ),
        rewards = listOf(
            RewardDescriptor("Wait", ResponseRef("Clinic:NInQ"), RewardKind.TIME_INTEGRAL, 2.5, RewardSense.COST),
            RewardDescriptor("Served", CounterRef("Clinic:NServed"), RewardKind.COUNTER_TOTAL, 1.0, RewardSense.REWARD)
        ),
        episode = EpisodeDescriptor(maxEpochs = 12, hasTerminalCondition = true),
        feasibility = FeasibilityPolicy.CLAMP_THEN_REJECT
    )

    // -------------------------------------------------------------------------- round trips

    @Test
    fun aDescriptorSurvivesBothRoundTripsExactly() {
        val d = rich()
        val json = d.toJson()
        val toml = d.toToml()
        println()
        println("JSON: ${json.length} chars; TOML: ${toml.length} chars")
        println(toml)

        assertEquals(d, DecisionSurfaceDescriptor.fromJson(json),
            "the descriptor is the authority that gives a positional action vector its meaning, so " +
                "a lossy encoding makes a stored trajectory uninterpretable")
        assertEquals(d, DecisionSurfaceDescriptor.fromToml(toml), "and the same must hold for TOML")

        // The two formats must agree, or one of them is carrying a different surface.
        assertEquals(
            DecisionSurfaceDescriptor.fromJson(json), DecisionSurfaceDescriptor.fromToml(toml),
            "JSON and TOML must decode to the same descriptor"
        )
    }

    /**
     *  Unbounded is a real declaration — an order quantity with no ceiling — and infinity is how the
     *  format has to carry it. Checked separately because it is the value most likely to be mangled
     *  by a text format.
     */
    @Test
    fun anUnboundedLimitSurvivesBothFormats() {
        val d = rich()
        val j = DecisionSurfaceDescriptor.fromJson(d.toJson()).levers[2]
        val t = DecisionSurfaceDescriptor.fromToml(d.toToml()).levers[2]
        println()
        println("unbounded lever upper limit: json=${j.modelUpperLimit} toml=${t.modelUpperLimit}")
        assertEquals(Double.POSITIVE_INFINITY, j.modelUpperLimit)
        assertEquals(Double.POSITIVE_INFINITY, t.modelUpperLimit)
    }

    // -------------------------------------------------------------------------- durability

    /**
     *  The discriminator is what a stored file uses to know which subtype it is holding. If it is
     *  the fully-qualified class name, every stored file depends on the package layout of an
     *  experimental subsystem.
     */
    @Test
    fun theSubtypeDiscriminatorsAreStableShortNames() {
        val json = rich().toJson()
        val toml = rich().toToml()
        println()
        println("discriminators in JSON: " +
            Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").findAll(json).map { it.groupValues[1] }.toList())

        for (tag in listOf("response", "counter", "sumEquals", "sumAtMost")) {
            assertTrue(json.contains("\"$tag\""), "JSON must tag subtypes with '$tag'")
            assertTrue(toml.contains(tag), "TOML must tag subtypes with '$tag'")
        }
        for (text in listOf(json, toml)) {
            assertTrue(!text.contains("ksl.modeling.decision"),
                "a stored descriptor must not carry a Kotlin package path: it would bind every " +
                    "written file to the current package layout, and this subsystem is experimental")
        }
    }

    /** A stored artifact that does not say what wrote it cannot be refused later. */
    @Test
    fun aStoredDescriptorSaysWhichSchemaVersionWroteIt() {
        val json = rich().toJson()
        println()
        println("schema version in JSON: ${json.contains("schemaVersion")}; " +
            "in TOML: ${rich().toToml().contains("schemaVersion")}")
        assertTrue(json.contains("\"schemaVersion\""),
            "JSON must carry the schema version even though it is the default value — a file with " +
                "no version is the one that cannot be rejected when the format changes")
        assertEquals(SchemaVersion(2, 0), DESCRIPTOR_SCHEMA_VERSION,
            "the version this test was written against; changing it is a deliberate act")
    }

    /**
     *  Major is the compatibility boundary and minor is additive, so the two must behave
     *  differently. Both directions are asserted, because a gate that refuses everything is as
     *  useless as one that refuses nothing.
     */
    @Test
    fun aLaterMajorIsRefusedAndALaterMinorIsRead() {
        val d = rich()
        val laterMajor = d.copy(schemaVersion = SchemaVersion(major = 3, minor = 0)).toJson()
        val laterMinor = d.copy(schemaVersion = SchemaVersion(major = 2, minor = 7)).toJson()

        val t = assertFailsWith<SchemaVersionException> { DecisionSurfaceDescriptor.fromJson(laterMajor) }
        println()
        println("later major: ${t.message}")
        assertTrue(t.message!!.contains("3.0"), "the message must name the version it found")

        val read = DecisionSurfaceDescriptor.fromJson(laterMinor)
        assertEquals(SchemaVersion(2, 7), read.schemaVersion,
            "a later minor of the same major is additive and must be readable, keeping the version " +
                "it was written with rather than being silently restamped")

        assertFailsWith<SchemaVersionException> {
            DecisionSurfaceDescriptor.fromToml(d.copy(schemaVersion = SchemaVersion(9, 9)).toToml())
        }
    }

    /** Unknown keys are what a later minor version looks like from here. */
    @Test
    fun aFieldThisVersionHasNoNameForIsIgnoredRatherThanFatal() {
        val json = rich().toJson().replaceFirst("{", "{\n  \"aFieldFromTheFuture\": 42,")
        val back = DecisionSurfaceDescriptor.fromJson(json)
        println()
        println("decoded with an unknown key present: ${back.name}")
        assertEquals(rich(), back,
            "an additive field from a later minor version must be dropped, not fatal — that is " +
                "what makes minor versions additive rather than breaking")
    }

    // -------------------------------------------------------------------------- structure

    /**
     *  The structural gate, case by case. Each row is a hand-edit a person could plausibly make in
     *  a TOML file, and each must be refused with a message that names the problem.
     */
    @Test
    fun aFileAssertingASurfaceTheDslWouldRefuseIsRefused() {
        val base = rich()
        val cases = listOf<Pair<String, DecisionSurfaceDescriptor>>(
            "two levers with one name" to
                base.copy(levers = base.levers.map { it.copy(name = "Nurses") }),
            "a constraint over an undeclared lever" to
                base.copy(constraints = listOf(SumEquals(listOf("Nurses", "Ghost"), 10.0))),
            "a constraint over one lever" to
                base.copy(constraints = listOf(SumEquals(listOf("Nurses"), 10.0))),
            "a lever in two constraints" to
                base.copy(constraints = listOf(
                    SumEquals(listOf("Nurses", "Doctors"), 10.0),
                    SumAtMost(listOf("Nurses", "Order"), 4.0)
                )),
            "narrowed outside the model envelope" to
                base.copy(levers = base.levers.map {
                    if (it.name == "Nurses") it.copy(upperBound = 99.0) else it
                }),
            "maxEpochs of zero" to
                base.copy(episode = EpisodeDescriptor(maxEpochs = 0)),
            "a categorical lever with no levels" to
                base.copy(levers = base.levers.map {
                    if (it.name == "Mode") it.copy(levels = null) else it
                }),
            "a NaN bound" to
                base.copy(levers = base.levers.map {
                    if (it.name == "Nurses") it.copy(upperBound = Double.NaN) else it
                })
        )

        println()
        for ((label, bad) in cases) {
            val problems = bad.validationProblems()
            val t = assertFailsWith<IllegalArgumentException>("'$label' must be refused on decode") {
                DecisionSurfaceDescriptor.fromJson(bad.toJson())
            }
            println("  %-38s → %s".format(label, problems.firstOrNull()?.take(72) ?: "NONE"))
            assertTrue(problems.isNotEmpty(), "'$label' must produce at least one problem")
            assertTrue(t.message!!.isNotBlank())
        }

        // The control: the unmodified descriptor must pass, or every row above is meaningless.
        assertEquals(emptyList(), base.validationProblems(),
            "the base descriptor is well-formed; if it is not, the rows above may be passing for " +
                "the wrong reason")
    }

    /** A file with three mistakes should teach a person three things, not one. */
    @Test
    fun everyProblemIsReportedRatherThanTheFirst() {
        val bad = rich().copy(
            name = "  ",
            episode = EpisodeDescriptor(maxEpochs = -3),
            // A third mistake, of a different shape. It used to be a malformed epoch section; with
            // epochs gone from the descriptor (D5) the test needs another independent fault, or it
            // would only be checking that two problems are reported together.
            rewards = rich().rewards.map { it.copy(rate = Double.NaN) }
        )
        val problems = bad.validationProblems()
        println()
        problems.forEach { println("  - $it") }
        assertTrue(problems.size >= 3,
            "three independent mistakes were made and ${problems.size} reported; reporting the " +
                "first would make fixing a file a sequence of round trips")

        val t = assertFailsWith<IllegalArgumentException> { DecisionSurfaceDescriptor.fromJson(bad.toJson()) }
        assertTrue(t.message!!.contains("3 problem") || t.message!!.contains("problem(s)"),
            "and the thrown message carries them all: ${t.message}")
    }

    // -------------------------------------------------------------------------- end to end

    /**
     *  The case that matters: a descriptor **derived from a real declaration**, not hand-built.
     *
     *  A descriptor the codec can round-trip but the element never produces would be a format for
     *  nothing, and the two could drift apart without either test noticing.
     */
    @Test
    fun aDescriptorDerivedFromARealElementRoundTripsThroughBothFormats() {
        val model = Model("Derived")
        val s = Station(model, "A")
        val e = s.decisionElement("A:Review") {
            observe(s.level, unit = "units")
            val a = lever(s, 0..10, neutral = Neutral.Current { setting }, alias = "L", unit = "staff") { v -> setting = v }
            val b = lever(s, 0.0..5.0, neutral = Neutral.Value(0.0), alias = "Q", unit = "staff") { v -> setting += v }
            budget(a, b, total = 6.0)
            reward(s.level, rate = 1.5, sense = RewardSense.COST, alias = "R")
            every(10.0)
            maxEpochs(7)
            policy = NeutralPolicy
        }
        e.narrow(e.leverRef("L"), 2.0..8.0)

        val d = e.descriptor()
        println()
        println("derived descriptor: ${d.levers.size} levers, ${d.constraints.size} constraint(s)")
        println(d.toToml())

        assertEquals(emptyList(), d.validationProblems(),
            "an element cannot declare a surface its own descriptor calls malformed; if this fails " +
                "the validation rules have drifted from the DSL's, which is the one thing they may " +
                "not do")
        assertEquals(d, DecisionSurfaceDescriptor.fromJson(d.toJson()))
        assertEquals(d, DecisionSurfaceDescriptor.fromToml(d.toToml()))

        // And it really carries what a consumer needs, rather than round-tripping an empty shell.
        val lever = d.levers.first { it.name == "L" }
        assertEquals(2.0 to 8.0, lever.lowerBound to lever.upperBound, "the experiment's narrowing")
        assertEquals(0.0 to 10.0, lever.modelLowerLimit to lever.modelUpperLimit, "and the model's envelope")
    }

    private class Station(parent: ModelElement, name: String) : ModelElement(parent, name) {
        val level = TWResponse(this, name = "${this.name}:Level", initialValue = 2.0)
        var setting: Double = 0.0
    }
}
