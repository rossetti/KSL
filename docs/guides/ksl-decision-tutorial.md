# Tutorial: Sequential Decision Making with the KSL

A hands-on, step-by-step introduction to the KSL's sequential decision-making layer (`ksl.modeling.decision`), written for someone who **already has a KSL model** — or is comfortable building one — and wants a recurring intervention in it to become a first-class object: declared, swappable, measurable, and recordable. By the end you will have taken four models from "there is a decision buried in here somewhere" to a defensible comparison of rules, a trajectory on disk you can train from, and a parameter search run by `ksl.simopt`.

> **This is a tutorial, not a reference.** It teaches the workflow by building concrete examples slowly. One companion guide is the reference manual you graduate to:
>
> - `ksl-decision` — every declaration, the full parameterization surface, the epoch algorithm, capture, and the gotchas in condensed form.
>
> For the theory, the standard sources are Powell, *Approximate Dynamic Programming* (2011) and Sutton & Barto, *Reinforcement Learning* (2018). This tutorial does not replace them — it gets you to the point where the API stops being the obstacle.

> **Status: experimental.** `ksl.modeling.decision` and `ksl.sdm` are released as experimental. Their public API may change in future releases without notice. Pin your KSL version if you build models against them for production use.

Every code example here is a real, compiled, runnable file. They live under `KSLExamples`, in the package `ksl.examples.decision.tutorial`, and are listed in [Appendix A](#appendix-a--the-companion-files-and-how-to-run-them). If you have the KSL open in IntelliJ IDEA, run any of them by clicking the green arrow next to its `main` function.

**A note on style.** Rules are written as **named classes** rather than lambdas, so they can be found, reused, and tested. The *declaration* block is the exception and deliberately so: `observe { … }` and `lever(…) { v -> … }` take lambdas because those lambdas **are** the reader and the actuator — they are how the declaration points at the model rather than restating it.

---

## Table of contents

- [Part I — What this package is for, and the recipe every example follows](#part-i--what-this-package-is-for-and-the-recipe-every-example-follows)
- [Part II — Example 1: one lever, a rule that computes](#part-ii--example-1-one-lever-a-rule-that-computes)
- [Part III — Example 2: several levers under a constraint](#part-iii--example-2-several-levers-under-a-constraint)
- [Part IV — Example 3: when the feasible set depends on the state](#part-iv--example-3-when-the-feasible-set-depends-on-the-state)
- [Part V — Recording, and training a rule off-line](#part-v--recording-and-training-a-rule-off-line)
- [Part VI — Handing the parameters to `simopt`](#part-vi--handing-the-parameters-to-simopt)
- [Part VII — Pitfalls, and where to go next](#part-vii--pitfalls-and-where-to-go-next)
- [Appendix A — the companion files and how to run them](#appendix-a--the-companion-files-and-how-to-run-them)
- [Appendix B — glossary](#appendix-b--glossary)

---

## Part I — What this package is for, and the recipe every example follows

### 1.1 The situation

You have a model of a system in which **somebody intervenes, repeatedly**. Every morning a manager sets staffing. Every review period a planner places an order. Every hour a dispatcher allocates trucks. That intervention is usually buried in the model as an `if` statement or a constant, which makes it invisible: you cannot ask what it may see, you cannot swap it for a different one without editing the model, and you cannot record what it did.

This package makes it explicit. You declare, on the model you already have, four things:

| Declaration | Question it answers |
|---|---|
| `observe` | what may the rule **see**? |
| `lever` | what may it **change**? |
| `reward` | what is it **scored on**? |
| `every` / `onCalendar` | **when** does it decide? |

KSL then runs the loop: at each decision epoch it reads the observations, hands them to your rule, validates and applies the action, prices the interval that just ended, and — if you asked — records the whole transition.

### 1.2 What it deliberately does not do

**It does not choose the rule.** There is no solver here: no value iteration, no Q-learning, no policy gradient. This package is the *seam*. It makes the decision point explicit, inspectable, swappable and recordable, so that a rule you write — or a learner you train elsewhere, or a search run by `ksl.simopt` — has somewhere to plug in.

That boundary is why this tutorial ends where it does. Parts II–IV teach you to declare and compare; Part V records so something else can learn; Part VI hands the parameters to the KSL's actual optimizer.

### 1.3 The loop, and where your code goes

```
        every(interval) fires
                |
                v
    +---------------------------+
    |  read the observations    |     <- your `observe` declarations
    +---------------------------+
                |
                v
    +---------------------------+
    |  YOUR RULE                |     <- PolicyIfc.action(observation, ctx)
    |  action(observation, ctx) |        the SEAM
    +---------------------------+
                |
                v
    +---------------------------+
    |  validate against X(s)    |     <- limits, narrowing, state-dependent bounds
    |  then apply, or refuse    |        joint constraints
    +---------------------------+
                |
                v
    +---------------------------+
    |  price the interval       |     <- your `reward` declarations
    |  emit the transition      |     -> attached sinks; adaptive rules
    +---------------------------+
```

The line marked **the seam** is the only line you have to write. Everything above and below it is the same whatever kind of rule you plug in.

### 1.4 Two shapes of rule, one framework

There are two structurally different kinds of decision rule, and this tutorial covers both because writing them is genuinely different:

|  | **Computes an answer** | **Scores candidates** |
|---|---|---|
| What the rule does | maps the observation straight to an action | enumerates legal actions and picks the best by some score |
| Powell's classes | policy function approximation (PFA) | cost function approximation, value function approximation, direct lookahead |
| Examples here | `(s, S)` ordering (Part II), proportional staffing (Part III) | greedy-by-shortage-cost (Part IV) |
| What it needs from the framework | the observation vector | the observation vector **and the feasible set as an object** |
| Interface | `PolicyIfc` | `PolicyIfc` reading `ctx.actions`, or `LookaheadPolicy` |

The second row is why the feasible set 𝒳(*s*) is an **object you can enumerate and test membership in**, rather than a predicate applied to your answer after the fact. Three of Powell's four policy classes cannot be written without it.

### 1.5 Three ideas that explain most of the API's shape

**Positions, not names.** An observation vector and an action vector are bare `DoubleArray`s. What gives entry *i* its meaning is the *i*th entry of the element's declared list, so **declaration order is vector order**. The *descriptor* (§4.4 of the reference guide) is the authority that says which is which, and it is what lets one rule work against several models.

**Doing nothing is two different acts.** A **setting** is a quantity the model *holds* — a capacity, a reorder point. Doing nothing means writing nothing, so its neutral is a **reader**: `Neutral.Current { capacity.toDouble() }`. A **transaction** is a quantity the model *does* — placing an order, dispatching a shipment. There is no "current order quantity", so doing nothing means acting with a declared amount, almost always zero: `Neutral.Value(0.0)`. **This is the single most common modeling mistake with this package.** Part II and Part III show one of each.

**An action is prepared, then applied.** Validation and writing are separate steps, which is what makes *"no lever is written when an action is rejected"* a property of the type rather than of an implementation. A rule that asks for something infeasible does not half-move your model.

### 1.6 The recipe every example follows

Learn the shape once; the rest is repetition.

1. **Find the decision** in a model that already works.
2. **Declare the surface** — observe, lever, reward, every.
3. **Run the do-nothing arm first**, and confirm nothing changed.
4. **Swap in a rule**, and compare it against that arm.
5. **Check the comparison is worth believing** — intervals, bracketing, an independent measure.

Step 3 is not ceremony. An arm that changes nothing is what tells you your model still behaves the way it did before you added a decision to it, and the package guarantees that arm is exact: a *setting* under the neutral rule issues no write at all.

---

## Part II — Example 1: one lever, a rule that computes

A stock room that reviews its inventory every five time units and may place an order. One observation, one lever, and a rule that computes its answer directly.

Companion files: `DecisionGuideDemo.kt`.

### 2.1 Step 1 — the decision in a model that already works

Here is the model *before* any decision exists. It has demand, lead times, backorders, and one operation the model performs — `placeOrder`. Nothing about it is decision code.

```kotlin
class StockRoom(parent: ModelElement, name: String? = null) : ModelElement(parent, name) {

    val onHand = TWResponse(this, name = "${this.name}:OnHand", initialValue = 50.0)
    val ordersPlaced = Counter(this, name = "${this.name}:Orders")

    private var onOrder: Double = 0.0

    /** The model's own operation. A lever writes through this; it is not decision code. */
    fun placeOrder(quantity: Double) {
        if (quantity <= 0.0) return
        onOrder += quantity
        ordersPlaced.increment()
    }
```

The decision to make explicit is *"every five time units, somebody looks at the inventory position and decides how much to order."*

### 2.2 Step 2 — declare the surface

Four declarations, in one block, inside the class:

```kotlin
    val review: DecisionElement = decisionElement("${this.name}:Review") {
        observe(onHand, unit = "units")                       // observation 0
        lever(
            this@StockRoom, limits = 0..200,
            neutral = Neutral.Value(0.0),                     // ordering nothing IS the no-op
            alias = "OrderQty", unit = "units"
        ) { q -> placeOrder(q) }
        reward(onHand, rate = 0.5, sense = RewardSense.COST, alias = "Holding")
        every(5.0)
        policy = NeutralPolicy
    }
}
```

Four things to notice, and the second and third are the ones that bite.

- **The element name is model-wide**, like every other KSL element name. `decisionElement("Review")` reads as though it were a local label and it is not — a subsystem that names its element with a bare literal cannot be instantiated twice. Qualify it: `"${this.name}:Review"`.
- **The lever writes through a method the model already has.** `placeOrder` is the operation the model performs; a lever is a *permission to call it*. You are not writing new mechanism, you are exposing existing mechanism.
- **`Neutral.Value(0.0)`, because an order is a transaction.** There is no "current order quantity" to read. Getting this backwards — declaring `Neutral.Current { … }` for a transaction — makes the do-nothing arm re-issue the last order at every epoch.
- **The reward declares its sense.** `RewardSense.COST` is negated **once**, at declaration, so everything downstream maximizes one quantity and no code tracks signs.

### 2.3 Step 3 — run the do-nothing arm

```kotlin
val model = Model("StockRoomDemo")
val room = StockRoom(model, "Room")
model.numberOfReplications = 10
model.lengthOfReplication = 500.0
model.simulate()
model.print()
```

The objective appears in the standard report as `Room:Review:TotalReward`. It is an ordinary `Response` — comparison, confidence intervals and databases work on it exactly as they do on anything else. The decision element introduces **no new statistic type**.

### 2.4 Step 4 — write a rule and swap it in

A rule is one method: one array in, one array out.

```kotlin
/** An (s, S) rule: order up to [bigS] whenever the position is at or below [s]. */
class OrderUpTo(private val s: Double, private val bigS: Double) : PolicyIfc {
    init { require(s < bigS) { "The reorder point s=$s must be below the order-up-to level S=$bigS" } }
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val position = observation[0]
        return if (position <= s) doubleArrayOf(bigS - position) else doubleArrayOf(0.0)
    }
}
```

Swapping it in is one line, and **the model is not edited**:

```kotlin
room.review.policy = OrderUpTo(12.0, 32.0)
room.review.policyLabel = "(12, 32)"
model.simulate()
```

That is the property worth pausing on. Everything a study varies — the rule, its parameters, the lever's limits, how often the review happens, whether anything is recorded — is set **from outside, on the element**. A modeler owns the model; whoever is running the study owns the decision.

### 2.5 Step 5 — check the comparison is worth believing

`DecisionGuideDemo` runs six arms and prints a table. Two readings of it matter more than the numbers.

**The do-nothing arm does not merely lose — it diverges.** With nothing ever ordered, backorders accumulate without bound. That is the right result and a *poor discriminator*: it tells you the model responds to the decision at all, and nothing else. The comparison that discriminates is among the ordering rules.

**Where the best arm sits in the grid decides what you are allowed to say.** If the winner is an interior point, the grid brackets an optimum and the comparison is worth believing. If it sits at an edge, the honest reading is *"better than everything tried"*, not *"best"* — widen the grid before quoting it as a recommendation. The demonstration prints which case it is in, rather than leaving you to check.

### 2.6 Recap

Declare four things; run the neutral arm; swap rules from outside; read an ordinary `Response`; check the grid brackets. Everything after this is the same shape with harder declarations.

---

## Part III — Example 2: several levers under a constraint

A clinic with triage and exam stations, staffed from a fixed pool of eight. Every shift, somebody decides the split. Two levers, a joint constraint, and — new here — an objective with **more than one term, pushing in opposite directions**.

Companion files: `ClinicWalkthrough.kt`; the model is `ClinicExample.kt`.

### 3.1 Step 1 — the decision, and why it is not obvious

Triage is offered about 2.0 server-units of work, exam about 2.4. A static 4/4 split therefore over-staffs triage and under-staffs exam, but by how much, and is the right correction one server or two? That is a real question, which is what makes the clinic a better second example than a larger version of the stock room.

### 3.2 Step 2 — two levers, one budget, and a **setting** this time

```kotlin
        val t = lever(triageStaff, limits = 0..10,
            neutral = Neutral.Current { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
        val e = lever(examStaff, limits = 0..10,
            neutral = Neutral.Current { capacity.toDouble() }) { v -> changeCapacity(v.toInt()) }
        budget(t, e, total = 8.0)
```

`lever(...)` **returns the lever's identity**, and `budget` names levers rather than targets — which matters because one model element can back several levers. `budget` is "must sum to exactly"; `atMost` is "may sum to at most".

And here is the other kind of neutral. A capacity is a **setting**: a level the model holds. Doing nothing means writing nothing, so the neutral carries a *reader*. That reader is what lets the element answer "where does this lever stand now?" and skip a write that would change nothing — which is what makes the do-nothing arm identical, observation for observation, to the model with no decision element in it.

### 3.3 Step 3 — an objective with several terms and two directions

```kotlin
        reward(exam.numProcessed, rate = 25.0, sense = RewardSense.REWARD, alias = "Revenue")
        reward(triage.waitingQ.numInQ, rate = 10.0, sense = RewardSense.COST, alias = "TriageWait")
        reward(exam.waitingQ.numInQ, rate = 10.0, sense = RewardSense.COST, alias = "ExamWait")
```

Every rate is a **positive number in the units the modeler thinks in**. `sense` carries the direction. A `COST` is negated once, here at declaration, so larger is better everywhere downstream and no rule, comparison, or captured trajectory tracks a sign.

Notice which term the decision actually moves. Throughput is arrival-limited, so revenue is much the same whatever the allocation; what reallocating staff changes is where patients *wait*. Real objectives usually look like this — several terms, of which the decision touches one — and it is worth seeing that written down rather than simplified away.

### 3.4 Step 4 — run the arms

Measured over 20 replications of a 43,200-unit clinic with a 4,320 warm-up:

| rule | profit | ± half-width | mean system time |
|---|---|---|---|
| static 4/4 (do nothing) | 17,884.6 | 9,493.9 | 20.28 |
| static 3/5 | 116,097.8 | 3,698.8 | 19.01 |
| static 2/6 | −77,505.9 | 10,901.3 | 21.52 |
| proportional to demand | 114,357.4 | 4,214.2 | 19.03 |

The over-cut arm goes **negative**: with triage starved, the waiting charges outweigh the revenue. That is worth checking deliberately — a composite that stayed positive everywhere would be revenue wearing a costume.

### 3.5 Step 5 — the check that matters: does the objective measure the clinic?

Declaring an objective is easy. Declaring one that **discriminates** is the part worth testing, and the way to test it is to compare it against a measure it never reads.

Mean time in the system is that measure: the profit is built from patients processed and queue lengths and never looks at a system time. Independently, 3/5 is the M/M/c optimum on mean system time. The composite picks 3/5 too — **without being told** — which is the evidence that it is measuring the clinic rather than measuring itself. Had the two disagreed, one of them would be wrong and it would matter which.

### 3.6 The failure worth studying: a rule that oscillated

`ProportionalStaffing` divides the budget in proportion to observed demand. Its **first version allocated in proportion to queue length** and was a disaster:

```
    proportional to queue length              187.45   mean system time
    proportional to instantaneous busy units   68.84
    proportional to time-averaged busy units   19.03   <- the rule above
    static 3/5, the M/M/c optimum              19.01
```

Two separate defects, and fixing either alone is not enough.

**Demand, not congestion.** Queue length is a *consequence* of the allocation, so allocating on it closes a positive feedback loop with a 480-unit delay: one patient waiting at triage with none at exam reads as "all eight to triage", which starves exam, fills exam's queue, and starves triage at the next epoch. Busy units are what the arrival process makes them — while a station has enough capacity, its busy count does not depend on what the rule decided. Allocating on a quantity the rule does not itself move is what breaks the loop.

**An average, not a snapshot.** Instantaneous busy units at triage are 0, 1, 2 or 3 around a mean of 1.2 — the station is completely idle about 30% of the time. A snapshot reading of (0, 3) sends every spare unit to exam and leaves triage below its own offered load for a full shift.

Damping the original — moving partway toward the target each epoch, or capping the step size — would have reduced the amplitude without removing the loop. **The arithmetic was never the problem; the signal was.** The fix is to the `observe` declarations, not to the rule.

### 3.7 A rule that inspects the surface it was handed

A rule that divides a budget only makes sense against an element that declares one. `ShapeAwarePolicyIfc` lets it check, once, at assignment — and **refuse**:

```kotlin
    override fun configure(surface: DecisionSurfaceDescriptor) {
        require(surface.observations.size == surface.levers.size) {
            "ProportionalStaffing weights each lever by one observation, so it needs " +
                "${surface.levers.size} observations; the element declares ${surface.observations.size}."
        }
        require(surface.constraints.any { it is SumEquals }) {
            "ProportionalStaffing divides a fixed budget, so it needs a declared budget() " +
                "over its levers. The element declares: ${surface.constraints}"
        }
    }
```

Refusing at assignment beats failing at epoch 400 of replication 12.

### 3.8 Recap

Several levers; a joint constraint that names lever identities; a *setting* whose neutral is a reader; an objective with mixed senses and positive rates throughout; and an independent check that the objective ranks the way reality does.

---

## Part IV — Example 3: when the feasible set depends on the state

A depot allocating scarce stock across three regions. This is the part where the **feasible set** stops being bookkeeping and becomes the thing the rule is built around.

Companion files: `DepotWalkthrough.kt`; the model is `ShipmentAllocationExample.kt`.

### 4.1 Step 1 — constraints that move

```
    ship[i]   <= backlog[i]        you cannot send what nobody has asked for
    sum(ship) <= onHand            you cannot ship what you do not have
    sum(ship) <= truckCapacity     the only constant among them
```

Parts II and III had constraints that were **constant**: a lever's limits, and a budget summing to eight. Here two of the three are functions of the state, so the set of legal allocations is different at every review. Regions differ in shortage cost — 9, 3 and 1 per unit per unit time — so the allocation is not symmetric either: when stock is short, the expensive region should be served first.

### 4.2 Step 2 — declare the constraints in the model that owns them

```kotlin
                lever(
                    this@ShipmentDepot, limits = 0..truckCapacity,
                    neutral = Neutral.Value(0.0),
                    alias = "Ship:${regionNames[i]}",
                    bounds = { 0.0..backlog(i) }
                ) { q -> ship(i, q.toInt()) }
```

and the conservation law over all three:

```kotlin
            atMost(*refs.toTypedArray(), envelope = truckCapacity.toDouble()) { shippableNow }
```

`limits` is the **envelope** — the model's physical range, constant. `bounds` is 𝒳(*s*) — what is legal *right now*. The `envelope` / lambda pair on `atMost` is the same split for the joint constraint. The effective set is their **intersection**, not a containment: "what this region is owed" may legitimately exceed "what one truck holds", and the smaller wins.

### 4.3 Step 3 — what declaring it buys, measured

The depot carries a switch, `stateDependentDeclaration`, so the **same model** can be built both ways. Give both the same naive rule — *serve every region's full backlog*, staying inside the truck but ignoring the shelf — and:

**Without the state-dependent declaration**, the element cannot see the real bound. The rule proposes allocations the depot cannot supply, and the model defends itself by clamping:

```
    over-shipments the model had to absorb: 182
```

Every one of those is a silent correction inside the model. The trajectory records what was *written*, so the rows are honest — but the rule was never told it asked for something impossible, and cannot learn from it.

**With it**, the same rule is refused before any lever is written:

```
    ActionValidationException
    [Sum of [Ship:North, Ship:Central, Ship:South] is 21.0; the state-dependent total allows at most 5.0.]
```

The refusal cites the *state-dependent* total rather than the constant truck, which is the whole point: the truck bound is declarable under both designs, so a violation of it would prove nothing.

**The declaration moved from the policy to the model, which is where it always belonged.** The conservation law is a fact about the depot, not an opinion of whoever is writing this week's rule. Every future rule inherits it for free, and none of them can violate it quietly.

### 4.4 Step 4 — a rule that scores candidates

Now the second shape of rule from §1.4. `GreedyByShortageCost` solves a small optimization at every epoch, subject to the constraints in force:

```kotlin
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val n = ctx.leverNames.size
        val plan = DoubleArray(n)
        var remaining = ctx.budgetTotal(0) ?: Double.MAX_VALUE
        for (i in order) {
            val want = ctx.actions.bounds(i).endInclusive          // what this region is owed
            val give = max(0.0, minOf(want, remaining))
            plan[i] = Math.rint(give)
            remaining -= plan[i]
        }
        return plan
    }
```

It never re-derives a constraint. It **asks the element**: `ctx.budgetTotal(0)` is what may be shipped in total right now; `ctx.actions.bounds(i)` is what region *i* may receive right now. Both are current at the instant of the decision.

Measured over 20 replications, shortage cost per unit time (smaller is better — this is a raw cost, not the sign-normalized estimand):

| rule | cost / time |
|---|---|
| ship nothing | 8,568.97 |
| proportional to backlog (PFA) | 59.48 |
| greedy by shortage cost (CFA) | 53.91 |

The greedy rule is **9.4%** better. The two are different *policy classes*, not two tunings of one idea: the proportional rule computes shares directly from the observation and has nowhere to put the differing shortage rates; the greedy one considers the regions in an order it derived from the declared surface and stops when the budget runs out.

### 4.5 Step 5 — the same rule, forced to re-derive the constraint

Run `GreedyByShortageCost` with `useFeasibleSet = false` and it re-derives the bounds from the observations instead of asking. The costs come out **identical** — it is the same class with one flag flipped. What differs is where the conservation law lives:

```kotlin
        var remaining = min(observation[n], 100.0)
```

That `100.0` is the truck capacity, as a **literal inside the rule**, because the old declaration could not express the real bound. Change the truck and the model is right and the rule is silently wrong. The argument for declaring the feasible set is not about performance; it is about which artifact owns a fact.

### 4.6 One predicate, asked two ways

A rule can ask *"is this allocation legal?"* or *"what is wrong with it?"*. Those must be the same question, or a rule that checks one and reports the other will contradict itself in front of a user. `DepotWalkthrough` probes 1,600 candidate allocations across a run and asserts zero disagreements between `ctx.actions.contains(...)` and `ctx.actions.violations(...)`.

### 4.7 Recap

Envelope versus state-dependent bounds; the conservation law declared by the model that owns it; a candidate-scoring rule that asks rather than re-derives; and a measured A/B for what the declaration is worth.

---

## Part V — Recording, and training a rule off-line

Everything so far compares rules you wrote. This part records what a rule *did*, so something else can learn from it.

Companion files: `OfflineTrainingDemo.kt`.

### 5.1 A transition, and why the rows are self-describing

One row is a complete `(state, action, reward, successor)` transition, with the interval length `tau`, the termination flags, and — when the action had to be repaired or a lever had nothing to choose from — what was originally asked for.

`terminated` and `truncated` are separate fields on purpose: an episode that reached a real ending and one the run length cut off are different things, and conflating them biases any learner that bootstraps from the last row.

### 5.2 Attach a sink from outside the model

Whether a run is recorded is a property of **the run**, not of the subsystem being simulated. So a sink is attached to a model that is already built:

```kotlin
val sink = MemorySink()
element.attachTransitionSink(sink)
model.simulate()
element.detachTransitionSink(sink)

println("${sink.records.size} transitions, and the model never mentioned capture")
```

For a whole model there is a one-liner that finds every decision element and writes a trajectory per element per experiment:

```kotlin
DecisionCapture.toDirectory(model, outputDir).use {
    model.simulate()
}
```

Attaching or detaching **during** a run throws. A trajectory that begins in the middle of an episode has no predecessor state for its first row, and a run that recorded half its decisions is worse than one that recorded none. Before a run, or between two runs, is fine.

### 5.3 A durable trajectory leaves two files, and the second is not optional

`TabularSink` writes `<name>.sqlite` — an ordinary SQLite database, queryable with any SQL tool and readable from Python — and `<name>.provenance.json` beside it.

A row is **positional**. `a_Mode = 2.0` means nothing without the declaration saying that lever is `CATEGORICAL` over `["slow", "normal", "fast"]`, that it is a `SETTING` rather than a `TRANSACTION`, and where its bounds are. Column names carry position-to-name and nothing else. `TrajectoryFile` pairs the two on read and **refuses** a trajectory whose provenance is missing rather than guessing.

### 5.4 The learner's whole input is a path

```kotlin
/** Fits an order-up-to level from a captured trajectory, reading only the file. */
fun bestOrderUpTo(rowsPath: Path): Double =
    TrajectoryFile(rowsPath).use { trajectory ->
        val surface = trajectory.descriptor
        val position = surface.observations.indexOfFirst { it.name.endsWith(":Position") }
        val lever = surface.levers[0]

        val best = trajectory.transitions()
            .groupBy { floor((it.state[position] + it.action[0]) / 5.0) * 5.0 }   // post-decision position
            .filterValues { it.size >= 20 }                                        // ignore thin buckets
            .maxByOrNull { (_, rows) -> rows.sumOf { it.reward } / rows.size }!!
            .key + 2.5                                                             // bucket midpoint

        // The descriptor says what a LEGAL order is; the rows do not.
        if (lever.domain == LeverDomain.CONTINUOUS) best else Math.rint(best)
    }
```

No `Model`, no element, not even the JVM that produced the data. That last line is there because of a real failure: the first version ignored `lever.domain`, fitted `0.5` units, and the fitted rule crashed the model with `ActionValidationException: 'OrderQty' = 0.5 units is not integral`.

### 5.5 Explore badly and the learner will tell you

The exploration run must cover the decision space rather than one rule's habits. For an order-up-to family, vary the **target**, not the quantity.

> Get this wrong and it shows. Drawing a random order *quantity* on 0..120 against demand of five per epoch ordered twelve times what the model consumed — inventory ran away, post-decision positions spread over thousands of units, and the fit refused with *"no bucket had 20 or more rows out of 7,960 transitions."* That is the right refusal on the wrong data.

### 5.6 Then put the fitted rule back in the simulator

Explore → capture → read back with no live model → fit → run the fitted rule → **beat the do-nothing arm**. `OfflineTrainingDemo` runs all five steps end to end and prints each.

---

## Part VI — Handing the parameters to `simopt`

Part II swapped whole rules. This part searches over one rule's **parameters** — which is simulation optimization, and the KSL already has it.

Companion files: `SimoptHandoffSetup.kt`, `SimoptHandoffExample.kt`.

### 6.1 Write the rule as a `ModelElement` with controls

```kotlin
class ParameterizedOrderUpTo(
    parent: ModelElement,
    s: Double = 10.0,
    sDelta: Double = 20.0,
    name: String? = null
) : ModelElement(parent, name), PolicyIfc {

    @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0, upperBound = 60.0)
    var s: Double = s
        set(value) {
            require(value.isFinite() && value >= 0.0) { "s must be finite and >= 0, was $value" }
            field = value
        }

    @set:KSLControl(controlType = ControlType.DOUBLE, lowerBound = 0.0, upperBound = 80.0)
    var sDelta: Double = sDelta
        set(value) {
            require(value.isFinite() && value >= 0.0) { "sDelta must be finite and >= 0, was $value" }
            field = value
        }

    val orderUpToLevel: Double get() = s + sDelta

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val position = observation[0]
        val quantity = if (position <= s) orderUpToLevel - position else 0.0
        return doubleArrayOf(Math.rint(maxOf(quantity, 0.0)))
    }
}
```

Being a `ModelElement` is what puts the parameters in front of the model's control walk. Run the demonstration and it prints:

```
    OrderRule.s
    OrderRule.sDelta
    Room:Review.epochInterval
    Room:Review.maxEpochs
```

Two belong to the rule. **Two belong to the decision element** — *when* it decides is a parameter too, so "how often should we review?" is a question a search can be asked.

### 6.2 Parameterize so the optimizer sees a box

This is the part that is easy to get wrong. A numeric control **clamps silently** — it does not refuse — so a solver proposing `s = 40, S = 10` would have it *applied*, and the search would spend evaluations on points corresponding to no rule you meant.

Declaring the pair as `s` and a **non-negative increment** `sDelta`, with `S = s + sDelta`, makes every point of the box a legal rule by construction. This is the same reparameterization KSL uses for its own `(r, S)` inventory policies.

### 6.3 The problem definition

```kotlin
fun makeStockRoomProblem(): ProblemDefinition {
    val problem = ProblemDefinition(
        problemName = "StockRoomOrderUpTo",
        modelIdentifier = STOCK_ROOM_DECISION_ID,
        objFnResponseName = STOCK_ROOM_OBJECTIVE,
        inputNames = listOf(STOCK_ROOM_S, STOCK_ROOM_S_DELTA),
        optimizationType = ksl.simopt.problem.OptimizationType.MAXIMIZE
    )
    problem.inputVariable(name = STOCK_ROOM_S, interval = Interval(0.0, 60.0), granularity = 1.0)
    problem.inputVariable(name = STOCK_ROOM_S_DELTA, interval = Interval(0.0, 80.0), granularity = 1.0)
    return problem
}
```

**MAXIMIZE, and that is not a preference.** The estimand is sign-normalized at declaration: a `COST` is negated once, so larger is always better. The stock room's estimand is a negative number that a good rule makes less negative. Telling `simopt` to minimize it would search for the *worst* rule — and it would not fail. It would answer the wrong question, confidently.

The objective name is just a response name, and the input names are just control keys. **There is no adapter in any of this, and that absence is the design decision.**

### 6.4 Run it

```kotlin
    val solver = Solver.createStochasticHillClimberSolver(
        problemDefinition = problem,
        modelBuilder = BuildStockRoomDecisionModel,
        startingPoint = null,
        maxIterations = maxIterations,
        replicationsPerEvaluation = replicationsPerEvaluation
    )
    solver.runAllIterations()
```

Measured, against a hand grid that swept `s` with the increment held at 5:

| arm | estimand |
|---|---|
| do nothing | −9,461,188 |
| hand grid, best of 5 | −6,509.9 |
| search, 30 iterations | −7,467.4 |
| search, 120 iterations | −5,021.8 |

**Read that honestly.** On 30 iterations the search does **not** beat a well-chosen grid. That is not a bug and not a criticism of the solver — it is the lesson. A greedy, single-trajectory method on a noisy objective needs enough evaluations to find its way, and effort is the currency of this whole subject. Given four times the budget it finds `(s = 7, S = 10)`, which an independent 35-point scan confirms is essentially the optimum.

Two more things worth taking away. The search is **reproducible**: run it again and it lands on the same point, because each evaluation builds a fresh model whose stream provider is seeded identically. And a single run of one method is still not the final word on a noisy problem — which is exactly what `ksl-simopt-tutorial`'s benchmark harness is for, and it is the next thing to read.

### 6.5 Reading a solution without fooling yourself

Two traps, both measured while writing this part:

- **Read the inputs by name.** `Solution.asString()` prints them positionally, in the input map's own iteration order, which is *not* the order of `problem.inputNames`. Use `best.inputMap[STOCK_ROOM_S]`.
- **Read `estimatedObjFnc.average` for the raw value.** On a MAXIMIZE problem, `estimatedObjFncValue` is sign-flipped internally, so printing it beside a hand-computed number compares `+5,022` with `−6,510` and reads backwards.

---

## Part VII — Pitfalls, and where to go next

### 7.1 Pitfalls checklist

- **Setting or transaction?** A quantity the model *holds* takes `Neutral.Current { … }`; a quantity the model *does* takes `Neutral.Value(0.0)`. This is the most common mistake and it makes the do-nothing arm wrong rather than throwing.
- **A setting must be reset in `initialize()`.** A lever value left over from the previous replication makes two runs of one model disagree for reasons unrelated to what you are studying.
- **Declaration order is vector order.** `observation[2]` means "the third thing declared". Read the descriptor rather than counting by hand.
- **Qualify the element's name.** `decisionElement("${this.name}:Review")`, never `decisionElement("Review")`, or the subsystem cannot be instantiated twice.
- **Allocate on a signal the rule does not itself move.** Allocating on congestion closes a feedback loop with an epoch-length delay (§3.6).
- **Check the objective against something it never reads.** A composite that ranks the way an independent measure ranks is measuring your system; one that does not is measuring itself (§3.5).
- **Check where the winner sits in the grid.** At an edge, you may say "better than everything tried", not "best".
- **A numeric control clamps, it does not refuse.** Parameterize so every point of the box is legal (§6.2).
- **When maximizing, read the right number** — and read inputs by name, not by position (§6.5).
- **Vary the target, not the quantity, when exploring for training data** (§5.5).
- **Capture is attach/detach, and refuses mid-run.** Attach before a run or between runs.

### 7.2 Where to go next

- **The reference for this package:** `ksl-decision` — every declaration, the full parameterization surface, capture, and the condensed gotchas.
- **Simulation optimization:** `ksl-simopt-tutorial` first, then `ksl-simopt` and `ksl-simopt-benchmark`.
- **The control-key naming convention:** `ksl-controls`.
- **Building models:** `ksl-entity`, `ksl-modeling`, `ksl-simulation`, `ksl-station`.
- **The theory:** Powell (2011); Sutton & Barto (2018); Puterman (1994).

---

## Appendix A — the companion files and how to run them

All files are in `KSLExamples`, package `ksl.examples.decision.tutorial` (directory `KSLExamples/src/main/kotlin/ksl/examples/decision/tutorial/`). The models three of them use live one package up, in `ksl.examples.decision`.

| File | Role | Has `main`? |
|---|---|---|
| `DecisionGuideDemo.kt` | Part II: the stock room, its `(s, S)` rule, and the six-arm comparison | yes |
| `ClinicWalkthrough.kt` | Part III: two levers under a budget, mixed-sense objective, the independent check | yes |
| `DepotWalkthrough.kt` | Part IV: state-dependent feasible set, the declaration A/B, PFA vs CFA | yes |
| `OfflineTrainingDemo.kt` | Part V: explore, capture, read back, fit, evaluate | yes |
| `SimoptHandoffSetup.kt` | Part VI shared setup: the parameterized rule, model builder, problem definition | no |
| `SimoptHandoffExample.kt` | Part VI: controls, a hand grid, and two solver budgets | yes |

Start with `DecisionGuideDemo.kt` (a few seconds), then `ClinicWalkthrough.kt` and `DepotWalkthrough.kt`. `SimoptHandoffExample.kt` runs a real search and takes the longest; reduce its budgets while you are reading.

The models used by Parts III and IV are `ClinicExample.kt` and `ShipmentAllocationExample.kt` in `ksl.examples.decision`, alongside `SsInventoryExample.kt` and `VfaInventoryExample.kt` — worked models a modeler might build on, rather than walkthroughs.

---

## Appendix B — glossary

- **Decision epoch** — an instant at which the rule is consulted. Declared with `every(interval)` or `onCalendar(times)`.
- **Observation** — a quantity the rule may read. Declaration order is vector order.
- **Lever** — a quantity the rule may write, through a method the model already has.
- **Setting / transaction** — a lever the model *holds* versus one the model *does*; they have different neutrals.
- **Neutral value** — what "do nothing" means for a lever: a reader for a setting, a declared value for a transaction.
- **Feasible set 𝒳(s)** — the legal actions at a decision instant: envelope ∩ narrowing ∩ state-dependent bounds ∩ joint constraints. An object, not a predicate.
- **Envelope** — a lever's constant physical range (`limits`), as opposed to its state-dependent `bounds`.
- **Estimand** — the objective the element publishes, as an ordinary `Response`. Sign-normalized: larger is better.
- **Reward sense** — `COST` or `REWARD`; negated once at declaration so nothing downstream tracks signs.
- **Descriptor** — the machine-readable description of a decision surface: observations, levers, rewards, constraints, timing. Serializable to JSON and TOML, and carried with a trajectory.
- **Transition** — one row: state, action, reward, successor, `tau`, termination flags, and the proposed action when it differed.
- **Provenance** — which model, experiment, element and rule produced a trajectory, plus the descriptor that makes its columns readable.
- **Sink** — a consumer of transitions, attached to an element and told when each run starts and stops.
- **Policy class** — Powell's taxonomy: policy function approximation, cost function approximation, value function approximation, direct lookahead. The first computes; the other three score candidates.
- **Do-nothing arm** — the run under `NeutralPolicy`; the baseline every comparison starts from.
