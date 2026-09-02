package ksl.examples.decision

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.decision.DecisionElement
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 *  Callers that drive a [DecisionElement] on a schedule.
 *
 *  **These live outside KSLCore on purpose** (plan D4). The element no longer owns its epoch timing:
 *  a modeler decides when a decision is due and calls `decide(reason)`. The commonest thing they will
 *  want is a periodic review, and something has to provide it — but if that something lived in
 *  KSLCore, the library would still contain a timing concept and the claim that it does not would be
 *  a matter of discipline rather than of fact. Here it is a fact, and `NoTimingConceptTest` checks it.
 *
 *  A twin of this file lives in KSLTestModels for the KSLCore suites. KSLExamples must not depend on
 *  KSLTestModels -- the teaching surface and the test fixtures are kept apart on purpose -- so the
 *  duplication is deliberate and copies flow from here to there, never the other way.
 *
 *  Both drivers are five lines of ordinary KSL around a call. That is the point: a modeler who wants
 *  something these do not do writes their own, in their own model, with no library concept to learn
 *  or to fight. A permanent entity whose process is `while (model.isRunning) { delay(p); decide() }`
 *  does the same job in the process view.
 */

/**
 *  Calls [element] every [interval] time units.
 *
 *  Replaces the `every(interval)` declaration the element used to carry, and reproduces it exactly:
 *  the first review is at [interval] unless [firstAtTimeZero], and reviews continue for the length of
 *  the replication. [priority] is the priority of the *review* event, which is what now decides the
 *  order against other events at a coinciding instant -- the element's own `epochPriority` still
 *  orders its deferred epochs, but a review taken through `decide` is taken inside this event.
 */
class PeriodicReview @JvmOverloads constructor(
    parent: ModelElement,
    interval: Double,
    private val firstAtTimeZero: Boolean = false,
    private val reason: String = "periodic",
    private val priority: Int = KSLEvent.MEDIUM_LOW_PRIORITY,
    name: String? = null
) : ModelElement(parent, name ?: "PeriodicReview") {

    private var myInterval: Double = interval

    /**
     *  How often this caller asks for a decision.
     *
     *  **A `@KSLControl`, so `simopt` can search it through the path it already uses.** A review
     *  period is an ordinary decision variable, and it used to be one on the decision element. With
     *  timing owned by the caller it is a parameter of the caller instead — which is where it
     *  belongs, and which `simopt` reaches identically.
     *
     *  The declared bounds are the exact domain of the setter (R16): a numeric control clamps, so a
     *  bound outside the domain would deliver a value the setter refuses. `Double.MIN_VALUE` and
     *  `Double.MAX_VALUE` are precisely the smallest and largest values satisfying "finite and
     *  `> 0.0`".
     *
     *  Replication-initial, like every other decision parameter.
     */
    @set:KSLControl(
        controlType = ControlType.DOUBLE,
        lowerBound = Double.MIN_VALUE,
        upperBound = Double.MAX_VALUE
    )
    var interval: Double
        get() = myInterval
        set(value) {
            check(model.isNotRunning) {
                "Attempted to set 'interval' on ${this.name} while the simulation was running. " +
                    "A review period is replication-initial."
            }
            require(value.isFinite() && value > 0.0) {
                "The review interval must be finite and > 0.0, but $value was assigned."
            }
            myInterval = value
        }

    init {
        require(interval.isFinite() && interval > 0.0) {
            "The review interval must be finite and > 0.0, but $interval was given."
        }
    }

    /** Assigned after the element is built, because the element is declared on some other parent. */
    lateinit var element: DecisionElement

    private inner class Review : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            element.decide(reason)
            schedule(myInterval, priority = priority)
        }
    }

    private val review = Review()

    override fun initialize() {
        review.schedule(if (firstAtTimeZero) 0.0 else myInterval, priority = priority)
    }
}

/**
 *  Calls [element] at each of [times], in order.
 *
 *  Replaces `onCalendar(times)`. The declaration used to reject duplicate instants at construction,
 *  because two epochs at one instant bound a zero-length interval and the second decision would be
 *  taken and never recorded. That refusal is kept here rather than lost: the same hazard exists, it
 *  is still checkable at construction, and this is where the calendar now lives.
 */
class CalendarReview @JvmOverloads constructor(
    parent: ModelElement,
    times: List<Double>,
    private val reason: String = "calendar",
    private val priority: Int = KSLEvent.MEDIUM_LOW_PRIORITY,
    name: String? = null
) : ModelElement(parent, name ?: "CalendarReview") {

    private val times: List<Double>

    init {
        require(times.isNotEmpty()) { "A calendar review requires at least one time." }
        val bad = times.filter { !it.isFinite() || it < 0.0 }
        require(bad.isEmpty()) { "Review times must be finite and non-negative; got $bad." }
        val dupes = times.groupBy { it }.filterValues { it.size > 1 }.keys
        require(dupes.isEmpty()) {
            "Review times must be distinct; these repeat: $dupes. Two reviews at one instant bound " +
                "a zero-length interval, which is discarded, so the earlier decision would be taken " +
                "and never recorded."
        }
        this.times = times.sorted()
    }

    lateinit var element: DecisionElement

    private inner class Review : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) = element.decide(reason)
    }

    private val review = Review()

    override fun initialize() {
        for (t in times) review.schedule(t, priority = priority)
    }
}

/**
 *  Attach a periodic caller to this element, and return the element.
 *
 *  This is what `every(interval)` becomes. It reads as one line at the end of a declaration, which is
 *  deliberate: the migration away from element-owned timing should be a substitution, not five
 *  hand-written lines per site, because twenty-odd call sites of hand-written boilerplate is where a
 *  copy-paste error hides.
 *
 *  [parent] is explicit because `ModelElement.parent` is `internal` to KSLCore and these drivers live
 *  outside it (D4) -- which is the point, and worth the extra argument.
 */
fun DecisionElement.reviewEvery(
    parent: ModelElement,
    interval: Double,
    firstAtTimeZero: Boolean = false,
    reason: String = "periodic",
    priority: Int = KSLEvent.MEDIUM_LOW_PRIORITY
): DecisionElement {
    PeriodicReview(parent, interval, firstAtTimeZero, reason, priority, "${this.name}:Reviewer")
        .element = this
    return this
}

/** Attach a calendar caller to this element, and return the element. Replaces `onCalendar(times)`. */
fun DecisionElement.reviewOn(
    parent: ModelElement,
    times: List<Double>,
    reason: String = "calendar",
    priority: Int = KSLEvent.MEDIUM_LOW_PRIORITY
): DecisionElement {
    CalendarReview(parent, times, reason, priority, "${this.name}:Reviewer").element = this
    return this
}
