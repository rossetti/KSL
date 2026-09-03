# Using `ksl.modeling.decision`

A task-oriented usage guide. For each common task, the smallest amount
of code that does it, and the gotchas that matter in practice.
Reference detail (parameter lists, every overload) is on the Dokka API
pages; this guide gets you productive.

> **Status: experimental.** `ksl.modeling.decision` and `ksl.modeling.decision.capture` are
> released as experimental. Their public API may change in future
> releases without notice. Pin your KSL version if you build models
> against them for production use.

## 1. What this package is for

`ksl.modeling.decision` is the **sequential decision-making layer** in
KSL. You declare, on an existing model, what a decision rule may *see*,
what it may *change*, what it is *scored on*, and *when* it decides.
KSL then runs the decision loop for you: at each decision epoch it
reads the observations, hands them to your rule, validates and applies
the action, prices the interval that just ended, and — if you asked —
records the whole transition.

Reach for it when the natural language of your model is *"every so
often, something looks at the state and changes a setting."* Reorder
points, staffing levels, dispatch priorities, capacities, prices.

**What it does not do is choose the rule.** There is no solver here —
no value iteration, no Q-learning, no policy gradient. This package is
the *seam*: it makes the decision point in your model explicit,
inspectable, swappable, and recordable, so that a rule you write (or a
learner you train elsewhere) has somewhere to plug in. That is a
deliberate boundary, not an omission.

### How it relates to its neighbors

- `ksl.simulation` supplies `ModelElement`, the lifecycle, and the
  event calendar. A decision element **is** a `ModelElement` and obeys
  the ordinary phases; nothing about running your model changes.
- `ksl.modeling.variable` supplies the `Response` / `TWResponse` /
  `Counter` your observations read and your rewards accumulate from.
  The decision element defines **no new statistic type** — its
  objective is published as an ordinary `Response`.
- `ksl.simopt` is the other half of the same activity. Searching over
  the parameters of a parameterized rule to minimize an expected cost
  estimated by simulation *is* simulation optimization. This package
  does not reimplement it; see §4.12 for how the two meet.
- `ksl.modeling.decision.capture` holds the sink *destinations* —
  `MemorySink`, `NullSink`, `TabularSink` — and `DecisionCapture`, which
  attaches capture to a model from outside. The sink *contract* lives
  with its producer in `ksl.modeling.decision`, and so does
  `RollingSink`, which is a decorator over that contract rather than a
  destination: it is about a sink's *lifetime*, one artifact per run.
  Only destinations live in `ksl.modeling.decision.capture`.
- `ksl.modeling.decision.descriptor` is plain serializable data — the
  machine-readable description of a decision surface, with no
  reference to a model at all, so it can travel without one.

## 2. The mental model

Three declarations, one call, and one loop.

**Observations** are what the rule may read. **Levers** are what it may
write. **Rewards** are what it is scored on. You declare those three in
one block. **When** the decision happens is not declared at all — *you*
call `decide(reason)` at the point a decision is due.

```
      declare                  you call                 the element runs
   ┌───────────┐            ┌──────────────┐        ┌────────────────────────┐
   │ observe   │            │ decide(why)  │        │  read observations     │
   │ lever     │  ────────► │      or      │ ─────► │  call your rule        │
   │ reward    │            │ requestDeci- │        │  validate + apply      │
   └───────────┘            │ sion(why)    │        │  price the interval    │
                            └──────────────┘        │  record the transition │
                                                    └────────────────────────┘
```

That the caller owns the timing is the one thing to understand before
anything else here makes sense, so §2.1 is about it.

### 2.1 You decide when a decision happens

The element does not schedule anything. It has no review period, no
calendar, and no opinion about when it should be consulted. A decision
happens because something in your model calls `decide(reason)`.

This is less of a change than it sounds. Without this package you
already write the decision at the point it is due — reading what you
need, changing what you mean to change. All that moves into the element
is the *rule* and the bookkeeping around it: a swappable policy, reward
accrued over the interval between decisions, a recorded trajectory, and
an estimand the comparison machinery already understands. Where the
decision happens stays yours.

**Most models want a review on a period, and should say so in one
construction.** `PeriodicDecisionElement` is a model element that owns a
decision element *and* the event that reviews it:

```kotlin
val review = PeriodicDecisionElement(this, interval = 30.0, name = "Review") {
    observe(position)
    lever(this@StockRoom, 0..200, neutral = Neutral.Value(0.0)) { q -> placeOrder(q) }
    reward(onHand, rate = 0.5, sense = RewardSense.COST)
    policy = OrderUpTo(s = 20.0, bigS = 60.0)
}
```

It is a composition, not a special case: it holds an ordinary element and
calls the same public `decide` you would. Three things come with it. The
interval is checked at construction, so an element that never decides is
unreachable. The review runs in an event of its own and changes nothing
itself, so the state it reads is consistent and §6's obligation is met
for you. And the interval is a `@KSLControl`, so `simopt` can search it.

Everything below is the general case underneath it — reach for it when a
period is not what you want.

**A periodic review written by hand** is an ordinary permanent entity, or
an ordinary event action — `decisions` here is a plain `DecisionElement`
declared with `decisionElement { }`:

```kotlin
private inner class Reviewer : Entity() {
    val reviewProcess = process {
        while (model.isRunning) {
            delay(reviewPeriod)
            decisions.decide("periodic")
        }
    }
}
```

**A review triggered by the system** is a call at the point the system
changed:

```kotlin
val demandProcess = process {
    applyDemand(demandSize.value)
    if (inventoryPosition <= reorderPoint) decisions.decide("reorder point")
}
```

Timed and state-triggered reviews are the same thing — a call at a
point in a process — which is why the package needs no vocabulary for
either. `ksl.examples.decision.PeriodicReview` is a five-line driver
for the common case; it is an example rather than library API, on
purpose.

**Two entry points, and they mean different things.**
`decide(reason)` decides *now*, inside your event: *n* calls at one
instant are *n* decisions, which is sometimes exactly right — two
demands arriving together, each triggering a review, is a correct model.
`requestDecision(reason)` asks for *a decision at the next quiescent
point*: it schedules the epoch into an event of the element's own, and
**several requests at one instant produce one decision** whose reason
names them all. They describe the same state, and there is one surface
and one rule, so there is nothing to make several decisions about.

Prefer the first where you control the call site and can vouch for the
state; prefer the second where you cannot, or where nobody is waiting
for the answer. §6 says why that choice matters.

Three more ideas are worth holding onto because they explain most of
the API's shape.

**Positions, not names.** An observation vector and an action vector
are bare `DoubleArray`s. What gives entry *i* its meaning is the *i*th
entry of the element's declared list — so **declaration order is vector
order**, and the descriptor (§4.4) is the authority that says which is
which. This is what lets one rule work against several models.

**Doing nothing is two different acts.** A *setting* is a quantity the
model **holds** — a capacity, a reorder point. Doing nothing means
writing nothing, so its neutral is a **reader**:
`Neutral.Current { capacity.toDouble() }`. A *transaction* is a
quantity the model **does** — placing an order, dispatching a shipment.
There is no "current order quantity", so doing nothing means acting
with a declared amount, almost always zero:
`Neutral.Value(0.0)`. Getting this wrong is the single most common
modeling mistake with this package; see §6.

**An action is prepared, then applied.** Validation and writing are two
separate steps, which is what makes *"no lever is written when an
action is rejected"* a property of the type rather than of an
implementation. A rule that asks for something infeasible does not
half-move your model.

## 3. Quick start

A stock room that reviews its inventory every five time units. It runs
under the do-nothing rule first, which is always where to start: an arm
that changes nothing is what tells you your model still behaves the way
it did before you added a decision to it.

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

    val review = PeriodicDecisionElement(this, interval = 5.0, name = "${this.name}:Review") {
        observe(onHand, unit = "units")                       // observation 0
        lever(
            this@StockRoom, limits = 0..200,
            neutral = Neutral.Value(0.0),                     // ordering nothing IS the no-op
            alias = "OrderQty", unit = "units"
        ) { q -> placeOrder(q) }
        reward(onHand, rate = 0.5, sense = RewardSense.COST, alias = "Holding")
        policy = NeutralPolicy
    }
}
```

```kotlin
val model = Model("StockRoomDemo")
val room = StockRoom(model, "Room")
model.numberOfReplications = 10
model.lengthOfReplication = 500.0
model.simulate()
model.print()
```

Four things to notice.

- **The element name is model-wide**, like every other KSL element name.
  `decisionElement("Review")` reads as though it were a local label, and
  it is not — a subsystem that names its element with a bare literal
  cannot be instantiated twice. Qualify it: `"${this.name}:Review"`.
- **The lever writes through a method the model already has.**
  `placeOrder` is not decision code; it is the operation the model
  performs. A lever is a permission to call it.
- **The reward declares its sense.** `RewardSense.COST` is negated once,
  at declaration, so everything downstream maximizes one quantity and no
  code has to track signs.
- **The objective appears in the standard report** as
  `Room:Review:TotalReward`. It is an ordinary `Response`; comparison,
  confidence intervals and databases work on it exactly as they do on
  anything else.

## 4. How do I…?

### 4.1 …write a rule?

Implement `PolicyIfc`. One method, one array in, one array out.

```kotlin
/** An (s, S) rule: order up to [bigS] whenever the position is at or below [s]. */
class OrderUpTo(private val s: Double, private val bigS: Double) : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val position = observation[0]
        return if (position <= s) doubleArrayOf(bigS - position) else doubleArrayOf(0.0)
    }
}
```

Swap it in without touching the model:

```kotlin
room.review.element.policy = OrderUpTo(s = 20.0, bigS = 80.0)
room.review.element.policyLabel = "(20, 80)"
```

`policyLabel` is what appears in a captured trajectory's provenance. Set
it whenever you compare rules, or your recorded runs will not say which
was which.

### 4.2 …make a rule check the surface it was handed?

`observation[0]` is a promise the compiler cannot keep. Implement
`ShapeAwarePolicyIfc` and the element will hand you the descriptor once,
before the run, so a mismatch fails at setup instead of producing
plausible nonsense.

```kotlin
class CheckedOrderUpTo(private val s: Double, private val bigS: Double) : ShapeAwarePolicyIfc {

    private var positionIndex = 0

    override fun configure(surface: DecisionSurfaceDescriptor) {
        require(surface.levers.size == 1) {
            "OrderUpTo writes one quantity; this element declares ${surface.levers.size} levers."
        }
        positionIndex = surface.observations.indexOfFirst { it.unit == "units" }
        require(positionIndex >= 0) { "No observation is declared in units." }
    }

    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val position = observation[positionIndex]
        return if (position <= s) doubleArrayOf(bigS - position) else doubleArrayOf(0.0)
    }
}
```

### 4.3 …parameterize an experiment without editing the model?

Everything an experiment chooses is settable on the element. All of it is
**replication-initial**: set it before `simulate()`, and it is refused
while the model is running.

```kotlin
val qty = element.leverRef("OrderQty")
element.narrow(qty, 0..120)                 // the experiment's limits, inside the model's
element.maxEpochs = 100                     // cap the episode
val limits: IntRange = element.limitsOf(qty)
```

**Narrowing may only shrink.** The model's limits are a physical fact
and the experiment's are a choice, so `narrow` refuses anything wider
and leaves *both* bounds untouched when it does.

`maxEpochs` is also a `@KSLControl`, so `simopt` and
the experiment-running machinery can set them by name through the paths
they already use.

### 4.4 …find out what a model offers, in code?

Ask the element for its description. It is derived from the declaration
on demand — never authored, so it cannot go stale — and it holds no
reference to the model, so it can be written to a file and read
somewhere else.

```kotlin
val surface = element.descriptor()

for ((i, o) in surface.observations.withIndex()) {
    println("observation $i is ${o.name} in ${o.unit ?: "unstated units"}")
}
for ((i, l) in surface.levers.withIndex()) {
    println("action $i writes ${l.name}, a ${l.kind}, within ${l.lowerBound}..${l.upperBound}")
}

val json: String = surface.toJson()
val toml: String = surface.toToml()
```

Both codecs round-trip losslessly. TOML is the friendlier one to read
and hand-edit; JSON is the one for tooling. `DecisionSurfaceDescriptor.fromJson`
and `.fromToml` read them back, refusing a schema version they do not
understand and a file that describes a surface the DSL would not have
accepted.

Note that a lever carries **two** ranges: `modelLowerLimit`/
`modelUpperLimit` is the model's physical envelope, and
`lowerBound`/`upperBound` is what this experiment narrowed it to. A tool
that offers the wrong pair will propose values the run has excluded.

### 4.5 …record what the rule did?

Attach a sink. Two ways in: declare one in the element, shown here, or
attach one from outside to a model that is already built — see *Attaching
capture from outside the model*, below. A sink declared with `captureTo`
is made and closed for you, once per experiment.

```kotlin
val sink = MemorySink()
val element = parent.decisionElement("Captured") {
    observe("Level") { 1.0 }
    lever(parent, limits = 0..10, neutral = Neutral.Value(0.0)) { v -> }
    captureTo { provenance -> sink }        // called once per experiment
    policy = NeutralPolicy
}.reviewEvery(parent, 5.0)
// after model.simulate()
for (row: TransitionRecord in sink.records) {
    println("${row.epochIndex}: ${row.state.toList()} -> ${row.action.toList()} " +
        "reward ${row.reward} over ${row.tau}")
}
```

A row is a complete `(state, action, reward, successor)` transition with
the interval length `tau`, the termination flags, and — when the action
had to be repaired or a lever had nothing to choose from — what was
originally asked for. `terminated` and `truncated` are separate fields
on purpose: an episode that reached a real ending and one the run length
cut off are different things, and conflating them biases any learner
that bootstraps from the last row.

Write your own sink by implementing `TransitionSink`. Only `write` is
required; the two lifecycle methods are told when a run starts and stops,
and that is where a sink learns the **provenance** — which model, which
experiment, which rule, and the full description of the surface:

```kotlin
/** A sink of your own. Told when a run starts and stops; closed by whoever made it. */
class CountingSink : TransitionSink {
    var rows = 0
        private set
    override fun beginExperiment(provenance: RunProvenance) {
        println("recording ${provenance.elementName} under ${provenance.policyLabel}")
        rows = 0
    }
    override fun write(record: TransitionRecord) { rows++ }
    override fun endExperiment() { println("$rows rows this run") }
}
```

```kotlin
parent.decisionElement("Counted") {
    observe("Level") { 1.0 }
    lever(parent, limits = 0..10, neutral = Neutral.Value(0.0)) { v -> }
    captureTo { provenance -> CountingSink() }
    policy = NeutralPolicy
}.reviewEvery(parent, 5.0)
```

Provenance arrives once per **experiment** rather than once per sink,
because two of its fields change between runs of the same model: the
experiment name, and the policy label. Comparing k rules on one model
(§4.9) is exactly that case.

Capture is close to free when it is off — with no sink attached the
element does not even build the record — and below measurement
resolution when it is on. See §6.


#### Attaching capture from outside the model

`captureTo` writes the decision into the model, which is the wrong place
for it if you did not write the model, or if only some of your runs
should be recorded. Whether a run is recorded is a property of *the run*.

So a sink can also be attached to an element that is already built, from
`main()` or from a tool layer, and detached again:

```kotlin
val sink = MemorySink()
element.attachTransitionSink(sink)
model.simulate()
element.detachTransitionSink(sink)

println("${sink.records.size} transitions, and the model never mentioned capture")
```

Several sinks may be attached at once — a live view *and* a file — and
each receives every record, in attachment order. Detaching does not close
the sink: you made it, so you close it.

For a whole model there is a one-liner, which finds every decision
element and writes a trajectory per element per experiment:

```kotlin
DecisionCapture.toDirectory(model, outputDir).use {
    model.simulate()
}
```

`DecisionCapture` is the same shape as `AnimationCapture`: it installs on
construction and reverses everything on `close`, so the model is left
exactly as it was found. Give it a selector to record only some elements:

```kotlin
val capture = DecisionCapture(model) { element ->
    if (element.name in wanted) MemorySink() else null
}
capture.use { model.simulate() }
```

or a per-run factory, for a durable sink that should leave one artifact
per experiment:

```kotlin
DecisionCapture.rolling(model) { provenance ->
    TabularSink(provenance, outputDir.resolve(provenance.experimentName))
}.use {
    model.simulate()
}
```

**The one rule: not while the model is running.** Attaching or detaching
mid-run throws. A trajectory that begins in the middle of an episode has
no predecessor state for its first row, and a run that recorded half its
decisions is worse than one that recorded none. Before a run, or between
two runs, is fine — nothing is half-recorded there.


#### Keeping a trajectory after the run ends

`MemorySink` is a list, which is right for a test and wrong for a study.
`TabularSink` writes to disk instead:

```kotlin
parent.decisionElement("Recorded") {
    observe("Level") { 1.0 }
    lever(parent, limits = 0..10, neutral = Neutral.Value(0.0)) { v -> }
    // One file per experiment, named from the provenance, so a k-rule study does not
    // write over itself. The sink is opened and closed for you, per experiment.
    captureTo { provenance -> TabularSink(provenance, outputDir.resolve(provenance.experimentName)) }
    policy = NeutralPolicy
}.reviewEvery(parent, 5.0)
```

It leaves **two** files, and the second is not optional:

- `baseline.sqlite` — the rows. A `TabularOutputFile` *is* a SQLite
  database, so this opens in any SQL tool and reads from Python with no
  JVM. Columns are `s_*` for the state, `a_*` for the action applied,
  `p_*` for what the rule proposed, `sp_*` for the successor, plus
  `rep`, `epoch`, `time`, `tau`, `reward`, `repaired`, `terminated`,
  `truncated` and `source`.
- `baseline.provenance.json` — which model, experiment, element and
  policy produced the rows, **and the descriptor**.

**Why the second file exists.** A row is positional. `a_Mode = 2.0`
means nothing without the declaration saying that lever is
`CATEGORICAL` over `["slow", "normal", "fast"]`, that it is a `SETTING`
rather than a `TRANSACTION` — a fact about the dynamics, not
decoration — and where its bounds are. Column names carry
position-to-name and nothing else. Read a trajectory back with
`TrajectoryFile`, which pairs the two and **refuses** a trajectory whose
provenance is missing rather than guessing:

```kotlin
TrajectoryFile(rowsPath).use { trajectory ->
    println("${trajectory.rowCount} transitions written by '${trajectory.provenance.policyLabel}'")

    // What the columns cannot say, the descriptor can.
    val lever = trajectory.descriptor.levers[0]
    println("${lever.name} is a ${lever.domain} ${lever.kind} over ${lever.lowerBound}..${lever.upperBound}")
    lever.levels?.let { println("its values name: $it") }

    for (t: StoredTransition in trajectory.transitions()) {
        // t.state, t.action, t.reward, t.successorState, t.terminated, t.truncated
    }
}
```

Two practical notes. Column names are reduced to `[A-Za-z0-9_]`, because
`CREATE TABLE` will not take the colons KSL element names carry; two
declarations that reduce to the same column are refused at construction,
naming both. And nothing is stored as null — `p_*` and `unavail_*` are
always written, with `repaired` saying whether the proposal is news, so
there are no `NaN`s waiting in your training data.

### 4.6 …decide several things at once, under a constraint?

Declare several levers and a joint constraint over them. `budget` is
"must sum to exactly"; `atMost` is "may sum to at most".

```kotlin
val review = decisionElement("${this.name}:Shift") {
    observe("Demand") { 1.0 }
    val day = lever(this@Pools, limits = 0..8, unit = "staff",
        neutral = Neutral.Current { dayStaff.toDouble() }) { v -> setDay(v.toInt()) }
    val night = lever(this@Pools, limits = 0..8, unit = "staff",
        neutral = Neutral.Current { nightStaff.toDouble() }) { v -> setNight(v.toInt()) }
    budget(day, night, total = 8.0)          // the pair must sum to exactly 8
    batchLever(day, night) { values -> setBoth(values) }   // move both in one act
    policy = NeutralPolicy
}.reviewEvery(this, 480.0)
```

`lever(…)` returns a `LeverRef` — the lever's identity — and constraints
name levers rather than the elements they write, which is what lets two
levers write the same element.

**`batchLever` is the escape hatch, and you usually do not need it.**
The library writes multi-lever actions in a decrease-before-increase
order, so the common budgeted case stays feasible at every intermediate
step. What it does *not* promise is cross-lever atomicity: a pair moving
from (4, 2) to (3, 3) under `sum == 6` passes through (3, 2) between the
two writes, and if something in your model observes that instant it sees
a total of 5. When that matters, declare a batch and the group moves in
one call.

### 4.7 …write a rule that scores candidates?

Ask the context for the feasible set and hand it to a search.

```kotlin
class CheapestFeasible(private val search: ActionSearch = ExhaustiveSearch) : PolicyIfc {
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray {
        val best = search.best(ctx.actions) { candidate -> candidate.sum() }
        return best ?: ctx.neutralAction         // an empty feasible set is not an error
    }
}
```

`ExhaustiveSearch` walks the whole set and refuses (loudly) when it is
continuous or too large. `GridSearch` samples a regular grid per lever;
`SampledSearch` draws candidates at random and is the one to reach for
when a tight joint constraint makes the feasible set a thin slice of the
box.

```kotlin
fun chooseASearch(): ActionSearch = GridSearch(pointsPerLever = 9)
```

**A search is the expensive part of this package by two orders of
magnitude** (§6). Its cost tracks the number of candidates the
enumeration has to *test*, which under a joint constraint is the whole
box rather than the feasible slice.

An **empty feasible set is not an error**. Every lever takes its declared
neutral, the row records which levers had nothing to choose from, and the
run continues. A model whose constraint is momentarily unsatisfiable is a
model in a tight spot, not a broken one.

### 4.8 …write a rule that owns a resource?

Implement `ManagedPolicyIfc` and the element will call the lifecycle
hooks for you.

```kotlin
class LoggingRule(private val path: String) : ManagedPolicyIfc {
    override fun beforeExperiment() { println("opening $path") }
    override fun beforeEpisode(episodeIndex: Int) { }
    override fun onTransition(record: TransitionRecord) { }
    override fun afterEpisode(episodeIndex: Int, source: TerminationSource) { }
    override fun action(observation: DoubleArray, ctx: DecisionContext): DoubleArray =
        ctx.neutralAction
    override fun afterExperiment() { }
    override fun close() { println("closing $path") }
}
```

`onTransition` is where an adaptive rule learns: it is handed the same
complete transition a sink receives.

**The element closes what the element opened.** It opened the sink, so it
closes the sink. It did not open your policy, so it does *not* close it
at the end of an experiment — which is what lets you simulate the same
model twice. A policy is closed only when it is *replaced*.

### 4.9 …compare two rules?

There is no comparison API here, because KSL already has one. Run the
same model under each rule and compare the objective the way you compare
any other KSL response.

```kotlin
val results = mutableMapOf<String, Double>()
for (rule in listOf<Pair<String, PolicyIfc>>(
    "do nothing" to NeutralPolicy,
    "(20, 80)" to OrderUpTo(20.0, 80.0)
)) {
    val (model, element) = build()
    element.policy = rule.second
    element.policyLabel = rule.first
    model.numberOfReplications = 30
    model.lengthOfReplication = 500.0
    model.simulate()
    results[rule.first] = element.estimand.acrossReplicationStatistic.average
}
println(results)
```

For a real comparison use `MultipleComparisonAnalyzer` over the
replication data, exactly as you would for any set of alternatives.
**Always include the do-nothing arm.** A rule that reads well and loses
to `NeutralPolicy` is the defect this package exists to make visible.

### 4.10 …score on several things at once?

Declare several `reward` terms. They compose into one estimand, and **you never write a minus
sign**: each rate is a positive number in the units you think in, and `sense` says which way it
pushes. A `COST` is negated once, at declaration, so nothing downstream — not your rule, not the
captured trajectory, not a comparison — has to track signs.

```kotlin
    val review = decisionElement("${this.name}:Shift") {
        observe(queue)
        val staff = lever(this@Clinic, limits = 0..8,
            neutral = Neutral.Current { capacity.toDouble() }) { v -> capacity = v.toInt() }
        // Every rate is a positive number in the units you think in. `sense` carries the direction.
        reward(treated, rate = 25.0, sense = RewardSense.REWARD, alias = "Revenue")
        reward(queue, rate = 10.0, sense = RewardSense.COST, alias = "Waiting")
        policy = NeutralPolicy
    }.reviewEvery(this, 480.0)
```

The published estimand is a profit, so **larger is better**. That convention holds for every
element, whatever mix of terms it declares.

Two things worth knowing. A term whose rate is zero stays in the description and still reports —
dropping it would make the estimand's meaning depend on a value rather than on a declaration. And
the *descriptor* reports each rate as you wrote it, not signed, so a tool that echoes it back into a
configuration file cannot flip a sign a second time.

### 4.11 …train a rule off-line from captured data?

This is what capture is *for*, and it is three steps: **explore**,
**learn**, **evaluate**. `OfflineTrainingDemo` runs all three; the shape
is worth seeing whole.

**Explore.** Run under a rule that varies what it does, so the file
covers the decision space rather than one rule's habits. For an
order-up-to family, vary the *target*:

> Get this wrong and the learner will tell you. Drawing a random order
> *quantity* on 0..120, against demand of five per epoch, ordered twelve
> times what the model consumed — inventory ran away, post-decision
> positions spread over thousands of units, and the fit refused with
> *"no bucket had 20 or more rows out of 7960 transitions."* That is the
> right refusal on the wrong data.

**Learn.** The learner's whole input is a path. No `Model`, no element,
not even the JVM that produced the data:

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

**Evaluate.** Put the fitted rule back in the simulator and score it
against the do-nothing arm, exactly as in §4.9. Measured, 30
replications per arm:

| rule | profit | half-width |
|---|---|---|
| do nothing | −9,479,766 | 95,716 |
| learned(8) | **−5,457** | 121 |
| hand-tuned (5, 15) | −7,093 | 95 |

Two things that bit during this and will bite you:

**Ask the descriptor what a legal action is.** The first version of that
learner returned a bucket midpoint of 7.5, the rule ordered
`7.5 − position` against an INTEGER lever, and the model refused the
epoch outright — *"'OrderQty' = 0.5 units is not integral"*. That
refusal is the design working, and it is the reason the domain has to
travel with the rows. Round in `configure`, where the surface is handed
to you.

**Off-policy evaluation is the hard part, and this dodges it.** The
learner above works because exploration covered the action space. Fitting
from data generated by one good rule is a genuinely harder problem and
this package does not solve it for you — it gives you the transitions.

### 4.12 …hand a rule's parameters to `simopt`?

Write the rule as a `ModelElement` with `@set:KSLControl` properties, the
way KSL's own inventory policies are written. The model's control walk
finds them and `simopt` drives them through the flat inputs map it
already uses — no adapter, and nothing in this package involved.

Parameterize so the optimizer sees a **box**. A numeric control clamps
silently, so a pair like *(s, S)* with `S > s` should be declared as
*(s, sDelta ≥ 0)* with `S = s + sDelta`: every clamped combination is
then feasible by construction. This is the same reparameterization KSL
uses for `(r, S)` inventory policies.

### 4.13 …review whenever the system changes, rather than on a period?

Call `decide` where the state changes. There is no trigger construct, no
condition to declare, and nothing to switch on.

```kotlin
private fun demandArrives(event: KSLEvent<Nothing>) {
    applyDemand(demandSize.value)
    decisions.decide("demand")
}
```

**Note what is not there: a threshold.** The model does not test whether
the position has fallen past the reorder point. It says the position
moved, and the *rule* decides whether that warrants an order — declining
by ordering nothing, which is a declared act (`Neutral.Value(0.0)`), not
an abstention. The reorder point stays in the policy, which is where a
parameter of the rule belongs, and where `simopt` can search it.

This is continuous review, and it is worth knowing what it costs.
Measured on `ksl.examples.decision.SsInventory`, which carries both
wirings, over three replications of 5 000 time units with unit demand at
rate 1 and a five-unit review period:

| Wiring | epochs / replication | cost | wall clock |
|---|---|---|---|
| review every 5.0 | 1 000 | 11.92 | 3 ms |
| review on every demand | 4 845 | 11.74 | 9 ms |

Reviewing on every change costs about five times the epochs and, on this
model — where decisions are a large share of the work — three times the
wall clock. It is also **cheaper**, and must be: it sees every crossing
the periodic arm sees and some it misses. Whether that trade is worth it
is a modelling judgement, and the point is that you can simply make it.

Two consequences worth expecting. Your trajectory grows in proportion,
and most rows will record a decision that declined to act — which is
signal for anything you fit later, not waste, since a trajectory of
crossings only never shows the states where the rule correctly did
nothing. And the review happens wherever you put the call, so §6's
obligation applies with full force: finish the update, then decide.

## 5. The key types at a glance

| Type | What it is |
|---|---|
| `DecisionElement` | The `ModelElement` that runs the loop. Built by `decisionElement { }`; carries the parameterization surface |
| `DecisionElementBuilder` | The DSL receiver: `observe`, `lever`, `reward`, `budget`/`atMost`, `batchLever`, `maxEpochs`, `terminalWhen`, `captureTo`, `policy` |
| `DecisionElement.decide` / `.requestDecision` | How a decision happens: you call one of them (§2.1) |
| `PeriodicDecisionElement` | A decision element **and** the event that reviews it, in one construction. What most models want (§2.1) |
| `PolicyIfc` | Your rule. `action(observation, ctx): DoubleArray` |
| `ShapeAwarePolicyIfc` | A rule that is shown the descriptor once, before the run, and may refuse |
| `ManagedPolicyIfc` | A rule with a lifetime and per-transition learning hooks |
| `NeutralPolicy` / `FixedPolicy` | The do-nothing arm, and a constant action |
| `LookaheadPolicy` | Template for score-and-pick rules: contribution, post-decision state, value |
| `DecisionContext` | What a rule may know at a decision instant — time, epoch index, the feasible set, the neutral action. **Do not retain it**; it throws if read outside its own call |
| `ActionSet` | 𝒳(*s*) as an object: `contains`, `violations`, `asSequence`, `sample` |
| `ActionSearch` | `ExhaustiveSearch`, `GridSearch`, `SampledSearch` |
| `Neutral.Current` / `Neutral.Value` | Doing nothing, for a setting and for a transaction |
| `LeverRef` / `RewardRef` | Identities returned by declaration, consumed by constraints and parameterization |
| `TransitionRecord` | One complete transition: state, action, reward, successor, termination |
| `TransitionSink` | Write-only consumer with a per-run lifetime. `MemorySink`, `NullSink` in `ksl.modeling.decision.capture` |
| `DecisionElement.attachTransitionSink` | Records an element from outside the model; `detachTransitionSink` stops it (§4.5) |
| `DecisionCapture` | Attaches capture to a whole built model and reverses it on `close` (§4.5) |
| `RollingSink` | Wraps a per-experiment factory, so an attached sink still leaves one artifact per run. In `ksl.modeling.decision`, with the contract — it decorates a sink rather than being a destination |
| `TabularSink` | A durable sink: rows to a SQLite file, provenance beside it (§4.5) |
| `TrajectoryFile` | Reads a trajectory back with no live `Model`; refuses one whose provenance is missing |
| `StoredTransition` | One transition as read back — state, action, reward, successor, flags |
| `DecisionSurfaceDescriptor` | The serializable description; `toJson`/`fromJson`, `toToml`/`fromToml` |

## 6. Gotchas & best practices

**Finish the update, then decide.** You choose where `decide` is
called, and the model is *not* guaranteed to be between events there —
it is wherever your code is. Calling it partway through your own update
hands the rule a state no observer of the finished system would ever
see, and that state is also written into the trajectory, where it will
train whatever you fit later. The failure is quiet: on-hand decremented,
inventory position not yet recomputed, the rule reads the stale position
and declines to order — every time, on every crossing.

There is nothing the library can check here. It cannot know when your
update is finished. Three habits cover it:

- **Call last.** Where the decision belongs to a handler that changes
  state, put the call after the last line of the update.
- **Prefer a reviewer.** A permanent entity that wakes, looks and calls
  is not the thing that changed anything, so there is no half-finished
  update to be inside of.
- **When in doubt, defer.** `requestDecision(reason)` moves the epoch
  into its own event, so the model is between events when the state is
  read. Weaker than it sounds — a zero-delay event lands at the current
  time later in the event order, not at the end of the instant — but
  stronger than any promise a call site can make.

Keep the position **fixed**: whatever point in the handler you choose,
use it at every call site for that element. A trajectory half of whose
rows were read before the demand and half after has a state column that
means two different things, and nothing downstream can separate them.
`ksl.examples.decision.CallSiteExamples` works all of this as running
code, including what each mistake actually costs.

**A rule may not schedule events.** A `PolicyIfc` reads the model and
returns an action; it may not change the model, and scheduling is
changing it. Whatever it scheduled would land in an interval no
transition attributes, and the run would stop being reproducible from
its declared inputs. This is checked, not merely asked for: a rule that
schedules during the call gets a `PolicyScheduledEventException`. If a
rule wants the model to do something, that is what a lever is for —
declared, validated, applied in a defined order, and recorded.

**Two `decide` calls at one instant cost you a row.** They are allowed,
and sometimes right — two demands arriving together, each triggering a
review, is a correct model. But the interval between them has no
duration, and a row with no duration carries no information, so it is
discarded: the earlier decision's action is applied and never recorded.
`element.discardedZeroLengthCount` counts it, so the loss is visible
rather than silent. `requestDecision` does not have this problem —
several requests at one instant become one decision naming them all.

**A decision as a consequence of a decision.** A lever's write function
is your code, so it can reach back into `decide` — and that is refused,
because the nested decision would be applied to the model and never
recorded. Use `requestDecision` instead: it only schedules, so it
cannot re-enter.

It is **re-entrancy-safe and not termination-safe**, and the difference
matters. A write that *always* asks for another decision asks forever,
all at the same instant, because a zero-delay event lands at the current
time. The guard is yours to write — but you will be told: asking for a
decision during the decision that answered you, over and over with the
clock standing still, is refused with a
`RunawayDecisionRequestException` naming what is still pending.

**Declare a setting as a setting and a transaction as a transaction.**
This is the mistake that costs the most and announces itself the least.
Declaring an order quantity as a `Neutral.Current { lastOrderQuantity }`
compiles, binds, and quietly re-orders last period's amount every time
your rule "does nothing". If there is no meaningful *current value*, it
is a transaction and its neutral is a number.

**Declaration order is vector order.** Reordering two `observe` calls
silently changes what `observation[0]` means. If a rule needs a
particular quantity, use `ShapeAwarePolicyIfc` (§4.2) rather than a
comment.

**Qualify the element's name.** `decisionElement("Review")` inside a
reusable subsystem makes that subsystem single-use.

**Units are carried, not checked.** The library cannot know that a
quantity you declared in `"jobs"` is really in server-units. What it
does with a unit is refuse to sum levers measured in different things,
name it in violation messages, and expose it to a rule that wants to
check. That is worth declaring them for.

**Start every study with the do-nothing arm.** `NeutralPolicy` is
guaranteed to be transparent: an element under it reorders no event and
consumes no randomness, so any difference you see is your rule's.

**Two elements deciding at the same instant.** They run in
`epochPriority` order, smaller first. If both take the default priority,
the tie is broken by the order in which their *owning model elements*
were constructed — deterministic and reproducible, but not something to
rely on. If one must act first, say so with a priority.

**Overhead, measured.** On a 4-processor Linux/JVM 21 machine, a decision
epoch costs roughly **1.3–2.3 ordinary events** (about 320–620 ns);
capture is below measurement resolution; and a candidate-scoring rule
costs roughly **200 ns per action the enumeration tests** — which is
73–210 events per epoch for the models measured. The epoch loop is cheap
and the search is not. If a scoring rule is too slow, change the search
strategy (§4.7) before changing anything else.

**Everything you can set is replication-initial.** Parameterization
setters refuse while the model is running, and a refused one changes
nothing at all.

## 7. See also

- [`ksl-simulation`](ksl-simulation.md) — `Model`, `ModelElement`, the
  lifecycle phases a decision element participates in.
- [`ksl-modeling`](ksl-modeling.md) — `Response`, `TWResponse`,
  `Counter`: what observations read and rewards accumulate from.
- [`ksl-decision-tutorial`](ksl-decision-tutorial.md) — the
  hands-on tutorial for this package: four worked models, the
  off-line training round trip, and the `simopt` handoff. Start there
  if you are new to it; this guide is the reference you graduate to.
- [`ksl-simopt-tutorial`](ksl-simopt-tutorial.md) — start here for
  searching over a rule's parameters (§4.12).
- [`ksl-controls`](ksl-controls.md) — how `@KSLControl` properties are
  found and set, including the clamping behavior §4.12 warns about.
- [`ksl-supplychain`](ksl-supplychain.md) — KSL's inventory policies,
  which are the model this package's `simopt` seam follows.
- `KSLExamples` — split in two, along the line between something you
  might build on and something you run once to see this guide happen.
  - `ksl.examples.decision` holds the **worked models**: a clinic
    staffing decision scored on a mixed-sense profit, an (*s*, *S*)
    inventory, a multi-lever shipment allocation under a joint
    constraint, and a value-function rule.
  - `ksl.examples.decision.tutorial` holds the **runnable
    walkthroughs** this guide points at — `DecisionGuideDemo` for §3–§4
    and `OfflineTrainingDemo` for §4.11. Run either one's `main` and
    read the output beside the section it belongs to. Their fixtures
    (`StockRoom`, `OrderUpTo`) are there to be read, not reused.
