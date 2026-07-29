# Tutorial: Simulation Optimization with the KSL

A hands-on, step-by-step introduction to the KSL's simulation-optimization framework (`ksl.simopt`), written for users who have seen a little simulation and a little optimization but have never used the KSL. By the end you will have set up, run, and *benchmarked* three complete simulation-optimization problems, of the two different kinds the framework handles.

> **This is a tutorial, not a reference.** It teaches the workflow by building concrete examples slowly. Two companion guides are the reference manuals you graduate to when you want the full parameter surface and methodology:
>
> - `ksl-simopt` — every solver, every knob, the evaluator and cache internals, the concurrency model.
> - `ksl-simopt-benchmark` — the benchmarking engine, the five fairness policies, and the complete results-database schema.
>
> The [**KSL Book**](https://rossetti.github.io/KSLBook/)**, Chapter 11** covers the underlying theory. This tutorial does not replace any of those — it gets you to the point where they make sense.

Every code example here is a real, compiled, runnable file. They live under `KSLExamples`, in the package `ksl.examples.general.simopt.tutorial`, and are listed in [Appendix A](#appendix-a--the-companion-files-and-how-to-run-them). If you have the KSL open in IntelliJ IDEA, you can run any of them by clicking the green run arrow next to its `main` function.

**A note on style.** To keep the focus on the ideas and not on Kotlin, this tutorial deliberately writes everything with ordinary **named classes** and **named functions**. It avoids lambdas, anonymous classes, and other compact functional syntax. The framework itself allows terser code (and the shipped examples in `KSLExamples` use it), but named pieces are easier to read, find, and reuse while you are learning.

---

## Table of contents

- [Part I — What simulation optimization is, and how the KSL frames it](#part-i--what-simulation-optimization-is-and-how-the-ksl-frames-it)
- [Part II — Example 1: a noisy mathematical function (Type 1)](#part-ii--example-1-a-noisy-mathematical-function-type-1)
- [Part III — Example 2: a discrete-event simulation model (Type 2)](#part-iii--example-2-a-discrete-event-simulation-model-type-2)
- [Part IV — Example 3: a genuine Monte Carlo model, maximized (Type 1)](#part-iv--example-3-a-genuine-monte-carlo-model-maximized-type-1)
- [Part V — Bringing it together: a fair benchmark study](#part-v--bringing-it-together-a-fair-benchmark-study)
- [Part VI — Variations, pitfalls, and where to go next](#part-vi--variations-pitfalls-and-where-to-go-next)
- [Appendix A — the companion files and how to run them](#appendix-a--the-companion-files-and-how-to-run-them)
- [Appendix B — glossary](#appendix-b--glossary)

---

## Part I — What simulation optimization is, and how the KSL frames it

### 1.1 Overview of Simulation Optimization

You have a system with some *settings you control* — the order quantity in an inventory policy, the number of tellers at a bank, the buffer size in a factory. You have a *performance measure* you care about — average cost, expected profit, a service level. And you have a *simulation* that, given a choice of settings, estimates that performance measure. **Simulation optimization** is the search for the settings that make the expected performance as good as possible. The catch — the thing that makes it its own subject — is that the simulation is *random*, so every performance number you get is only an *estimate*.

### 1.2 The general problem, in mathematical notation

Write the settings you control as a vector **x** = (x₁, x₂, …, x_d). Each evaluation of the simulation at **x** produces a random output H(**x**, ξ), where ξ stands for all the randomness in one run. You cannot see the true mean directly; you only get noisy samples of it. The problem is:

```
    minimize (or maximize)   g(x) = E[ H(x, ξ) ]
       over x

    subject to
       E[ G_j(x, ξ) ] ≤ c_j       for each response constraint j     (probabilistic)
       A x ≤ b                     linear constraints on the inputs   (deterministic)
       d_k(x) ≤ 0                  functional constraints on inputs   (deterministic)
       x ∈ X                       ranges, and a granularity per input
```

The objective g(**x**) = E\[H(**x**, ξ)\] is the *expected* value of the simulation response. Because H is random, we estimate g(**x**) with a sample average over n independent replications:

```
    ĝ_n(x) = (1/n) · Σ H(x, ξ_i)        i = 1 … n
```

The more replications n, the tighter the estimate — and the more computer time it costs. That trade-off is the heartbeat of everything below.

There are two flavors of constraint, and the framework treats them differently:

- **Deterministic constraints** (`A x ≤ b`, `d_k(x) ≤ 0`, and the input ranges) depend only on **x**, so they can be checked *before* running any simulation.
- **Probabilistic / response constraints** (`E[G_j(x)] ≤ c_j`) depend on random simulation outputs, so they can only be *estimated*, and tested statistically.

### 1.3 Why "noisy" changes everything

If g(**x**) were a formula you could evaluate exactly, this would be ordinary optimization. Noise breaks three things you would otherwise take for granted:

1. **You cannot trust a single number.** The point that *looks* best might just be the one that got the luckiest draw. The cure is replications, confidence intervals, and — when picking a final answer — statistical *best selection*.
2. **Comparisons are fragile.** To tell whether **x** beats **x'**, it helps to evaluate them under the *same* random numbers (common random numbers, CRN), so the comparison isn't swamped by luck.
3. **Effort is a currency.** Every replication costs time. Good methods reuse past work (caching), spend replications where they matter, and know when to stop.

The KSL handles all of this for you. Your job is to *describe* the problem and *pick* a search method; the framework manages the replications, the penalties for violating constraints, the caching, the CRN, and the honest selection of a winner.

### 1.4 Two kinds of problems, one framework

Within simulation optimization there are two very different kinds of "simulation," and this tutorial covers both because setting them up is genuinely different:

|  | **Type 1 — noisy function / static Monte Carlo** | **Type 2 — discrete-event model (DEDS)** |
| --- | --- | --- |
| What the "model" is | a plain function you write, `H(x)` | a full KSL simulation `Model` (entities, events, resources) |
| Examples in this tutorial | noisy Rosenbrock (Part II), newsvendor (Part IV) | (r, Q) inventory system (Part III) |
| What one *replication* is | one evaluation of the function | one simulation run (after a warm-up period) |
| How you build it | a `ResponseFunctionBuilderIfc` | a `ModelBuilderIfc` |
| Cost of one replication | usually microseconds | milliseconds to seconds |
| The "oracle" underneath | `ResponseFunctionOracle` | `SimulationProvider` |

The beautiful part: **everything above the "oracle" line is identical for both**.The same solvers, the same caching, the same benchmarking harness drive both a one-line math function and a thousand-line discrete-event model.

### 1.5 How the KSL is organized

The framework is four building blocks:

| Block | Package | What it is |
| --- | --- | --- |
| **Problem** | `ksl.simopt.problem` | A `ProblemDefinition`: the objective, the decision variables (name, range, granularity), and any constraints. |
| **Evaluator + oracle** | `ksl.simopt.evaluator` | Turns a request "evaluate these points" into scored `Solution`s — running the model (or the function), averaging replications, caching, and applying CRN. |
| **Solver** | `ksl.simopt.solvers` | The search algorithm: hill climbing, simulated annealing, cross-entropy, R-SPLINE, and more. |
| **Cache** | `ksl.simopt.cache` | Remembers points already evaluated so they are not re-simulated. |

They interact in a loop. Each iteration, the solver proposes one or more candidate points; the evaluator scores them (using the cache and the oracle); the solver updates the best it has seen and decides where to look next:

```
   Solver.runAllIterations()
        |   pick candidate point(s)  x
        v
   Evaluator  --- cache hit? ---> reuse the stored Solution
        |  cache miss
        v
   Oracle.simulate(x)      (a ResponseFunctionOracle for Type 1,
        |                    a SimulationProvider for Type 2)
        v
   Solution:  x + estimated objective + response estimates + feasibility
        |
        v
   Solver updates its best solution, then chooses the next point
```

The line marked **oracle** is the seam that unifies the two problem types. A solver never knows whether it is optimizing a math function or a supply chain — it only talks to an evaluator.

### 1.6 A few words you will need

- **Decision variable / input.** One thing you control, e.g. `x1` or `reorderPoint`. It has a **range** `[lower, upper]` and a **granularity**.
- **Granularity.** The step size of a decision variable. `granularity = 1.0`makes a variable **integer-ordered** (it can only take whole-number values), which some solvers (R-SPLINE, COMPASS, ISC) require.
- **Objective response.** The named simulation output you are optimizing.
- **Response name.** The name of any simulation output the problem refers to (the objective, or a response used in a constraint).
- **Replication.** One statistical observation of the outputs at a point.
- **Penalized objective.** The objective plus a penalty for any constraint violation. Solvers search on this internally so constraints *bend* the search rather than hard-rejecting points.
- `bestSolution`**.** The single recommended answer, chosen *feasibility-first*and screened statistically — not merely the smallest raw number seen.

### 1.7 The recipe every example follows

Each of the three examples below walks the same five steps. Learn the shape once and the rest is repetition:

1. **State the mathematical problem** — what is minimized/maximized, over what, subject to what.
2. **Build the "model"** — a response function (Type 1) or a DEDS `Model`(Type 2).
3. **Instrument it** — describe the problem to the framework: name the objective, declare the decision variables and their ranges, add any constraints.
4. **Run the optimization pipeline** — assemble oracle → evaluator → solver and run it once; read the result.
5. **Wrap it as a benchmark case** — package the problem so the benchmark harness can compare several solvers on it fairly.

---

## Part II — Example 1: a noisy mathematical function (Type 1)

Our first problem is a classic optimization test function observed through noise. It is a *Type 1* problem: the "simulation" is a short formula plus a random term.

Companion files: `RosenbrockSetup.kt`, `RosenbrockOptimizationExample.kt`, `RosenbrockBenchmarkExample.kt`.

### 2.1 Step 1 — the mathematical problem

The **Rosenbrock function** in two dimensions is

```
    f(x1, x2) = 100 · (x2 - x1²)² + (1 - x1)²
```

Its minimum value is 0, at the point (x1, x2) = (1, 1). It is famous for a long, curved, nearly flat valley that makes simple search methods crawl.

We never observe f directly. Each evaluation returns f plus Gaussian noise with standard deviation 1:

```
    H(x1, x2) = f(x1, x2) + ε,     ε ~ Normal(mean 0, variance 1)
```

So the problem is

```
    minimize   g(x1, x2) = E[ H(x1, x2) ] = f(x1, x2)
       over    x1, x2 ∈ {-5, -4, …, 9, 10}   (integer lattice)
```

We put the variables on the integer lattice (whole numbers from −5 to 10). That is a modeling choice — it keeps the example small and lets integer-ordered solvers such as R-SPLINE participate.

### 2.2 Step 2 — build the "model": a response function

For a Type 1 problem, the "model" is an object that produces **one noisy observation** per call. You implement the framework's `ResponseFunctionIfc`interface — a class with a single `replication` method that takes the input point and returns the responses as a map.

The one rule that matters: **acquire every source of randomness in the constructor, never inside** `replication`**.** The framework positions random-number streams before each evaluation to make common random numbers and reproducibility work; a stream created mid-evaluation would silently break that.

```kotlin
class NoisyRosenbrockResponse(
    streamProvider: RNStreamProviderIfc
) : ResponseFunctionIfc {

    // mean 0, variance 1 (standard deviation 1), on stream number 1.
    // Acquired ONCE here, in the constructor.
    private val noise: NormalRV = NormalRV(
        mean = 0.0,
        variance = 1.0,
        streamNum = 1,
        streamProvider = streamProvider
    )

    override fun replication(inputs: Map<String, Double>): Map<String, Double> {
        val x1: Double = inputs.getValue("x1")
        val x2: Double = inputs.getValue("x2")
        val a: Double = x2 - x1 * x1
        val b: Double = 1.0 - x1
        val trueValue: Double = 100.0 * a * a + b * b
        val observed: Double = trueValue + noise.value
        return mapOf("objFn" to observed)
    }
}
```

The framework sometimes needs a *fresh* response function (for example, one per worker when running a benchmark). So we also write a tiny **builder** that makes one on demand, against whatever stream provider it is handed:

```kotlin
class NoisyRosenbrockResponseBuilder : ResponseFunctionBuilderIfc {
    override fun build(streamProvider: RNStreamProviderIfc): ResponseFunctionIfc {
        return NoisyRosenbrockResponse(streamProvider)
    }
}
```

### 2.3 Step 3 — instrument it: the problem definition

The `ProblemDefinition` tells the framework *what* to optimize: the objective response name, the decision-variable names, their ranges, and their granularity. For a Type 1 problem the `modelIdentifier` is just a label we pick (it plays the role a model's name plays for a DEDS problem).

```kotlin
fun makeRosenbrockProblem(): ProblemDefinition {
    val problem = ProblemDefinition(
        problemName = "Rosenbrock2D",
        modelIdentifier = "noisyRosenbrock2D",
        objFnResponseName = "objFn",
        inputNames = listOf("x1", "x2")
    )
    problem.inputVariable(name = "x1", lowerBound = -5.0, upperBound = 10.0, granularity = 1.0)
    problem.inputVariable(name = "x2", lowerBound = -5.0, upperBound = 10.0, granularity = 1.0)
    return problem
}
```

Two things to notice. The objective name `"objFn"` matches the key our response function returns. And `granularity = 1.0` on each variable is what makes the problem integer-ordered.

### 2.4 Step 4 — run the optimization pipeline

Now we assemble the pipeline by hand — oracle, then evaluator, then solver — so you can see each layer. (In Part III you will see the shortcut the framework provides for models; for a response function, doing it by hand is clear enough.)

```kotlin
fun main() {
    // 1. The problem.
    val problem: ProblemDefinition = makeRosenbrockProblem()

    // 2. The oracle: for a Type 1 problem, a ResponseFunctionOracle over the
    //    builder. Its model identifier must match the problem's.
    val oracle = ResponseFunctionOracle(
        modelIdentifier = "noisyRosenbrock2D",
        responseNames = setOf("objFn"),
        responseFunctionBuilder = NoisyRosenbrockResponseBuilder()
    )

    // 3. The evaluator, with a solution cache so repeated points are not redone.
    val evaluator = Evaluator(problem, oracle, MemorySolutionCache())

    // 4. The solver: a simple stochastic hill climber. Each iteration it tries a
    //    random neighbor and keeps it only if it is better. 50 replications per
    //    point average out enough noise to compare points fairly.
    val solver = StochasticHillClimber(
        problem,
        evaluator,
        maximumIterations = 100,
        replicationsPerEvaluation = 50
    )

    // Optional: print progress each iteration.
    ConsoleSolverStateTracker(solver).startTracking()

    // 5. Run to completion (this blocks until the solver stops).
    solver.runAllIterations()

    println()
    println("Best solution found (true optimum is x1=1, x2=1 with objective 0):")
    println(solver.bestSolution.asString())
}
```

Running it prints a progress line per iteration and then the result. On one run it finished like this:

```
>>> SOLVER COMPLETED: Run finished successfully.

Best solution found (true optimum is x1=1, x2=1 with objective 0):
id = 6 : n = 50.0 : objFnc = 1.160 : 95%ci = [0.908, 1.412] : inputs : 0.0, 0.0
```

**Read that honestly.** The hill climber stopped at (0, 0), where the true objective is f(0,0) = 1.0 — *not* at the true optimum (1, 1) where it is 0. This is not a bug; it is the lesson. A greedy, single-trajectory search on Rosenbrock's curved valley gets stuck, and on a noisy problem one run of one simple method is never the final word. Two responses to that are coming:

- In Part II Step 5 we *benchmark* several solvers and let a confirmation stage pick the winner — and it recovers the true optimum.
- Swapping to a stronger solver is a small change. Because the problem is integer-ordered, R-SPLINE works over the same evaluator (its replication count grows across iterations, so it takes a growth schedule instead of a fixed number):

```kotlin
// Same problem and evaluator; a different, integer-ordered search algorithm.
val solver = RSplineSolver(
    problem, evaluator,
    replicationsPerEvaluation = FixedGrowthRateReplicationSchedule(initialNumReps = 8)
)
```

### 2.5 Step 5 — wrap it as a benchmark case

A single run tells you little. The benchmark harness runs many solvers, from common starting points, on an equal replication budget, and picks a winner with a statistical confirmation stage. To hand a problem to that harness you wrap it in a `ProblemCase`.

A `ProblemCase` bundles three things: a way to build a fresh **problem definition**, a way to build a fresh **evaluator**, and (when the optimum is known) a **reference solution** for measuring gaps. We write each as a named function and hand the harness the function's *name* with `::`.

The evaluator part needs a word of explanation. A benchmark isn't one run — it's many, often several at once (different solvers, repeated trials). Each such run is called a **member**, and each member must get its *own* private evaluation resources — its own oracle, its own cache, and its own separate slice of the random-number stream — so runs can't interfere and the study stays reproducible. So you don't hand the harness a single evaluator; you hand it a **member-evaluator factory**, which it calls to mint a fresh, isolated evaluator per member. You rarely write one yourself: the KSL ships one per problem type — `FunctionMemberEvaluatorFactory` for a Type 1 (response-function) problem, and `PooledMemberEvaluatorFactory` for a Type 2 (model) problem (Part III). Both implement `MemberEvaluatorFactoryIfc`.

```kotlin
fun makeRosenbrockEvaluatorFactory(problem: ProblemDefinition): MemberEvaluatorFactoryIfc {
    return FunctionMemberEvaluatorFactory(
        problem,
        NoisyRosenbrockResponseBuilder(),
        microRepSampleSize = 1     // one raw evaluation per observation
    )
}

fun rosenbrockReference(): ReferenceSolution {
    return ReferenceSolution(
        inputs = mapOf("x1" to 1.0, "x2" to 1.0),
        objectiveValue = 0.0,
        type = ReferenceType.KNOWN_OPTIMUM
    )
}

fun makeRosenbrockProblemCase(): ProblemCase {
    return ProblemCase(
        name = "Rosenbrock2D",
        problemDefinitionFactory = ::makeRosenbrockProblem,
        evaluatorFactoryProvider = ::makeRosenbrockEvaluatorFactory,
        referenceSolution = rosenbrockReference(),
        tags = mapOf("family" to "noisyRosenbrock", "dimension" to "2")
    )
}
```

`FunctionMemberEvaluatorFactory` is the Type 1 helper: it gives each concurrent run its own private oracle over a fresh response function, on its own block of random-number streams. (Part III uses the Type 2 counterpart.)

Now run a small study — one problem, the standard set of solvers, three macro-replications, an equal budget of 1000 replications each:

```kotlin
fun main() {
    val experiment = BenchmarkExperiment(
        name = "RosenbrockTutorial",
        problems = listOf(makeRosenbrockProblemCase()),
        solverCases = standardSolverCases(),
        macroReplications = 3,
        replicationBudgetPerRun = 1000,
        verificationReplications = 100
    )
    val summary = experiment.run()

    val db = BenchmarkResultsDb("rosenbrockTutorial.db", KSL.dbDir)
    val expId = db.saveSummary(summary)

    for (problemResult in summary.problemResults) {
        println("Problem '${problemResult.problemName}' (gap basis: ${problemResult.gapType}):")
        for (run in problemResult.runs) {
            println("   ${run.cellLabel}: best = ${run.bestObjective}, gap = ${run.gap}, " +
                    "consumed = ${run.numReplicationsRequested} replications")
        }
        println("   winner inputs = ${problemResult.winner?.inputMap}")
    }
}
```

`standardSolverCases()` is a ready-made list of five solver configurations (hill climbing, simulated annealing, cross-entropy, R-SPLINE, and random-restart hill climbing) at their library defaults. An actual run produced (trimmed):

```
Problem 'Rosenbrock2D' (gap basis: KNOWN_OPTIMUM):
   Rosenbrock2D_SHC_r1:      best = 1.436,   gap = 1.436,   consumed = 1020 replications
   Rosenbrock2D_SHC_r3:      best = 9.317,   gap = 9.317,   consumed = 1020 replications
   Rosenbrock2D_SA_r1:       best = 0.692,   gap = 0.692,   consumed = 1020 replications
   Rosenbrock2D_CE_r1:       best = 0.023,   gap = 0.023,   consumed = 1710 replications
   Rosenbrock2D_CE_r2:       best = -0.078,  gap = -0.078,  consumed = 1650 replications
   Rosenbrock2D_RSPLINE_r1:  best = -0.012,  gap = -0.012,  consumed = 1080 replications
   Rosenbrock2D_RestartSHC_r1: best = 0.718, gap = 0.718,   consumed = 2280 replications
   winner inputs = (x1, x2)=(1.000, 1.000)
```

Three things worth seeing:

- **The instrument discriminates.** Hill climbing (SHC) stalls (gaps 1.4–9.3); cross-entropy (CE) and R-SPLINE reach essentially zero. That is exactly the comparison the harness exists to make.
- **The confirmed winner is (1, 1)** — the true optimum. Even though no single greedy run in Part II Step 4 found it, comparing solvers under equal effort and confirming the finalists under common random numbers recovers ground truth.
- **A few gaps are slightly negative** (e.g. −0.078). On a noisy problem the *estimated* best can dip below the true optimum by luck. This is precisely why you never crown a winner from a single point estimate — and why the harness re-races finalists in a confirmation stage.

### 2.6 Recap

You built a Type 1 problem end to end: a response function and its builder, a problem definition, a hand-assembled pipeline, and a benchmark case. To experiment, change the noise standard deviation in `NoisyRosenbrockResponse`, widen the ranges, or add solver cases.

---

## Part III — Example 2: a discrete-event simulation model (Type 2)

Now the other kind of problem. The "model" is a full discrete-event simulation of a single-item **(r, Q) inventory system**, and — because real inventory problems almost always have a service requirement — this example carries a **constraint**.

Companion files: `RQInventorySetup.kt`, `RQInventoryOptimizationExample.kt`, `RQInventoryBenchmarkExample.kt`. The simulation model itself (`RQInventorySystem` and the elements it contains) already ships in the KSL examples; we reuse it.

### 3.1 Step 1 — the mathematical problem

In an (r, Q) policy, whenever the inventory position falls to or below the reorder point **r**, you order in multiples of the reorder quantity **Q** to bring it back up. Demand that cannot be met immediately is backordered. We choose r and Q to minimize long-run average cost while keeping the fill rate high:

```
    minimize   E[ OrderingAndHoldingCost(r, Q) ]
       over    Q ∈ {1, …, 100},  r ∈ {1, …, 100}     (integers)

    subject to E[ FillRate(r, Q) ] ≥ 0.95
```

Both the objective and the fill rate are *estimated* by running the simulation — there is no formula. The fill-rate requirement is a **response constraint**: it can only be checked statistically.

### 3.2 Step 2 — the simulation model

The model is an ordinary KSL discrete-event model. You do not need to understand every line to optimize it, but you do need to know **two things about how it is written**, because they become the names you optimize over. (For a full treatment of building DEDS models, see `ksl-entity` and `ksl-modeling`.)

The decision variables are exposed as **controls** — settable properties tagged with `@KSLControl`. In the (r, Q) item element they look like this:

```kotlin
@set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 1.0)
var initialReorderQty: Int
    get() = myInitialReorderQty
    set(value) { setInitialPolicyParameters(myInitialReorderPt, value) }

@set:KSLControl(controlType = ControlType.INTEGER, lowerBound = 0.0)
var initialReorderPoint: Int
    get() = myInitialReorderPt
    set(value) { setInitialPolicyParameters(value, myInitialReorderQty) }
```

The performance measures are exposed as **responses** — `Response` (or `TWResponse`) objects with names:

```kotlin
private val myOrderingAndHoldingCost = Response(this, "${this.name}:OrderingAndHoldingCost")
private val myFirstFillRate = Response(this, "${this.name}:FillRate")   // in the parent Inventory element
```

Those two facts — the `@KSLControl` properties and the named responses — are the entire interface between your model and the optimizer.

### 3.3 Step 3 — instrument the model for optimization

**Naming is the part that bites, so go slowly.** The KSL identifies a control by `elementName.propertyName` and a response by its own name. In our builder we name the (r, Q) system element `"Inventory"`. Inside, the system creates its item element as `"Inventory:Item"`. Therefore:

| Thing | Name the optimizer uses |
| --- | --- |
| reorder quantity control | `Inventory:Item.initialReorderQty` |
| reorder point control | `Inventory:Item.initialReorderPoint` |
| objective response | `Inventory:Item:OrderingAndHoldingCost` |
| fill-rate response | `Inventory:Item:FillRate` |

Note the punctuation: a control key uses a dot between the element name and the property (`....Item.initialReorderQty`), while a response is just its element-path name (`...:Item:FillRate`).

First, the **model builder**. A `ModelBuilderIfc` returns a *fresh, fully configured* `Model` each time it is called — the framework may build several copies, and a shared one would corrupt results. The model's name must equal the problem's `modelIdentifier`, because the framework routes evaluation requests by that identifier.

```kotlin
object BuildRQTutorialModel : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model("RQInventoryTutorial")   // must match the modelIdentifier below
        // Naming the system element "Inventory" fixes the control keys and
        // response names to the "Inventory:Item..." forms shown above.
        val rqModel = RQInventorySystem(model, reorderPt = 1, reorderQty = 2, name = "Inventory")
        rqModel.initialOnHand = 0
        rqModel.demandGenerator.initialTimeBtwEvents = ExponentialRV(1.0 / 3.6)
        rqModel.leadTime.initialRandomSource = ConstantRV(0.5)
        model.lengthOfReplication = 20000.0        // a long run for a good long-run estimate
        model.lengthOfReplicationWarmUp = 10000.0  // discard the transient start-up period
        model.numberOfReplications = 40
        return model
    }
}
```

Then the **problem definition**. The objective and input names are the control keys and response names from the table; the fill-rate constraint is a `responseConstraint`:

```kotlin
fun makeRQProblem(): ProblemDefinition {
    val problem = ProblemDefinition(
        problemName = "RQInventoryConstrained",
        modelIdentifier = "RQInventoryTutorial",
        objFnResponseName = "Inventory:Item:OrderingAndHoldingCost",
        inputNames = listOf("Inventory:Item.initialReorderQty", "Inventory:Item.initialReorderPoint"),
        responseNames = listOf("Inventory:Item:FillRate")
    )
    problem.inputVariable(name = "Inventory:Item.initialReorderQty", interval = Interval(1.0, 100.0), granularity = 1.0)
    problem.inputVariable(name = "Inventory:Item.initialReorderPoint", interval = Interval(1.0, 100.0), granularity = 1.0)
    // The probabilistic constraint: E[fill rate] >= 0.95.
    problem.responseConstraint(
        name = "Inventory:Item:FillRate",
        rhsValue = 0.95,
        inequalityType = InequalityType.GREATER_THAN
    )
    return problem
}
```

Because a naming mistake here would not surface until the model runs, the KSL lets you check the correspondence up front. Do this once in a test:

```kotlin
val model = BuildRQTutorialModel.build(null, null)
require(makeRQProblem().validateProblemDefinition(model)) {
    "The problem's input keys / response names do not match the model."
}
```

**How the constraint steers the search.** You do not write any accept/reject logic for the fill rate. The framework attaches a *penalty function* to the constraint; when a point's estimated fill rate is below 0.95, a non-negative penalty is added to its objective, making it look worse. Solvers search on that *penalized*objective, so they are pushed toward feasibility. The final `bestSolution`, however, is chosen *feasibility-first*: a point you are statistically confident is feasible outranks one you are not. You get all of this for free by adding the one `responseConstraint` line.

### 3.4 Step 4 — run the optimization pipeline

For a DEDS model you do **not** assemble the oracle and evaluator by hand. A solver factory does it from just the problem and the model builder:

```kotlin
fun main() {
    val problem: ProblemDefinition = makeRQProblem()

    // The factory builds the evaluator (with a cache) over a fresh model and
    // binds a stochastic hill climber to it. A null starting point means
    // "choose a random feasible starting point for me."
    val solver = Solver.createStochasticHillClimberSolver(
        problemDefinition = problem,
        modelBuilder = BuildRQTutorialModel,
        startingPoint = null,
        maxIterations = 30,
        replicationsPerEvaluation = 30
    )

    ConsoleSolverStateTracker(solver).startTracking()
    solver.runAllIterations()

    println()
    println("Best solution found (reorder quantity and reorder point):")
    println(solver.bestSolution.asString())
    solver.printResults()
}
```

Because each evaluation runs a discrete-event simulation, this takes noticeably longer than the instant Type 1 example — that difference is the whole reason simulation optimization tries to be frugal with evaluations. An actual run descended like this and settled on a feasible policy:

```
[Iter: 10 ...] EstObj: 16.600 | Best: (reorderPoint, reorderQty) = (15, 4)
[Iter: 20 ...] EstObj: 10.601 | Best: (reorderPoint, reorderQty) = (9, 4)
>>> SOLVER COMPLETED

Best solution found (reorder quantity and reorder point):
id = 20 : n = 30.0 : objFnc = 10.601 : 95%ci = [10.598, 10.603] : penalizedObjFnc = 10.601 : inputs : 9.0, 4.0
```

Here `penalizedObjFnc` equals `objFnc` (10.601), which tells you the reported point carries **no penalty** — its estimated fill rate satisfies the 0.95 requirement, so the solution is feasible. (When a best solution is infeasible, the penalized value is larger than the raw objective.)

Swapping algorithms is again one line — and since the inputs are integer-ordered, R-SPLINE applies unchanged:

```kotlin
val solver = Solver.createRSplineSolver(problemDefinition = problem, modelBuilder = BuildRQTutorialModel)
```

### 3.5 Step 5 — wrap it as a benchmark case

The Type 2 wrapper mirrors Part II, with one substitution: instead of the function-based evaluator factory, use `PooledMemberEvaluatorFactory`, which provisions each concurrent run with a pooled, reused model over the builder. There is no closed-form optimum, so we supply no reference solution; the harness then measures each run's gap against the best objective found across the experiment.

```kotlin
fun makeRQEvaluatorFactory(problem: ProblemDefinition): MemberEvaluatorFactoryIfc {
    return PooledMemberEvaluatorFactory(problem, BuildRQTutorialModel)
}

fun makeRQProblemCase(): ProblemCase {
    return ProblemCase(
        name = "RQInventoryConstrained",
        problemDefinitionFactory = ::makeRQProblem,
        evaluatorFactoryProvider = ::makeRQEvaluatorFactory,
        tags = mapOf("family" to "inventoryDEDS", "dimension" to "2", "constrained" to "true")
    )
}
```

A small study looks exactly like the Type 1 one — keep the grid small while learning, because each replication is a full simulation:

```kotlin
val experiment = BenchmarkExperiment(
    name = "RQInventoryTutorial",
    problems = listOf(makeRQProblemCase()),
    solverCases = standardSolverCases(),
    macroReplications = 2,
    replicationBudgetPerRun = 300,
    verificationReplications = 50      // re-simulate the winner to check feasibility at high N
)
```

The `verificationReplications` matters more here than for an unconstrained problem: re-simulating the winner at a higher replication count lets you confirm the fill-rate constraint is *actually* met, not merely met by a lucky low-N estimate.

### 3.6 Recap

The Type 2 workflow adds a real model builder and careful naming, and this example added a response constraint — but Steps 3–5 are the same shape as Type 1. The framework absorbed the constraint through penalties without any special code from you.

---

## Part IV — Example 3: a genuine Monte Carlo model, maximized (Type 1)

The Rosenbrock problem was "a deterministic function plus noise." Not all Type 1 problems look like that. In the **newsvendor** problem the randomness *is* the model, and the goal is to **maximize** — two wrinkles worth seeing.

Companion files: `NewsvendorSetup.kt`, `NewsvendorOptimizationExample.kt`, `NewsvendorBenchmarkExample.kt`.

### 4.1 Step 1 — the mathematical problem

A newsvendor orders q units at cost c each, sells each for price p up to whatever demand D appears, and salvages leftovers at value v. Demand is random — exponential with mean 50. Profit for one "day" is

```
    profit(q, D) = p · min(D, q) + v · max(q - D, 0) - c · q
```

and we choose the integer order quantity to maximize expected profit:

```
    maximize   E[ profit(q, D) ]
       over    q ∈ {0, 1, …, 200}
```

With p = 5, c = 1, v = 0, and mean demand 50, the classic *critical-fractile*result gives a closed-form optimum of q\* ≈ 50 · ln(5) ≈ 80 units. We will use that known optimum to check our work.

### 4.2 Step 2 — build the response function

Same interface as before, but notice: there is **no separate noise term**. The randomness comes entirely from the demand random variable, which — following the contract — is acquired once in the constructor.

```kotlin
class NewsvendorResponse(
    streamProvider: RNStreamProviderIfc
) : ResponseFunctionIfc {

    private val demand: ExponentialRV =
        ExponentialRV(50.0, streamNum = 1, streamProvider = streamProvider)

    override fun replication(inputs: Map<String, Double>): Map<String, Double> {
        val q: Double = inputs.getValue("orderQuantity")
        val d: Double = demand.value
        val sold: Double = minOf(d, q)
        val leftOver: Double = maxOf(q - d, 0.0)
        val profit: Double = 5.0 * sold + 0.0 * leftOver - 1.0 * q
        return mapOf("profit" to profit)
    }
}
```

### 4.3 Step 3 — instrument it: a maximize problem

The only new ingredient is `optimizationType = OptimizationType.MAXIMIZE`:

```kotlin
fun makeNewsvendorProblem(): ProblemDefinition {
    val problem = ProblemDefinition(
        problemName = "Newsvendor",
        modelIdentifier = "newsvendor",
        objFnResponseName = "profit",
        inputNames = listOf("orderQuantity"),
        optimizationType = OptimizationType.MAXIMIZE
    )
    problem.inputVariable(name = "orderQuantity", lowerBound = 0.0, upperBound = 200.0, granularity = 1.0)
    return problem
}
```

Internally the framework always *minimizes*, and handles a maximize problem by flipping the sign of the objective. You will see the consequence in the output.

### 4.4 Step 4 — run the optimization pipeline

The pipeline is identical in shape to Part II — which is the point: the same machinery drives a genuine Monte Carlo model and a maximize objective without any special handling from you.

```kotlin
val oracle = ResponseFunctionOracle(
    modelIdentifier = "newsvendor",
    responseNames = setOf("profit"),
    responseFunctionBuilder = NewsvendorResponseBuilder()
)
val evaluator = Evaluator(problem, oracle, MemorySolutionCache())
val solver = StochasticHillClimber(problem, evaluator, maximumIterations = 100, replicationsPerEvaluation = 50)
solver.runAllIterations()
println(solver.bestSolution.asString())
```

An actual run printed:

```
Closed-form optimal order quantity q* = 80.0
Best solution found (asString prints the raw average profit for a maximize problem):
id = 4 : n = 50.0 : objFnc = 159.5 : 95%ci = [128.9, 190.1] : penalizedObjFnc = -159.5 : inputs : 64.0
```

Two teaching points:

- `objFnc` **is positive (159.5) but** `penalizedObjFnc` **is negative (−159.5).** For a maximize problem the internal, minimization-oriented value is the *negated*objective. `asString()` shows you the raw average (a profit you can read directly); the negative penalized value is the framework's internal search key. When you maximize, always compare the raw-average numbers with each other, not with the penalized ones.
- **Again the greedy run misses.** It settled on q = 64 with a wide interval (\[128.9, 190.1\]) — the true optimum is q\* = 80. The single-run estimate is also optimistically biased upward by the exponential demand's heavy noise. As before, the benchmark's confirmation stage is the antidote.

### 4.5 Step 5 — wrap it as a benchmark case

The wrapper is the Type 1 pattern from Part II, and because we know the optimum we supply a reference solution computed from the closed form (see the companion `NewsvendorSetup.kt` for the two-line critical-fractile calculation). One design choice is worth calling out in the runner:

```kotlin
val experiment = BenchmarkExperiment(
    name = "NewsvendorTutorial",
    problems = listOf(makeNewsvendorProblemCase()),
    solverCases = listOf(
        stochasticHillClimberCase(),
        simulatedAnnealingCase(),
        crossEntropyCase()
    ),
    macroReplications = 3,
    replicationBudgetPerRun = 1000,
    verificationReplications = 100
)
```

We hand-pick the solver cases instead of using `standardSolverCases()`, because the newsvendor is **one-dimensional** and R-SPLINE (part of the standard set) currently has a known issue on 1-D problems. **Matching the solver set to the problem is itself part of designing a fair study.** (If you do include R-SPLINE on a 1-D problem, the harness isolates and records the failure per cell rather than crashing — see Part V.)

### 4.6 Recap

Type 1 is broader than "a formula plus noise": the newsvendor's randomness is intrinsic, and it maximizes. Neither required new machinery — just `OptimizationType.MAXIMIZE` and awareness of which output number to read.

---

## Part V — Bringing it together: a fair benchmark study

You have now benchmarked each problem on its own. The harness is happiest doing the whole grid at once — several problems, of both types, against several solvers. Companion file: `CombinedBenchmarkExample.kt`.

### 5.1 Why fair comparison is hard, and the five policies

Comparing optimization algorithms on noisy problems is easy to do wrong. The harness bakes in five policies so you do not have to:

1. **Equal replication budgets.** Every run stops when it has *requested* the same number of replications, so no solver wins by simply spending more. An "iteration" means wildly different effort for cross-entropy than for hill climbing; replications are the fair currency.
2. **Common starting points.** For each (problem, macro-replication) one starting point is drawn and given to every solver, so none wins by a lucky start.
3. **Confirmation.** After a problem's runs finish, the top finalists are re-raced under common random numbers, and the winner is chosen from those confirmed estimates — not from the single luckiest run.
4. **Gap recording.** With a known optimum, each run's gap is exact; without one, gaps are measured against the best objective found. Larger gap is always worse.
5. **Determinism.** Streams and starting points are fixed at launch, so the whole study reproduces bit-for-bit regardless of how many worker threads run it.

### 5.2 The combined grid

```kotlin
val experiment = BenchmarkExperiment(
    name = "TutorialCombinedStudy",
    problems = listOf(
        makeRosenbrockProblemCase(),   // Type 1: noisy function, 2-D, known optimum
        makeNewsvendorProblemCase(),   // Type 1: genuine Monte Carlo, 1-D, MAXIMIZE
        makeRQProblemCase()            // Type 2: constrained DEDS, 2-D
    ),
    solverCases = standardSolverCases(),
    macroReplications = 2,
    replicationBudgetPerRun = 250,
    verificationReplications = 50
)
val summary = experiment.run()

val db = BenchmarkResultsDb("tutorialCombined.db", KSL.dbDir)
val expId = db.saveSummary(summary)
```

One `BenchmarkExperiment` handles all three problems and both problem types uniformly. Two things to expect when you run it:

- **The (r, Q) cells dominate the wall-clock time** — the discrete-event simulation is where the minutes go, while the synthetic problems are nearly instant. This is the real texture of simulation-optimization studies.
- **R-SPLINE cells on the 1-D newsvendor may report** `status = FAILED`**.** That is the harness doing its job: a failing cell is isolated and recorded, never crashing the study. Every other cell is unaffected.

### 5.3 Reading the results

The returned `BenchmarkSummary` is a complete in-memory record, and `saveSummary`writes everything to a SQLite database that **appends** by default (successive studies accumulate in one file). The most useful fields per run:

- `bestObjective` — the best objective on the natural scale;
- `gap` / `gapType` — how far from the reference (or best-found);
- `numReplicationsRequested` — the *actual* effort consumed (normalize by this, not by the nominal budget, since batch solvers can overshoot by a generation);
- `status` — `COMPLETED`, `FAILED`, or `STOPPED_BEFORE_START`.

For a statistically defensible "which solver is best on this problem?", the database exposes a multiple-comparison feed:

```kotlin
val analyzer = db.mcbAnalyzer(expId, "Rosenbrock2D")   // a MultipleComparisonAnalyzer, or null
if (analyzer != null) {
    println(analyzer)
}
```

The full schema — eight tables, every field, and what each one lets you analyze — is documented in the [benchmark guide](ksl-simopt-benchmark.md#8-the-results-database). That guide also describes the **trace-rerun pattern**: run the big grid without per-iteration traces, then rerun just the one problem you want convergence curves for with `captureIterationTraces = true`, saving into the same database.

---

## Part VI — Variations, pitfalls, and where to go next

### 6.1 Small changes worth trying

- **Swap the solver.** Every `Solver.create…Solver(problemDefinition, modelBuilder, …)` factory has siblings for simulated annealing, cross-entropy, R-SPLINE, genetic algorithms, particle swarm, Bayesian optimization, and ISC. For a hand-assembled Type 1 pipeline, just construct a different solver class over the same evaluator.
- **Escape local optima with random restarts.** Every factory also has a `createRandomRestart…Solver` sibling that reruns the search from fresh random starting points and keeps the best — a good answer to the "greedy run got stuck" problem you saw in Parts II and IV.
- **Control the effort.** `replicationsPerEvaluation` sets how many replications average into each point's estimate (more = less noise, more cost). To stop a solver on a fixed replication budget instead of its own convergence rule (the basis of a fair comparison), install a `ReplicationBudgetStoppingCriterion`.
- **Add a constraint to a Type 1 problem.** The `ConstrainedNoisyQuadratic` in `ksl.examples.general.simopt.problems` shows a response constraint on a noisy function, exactly parallel to the (r, Q) fill-rate constraint here.

### 6.2 Pitfalls checklist

- **Input keys and response names must match the model exactly.** For a DEDS problem, an input name is a control key (`elementName.propertyName`) and a response name is a response's own name. Pin the correspondence with `validateProblemDefinition` in a test, as in Part III.
- **The model identifier must equal the built model's name** (Type 2) or the oracle's identifier (Type 1). Requests are routed by it.
- **Builders must return fresh, independent instances** — a new `Model` per `ModelBuilderIfc` call, a new response function per `ResponseFunctionBuilderIfc`call — with all random streams acquired at construction.
- **R-SPLINE, COMPASS, and ISC require integer-ordered inputs** (`granularity = 1.0`), and R-SPLINE currently has a known issue on 1-D problems.
- **Do not crown a winner from one point estimate.** On noisy problems the best-looking estimate is partly the luckiest. Use the benchmark's confirmation and verification stages, or `solver.bestSolutions.possiblyBest()`.
- **When maximizing, read the right number.** `asString()` prints the raw average; the penalized value is sign-flipped internally (Part IV).

### 6.3 Where to go next

- **The full reference for the optimization side:** `ksl-simopt`— every solver and every parameter, the evaluator/oracle internals, caches, trackers, and the concurrency model.
- **The full reference for benchmarking:**`ksl-simopt-benchmark` — the engine, the synthetic problem ladder, the DEDS problem cases, the database schema, and the pilot study.
- **The control-key naming convention:** `ksl-controls`.
- **Building DEDS models:** `ksl-entity`, `ksl-modeling`, `ksl-simulation`.
- **The theory:** the [KSL Book](https://rossetti.github.io/KSLBook/), Chapter 11.
- **A point-and-click version** of these ideas: the Simopt desktop app.

---

## Appendix A — the companion files and how to run them

All files are in `KSLExamples`, package `ksl.examples.general.simopt.tutorial`(directory `KSLExamples/src/main/kotlin/ksl/examples/general/simopt/tutorial/`). Each `…Example.kt` file has a `main` function; run it from IntelliJ IDEA by clicking the green arrow next to `main`.

| File | Role | Has `main`? |
| --- | --- | --- |
| `RosenbrockSetup.kt` | Example 1 shared setup: response function, builder, problem, benchmark case | no |
| `RosenbrockOptimizationExample.kt` | Example 1: one hand-assembled optimization run | yes |
| `RosenbrockBenchmarkExample.kt` | Example 1: a small benchmark study | yes |
| `RQInventorySetup.kt` | Example 2 shared setup: model builder, constrained problem, benchmark case | no |
| `RQInventoryOptimizationExample.kt` | Example 2: one factory-built optimization run | yes |
| `RQInventoryBenchmarkExample.kt` | Example 2: a small benchmark study | yes |
| `NewsvendorSetup.kt` | Example 3 shared setup: response function, maximize problem, benchmark case | no |
| `NewsvendorOptimizationExample.kt` | Example 3: one optimization run (maximize) | yes |
| `NewsvendorBenchmarkExample.kt` | Example 3: a small benchmark study (hand-picked solvers) | yes |
| `CombinedBenchmarkExample.kt` | Part V: all three problems in one grid | yes |

Start with `RosenbrockOptimizationExample.kt` (instant), then `RosenbrockBenchmarkExample.kt` (a few seconds). The (r, Q) examples run real simulations and take longer; keep their grids small while learning.

---

## Appendix B — glossary

- **Decision variable / input** — a setting you optimize over; has a range and a granularity. For a DEDS model it is a `@KSLControl` property, keyed `elementName.propertyName`.
- **Granularity** — the step size of a decision variable; `1.0` means integer-ordered.
- **Objective response** — the named simulation output being optimized.
- **Response constraint** — a requirement on an *estimated* output, e.g. `E[FillRate] ≥ 0.95`; enforced by penalties, tested statistically.
- **Replication** — one statistical observation of the outputs at a point.
- **Macro-replication** — in a benchmark, one independent repeat of a whole (problem, solver) cell; your statistical sample size.
- **Replication budget** — the number of replications a run may request before it is stopped; the fair-comparison currency.
- **Penalized objective** — objective plus constraint penalties; the solver's internal search key.
- `bestSolution` — the recommended answer, chosen feasibility-first and screened statistically.
- **Oracle** — the object that actually runs the "simulation": a `ResponseFunctionOracle` (Type 1) or a `SimulationProvider` over a `Model`(Type 2).
- **Evaluator** — turns a request to evaluate points into scored solutions, using the oracle and a cache.
- `ProblemCase` **/** `SolverCase` — a benchmark-ready problem / a named, problem-agnostic solver configuration.
- **Reference solution** — a known (or best-known) optimum a problem's runs are gapped against.
- **Gap** — how far a run's result is from the reference (or best-found); oriented so larger is worse.
- **CRN (common random numbers)** — evaluating compared points under the same randomness to sharpen the comparison.