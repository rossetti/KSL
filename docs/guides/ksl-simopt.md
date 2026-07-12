# Guide: `ksl.simopt`

A task-oriented guide to the KSL's **simulation-optimization** framework: finding
the input settings that optimize a *noisy* simulation response, subject to
deterministic and probabilistic constraints. It covers the four building blocks
you assemble to run an optimization — **problems**, the **evaluator/oracle**,
**solvers**, and **caches** — how a solver's search loop drives them, every
solver's complete parameter surface, the **trackers** that observe a run, and
the **benchmark** harness that compares solvers fairly.

This guide complements, rather than replaces:

- The **API reference** (Dokka/KDoc) documents every member of every class.
- The **[KSL Book](https://rossetti.github.io/KSLBook/)**, **Chapter 11**, covers
  the *theory*: formulating a problem (`ch11ProbDefn`), representing the
  simulation oracle, characterizing simulation-optimization algorithms, the
  implemented solvers, and applying a solver (`secApplySolver`). This guide does
  not re-derive that material — it answers *"how do I drive this package from
  Kotlin, and what does every knob actually do?"*

> **Status *(evolving)*.** The core — `ProblemDefinition`, the evaluator/oracle
> seam, `Solver`, and the classic solvers (hill climbing, simulated annealing,
> cross-entropy, R-SPLINE) — tracks Chapter 11 and is stable. The **newer solver
> families** (genetic, particle-swarm, Bayesian optimization, the ISC/COMPASS
> stack), the **concurrent portfolio**, and the **caches** are actively growing;
> signatures, defaults, and some behaviors are still settling (§9 documents the
> current rough edges). Pin the version you build against.

> **Scope.** Unlike most guides in this series, this one covers `ksl.simopt`
> **and every one of its sub-packages**: `ksl.simopt.problem`,
> `ksl.simopt.evaluator`, `ksl.simopt.solvers` (including `.algorithms` — with
> its `.bo`, `.genetic`, `.isc`, and `.pso` families — `.concurrent`, and
> `.trackers`), `ksl.simopt.cache`, and `ksl.simopt.benchmark`. §5 in particular
> is a full parameter reference for every solver, not just a task-oriented
> recipe — expect it to read more like an API appendix than the rest of the
> guide. The desktop GUI still has its own guide.

The code shown below is adapted from real, runnable examples under
`KSLExamples`: `CESolverExample.kt` and `SARestartSolverExample.kt` under
`ksl.examples.book.chapter11`, and — new alongside this guide —
`StochasticHillClimberExample.kt` and `RSplineSolverExample.kt` under
`ksl.examples.general.simopt`, which already hosts the shared
`makeLKInventoryModelProblemDefinition()`/`BuildLKModel` helpers every example
here uses. Because these are ordinary KSLExamples source files, they compile
on every normal build. See §10 for the complete file list.

---

## 1. What this package is for

A simulation-optimization problem asks: *which input settings `x` minimize (or
maximize) the expected value of a simulation response `E[H(x)]`* — where `H` is
noisy, so every evaluation is an *estimate* from a finite number of replications.
The problem may carry deterministic constraints on the inputs and probabilistic
constraints on other responses (`E[G(x)] ≤ c`).

That "noisy" is what makes it hard, and it is what this package handles for you:
replications to control estimation error, **penalty functions** to steer search
away from constraint violations without hard rejection, **caching** so a
revisited point isn't re-simulated, **common random numbers** to sharpen
comparisons, and **statistical best-selection** so you don't crown a winner that
was merely the luckiest estimate.

### The four building blocks

| Block | Package | What it is |
|---|---|---|
| **Problem** | `ksl.simopt.problem` | `ProblemDefinition` — objective response, decision variables (name, range, granularity), and constraints (linear, functional, response). |
| **Evaluator + oracle** | `ksl.simopt.evaluator` | Turns a request for input points into evaluated `Solution`s — running the model through a `SimulationOracleIfc`, applying caching/CRN, and combining replications into estimates. |
| **Solver** | `ksl.simopt.solvers` (+ `.algorithms`, `.concurrent`) | The search algorithm: hill climbing, simulated annealing, cross-entropy, GA, PSO, Bayesian, ISC/COMPASS, R-SPLINE — plus random restarts and a concurrent portfolio. |
| **Cache** | `ksl.simopt.cache` | `SolutionCacheIfc` (evaluated design points) and `SimulationRunCacheIfc` (raw replication output) so repeated points are cheap. |

### How it relates to its neighbors

| Package | Role |
|---|---|
| [`ksl.controls`](ksl-controls.md) | A problem's **input names are model input keys** — a control (`elementName.propertyName`) or a random-variable parameter (`rvName.paramName`) — and its **response names are model responses** — the naming convention this package builds on. |
| [`ksl.simulation`](ksl-simulation.md) | `Model`, `ModelBuilderIfc` (the oracle builds a fresh model per evaluation), `ExperimentRunParametersIfc`. |
| [`ksl.utilities.statistic`](ksl-utilities-statistic.md) | The confidence-interval / ranking machinery behind estimates, feasibility tests, and best-selection. |
| **Simopt desktop app** | The [GUI](apps/simopt.md) whose Model → Problem → Constraints → Algorithm → Run Setup → Execute steps are this package's `ProblemDefinition` / constraints / solver factories / budget. |

---

## 2. The mental model

### 2.1 The search loop

A `Solver` is an iterative search. Each iteration it chooses one or more
candidate input points and asks its `Evaluator` to score them; the evaluator
runs the model (or reads a cache), combines replications into an estimate, and
returns a `Solution` per point; the solver updates the best it has seen and
decides where to look next.

```
Solver.runAllIterations()
   │  each iteration: pick candidate InputMap(s)
   ▼
EvaluationRequest (points × replication count)
   │
   ▼
Evaluator ──▶ SolutionCache?  (hit → reuse / merge replications)
   │            │ miss
   │            ▼
   │        SimulationOracleIfc.simulate(...)   ── runs a fresh Model per point
   │            (SimulationRunCache? at this level)
   ▼
Solution(s):  inputMap + estimated objective + response estimates + feasibility
   │
   ▼
Solver updates bestSolution / bestSolutions, then chooses the next point
```

Underneath, `Solver.createXxxSolver(problemDefinition, modelBuilder, …)` wires
this whole stack for you in one call — building the `Evaluator` (via
`Evaluator.createProblemEvaluator`), a solution cache, and the oracle over
fresh models. You can also assemble it by hand (§4).

### 2.2 Concepts that fall out of the design

- **Everything is minimization internally.** `MAXIMIZE` problems are handled by
  an `objFncFactor` of `-1.0`; solvers always minimize the *penalized* objective.
- **The penalized objective steers search.** A `Solution`'s
  `penalizedObjFncValue` = objective + the sum of constraint penalties
  (deterministic linear/functional + stochastic response). Solvers rank by it, so
  constraints bend the search rather than hard-rejecting points. The total penalty
  is always **non-negative** and is *added* to the minimization-oriented objective,
  so a violation makes a solution worse for both `MINIMIZE` and `MAXIMIZE` (the
  penalty is deliberately not multiplied by `objFncFactor`). Each penalty is bound
  to its constraint and ramps with the iteration counter; a *memoryful* penalty
  (the Park & Kim Penalty-Function-with-Memory, §8) additionally sharpens as a
  re-sampled point accumulates evidence of infeasibility.
- **The reported answer is chosen feasibility-first, not by the penalized
  objective.** Because the penalty multiplier grows with the iteration counter, the
  penalized value of a point found early isn't comparable to one found late. So
  `solver.bestSolution` — the recommended answer — is selected by a
  clock-independent `FeasibilityFirstComparator`: a solution you're statistically
  confident is response-feasible outranks one you're not; feasible solutions then
  compare on the raw objective and infeasible ones on total violation. The
  penalized objective is a *within-iteration* search key only (exposed as the
  protected `penalizedIncumbent`).
- **Two kinds of feasibility.** *Input* feasibility (ranges + linear + functional
  constraints) is deterministic and can be checked before simulating; *response*
  feasibility (`E[G(x)] ≤ c`) is statistical and tested with confidence intervals
  (`Solution.isResponseConstraintFeasible`). Input-infeasible points can be
  filtered up front; response constraints are penalized.
- **Granularity picks the lattice.** An input's `granularity` is the decision
  precision; `granularity = 1.0` makes it **integer-ordered**, which R-SPLINE,
  ISC, and COMPASS all require (§5).
- **Replications are the noise/cost dial.** How many replications a solver
  requests per point is a *strategy* (`ReplicationPerEvaluationIfc` —
  `FixedReplicationsPerEvaluation` or a growth schedule), not a constant. More
  replications → tighter estimate → more simulation cost.
- **Don't trust one point estimate.** `bestSolutions` is a bounded, penalized-
  objective-ordered set; `possiblyBest()` screens it down to solutions that are
  *statistically indistinguishable* from the best. The benchmark harness's
  confirmation stage (§7) exists for the same reason.

### 2.3 Two independent concurrency axes

Simulation optimization is CPU-bound, and this package parallelizes in two
orthogonal ways — don't conflate them:

1. **Concurrent evaluation of a batched multi-point request** — population-based
   methods (GA, CE, PSO) submit a whole generation to the evaluator as *one*
   batched request (`requestEvaluations`). Batching is inherent to those solvers;
   what `ParallelEvaluationOptions.enabled` controls is whether the points in that
   batch are *simulated concurrently* on many fresh models, or *serially* on one
   reused model. The default is serial (`enabled = false`); PSO's factory is the
   one that defaults it on. So GA and CE also batch their requests — they just
   evaluate the batch one point at a time unless you enable parallelism.
2. **Concurrent restarts / a solver portfolio** — run several *whole solver runs*
   at once: many restarts of one algorithm (`concurrentRestarts` on the SHC/SA
   random-restart factories) or a `SolverPortfolio` racing different algorithms.
   Controlled by `ConcurrentRunOptions`.

Both are bounded by the available processors, and the two are **mutually
exclusive** within one random-restart solver (the concurrency budget is spent at
one level or the other). Crucially — unlike [`ksl.controls.experiments`](ksl-controls-experiments.md),
whose parallel runners are `suspend` — the concurrent simopt entry points are
**ordinary blocking calls**: `portfolio.runAllIterations()` and
`RandomRestartSolver.runAllIterations()` both hide their coroutines behind an
internal `runBlocking` (inside the shared `ConcurrentSolverRunner`), so you
never write `runBlocking` or `suspend` yourself.

---

## 3. Quick start

Minimize the expected total cost of the LK inventory model with a stochastic
hill climber. `makeLKInventoryModelProblemDefinition()` and `BuildLKModel` are
ready-made helpers (objective `"TotalCost"`, two integer-ordered decision
variables bound to model control keys).

```kotlin
// A problem: objective response "TotalCost", two integer-ordered inputs
// bound to model control keys ("Inventory.orderQuantity", "...reorderPoint").
val problem = makeLKInventoryModelProblemDefinition()

// A solver: the factory wires an Evaluator (with a solution cache) over a
// fresh model per evaluation and binds a stochastic hill climber to it.
val solver = Solver.createStochasticHillClimberSolver(
    problemDefinition = problem,
    modelBuilder = BuildLKModel,     // a ModelBuilderIfc returning a fresh LK model
    startingPoint = null,            // null -> random feasible start
    maxIterations = 100,
    replicationsPerEvaluation = 50
)

// Optional: stream solver state to the console each iteration.
ConsoleSolverStateTracker(solver).startTracking()

solver.runAllIterations()            // blocking; drives the search to termination
println(solver.bestSolution.asString())
```

Everything else elaborates the problem (§4), a full reference for every solver
(§5), trackers (§6), the benchmark harness (§7), and the results you read back
(§4, §8).

---

## 4. How do I...?

### ...define a problem from scratch

Construct a `ProblemDefinition` with the objective response, the input names, and
(optionally) response names; then declare each decision variable and each
constraint. **Input names must be valid model *input keys*** — which are the
model's controls (`@KSLControl` properties, keyed `elementName.propertyName`) **or
its random-variable parameters** (keyed `rvName.parameterName`, e.g.
`"ServiceTimeRV.mean"`). Either kind of key can be a decision variable;
`model.inputKeys()` lists them all and `model.validateInputKeys(...)` checks a set.
**Response names must be model response names.** See [`ksl-controls`](ksl-controls.md)
for the control-key convention.

```kotlin
val problem = ProblemDefinition(
    problemName = "RQInventory",
    modelIdentifier = "RQInventoryModel",          // must match a provided model
    objFnResponseName = "Inventory:Item:OrderingAndHoldingCost",
    inputNames = listOf(
        "Inventory:Item.initialReorderQty",
        "Inventory:Item.initialReorderPoint"
    ),
    responseNames = listOf("Inventory:Item:FillRate"),
    optimizationType = OptimizationType.MINIMIZE
)
// Decision variables: control keys, ranges, granularity 1.0 = integer-ordered.
problem.inputVariable("Inventory:Item.initialReorderQty", Interval(1.0, 100.0), granularity = 1.0)
problem.inputVariable("Inventory:Item.initialReorderPoint", Interval(1.0, 100.0), granularity = 1.0)

// Probabilistic (response) constraint: E[fill rate] >= 0.95.
problem.responseConstraint(
    name = "Inventory:Item:FillRate",
    rhsValue = 0.95,
    inequalityType = InequalityType.GREATER_THAN
)
// Deterministic linear constraint on the inputs (optional).
problem.linearConstraint(
    equation = mapOf("Inventory:Item.initialReorderPoint" to 1.0),
    rhsValue = 50.0,
    inequalityType = InequalityType.LESS_THAN
)
```

The objective response name **must not** also appear in `responseNames` (it is
added automatically). A functional (deterministic non-linear) constraint takes a
`ConstraintFunctionIfc` via `problem.functionalConstraint(...)`.

### ...steer the search around a constraint (penalty functions)

Every constraint carries a `PenaltyFunction` — a non-negative contribution added
to the penalized objective when the constraint is violated. You don't have to
touch this: each constraint inherits a problem-level default
(`DynamicPolynomialPenalty`, a memoryless polynomial that ramps with the iteration
counter). Override it at either scope:

```kotlin
// (a) Problem-wide default for a whole constraint family:
problem.defaultResponsePenalty =
    DynamicPolynomialPenalty(basePenalty = 250.0, iterationExponent = 1.0)

// (b) Per-constraint, via the optional penaltyFunction argument:
problem.responseConstraint(
    name = "Inventory:Item:FillRate",
    rhsValue = 0.95,
    inequalityType = InequalityType.GREATER_THAN,
    penaltyFunction = DynamicPolynomialPenalty(basePenalty = 500.0)
)
```

For a **stochastic** (response) constraint you can opt into the Park & Kim (2015)
Penalty-Function-with-Memory (`ParkKimPenalty`), which accumulates a standardized
violation measure across repeated visits to a design point and appreciates or
depreciates its multiplier accordingly — sharper convergence than the memoryless
polynomial when the constraint is noisy:

```kotlin
problem.defaultResponsePenalty = ParkKimPenalty(
    sequence = AppreciateDepreciateSequence(
        appreciationFactor = 2.0,   // grows the multiplier when a point looks infeasible
        depreciationFactor = 0.5    // shrinks it when the point looks feasible
    )
    // fallback defaults to DynamicPolynomialPenalty until memory accumulates
)
```

A memoryful penalty only engages when design points are **re-sampled**, which
requires a solution cache on the evaluator (the `create*Solver` factories supply
one). Without a cache — or for a point that's never revisited — it degrades
gracefully to its memoryless fallback and the evaluator logs a warning (§9).

### ...pick a solver

§5 is the full reference. As an index, the primary entry points are the
`Solver.Companion` factories:

| Factory | Class | Algorithm | Notes |
|---|---|---|---|
| `createStochasticHillClimberSolver` | `StochasticHillClimber` | greedy accept-if-better neighbor search | simplest; §5.1 |
| `createSimulatedAnnealingSolver` | `SimulatedAnnealing` | Metropolis acceptance + cooling | §5.2 |
| `createCrossEntropySolver` | `CrossEntropySolver` | cross-entropy (fit distribution to elites) | §5.3 |
| `createRSplineSolver` | `RSplineSolver` | R-SPLINE retrospective search | **integer-ordered problems only**; §5.4 |
| `createGeneticAlgorithmSolver` | `GeneticAlgorithmSolver` | population GA (selection/crossover/mutation) | §5.5 |
| `createParticleSwarmSolver` | `ParticleSwarmSolver` | global-best particle swarm | parallel evaluation **on by default**; §5.6 |
| `createBayesianOptimizationSolver` | `BayesianOptimizationSolver` | GP surrogate + acquisition function | §5.7 |
| `createISCSolver` | `ISCSolver` | Industrial-Strength COMPASS: niching-GA → COMPASS → R&S clean-up | **integer-ordered problems only** (enforced at construction); §5.8 |

`RandomWalkSolver` (unbiased walk, for landscape analysis/calibration — no
factory) and `RandomRestartSolver` (the generic random-restart wrapper) are
covered in §5.9. `NichingGeneticAlgorithmSolver` and `CompassSolver` — the two
phases `ISCSolver` orchestrates, independently constructible — are covered
alongside ISC in §5.8.

Swapping algorithms is a one-line change once a problem exists:

```kotlin
val solver = Solver.createCrossEntropySolver(
    problemDefinition = problem,
    modelBuilder = BuildLKModel,
    maxIterations = 100,
    replicationsPerEvaluation = 50
)
solver.runAllIterations()
println("best = ${solver.bestSolution.asString()}")

// The retained bests that are statistically indistinguishable from the best.
val screened = solver.bestSolutions.possiblyBest()
println(screened)
// Export the retained solutions as a data frame.
println(solver.bestSolutions.toDataFrame())
```

To assemble the stack by hand (e.g. to share one evaluator, or subclass a
solver), build the evaluator yourself and pass it to a solver constructor:

```kotlin
val evaluator = Evaluator.createProblemEvaluator(problem, BuildLKModel)
val solver = StochasticHillClimber(
    problem, evaluator,
    maxIterations = 100,
    replicationsPerEvaluation = FixedReplicationsPerEvaluation(50)
)
```

### ...escape local optima with random restarts

```kotlin
val solver = Solver.createRandomRestartSimulatedAnnealingSolver(
    problemDefinition = problem,
    modelBuilder = BuildLKModel,
    maxNumRestarts = 5,
    maxIterations = 100,
    replicationsPerEvaluation = 50
)
// The nested tracker follows the outer restart driver and the inner solver.
NestedConsoleSolverStateTracker(solver, solver.restartingSolver).startTracking()
solver.runAllIterations()
println(solver.bestSolution.asString())
```

Every `create*Solver` factory has a `createRandomRestart*Solver` sibling. All of
them additionally accept `concurrentRestarts` (> 1) to run restarts on a bounded
worker pool instead of sequentially — see §5.9 and §6 for the tracking
implications of that choice.

### ...race several algorithms concurrently (a portfolio)

`SolverPortfolio` is itself a `Solver` that races member solvers on one problem
and reports the best (optionally confirmed under CRN). Each member is built by a
`SolverFactoryIfc` bound to that member's private evaluator:

```kotlin
val members = listOf(
    SolverMemberTask(
        solverFactory = SolverFactoryIfc { evaluator, _, name ->
            StochasticHillClimber(
                problem, evaluator,
                maxIterations = 100,
                replicationsPerEvaluation = FixedReplicationsPerEvaluation(50),
                name = name
            )
        },
        label = "SHC"
    ),
    SolverMemberTask(
        solverFactory = SolverFactoryIfc { evaluator, _, name ->
            SimulatedAnnealing(
                problem, evaluator,
                maxIterations = 100,
                replicationsPerEvaluation = FixedReplicationsPerEvaluation(50),
                name = name
            )
        },
        label = "SA"
    )
)
val portfolio = SolverPortfolio.create(
    problemDefinition = problem,
    modelBuilder = BuildLKModel,      // each member gets its own pooled, isolated model
    members = members
)
portfolio.runAllIterations()          // blocking; members race on a bounded worker pool
println("winner = ${portfolio.bestSolution.asString()}")
```

Configure workers and an optional confirmation stage with
`ConcurrentRunOptions(numWorkers = …, confirmation = ConfirmationOptions(topK = 3, replicationsPerCandidate = 50))`
— those two numbers are `ConfirmationOptions`'s real defaults, spelled out, not
illustrative values.

### ...cache to avoid re-simulating a point

The factories already enable a `MemorySolutionCache` (evaluated design points).
Add a `MemorySimulationRunCache` to also cache the raw replication output at the
oracle level:

```kotlin
val solver = Solver.createStochasticHillClimberSolver(
    problemDefinition = problem,
    modelBuilder = BuildLKModel,
    maxIterations = 100,
    replicationsPerEvaluation = 50,
    solutionCache = MemorySolutionCache(capacity = 5000),   // evaluated design points
    simulationRunCache = MemorySimulationRunCache()         // raw replication output (oracle level)
)
```

`solver.solverResult.evaluatorMetrics` reports the cache savings (replications
served from cache vs. run by the oracle).

### ...stop on an equal replication budget

To terminate by *simulation effort* rather than the algorithm's own heuristic
convergence (the basis of a fair benchmark comparison — see §7), install a
`ReplicationBudgetStoppingCriterion`:

```kotlin
val solver = Solver.createStochasticHillClimberSolver(
    problemDefinition = problem,
    modelBuilder = BuildLKModel,
    maxIterations = 10_000               // a generous ceiling
)
// Stop once the cumulative requested replications reach the budget.
solver.solutionQualityEvaluator = ReplicationBudgetStoppingCriterion(replicationBudget = 3000)
solver.runAllIterations()
solver.printResults()
```

`solutionQualityEvaluator` is the general plug-in stopping hook
(`SolutionQualityEvaluatorIfc`); the budget criterion is one implementation, and
it silently supersedes each solver's own default no-improvement stopping rule
whenever it's set (§5, §9).

### ...read the results

| You want… | Use |
|---|---|
| the single best point | `solver.bestSolution` — the feasibility-first recommended solution (a `Solution`; `.asString()` / `.inputMap` / `.estimatedObjFncValue` / `.penalizedObjFncValue`) |
| the retained near-best set | `solver.bestSolutions` (`SolutionsIfc`), `possiblyBest()`, `orderedSolutions`, `toDataFrame()` |
| a formatted run report | `solver.printResults()` or `solver.solverResult` (a `SolverResult.Completed` with `evaluatorMetrics`, iterations, timing) |
| per-iteration progress | a tracker — see §6 |

### ...control randomness (CRN, streams)

Each solver factory gives the solver its **own** `RNStreamProvider` by default
(`streamProvider = RNStreamProvider()`), so independent solvers don't share
streams. Pass an explicit `streamNum` / `streamProvider` to seed reproducibly.
Inside the evaluator, common random numbers across compared points are requested
per evaluation (the `crnOption` on an `EvaluationRequest`) and the oracle
positions each point's sub-stream via a `StreamTapePolicy` so CRN and
per-point isolation are bit-reproducible.

---

## 5. Solver parameter reference

This section is deliberately exhaustive: every solver's factory parameters,
direct-constructor parameters, and every default value — with the reason each
parameter exists, not just its type. Two patterns hold across *every* solver
with a factory, stated once here so each subsection below doesn't repeat them:

> **Each solver has one default `maxIterations`, used by both its factory and its
> direct constructors.** The default is that algorithm's own companion constant:
> **100** for SHC, SA, CE, GA, PSO, BO, R-SPLINE, COMPASS, and NGA; **1000** for
> ISC (which counts orchestration macro-steps, not inner iterations).
>
> **`replicationsPerEvaluation` defaults to 30 everywhere**
> (`Solver.defaultReplicationsPerEvaluation`), shared by every factory and every
> Int-based convenience constructor.
>
> **All of these numeric defaults are mutable `@JvmStatic var` companion
> properties, not compile-time constants.** `StochasticHillClimber.shcDefaultMaxIterations = 250`
> (for example) changes the default for every solver built afterward in that
> JVM session — including by other code, other tests, or other threads. If you
> need a guaranteed value, pass it explicitly rather than relying on whatever
> the default currently is.

### 5.1 `StochasticHillClimber`

Single-trajectory greedy search: each iteration draws one random neighbor,
evaluates it, and keeps it only if it's strictly better than the current point
(compared on the penalized objective). No acceptance of worse moves — this is
the "always accept improvement, never accept regression" baseline the other
local-search algorithms generalize away from. Stops early via an internal
no-improvement `SolutionChecker`, independent of `maxIterations`.

**Factory — `Solver.createStochasticHillClimberSolver(...)`**

| Parameter | Type | Default | Purpose |
|---|---|---|---|
| `problemDefinition` | `ProblemDefinition` | required | The problem to solve. |
| `modelBuilder` | `ModelBuilderIfc` | required | Builds a fresh model per evaluation. |
| `startingPoint` | `MutableMap<String, Double>?` | `null` | If null, a random feasible starting point is generated automatically. |
| `maxIterations` | `Int` | `shcDefaultMaxIterations` = **100** | Iteration cap. |
| `replicationsPerEvaluation` | `Int` | **30** | Replications per point evaluation. |
| `solutionCache` | `SolutionCacheIfc` | `MemorySolutionCache()` | Evaluator-level cache. |
| `simulationRunCache` | `SimulationRunCacheIfc?` | `null` | Oracle-level cache; off by default. |
| `experimentRunParameters` | `ExperimentRunParametersIfc?` | `null` | Run-length/warm-up overrides for model building. |
| `streamNum` | `Int` | `0` | 0 = next available stream. |
| `streamProvider` | `RNStreamProviderIfc` | `RNStreamProvider()` | Fresh provider by default. |
| `name` | `String?` | `null` | Optional solver name. |
| `parallelOptions` | `ParallelEvaluationOptions` | `ParallelEvaluationOptions()` (sequential) | Parallel multi-point evaluation — SHC only ever proposes one point per iteration, so this rarely matters here. |

**Direct constructor** — two overloads: a primary one taking
`replicationsPerEvaluation: ReplicationPerEvaluationIfc` (no default — required
even though later params are defaulted), and a secondary `@JvmOverloads`
Int-based convenience one. Both default `maxIterations` to
`shcDefaultMaxIterations` = **100**, matching the factory. `streamNum=0`,
`streamProvider=RNStreamProvider()`, `name=null` also match the factory.

**The shared `StochasticSolver` base** (also underlies `RandomWalkSolver` and
`RandomRestartSolver`) adds a bound `RNStreamIfc`, `RNStreamControlIfc`
plumbing, a pluggable `startingPointGenerator: StartingPointIfc?` (defaults to
feasible acceptance-sampling when null), and the default `nextPoint()` =
`generateNeighbor(currentPoint, rnStream)`. It has no factory and is never
instantiated directly.

*Worth knowing:* the internal no-improvement stopping window defaults to **20**
iterations (`defaultNoImproveThresholdForSHC`) — a plain companion `var`, not a
constructor parameter, so it's global-default-only unless you reach into
`solver.solutionChecker.noImproveThreshold` after construction.

### 5.2 `SimulatedAnnealing`

Single-trajectory search with the Metropolis acceptance rule: a worse neighbor
is still accepted with probability `exp(-costDifference / currentTemperature)`,
so the search can escape local optima early (high temperature) and behaves like
greedy descent as the temperature cools. Stops when the temperature drops below
`stoppingTemperature`, a no-improvement window elapses, or `maxIterations` is
hit.

**Factory — `Solver.createSimulatedAnnealingSolver(...)`**

| Parameter | Type | Default | Purpose |
|---|---|---|---|
| `problemDefinition` | `ProblemDefinition` | required | — |
| `modelBuilder` | `ModelBuilderIfc` | required | — |
| `startingPoint` | `MutableMap<String, Double>?` | `null` | — |
| `temperatureConfiguration` | `TemperatureConfiguration` | `AutoCalibrate()` → `targetProbability=0.8, sampleSize=100` | Static temperature vs. an automatic pre-search calibration walk. **Differs from the direct constructor — see below.** |
| `coolingSchedule` | `CoolingScheduleIfc` | `ExponentialCoolingSchedule(1000.0, coolingRate=0.95)` | How temperature decreases per iteration. |
| `stoppingTemperature` | `Double` | **0.001** | Below this, the search stops (absent a custom `solutionQualityEvaluator`). |
| `maxIterations` | `Int` | `saDefaultMaxIterations` = **100** | Iteration cap. |
| `replicationsPerEvaluation` | `Int` | **30** | — |
| `solutionCache` | `SolutionCacheIfc` | `MemorySolutionCache()` | — |
| `simulationRunCache` | `SimulationRunCacheIfc?` | `null` | — |
| `experimentRunParameters` | `ExperimentRunParametersIfc?` | `null` | — |
| `streamNum` | `Int` | `0` | — |
| `streamProvider` | `RNStreamProviderIfc` | `RNStreamProvider()` | — |
| `name` | `String?` | `null` | — |
| `parallelOptions` | `ParallelEvaluationOptions` | `ParallelEvaluationOptions()` | — |

**The direct constructor defaults `temperatureConfiguration` differently from the
factory:** the constructor uses `TemperatureConfiguration.Fixed(1000.0)`, while
the factory uses `AutoCalibrate()`. (`maxIterations` agrees at **100** on both.)
**If the cooling behavior matters to you, pass `temperatureConfiguration`
explicitly regardless of which entry point you use** — don't rely on either
default. Note also that `AutoCalibrate` isn't free: it runs an extra
~100-evaluation random walk during initialization before the real search starts.

**`TemperatureConfiguration`** is a sealed class with exactly two variants:

| Variant | Parameters | When to use |
|---|---|---|
| `Fixed(temperature: Double)` | no default — required | You already know a good starting temperature (prior calibration, domain knowledge) and want zero pre-search overhead. |
| `AutoCalibrate(targetProbability: Double = 0.8, sampleSize: Int = 100)` | both defaulted | You don't know a good temperature for this landscape; spends a short random walk estimating one. |

The walk-based estimate comes from `InitialTemperatureEstimator` (an internal,
non-public object): it averages the positive ("worsening") cost differences
along a pre-generated random walk of `sampleSize` steps and solves for the
temperature that gives worsening moves the `targetProbability` acceptance rate;
falls back to `1000.0` if the walk found no worsening moves at all. A separate,
publicly-callable `SimulatedAnnealing.estimateInitialTemperature(...)` exists
if you want to probe a good `Fixed` temperature ahead of time without running a
solver.

*Worth knowing:* `solutionEqualityChecker` and the no-improvement stall
threshold (`defaultNoImproveThresholdForSA` = **5**) are not exposed by the
factory at all — only reachable via the direct constructor or a
post-construction companion-`var` override.

### 5.3 `CrossEntropySolver`

Maintains a parameterized sampling distribution (by default an independent
per-dimension normal) over the input space. Each iteration: draw a population,
evaluate it, keep the best slice as "elites," and re-fit the distribution's
mean/std-dev toward the elites via exponential smoothing. Repeats until the
distribution's spread collapses (converged) or no improvement is seen for
several iterations.

**Factory — `Solver.createCrossEntropySolver(...)`**

| Parameter | Type | Default | Purpose |
|---|---|---|---|
| `problemDefinition` | `ProblemDefinition` | required | — |
| `modelBuilder` | `ModelBuilderIfc` | required | — |
| `ceSampler` | `CESampler?` | `null` → built as `CENormalSampler(problemDefinition)` | The reference-distribution sampler. |
| `startingPoint` | `MutableMap<String, Double>?` | `null` | — |
| `maxIterations` | `Int` | `ceDefaultMaxIterations` = **100** | Iteration cap (factory and both direct constructors agree at 100). |
| `replicationsPerEvaluation` | `Int` | **30** | — |
| `solutionCache` | `SolutionCacheIfc` | `MemorySolutionCache()` | — |
| `simulationRunCache` | `SimulationRunCacheIfc?` | `null` | — |
| `experimentRunParameters` | `ExperimentRunParametersIfc?` | `null` | — |
| `streamNum` | `Int` | `0` | — |
| `streamProvider` | `RNStreamProviderIfc` | `RNStreamProvider()` | — |
| `name` | `String?` | `null` | — |
| `parallelOptions` | `ParallelEvaluationOptions` | `ParallelEvaluationOptions()` | — |

**`CENormalSampler`'s own parameters — the real CE tuning knobs**, none of
which the factory exposes (only reachable by building a `CESampler` yourself
and passing it as `ceSampler`):

| Parameter | Default | Purpose |
|---|---|---|
| `meanSmoother` | **0.85** | Exponential-smoothing weight on the *old* mean each iteration (`mean = w·mean + (1-w)·eliteAvg`); closer to 1 = slower drift. |
| `sdSmoother` | **0.85** | Same idea for standard deviation. **The companion global-default setter for this one (`CENormalSampler.defaultStdDevSmoother = ...`) has a real bug: it validates but never assigns, so the global default silently cannot be changed. Per-instance `sdSmoother` still works fine.** |
| `coefficientOfVariationThreshold` | **0.03** | Convergence bound: sampler is "converged" once every dimension's std dev is within 3% of its mean. |
| `initialVariabilityFactor` (property, not a constructor param) | **1.0** | Multiplier on the range-derived initial std dev (`range/4`); widen or narrow the initial search spread. |

Not constructor parameters — public `var` properties, each with its own
companion default: `elitePct` = **0.1** (fraction of the population kept as
elite), `ceSampleSize` (computed via a quantile formula, ≈**35** given current
defaults, clamped to [10, 100]), `eliteSize()` (computed, ≈**5** — floor-
dominated, since 10% of 35 rounds to only 4 before the `defaultMinEliteSize`=5
floor applies).

*Worth knowing:* the sampler models each dimension **independently** (diagonal
covariance) despite being called "the multivariate-normal default" — no
cross-dimension correlation is captured.

### 5.4 `RSplineSolver`

R-SPLINE — Retrospective Search with Piecewise Linear Interpolation and
Neighborhood Enumeration (Wang, Pasupathy & Schmeiser, 2013). Solves a sequence
of increasingly precise "sample-path problems," each seeded by the previous
one's solution, with both the replication count and search effort growing as
the sequence progresses. **Requires every input to be integer-ordered
(`granularity = 1.0`)** — not a policy choice: the piecewise-linear
interpolation step builds a simplex by walking one integer step at a time in
each coordinate (a lattice triangulation), which is only well-defined on a
unit-spaced grid, and the default neighborhood search (von Neumann, radius 1)
means "adjacent integer points." The constructor enforces this directly:

```kotlin
require(problemDefinition.isIntegerOrdered) { "R-SPLINE requires that the problem definition be integer ordered!" }
```

**Factory — `Solver.createRSplineSolver(...)`**

| Parameter | Type | Default | Purpose |
|---|---|---|---|
| `problemDefinition` | `ProblemDefinition` | required | — |
| `modelBuilder` | `ModelBuilderIfc` | required | — |
| `initialNumReps` | `Int` | `defaultInitialSampleSize` = **8** | Replication count at outer iteration 1. |
| `sampleSizeGrowthRate` | `Double` | `defaultReplicationGrowthRate` = **0.1** | Geometric growth rate of the replication count across outer iterations. (The factory KDoc names a constant, `defaultSampleSizeGrowthRate`, that doesn't exist anywhere in the codebase — the real one is `FixedGrowthRateReplicationSchedule.defaultReplicationGrowthRate`.) |
| `maxNumReplications` | `Int` | `defaultMaxNumReplications` = **1000** | Ceiling the growing replication count is capped at. |
| `startingPoint` | `MutableMap<String, Double>?` | `null` | — |
| `maxIterations` | `Int` | `rSplineDefaultMaxIterations` = **100** | Outer (retrospective) iteration cap (factory and both direct constructors agree at 100). |
| `solutionCache` | `SolutionCacheIfc` | `MemorySolutionCache()` | — |
| `simulationRunCache` | `SimulationRunCacheIfc?` | `null` | — |
| `experimentRunParameters` | `ExperimentRunParametersIfc?` | `null` | — |
| `streamNum` | `Int` | `0` | — |
| `streamProvider` | `RNStreamProviderIfc` | `RNStreamProvider()` | — |
| `name` | `String?` | `null` | — |
| `parallelOptions` | `ParallelEvaluationOptions` | `ParallelEvaluationOptions()` | — |

The replication schedule (`initialNumReps`, `sampleSizeGrowthRate`,
`maxNumReplications`) is wrapped into a `FixedGrowthRateReplicationSchedule`
whose growth formula, at outer iteration `k`, is: iteration 1 gets exactly
`initialNumReps`; thereafter `ceil(initialNumReps × (1+growthRate)^(k-1))`,
capped at `maxNumReplications`. With the defaults (8, 0.1, 1000): iteration 10
≈ 19 reps, iteration 50 ≈ 836, iteration ≥ ~53 caps at 1000. R-SPLINE's direct
constructor requires the concrete `FixedGrowthRateReplicationSchedule` type
(not the general `ReplicationPerEvaluationIfc` interface every other solver
accepts) — you cannot substitute an arbitrary custom replication strategy here.

**Most of R-SPLINE's tuning surface isn't in the constructor at all** — these
are post-construction `var` properties, each independently defaulted:
`perturbation` (0.15, the off-lattice jitter PLI applies before building its
simplex), `initialLineSearchStepSize`/`lineSearchStepSizeMultiplier` (2.0/2.0,
hardcoded literals — these two are the only tuning knobs in the class that
*don't* defer to a companion default var), `neighborhoodFinder` (von Neumann,
radius 1), `splineCallGrowthRate`/`initialMaxSplineCallLimit`/`maxSplineCallLimit`
(a second, independent growth schedule — 0.1 / 10 / 400 — governing how many
SPLI+NE rounds one retrospective iteration gets), `lineSearchIterMax` (10),
`spliMaxIterations` (5). The internal no-improvement stopping window defaults
to **10** (`defaultNoImproveThresholdForRSPLINE`) — overriding the generic
`SolutionChecker` default of 5.

*Worth knowing:* CRN usage is asymmetric by design — the piecewise-linear
interpolation step evaluates its simplex vertices under common random numbers,
but neighborhood-enumeration evaluations are independent (no CRN). See
`RSplineSolverExample.kt` for a complete run.

### 5.5 `GeneticAlgorithmSolver`

A generational, elitist genetic algorithm. One population of `populationSize`
individuals; each generation: sort by penalized objective, carry the top
`eliteCount` forward unchanged, fill the rest of the population by selecting
parents (`selectionOperator`), recombining them with probability `crossoverRate`
(`crossoverOperator`), and mutating each offspring with probability
`mutationRate` (`mutationOperator`, which then independently perturbs each gene
with its own rate). Stops after a no-improvement window or `maxIterations`.

**Factory — `Solver.createGeneticAlgorithmSolver(...)`**

| Parameter | Type | Default | Purpose |
|---|---|---|---|
| `problemDefinition` | `ProblemDefinition` | required | — |
| `modelBuilder` | `ModelBuilderIfc` | required | — |
| `populationSize` | `Int` | `defaultPopulationSize` = **30** | Individuals per generation. |
| `selectionOperator` | `SelectionOperatorIfc` | `TournamentSelection()` (tournamentSize=3) | Parent-selection strategy. |
| `crossoverOperator` | `CrossoverOperatorIfc` | `BlendCrossover()` (alpha=0.5) | Recombination strategy. |
| `mutationOperator` | `MutationOperatorIfc?` | `null` → `GaussianMutation(problemDefinition)` (perGeneRate=0.1, sigmaFactor=0.1) | Mutation strategy. |
| `startingPoint` | `MutableMap<String, Double>?` | `null` | — |
| `maxIterations` | `Int` | `gaDefaultMaxIterations` = **100** | Generation cap. |
| `replicationsPerEvaluation` | `Int` | **30** | — |
| `solutionCache` | `SolutionCacheIfc` | `MemorySolutionCache()` | — |
| `simulationRunCache` | `SimulationRunCacheIfc?` | `null` | — |
| `experimentRunParameters` | `ExperimentRunParametersIfc?` | `null` | — |
| `streamNum` | `Int` | `0` | — |
| `streamProvider` | `RNStreamProviderIfc` | `RNStreamProvider()` | — |
| `name` | `String?` | `null` | — |
| `parallelOptions` | `ParallelEvaluationOptions` | `ParallelEvaluationOptions()` | — |

**Not constructor or factory parameters at all** — public `var`s, settable only
after the solver exists: `crossoverRate` (default **0.9**), `mutationRate`
(default **0.1**), `eliteCount` (default **1**), plus optional dynamic-override
hooks `populationSizeFn`/`mutationRateFn` (both `null` by default).

> **Mutation probability compounds.** `mutationRate` (0.1) gates whether the
> mutation operator runs on an offspring at all; the default `GaussianMutation`'s
> own `perGeneRate` (0.1) then independently gates each coordinate. The real
> default per-gene-per-generation mutation probability is ≈ 0.1 × 0.1 = **1%**,
> not the 10% either number suggests in isolation.

**Operators available**, one row per implementation, with their own tunable
parameters:

| Family | Class (default in **bold**) | Own parameters |
|---|---|---|
| Selection | **`TournamentSelection`** | `tournamentSize: Int = 3` |
| | `RouletteWheelSelection` | none (fitness-proportional, windowed for negative values) |
| | `RankSelection` | `selectionPressure: Double = 1.5` (range [1,2]) |
| Crossover | **`BlendCrossover`** (BLX-α) | `alpha: Double = 0.5` |
| | `SinglePointCrossover` | none |
| | `UniformCrossover` | `swapProbability: Double = 0.5` |
| Mutation | **`GaussianMutation`** | `perGeneRate: Double = 0.1`, `sigmaFactor: Double = 0.1` |
| | `UniformResetMutation` | `perGeneRate: Double = 0.1` |

*Worth knowing:* if `eliteCount >= populationSize`, evolution effectively
freezes (no selection/crossover/mutation happens) — keep elite count well below
population size.

### 5.6 `ParticleSwarmSolver`

Global-best (gbest) particle swarm optimization. Each particle has a position
and velocity; every iteration, velocity updates toward both the particle's own
best-seen position (`cognitiveCoefficient`) and the swarm's best-seen position
(`socialCoefficient`), damped by an `inertiaSchedule`; the whole swarm's
positions are evaluated as **one batched multi-point request per iteration**
(this is what makes parallel evaluation attractive here specifically). Stops on
no-improvement or when the swarm's normalized diameter collapses below
`diameterThreshold`.

**Factory — `Solver.createParticleSwarmSolver(...)`**

| Parameter | Type | Default | Purpose |
|---|---|---|---|
| `problemDefinition` | `ProblemDefinition` | required | — |
| `modelBuilder` | `ModelBuilderIfc` | required | — |
| `swarmSize` | `Int` | `defaultSwarmSize` = **30** | Number of particles. |
| `inertiaSchedule` | `InertiaWeightScheduleIfc` | `LinearDecreasingInertia()` (initial=0.9, final=0.4, horizon=100) | Inertia weight `w` schedule — large early (exploration), small late (exploitation). |
| `cognitiveCoefficient` | `Double` | **1.49445** | Pull toward the particle's own best (`c1`). |
| `socialCoefficient` | `Double` | **1.49445** | Pull toward the swarm's global best (`c2`). |
| `boundaryHandler` | `BoundaryHandlerIfc` | `ClampToBounds()` | What happens when a particle moves outside the input ranges. |
| `startingPoint` | `MutableMap<String, Double>?` | `null` | — |
| `maxIterations` | `Int` | `psoDefaultMaxIterations` = **100** | Iteration cap (factory and both direct constructors agree at 100). |
| `replicationsPerEvaluation` | `Int` | **30** | — |
| `solutionCache` | `SolutionCacheIfc` | `MemorySolutionCache()` | — |
| `simulationRunCache` | `SimulationRunCacheIfc?` | `null` | — |
| `experimentRunParameters` | `ExperimentRunParametersIfc?` | `null` | — |
| `streamNum` | `Int` | `0` | — |
| `streamProvider` | `RNStreamProviderIfc` | `RNStreamProvider()` | — |
| `name` | `String?` | `null` | — |
| `parallelOptions` | `ParallelEvaluationOptions` | **`ParallelEvaluationOptions(enabled = true)`** | **The one exception among every solver in this package** — PSO defaults to parallel because it always evaluates the whole swarm as one batch. `numWorkers = null` (full machine width) and `shortCircuitSinglePoint = true` come along with it. |

**Not exposed by the factory at all** — direct-constructor-only or
post-construction: `velocityInitializer` (constructor default `ZeroVelocity()`;
factory callers can't choose `UniformRandomVelocity()` without either
constructing directly or mutating the property afterward), `vMaxFraction`
(property, default **0.2** — velocity clamp as a fraction of each input's
range), `diameterBasedStoppingEnabled` (property, default `true`),
`diameterThreshold` (property, default **0.001**), `swarmSizeFn`/
`coefficientSchedule` (both `null`, dynamic-override hooks).

*Worth knowing:* `LinearDecreasingInertia()`'s `horizon` does **not**
auto-sync with a caller-supplied `maxIterations` — it resolves from
`psoDefaultMaxIterations` (100) at the moment the default expression runs. If
you raise `maxIterations` to, say, 500 while leaving `inertiaSchedule` at its
default, the schedule finishes decaying at iteration 100 and then holds flat
for the remaining 400 — pass a matching custom schedule if you change the
iteration cap materially.

### 5.7 `BayesianOptimizationSolver`

Sequential, model-based optimization for expensive objectives: fit a Gaussian-
process surrogate to every point observed so far (including its per-point noise
variance), then use a cheap `acquisitionOptimizer` to maximize an
`AcquisitionFunctionIfc` **against the surrogate only** (no simulation) to pick
the single most promising next point. That one point is the only real
(expensive) evaluation per iteration.

**Factory — `Solver.createBayesianOptimizationSolver(...)`**

| Parameter | Type | Default | Purpose |
|---|---|---|---|
| `problemDefinition` | `ProblemDefinition` | required | — |
| `modelBuilder` | `ModelBuilderIfc` | required | — |
| `initialDesignSize` | `Int` | `defaultInitialDesignSize` = **10** | Space-filling points evaluated in one batch before the surrogate-driven loop starts. |
| `acquisition` | `AcquisitionFunctionIfc` | `ExpectedImprovement()` (xi=0.0) | Which acquisition function scores candidates. |
| `startingPoint` | `MutableMap<String, Double>?` | `null` | Added on top of the generated initial design (so the real initial batch can be `initialDesignSize + 1`). |
| `maxIterations` | `Int` | `boDefaultMaxIterations` = **100** | Iterations *after* the initial design (factory and both direct constructors agree at 100). |
| `replicationsPerEvaluation` | `Int` | **30** | — |
| `solutionCache` | `SolutionCacheIfc` | `MemorySolutionCache()` | — |
| `simulationRunCache` | `SimulationRunCacheIfc?` | `null` | — |
| `experimentRunParameters` | `ExperimentRunParametersIfc?` | `null` | — |
| `streamNum` | `Int` | `0` | — |
| `streamProvider` | `RNStreamProviderIfc` | `RNStreamProvider()` | — |
| `name` | `String?` | `null` | — |
| `parallelOptions` | `ParallelEvaluationOptions` | `ParallelEvaluationOptions()` (sequential — parallelism would only help the initial-design batch anyway) | — |

**The factory exposes only 2 of the solver's 6 strategy knobs**
(`initialDesignSize`, `acquisition`). The other four take the direct
constructor's defaults silently unless you construct `BayesianOptimizationSolver`
yourself:

| Knob | Default implementation | Its own parameters |
|---|---|---|
| `surrogate: SurrogateModelIfc` | `GaussianProcessModel(problemDefinition)` | `kernel: StationaryKernel = RBFKernel(problemDefinition)` (signal variance 1.0, per-dimension length scales from input ranges); `constantMean: Double? = null`; `jitter: Double = 1.0E-8` (numerical-stability floor, escalated ×10 up to 6 times if the covariance isn't positive-definite) |
| `acquisitionOptimizer: AcquisitionOptimizerIfc` | `SampledAcquisitionOptimizer()` | `numCandidates: Int = 512`, `numLocalRestarts: Int = 5`, `localFraction: Double = 0.1` |
| `hyperparameterFitter: HyperparameterFitterIfc` | `FixedHyperparameters()` | `lengthScaleFactor: Double = 1.0` (heuristic, non-iterative) — `MleHyperparameterFitter` is the alternative (`numStarts=10`, random multi-start log-likelihood search) |
| `initialDesign: InitialDesignIfc` | `LatinHyperCubeDesign()` | none — `RandomFeasibleDesign()` is the alternative |
| `incumbentRule: IncumbentRuleIfc` | `BestPosteriorMeanIncumbent()` | none — noise-robust (uses the surrogate's smoothed estimate, not raw observations); `BestObservedIncumbent()` is the raw-observation alternative |

Two kernels are available: `RBFKernel` (squared-exponential, default — assumes
a very smooth response surface) and `Matern52Kernel` (less smooth, often more
realistic). Three acquisition functions: `ExpectedImprovement` (default,
`xi: Double = 0.0`), `ProbabilityOfImprovement` (`xi: Double = 0.0`),
`LowerConfidenceBound` (`beta: Double = 2.0`).

**Not constructor/factory parameters** — post-construction `var`s:
`refitEvery` (default **1** — how often, in iterations, the surrogate's
hyperparameters are refit), `maxArchiveSize` (default `null` — no cap; when
set, bounds the GP's O(n³) cost by trimming to the best solutions).

*Worth knowing:* the no-improvement stopping window (`defaultNoImproveThresholdForBO`
= **10**) is silently bypassed whenever a custom `solutionQualityEvaluator` is
attached.

### 5.8 The ISC / COMPASS family — `ISCSolver`, `NichingGeneticAlgorithmSolver`, `CompassSolver`

The most complex algorithm in the package. `ISCSolver` is a **phase state
machine**, not an algorithm in its own right — it orchestrates three phases in
sequence:

1. **Global (niching GA).** `NichingGeneticAlgorithmSolver` evolves a
   population, identifying multiple *niches* (locally-best clusters) and using
   fitness sharing to keep the population spread across them rather than
   collapsing onto one basin. Output: one seed point per niche found.
2. **Local (COMPASS).** For each niche seed, in turn, `CompassSolver` runs a
   fresh local search: it builds the *most-promising area* — the region at
   least as close to the current best as to every point visited so far,
   intersected with the problem's real constraints — samples candidates from
   inside it, and refines until the region collapses to a single point. Output:
   one "local optimum" per niche.
3. **Clean-up (ranking and selection).** The collected local optima are
   screened (provably-not-best candidates are dropped) and the survivor
   compared under Rinott's indifference-zone procedure (if `deltaC > 0`) or
   just the sample-best (if `deltaC = 0`) — giving a formal correct-selection
   guarantee when the indifference zone is set.

A single knob governs how much of this is "real": `deltaC` (with `deltaL`
defaulting to it) defaults to `problemDefinition.indifferenceZoneParameter`,
itself `0.0` by default — meaning **out of the box, ISC silently runs in
degraded mode with no formal statistical guarantee at either the local or
global level.** Set a meaningful indifference zone on your problem to get the
paper's actual guarantees.

**Factory — `Solver.createISCSolver(...)`**

| Parameter | Type | Default | Purpose |
|---|---|---|---|
| `problemDefinition` | `ProblemDefinition` | required | — |
| `modelBuilder` | `ModelBuilderIfc` | required | — |
| `deltaC` | `Double` | `problemDefinition.indifferenceZoneParameter` (→ 0.0 unless you set it) | Clean-up indifference zone. |
| `deltaL` | `Double` | `deltaC` | COMPASS local-optimality indifference zone. |
| `skipGlobalPhase` | `Boolean` | `false` | Skip the niching-GA phase entirely and run one COMPASS search from the starting point — the unimodal special case. |
| `globalBudget` | `Int?` | `null` | Optional replication budget added as a soft transition rule to a **default-built** global phase only; ignored if you supply your own. |
| `maxIterations` | `Int` | `iscDefaultMaxIterations` = **1000** (the only solver defaulting to 1000 rather than 100, because it counts orchestration macro-steps; factory and constructor agree) | Orchestration macro-step cap (GLOBAL/LOCAL/CLEANUP steps combined). |
| `replicationsPerEvaluation` | `Int` | **30** | — |
| `solutionCache` | `SolutionCacheIfc` | `MemorySolutionCache()` | — |
| `simulationRunCache` | `SimulationRunCacheIfc?` | `null` | — |
| `experimentRunParameters` | `ExperimentRunParametersIfc?` | `null` | — |
| `streamNum` | `Int` | `0` | Shared by ISC's internal NGA and COMPASS sub-solvers. |
| `streamProvider` | `RNStreamProviderIfc` | `RNStreamProvider()` | — |
| `name` | `String?` | `null` | — |
| `parallelOptions` | `ParallelEvaluationOptions` | `ParallelEvaluationOptions()` | — |

> **The `createISCSolver` factory has no `startingPoint` parameter, but the
> inherited `.startingPoint` var is honored** — set it after construction. All
> three ISC classes consult it: `ISCSolver` seeds the phase machine from it
> (`startingPoint ?: startingPoint()`), `NichingGeneticAlgorithmSolver` injects
> it into the initial population, and `CompassSolver` uses it when no explicit
> `.seed` is set (precedence `seed → startingPoint → random`). A
> `startingPointGenerator` (`StartingPointIfc`) also works. With
> `skipGlobalPhase = true`, the single COMPASS search runs directly from that
> starting point.

**`NichingGeneticAlgorithmSolver` and `CompassSolver` have no factory of their
own** — `createISCSolver`/`createRandomRestartISCSolver` are the only two
factories for this whole family. Both classes are independently constructible
via their direct constructors if you want just one phase standalone.

`NichingGeneticAlgorithmSolver`'s notable constructor parameters (beyond the
common `problemDefinition`/`evaluator`/`streamNum`/`streamProvider`/
`maxIterations`/`replicationsPerEvaluation`/`name` every solver has):
`populationSize: Int = 50`; `nicheIdentifier`, `fitnessSharing`, `grouping`
(noise-aware statistical grouping, α=0.1), `ranking` (linear-rank, η=1.5),
`sampling` (stochastic universal sampling), `mating` (dynamic inbreeding
restriction, m=10), `crossover` (arithmetical), `mutation` (uniform, rate
defaults to 1/dimension) — each a pluggable strategy object with a sensible
library default; `conserveNicheCenters: Boolean = false` (elitism for niche
centers); `transitionRules: List<NgaTransitionRuleIfc> = [SingleNicheRule(), ImprovementRule()]`
(global→local transition fires when **any** rule triggers).

`CompassSolver`'s notable constructor parameters: `sampleSize: Int = 5`
(candidates drawn per iteration); `sar: SimulationAllocationRuleIfc = FixedScheduleSAR(initialReplications=5, epsilon=0.01)`
(replication-allocation schedule — swap in `OcbaSAR` for OCBA-style
allocation); `redundancyChecker = BruteForceRedundancyChecker()` (prunes
non-binding search-region boundaries; `SimplexRedundancyChecker` is the
LP-based alternative recommended in higher dimensions); `pruneEvery: Int = 5`;
`deltaL: Double = problemDefinition.indifferenceZoneParameter` (defaults to the
problem's indifference zone — symmetric with `ISCSolver`'s own `deltaL`, and
`0.0` unless you set that on the problem, which selects COMPASS's degraded
local-optimality mode); `maxReplications: Int = 50_000` (hard safety cap on total
replications for one COMPASS run). For a standalone `CompassSolver` the explicit
`seed: InputMap?` property takes precedence, and the inherited `startingPoint`
is the fallback when `seed` is null.

> **Clean-up cost is quadratic in the noise/indifference-zone ratio.** Rinott's
> second-stage sample size for each survivor is `ceil((h·σ/δ_C)²)` — noisier
> problems and tighter indifference zones both drive it up sharply, independent of
> `maxIterations`, bounded only by `maxCleanUpReplicationsPerSystem` (default
> 20,000/survivor). On a noisy problem with a non-trivial indifference zone this
> can dominate the whole run's replication budget, so budget for it explicitly
> (or set a smaller `deltaC`).

### 5.9 `RandomWalkSolver` and `RandomRestartSolver`

**`RandomWalkSolver`** unconditionally accepts every random neighbor — no
comparison against the current point at all. It doesn't optimize; it's a
landscape-analysis / calibration tool (e.g., feeding `SimulatedAnnealing`'s
temperature estimator). **No factory exists** for it. Its sole constructor has
no `@JvmOverloads`, no per-parameter KDoc, and — unusually — no default
`ReplicationPerEvaluationIfc` convenience overload; you must build a
`FixedReplicationsPerEvaluation(n)` yourself. Both `maximumIterations` and
`replicationsPerEvaluation` are required, with no fallback default. Its `name`
defaults to the literal string `"RandomWalk"`, not `null` like every sibling
class.

**`RandomRestartSolver`** wraps any inner solver and repeatedly runs it from
randomized starting points, keeping the best solution across all restarts. In
this class, **the base `Solver` concept of "iteration" literally means
"restart"** — `maxNumRestarts` is what gets passed as `maximumIterations` up
the constructor chain. Restart 0 honors a caller-supplied starting point; every
other restart draws a fresh random feasible point.

Every `create*Solver` factory has a `createRandomRestart*Solver` sibling that
adds exactly:

| Parameter | Default | Purpose |
|---|---|---|
| `maxNumRestarts` | `RandomRestartSolver.defaultMaxRestarts` = **5** | How many restarts to perform. |
| `concurrentRestarts` | **1** | Restarts on one worker at a time (sequential) if 1; run up to this many restarts concurrently otherwise. Confirmed present on both the SHC and SA random-restart factories (and, by the same shared helper, every other one). |
| `concurrentOptions` | `ConcurrentRunOptions()` | Worker count, stream-block size, optional confirmation stage for concurrent restarts (see §7-adjacent `SolverPortfolio` machinery — the two share the same underlying `ConcurrentSolverRunner`). |

`concurrentRestarts > 1` and a solver's own `parallelOptions.enabled = true`
are mutually exclusive — the concurrency budget is spent at one level or the
other, enforced with a `require()` at construction. In **sequential** mode
(the default), the inner solver is one long-lived, reused instance
(`restartingSolver`), which is exactly what lets a `NestedConsoleSolverStateTracker`
attach to it (§6). In **concurrent** mode, each restart gets its own instance
built by a `SolverFactoryIfc` on its own worker thread, and per-restart
instrumentation goes through `innerSolverDecorator` instead.

*Worth knowing:* `clearCacheBetweenRuns` (default `true`) is structural under
concurrency — setting it `false` with `concurrentRestarts > 1` throws at
`initializeIterations()`, since each concurrent restart already gets a private,
freshly-created cache by construction.

---

## 6. Trackers — observing a solver as it runs

`ksl.simopt.solvers.trackers` is not something `Solver` knows about by name —
every `Solver` exposes two generic `Emitter` properties for free
(`iterationEmitter: Emitter<SolverStateSnapshot>`,
`lifeCycleEmitter: Emitter<SolverStatus>`), and a tracker is just a subscriber.
Building a tracker does **not** subscribe it — call `tracker.startTracking()`
explicitly (idempotent) to attach, and `tracker.stopTracking()` to detach. A
tracker that stays attached across multiple sequential `runAllIterations()`
calls on the same solver transparently records each run in turn.

Emission happens at `INITIALIZED` (a baseline iteration-0 snapshot, only built
if something is actually listening), `STARTED`, once per iteration (throttled
by `snapShotFrequency`, default **1** = every iteration), and at `COMPLETED` or
`ERROR`.

### The 6 concrete tracker classes

| Class | Key constructor parameters | Sink |
|---|---|---|
| `ConsoleSolverStateTracker` | `solver`, optional format-strategy lambdas | `println` |
| `CsvSolverStateTracker` | `solver`, `outputFile: File` (or a `fileName: String` convenience overload → `KSL.outDir`), `columns: List<TrackerColumn>` | CSV file, append mode; opens on `INITIALIZED`, flushes/closes on `COMPLETED`/`ERROR` |
| `DataFrameSolverStateTracker` | `solver`, `columns: List<DataFrameColumn>` | in-memory `AnyFrame`, rebuilt on every terminal event; `clearData()` resets it |
| `NestedConsoleSolverStateTracker` | `macroSolver`, `microSolver`, optional format lambdas | `println`, micro lines indented |
| `NestedCsvSolverStateTracker` | `macroSolver`, `microSolver`, `outputFile`/`fileName`, `columns: List<NestedTrackerColumn>` | one CSV, macro/micro rows interleaved, discriminated by a `Level` column |
| `NestedDataFrameSolverStateTracker` | `macroSolver`, `microSolver`, `columns: List<NestedDataFrameColumn>` | in-memory `AnyFrame`; default column set is noticeably smaller than its CSV sibling (5 vs. 9) |

Custom columns are two different idioms: `TrackerColumn`/`DataFrameColumn`/
`NestedTrackerColumn`/`NestedDataFrameColumn` (a `(headerName, extractor)` pair
you can mix with library-provided constants) for the CSV/DataFrame trackers, or
whole-line formatter lambdas for the Console trackers. None of the CSV column
extractors escape commas/quotes for you automatically if you write a custom one
with a comma-bearing value.

### Nested vs. plain — the rule that actually matters

**A nested tracker only makes sense when there's exactly one long-lived inner
solver instance, re-run sequentially by the outer solver.** In this codebase,
that's `RandomRestartSolver` in **sequential** mode only:

```kotlin
val tracker = NestedConsoleSolverStateTracker(solver, solver.restartingSolver)
tracker.startTracking()
solver.runAllIterations()
```

`SolverPortfolio` structurally cannot use a nested tracker — it never has a
single micro solver; each member's actual instance is materialized
concurrently on its own worker thread. The real library code special-cases
exactly this distinction (branching on `is RandomRestartSolver` to choose
nested vs. plain tracking). For a portfolio, or for `RandomRestartSolver` with
`concurrentRestarts > 1`, attach a **plain** tracker per member instead, via
`SolverMemberTask.innerSolverDecorator` / `RandomRestartSolver.innerSolverDecorator`
— invoked on the member's own worker thread with the freshly-created solver, so
whatever it touches must be thread-safe, and each concurrent member typically
needs its own distinct sink.

```kotlin
// Plain console tracker on any standalone solver.
val console = ConsoleSolverStateTracker(solver)
console.startTracking()

// CSV tracker with an explicit file path.
val csv = CsvSolverStateTracker(solver, File(outputDir, "trace.csv"))
csv.startTracking()
```

---

## 7. Benchmarking solvers (`ksl.simopt.benchmark`)

Comparing solvers fairly is its own hard problem: an "iteration" costs wildly
different simulation effort across algorithms, a shared starting point keeps
one solver from winning by luck, and the best-looking estimate on a noisy
problem is partly the luckiest one. `ksl.simopt.benchmark` packages a policy
that handles all of this — equal replication budgets, common starting points
per macro-replication, CRN-based confirmation of finalists, and gap accounting
against a known or best-found optimum — so that

> problems × solver configurations × macro-replications → database → analysis

is a one-page program.

### Quick start

This is essentially `ksl.examples.general.simopt.BenchmarkDemo.kt`, runnable
as-is:

```kotlin
val experiment = BenchmarkExperiment(
    name = "myFirstStudy",
    problems = listOf(
        NoisySphere(dimension = 2, noiseLevel = NoiseLevel.LOW).problemCase(),
        lkInventoryProblemCase()
    ),
    solverCases = standardSolverCases(),
    macroReplications = 5,
    replicationBudgetPerRun = 2000,
    verificationReplications = 100      // re-simulate each winner at 100 reps
)
val summary = experiment.run()

val db = BenchmarkResultsDb("myFirstStudy.db", KSL.dbDir)
val expId = db.saveSummary(summary)

for (problem in summary.problemResults) {
    println("${problem.problemName}: winner = ${problem.winner?.inputMap}")
}
```

A problem enters the harness as a `ProblemCase(name, problemDefinitionFactory, evaluatorFactoryProvider, referenceSolution?, tags)`
— note this is a *different*, benchmark-specific wrapper around a
`ProblemDefinition`, not the `ProblemDefinition` itself; `lkInventoryProblemCase()`
(from `ksl.examples.general.simopt.InventoryProblemCases`) builds one by
wrapping the same `makeLKInventoryModelProblemDefinition()`/`BuildLKModel` pair
this guide's own examples use. A solver enters as a `SolverCase(label, solverFactory, description)`
— `standardSolverCases()` is the ready-made "vanilla" registry (SHC, SA, CE,
R-SPLINE, and a sequential-random-restart variant, at library defaults).
`BenchmarkExperiment.run()` returns a `BenchmarkSummary`: per-problem results,
each with cell-level results, the confirmation outcome, the winner, and the
gap basis.

### What you get, and where it's kept

Cells of one problem run concurrently on a bounded worker pool; problems run
sequentially. `BenchmarkResultsDb` is SQLite by default and **appends** —
successive experiments accumulate in one file unless you pass
`deleteIfExists = true`. A synthetic problem ladder with known optima
(`NoisySphere`, `NoisyRosenbrock`, `NoisyRastrigin`, `ConstrainedNoisyQuadratic`,
`Newsvendor`, `MultiItemNewsvendor` — all under `ksl.examples.general.simopt.problems`)
lets studies use noise level as a controlled factor without needing a real
simulation model at all.

**This section is intentionally an orientation, not the full methodology** —
the five governing policies (equal budgets, common starting points,
confirmation, gap recording, determinism), the complete results-database
schema, the analysis feeds (multiple-comparison data, performance profiles),
and a full worked walkthrough (the pilot study: 425 cells, zero failures,
~16.5 minutes) all live in the dedicated **[`ksl-simopt-benchmark`](ksl-simopt-benchmark.md)**
guide — read that once you're past "does this harness do what I need."

---

## 8. The key types at a glance

For full member lists, see the Dokka API reference. This is the orientation map.

**Problem** (`ksl.simopt.problem`)

| Type | Role |
|---|---|
| `ProblemDefinition` | The problem: `objFnResponseName`, `inputNames`, `responseNames`, `optimizationType`; declare variables (`inputVariable`), constraints (`linearConstraint`/`functionalConstraint`/`responseConstraint`), penalties, starting points; feasibility/objective/penalty computation. |
| `InputDefinition` | One decision variable: name, `[lowerBound, upperBound]`, `granularity` (1.0 ⇒ integer-ordered). |
| `InputMap` | An immutable, validated `Map<String,Double>` of one input point; the cache key. Built via `problem.toInputMap(...)` / `generateRandomInputValues(...)`. |
| `OptimizationType` / `InequalityType` | `MINIMIZE`/`MAXIMIZE`; `LESS_THAN`/`GREATER_THAN` (all constraints internally `≤`). |
| `LinearConstraint` / `FunctionalConstraint` / `ResponseConstraint` | Deterministic linear, deterministic non-linear, and probabilistic (`E[R(x)]`) constraints. |
| `PenaltyFunction` (abstract) + `PenalizableConstraint` | A non-negative penalty bound to a constraint that reports its own violation. `DynamicPolynomialPenalty` is the memoryless default (`basePenalty · k^iterExp · violation^violExp`); `ParkKimPenalty` (Park & Kim 2015 PFM) adds cross-visit memory via a `PenaltySequence` (`AppreciateDepreciateSequence`) + `PenaltyMemory`. Set problem-wide (`defaultLinearPenalty` / `defaultFunctionalPenalty` / `defaultResponsePenalty`) or per-constraint (the `penaltyFunction` arg on `linearConstraint(...)` / `responseConstraint(...)`). |
| `StartingPointIfc` / `FixedStartingPoint` | Supplies a feasible starting point; default is random. |

**Evaluator / oracle** (`ksl.simopt.evaluator`)

| Type | Role |
|---|---|
| `EvaluatorIfc` / `Evaluator` | Turns an `EvaluationRequest` into `Solution`s, applying caching + CRN and merging replications. Factory: `Evaluator.createProblemEvaluator(problemDefinition, modelBuilder, …)`. |
| `SimulationOracleIfc` | The "something that can simulate a request" seam. |
| `SimulationProvider` / `ParallelSimulationProvider` | Oracles backed by one reused model / many fresh models (a `ModelBuilderIfc`). |
| `ResponseFunctionOracle` / `MCReplicationOracle` | Analytic / Monte-Carlo oracles (no `Model`) through the same seam. |
| `Solution` | An evaluated point: `inputMap`, `estimatedObjFnc`, `responseEstimates`, feasibility; `estimatedObjFncValue`, `penalizedObjFncValue`, `penaltyMemory`, `asString()`. `compareTo` orders by penalized objective (within-iteration search); cross-iteration selection uses `FeasibilityFirstComparator`. |
| `FeasibilityFirstComparator` | Clock-independent ordering (validity → statistical response-feasibility → objective, then violation) that selects the recommended `Solver.bestSolution` — distinct from the iteration-relative penalized-objective search order. |
| `Solutions` / `SolutionsIfc` | Bounded (default capacity 10), penalized-objective-ordered set; `possiblyBest(…)`, `orderedSolutions`, `toDataFrame()`. |
| `EstimatedResponse` | One response's `(name, average, variance, count)`; `merge`/`pooledVariance` combine independent samples. |
| `ResponseMap` / `EvaluationRequest` / `ModelInputs` | Response estimates for a model / a batch request / one design point + replication count. |
| `ParallelEvaluationOptions` | `enabled`, `numWorkers`, `shortCircuitSinglePoint` — selects parallel multi-point evaluation. |

**Solvers** (`ksl.simopt.solvers`, `.algorithms` [+ `.bo`, `.genetic`, `.isc`, `.pso`], `.concurrent`, `.trackers`) — full parameter reference in §5, trackers in §6.

| Type | Role |
|---|---|
| `Solver` | Abstract search base: `runAllIterations()`, `bestSolution` (feasibility-first recommended answer, screened at `recommendationCILevel`), `penalizedIncumbent` (protected within-iteration search incumbent), `bestSolutions`, `maximumNumberIterations`, `replicationsPerEvaluation`, `solutionQualityEvaluator`, `printResults()`, `solverResult`; `iterationEmitter`/`lifeCycleEmitter` for trackers. |
| `StochasticSolver` | Intermediate base adding RNG control + acceptance-sampled starting points. |
| Concrete algorithms | `StochasticHillClimber`, `RandomWalkSolver`, `SimulatedAnnealing`, `CrossEntropySolver`, `RSplineSolver`, `GeneticAlgorithmSolver`, `ParticleSwarmSolver`, `BayesianOptimizationSolver`, `ISCSolver`, `NichingGeneticAlgorithmSolver`, `CompassSolver` — §5. |
| `RandomRestartSolver` | Wraps an inner solver, restarting from random feasible points; optional concurrent restarts. |
| `ReplicationPerEvaluationIfc` (`FixedReplicationsPerEvaluation`, `FixedGrowthRateReplicationSchedule`) | Reps-per-evaluation strategy. |
| `ReplicationBudgetStoppingCriterion` / `SolutionQualityEvaluatorIfc` | Equal-effort stop / the general stopping hook. |
| `NeighborhoodFinderIfc` (`MooreNeighborhoodFinder`, `VonNeumannNeighborhoodFinder`), `GenerateNeighborIfc` | Discrete neighborhood generation for local search. |
| `SolverResult` / `EvaluatorMetrics` | Immutable run snapshot / evaluator usage + cache savings. |
| `*SolverStateTracker` | Console / CSV / DataFrame progress trackers (+ `Nested*`) — §6. |
| `SolverPortfolio`, `SolverMemberTask`, `SolverFactoryIfc`, `ConcurrentRunOptions`, `ConfirmationOptions`, `PooledMemberEvaluatorFactory`, `ConcurrentSolverRunner` | Race several solvers concurrently on one problem (blocking API). |

**Cache** (`ksl.simopt.cache`)

| Type | Role |
|---|---|
| `SolutionCacheIfc` / `MemorySolutionCache` | Cache of evaluated `Solution`s keyed by `ModelInputs` (default capacity 1000); the evaluator-level cache. |
| `SimulationRunCacheIfc` / `MemorySimulationRunCache` | Cache of raw `SimulationRun`s (default capacity 1000); the oracle-level cache, JSON/dataframe-exportable. |
| `EvictionRuleIfc` / `SimulationRunEvictionRuleIfc` | Pluggable eviction strategy. |

**Benchmark** (`ksl.simopt.benchmark`) — full detail in the [benchmark guide](ksl-simopt-benchmark.md)

| Type | Role |
|---|---|
| `BenchmarkExperiment` | The engine: problems × solver cases × macro-replications → `BenchmarkSummary`. |
| `ProblemCase` / `SolverCase` | A benchmark-ready problem (definition + evaluator-factory provider + optional reference solution) / a named, problem-agnostic solver configuration. |
| `BenchmarkResultsDb` | SQLite results store; appends by default. |

---

## 9. Gotchas and best practices

- **Pass `maxIterations` explicitly for a run whose length matters.** Each
  solver's default is small (100 iterations; ISC's 1000 macro-steps) and lives in
  a mutable `@JvmStatic var` companion property, so the effective default can be
  changed elsewhere in the JVM session. The factory and the direct constructor use
  the same default — there is no factory-vs-constructor discrepancy (§5).

- **Concurrent runners need a fresh model per unit.** Build solvers and
  portfolio members from a `ModelBuilderIfc` that returns a *new* `Model` each
  call — the oracle builds a model per evaluation (pooled under concurrency),
  and a builder that returns a shared instance corrupts results.

- **The concurrent simopt entry points are ordinary blocking calls**, unlike
  `ksl.controls.experiments`'s `suspend` parallel runners.
  `portfolio.runAllIterations()` and a concurrent `RandomRestartSolver` both
  hide `runBlocking` internally — you never write it yourself.

- **The penalty is correctly oriented for both `MINIMIZE` and `MAXIMIZE`.**
  `penaltyFncValue()` returns the total constraint penalty **unsigned** (always
  ≥ 0) and `penalizedObjFncValue` *adds* it to the minimization-oriented
  objective (`objFncFactor · average`), so a violation always makes a solution
  worse regardless of optimization direction. The penalty is deliberately **not**
  multiplied by `objFncFactor` (doing so would flip its sign for `MAXIMIZE`).

- **Read `bestSolution` for the answer, not the penalized incumbent.** The
  reported `solver.bestSolution` is chosen feasibility-first and is
  clock-independent (§2.2); the penalized objective — whose multiplier grows with
  the iteration counter — is a *within-iteration* search key only (the protected
  `penalizedIncumbent`). Don't rank final solutions by `penalizedObjFncValue`
  across iterations; use `bestSolution` / `bestSolutions.possiblyBest()`. Tune the
  feasibility confidence with `solver.recommendationCILevel` (default 0.99).

- **A penalty with memory needs a solution cache.** `ParkKimPenalty` only
  accumulates its cross-visit violation measure when design points are
  *re-sampled*, which requires a `SolutionCacheIfc` on the evaluator (the
  `create*Solver` factories supply one; a hand-built `Evaluator` defaults to
  none). Without a cache — or for a point that is never revisited — it degrades
  to its memoryless fallback (`DynamicPolynomialPenalty`) and the evaluator logs
  a warning.

- **A solver's population/design size can't exceed the feasible input lattice.**
  For an integer-ordered problem with a small grid, a requested population or
  space-filling-design size may be larger than the number of distinct feasible
  points. `sampleInputFeasiblePoints` enumerates the exact feasible set (or
  bounded-rejection-samples) and returns *fewer* points rather than looping
  forever, logging what limited it; `ProblemDefinition.feasiblePointCapacity(n)`
  reports this up front. Reduce the size, refine an input's granularity, or widen
  a range.

- **`createISCSolver` has no `startingPoint` parameter, but the inherited
  `startingPoint` var is honored** — set it after construction (§5.8). All three
  ISC classes consult it.

- **`createParticleSwarmSolver` is the only factory that defaults to parallel
  evaluation.** Every other solver defaults to sequential.

- **Caching is on for factory-built solvers, off for a hand-built
  `Evaluator`.** `Evaluator.createProblemEvaluator(...)` defaults
  `solutionCache = MemorySolutionCache()`, but the direct constructor
  `Evaluator(problemDefinition, oracle)` defaults `cache = null`. If you
  assemble the evaluator yourself and want caching, pass a cache explicitly.

- **Input names are model input keys (a control *or* an RV parameter); response
  names are model responses.** A mismatch surfaces when the evaluator builds and
  validates the model (at solver *creation*, via `createProblemEvaluator`), not
  mid-run — but only if you go through the factory. Name model elements explicitly
  and pin the correspondence with a test
  (`problemDefinition.validateProblemDefinition(model)`), exactly as the benchmark
  cases do.

- **R-SPLINE, ISC, and COMPASS need integer-ordered problems.** Each one's
  constructor `require`s `problemDefinition.isIntegerOrdered` and throws an
  `IllegalArgumentException` if not, so a continuous problem is rejected at
  construction rather than silently mis-searched. (`NichingGeneticAlgorithmSolver`
  run standalone does not enforce it, but the `ISCSolver` orchestrator that builds
  it does.)

- **When maximizing, mind which "objective" number you read.**
  `Solution.estimatedObjFncValue` is oriented by the optimization type
  (sign-flipped for `MAXIMIZE`), but `Solution.asString()` prints the raw
  estimated average. They differ in sign for maximization — compare like
  with like.

- **Model builders must return fresh, independent models.** Same "pure
  constructor" contract as `ModelBuilderIfc` everywhere else in KSL.

- **Don't crown a winner from one point estimate.** Use
  `bestSolutions.possiblyBest()` (or a `SolverPortfolio` confirmation stage /
  the benchmark harness's confirmation) before declaring a winner.

- **Naming quirks.** A factory name isn't always its class name plus a suffix
  (`createStochasticHillClimberSolver` → `StochasticHillClimber`,
  `createSimulatedAnnealingSolver` → `SimulatedAnnealing`), and the iteration cap
  goes by three names across the API (`maximumIterations` ctor arg,
  `maxIterations` factory arg, `maximumNumberIterations` property) — they refer to
  the same thing.

---

## 10. See also

- **Input/response naming:** [`ksl-controls`](ksl-controls.md) — the
  `elementName.propertyName` control-key convention a problem's input names use.
- **Comparing solvers fairly, in depth:** [`ksl-simopt-benchmark`](ksl-simopt-benchmark.md)
  — the full methodology behind §7: equal-budget experiments, confirmation,
  the results database schema, and the pilot-study walkthrough.
- **The model substrate:** [`ksl-simulation`](ksl-simulation.md) — `Model`,
  `ModelBuilderIfc`, run parameters.
- **The statistics behind estimates and best-selection:**
  [`ksl-utilities-statistic`](ksl-utilities-statistic.md).
- **The desktop GUI** over these classes: the [Simopt app](apps/simopt.md).
- **Theory and workflow:** the [KSL Book](https://rossetti.github.io/KSLBook/),
  **Chapter 11** — problem definition, the simulation oracle, algorithm
  characteristics, the implemented solvers, and applying a solver.
- **Runnable, compiled examples**, all under `KSLExamples`:
  `CESolverExample.kt` and `SARestartSolverExample.kt` (`ksl.examples.book.chapter11`);
  `StochasticHillClimberExample.kt` and `RSplineSolverExample.kt` (new,
  `ksl.examples.general.simopt`, alongside the shared
  `makeLKInventoryModelProblemDefinition`/`BuildLKModel` helpers every example
  in this guide uses, and the `GeneticAlgorithmSolverTesting.kt`/
  `ParticleSwarmSolverTesting.kt`/`BayesianOptimizationSolverTesting.kt`/
  `ISCSolverTesting.kt` files, each already carrying real KDoc); the benchmark
  quick-start's `BenchmarkDemo.kt` and the full pilot study `PilotStudy.kt`
  (also `ksl.examples.general.simopt`).
