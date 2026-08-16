package ksl.modeling.decision

import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.variable.TWResponse
import ksl.sdm.capture.DecisionCapture
import ksl.sdm.capture.MemorySink
import ksl.sdm.capture.RollingSink
import ksl.sdm.capture.TabularSink
import ksl.sdm.capture.decisionElements
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  §4.8.2 — **capture is attached from outside the model, and can be taken back off.**
 *
 *  The subsystem used to allow capture only inside `decisionElement { … }`, which meant that
 *  recording a model's decisions required editing the model. Whether a run is recorded is a
 *  property of the *run*, not of the subsystem being simulated, so a user holding somebody else's
 *  model, a script that records two of ten sweep configurations, and a tool layer with a "record
 *  decisions" checkbox were all locked out.
 *
 *  The fixture below is the point of the whole exercise: **`Shop` never mentions capture.** Every
 *  test here attaches to it from the outside, exactly as a `main()` would.
 *
 *  ### The invariant that is kept, and the one that was replaced
 *
 *  The old rule was "declare capture when the element is built". That forbade mid-run attachment —
 *  correctly, since a trajectory beginning inside an episode has no predecessor for its first row —
 *  but it also forbade the harmless cases of attaching *before* a run or *between* two runs, and it
 *  could only be enforced by making the API unreachable. The real invariant is that the set of
 *  sinks is fixed for the duration of an experiment, and unlike the old one it can be checked. It
 *  is checked: [aSinkCannotBeAttachedOrDetachedWhileTheModelIsRunning].
 */
class ExternalSinkAttachmentTest {

    /**
     *  A subsystem that knows nothing about capture. No `captureTo`, no sink field, no import.
     *
     *  The queue moves so that `state` and `successorState` differ; the lever burns some of it off.
     */
    private class Shop(parent: ModelElement, name: String) : ModelElement(parent, name) {

        val queue = TWResponse(this, name = "${this.name}:Queue", initialValue = 3.0)
        var overtime: Double = 0.0

        private fun arrival(event: KSLEvent<Nothing>) {
            queue.increment(2.0)
            if (queue.value > overtime) queue.decrement(overtime) else queue.value = 0.0
            schedule(this::arrival, 3.0)
        }

        override fun initialize() {
            schedule(this::arrival, 3.0)
        }

        val review: DecisionElement = decisionElement("${this.name}:Review") {
            observe(queue, unit = "jobs")
            lever(this@Shop, 0.0..8.0, neutral = Neutral.Value(0.0), alias = "Overtime") { v ->
                overtime = v
            }
            reward(queue, rate = 2.0, sense = RewardSense.COST, alias = "Waiting")
            every(10.0)
            policy = PolicyIfc { _, _ -> doubleArrayOf(3.0) }
        }
    }

    private fun model(reps: Int = 2, horizon: Double = 45.0): Pair<Model, Shop> {
        val m = Model("External")
        val shop = Shop(m, "S")
        m.numberOfReplications = reps
        m.lengthOfReplication = horizon
        return m to shop
    }

    // ---- The headline -----------------------------------------------------------

    /**
     *  A model built without any mention of capture records a full trajectory once a sink is
     *  attached to it from outside — and stops when the sink is detached.
     */
    @Test
    fun aSinkAttachedFromOutsideRecordsTheRunAndDetachingStopsIt() {
        val (m, shop) = model()
        val sink = MemorySink()

        assertFalse(shop.review.isCaptured, "a freshly built element captures nothing")
        assertEquals(0, shop.review.countTransitionSinks)

        shop.review.attachTransitionSink(sink)
        assertTrue(shop.review.isCaptured)
        assertTrue(shop.review.isTransitionSinkAttached(sink))
        m.simulate()
        val recorded = sink.records.size

        println()
        println("attached externally: $recorded rows over ${m.numberOfReplications} replications")
        assertTrue(recorded > 0,
            "the whole point is that a model which never mentions capture can be recorded; if " +
                "this is zero, nothing was attached and the rest of this class proves nothing")

        assertTrue(shop.review.detachTransitionSink(sink), "detach reports that it was attached")
        assertFalse(shop.review.isCaptured)
        m.simulate()

        assertEquals(recorded, sink.records.size,
            "after detaching, a second run must add NOTHING. If capture continued, detach is " +
                "decoration and a user cannot turn recording off")
    }

    /**
     *  The prohibition that survived: not during a run.
     *
     *  A policy is the only code that runs at a decision with a handle on the element, so it is
     *  what a mid-run attach would realistically come from — a UI callback on the simulation
     *  thread has exactly this shape.
     */
    @Test
    fun aSinkCannotBeAttachedOrDetachedWhileTheModelIsRunning() {
        val (m, shop) = model(reps = 1)
        val late = MemorySink()
        val attempts = mutableListOf<String>()

        shop.review.policy = PolicyIfc { _, _ ->
            attempts += assertFailsWith<IllegalStateException> {
                shop.review.attachTransitionSink(late)
            }.message ?: ""
            attempts += assertFailsWith<IllegalStateException> {
                shop.review.detachAllTransitionSinks()
            }.message ?: ""
            doubleArrayOf(3.0)
        }
        m.simulate()

        println()
        println("mid-run attach refused: ${attempts.firstOrNull()}")

        assertTrue(attempts.isNotEmpty(),
            "the policy never ran, so no attach was ever attempted and this test measured nothing")
        assertTrue(attempts.all { it.contains("while the simulation was running") },
            "both must be refused with the element's standard replication-initial message: $attempts")
        assertEquals(0, late.records.size, "a refused attach must not have half-attached")
        assertFalse(shop.review.isTransitionSinkAttached(late))
    }

    /** Attaching the same sink twice would deliver every row to it twice, so it is refused. */
    @Test
    fun theSameSinkCannotBeAttachedTwice() {
        val (_, shop) = model()
        val sink = MemorySink()
        shop.review.attachTransitionSink(sink)
        val e = assertFailsWith<IllegalStateException> { shop.review.attachTransitionSink(sink) }
        println()
        println("double attach refused: ${e.message}")
        assertTrue(e.message!!.contains("twice"))
        assertEquals(1, shop.review.countTransitionSinks)
    }

    /**
     *  Several sinks, each receiving every row, in attachment order.
     *
     *  This is what the single `sinkFactory` slot could not do at all: a live view *and* a file, or
     *  a user's own counter beside the durable trajectory.
     */
    @Test
    fun everyAttachedSinkReceivesEveryRecordInAttachmentOrder() {
        val (m, shop) = model()
        val order = mutableListOf<String>()

        class Watcher(val label: String) : TransitionSink {
            val rows = mutableListOf<TransitionRecord>()
            override fun write(record: TransitionRecord) {
                rows += record
                order += label
            }
        }

        val first = Watcher("first")
        val second = Watcher("second")
        val third = MemorySink()
        shop.review.attachTransitionSink(first)
        shop.review.attachTransitionSink(second)
        shop.review.attachTransitionSink(third)
        m.simulate()

        println()
        println("three sinks saw ${first.rows.size}/${second.rows.size}/${third.records.size} rows")

        assertTrue(first.rows.isNotEmpty(), "nothing was emitted, so nothing is being compared")
        assertEquals(first.rows.size, second.rows.size, "every sink gets every record")
        assertEquals(first.rows.size, third.records.size)
        assertEquals(first.rows.map { it.epochIndex }, second.rows.map { it.epochIndex },
            "and the same records, not merely the same count")
        assertEquals(List(first.rows.size) { listOf("first", "second") }.flatten(), order,
            "delivery follows attachment order, which is the documented guarantee")
    }

    /**
     *  Attaching between two runs records the second run and not the first — the case the old
     *  build-time-only rule forbade along with the mid-run one, though nothing is half-recorded.
     */
    @Test
    fun attachingBetweenTwoRunsRecordsTheSecondRunOnly() {
        val (m, shop) = model(reps = 1)
        m.experimentName = "baseline"
        m.simulate()

        val sink = MemorySink()
        shop.review.attachTransitionSink(sink)
        m.experimentName = "recorded"
        m.simulate()

        println()
        println("rows captured on the second run only: ${sink.records.size}")
        assertTrue(sink.records.isNotEmpty(),
            "the second run must be recorded in full; a rule that forbade this was protecting " +
                "against a half-recorded run that cannot occur here")
    }

    // ---- Provenance -------------------------------------------------------------

    /**
     *  Provenance arrives **per experiment**, not per attachment, and it carries what changed.
     *
     *  This is why the sink interface has a `beginExperiment` rather than a constructor argument:
     *  the experiment name and the policy label differ between runs of one model, which is exactly
     *  §4.9's k-rule comparison.
     */
    @Test
    fun provenanceIsDeliveredOncePerExperimentAndReflectsThatRun() {
        val (m, shop) = model(reps = 1)

        class Recorder : TransitionSink {
            val seen = mutableListOf<RunProvenance>()
            var ends = 0
            override fun beginExperiment(provenance: RunProvenance) { seen += provenance }
            override fun write(record: TransitionRecord) {}
            override fun endExperiment() { ends++ }
        }

        val r = Recorder()
        shop.review.attachTransitionSink(r)

        m.experimentName = "cheap"
        shop.review.policyLabel = "overtime=3"
        m.simulate()

        m.experimentName = "dear"
        shop.review.policy = PolicyIfc { _, _ -> doubleArrayOf(6.0) }
        shop.review.policyLabel = "overtime=6"
        m.simulate()

        println()
        r.seen.forEach { println("  ${it.experimentName} / ${it.policyLabel} / ${it.elementName}") }

        assertEquals(2, r.seen.size, "one handshake per experiment, not per attachment")
        assertEquals(2, r.ends, "and one end per experiment, paired with it")
        assertEquals(listOf("cheap", "dear"), r.seen.map { it.experimentName },
            "each run's provenance describes THAT run; a provenance frozen at attachment would " +
                "label the second run's rows with the first run's experiment")
        assertEquals(listOf("overtime=3", "overtime=6"), r.seen.map { it.policyLabel })
        assertEquals(shop.review.name, r.seen.first().elementName)
        assertEquals(1, r.seen.first().descriptor.levers.size)
    }

    /** No sink attached means no handshake — an element that records nothing computes nothing. */
    @Test
    fun anElementWithNoSinkBuildsNoRecordAtAll() {
        val (m, shop) = model()
        m.simulate()

        // The census still counts, which is the property the gate had to preserve: the emission
        // truth table of §4.10.2.1 is accounting, not capture, and it must not go blind when
        // nobody is listening.
        println()
        println("uncaptured run census: ${shop.review.census}")
        assertTrue(shop.review.census.emitted > 0,
            "the census must still count emissions with no sink attached — the gate that skips " +
                "record construction sits BELOW the accounting, and if it drifted above it the " +
                "emission truth table would read zero for every uncaptured run")
        assertFalse(shop.review.isCaptured)
    }

    // ---- RollingSink ------------------------------------------------------------

    /**
     *  A [RollingSink] makes one delegate per experiment and closes it at the end of that
     *  experiment — which is what lets an externally attached sink still leave one artifact per
     *  run, the thing the per-experiment factory bought and instance attachment would otherwise
     *  lose.
     */
    @Test
    fun aRollingSinkMakesAndClosesOneDelegatePerExperiment() {
        val (m, shop) = model(reps = 1)
        val made = mutableListOf<String>()
        val closed = mutableListOf<String>()

        class Leg(val label: String) : TransitionSink {
            var rows = 0
            override fun write(record: TransitionRecord) { rows++ }
            override fun close() { closed += label }
        }

        val legs = mutableListOf<Leg>()
        val rolling = RollingSink { p ->
            made += p.experimentName
            Leg(p.experimentName).also { legs += it }
        }
        shop.review.attachTransitionSink(rolling)

        m.experimentName = "one"
        m.simulate()
        m.experimentName = "two"
        m.simulate()

        println()
        println("delegates made: $made; closed: $closed; rows: ${legs.map { it.rows }}")

        assertEquals(listOf("one", "two"), made, "a fresh delegate for each experiment")
        assertEquals(listOf("one", "two"), closed,
            "and each closed when ITS experiment ended, not left open until the next one")
        assertTrue(legs.all { it.rows > 0 }, "both delegates must have actually received rows")
        assertEquals(null, rolling.delegate, "no delegate is held between runs")
    }

    // ---- DecisionCapture --------------------------------------------------------

    /** `Model.decisionElements()` finds them, which is what an external layer attaches to. */
    @Test
    fun theModelCanBeAskedForItsDecisionElements() {
        val m = Model("Two")
        val a = Shop(m, "A")
        val b = Shop(m, "B")
        val found = m.decisionElements()

        println()
        println("found: ${found.map { it.name }}")
        assertEquals(2, found.size)
        assertTrue(found.containsAll(listOf(a.review, b.review)))
    }

    /**
     *  The one-liner: capture a whole model to a directory, run it, and take the capture back off.
     *
     *  This is the `AnimationCapture.toFile` shape, and it is the API a `main()` or a tool layer
     *  actually reaches for.
     */
    @Test
    fun captureToDirectoryRecordsEveryElementAndCloseLeavesTheModelAsItFoundIt() {
        val dir: Path = Files.createTempDirectory("ksl-decision-capture")
        val m = Model("Two")
        val a = Shop(m, "A")
        val b = Shop(m, "B")
        m.experimentName = "sweep1"
        m.numberOfReplications = 2
        m.lengthOfReplication = 45.0

        DecisionCapture.toDirectory(m, dir).use { capture ->
            assertEquals(2, capture.capturedElements.size)
            assertEquals(1, a.review.countTransitionSinks)
            assertEquals(1, b.review.countTransitionSinks)
            m.simulate()
        }

        val files = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        println()
        println("wrote: $files")

        assertEquals(0, a.review.countTransitionSinks,
            "close() must leave the model exactly as it found it, or a second capture stacks on " +
                "the first and every row is written twice")
        assertEquals(0, b.review.countTransitionSinks)

        assertEquals(
            listOf(
                "A_Review-sweep1.provenance.json", "A_Review-sweep1.sqlite",
                "B_Review-sweep1.provenance.json", "B_Review-sweep1.sqlite"
            ),
            files,
            "one trajectory per element per experiment, and the colon of the KSL element name " +
                "must NOT reach the file name — it is legal on Linux, illegal on Windows, and an " +
                "alternate-data-stream separator on NTFS, so an unsanitised name writes a file " +
                "the reader cannot open: $files"
        )

        // And the trajectories are real: readable, with rows.
        val rows = files.filter { it.endsWith(".sqlite") }
        assertEquals(2, rows.size)
        for (f in rows) {
            ksl.sdm.capture.TrajectoryFile(dir.resolve(f)).use { t ->
                assertTrue(t.rowCount > 0, "$f is empty, so the capture attached but never wrote")
            }
        }
    }

    /** A capture with nothing to attach to is refused rather than silently recording nothing. */
    @Test
    fun aCaptureThatWouldAttachToNothingIsRefused() {
        val m = Model("Empty")
        val e = assertFailsWith<IllegalStateException> { DecisionCapture(m) { MemorySink() } }
        println()
        println("empty model refused: ${e.message}")
        assertTrue(e.message!!.contains("no decision elements"))

        val (m2, _) = model()
        val e2 = assertFailsWith<IllegalStateException> { DecisionCapture(m2) { null } }
        assertTrue(e2.message!!.contains("nothing was attached"),
            "a selector that skips everything is the same silence with a different cause")
    }

    /** A selector can record some elements and skip others. */
    @Test
    fun aSelectorCanCaptureSomeElementsAndSkipOthers() {
        val m = Model("Two")
        val a = Shop(m, "A")
        val b = Shop(m, "B")
        m.numberOfReplications = 1
        m.lengthOfReplication = 45.0
        val only = MemorySink()

        DecisionCapture(m, { e -> if (e === a.review) only else null }).use {
            assertEquals(1, it.capturedElements.size)
            assertSame(a.review, it.capturedElements.single())
            m.simulate()
        }

        println()
        println("selective capture recorded ${only.records.size} rows, all from ${a.review.name}")
        assertTrue(only.records.isNotEmpty())
        assertTrue(only.records.all { it.elementName == a.review.name },
            "the skipped element must not appear")
        assertEquals(0, b.review.countTransitionSinks)
    }

    /**
     *  `DecisionCapture` does not close a sink the caller made, and does close one it made itself.
     *
     *  Ownership follows construction, which is `ResponseTrace`'s rule and the reason the element
     *  stopped closing sinks at all.
     */
    @Test
    fun aCaptureClosesOnlyTheSinksItMadeItself() {
        val (m, shop) = model(reps = 1)

        class Owned : TransitionSink {
            var closed = false
            override fun write(record: TransitionRecord) {}
            override fun close() { closed = true }
        }

        val callersSink = Owned()
        DecisionCapture(m, { callersSink }).use { m.simulate() }
        assertFalse(callersSink.closed,
            "the caller constructed it, so the caller closes it — this is the ResponseTrace rule")
        assertEquals(0, shop.review.countTransitionSinks, "but it is still detached")

        val made = mutableListOf<Owned>()
        DecisionCapture.rolling(m) { Owned().also { made += it } }.use { m.simulate() }
        println()
        println("caller's sink closed=${callersSink.closed}; rolling delegates closed=" +
            made.map { it.closed })
        assertTrue(made.isNotEmpty() && made.all { it.closed },
            "a delegate the capture caused to exist must be closed")
    }

    // ---- The declared form still works ------------------------------------------

    /**
     *  `captureTo` is now one line over the new mechanism, so the sixteen existing declarations
     *  keep their exact semantics — and two of them now compose instead of the second silently
     *  discarding the first.
     */
    @Test
    fun theDeclaredFormStillWorksAndTwoDeclarationsNowCompose() {
        val dir: Path = Files.createTempDirectory("ksl-declared")
        val counted = MemorySink()

        class Declared(parent: ModelElement, name: String) : ModelElement(parent, name) {
            val queue = TWResponse(this, name = "${this.name}:Q", initialValue = 3.0)
            var overtime = 0.0
            private fun arrival(event: KSLEvent<Nothing>) {
                queue.increment(2.0)
                if (queue.value > overtime) queue.decrement(overtime) else queue.value = 0.0
                schedule(this::arrival, 3.0)
            }
            override fun initialize() { schedule(this::arrival, 3.0) }
            val review: DecisionElement = decisionElement("${this.name}:Review") {
                observe(queue)
                lever(this@Declared, 0.0..8.0, neutral = Neutral.Value(0.0), alias = "OT") { v ->
                    overtime = v
                }
                reward(queue, rate = 2.0, sense = RewardSense.COST, alias = "Waiting")
                captureTo { p -> TabularSink(p, dir.resolve(p.experimentName)) }
                captureTo { counted }          // a second declaration, which used to be discarded
                every(10.0)
                policy = PolicyIfc { _, _ -> doubleArrayOf(3.0) }
            }
        }

        val m = Model("Declared")
        val d = Declared(m, "D")
        m.experimentName = "run1"
        m.numberOfReplications = 1
        m.lengthOfReplication = 45.0
        assertEquals(2, d.review.countTransitionSinks, "two declarations, two sinks")
        m.simulate()

        val written = Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        println()
        println("declared capture wrote $written and the second sink saw ${counted.records.size} rows")

        assertTrue(counted.records.isNotEmpty(),
            "the SECOND captureTo must record too; it used to overwrite the first, so one of two " +
                "declarations silently did nothing")
        assertEquals(2, written.size, "the first captureTo still leaves its two files: $written")
        ksl.sdm.capture.TrajectoryFile(dir.resolve("run1.sqlite")).use { t ->
            assertEquals(counted.records.size.toLong(), t.rowCount,
                "both sinks saw the same rows, which is what 'every sink gets every record' means")
        }
    }
}
