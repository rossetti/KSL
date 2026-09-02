package ksl.modeling.decision.capture

import ksl.modeling.decision.DecisionElement
import ksl.modeling.decision.RollingSink
import ksl.modeling.decision.TransitionSink
import ksl.simulation.Model
import java.io.Closeable
import java.nio.file.Path

/**
 *  Every [DecisionElement] in this model, in construction order (§4.8.2).
 *
 *  The counterpart of `ksl.animation.animatableModelElements()`, and written the same way over the
 *  same source, because the problem is the same: a layer added after the model was built has to be
 *  able to *find* what it attaches to. `Model.getModelElements()` is `internal`, so this lives in
 *  KSLCore — as animation's does.
 */
fun Model.decisionElements(): List<DecisionElement> =
    getModelElements().filterIsInstance<DecisionElement>()

/**
 *  Attaches transition capture to a model that is **already built**, and takes it back off again
 *  (§4.8.2).
 *
 *  ### The problem it solves
 *
 *  Capture used to be declarable only inside `decisionElement { … }`, which meant that recording a
 *  model's decisions required editing the model. That is the wrong place for the decision: whether
 *  this run is being recorded is a property of *the run*, not of the subsystem being simulated. A
 *  user with somebody else's model, a script sweeping ten configurations of which two are worth
 *  recording, or a tool layer offering a "record decisions" checkbox all need to attach from
 *  outside — and none of them can edit the model element.
 *
 *  ### The shape is the animation layer's
 *
 *  `ksl.animation.AnimationCapture` is the house pattern for exactly this: construct it just before
 *  a run, its `init` installs everything and records an undo action per installation, and [close]
 *  runs every undo. This is the same object for decisions, and deliberately smaller — animation has
 *  to compose filter sinks and walk every element kind in the model; this has one kind to find and
 *  one thing to attach.
 *
 *  ```kotlin
 *  val model = Model("Inventory")
 *  val room = StockRoom(model, "Room")            // a model that never mentions capture
 *  model.numberOfReplications = 30
 *
 *  DecisionCapture.toDirectory(model, outDir).use { model.simulate() }
 *  ```
 *
 *  ### What it does not do
 *
 *  It does not close the sinks it attached — unless it made them itself, which is what
 *  [toDirectory] and [rolling] do. A sink handed to the [DecisionCapture] constructor by the caller
 *  is the caller's, following `ksl.observers.ResponseTrace`; [close] detaches it and stops there.
 *
 *  It cannot be constructed or closed while the model is running. `attachTransitionSink` refuses
 *  that, and this does nothing to route around it: a trajectory that begins mid-episode has no
 *  predecessor for its first row.
 *
 *  @param model the model whose decision elements are to be captured
 *  @param sinkFor chooses a sink for each decision element; return `null` to skip that element,
 *  which is how a caller records two of a model's five decision elements
 */
class DecisionCapture private constructor(
    private val model: Model,
    sinkFor: (DecisionElement) -> TransitionSink?,
    /**
     *  Whether [close] also closes the sinks. False for the public constructor — the caller made
     *  them, so the caller closes them — and true for [rolling], which made them itself. It is not
     *  a public parameter: as a defaulted third argument it would swallow the trailing lambda in
     *  `DecisionCapture(model) { … }`, which is the call this class exists to be.
     */
    private val closeSinksOnClose: Boolean
) : Closeable {

    /**
     *  Capture [model]'s decision elements into sinks chosen by [sinkFor], which the caller owns.
     */
    constructor(model: Model, sinkFor: (DecisionElement) -> TransitionSink?) :
        this(model, sinkFor, closeSinksOnClose = false)

    private val attached = mutableListOf<Pair<DecisionElement, TransitionSink>>()

    /** The elements this capture attached to, in the order it found them. */
    val capturedElements: List<DecisionElement>
        get() = attached.map { it.first }

    /** The sinks it attached, aligned with [capturedElements]. */
    val sinks: List<TransitionSink>
        get() = attached.map { it.second }

    init {
        val elements = model.decisionElements()
        check(elements.isNotEmpty()) {
            "Model '${model.name}' has no decision elements, so there is nothing to capture. " +
                "A capture that silently recorded nothing would look like a model that made no " +
                "decisions."
        }
        // Attach one at a time and unwind on failure, so a selector that throws on the fourth
        // element does not leave three attached with no handle on them.
        try {
            for (e in elements) {
                val s = sinkFor(e) ?: continue
                e.attachTransitionSink(s)
                attached.add(e to s)
            }
        } catch (t: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let { t.addSuppressed(it) }
            throw t
        }
        check(attached.isNotEmpty()) {
            "The selector returned null for all ${elements.size} decision elements of " +
                "'${model.name}', so nothing was attached and this run will record nothing."
        }
    }

    /**
     *  Detaches every sink this capture attached, and closes them if it made them. Idempotent: a
     *  second call is a no-op, so `use { }` around a block that also closes is safe.
     */
    override fun close() {
        val failures = mutableListOf<Throwable>()
        for ((element, sink) in attached) {
            runCatching { element.detachTransitionSink(sink) }.exceptionOrNull()?.let { failures += it }
            if (closeSinksOnClose) {
                runCatching { sink.close() }.exceptionOrNull()?.let { failures += it }
            }
        }
        attached.clear()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    companion object {

        /**
         *  Capture every decision element of [model] to a durable trajectory under [directory] —
         *  the common case, so a caller needs only a model and a destination.
         *
         *  One [TabularSink] per element per experiment, named `<element>-<experiment>`, because
         *  both vary: a model may hold several decision elements, and §4.9's k-rule comparison runs
         *  one model k times.
         *
         *  **The file name is sanitised, not just the columns.** KSL element names are
         *  colon-qualified by convention (`Shop:Review`), and a `:` in a file name is legal on
         *  Linux, illegal on Windows, and an alternate-data-stream separator on NTFS — so a
         *  trajectory written on the author's machine would fail to appear on a reader's. This is
         *  the same substitution `ksl.observers.ResponseTrace` makes for the same reason, reusing
         *  [TabularSink.sanitize] so a file name and its columns cannot diverge on what a legal
         *  character is.
         *
         *  Mirrors `ksl.animation.AnimationCapture.toFile`.
         */
        @JvmStatic
        fun toDirectory(model: Model, directory: Path): DecisionCapture = rolling(model) { p ->
            val stem = TabularSink.sanitize(p.elementName) + "-" + TabularSink.sanitize(p.experimentName)
            TabularSink(p, directory.resolve(stem))
        }

        /**
         *  Capture every decision element with a sink built fresh for each experiment.
         *
         *  The general form of [toDirectory]: the [factory] is handed the provenance at the start
         *  of every run, so it can name an artifact after the experiment or the policy under test.
         *  The wrapping [RollingSink]s are made here, so this capture closes them.
         */
        @JvmStatic
        fun rolling(
            model: Model,
            factory: (ksl.modeling.decision.RunProvenance) -> TransitionSink
        ): DecisionCapture = DecisionCapture(model, { RollingSink(factory) }, closeSinksOnClose = true)
    }
}
