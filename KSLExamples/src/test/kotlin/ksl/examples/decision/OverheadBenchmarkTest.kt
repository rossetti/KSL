package ksl.examples.decision

import ksl.modeling.decision.ActionSearch
import ksl.modeling.decision.DecisionContext
import ksl.modeling.decision.ExhaustiveSearch
import ksl.modeling.decision.PolicyIfc
import ksl.modeling.decision.RunProvenance
import ksl.modeling.decision.TransitionSink
import ksl.modeling.station.StationNetwork
import ksl.sdm.capture.MemorySink
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 *  §16.4 / **G11** — what a decision epoch costs, measured (§18.2 item 7).
 *
 *  G11's criterion is that the per-epoch cost be **known**, not that it meet a target chosen before
 *  anyone measured. So this class asserts almost nothing about the numbers and prints all of them;
 *  what it asserts is that the measurement was actually taken and that each arm did the work it
 *  claims. A benchmark that fails the build when a shared machine is busy teaches nobody anything,
 *  and one smoke bound is kept only to catch a catastrophic regression (see the final test).
 *
 *  ### Method: the slope, not the difference
 *
 *  The obvious way to measure the element's overhead is to run the model with and without it. Both
 *  reference models are *built around* their decision element, so there is no undecorated arm to
 *  run, and a hand-written stand-in would be measuring a different model.
 *
 *  Instead the **epoch interval is swept** and the run time regressed against the number of epochs.
 *  Halving the interval doubles the epochs and changes nothing else, so
 *
 *      time(epochs) ≈ intercept + slope × epochs
 *
 *  where **slope is the per-epoch cost** and **intercept is what the model costs with no epochs at
 *  all** — the undecorated arm, obtained by extrapolation rather than by building one. Dividing the
 *  intercept by the event count gives the per-event cost that §16.4 asks the numbers to be quoted
 *  against.
 *
 *  This works only because neither reference model's *trajectory* depends on the epoch interval
 *  under the neutral policy, and that is a fact about them rather than an assumption: the clinic's
 *  levers are SETTINGs whose neutral is their current value, so the element elides every write; the
 *  inventory's lever is a TRANSACTION whose neutral is zero, and `placeOrder(0)` returns before
 *  scheduling anything. Both are checked below — the event count must not move across the sweep, or
 *  the regression is fitting something other than epoch cost.
 *
 *  ### The three parts §16.4 asks for
 *
 *  Each is a slope, so each is measured the same way and they are directly comparable:
 *
 *  1. **The element's own overhead** — neutral policy, capture off.
 *  2. **Capture** — the same, with a memory sink, minus part 1.
 *  3. **The policy's own cost** — the search over 𝒳(*s*), minus part 1.
 *
 *  Part 3 uses a rule that enumerates and scores the entire feasible set and then **returns the
 *  neutral action anyway**. That is deliberate: a rule that acted would change the trajectory, and
 *  the difference would then mix the cost of searching with the cost of whatever the decisions did
 *  to the model. Scoring without acting isolates the search, which is the quantity §16.4 says
 *  matters for design feedback.
 */
class OverheadBenchmarkTest {

    // ------------------------------------------------------------------ arms

    /** Counts epochs, so the regressor is an exact count rather than an inferred one. */
    private class CountingNeutral : PolicyIfc {
        var epochs = 0
        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            epochs++
            return ctx.neutralAction
        }
    }

    /**
     *  A candidate-scoring rule that does the search and then declines to use it.
     *
     *  The score is deliberately trivial — a sum over the action vector — so that what is being
     *  timed is the enumeration of 𝒳(*s*) and the machinery around it, not the arithmetic of some
     *  particular objective, which is the rule author's cost and not the subsystem's.
     */
    private class ScoringProbe(private val search: ActionSearch = ExhaustiveSearch) : PolicyIfc {
        var epochs = 0

        /** Actions handed to the score function — the *feasible* members of 𝒳(s). */
        var scored = 0L

        /**
         *  Actions the enumeration had to **test**, which is the box product over the levers'
         *  feasible ranges. This is the larger number whenever a joint constraint is declared, and
         *  it is the one the cost is actually proportional to: `asSequence` walks the box and
         *  yields the members that `prepare` accepts, so a budgeted pair at 0..10 tests 121 points
         *  to yield 9. Dividing the search cost by the 9 would report a per-candidate figure 13
         *  times too large and make two models look 25x apart when they are not.
         */
        var tested = 0L

        override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
            epochs++
            tested += ctx.actions.size ?: 0L
            search.best(ctx.actions) { a -> scored++; a.sum() }
            return ctx.neutralAction
        }
    }

    private enum class Arm(val label: String) {
        NEUTRAL("element only"),
        CAPTURE("+ capture"),
        SCORING("+ search")
    }

    /**
     *  Reads the executive's event count back out.
     *
     *  `Model.executive` is `protected`, so the count is only reachable from inside a
     *  `ModelElement`. This one does nothing else, and it is present in **every** arm so that
     *  whatever it costs is common to all of them and cancels out of the slope.
     */
    private class EventCounter(parent: ModelElement, name: String) : ModelElement(parent, name) {
        var events: Long = 0
            private set
        override fun replicationEnded() { events = executive.numEventsExecuted }
    }

    /** One timed run. */
    private class Run(
        val nanos: Long, val epochs: Int, val events: Long,
        val captured: Int, val scored: Long, val tested: Long)

    /** A model built for one arm at one epoch interval, plus the probes that read it back. */
    private class Rig(val model: Model, val run: () -> Run)

    // ------------------------------------------------------------------ the two reference models

    private fun clinicRig(arm: Arm, interval: Double): Rig {
        val model = Model("ClinicBench")
        val sink = if (arm == Arm.CAPTURE) MemorySink() else null
        val flow = StationNetwork(model, "ClinicFlow")
        val exit = flow.sink("Exit")
        val clinic = ClinicSubsystem(
            model, exit,
            decisionSink = sink?.let { s -> { _: RunProvenance -> s as TransitionSink } },
            name = "Clinic"
        )
        flow.source("Patients", ExponentialRV(5.0, streamNum = 3), firstReceiver = clinic.entry)
        val counter = EventCounter(model, "Events")
        val neutral = CountingNeutral()
        val scoring = ScoringProbe()
        clinic.shiftReview.policy = if (arm == Arm.SCORING) scoring else neutral
        clinic.shiftReview.epochInterval = interval
        model.numberOfReplications = 1
        model.lengthOfReplication = CLINIC_LENGTH
        return Rig(model) {
            val t0 = System.nanoTime()
            model.simulate()
            val dt = System.nanoTime() - t0
            Run(dt, neutral.epochs + scoring.epochs, counter.events,
                sink?.records?.size ?: 0, scoring.scored, scoring.tested)
        }
    }

    private fun inventoryRig(arm: Arm, interval: Double): Rig {
        val model = Model("InventoryBench")
        val sink = if (arm == Arm.CAPTURE) MemorySink() else null
        val inv = SsInventory(
            model, reviewPeriod = interval,
            decisionSink = sink?.let { s -> { _: RunProvenance -> s as TransitionSink } },
            name = "Inv"
        )
        val counter = EventCounter(model, "Events")
        val neutral = CountingNeutral()
        val scoring = ScoringProbe()
        inv.review.policy = if (arm == Arm.SCORING) scoring else neutral
        model.numberOfReplications = 1
        model.lengthOfReplication = INVENTORY_LENGTH
        return Rig(model) {
            val t0 = System.nanoTime()
            model.simulate()
            val dt = System.nanoTime() - t0
            Run(dt, neutral.epochs + scoring.epochs, counter.events,
                sink?.records?.size ?: 0, scoring.scored, scoring.tested)
        }
    }

    private class Subject(
        val name: String,
        val intervals: List<Double>,
        val rig: (Arm, Double) -> Rig
    )

    /**
     *  The sweeps span 64x in epoch count, not 8x.
     *
     *  The per-epoch cost is small and the models are not: the clinic fires ~25 700 events of its
     *  own whatever the epoch interval, so at the original 90 epochs the entire quantity being
     *  measured was under half a percent of the run and the fit was reading noise. The widest point
     *  has to make the epochs a visible fraction of the work, which is what a sweep is for.
     */
    private fun subjects() = listOf(
        Subject("Clinic (2 levers under a budget)", listOf(480.0, 120.0, 30.0, 7.5), ::clinicRig),
        Subject("Inventory (1 lever, 0..200)", listOf(5.0, 2.5, 1.25, 0.625), ::inventoryRig)
    )

    // ------------------------------------------------------------------ timing discipline

    /**
     *  The **fastest** of [REPEATS] timed runs, after discarded warm-up runs.
     *
     *  The minimum rather than the mean or the median, because the noise here is one-sided:
     *  scheduler interference, GC and a busy machine can only ever *add* time, never remove it. The
     *  fastest observed run is therefore the best available estimate of what the work actually
     *  costs, while the median still carries whatever interference was present in more than half
     *  the runs.
     *
     *  This was not a free choice. Measured with the median, the clinic's element-only arm produced
     *  29.45, 24.06, 17.15, 17.83 ms across a sweep whose true times rise — a **negative** slope,
     *  which is not a small per-epoch cost but an unusable measurement. The cause was residual JIT
     *  warming in whichever arm ran first, and the fix is both this estimator and the wider sweep.
     */
    private fun measure(build: () -> Rig): Run {
        repeat(2) { build().run() }                     // warm-up, discarded
        val runs = (1..REPEATS).map { build().run() }
        return runs.minBy { it.nanos }
    }

    /**
     *  Least squares on (epochs, nanos): nanoseconds per epoch, the zero-epoch intercept, and the
     *  **standard error of the slope**.
     *
     *  The standard error is not decoration. Capture turned out to cost so little that the
     *  difference between the capture arm's slope and the element arm's came out at +73, +33, +31
     *  and −26 ns/epoch on four runs of this benchmark — a quantity whose sign is not even stable.
     *  Reporting −26 ns as though it were a measurement would be worse than reporting nothing;
     *  reporting it against an error bar says the true figure is small and this method cannot
     *  resolve it, which is the actual finding.
     */
    private class Fit(val slope: Double, val intercept: Double, val slopeError: Double)

    private fun fit(points: List<Pair<Int, Long>>): Fit {
        val n = points.size
        val mx = points.sumOf { it.first.toDouble() } / n
        val my = points.sumOf { it.second.toDouble() } / n
        val sxy = points.sumOf { (it.first - mx) * (it.second - my) }
        val sxx = points.sumOf { (it.first - mx) * (it.first - mx) }
        val slope = sxy / sxx
        val intercept = my - slope * mx
        val sse = points.sumOf { p: Pair<Int, Long> ->
            val e = p.second - (intercept + slope * p.first)
            e * e
        }
        val se = if (n > 2) Math.sqrt(sse / (n - 2) / sxx) else Double.NaN
        return Fit(slope, intercept, se)
    }

    // ------------------------------------------------------------------ the measurement

    @Test
    fun theCostOfADecisionEpochIsMeasuredOnBothReferenceModels() {
        println()
        println("=".repeat(96))
        println("G11 / §16.4 — per-epoch cost of a decision element")
        println(hardware())
        println("=".repeat(96))

        // A global warm-up before anything is timed. Per-point warm-up is not enough: the very
        // first model built in a JVM pays class loading and interpretation for the whole KSL stack,
        // and measured that way the first point came in at 141 ms against 15 ms for its neighbours
        // — a tenfold artefact that the regression would have read as a huge per-epoch cost.
        for (subject in subjects()) {
            for (arm in Arm.entries) repeat(3) { subject.rig(arm, subject.intervals.last()).run() }
        }

        val results = mutableMapOf<Pair<String, Arm>, Fit>()
        val candidatesPerEpoch = mutableMapOf<String, Double>()
        val testedPerEpoch = mutableMapOf<String, Double>()

        for (subject in subjects()) {
            println()
            println(subject.name)
            println("  %-14s %9s %12s %14s %12s %10s".format(
                "arm", "epochs", "events", "fastest (ms)", "ns/epoch", "captured"))

            for (arm in Arm.entries) {
                val points = mutableListOf<Pair<Int, Long>>()
                val backgroundEvents = mutableSetOf<Long>()
                var lastScored = 0L
                var lastTested = 0L
                var lastCaptured = 0
                for (interval in subject.intervals) {
                    val r = measure { subject.rig(arm, interval) }
                    points += r.epochs to r.nanos
                    // Every epoch IS a scheduled event, so the raw count necessarily rises with
                    // the sweep. What must hold still is everything else — the model's own events.
                    // (Measured: the clinic's raw counts were 25769/25859/26039/26399 at 90/180/
                    // 360/720 epochs, which is one constant 25679 plus the epochs, exactly.)
                    backgroundEvents += r.events - r.epochs
                    lastScored = r.scored
                    lastTested = r.tested
                    lastCaptured = r.captured
                    println("  %-14s %9d %12d %14.2f %12s %10d".format(
                        if (interval == subject.intervals.first()) arm.label else "",
                        r.epochs, r.events, r.nanos / 1e6, "", r.captured))

                    assertTrue(r.epochs > 0, "${subject.name}/$arm at interval $interval took no decisions")
                    assertTrue(r.events > 0, "${subject.name}/$arm at interval $interval fired no events")
                }

                val f = fit(points)
                val slope = f.slope
                results[subject.name to arm] = f

                // A negative per-epoch cost is not a fast element; it is a failed measurement, and
                // saying so is the difference between reporting a number and reporting a number
                // that means something. G11 asks that the cost be KNOWN.
                assertTrue(slope > 0.0,
                    "${subject.name}/$arm fitted a per-epoch cost of %.0f ns. Time cannot fall as ".format(slope) +
                        "epochs are added, so this fit is dominated by noise and no figure derived " +
                        "from it is usable. Points (epochs, ns): $points")
                println("  %-14s %9s %12s %14s %10.0f ± %.0f".format("", "", "", "→ fit", slope, f.slopeError))

                // The regression only means what it says if the sweep changed the epoch count and
                // nothing else. An event count that moved would mean the trajectory moved with it.
                assertTrue(backgroundEvents.size == 1,
                    "${subject.name}/$arm produced $backgroundEvents distinct NON-epoch event " +
                        "counts across the interval sweep, so the trajectory moved with the epoch " +
                        "interval. The regression attributes all of the time difference to epochs, " +
                        "so a moving trajectory would be folded into the per-epoch cost and the " +
                        "number below would be wrong")

                if (arm == Arm.CAPTURE) assertTrue(lastCaptured > 0,
                    "the capture arm recorded nothing, so it is measuring the same thing as the " +
                        "neutral arm and the capture cost below is noise")
                if (arm == Arm.SCORING) {
                    candidatesPerEpoch[subject.name] = lastScored.toDouble() / points.last().first
                    testedPerEpoch[subject.name] = lastTested.toDouble() / points.last().first
                }
                if (arm == Arm.SCORING) assertTrue(lastScored > points.last().first,
                    "the scoring arm scored $lastScored candidates over ${points.last().first} " +
                        "epochs — it is not enumerating a feasible set, so it is not measuring a search")
            }
        }

        // ------------------------------------------------------------------ the decomposition
        println()
        println("=".repeat(96))
        println("DECOMPOSITION — nanoseconds per epoch, and what that is in units of one event")
        println("=".repeat(96))
        println("  %-34s %11s %11s %11s %11s %10s %10s".format(
            "model", "element", "capture", "search", "1 event", "feasible", "tested"))

        for (subject in subjects()) {
            val neutralFit = results.getValue(subject.name to Arm.NEUTRAL)
            val captureFit = results.getValue(subject.name to Arm.CAPTURE)
            val searchFit = results.getValue(subject.name to Arm.SCORING)
            val elementNs = neutralFit.slope
            val intercept = neutralFit.intercept
            val captureNs = captureFit.slope - elementNs
            val searchNs = searchFit.slope - elementNs
            // Difference of two fitted slopes, so the errors add in quadrature.
            val captureErr = Math.sqrt(
                captureFit.slopeError * captureFit.slopeError + neutralFit.slopeError * neutralFit.slopeError)
            val base = measure { subject.rig(Arm.NEUTRAL, subject.intervals.first()) }
            val perEvent = intercept / (base.events - base.epochs)

            val feasible = candidatesPerEpoch.getValue(subject.name)
            val tested = testedPerEpoch.getValue(subject.name)
            // Two standard errors, not one. Capture's cost is a difference of two independently
            // fitted slopes that are nearly equal, so it fluctuates in sign from run to run; a
            // one-sigma test reports a confident-looking "-47 ns" often enough to mislead. Inside
            // two sigma the only defensible statement is that it is below what this method can
            // resolve, and that is what gets printed.
            val resolvable = Math.abs(captureNs) >= 2.0 * captureErr
            val captureText =
                if (!resolvable) "<%.0f".format(2.0 * captureErr) else "%.0f".format(captureNs)
            println("  %-34s %7.0f±%-3.0f %11s %11.0f %11.1f %10.0f %10.0f".format(
                subject.name, elementNs, neutralFit.slopeError, captureText, searchNs,
                perEvent, feasible, tested))
            println("  %-34s %10.1fx %11s %10.1fx %11s %10s %8.0fns".format(
                "   in events; search per tested action",
                elementNs / perEvent,
                if (!resolvable) "~0" else "%.1fx".format(captureNs / perEvent),
                searchNs / perEvent, "", "", searchNs / tested))

            assertTrue(perEvent > 0.0,
                "the zero-epoch intercept came out non-positive, so the fit is not usable and no " +
                    "ratio below it means anything")

            // The one smoke bound. It is three orders of magnitude above anything observed and
            // exists to catch a regression that makes an epoch catastrophically expensive — not to
            // encode a target, which G11 explicitly does not ask for.
            assertTrue(elementNs < 1_000_000.0,
                "an epoch cost ${elementNs / 1000} microseconds on ${subject.name}. That is not a " +
                    "slow machine, it is a defect: the element does a bounded amount of work per " +
                    "epoch and cannot legitimately approach a millisecond")
        }
        println()
    }

    private fun hardware(): String {
        val rt = Runtime.getRuntime()
        return "  %s %s | %d processors | JVM %s | max heap %d MB | %d timed runs per point, fastest taken".format(
            System.getProperty("os.name"), System.getProperty("os.arch"),
            rt.availableProcessors(), System.getProperty("java.version"),
            rt.maxMemory() / (1024 * 1024), REPEATS)
    }

    private companion object {
        const val REPEATS = 9
        const val CLINIC_LENGTH = 43_200.0
        const val INVENTORY_LENGTH = 5_000.0
    }
}
