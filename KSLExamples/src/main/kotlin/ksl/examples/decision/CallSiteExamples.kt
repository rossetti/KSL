package ksl.examples.decision

import ksl.modeling.decision.DecisionContext
import ksl.modeling.decision.DecisionElement
import ksl.modeling.decision.Neutral
import ksl.modeling.decision.PolicyIfc
import ksl.modeling.decision.decisionElement
import ksl.modeling.decision.descriptor.RewardSense
import ksl.modeling.variable.Counter
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Interval

/**
 *  **Where you call `decide` is part of your model, and getting it wrong is quiet.**
 *
 *  The element does not schedule its own decisions any more: you call it at the point a decision is
 *  due. That is the responsibility you already carried before this package existed — without it you
 *  would write the decision inline at that same point — but it now has a name, because the state the
 *  rule sees is also the state written into the trajectory.
 *
 *  These are the four ways a call site goes wrong, as running models rather than as advice. Each is
 *  deliberately deterministic: one demand, exact numbers, so the cost of each mistake is a figure
 *  you can read rather than a tendency you have to believe. `CallSiteExamplesTest` asserts every
 *  one of them, including that the wrong ones are wrong — an example that only shows the correct
 *  call site teaches nothing about why the position matters.
 *
 *  Run `main` to see all four side by side.
 */

/** Where in the demand handler the decision is taken. */
enum class CallSite {
    /** After on-hand is decremented but **before** the position is recomputed. A torn read. */
    TORN,

    /** **Before** the demand is applied at all. Consistent, and about the wrong state. */
    EARLY,

    /** After the whole update. The rule this file exists to state. */
    CORRECT
}

/**
 *  A single-item stockroom under an (s, S) rule, reviewed when demand arrives.
 *
 *  On-hand starts at 10 and one demand for 12 arrives at t = 5. The reorder point is 5, so after
 *  that demand the position is -2 and an order is due. Whether the model notices depends entirely
 *  on where in the handler [callSite] puts the call.
 */
class StockRoom(
    parent: ModelElement,
    private val callSite: CallSite,
    name: String? = null
) : ModelElement(parent, name) {

    val onHand = TWResponse(this, name = "${this.name}:OnHand", initialValue = 10.0)
    val backorders = TWResponse(this, name = "${this.name}:Backorders")

    /**
     *  On hand, less what is owed, plus what is coming.
     *
     *  Held as a variable the model **recomputes**, rather than derived on every read, precisely so
     *  that a torn read is possible. That is not a contrivance: a model that maintains a position
     *  incrementally is the ordinary case, and a derived-on-read position would hide the mistake
     *  this file is about rather than prevent it.
     */
    val position = TWResponse(
        this, name = "${this.name}:Position", initialValue = 10.0,
        // A position goes negative the moment demand outruns stock, and the default domain for a
        // TWResponse is non-negative. Saying so here rather than discovering it mid-run.
        allowedDomain = Interval(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
    )

    val ordersPlaced = Counter(this, name = "${this.name}:Orders")

    private var onOrder = 0.0
    private val leadTime = 3.0

    fun placeOrder(quantity: Double) {
        if (quantity <= 0.0) return
        onOrder += quantity
        ordersPlaced.increment()
        schedule(this::orderArrives, leadTime, message = quantity)
    }

    private fun orderArrives(event: KSLEvent<Double>) {
        val q = event.message!!
        onOrder -= q
        val owed = minOf(backorders.value, q)
        if (owed > 0.0) backorders.decrement(owed)
        if (q - owed > 0.0) onHand.increment(q - owed)
        recomputePosition()
    }

    private fun recomputePosition() {
        position.value = onHand.value - backorders.value + onOrder
    }

    val review: DecisionElement = decisionElement("${this.name}:Review") {
        observe(position)
        // Observed as well, so a reader of the trajectory can SEE the tear: under TORN the row
        // says position 10 with on-hand 0, which is a pair no observer of the finished system
        // would ever see. Under EARLY it says position 10 with on-hand 10 -- consistent, and
        // about the wrong moment.
        observe(onHand)
        lever(this@StockRoom, 0.0..100.0, neutral = Neutral.Value(0.0), alias = "OrderQty") { q ->
            placeOrder(q)
        }
        reward(backorders, rate = 5.0, sense = RewardSense.COST, alias = "Shortage")
        policy = OrderUpTo(reorderPoint = 5.0, orderUpTo = 20.0)
    }

    /**
     *  The handler, and the whole point of this file. The three arms differ **only** in where the
     *  call sits.
     */
    private fun demandArrives(event: KSLEvent<Nothing>) {
        val q = 12.0

        if (callSite == CallSite.EARLY) {
            // WRONG, but not torn. Every value is mutually consistent; it is simply the state
            // BEFORE the demand. The library cannot tell this from a deliberate choice, because
            // deciding on pre-event state is sometimes exactly right -- an admission-control rule
            // is *about* the arriving entity and must decide before admitting it.
            review.decide("demand arrival")
        }

        val filled = minOf(q, onHand.value)
        onHand.decrement(filled)

        if (callSite == CallSite.TORN) {
            // WRONG. On-hand reflects the demand and `position` does not, because the line that
            // recomputes it has not run. The rule reads the pre-demand position, sees it above the
            // reorder point, and declines to order -- on every crossing, silently.
            review.decide("demand arrival")
        }

        val unmet = q - filled
        if (unmet > 0.0) backorders.increment(unmet)
        recomputePosition()

        if (callSite == CallSite.CORRECT) {
            // RIGHT. The update is finished, so the state the rule reads is the state an observer
            // of the finished system would see -- and it is the state written into the trajectory.
            review.decide("demand arrival")
        }
    }

    override fun initialize() {
        super.initialize()
        onOrder = 0.0
        schedule(this::demandArrives, 5.0)
    }
}

/** The (s, S) rule: if the position has fallen to the reorder point, order up to [orderUpTo]. */
class OrderUpTo(
    private val reorderPoint: Double,
    private val orderUpTo: Double
) : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val position = observation[0]
        return doubleArrayOf(if (position <= reorderPoint) orderUpTo - position else 0.0)
    }
}

/**
 *  A lever whose write function reaches back into `decide`, which is refused.
 *
 *  The nested decision would be applied to the model and never recorded, and the trajectory's epoch
 *  indices would stop matching the decisions that happened -- a silent defect rather than a crash,
 *  which is why it is refused rather than tolerated. [deferInstead] shows the repair:
 *  `requestDecision` only schedules, so it cannot re-enter.
 */
class ReEntrantRoom(
    parent: ModelElement,
    private val deferInstead: Boolean,
    name: String? = null
) : ModelElement(parent, name) {

    val level = TWResponse(this, name = "${this.name}:Level", initialValue = 5.0)
    val decisions = Counter(this, name = "${this.name}:Decisions")

    private var followUpsRequested = 0

    val review: DecisionElement = decisionElement("${this.name}:Review") {
        observe(level)
        lever(this@ReEntrantRoom, 0.0..10.0, neutral = Neutral.Value(0.0), alias = "Set") { v ->
            level.value = v
            if (deferInstead) {
                // `requestDecision` is re-entrancy-safe -- it only schedules -- but it is NOT
                // termination-safe. A write that always asks for another decision asks forever, at
                // the same instant, because a zero-delay event lands at the current time. The guard
                // is the caller's to write, and there has to be one.
                if (followUpsRequested < 1) {
                    followUpsRequested++
                    review.requestDecision("after the write")
                }
            } else {
                review.decide("after the write")          // refused: we are inside decide()
            }
        }
        policy = PolicyIfc { _, _ -> decisions.increment(); doubleArrayOf(1.0) }
    }

    private fun tick(event: KSLEvent<Nothing>) = review.decide("tick")

    override fun initialize() {
        super.initialize()
        schedule(this::tick, 5.0)
    }
}

/**
 *  The right action, against a consistent state, with the reward on the wrong row.
 *
 *  The reward baseline is taken when `decide` is called. A counter incremented *after* the call
 *  falls into the next interval, so the transition recorded for **this** decision is credited with a
 *  cost caused by something that happened before it. Nothing in the reported statistics changes --
 *  the totals are identical -- and it shows up only as a learner that fits something slightly wrong.
 *
 *  Which accrual kinds are sensitive to this is **not uniform**: a `TIME_INTEGRAL` term is not,
 *  because accumulated area up to an instant is the same whichever side of the call the level
 *  changed on. `OBSERVATION_SUM` and `COUNTER_TOTAL` are.
 */
class ShortfallRoom(
    parent: ModelElement,
    private val countBeforeDeciding: Boolean,
    name: String? = null
) : ModelElement(parent, name) {

    val level = TWResponse(this, name = "${this.name}:Level", initialValue = 1.0)
    val shortfalls = Counter(this, name = "${this.name}:Shortfalls")

    val review: DecisionElement = decisionElement("${this.name}:Review") {
        observe(level)
        lever(this@ShortfallRoom, 0.0..10.0, neutral = Neutral.Value(0.0), alias = "Fix") { }
        reward(shortfalls, rate = 25.0, sense = RewardSense.COST, alias = "Shortfall")
        policy = PolicyIfc { _, _ -> doubleArrayOf(0.0) }
    }

    private fun event(event: KSLEvent<Nothing>) {
        if (countBeforeDeciding) {
            shortfalls.increment()                 // counted into the interval now closing
            review.decide("shortfall")
        } else {
            review.decide("shortfall")             // baseline taken first...
            shortfalls.increment()                 // ...so this lands in the NEXT interval
        }
    }

    override fun initialize() {
        super.initialize()
        schedule(this::event, 5.0)
        schedule(this::event, 10.0)
    }
}

fun main() {
    println("Where the call sits, and what each choice costs")
    println("on hand 10, one demand for 12 at t=5, reorder point 5, order up to 20")
    println()
    for (site in CallSite.entries) {
        val model = Model("CallSite-$site")
        val room = StockRoom(model, site, name = "Room")
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        println("  %-8s orders placed = %.0f   ending position = %.1f   backorders = %.1f"
            .format(site, room.ordersPlaced.value, room.position.value, room.backorders.value))
    }
}
