# Guide: Benchmarking Simulation-Optimization Solvers (`ksl.simopt.benchmark`)

A guide for **researchers and students** who want to set up, run, and compare
KSL's simulation-optimization solvers across sets of problems, with results
captured to a database for statistically defensible analysis. It covers the
package's purpose and design, how to define problems and solver
configurations, how to run experiments and analyze the results, and a full
walkthrough of the pilot study that validated the harness.

Prerequisites: working knowledge of KSL simulation models and of the simopt
basics (`ProblemDefinition`, solvers, evaluators) — see the KSL book's
simulation-optimization chapter. All code shown is Kotlin against `KSLCore`
(the harness) and `KSLExamples` (the ready-made problem cases and demos).

---

## 1. What this package is for

Comparing optimization algorithms on simulation problems sounds simple and is
notoriously easy to do wrong. A credible comparison needs *equal computational
budgets* (an "iteration" means wildly different simulation effort for
cross-entropy than for hill climbing), *common starting points* (so a solver
cannot win by drawing a lucky start), *repeated macro-replications* (one run
per configuration supports no statistical statement), *honest winner
selection* (solvers report "bests" estimated with different precision — the
apparent winner is often just the luckiest estimate), and *complete records*
(what parameters actually ran, what was consumed, what was found).

`ksl.simopt.benchmark` packages all of that policy into one engine so that a
study of the form

> problems × solver configurations × macro-replications → database → analysis

is a one-page program. Two audiences share the artifact: students learn the
canonical setup pattern from small demos built on the harness, and research
studies get a reproducible, budget-fair experimental engine whose every run is
recorded.

**In scope:** the experiment engine, a synthetic problem ladder with known
optima, DEDS (discrete-event) problem wrappers, an SQLite results schema,
and analysis feeds (multiple-comparison data, performance profiles,
convergence traces).

**Out of scope:** new optimization algorithms (the harness runs the solvers
that exist), GUI, and server/config-driven execution (a possible future
layer).

## 2. The mental model

A **benchmark experiment** is a grid. Each **cell** is one solver
configuration run once on one problem — cell identity is
`(problem, solver case, macro-replication)`, and each cell gets a label like
`noisySphere_d2_MED_SHC_r3` that follows it into solver names, log lines, and
the database.

Five policies govern every cell:

1. **Equal replication budgets.** Every cell's solver is stopped once its
   cumulative *requested simulation replications* reach the experiment's
   budget. The check happens between solver iterations, so population-based
   algorithms can overshoot by up to one generation — the *actual* consumption
   is recorded per run, and analysis normalizes by it. The budget criterion
   replaces a solver's own heuristic convergence stops (no-improvement
   checkers, sampler convergence): under an equal-budget comparison, every
   algorithm spends its budget rather than quitting early by its own taste.
   The solver's iteration ceiling is also set to the budget, which is provably
   generous (every iteration requests at least one replication).

2. **Common starting points.** For each (problem, macro-replication), one
   starting point is pre-drawn from the experiment's stream and given to
   *every* solver case — solvers race from the same place, and
   macro-replications vary the place. Population-based solvers that ignore
   starting points simply ignore them.

3. **Confirmation.** After a problem's cells finish, the top candidate
   solutions are re-evaluated under common random numbers (a paired
   comparison) and the problem's winner is picked from those *confirmed*
   estimates — standard ranking-and-selection hygiene against
   winner-selection bias.

4. **Gap recording.** When a problem has a reference solution, every run's
   optimality gap is computed against it (`KNOWN_OPTIMUM` or `BEST_KNOWN`);
   otherwise runs are gapped against the best found across the experiment
   (`BEST_FOUND`). Gaps are oriented so larger is worse regardless of whether
   the problem minimizes or maximizes.

5. **Determinism.** Starting points, per-cell random-number-stream blocks, and
   solver-side streams are all fixed at launch. For a fixed configuration the
   entire summary is identical regardless of how many worker threads run the
   cells (this is asserted by a test, not just claimed).

Cells of one problem run concurrently on a bounded worker pool; problems run
sequentially.

## 3. Where everything lives

| Package / module | Contents |
|---|---|
| `ksl.simopt.benchmark` (KSLCore) | The engine: `BenchmarkExperiment`, `ProblemCase`, `SolverCase`, `BenchmarkSolverFactoryIfc`, `FunctionMemberEvaluatorFactory`, result records (`BenchmarkSummary` etc.), `ReferenceSolution`/`GapType`, and `BenchmarkResultSink`/`BenchmarkSummaryHeader` — the per-problem checkpoint seam (§7.1) |
| `ksl.simopt.benchmark.io` (KSLCore) | `BenchmarkResultsDb` (SQLite, and itself a `BenchmarkResultSink`) + one table-data class per table + analysis feeds (`mcbDataMap`, `performanceProfile`) + `ConfirmationReplay`, which re-selects a finished study offline (§8.7) |
| `ksl.simopt.evaluator` (KSLCore, additions) | `ResponseFunctionIfc`/`ResponseFunctionBuilderIfc` + `ResponseFunctionOracle` — lets a response-function component stand in for a DEDS model at the oracle seam, with macro/micro replication semantics |
| `ksl.simopt.solvers` (KSLCore, addition) | `ReplicationBudgetStoppingCriterion` — the equal-effort termination rule |
| `ksl.examples.general.simopt` (KSLExamples) | `standardSolverCases()` registry, LK/RQ problem cases, `BenchmarkDemo`, `PilotStudy` |
| `ksl.examples.general.simopt.problems` (KSLExamples) | The synthetic ladder: noisy sphere / Rosenbrock / Rastrigin, constrained noisy quadratic, single- and multi-item newsvendor, `NoiseLevel` |
| `ksl.examples.general.models.inventory` (KSLExamples) | `twoEchelonProblemCase(...)` + its model builder |
| `ksl.examples.general.supplychain` (KSLExamples) | `multiEchelonNetworkProblemCase()` + its model builder |

## 4. Quick start

The smallest complete study — one synthetic problem, one DEDS problem, the
standard solver registry, results to a database (this is essentially
`BenchmarkDemo.kt`, which you can run as-is):

```kotlin
import ksl.examples.general.simopt.lkInventoryProblemCase
import ksl.examples.general.simopt.standardSolverCases
import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.examples.general.simopt.problems.NoiseLevel
import ksl.examples.general.simopt.problems.NoisySphere
import ksl.utilities.io.KSL

fun main() {
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
        for (run in problem.runs) {
            println("  ${run.cellLabel}: best=${run.bestObjective} gap=${run.gap} " +
                    "consumed=${run.numReplicationsRequested}")
        }
    }
    println("Database: ${KSL.dbDir.resolve("myFirstStudy.db")} (experiment $expId)")
}
```

Everything else in this guide elaborates the three ingredients: problems
(§5), solver cases (§6), and what you get back (§7–§8).

## 5. Defining problems

A problem enters the harness as a `ProblemCase`:

```kotlin
class ProblemCase(
    val name: String,                                   // unique within an experiment
    val problemDefinitionFactory: () -> ProblemDefinition,
    val evaluatorFactoryProvider: (ProblemDefinition) -> MemberEvaluatorFactoryIfc,
    val referenceSolution: ReferenceSolution? = null,   // known/best-known optimum, if any
    val tags: Map<String, String> = emptyMap()          // analysis grouping: family, dimension, ...
)
```

The two factories are called fresh for each run of the problem. The
`evaluatorFactoryProvider` receives the problem-definition instance the
harness just created — the member evaluators **must** be built against that
same instance (solutions validate their problem identity against it), which is
why it is an argument rather than something the provider builds for itself.

What unifies the two kinds of problems is the provisioning interface: each
concurrently running cell gets *private* evaluation resources (its own
oracle/model, cache, and a non-overlapping block of the random-number
sub-stream tape).

### 5.1 Synthetic / static Monte Carlo problems

Write the problem as a small component in the standard KSL style — random
variables with explicit stream numbers, acquired at construction against the
provider the instance is built with, exactly like a simulation model's random
variables — and supply a builder that creates a fresh instance per concurrent
member (the response-function counterpart of `ModelBuilderIfc`):

```kotlin
fun interface ResponseFunctionIfc {
    fun replication(inputs: Map<String, Double>): Map<String, Double>   // one observation
}
fun interface ResponseFunctionBuilderIfc {
    fun build(streamProvider: RNStreamProviderIfc): ResponseFunctionIfc
}

// a noisy function with two randomness sources, each on its own dedicated stream
val builder = ResponseFunctionBuilderIfc { streamProvider ->
    val demandRV = ExponentialRV(50.0, streamNum = 1, streamProvider = streamProvider)
    val noiseRV = NormalRV(0.0, 4.0, streamNum = 2, streamProvider = streamProvider)
    ResponseFunctionIfc { inputs ->
        mapOf("profit" to profit(inputs.getValue("q"), demandRV.value) + noiseRV.value)
    }
}
```

The contract is the reproducibility guarantee: acquire **all** streams at
construction (never inside `replication` — the oracle detects a mid-evaluation
stream request and fails loudly, because a stream created after positioning
would silently break common random numbers), draw nothing from a global
stream, and keep no other mutable state. In exchange, CRN — synchronized *per
randomness source* when each source has its own stream — per-member isolation,
and bit-for-bit experiment reproducibility all work exactly as they do for
simulation models.

**Replication semantics** mirror the simulation case and the macro/micro
vocabulary of `ksl.utilities.mcintegration`: one *replication* (the unit
solvers request and the budget counts) is one statistical observation — the
average of `microRepSampleSize` calls of the function (default 1, so an
observation is a single raw evaluation). A larger micro sample gives
observations the averaged, near-normal character of a DEDS replication's
within-replication average; the budget then costs `microRepSampleSize` raw
evaluations per replication, which a study should report.

Wrap the builder with `FunctionMemberEvaluatorFactory(problemDefinition,
builder, microRepSampleSize)` in the `evaluatorFactoryProvider` — or skip all
of this and use the ready-made ladder (§9), whose classes produce complete
`ProblemCase`s with known-optimum references and tags via
`.problemCase(microRepSampleSize)`.

### 5.2 DEDS (simulation-model) problems

Provide a `ModelBuilderIfc` that returns a *fresh, independent* model per call
and wrap it with the pooled factory:

```kotlin
ProblemCase(
    name = "RQInventory",
    problemDefinitionFactory = { makeRQInventoryModelProblemDefinition() },
    evaluatorFactoryProvider = { pd -> PooledMemberEvaluatorFactory(pd, BuildRQModel) },
    tags = mapOf("family" to "inventoryDEDS", "dimension" to "2", "constrained" to "true")
)
```

Models are pooled and reused across cells (builds settle at the worker count,
not the cell count), which is safe because every run positions its streams
absolutely and run parameters are restored after each request.

Naming is the part that bites: the problem definition's **input names must be
control keys** of the model (`elementName.propertyName` — see the
`ksl-controls` guide) and its **response names must be response names the
model produces**. Name your model elements explicitly so keys are stable, and
pin the correspondence with a test:

```kotlin
val model = BuildMyModel.build(null, null)
assertTrue(problemDefinition.validateProblemDefinition(model))
```

`KSLExamples` does exactly this in `BenchmarkProblemCaseValidationTest` for
every shipped case — renames surface at test time, not mid-benchmark.

### 5.3 Reference solutions

```kotlin
ReferenceSolution(
    inputs = mapOf("x1" to 3.0, "x2" to -2.0),
    objectiveValue = 0.0,
    type = ReferenceType.KNOWN_OPTIMUM     // or BEST_KNOWN
)
```

With a reference, every run's gap is exact (or best-known-relative). Without
one, gaps are computed against the best objective found across the problem's
valid runs in the experiment (`GapType.BEST_FOUND`) — the best run gaps to
zero by construction. Problems with no closed-form optimum (the multi-echelon
network) start with no reference; maintain a `BEST_KNOWN` reference as study
results accumulate.

## 6. Defining solver cases

A `SolverCase` is a *named, problem-agnostic* solver configuration:

```kotlin
class SolverCase(
    val label: String,                       // unique within an experiment
    val solverFactory: BenchmarkSolverFactoryIfc,
    val description: String = ""
)

fun interface BenchmarkSolverFactoryIfc {
    fun create(problemDefinition: ProblemDefinition, evaluator: EvaluatorIfc,
               memberIndex: Int, name: String): Solver
}
```

The factory contract: return a **new** solver instance on every call, bind it
only to the supplied evaluator, and let it keep its own fresh stream provider
(the solver constructors' default). The same case runs on every problem in
the grid — that is why the problem definition arrives as an argument.

Cases are **budget-agnostic**: configure the algorithm (schedules, population
sizes, replications per evaluation) but not its termination. The experiment
wraps every factory so the created instance receives the replication-budget
criterion and the iteration ceiling; a `maxIterations` set in your factory is
overridden. If your factory installs its own `solutionQualityEvaluator`, the
wrap composes them (either one stops the solver).

The **standard registry** in `KSLExamples` (`BenchmarkSolverCases.kt`) is the
starting list — the "vanilla" configurations at library defaults:

| Label | Algorithm | Notes |
|---|---|---|
| `SHC` | Stochastic hill climbing | |
| `SA` | Simulated annealing | default temperature config + exponential cooling |
| `CE` | Cross-entropy | default normal sampler / sample size / elite fraction |
| `RSPLINE` | R-SPLINE | integer-ordered problems only (granularity-1 inputs) |
| `RestartSHC` | Sequential random restarts around SHC | outer solver aggregates inner consumption; overshoot bounded by one restart |

A variant is a one-line addition:

```kotlin
SolverCase("SHC50", BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
    StochasticHillClimber(pd, evaluator, replicationsPerEvaluation = 50, name = name)
})
```

Whatever configuration actually ran is captured from each case's first created
instance (its `configurationProperties`) into the database — recorded, not
assumed.

## 7. Running an experiment

```
BenchmarkExperiment(
    name = "...",
    problems = ...,                          // List<ProblemCase>, unique names
    solverCases = ...,                       // List<SolverCase>, unique labels
    macroReplications = 10,
    macroReplicationRange = 1..10,           // default: all of them; a sub-range runs one block
    replicationBudgetPerRun = 3000,
    confirmation = ConfirmationOptions(topK = 3, replicationsPerCandidate = 50), // default; null disables
    captureIterationTraces = false,          // opt-in per-iteration progress capture
    captureSolverState = false,              // opt-in again: solver state on each trace point
    verificationReplications = null,         // when set: re-simulate each winner at this many reps
    numWorkers = null,                       // default: min(cells, available processors)
    experimentStreamProvider = RNStreamProvider(),  // the experiment-level seed carrier
    resultSink = null                        // when set: checkpoint per problem, and resume (§7.1)
).run(): BenchmarkSummary
```

`captureSolverState` requires `captureIterationTraces` and is gated separately from it
because the volume is an order of magnitude larger: a solver publishing six state values
turns a study's trace rows into millions of state rows. What it buys is the direct
measurement of premature convergence — a swarm's diameter, a population's diversity, a
reference distribution's coefficient of variation — which otherwise has to be inferred
from identical results across a ninefold budget increase.

`run()` may be called once per instance (the experiment stream advances as
starting points are drawn). The returned `BenchmarkSummary` is a complete
in-memory record: per-problem results (each with cell-level
`BenchmarkRunResult`s, the confirmation outcome, the winner, the verification
estimates, and the gap basis), the captured solver configurations, timestamps,
and — when enabled — the iteration traces keyed by cell label.

The fields of `BenchmarkRunResult` worth knowing: `status`
(COMPLETED/FAILED/…), `bestObjective` (model scale) and
`bestPenalizedObjective` (the solver's internal minimization-oriented value),
`isBestValid` (false for a failed cell's placeholder), feasibility fields,
`numReplicationsRequested` (**actual** consumption — use it to normalize),
`totalIterations`, `wallClockMillis`, `cpuTimeMillis`, `gap`/`gapType`, and
`errorMessage`.

Two of its fields exist to make a cell answerable after the fact rather than only
comparable. `responseEstimates` carries the best point's average, variance and count for
**every** response including the objective, which is what lets a selection be replayed
offline (§8.7) — the inputs alone are enough to re-simulate a point but not to re-rank
it. `responseConstraintAssessments` carries the per-constraint verdict, because the
aggregate `responseConstraintViolation` beside it cannot say *which* constraint bound,
and "do particular solvers fail specifically on one coupling constraint?" is a question
studies actually ask.

Failures are isolated: a cell whose solver throws records `FAILED` with the
problem's bad solution and the error message; sibling cells are unaffected.

### 7.1 Running a long study without losing it

A study that takes a weekend is a study that must not be interrupted — unless it can
be resumed. `macroReplicationRange` runs one block of the macro-replications and
leaves the rest for later:

```kotlin
// today
BenchmarkExperiment(name = "study", macroReplications = 30,
                    macroReplicationRange = 1..10, /* … */).run()
// tomorrow, same everything else
BenchmarkExperiment(name = "study", macroReplications = 30,
                    macroReplicationRange = 11..30, /* … */).run()
```

**The result is identical to running 1..30 at once.** Each cell addresses its
randomness by its **absolute** macro-replication number — where that cell would sit
if the whole study ran as one experiment — so a starting point does not depend on how
the run happened to be sliced. `macroReplications` stays at the study's full size in
every block; only the range changes. Positions are assigned by a problem's index in
the list, so appending a problem to a study already partly run is safe while
inserting one is not.

Each block is saved as its own experiment row. Give the blocks distinct names if you
want them distinguishable in the database, and pool them for analysis with the
collection-taking overloads in §8.4. The confirmation and verification stages run per
block, on the runs that block contains.

**Blocks do nothing for a study that is one experiment**, which is what the long ones
usually are: `run()` builds every problem's result in memory and writes once at the end,
so an interruption at hour 30 of a 32-hour run loses all of it. A `resultSink` moves the
checkpoint boundary to where the control flow already has one — `runProblem` finishes a
problem's cells, confirmation and verification before the next begins — so six problems
become six checkpoints and an interruption costs at most one problem:

```kotlin
val db = BenchmarkResultsDb("study.db", KSL.dbDir, deleteIfExists = false)
BenchmarkExperiment(name = "study", problems = sixProblems, resultSink = db, /* … */).run()
```

Re-run that same call after an interruption and it resumes: the sink is asked which
problems an **unfinished** record of that name already holds, and those are skipped. A
record counts as finished only once `endExperiment` stamps its end time, so a completed
study is never resumed into — re-running one appends beside it under a fresh id, which is
the append behaviour `saveSummary` has always had. Where several same-named records are
unfinished, the most recent is resumed, so a second interruption continues the latest
attempt rather than an abandoned one.

`BenchmarkResultsDb` implements `BenchmarkResultSink`, and `saveSummary` is routed
through that same implementation, so a study saved in one call and one streamed
problem-by-problem travel identical code and cannot drift apart. Any other destination —
a CSV writer, a remote store — is four methods: `beginExperiment`, `problemCompleted`,
`endExperiment`, and `completedProblems`, which defaults to returning nothing so a sink
that cannot answer never causes a problem to be skipped. Silence has to mean "run it".

> **A resumed run's return value covers only this pass.** `run()` still returns a
> `BenchmarkSummary`, but on a resume it holds the problems this pass ran and not the
> skipped ones, which are in the sink. Read a resumed study back from the database rather
> than from the value `run()` handed you.

## 8. The results database

This schema is documented nowhere else, so this section is a full reference:
every table, every field, and — the part that matters for a study — what each
one lets you *analyze*.

### 8.1 Storage, population, and identity

`BenchmarkResultsDb` is an ordinary KSL **SQLite** database (it extends
`SQLiteDb`), written to `dbDirectory` (default `KSL.dbDir`). You populate it in
one call after a run:

```kotlin
val db = BenchmarkResultsDb("pilotStudy.db", KSL.dbDir, deleteIfExists = false)
val expId = db.saveSummary(summary, kslVersion = "R1.4")   // one BenchmarkSummary → one experiment
```

- **Append by default.** With `deleteIfExists = false`, an existing file is kept
  and only missing tables are created, so successive `saveSummary` calls
  accumulate. Pass `deleteIfExists = true` to start clean.
- **Database-assigned ids.** `expId` (per experiment) and `runId` (per cell) are
  allocated as `MAX(id) + 1` at save time. That is why a later trace-enabled
  rerun of one problem lands under a *fresh* `expId`/`runId` beside the earlier
  results instead of colliding with them. `expId` is the hub key on almost every
  table; `runId` links a cell to its trace.

### 8.2 How the tables relate

Thirteen tables, hung off the experiment. Bracketed columns are the primary key;
indentation is the parent → child (logical foreign-key) direction. There are no
enforced foreign keys — the relationships are by shared column, so you join on
them yourself.

```
tblExperiment            [expId]
├── tblProblem           [expId, problemName]
│   ├── tblProblemConstraint     [expId, problemName, responseName]
│   ├── tblConfirmation          [expId, problemName, candidateNum]
│   ├── tblConfirmationSummary   [expId, problemName]
│   └── tblVerification          [expId, problemName, responseName]
├── tblSolverCase        [expId, solverLabel]
│   └── tblSolverCaseParameter  [expId, solverLabel, paramName]
└── tblRun               [runId]   — the cell = expId × problemName × solverLabel × repNum
    ├── tblRunResponse        [runId, responseName]
    ├── tblRunConstraint      [runId, responseName]
    └── tblIterationTrace     [runId, iteration]
        └── tblIterationTraceState [runId, iteration, stateName]
```

`tblRun` is the fact table (one row per benchmark *cell*); the rest give it the
context — what experiment, what problem, what solver configuration — plus the two
confirmation/verification stages and the opt-in convergence trace.

Five of them answer questions the aggregates cannot. `tblProblemConstraint` records what
a run had to **meet**, which the archive otherwise never held: without it, "was every
verified winner feasible?" meant hand-coding the constraint table from the specification
and joining it externally. `tblRunConstraint` and `tblRunResponse` decompose a cell's
single violation figure and single objective into one row per constraint and per
response. `tblConfirmationSummary` says what the selection stage could and could not
decide. `tblIterationTraceState` carries the solver state that `captureSolverState`
turns on.

### 8.3 Table-by-table field reference

Types: `Int?`/`Double?`/`Long?`/`String?` mark nullable columns; **PK** marks a
primary-key column; **→ tbl…** marks a column you join on. Every `…Json` column
is a `kotlinx.serialization` JSON object — an input point is a
`{"inputName": value, …}` map of `Double`s; `tagsJson` is a `String`→`String`
map.

**`tblExperiment`** — one row per experiment (one `saveSummary`).

| Field | Type | Meaning / analytical use |
|---|---|---|
| `expId` | Int **PK** | The experiment id (DB-assigned). Every analysis starts by choosing one. |
| `expName` | String | The experiment's name. |
| `startTime`, `endTime` | String | Wall-clock start/end instants — elapsed study duration and provenance. |
| `replicationBudgetPerRun` | Int | The equal replication budget each cell received. **The normalization basis** — every cross-solver comparison assumes this was equal, and `performanceProfile` divides by it. |
| `macroReplications` | Int | Independent macro-replications per (problem, solver) cell — your statistical sample size for variance and confidence intervals. |
| `numProblems`, `numSolverCases` | Int | The grid's shape (rows expected in `tblProblem` / `tblSolverCase`). |
| `confirmationTopK` | Int? | Confirmation stage: how many finalists were re-raced (null ⇒ no confirmation stage ran). |
| `confirmationReplications` | Int? | Replications per finalist in confirmation (null ⇒ none). |
| `verificationReplications` | Int? | Replications used to re-simulate each winner (null ⇒ no verification). |
| `tracesCaptured` | Boolean | Whether `tblIterationTrace` holds rows for this experiment — check before asking for convergence curves. |
| `kslVersion` | String? | The KSL version you passed, for reproducibility. |

*Use it to:* pick the `expId`; confirm budget parity before comparing; and learn
which optional stages (confirmation, verification, traces) exist before querying
them.

**`tblProblem`** — one row per problem within an experiment. Without this table a
run's numbers are uninterpretable (you can't orient them or compute a gap).

| Field | Type | Meaning / analytical use |
|---|---|---|
| `expId` | Int **PK** → tblExperiment | |
| `problemName` | String **PK** | Join key to runs, confirmations, verifications. |
| `dimension` | Int | Number of decision variables — a difficulty axis to stratify by. |
| `optimizationType` | String | `"MINIMIZE"` or `"MAXIMIZE"`. **Sets the sign convention** — you must know it before comparing `bestObjective` values. |
| `numResponseConstraints` | Int | Number of stochastic constraints; `> 0` means feasibility (not just objective) decides the winner. |
| `tagsJson` | String? | JSON map of problem tags (e.g. `noiseLevel`) — your controlled study factors, for stratifying results. |
| `referenceType` | String? | `KNOWN_OPTIMUM` or `BEST_KNOWN` when the problem has a real reference; null when the basis is the experiment's best-found. |
| `referenceObjective` | Double? | The reference's objective value (null for best-found). |
| `referenceInputsJson` | String? | Reserved by the schema; the current writer leaves it null. |
| `gapType` | String? | The basis a run's `gap` was measured against: `KNOWN_OPTIMUM`, `BEST_KNOWN`, or `BEST_FOUND` (relative gap, where the best run is 0 by construction). |
| `gapBasisObjective` | Double? | The numeric basis value gaps are computed from — populated for *all* gap types (it is what `performanceProfile` tests against). |
| `winnerInputsJson` | String? | The problem's winning input point (JSON map). |
| `winnerObjective` | Double? | The winner's objective on the natural (model) scale. |

*Use it to:* orient objectives correctly; recompute or sanity-check gaps against
the right basis; slice results by dimension, constraint count, or tag; and read
off the per-problem winner.

**`tblProblemConstraint`** — one row per (experiment, problem, response constraint):
the constraint as the problem *defines* it, rather than what a run achieved against it.

| Field | Type | Meaning / analytical use |
|---|---|---|
| `expId`, `problemName` | Int, String **PK** → tblProblem | |
| `responseName` | String **PK** | The constrained response. |
| `rhsValue` | Double | The right-hand side the constraint is stated against. |
| `inequalityType` | String | `LESS_THAN` / `GREATER_THAN`, as the problem declares it. |
| `target` | Double | The target the assessment is made against. |
| `tolerance` | Double | The tolerance allowed around it. |

*Use it to:* make the archive self-describing. Joined to `tblVerification` it answers
"was every verified winner actually feasible?" in one query; without it that question
needed the specification hand-copied into a table and joined from outside, which is a
step that rots the moment a problem's limits are retuned.

**`tblSolverCase`** — one row per solver configuration in the experiment.

| Field | Type | Meaning / analytical use |
|---|---|---|
| `expId` | Int **PK** → tblExperiment | |
| `solverLabel` | String **PK** | The case's label — the join key wherever a run or parameter names a solver. |
| `description` | String | Human-readable description of the configuration. |

**`tblSolverCaseParameter`** — one row per configuration property of a solver
case, flattened from **the first solver instance the case actually built** — the
configuration that ran, not what was assumed.

| Field | Type | Meaning / analytical use |
|---|---|---|
| `expId`, `solverLabel` | Int, String **PK** → tblSolverCase | |
| `paramName` | String **PK** | The property name. |
| `paramValue` | String | Its value, stringified. |

*Use it to:* attribute a performance difference to a specific setting (e.g.
`maxIterations`, a mutation rate); audit that budgets/parameters were what you
intended; and reproduce a case exactly.

**`tblRun`** — **the fact table.** One row per *cell*: one solver case run once
(one macro-replication) on one problem under the experiment's budget.

| Field | Type | Meaning / analytical use |
|---|---|---|
| `runId` | Int **PK** | Cell id (DB-assigned); links to `tblIterationTrace`. |
| `expId` | Int → tblExperiment | |
| `problemName` | String → tblProblem | |
| `solverLabel` | String → tblSolverCase | The three columns above locate the cell in the grid. |
| `repNum` | Int | Macro-replication index — the statistical replication key; group by it for means/variances/CIs. |
| `cellLabel` | String | Stable composite label (solver × problem × rep) — the key that lines traces up with runs. |
| `status` | String | Cell outcome: `COMPLETED`, `FAILED`, or `STOPPED_BEFORE_START`. **Filter to `COMPLETED` before comparing.** |
| `startingPointJson` | String | The run's starting point (JSON map) — check whether starts were controlled or randomized. |
| `bestInputsJson` | String | The run's best input point (JSON map) — recompute a *true* objective here for synthetics. |
| `bestObjective` | Double | Best objective on the natural scale, from the solver's estimate. The primary quality number when a gap basis is absent. |
| `bestPenalizedObjective` | Double | Best minimization-oriented penalized objective (objective + constraint penalties) — the internal search key. |
| `bestValid` | Boolean | Whether the returned best is a valid solution. |
| `inputFeasible` | Boolean | Whether the best point satisfies the deterministic (input/linear/functional) constraints. |
| `responseConstraintViolation` | Double | Total response-constraint violation at the best point (0 ⇒ feasible). |
| `numOracleCalls` | Int | Distinct simulation (oracle) calls consumed — the cache-adjusted work done. |
| `numReplicationsRequested` | Int | Total replications requested — **the effort currency**; normalize quality by this, not by the nominal budget (batch solvers overshoot by up to a generation). |
| `totalIterations` | Int? | Solver iterations (null if the solver doesn't report them). |
| `wallClockMillis` | Long? | Wall-clock time — machine-dependent, so a secondary (not the fair) cost axis. |
| `gap` | Double? | Optimality gap versus the problem's basis; larger is worse, and for `BEST_FOUND` the best run is 0. Null when the problem has no basis. |
| `gapType` | String? | Which basis this run's gap used (mirrors the problem's `gapType`). |
| `errorMessage` | String? | Failure detail when `status` is not `COMPLETED`. |

*Use it to:* almost everything. Group by `solverLabel` within a problem to rank
quality (`bestObjective`/`gap`); gate on `status`, `bestValid`, `inputFeasible`,
and `responseConstraintViolation` for admissibility (a great objective on an
infeasible point is not a win); measure reliability by counting non-`COMPLETED`
rows per solver; and check effort parity or compute quality-per-replication from
`numReplicationsRequested`.

**`tblRunResponse`** — one row per (run, response): the cell best's estimate for every
response of the problem, the objective included.

| Field | Type | Meaning / analytical use |
|---|---|---|
| `runId` | Int **PK** → tblRun | |
| `responseName` | String **PK** | The response estimated. |
| `average` | Double | Its sample average at the cell's best point. |
| `variance` | Double | Its sample variance. **NaN** is what a single-observation estimate legitimately carries. |
| `count` | Double | The number of observations behind the estimate. |

*Use it to:* **replay a selection offline** (§8.7). `tblRun` preserves the best point's
inputs, which is enough to re-simulate it but not to re-rank it — ranking a candidate
needs each response's average, variance and count. With those stored, a different
confirmation rule can be applied to a finished study and only the finalists it chooses
need fresh simulation. It also answers the ordinary question of what a cell's *other*
responses were doing while its objective improved.

**`tblRunConstraint`** — one row per (run, response constraint): how the cell best stood
against each constraint separately.

| Field | Type | Meaning / analytical use |
|---|---|---|
| `runId` | Int **PK** → tblRun | |
| `responseName` | String **PK** | The constrained response. |
| `estimate` | Double | The cell best's estimate for it. |
| `violation` | Double | By how much this constraint alone is violated. The rows sum to `tblRun.responseConstraintViolation`. |
| `ciUpperLimit` | Double? | The one-sided upper confidence limit. **Null** when the estimate carried fewer than two observations, so there was no sample variance and no interval. |
| `feasibleAtCI` | Boolean | Whether this constraint on its own can be declared feasible. |

*Use it to:* find out **which** constraint bound. `tblRun.responseConstraintViolation` is
retained unchanged beside this and is the aggregate; an aggregate cannot answer "do
particular solvers fail specifically on one coupling constraint?", which is the question
a constrained study is usually run to settle.

> `feasibleAtCI = false` on a row whose `ciUpperLimit` is null means "not shown
> feasible", not "shown infeasible". Read the two columns together.

**`tblConfirmation`** — one row per confirmed finalist of a problem's
confirmation stage (present only when confirmation ran).

| Field | Type | Meaning / analytical use |
|---|---|---|
| `expId`, `problemName` | Int, String **PK** → tblProblem | |
| `candidateNum` | Int **PK** | 1-based finalist index. |
| `inputsJson` | String | The finalist's input point. |
| `objective` | Double | Its CRN-confirmed objective estimate (re-raced at higher replication under common random numbers). |
| `penalizedObjective` | Double | Its CRN-confirmed penalized objective. |
| `numReplications` | Double | Replications behind the confirmed estimate. |
| `isWinner` | Boolean | Whether this finalist is the confirmed winner. |

*Use it to:* make **statistically defensible** winner statements. A single macro-
replication's "best" is partly luck; the confirmation stage re-races the top
finalists together under CRN, so `isWinner` here (and the margin between
finalists) is the number to report — **once you have checked
`tblConfirmationSummary.selectionDegenerate` for that problem.** A winner drawn from a
degenerate selection is not a defensible statement, and nothing on this table says so.

**`tblConfirmationSummary`** — one row per problem whose confirmation stage ran, saying
what that stage could and could not decide.

| Field | Type | Meaning / analytical use |
|---|---|---|
| `expId`, `problemName` | Int, String **PK** → tblProblem | |
| `numCandidates` | Int | How many candidates entered ranking. |
| `numConfidentlyFeasible` | Int | How many of them could be declared feasible at the confirmation confidence level. |
| `selectionDegenerate` | Boolean | True when **none** could. Always false for a problem with no response constraints. |
| `numConfidentlyFeasibleAfterScreening` | Int? | The count at the screening precision, or null when no screening stage ran. |
| `numOracleCalls` | Int | Oracle calls the stage spent. |
| `numReplicationsRequested` | Int | Replications the stage spent — what confirmation cost, separately from the search. |

*Use it to:* **decide whether the winner means anything.** Confirmation ranks
feasibility-first, so when no candidate can be declared confidently feasible the
comparator has nothing to discriminate on: every candidate falls through to the
constraint-violation tie-break and the objective plays no part in choosing the winner.
That is how a FacilitySizing winner verifying at P(stockout) = 0.122 against a 0.05 limit
was once reported with exactly the same confidence as any other result. `isWinner` on
`tblConfirmation` is silent about it; this row is not.

Degeneracy is usually a precision problem rather than a solver problem. The estimates
reaching confirmation come from the search, at the weakest precision anywhere in the
pipeline: on a P(event) ≤ 0.05 constraint at 99% confidence with 30 replications per
evaluation, only a candidate observing **zero** violations in all 30 can be declared
feasible — one violation puts it at 0.033, comfortably inside the limit, and it is
rejected anyway. Read `numConfidentlyFeasibleAfterScreening` beside the flag: a degenerate
row followed by a positive count there records a selection that screening rescued, and a
zero records a screening stage that could not.

*Written whenever a confirmation stage ran*, including the case where `tblConfirmation`
gets no rows at all because there was a single distinct finalist — which is exactly a case
where knowing the selection could not discriminate still matters.

**`tblIterationTrace`** — one row per captured iteration of a run's trace
(opt-in via `captureIterationTraces`). The raw material for convergence curves.

| Field | Type | Meaning / analytical use |
|---|---|---|
| `runId` | Int **PK** → tblRun | |
| `iteration` | Int **PK** | Iteration index; `0` is the initialized state. |
| `cumulativeReplications` | Int | Replications consumed through this iteration — the **x-axis** (budget) for a convergence plot. |
| `bestPenalizedObjective` | Double | Best penalized objective so far — the **y-axis**. |

*Use it to:* plot *how fast* each solver improved (not just where it ended);
compute time-to-target; and drive `performanceProfile` (§8.4).

**`tblIterationTraceState`** — one row per (run, iteration, state name) of a captured
trace, holding the cell solver's algorithm-specific state. Written only when
`captureSolverState` is on.

| Field | Type | Meaning / analytical use |
|---|---|---|
| `runId` | Int **PK** → tblRun | |
| `iteration` | Int **PK** | Matches `tblIterationTrace`. |
| `stateName` | String **PK** | What the solver called the measurement. |
| `stateValue` | Double | Its value at that iteration. |

*Use it to:* measure **premature convergence directly** rather than inferring it. A
swarm's diameter, a population's diversity, a reference distribution's coefficient of
variation: every solver publishes something, and until these rows existed the benchmark
computed them and threw them away, leaving "the solver collapsed early" to be argued from
identical results across a ninefold budget increase.

Long format is deliberate. Solvers publish different state, and a new solver — or a new
measurement on an existing one — would otherwise add a column and change the schema for
every study already in the file.

> **Volume is why this has its own flag.** A solver publishing six values per iteration
> produces roughly six times the trace row count, so a study with a couple of hundred
> thousand trace rows lands on the order of a million here.

**`tblVerification`** — one row per response of a problem's verification stage
(opt-in). The winner re-simulated at `verificationReplications`.

| Field | Type | Meaning / analytical use |
|---|---|---|
| `expId`, `problemName` | Int, String **PK** → tblProblem | |
| `responseName` | String **PK** | The objective response or a constraint response name. |
| `inputsJson` | String | The winner's input point (identical across the problem's rows). |
| `average` | Double | High-replication mean of this response at the winner. |
| `variance` | Double | Its sample variance. |
| `count` | Double | Replications behind it. |

*Use it to:* independently confirm the winner's *true* objective at high `N`, and
— crucially for constrained problems — check that each constraint response
actually satisfies its bound at high replication (feasibility the single-run
estimate may have missed). `variance`/`count` give you a confidence interval.

### 8.4 Built-in analysis feeds

`BenchmarkResultsDb` returns typed rows (one accessor per table) and three
higher-level feeds:

```kotlin
// Typed rows (each optionally scoped to one experiment):
db.experiments(); db.problems(expId); db.solverCases(expId); db.solverCaseParameters(expId)
db.runs(expId); db.confirmations(expId); db.verifications(expId); db.traces(expId)
db.problemConstraints(expId); db.runConstraints(expId); db.runResponses(expId)
db.confirmationSummaries(expId); db.traceStates(expId)

// Multiple-comparison-with-the-best feed for one problem:
//   solverLabel -> final objectives (or gaps) across the COMPLETED, valid macro-reps.
val dataMap = db.mcbDataMap(expId, "LKInventory", useGaps = false)
val analyzer = db.mcbAnalyzer(expId, "LKInventory")  // MultipleComparisonAnalyzer, or null if
                                                     // < 2 cases or unequal observation counts

// Performance (data) profile from captured traces: for each solver and each budget
// fraction, the fraction of cells that reached the gap basis + tau by that fraction of
// the budget. Returns List<PerformanceProfilePoint>(solverLabel, budgetFraction, fractionSolved).
val profile = db.performanceProfile(expId, tau = 2.0, numPoints = 10)

// All three also take a COLLECTION of experiment ids, which is how a study run in
// blocks (§7.1) is analyzed whole — no single experiment holds all of a problem's
// macro-replications:
val pooled = db.mcbAnalyzer(listOf(1, 3), "LKInventory")
val pooledProfile = db.performanceProfile(listOf(1, 3), tau = 2.0, numPoints = 10)
```

- `mcbDataMap` / `mcbAnalyzer` answer *"which solver is best on this problem, with
  statistical support?"* — they feed KSL's `MultipleComparisonAnalyzer`. The
  analyzer needs an equal number of observations per solver, so trim failed cells
  first (`mcbDataMap` already keeps only `COMPLETED` + `bestValid` rows).
- `performanceProfile` answers *"which solver reaches good solutions fastest,
  across the whole problem set?"* It requires captured traces and a gap basis;
  runs missing either are skipped.
- **Pooling across blocks.** The collection-taking forms combine the experiments you
  name. They reject a repeated macro-replication rather than counting it twice, and
  they require the pooled experiments to share a replication budget — a profile's
  budget fraction is meaningless otherwise. Blocks can also disagree on a problem's
  gap basis, and what happens then depends on which basis it is: under `BEST_FOUND`
  each block has its own best, so the basis is **recomputed over the pooled runs**;
  under `KNOWN_OPTIMUM` or `BEST_KNOWN` the basis is a fixed reference and blocks
  disagreeing on it means they are not blocks of the same study, so the call
  **fails** rather than quietly picking one.

### 8.5 Generic SQL, DataFrame, and Excel access

The typed helpers are conveniences over a normal KSL SQLite database, so the full
`DatabaseIfc` surface is available for anything they don't cover — arbitrary
`JOIN`s, cross-experiment queries, or export:

```kotlin
db.printAllTablesAsText()                              // quick console dump of every table
val rs = db.selectAll("tblRun")                        // any table as a CachedRowSet
db.exportToExcel(null, "pilotStudy.xlsx", KSL.excelDir) // every table to a workbook
// Any query into a Kotlin DataFrame for slicing/plotting:
val frame = DatabaseIfc.toDataFrame(db.fetchOpenResultSet("SELECT * FROM tblRun WHERE gap IS NOT NULL")!!)
```

The database file itself is plain SQLite, so external tools (the `sqlite3` CLI,
DB Browser, pandas, R's `DBI`) open it directly.

### 8.6 Turning fields into answers

| Question | Where to look |
|---|---|
| Which solver wins this problem, defensibly? | `tblConfirmation.isWinner` — **after** `tblConfirmationSummary.selectionDegenerate` for that problem; or `mcbAnalyzer(expId, problem)` over `tblRun` |
| How close to optimal did each run get? | `tblRun.gap` (with `tblProblem.gapType`/`gapBasisObjective` for the basis) |
| Is the "winning" point actually feasible? | `tblRun.inputFeasible` + `responseConstraintViolation`, confirmed by `tblVerification`; join `tblProblemConstraint` for what it had to meet |
| **Which** constraint bound, and for which solver? | `tblRunConstraint` per `responseName`, grouped by `tblRun.solverLabel` |
| Could the selection discriminate at all? | `tblConfirmationSummary.selectionDegenerate`, with `numConfidentlyFeasible` |
| Did the solver converge prematurely? | `tblIterationTraceState` — diameter, diversity, coefficient of variation, by `stateName` |
| Would a different selection rule have changed the winner? | replay it against `tblRunResponse` (§8.7); read `winnerChanged` |
| Which solver improves fastest? | `tblIterationTrace` curves, or `performanceProfile` |
| Was the comparison fair (equal effort)? | `tblExperiment.replicationBudgetPerRun` vs each `tblRun.numReplicationsRequested` |
| Why did a solver behave that way? | `tblSolverCaseParameter` (the config that ran) |
| How reliable is a solver? | count of `tblRun.status != 'COMPLETED'` per `solverLabel`, with `errorMessage` |
| What is the winner's true performance? | `tblVerification.average`/`variance`/`count` (high-`N`, independent) |

Two honesty rules run through all of the above:

- **Do not crown winners from raw point estimates.** On noisy problems the per-run
  `gap` (computed from the solver's *estimated* best) is optimistically biased —
  the estimate that looks best is partly the luckiest. Use `tblConfirmation` and
  `tblVerification` for winner statements; for synthetics, recompute the *true*
  objective at `bestInputsJson`.
- **Normalize by actual consumption** (`numReplicationsRequested`), not the
  nominal budget — batch solvers overshoot by up to one generation.

The **trace-enabled rerun** pattern (validated in the pilot): run the big grid
without traces (they grow with the budget), then rerun just the problem you want
convergence curves for with `captureIterationTraces = true` and `saveSummary`
into the *same* database — the traces land under the new experiment's run ids and
the old results are untouched.

### 8.7 Replaying a selection without re-running the study

Evaluating a change to the selection rule against a finished study used to mean running
the study again — days of compute to answer a question about its last few minutes. The
search is the expensive part and it does not depend on the rule: same cells, same bests.
Only the choice among those bests changes, and with `tblRunResponse` storing each
response's average, variance and count, the candidates that choice needs are already in
the database.

```kotlin
val (replayExpId, results) = ConfirmationReplay.replayExperiment(
    db, sourceExperimentName = "study", newExperimentName = "study-refeasibility",
    problemCases = sixProblems,
    options = ConfirmationOptions(topK = 5, replicationsPerCandidate = 200)
)
val flipped = results.filter { it.winnerChanged }
```

Only the finalists the new rule chooses are simulated; the rest is read back. Each
`ProblemReplayResult` carries the rebuilt candidates, the outcome under the replayed rule,
the winner the source experiment reported, and `winnerChanged` — the question a replay
exists to answer. `replayProblem` does one problem; `rebuildCandidates` stops at the
reconstructed `Solution`s if you want to rank them yourself.

**The source is never mutated.** A replay is written as its own experiment under a new
name, through the same sink an ordinary experiment uses, so every helper in §8.4 works on
it and it can be compared with its source by the same queries. The call refuses a
`newExperimentName` equal to the source's.

> **What a replay is not.** Rebuilt candidates carry the estimates the search produced,
> not the penalty state it carried — penalty memory is not stored. A rule that reads the
> penalized objective would therefore not reproduce the original, which is not a
> limitation in practice: `FeasibilityFirstComparator`, and any rule fit for
> cross-iteration selection, is clock- and penalty-independent by design. A rule that
> ranks on the penalized objective is the thing this library deliberately does not use
> for selection.

## 9. The synthetic problem ladder

Cheap problems with known optima, so gaps are exact and studies can use noise
level as a controlled factor. All inputs are on the integer lattice
(granularity 1) so integer-ordered solvers such as R-SPLINE participate; all
randomness is held as random variables acquired at construction against each
instance's provider, so CRN works per source. Each class yields
a complete `ProblemCase` via `.problemCase()`.

| Class | Purpose | Optimum (value) |
|---|---|---|
| `NoisySphere(d, level)` | unimodal sanity check | shifted lattice point (3,−2,3,…), value 0 |
| `NoisyRosenbrock(d, level)` | ill-conditioned curved valley | all-ones, value 0 |
| `NoisyRastrigin(d, level)` | regular multimodality — restart/portfolio testbed | shifted lattice point, value 0 |
| `ConstrainedNoisyQuadratic(d, level)` | isolates penalty/feasibility handling: unconstrained optimum violates E(Σx)≤3d | all-threes, value 4d |
| `Newsvendor(...)` | genuine static Monte Carlo, MAXIMIZE, critical-fractile optimum computed from the closed form | q\*≈80 for the defaults |
| `MultiItemNewsvendor(...)` | adds a binding budget constraint with a known feasible boundary; optimum by (exact) greedy marginal allocation | budget-boundary allocation |

`NoiseLevel` sets the additive Gaussian noise σ: `LOW = 1`, `MED = 10`,
`HIGH = 100` (a study factor recorded in the problem tags). The newsvendors
have no additive layer — their randomness *is* the model (tag
`noiseLevel = MODEL`).

## 10. The DEDS problem cases (KSLExamples)

| Case | Dim | Constraints | Cost per replication (measured) |
|---|---|---|---|
| `lkInventoryProblemCase()` | 2 | none | ≲ 0.2 ms |
| `rqInventoryProblemCase()` | 2 | fill rate ≥ 0.95 | ≈ 80 ms |
| `twoEchelonProblemCase(constrained = true/false)` | 4 | two fill rates (constrained variant) | ≈ 14 ms |
| `multiEchelonNetworkProblemCase()` | 8 | three retailer fill rates | ≈ 66 ms |

The multi-echelon case is worth reading as a template for optimizing over the
supply-chain domain layer: its decision variables are supply-chain *controls*
(the warehouse (R,Q) policy's `RDelta`/`Q` and each retailer (r,S) policy's
`r`/`SDelta` — delta parameterizations that make every clamped combination
valid, so the problem is purely box-constrained), and its objective is the
cost formulation's network-wide grand total response.

## 11. The pilot study — setup, execution, results

The pilot (`ksl.examples.general.simopt.PilotStudy`, runnable as-is) is the
validation pass that preceded any paper-scale use: it exercises every feature
of the harness end-to-end on a real grid and demonstrates the intended
workflow. It runs **four experiments into one database** (`pilotStudy.db`):

| # | Experiment | Grid | Wall clock |
|---|---|---|---|
| 1 | `pilotCore` | {sphere-d2, Rastrigin-d2} × {LOW, MED} + LK + RQ — 6 problems × 5 registry cases × 10 macro-reps, budget 3000, verification 200 | 544 s (RQ dominates) |
| 2 | `pilotTwoEchelon` | two-echelon constrained × 5 cases × 10 reps, budget 3000 | 330 s |
| 3 | `pilotMultiEchelon` | multi-echelon network × 5 cases × 5 reps, budget 2000 | 110 s |
| 4 | `pilotTraceRerun` | sphere-MED again, **traces on** — the append-a-rerun pattern | < 1 s |

Totals: **425 cells, zero failures, ≈ 16.5 minutes** on 12 workers.
Post-processing wrote one MCB table per core problem
(`kslOutput/pilotMcb_*.txt`), a convergence step plot from the captured traces
(`pilotConvergence_noisySphere_d2_MED.PNG`), and printed a performance
profile.

What the results showed:

- **Correctness.** On all four synthetic problems the confirmed winner is the
  *exact* known optimum (3, −2) — search, confirmation, and gap accounting
  agree with ground truth. The DEDS problems produced plausible winners with
  full audit trails.
- **The instrument discriminates.** The performance profile on sphere-MED
  (τ = 2): R-SPLINE reaches the optimum region within **10%** of the budget on
  every macro-replication; SHC and RestartSHC by ~70%; SA and CE solve only
  50–60% of their macro-replications within the *full* budget. Exactly the
  kind of statement the harness exists to support — and it comes with the
  consumption data to defend it.
- **Budget fairness is visible.** Recorded consumption vs the 3000 budget
  shows the expected shapes: point solvers land within one iteration
  (3000–3030), CE overshoots by up to a generation (~3800), RestartSHC by up
  to one inner restart.
- **The D3 rerun pattern works.** Experiment 4 appended into the same
  database under fresh run ids; its traces are keyed to those runs and the
  earlier experiments are untouched.
- **One presentation-layer lesson.** Iteration traces faithfully record a
  `Double.MAX_VALUE` penalized best *before* a solver has any valid best
  (population solvers at iteration 0). Plot axes cannot absorb 1e308 — filter
  the sentinel before plotting (the pilot's plot helper shows how).

To rerun the pilot: run `PilotStudy.kt`'s `main`. Note it opens its database
with `deleteIfExists = true` (each pilot is a fresh validation); flip that
flag if you want pilots to accumulate.

### Sizing your own study

Budget wall-clock with: `cells × budget × cost-per-replication ÷ workers`,
using the measured per-replication costs (§10 table; synthetics are
effectively free) — then confirm with a small probe run, since model cost
depends on run length and traffic intensity. For reference, the pilot's core
grid was dominated entirely by the RQ problem's 80 ms replications.

## 12. Caveats and good practice

- **A winner is only as good as the selection that produced it.** Confirmation ranks
  feasibility-first, so on a chance-constrained problem where no candidate can be declared
  confidently feasible, every candidate falls to the constraint-violation tie-break and
  the objective plays no part in choosing the winner. The run reports a winner either way
  and nothing in `tblConfirmation` marks it. Check
  `tblConfirmationSummary.selectionDegenerate` before quoting one, and if it is set, the
  remedy is precision at confirmation rather than a different solver — the estimates
  reaching that stage came from the search, at the weakest precision in the pipeline.
- **R-SPLINE** requires integer-ordered problems, and the requirement is stricter
  than the name suggests: the granularity must be exactly 1, not merely a grid of
  whole numbers. R-SPLINE steps one unit along a coordinate at a time in the
  variable's own units, so a variable with granularity 5 — integer-valued, but
  coarser than the unit lattice — is refused, as it is by COMPASS and ISC. The
  refusal names the offending inputs and their granularities. (A zero-pseudo-gradient
  crash on one-dimensional problems, reported in earlier releases, is fixed and
  regression-tested; R-SPLINE runs 1-D problems normally.)
- **Solver-internal convergence checks are superseded** by the budget
  criterion (deliberately — see §2, policy 1). If you specifically want to study
  early-stopping behavior, that is a different experimental design than
  equal-budget comparison.
- **Builders must return fresh instances** (a new model per model-builder
  call, a new response function per response-function-builder call, with all
  streams acquired at construction). The same freshness logic applies to
  custom penalty functions on problem definitions — cells evaluate
  concurrently.
- **Reproducibility recipe:** fixed problem/solver lists, fixed
  `macroReplications` and budget, default (or explicitly seeded) stream
  providers, and response functions that follow the construction-time
  acquisition contract ⇒ identical results at any worker count. Draw nothing
  from `KSLRandom.defaultRNStream()` inside a response function; the oracle
  fails loudly on mid-evaluation stream acquisition.
- **The database is append-only by convention** — treat `deleteIfExists =
  true` as a deliberate act. Experiment ids, not names, are the keys; the
  same experiment name may legitimately appear under several ids.

## 13. See also

- `ksl-controls.md` — control keys, the `elementName.propertyName` convention
  that DEDS problem inputs are written in.
- `ksl-supplychain.md` — the domain layer behind the multi-echelon problem
  (policies, cost formulations, and their controls).
- The KSL book's simulation-optimization chapter — `ProblemDefinition`,
  solvers, evaluators, penalty functions.
- `ksl.examples.general.simopt` — `BenchmarkDemo` (the quick-start, runnable),
  `PilotStudy` (the full workflow), the per-solver configuration demos, and
  `BenchmarkProblemCaseValidationTest` (the name-pinning pattern).
