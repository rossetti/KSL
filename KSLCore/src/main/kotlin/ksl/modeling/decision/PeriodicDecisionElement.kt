package ksl.modeling.decision

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 *  A decision element reviewed on a fixed period, declared and scheduled in one construction.
 *
 *  A [DecisionElement] does not own its timing: a caller decides when a decision is due and calls
 *  `decide`. That is what makes a decision at a stockout, or at a point inside a process, expressible
 *  at all. It is also more than a modeler wants to think about when all they want is a review every
 *  thirty days — so this is that, packaged, and it is the shape most models should use.
 *
 *  ```kotlin
 *  val review = PeriodicDecisionElement(this, interval = 30.0, name = "Review") {
 *      observe(position)
 *      lever(this@StockRoom, 0..200, neutral = Neutral.Value(0.0)) { q -> placeOrder(q) }
 *      reward(onHand, rate = 0.5, sense = RewardSense.COST)
 *      policy = OrderUpTo(s = 20.0, bigS = 60.0)
 *  }
 *  ```
 *
 *  **It is a composition, not a special case.** It holds an ordinary element and calls its ordinary
 *  public entry point from an ordinary event. Nothing here is reachable only from inside the library:
 *  a modeler who needs something this does not do writes their own, and `PeriodicReview` in the
 *  examples is the same thing with the declaration left to the caller.
 *
 *  ### What being a composition buys back
 *
 *  **The construction guarantee.** The DSL used to refuse an element that declared no timing, on the
 *  ground that it would be built, never decide, and report nothing. With timing owned by the caller
 *  that refusal had nothing to check — an element that declares none is now the ordinary shape. Here
 *  it comes back exactly: [interval] is a constructor argument, validated at construction, and an
 *  element built this way always has a caller. The silent no-decisions failure is unreachable.
 *
 *  **R2b, by construction.** The caller of `decide` warrants that the state it can observe is
 *  consistent, because the model is not guaranteed to be between events wherever a modeler chooses to
 *  call. This review runs inside an event of its own and does not change anything itself, so there is
 *  no half-finished update for it to be inside of. The caveat is the one the old scheduled epoch had
 *  too: other events at the same instant may not have run yet, so the state is consistent rather than
 *  final.
 *
 *  **No runaway.** A review that only fires on a timer cannot ask for a decision during a decision,
 *  which is the fault `RunawayDecisionRequestException` exists to diagnose.
 *
 *  @param interval time between reviews; finite and greater than zero
 *  @param firstAtTimeZero review at time zero as well as at each interval thereafter
 *  @param reason the label carried onto every transition this review produces
 *  @param reviewPriority the priority of the review *event*, which is what orders this review against
 *   other events at a coinciding instant — including this element's own warm-up
 *  @param elementName what to call the element, if not `"<this element's name>:Decision"`. It exists
 *   for conversion: a model that already had a decision element should not have to rename it — and so
 *   invalidate stored trajectories, control keys and anything holding a `LeverRef` — merely because the
 *   review moved into a composition around it.
 *  @param declaration the element's declaration, exactly as `decisionElement { }` takes it
 */
class PeriodicDecisionElement @JvmOverloads constructor(
    parent: ModelElement,
    interval: Double,
    name: String? = null,
    private val firstAtTimeZero: Boolean = false,
    private val reason: String = "periodic",
    private val reviewPriority: Int = KSLEvent.MEDIUM_LOW_PRIORITY,
    elementName: String? = null,
    declaration: DecisionElementBuilder.() -> Unit
) : ModelElement(parent, name) {

    private var myInterval: Double = interval

    init {
        // The refusal `build()` used to give, restored to the place that can now give it. An interval
        // of zero schedules reviews forever at one instant; a non-finite one means "never review",
        // which is indistinguishable from declaring nothing and is what this class exists to prevent.
        require(interval.isFinite() && interval > 0.0) {
            "The review interval must be finite and > 0.0, but $interval was given."
        }
    }

    /**
     *  How often this element is reviewed.
     *
     *  **A `@KSLControl`, so `simopt` searches it through the path it already uses.** A review period
     *  is an ordinary decision variable and always was; what changed is that it belongs to whatever
     *  schedules the reviews rather than to the element. Because that is this class, a study finds it
     *  on a named type rather than by knowing which model element happens to do the scheduling.
     *
     *  Bounds are the exact domain of the setter (R16): a numeric control clamps, so a bound outside
     *  the domain would deliver a value the setter refuses, and `Double.MIN_VALUE` / `Double.MAX_VALUE`
     *  are precisely the smallest and largest values that are finite and greater than zero.
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

    /**
     *  The element this reviews.
     *
     *  Exposed because everything a decision element offers — assigning a rule, narrowing a lever,
     *  attaching a sink, reading the estimand — is done on it, and none of that is this class's
     *  business to re-export.
     */
    val element: DecisionElement = decisionElement(elementName ?: "${this.name}:Decision", declaration)

    private val myReviewAction = ReviewAction()

    override fun initialize() {
        super.initialize()
        schedule(myReviewAction, if (firstAtTimeZero) 0.0 else myInterval, priority = reviewPriority)
    }

    private inner class ReviewAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            element.decide(reason)
            schedule(myReviewAction, myInterval, priority = reviewPriority)
        }
    }
}
