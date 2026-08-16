package ksl.examples.decision.tutorial

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.decision.DecisionContext
import ksl.modeling.decision.PolicyIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement
import ksl.utilities.Interval

/*
 * Tutorial Part VI -- shared setup: handing a rule's parameters to `ksl.simopt`.
 *
 * The two tutorials meet here. `ksl-decision` makes the decision point explicit and lets
 * you swap rules; it does NOT choose the rule, and it does not search over a rule's
 * parameters. Searching over parameters to minimise an expected cost estimated by
 * simulation IS simulation optimisation, and KSL already has that. So a parameterised
 * rule's parameters are simply decision variables of a simopt problem, and nothing in
 * `ksl.modeling.decision` is involved in the search.
 *
 * There is no adapter in this file. That is the point of it.
 */

/**
 *  The `(s, S)` rule of Part II, rewritten as a **`ModelElement` carrying `@KSLControl`
 *  properties** so that `simopt` can drive its parameters.
 *
 *  Three things changed from Part II's `OrderUpTo`, and each is required:
 *
 *  1. **It is a `ModelElement`.** `Controls` reflects over the model's elements, so a rule
 *     that is a plain object is invisible to the control walk. Being an element also gets
 *     the parameters into the standard reporting and into a model configuration.
 *  2. **The parameters are `var`s with `@set:KSLControl`.** The control key is
 *     `elementName.propertyName`, so naming this element `OrderRule` gives `OrderRule.s`
 *     and `OrderRule.sDelta`.
 *  3. **It is parameterised as a BOX.** This is the part that is easy to get wrong and is
 *     explained below.
 *
 *  ### Why `(s, sDelta)` and not `(s, S)`
 *
 *  An `(s, S)` rule requires `S > s`; a rule with `S <= s` orders nothing, ever, or orders
 *  a negative amount. A numeric control **clamps silently** to its declared bounds — it
 *  does not refuse — so a solver proposing `s = 40, S = 10` would get `s = 40, S = 10`
 *  applied rather than an error, and the search would spend evaluations in a region that
 *  does not correspond to any rule the modeller meant.
 *
 *  Declaring the pair as `s` and a non-negative **increment** `sDelta`, with `S = s +
 *  sDelta`, makes every point of the box a legal rule by construction. The solver then
 *  searches a rectangle and every corner of it means something. This is the same
 *  reparameterisation KSL uses for its own `(r, S)` inventory policies, and it is the
 *  reason R16 (a control's declared bounds are the exact domain of the property it writes)
 *  is a rule of this subsystem rather than an observation about one model.
 *
 *  @param parent the model element to attach to
 *  @param s the reorder point; ordering is triggered at or below it
 *  @param sDelta how far above [s] to order up to, so the order-up-to level is `s + sDelta`
 */
class ParameterizedOrderUpTo(
    parent: ModelElement,
    s: Double = 10.0,
    sDelta: Double = 20.0,
    name: String? = null
) : ModelElement(parent, name), PolicyIfc {

    /**
     *  The reorder point. Ordering is triggered when the inventory position is at or below
     *  this. The declared bounds are the exact domain of the setter, per R16 — the setter
     *  accepts any finite non-negative value, and the control cannot deliver anything else.
     */
    @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0, upperBound = 60.0)
    var s: Double = s
        set(value) {
            require(value.isFinite() && value >= 0.0) { "s must be finite and >= 0, was $value" }
            field = value
        }

    /**
     *  How far above [s] to order up to. **Non-negative by declaration**, which is what
     *  makes every point of the search box a legal rule: `S = s + sDelta >= s` always.
     */
    @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0, upperBound = 80.0)
    var sDelta: Double = sDelta
        set(value) {
            require(value.isFinite() && value >= 0.0) { "sDelta must be finite and >= 0, was $value" }
            field = value
        }

    /** The order-up-to level, derived rather than declared — so it cannot contradict [s]. */
    val orderUpToLevel: Double get() = s + sDelta

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val position = observation[0]                    // declaration order is vector order
        val quantity = if (position <= s) orderUpToLevel - position else 0.0
        return doubleArrayOf(Math.rint(maxOf(quantity, 0.0)))
    }
}

/** The problem's model identifier. For a Type 2 problem this MUST equal the model's name. */
const val STOCK_ROOM_DECISION_ID: String = "StockRoomDecision"

/**
 *  The objective: the decision element's estimand, which is an ordinary `Response`.
 *
 *  Note what is *not* here. There is no bridge type, no result adapter, and no reference to
 *  `ksl.modeling.decision` at all — the estimand is reported under its element's name like
 *  any other response, so `simopt` addresses it the way it addresses a queue length.
 */
const val STOCK_ROOM_OBJECTIVE: String = "Room:Review:TotalReward"

/** Control keys. `elementName.propertyName`, with the element named `OrderRule`. */
const val STOCK_ROOM_S: String = "OrderRule.s"
const val STOCK_ROOM_S_DELTA: String = "OrderRule.sDelta"

/**
 *  Builds a fresh stock room whose decision element is driven by a [ParameterizedOrderUpTo].
 *
 *  A model builder must return a **brand-new, independent `Model` on every call** — the
 *  framework may build many copies, one per worker, and a shared instance would corrupt
 *  results. Nothing about that requirement is specific to decision elements.
 */
object BuildStockRoomDecisionModel : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model(STOCK_ROOM_DECISION_ID)
        val room = StockRoom(model, name = "Room")
        // The rule is an element of the model, which is what puts its parameters in front
        // of the control walk. Naming it `OrderRule` fixes the two control keys above.
        val rule = ParameterizedOrderUpTo(model, name = "OrderRule")
        room.review.policy = rule
        room.review.policyLabel = "parameterized (s, S)"
        model.numberOfReplications = 20
        model.lengthOfReplication = 2_000.0
        model.lengthOfReplicationWarmUp = 200.0
        return model
    }
}

/**
 *  MAXIMISE the estimand over the two rule parameters.
 *
 *  **Maximise, and that is not a preference.** The estimand is sign-normalised at
 *  declaration: a `RewardSense.COST` term is negated once, where it is declared, so that
 *  larger is always better downstream. The stock room declares holding and shortage as
 *  costs, so its estimand is a negative number that a good rule makes less negative.
 *  Telling `simopt` to minimise it would search for the *worst* rule, and — this is the
 *  trap — it would not fail. It would return a confident answer to the wrong question.
 */
fun makeStockRoomProblem(): ProblemDefinition {
    val problem = ProblemDefinition(
        problemName = "StockRoomOrderUpTo",
        modelIdentifier = STOCK_ROOM_DECISION_ID,
        objFnResponseName = STOCK_ROOM_OBJECTIVE,
        inputNames = listOf(STOCK_ROOM_S, STOCK_ROOM_S_DELTA),
        optimizationType = ksl.simopt.problem.OptimizationType.MAXIMIZE
    )
    // Integer-ordered, so integer-ordered solvers (R-SPLINE, COMPASS, ISC) can play. The
    // ranges are the SAME numbers the controls declare; R16 says a control's bounds are
    // the exact domain of its property, so a problem that stayed inside them can never
    // propose a value the model would silently clamp.
    problem.inputVariable(name = STOCK_ROOM_S, interval = Interval(0.0, 60.0), granularity = 1.0)
    problem.inputVariable(name = STOCK_ROOM_S_DELTA, interval = Interval(0.0, 80.0), granularity = 1.0)
    return problem
}
