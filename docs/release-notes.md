# KSL Release Notes

Two things are released from this repository, on separate cadences and with separate
numbering, so they are kept apart here:

- **The KSL library** — `io.github.rossetti:KSLCore`, versioned `R1.6`, `R1.5.1`, … The
  simulation engine, published to Maven. [Library releases](#library-releases-kslcore).
- **The KSL suite** — the installable applications, servers and `kslpkg`, versioned
  `0.3.7`, `0.3.6`, … shipped as `ksl-suite.zip` and installed by the one-line installer.
  [Suite releases](#suite-releases).

A suite release does not imply a library release, or the reverse. The suite can gain an
application while KSLCore is untouched, which is exactly what 0.3.0 did.

---

# Suite releases

The installable applications, servers and `kslpkg`. Install or update with the one-liner
in the [README](../README.md#installing-the-ksl-applications).

`ksl update` is broken in 0.3.1 and earlier: it re-reads the manifest cached at install
time, so it re-downloads the version you already have and reports success. Until 0.3.2,
updating means re-running the installer — and because the broken updater is the thing that
would have to run, existing installs need that one re-run to reach the fix.

## 0.3.8 — a different model, without restarting

*15 August 2026.* KSLCore is untouched by this release.

**The Single-Model application can now open a different model.** Until now the model you picked
at launch was the model for as long as the application was open: the only way to run a second one
was to quit and start again. **Bundles → Open Model…** shows the same picker you saw at startup,
and the window reopens on your choice, at the same size and position. Every other KSL application
could already switch models; this one now does too.

Switching starts a clean editor. Overrides name a particular model's controls and random
variables, so they are not carried across to a model that does not have them — save your
configuration first if you want to come back to it. The application asks before discarding
unsaved changes, and declines to switch while a simulation is running.

**A configuration saved for one model is no longer applied to another.** Opening a configuration
that belongs to a different model used to load it anyway, matching whatever names happened to
agree and mentioning the mismatch in a notification you could easily miss. It now tells you which
model the file was saved for and offers to open that model and load the file into it. Decline, and
your current work is left exactly as it was.

**Load JAR… opens where bundles actually live.** In every application, that file chooser started
in your home directory every time, however many bundles you had already loaded. It now opens in
your workspace `bundles/` folder — and, once you have loaded a JAR from somewhere else during a
session, back in that folder instead. Bundle JARs built in a project folder and bundles kept in
the workspace are both one step away.

**Every application says the same thing about a loaded JAR.** The same outcome was worded four
different ways across the applications, and most of them reported a JAR with no models in it by
naming a Java interface that has nothing to do with simulation. The messages are now identical
everywhere, name the file rather than its full path, and reserve the error styling for a load that
actually failed.

**Closing an Animation window now closes its work.** The window went away while its simulation
session stayed open behind it. Closing it now cancels any run in progress and releases the models
it had loaded.

**`ksl update` now checks whether it can install before it downloads.** It fetched the whole
release — around 150 MB — and only then noticed that the KSL Server was still running, or that the
component name you typed did not exist. It checks first and stops in a second rather than after a
download. As with any fix to the updater, the update that delivers this one still runs the old
order; it takes effect from the update after this.

## 0.3.7 — installing over a running KSL

*15 August 2026.* KSLCore is untouched by this release.

**Installing or updating while the KSL Server was running quietly corrupted it.** The installer
and `ksl update` unpacked straight over the shared library and server jars, and on macOS and Linux
that overwrite succeeds: the running server keeps going with jars whose contents no longer match
what it loaded, then fails minutes later with an error naming some unrelated class. Windows was
accidentally safe, because it refuses to replace a file that is open. Both installers and both
updaters now check first, list the processes still using the installation — the server, any open
applications, and the KSL bridges your AI client started — and stop without touching anything.
They only ever report; nothing is killed for you.

**Quit the KSL Server before updating to 0.3.7.** The check ships *inside* this release, so the
update that delivers it still runs 0.3.6's unguarded updater. From 0.3.7 on, `ksl update` enforces
this itself. Re-running the installer is protected immediately either way — the one-liner fetches
the current installer rather than the one on your disk.

## 0.3.6 — one configuration file, and reports you can open

*15 August 2026.* KSLCore is untouched by this release.

**A configuration saved by a desktop application now runs on the server.** The Single and Scenario
applications save a model reference naming the bundle and the model; the server only accepted a
reference naming a provider id, and rejected the applications' own files. So a run configuration
was portable in principle and not in practice. The same `.toml` now opens in the applications and
runs on the server, which is what the format was for. If you hand-write one, remember that a
scenario naming a bundle must also declare that bundle in `[[bundleRefs]]` — the applications write
this for you, and the paths recorded there are not required to exist on the machine that runs it.

**Reports and plots come back as links you can open.** Every rendered artifact — comparison
reports, plot images, exports — was reported only as a file path on the server's own disk, which is
useless to anyone not sitting at that machine and reads as broken in a chat client. Artifacts now
carry a URL served by the KSL Server itself, on both the MCP and REST interfaces, and the
comparison report prints the link directly.

**The KSL Server no longer leaves a stray icon in the Dock.** On macOS the server registered itself
as a regular application the first time it drew a plot, so a "MainKt" icon appeared in the Dock and
stayed for as long as the server ran. It now runs as a background accessory, as the menu-bar agent
already did. Plots are unaffected.

**Pasting a `.toml` into the server's configuration tools works.** The tools documented that they
accept a configuration either as structured data or as the text of a `.toml` file, but the text
form was rejected before it ever reached the server. You can now paste the contents of a saved
configuration straight in.

**Your KSL folder explains itself.** `~/Applications/KSL` now contains a `README.md` describing
what each part of the folder is and — the part worth knowing — that your own work lives in
`~/Documents/KSLWork` and is never touched by an update. Alongside it, a new `skills/` folder holds
optional instructions that help an AI assistant drive the KSL Server correctly, with a working
example configuration to copy; see `skills/README.md`. Neither is required to use KSL.

**The server also gives connected assistants better instructions**, covering how to build a run
configuration rather than only which tool to reach for — the point where they most often went
wrong.

**Upgrading from 0.3.5 with `ksl update`? Run it twice, or just re-run the installer.** `skills/`
and `README.md` are new items that belong beside the applications, and the code that puts them
there ships inside 0.3.6 — so 0.3.5's updater unpacks them and leaves them out of sight. A second
update, now running 0.3.6's code, puts them where they belong. Re-running the one-liner does it in
one step.

## 0.3.5 — the R1.6 engine

*9 August 2026.* No application changed in this release. The simulation engine underneath them
did, and it changes numbers.

**Interval and period statistics for time-persistent quantities were measured over the wrong
window.** Anything collected through a response schedule, a response interval, or a time series —
queue lengths, resource utilisation, numbers in system — was averaged over a window offset from
the one you asked for. If a warm-up period fell inside such an interval it was worse: an interval
could report a level as though it had been constant throughout, and a counter could report a
**negative** count. Across the shipped examples 22 of 127 responses moved, by 1.2% at the median
and 12.5% at most. Observation-based statistics — waiting times, system times — were correct and
are unchanged.

**Results databases are not migrated.** Rows written before 0.3.5 keep the values they were
written with. Comparing an interval statistic from an older run against a new one in the Results
application compares two different computations, so re-run rather than compare across the upgrade.

**Models using a movable resource pool could hang.** Entities queued for a pool of transporters
were never woken when one was released, so they waited forever. This has been broken since library
release R1.2.6. A model that stalled should now run to completion.

**Two shipped animation examples could not run past one replication.** *Warehouse AGV* and *Drone
Delivery* failed on replication 2 with "entity is already running a process". Both now run any
number of replications. Their polished layouts are unchanged.

**Upgrading.** `ksl update` works normally from 0.3.2 on. Nothing in the applications behaves
differently.

**Using KSLCore directly?** The [R1.6 notes](#r16) cover the library changes, including one API
change in the experimental agent package.

## 0.3.4 — the AIC scoring option

*5 August 2026.* This release changes KSLCore.

**The Distribution application's AIC scoring option was not computing AIC.** Its charge for
each extra estimated parameter was a fraction that never exceeded one and grew *smaller* as
parameters were added — so rather than pricing complexity it rewarded it, very slightly. A
fit ranked with **AIC** among the selected metrics was in effect ranked on goodness of fit
alone, which favours whichever candidate family has the most parameters to spend. AIC now
means what it has always meant: minus twice the log-likelihood, plus two for every estimated
parameter. Where it appears in a scored comparison, the ranking can change.

**Default fits are unaffected.** AIC is not one of the four metrics a fit uses unless you ask
for it — those are BIC, Anderson-Darling, Cramér-von Mises and the Q-Q correlation, and BIC
was computing BIC. If you have never added AIC to the metrics, nothing you have fitted moves.

**Using KSLCore directly?** Two more statistics were wrong in the library — the Hannan-Quinn
criterion and the Watson goodness-of-fit statistic — a small-sample corrected AIC has been
added, and AIC's own signature has changed. The [R1.5.1 notes](#r151) cover all of it.

**Upgrading.** `ksl update` works normally from 0.3.2 on. Nothing else in the suite behaves
differently.

## 0.3.3 — cancelling one design point or scenario

*31 July 2026.* This release changes KSLCore.

**Cancelling a single design point or scenario could throw away results that were already
finished.** If the request landed after that unit's replications completed — a matter of
milliseconds, but a window that widens sharply on a machine with fewer cores than the sweep
has units — the run discarded a complete set of results, wrote nothing to the database, and
reported the unit as cancelled. In the Experiment app the point's row went to *Cancelling…*
and its results never arrived; in the Scenario app the row was marked completed and then
flipped to cancelled.

**The Experiment app also reported success for cancels that did nothing.**
`cancelDesignPoint` searched a table of running points that was never pruned until the whole
sweep finished, so it always found the point and always said yes — including for points that
had finished long before, and for points the dispatcher had not started yet.

A unit's result and a cancel request now settle atomically: whichever arrives first wins, and
once a unit's replications are done its results are committed rather than discarded.
Cancelling a unit that is still working is unaffected — it stops at the next replication
boundary, so the replication in progress finishes and no more start.

**The Scenario app's per-scenario cancel had stopped working entirely.** Each running
scenario's Status cell shows a red ✕ that cancels just that scenario, but the table was
disabled for the duration of a run — and a disabled Swing table receives no mouse clicks, so
the glyph was inert during exactly the window it exists for. The only working control was the
global *Cancel*, which stops everything, making per-scenario cancellation look like a
batch-level feature rather than a broken one. It is clickable again, and a cancelled scenario
now reads *Cancelling…* immediately instead of claiming it is still running until the rest of
the sweep finishes.

**Upgrading.** `ksl update` from 0.3.2 works normally. Sweeps that never cancel are
unaffected by any of this.

## 0.3.2 — `ksl update` actually updates

*30 July 2026.* KSLCore is untouched by this release.

**`ksl update` had never been able to change your version.** It read the copy of
`manifest.json` cached when you installed, which names the release you already have — so it
dutifully re-downloaded that, reinstalled it, printed *updated the whole suite*, and exited
successfully. It now reads the published manifest, and falls back to the cached copy only
when the network is unreachable, saying so rather than pretending.

**Getting this fix needs one installer re-run.** The broken updater is the thing that would
have to run, so it cannot deliver its own replacement. Re-run the one-liner in the
[README](../README.md#installing-the-ksl-applications) once; `ksl update` works from then
on.

**Updates refresh the shipped examples again.** The updater extracted a hardcoded list of
directories that still named `bundles/` — which stopped existing when the examples moved to
`examples/` in 0.3.0 — and never mentioned `examples/` at all. So model bundles and polished
layouts were never refreshed by an update, and `unzip`'s `caution: filename not matched`
was the only sign. It now unpacks the whole payload the way the installer does, and a
build-time check refuses to package a suite whose updater does not cover every directory it
ships.

**Also:** an update verifies the download's `sha256` against the manifest, which the
installer always did and the updater never did; and `ksl list` reports the version you are
actually on, rather than the one you first installed.

## 0.3.1 — two things 0.3.0 shipped broken

*30 July 2026.* KSLCore is untouched by this release. Everyone on 0.3.0 should update; both
fixes are for things 0.3.0 claimed to do and did not.

**The KSL Server finds the shipped example bundles again.** Its generated launcher never
set the variable it used to locate them, so it pointed at the filesystem root and the
server started with no examples loaded. The application launchers were unaffected, which is
why this survived testing. A build-time check now refuses to package a launcher that reads
a variable nobody assigns.

**The shipped layouts are reachable from the Layout tab.** *Use Shipped Layout* existed
only in the window's Layout menu — on macOS, at the top of the screen rather than beside
the work. The Layout tab now carries a **Shipped** button next to *from Model*, disabled
when the open model's bundle ships no layout. The row also wraps when the window is narrow,
which it previously did not: the last three actions were drawn outside their toolbar and
could not be clicked.

**Auto Layout draws a circular conveyor as a loop.** It used to place every belt on a
straight line, so a closed circuit appeared to end at its last station and the segment
completing the circuit was dropped entirely. On a loop the stations are now placed round a
ring in cell order, which is a claim about the belt's topology and only a guess about your
floor — polishing still moves things to where they physically are. Straight belts are
unchanged.

## 0.3.0 — animations in a browser

*28 July 2026.* KSLCore is untouched by this release.

**Animations play in a browser.** A captured trace now replays through the same renderer
the desktop application uses, compiled for Kotlin/JS. Nothing is a recording: the page
reads the trace and draws it, so what a browser shows and what the app shows cannot drift.

**Export to HTML…** in the Animation app writes a *single self-contained file* — the
player, the trace and the layout all inside it. It opens by double-clicking, needs no
server and no KSL install, survives being emailed, and can be handed to a student the way
a PDF can.

**Fifteen polished layouts ship with the suite.** Every model in the animation examples
bundle now has a layout worth looking at, offered in the app by *Layout ▸ Use Shipped
Layout* when you open one of those models. Choosing it leaves the document unbound, so
saving writes to your workspace and never back into the install.

**A visible `KSL/examples/` folder.** The shipped example bundles moved out of the hidden
`.support/` directory and now sit beside the applications, together with those layouts. A
student told "open the animation examples" can find them. Upgrading from 0.2.0 relocates
them and removes the old copy.

**A published gallery.** Every polished animation is playable at
**https://rossetti.github.io/KSL-Animations/** — no install, no download.

**Also:** the desktop viewer's replay controls report what a trace actually contains
rather than offering overlays it has none of; playback speed means the same thing in both
viewers; and the browser player gained Stop, Loop, and zoom controls with a Fit that
recovers a lost view.

## 0.2.0 — the suite as one installable thing

*20 July 2026.* The applications, servers and `kslpkg` began shipping as a single
`ksl-suite.zip` on the student's own Java, installed by `install.sh` / `install.ps1` and
managed with `ksl list` / `ksl update`. This replaced the per-application `jpackage`
pipeline.

---

# Library releases (KSLCore)

Release history for **KSLCore** (`io.github.rossetti:KSLCore`), newest first.
These notes cover the published library — the simulation engine. As of R1.4 the
`ksl.app.*` model-packaging / run infrastructure lives in a separate `KSLApp` module
(not published to Maven); it and the Swing applications are separate modules (see the
README's build section) and are not part of the KSLCore artifact.

## R1.6.1

*23 August 2026.* A correctness release for simulation optimization, the random-number stream
provider and capacity schedules, with one addition — a discrete-time Markov chain that computes
its own exact properties. Nothing is removed and no signature changed, so it is a drop-in
replacement for R1.6 — but several fixes correct behaviour that was silently wrong rather than
loudly broken, so numbers move. The three worth knowing about before upgrading are the
comparison of penalized solutions, the stream a variable is bound to when it was built
antithetic, and a capacity schedule that is configured more than once.

Almost all of it was found by using the library hard: instrumenting a call-center model and
running a benchmarking study end to end surfaced defects that the test suites did not.

### Fixed

- **A search on a constrained problem could never displace its starting point.** A dynamic
  penalty scales a constraint violation by the solution's own evaluation number, and an incumbent
  keeps the number it was born with while every challenger carries the current, larger one. The
  same violation was therefore penalized harder on the challenger, by the ratio of the two
  clocks — and because nothing displaced the incumbent, its clock never advanced and the bar rose
  against every later candidate. **More budget made it worse.** Both operands are now judged at
  the later of the two evaluation numbers, which is the multiplier the penalty intends and the
  same standard for both sides. Simulated annealing decides with its own Metropolis rule on
  penalized values and had the same defect at a different site; it is fixed too. Unconstrained
  problems are unaffected — the penalty is zero at every clock.
- **A random variable built on an antithetic stream was bound to the wrong one.** A provider
  names a stream by its position in the list it holds, and it serves an antithetic stream as a
  derived copy it does not hold — so asking for the number of an antithetic stream returned −1.
  Since the number is what gets carried when a variable is rebound onto a model's own provider, a
  variable built on antithetic stream 3 was silently rebound onto antithetic stream 1: measured,
  its draws matched antithetic 1 exactly. **Anyone using antithetic variates got the wrong
  pairing, with no error and no warning.** A variable now remembers the number it was built with.
  Applies to `RVariable`, `MVRVariable`, `RMap`, `RList`, `DPopulation`, `DEmpiricalList`,
  `BernoulliPicker`, `CaseBootstrapSampler` and `MetropolisHastings1D`.
- **A capacity schedule configured a second time did nothing at all.** A schedule's length is the
  total of its item durations, and an item's start time is the schedule length when it is added.
  Clearing the items emptied the list but left the length at whatever the removed items summed
  to, so the next item added started where the deleted schedule had ended. Assigning
  `capacityScheduleData` a second time — which clears and re-adds — therefore produced a schedule
  whose first capacity change was due only after the previous schedule would have finished:
  within any replication no change occurred and the resource sat at its initial capacity for the
  whole run. Nothing threw and nothing warned. A schedule configured **once** at construction was
  always correct, which is why this survived; it broke exactly where a schedule is rebuilt from a
  property setter — a staffing control, a scenario sweep, a simulation-optimization input — and
  there the second assignment is the one carrying the design point being evaluated.
- **Concurrent model building could bind a variable to the wrong stream.** `RNStreamProvider` was
  not thread-safe. Two threads growing its list at once can each land at a different index, and a
  stream's number is its position in that list, so a variable that asked for stream 1 could report
  stream 6. Construction and lookup are now guarded, and `streams` returns a snapshot. The pooled
  member-evaluator factory also serializes `modelBuilder.build(...)`, since a builder is user code
  that may share more than streams.
- **A deterministic response made a comparison throw.** Comparing two estimates whose sample
  variances are both zero — which happens whenever a response is a deterministic function of the
  inputs — gave Welch–Satterthwaite degrees of freedom of `0.0/0.0`. The clamp that guards a
  too-small value does not fire for NaN, and Student-t rejected it. The difference of two exactly
  known averages has no sampling error, so the interval now collapses to the point estimate.
- **A single-observation estimate threw where it should have been reported as inconclusive.** Two
  places: comparing two estimates, and testing whether a solution satisfies a response
  constraint. One observation carries no sample variance, so no confidence interval exists — the
  answer is that nothing has been shown, which is what these methods already return when an
  interval contains zero. Feasibility ranking is the ordinary path (`bestSolution` ranks
  feasibility first), so a search configured with one replication per evaluation on a constrained
  problem failed at the moment it was asked for its answer rather than while it ran.
- **An indifference zone was applied asymmetrically.** For two single-observation estimates the
  difference was compared against the zone on one side only, so two equal observations were
  reported as "less than" for any positive indifference zone. The zone is now the symmetric band
  about zero that the other branches already used.
- **Sampling a first passage time moved the chain's starting point.** `countTransitionsUntil` has
  to move the start to take its observation and left it moved, so a later `reset()` returned to
  wherever the last sample happened to begin rather than where the caller had set it. Anything
  mixing sampled first-passage times with manual `nextState()` walking was quietly wrong.

### Things that change results

- Any solver on a **constrained** problem, and the direction is toward feasibility. On a
  stochastic-activity-network problem where no solver had reached the feasible region — the
  confirmed winner violated a deadline of 5 by more than 12 — every solver now returns feasible
  answers, and a hill climber that had not left its starting point at 100,000 replications now
  works its way in. On a 24-problem synthetic grid at 30 macro-replications, feasibility on the
  constrained problems under medium noise now runs 47–97% by solver.
- Any model using **antithetic** random variables, which were previously paired against the wrong
  stream.
- Any **benchmark experiment**: starting points are now addressed by absolute macro-replication
  number, so which point a cell draws no longer depends on how the run was sliced.
- Any model whose **capacity schedule is configured more than once**, which previously ran at its
  initial capacity throughout.
- Anything mixing sampled **first passage times** with manual chain walking.

### New

- **`DTMC`** — a discrete-time Markov chain that reports its own exact properties rather than
  estimating them: n-step transition matrices, reachability, recurrence and transience, period,
  absorbing states, the fundamental matrix, expected absorption times, absorption probabilities
  and mean first-passage times. `DMarkovChain` gains state generation, state-frequency collection
  and sampling from a supplied distribution.
- **`BenchmarkExperiment.macroReplicationRange`** — a study can run its macro-replications in
  blocks. Because cells address their randomness by absolute macro-replication number, running
  1..10 today and 11..30 tomorrow gives the same result as running 1..30 at once, which is what
  makes a long study survivable.
- **Collection-taking overloads of `mcbDataMap`, `mcbAnalyzer` and `performanceProfile`** on
  `BenchmarkResultsDb`, so a study run in blocks can be analyzed whole. Duplicate blocks are
  rejected and a gap basis is recomputed when blocks disagree.
- **`ProblemDefinition.nonIntegerOrderedInputs`** and `integerOrderedRequirementMessage`.

### Diagnostics

Three failures that named a rule but not the cause now name the cause.

- R-SPLINE, COMPASS and ISC refuse a problem whose inputs are not unit-spaced. The refusal now
  names the offending inputs and their granularities. Note that the property tested is stricter
  than "integer-ordered" in the usual sense: a variable ranging over 30, 35, … 100 takes only
  integer values and is still refused, because these solvers step one unit along a coordinate at
  a time.
- A response that cannot be summarized now reports which response, in which model, how many
  replications left it unobserved, why that happens, and the design point that produced it.
  A response conditioned on an event is well defined over most of a feasible region and undefined
  in a corner of it, and a search that explores corners will find that corner.

### Compatibility

**A drop-in replacement for R1.6.** Nothing removed, no signature changed, no deprecation. The
whole 25-module build compiles and passes against it — 4,869 tests across 23 modules — and the
shipped examples that touch the changed surface were executed, not merely compiled.

If you have recorded results from R1.6 that you intend to compare against new ones, read
*Things that change results* first: several of the fixes correct behaviour that produced
plausible numbers rather than obvious failures.

## R1.6

*9 August 2026.* A correctness release in three areas: interval and period statistics, entity
state after a terminated process, and movable resource pools. The experimental
`ksl.modeling.agent` package also gains fixes and a small API cleanup — the one place R1.6 is
**not** a drop-in replacement for R1.5.1.

### Fixed

- **Interval and period averages for time-weighted responses were measured over the wrong
  window.** `ResponseInterval`, `TimeSeriesResponse` and anything on a `ResponseSchedule`
  snapshotted a response's within-replication statistic at the interval start and differenced it
  at the end. A `TWResponse` banks a segment only when the next value arrives, so both reads
  lagged: the figure reported was an average over neither the requested window nor any part of
  it. Observation-based responses and counters were correct and are unchanged.
- **A warm-up falling inside a `ResponseInterval` corrupted it.** The warm-up resets what the
  interval captured at its start. The interval could report the current height as though the
  variable had been constant across a window in which it changed, and a `Counter` could report a
  **negative** count. Such an interval is now discarded — it observes nothing, counters and the
  empty-interval indicator included — and the discard is logged once per experiment.
- **A `TimeSeriesResponse` period split by a warm-up reported a wrong number.** This class slices
  time and is documented as not reacting to warm-ups, which it now does: it accumulates
  independently of the responses it watches, so every period is measured over its own window
  whether or not a warm-up falls inside it.
- **`MovableResourcePool` never woke the entities queued for it.** A request queued against a
  pool names the pool, but the pool released the individual member, which matched none of its own
  waiting requests — so entities waited forever. A **regression introduced in R1.2.6** and
  present through R1.5.1. Terminating an entity holding an ordinary `ResourcePool` allocation
  stranded that pool's waiters the same way.
- **A terminated process left its entity permanently unusable.** The entity stayed bound to the
  dead process and in whatever suspended state the unwind left it, while an entity whose process
  merely *completed* was reusable. Four suspension kinds also left registrations behind — `seize`
  a `Request`, `waitForItems` / `waitForAnyItems` a `ChannelRequest`, `waitFor(blockage)` a
  blockage entry, `suspendFor` a back-reference — all surfacing as "Tried to resume process …
  from an illegal state: Terminated". A completed process now ends in `ProcessEnded` rather than
  `Active`, so both endings agree.
- **`ksl.modeling.agent`** — the Manhattan A\* heuristic is withdrawn, because it is a lower
  bound only under `VON_NEUMANN` movement: under the default `MOORE` rule a diagonal step costs
  the square root of two while Manhattan charges two, so the estimate exceeded the true cost and
  A\* lost its optimality guarantee. An agent removed from its context kept firing statechart
  timers, and a `receiveMessage` waiter outlived its process.

### New

- **`ResponseCIfc.withinReplicationAverage`, `withinReplicationWeightedSum` and
  `withinReplicationSumOfWeights`** — the accumulated average, area and elapsed weight in the
  current replication. A `TWResponse` overrides the latter two to include the segment currently
  in progress, which its within-replication statistic cannot hold.
- **`AgentLike.dispose`** for agent teardown, **`withReservation`** for scoped mailbox
  reservations, and **`Statechart.detachObserver`**, which previously had no way to detach an
  observer at all.

### Things that change results

- Every interval and period statistic on a **time-weighted** response — `ResponseInterval`,
  `TimeSeriesResponse`, `ResponseSchedule`. Measured across the shipped examples: 22 of 127
  responses moved, median 1.2%, maximum 12.5%. Observation-based responses collected over the
  same intervals did not move.
- Models using `MovableResourcePool` under contention, which previously stalled, now run to
  completion.
- Agent path-finding under `MOORE` movement, which now returns optimal paths.

### Compatibility

**A drop-in replacement for R1.5.1 unless you use `ksl.modeling.agent`.** That package is
released as experimental and its API changed without deprecation shims: `addObserver` and
`removeObserver` on `AgentMailbox` and `Statechart` are now `attachObserver` and
`detachObserver`, and the uncalled `AgentModel.removeAgent` has been deleted.

## R1.5.1

*5 August 2026.* A correctness release for the information criteria in
`ksl.utilities.statistic.Statistic`. A drop-in replacement for R1.5 — nothing removed and no
signature changed — but four functions return different numbers.

### Fixed

- **`akaikeInfoCriterion` did not compute AIC.** Its penalty was the ratio
  `(n - 2p + 2)/(n - p + 1)` rather than `2p`. That ratio is less than one and *shrinks* as
  parameters are added, so the criterion fell as the model grew: minimising it picked the most
  complex model on offer. It now returns `-2L + 2p`.
- **`hannanQuinnInfoCriterion` carried the same ratio, and also multiplied the log-likelihood by
  the parameter count.** It now returns `-2L + 2p·ln(ln n)`.
- **`watsonTestStatistic` lost a pair of parentheses**, computing `(2i - 1)/2 * n` where the
  definition is `(2i - 1)/(2n)`. Nothing inside KSL calls it — every internal use is commented
  out in `ContinuousCDFGoodnessOfFit`.

### New

- **`akaikeInfoCriterionCorrected`** — the small-sample corrected criterion,
  `AIC + 2p(p + 1)/(n - p - 1)`, which approaches AIC as the sample grows.
- **A two-argument `akaikeInfoCriterion(numParameters, lnMax)`**, since AIC does not depend on
  the sample size. The three-argument form still compiles, deprecated, and goes away in R1.6.

### What changes for you

- Every AIC and Hannan-Quinn value, and any model selected by minimising one. A model chosen
  under R1.5 by AIC was chosen by log-likelihood alone, with a slight bonus for complexity.
- Distribution fitting **only if you asked for AIC**. `PDFModeler.defaultScoringModels` uses BIC,
  which was correct, so default fits and their rankings are unchanged.
- Every `watsonTestStatistic` value.
- `akaikeInfoCriterion` no longer rejects a model with at least as many parameters as
  observations — the plain criterion divides by nothing. `hannanQuinnInfoCriterion` now requires
  more than one observation and rejects a non-finite log-likelihood.

## R1.5

*3 August 2026.* A correctness release. Four things are fixed that failed quietly rather than loudly:
the gamma distribution function, the sample median, the decision-analysis engine, and the ids
handed out while models run concurrently. **Things that change results** below lists every fix
that makes a number differ from R1.4.

Two additions: the **metalog** distribution family, and snapshots, weight sensitivity and
swing-weight elicitation for **multi-objective decision analysis**. The guides cover how to
use them.

**R1.5 is not a drop-in replacement for R1.4.** See **Compatibility**.

### Fixed

- **`Gamma.cdf` stopped its series before it had converged.** One cause, two different
  outcomes.
  - **It lost accuracy as the shape grew, from a shape of about 150 upward.** The series
    accepted a truncation larger than it supposed, and the shortfall grew roughly with the
    square root of the shape. In relative terms:

    | shape | probabilities good to | relative error |
    |---:|---:|---:|
    | 150 | ~8 significant figures | 1.1 × 10⁻⁸ |
    | 10³ | ~7 | 5 × 10⁻⁸ |
    | 10⁴ | ~6.7 | 1.8 × 10⁻⁷ |
    | 10⁵ | ~6.2 | 6 × 10⁻⁷ |
    | 10⁶ | ~5.7 | 2 × 10⁻⁶ |

    The relative error is close to uniform across the distribution — at a shape of 10⁶ it is
    1.7 × 10⁻⁶ three standard deviations below the mean, where the probability is 0.0013, and
    2.1 × 10⁻⁶ at the mean — so it does not concentrate in the tail. Whether that mattered
    depends on what the probability was for: it is immaterial to a percentile or a
    goodness-of-fit statistic, and it exceeded the library's own declared tolerance
    (`KSLMath.defaultNumericalPrecision`, 1.05 × 10⁻⁸) from a shape of about 150 on, which is
    an ordinary shape — a sum of 150 exponentials reaches it. Nothing signalled the shortfall.
  - **Above a shape of about 1.1 × 10⁶ it threw** `KSLTooManyIterationsException` rather than
    returning, for arguments in a band near and below the distribution's mean, where the series
    converges slowest. Further out it still returned, inaccurately. Shapes that large come from
    fitting a gamma to tightly clustered data, where the shape is roughly the square of the mean
    over the variance, and are rare.

  Both are fixed; results are now within stated precision throughout. This reaches
  `ChiSquaredDistribution`, `PearsonType5`, `Poisson` and every chi-squared goodness-of-fit
  p-value. The defect was found during high-precision testing of the new metalog work.
- **`Statistic.median` returned the wrong observation for odd-length data** — the element one
  above the middle, so `[1, 2, 3, 4, 100]` gave `4.0`. Always the next observation up, so it
  biased everything built on it in one direction: Laplace and logistic location parameters, the
  bootstrap median, and every box plot's median line.
- **MODA crashed or returned a not-a-number on tied scores**, and fitting a metric's domain
  modified the caller's metric. Both are fixed; fitted domains are now held within the declared
  limits. See the [MODA guide](guides/ksl-utilities-moda.md).
- **Concurrently running models handed out duplicate ids.** `QObject`, `Allocation`,
  `ResourcePoolAllocation` and simopt `Solution` drew from counters shared by the process and
  read without synchronization — 16 000 QObjects across eight threads produced about 3 500
  duplicates. Mostly this showed up as repeated ids and repeated `ID_n` default names rather
  than as a failure. Ids are still shared across models, so they say nothing about which model
  produced them.
- **Cancelling one design point or scenario discarded finished results.** If the request
  arrived after that unit's replications completed, the run threw away a complete set of
  results and reported the unit as cancelled;
  `ParallelDesignedExperiment.cancelDesignPoint` also returned `true` for units that had long
  since finished. A result and a cancel now settle atomically.

### New

- **Metalog distributions.** `Metalog2P` through `Metalog6P`, each available unbounded,
  lower-bounded, upper-bounded or bounded, with matching random variables and `RVType` entries.
  A metalog takes its shape from the data rather than from a named family, so it describes
  distributions no standard family matches, and it can be built from three elicited quantiles
  when there is no data at all. Twenty estimators fit the family through
  `PDFModeler.metalogEstimators`, a separate set from `PDFModeler.allEstimators`, so existing
  fits are unaffected. See the [metalog guide](guides/ksl-metalog.md).
- **MODA snapshots, sensitivity and elicitation.** `ModaSnapshot` is a complete, immutable
  record of an evaluated study — recommendation, values, ranks, the domain each value was
  computed over, and warnings — which reports and writes to a database without the model that
  produced it. `ModaSensitivity` gives the weight at which the recommendation changes, which
  alternative takes over, and how far the weight has to move to get there. Swing-weight
  elicitation records the ranges weights were given against, so a later change to a range is
  detectable. See the [MODA guide](guides/ksl-utilities-moda.md).

### Things that change results

Each is a fix, and each means a number may differ from R1.4:

- Gamma, chi-squared and Poisson probabilities, and every chi-squared goodness-of-fit
  p-value. Differences appear in the sixth significant figure or beyond, growing with the
  shape parameter.
- The sample median for odd-length data, and so fitted Laplace and logistic location
  parameters, bootstrap medians, and box plots.
- MODA studies with tightly declared bounds, through domain containment and the margin cap.
- `Allocation`, `ResourcePoolAllocation` and `Solution` ids now begin at 1 rather than 0.
- `cancelDesignPoint` now returns `false` for a design point the dispatcher has not started
  yet. Treat `true` as "the point was cancelled", not "the point exists".

### Compatibility

- **Removed.** The five `…DataCounter` fields on the MODA record companions
  (`MetricData`, `ScoreData`, `ValueData`, `OverallValueData`,
  `AlternativeRankFrequencyData`) were public and settable in R1.4. They are now private, and
  ids are issued atomically. Code that read or reset one will not compile.
- **`Gamma.maxNumIterations` rejects values it used to accept.** R1.4 silently raised anything
  below 5 000 to 5 000. It now stores what you give it and throws on a value that is not
  positive.
- **Recompile, do not merely upgrade.** `Gamma.DEFAULT_MAX_ITERATIONS` and
  `Gamma.INC_GAMMA_MAX_ITERATIONS` are public `const val`, so R1.4's value of 5 000 is inlined
  into any bytecode that named one. Such code keeps passing 5 000 — below what the corrected
  series needs at large shapes — until it is rebuilt against R1.5.
- **`MetricIfc.metricData` is unaffected.** It gained a three-argument form; the two-argument
  signature R1.4 published is unchanged, so existing Kotlin and Java callers need nothing.
- **`ScoringResult.numberOfParameters` is defined differently but returns the same value for
  every R1.4 distribution.** It now reports what the estimator estimated rather than the size of
  the distribution type's declared parameter set. The two agree for every family whose members
  fit the same parameters, which is all of them in R1.4; they differ only for metalogs, where
  one type covers members that fit different numbers of parameters.

## R1.4

A reorganization and optimization-hardening release: 100+ commits to KSLCore since R1.3.
The headline for the library is a **module-boundary change** — the `ksl.app.*`
infrastructure introduced in R1.3 moves out of the published artifact — alongside a new
animation-capture layer and a substantial round of simulation-optimization work. This
release also coincides with a new one-command installable suite for the applications and
servers (see the README).

### Module boundary

- **`ksl.app.*` extracted into the new internal `KSLApp` module.** The model-packaging /
  run / session infrastructure introduced in R1.3 (`ksl.app.bundle`, `KSLAppSession`, run
  configuration and codecs) has moved **out of KSLCore** into a separate `KSLApp` module,
  so the published KSLCore library now carries only the simulation engine (plus the
  animation-capture layer below). 
  - `KSLApp` depends on KSLCore, and is **not published to
    Maven at this time**, and backs the Swing applications and the servers. Code that imported `ksl.app.*`
    from the KSLCore artifact must now depend on `KSLApp`.
- **Optimization plotting and catalog validation relocated into KSLCore.** `ConvergencePlot`
  and `CatalogValidation` now live in KSLCore, available to the library's own optimization
  and bundle-authoring paths.

### Animation

- **Model-animation capture (new).** A `ksl.animation` layer in KSLCore captures a run as a
  replayable trace — an `AnimationBuilder` DSL and `AnimationCapture`, with emitters for
  agents and stations — so a model's movement, queues, and resources can be visualized. The
  **capture** side ships in KSLCore; the replay engine and the desktop viewer live on the
  KSLApp / Swing side (see the [Animation app](guides/apps/animation.md) guide).

### Simulation optimization (`ksl.simopt`)

- **Penalty Function Method (new).** A memoryful penalty engine after Park & Kim (2015):
  penalty functions are now an abstract class bound to their constraint (`PenaltyFunction`,
  `ParkKimPenalty`, `PenalizableConstraint`), with penalty memory carried on the `Solution`
  (inert for memoryless penalties). Fast solver-level integration guards were added for CE
  and R-SPLINE.
- **Default constraint penalty corrected.** The default now uses linear violation and drops
  the `sqrt(N)` factor, fixing a regression in which the response-constraint penalty was far
  too weak to steer solvers away from infeasible regions.
- **Best-solution semantics.** Solvers recommend the best **feasible** solution rather than
  the best penalized one, and `allowInfeasibleSolutions` is honored by the best-solution
  archive.
- **ISC corrected to the source papers.** Industrial-Strength COMPASS clean-up and
  local-optimality statistics were corrected to match Kim (2005) and the ISC reference,
  reproducibility and finite-value reporting were hardened, and clean-up now runs on
  response-feasible solutions only.
- **Feasible-lattice sampling.** A new `inputLatticeSize` and a `feasiblePointCapacity` API
  drive feasible sampling; the feasible grid is enumerated when it is smaller than the
  request, solvers warn when their size exceeds the input lattice, and
  `sampleInputFeasiblePoints` is bounded to prevent an infinite-loop hang.
- Smaller items: `startingPoint` exposed on the ISC solver factories; PSO's default inertia
  horizon synced with `maximumIterations`.

### Controls & configuration

- **Finite JSON round-trips for controls.** `ControlData` decodes a wire `null` as its
  canonical infinity, so control values survive a JSON round-trip without producing
  non-finite output.
- **Optional results database for scenario runs.** The scenario runners' `KSLDatabase` is
  now optional, so a scenario batch can run without materializing a database.

> Experimental / evolving APIs: the R1.3 supply-chain, queueing-network station, and
> agent-based modeling packages remain experimental, as do the `@KSLStringControl` /
> `@KSLJsonControl` controls and the new `ksl.animation` capture API. Expect refinements in
> subsequent releases.

## R1.3

A large feature release: 288 commits to KSLCore since R1.2.7, headlined by three new
(experimental) modeling domains and a model‑packaging / run‑configuration substrate,
plus optimization, reporting, and numerics additions.

### New modeling domains (all new and experimental)

- **Supply‑chain modeling (new, experimental).** A `ksl.modeling.supplychain` package adds
  `SupplyChainModel` with demand/order flows and concrete multi‑echelon inventory policies
  (rQ, (r,S), continuous & periodic, warehouses, cross‑docks) plus cost and transport layers.
  The largest single addition in this release; the API is new and expected to evolve.
  The package's decision variables, experimental factors, and configuration toggles are
  exposed as controls (`ksl.controls`), so supply‑chain models are drivable by designed
  experiments, scenarios, and simulation optimization by key: inventory policy parameters
  (following the delta parameterization — a reorder‑point control plus a gap control, so
  any combination of control values is valid), inventory/item configuration (including
  initial weight/cube applied at replication start), demand‑generation toggles, carrier
  fallback flags, and the load‑forming strategy and limits (pairwise limits validated at
  replication start). Initial‑condition controls are guarded against mid‑replication
  changes. See `SupplyChainControlsDemo` in KSLExamples.
- **Queueing‑network station library (new, experimental).** `ksl.modeling.station` provides
  composable source/sink/seize/release/batch/fork/join/match/router stations, an `NHPPSource`,
  and a `StationNetwork` with a builder DSL and TOML‑driven configuration. New and still settling.
- **Agent‑based modeling (new, experimental).** A `ksl.modeling.agent` layer runs alongside the
  process view — transient/permanent agents over a message bus, statecharts, contract‑net
  negotiation, and 2D/3D dynamics with flow fields. The earliest‑stage of the three; some hooks
  are not yet complete.

### Model packaging & run infrastructure (ships in KSLCore)

- **Model‑bundle SPI (new).** `ksl.app.bundle` lets models ship as self‑describing JARs discovered
  via `ServiceLoader`, loaded with per‑JAR classloader isolation from JAR, directory, or classpath.
  Deliberately GUI‑agnostic — built to host future CLI/REST/MCP front‑ends, not just the Swing apps.
- **Run configuration & session façade (new).** `KSLAppSession` executes
  single/scenario/experiment/optimization specs from serializable JSON/TOML run documents, with
  validators and both async and blocking submission. This is the substrate behind the new
  configuration‑style applications.

### Controls & experiments

- **String & JSON controls (new, evolving).** `@KSLStringControl` adds string parameters with
  optional allowed‑value constraints, and `@KSLJsonControl` exposes lists/maps/serializable types as
  JSON — both with safe, non‑mutating validation.
- **Parallel designed experiments (new).** `ParallelDesignedExperiment` runs design points
  concurrently on freshly built models via structured concurrency, with selectable
  independent‑stream vs. common‑random‑number policies.
- **Parallel scenario execution (new).** `ConcurrentScenarioRunner` runs a list of scenarios
  concurrently — each on a fresh `Model` on a CPU‑bounded dispatcher — then writes the captured
  results to a shared `KSLDatabase`. It sits alongside the sequential `ScenarioRunner` (the two were
  split into separate runners).

### Simulation optimization (`ksl.simopt`)

- **Opt‑in parallel evaluation (new).** A `ParallelSimulationProvider` fans multi‑point evaluations
  across concurrently built models, honoring CRN/independent‑stream choices and wired through the
  evaluator and solver factories.
- **Stream‑ownership & Cross‑Entropy overhaul.** A unified `StreamTapePolicy` and per‑solver
  `RNStreamProvider` make stochastic solvers stream‑safe, and Cross‑Entropy was redesigned around an
  attachable `CESampler` base. Solvers and problems now auto‑name themselves and surface their
  configuration in reports.

### Reporting & analysis

- **Reporting framework (new).** A sealed‑AST report model renders to HTML, Markdown, plain text,
  and LaTeX through a builder DSL, with ~30 extensions that turn KSL objects (databases, runs,
  scenarios, solvers, MODA, regression, Welch, histograms…) into report sections.
- **Data & analysis additions.** New multi‑series plots and DB‑backed histogram/frequency plot data,
  a reworked `ExcelUtil`, a `DataFrameUtil`, and simulation snapshots (`SimulationSnapshot` +
  lifecycle bridge) that stream model state into `KSLDatabase`.

### Numerics & utilities

- **Stochastic‑approximation root finding (new).** `StochasticApproximationRootFinder` implements
  Robbins‑Monro with Kesten step acceleration and an EWMA stopping rule.
- **Monte‑Carlo integration harness (new).** `MCExperiment` provides macro/micro Monte‑Carlo with
  absolute‑precision stopping; also new are `BivariateNormalDistribution`, process‑wide
  stdout/stderr capture, and text‑editor utilities.

### Dependency changes

- **Excel I/O moved from Apache POI to fastexcel.** KSLCore now uses the lightweight, streaming
  `org.dhatim:fastexcel` / `fastexcel-reader` for `.xlsx` and **no longer depends on Apache POI**;
  `ExcelUtil` was reworked to match. Much smaller footprint — update any code or docs that assumed
  POI on the classpath.
- **DuckDB removed.** KSLCore no longer bundles or depends on DuckDB; PostgreSQL, SQLite, and Derby
  remain.

### Notable bug fixes

- **Gamma inverse‑CDF.** Fixed `Gamma.invCDF` failing in the lower tail for very small probabilities
  — a numerical‑accuracy fix affecting gamma‑based quantiles and sampling.
- **Random‑stream reproducibility.** Corrected pre‑run random‑stream advance ordering, so seeded
  runs reproduce as intended.
- **Validation.** Fixed `ProblemDefinition.validateProblemDefinition` and
  `Model.validateResponseNames` so invalid problem definitions and response names are caught
  correctly.
- **Histograms & statistics output.** Histogram bin labels no longer render `Double.MAX_VALUE`
  sentinels as enormous numbers (now `%g`‑formatted), and `StatPropertyTable` always emits all 19
  statistical properties.
- **Plotting.** Fixed box‑plot rendering and outlier handling, and the confidence‑interval plot's
  Y‑axis order.
- **Run lifecycle.** Closed a race in `Runner` by completing its `Deferred` after `onDetach()`.

> Experimental / evolving APIs: the supply‑chain, queueing‑network station, and agent‑based
> modeling packages are all new and experimental; the `@KSLStringControl` / `@KSLJsonControl`
> controls are also still evolving. Expect refinements in subsequent releases.

## R1.2.7
- Revised the simopt package
	- **Penalty Function:**
		- Removed `DefaultPenaltyFunction`**: The legacy `DefaultPenaltyFunction` class has been completely removed. Its aggregation responsibilities have been shifted directly into `ProblemDefinition`, and its mathematical duties have been replaced by more robust, context-aware penalty classes.
		- **Updated `PenaltyFunctionIfc` Interface**: The interface signature has been updated to `penalty(violation: Double, iterationCounter: Int, sampleCount: Int)`. It remains a single abstract method (SAM) interface, allowing users to continue defining custom penalty functions on the fly using Kotlin lambdas.
		- **Introduced `PenaltyFunctionWithMemory`**: Added a new penalty class specifically designed for Simulation Optimization based on the principles of Park and Kim (2015). It utilizes the `sampleCount` (memory) of a simulated response to mathematically dampen stochastic noise, preventing standard error from infinitely penalizing valid boundary solutions.
		- **Introduced `DynamicPolynomialPenalty`**: Added a standard dynamic penalty class optimized for deterministic constraints (Linear and Functional) where memory-based noise dampening is unnecessary.
		- **Granular Penalty Defaults in `ProblemDefinition`**: `ProblemDefinition` now supports assigning different default penalty functions based on the constraint type. By default, Linear and Functional constraints utilize `DynamicPolynomialPenalty`, while Response constraints safely utilize `PenaltyFunctionWithMemory`.
		- **Standardized Violation Logic**: Refactored the internal math for all `ConstraintIfc` implementations (Linear, Functional, and Response). All constraints now internally normalize greater-than/less-than operators via an `inequalityFactor`, ensuring that the `.violation()` method universally returns a strictly positive `Double` (`v > 0.0`) when a constraint is violated, and exactly `0.0` when satisfied or feasible. 
	- **Replication-Based Tabulation:** Redesigned the `Evaluator` metrics to track computational effort by *replications* 
	- **New `EvaluatorMetrics` Class:** Introduced a dedicated metrics snapshot class that natively computes and reports "Cache Savings %", allowing users to immediately see the simulation budget saved by the `SolutionCache`.
	- **Clarified Evaluator Calls vs. Points:** Renamed `totalEvaluations` to `totalEvaluatorCalls` to strictly represent the number of batches/invocations. The breadth of the search is now accurately tracked via `totalDesignPointsEvaluated`.
	- **Solution Batch IDs:** The `evaluationNumber` property on `Solution` objects is now explicitly tied to the `totalEvaluatorCalls` ID, correctly grouping solutions by the generation/batch in which they were created.
	- **Standardized "Warm Start" Support:** Updated all `RandomRestartSolver` factory methods (SHC, CE, SA, R-SPLINE) to accept an optional deterministic `startingPoint`.
	- **Lazy Initialization of Starting Points:** Refactored all `Solver` companion object factory methods (e.g., `createStochasticHillClimbingSolver`, `createSimulatedAnnealingSolver`) to implement lazy instantiation of the `startingPoint`. 
	- **Solver Configuration Logging:** The solver's `toString()` output now reflects user intent, reporting `"Not Provided (Will Auto-Generate)"` when a starting point is omitted, rather than masking it with a silently pre-populated point.
	- **Improved Solver Reporting:** Revised the solver reporting of results and solution output results with better console output.
	- **Simulated Annealing:** Added SimulatedAnnealing.estimateInitialTemperature(), which automatically calculates an optimal starting temperature based on the specific problem's landscape and a target acceptance probability. Added a new constructor and calculateOptimalCoolingRate() helper to ExponentialCoolingSchedule. This allows the cooling rate (α) to be dynamically calculated so the temperature reaches the stopping threshold precisely on the final iteration, preventing premature cooling. Fixed Logarithmic Cooling "Heating" Bug: Corrected a mathematical flaw in LogarithmicCoolingSchedule where the initial denominator evaluated to less than 1.0, causing the temperature to temporarily spike above the initial temperature during the first iteration. Added validation to LinearCoolingSchedule and the base CoolingSchedule logic to ensure the initial temperature is strictly greater than the stopping temperature, preventing mathematically invalid negative cooling steps.  Added a strict safeguard inside SimulatedAnnealing.initializeIterations() that throws an immediate exception if the solver is started with an initial temperature less than or equal to the stopping temperature, preventing silent logic failures where the solver bypasses the annealing phase entirely.
    - **New RandomWalkSolver:** Introduced a standalone unbiased random walk algorithm. While primarily added to facilitate the dynamic temperature estimation, it is now available as a first-class StochasticSolver subclass for general landscape analysis and baseline benchmarking.
- Fixed bug in SResource which caused utilization to be incorrectly calculated when user changed the capacity.
- Improved documentation of Counters and other related statistical collection
- Revised constructor signature of Counter to permit setting a stopping limit (and action) at construction.

## R1.2.6
* Fixed issue where a RequestQ is shared amongst multiple resources or resource pools. The release logic
was not checking if the resource associated with the waiting requests was associated with the release. This
caused waiting requests to be resumed with the resource not having available units. New and correct functionality
was added to RequestQ. This required that the resource associated with the release was passed to the request selection logic.

## R1.2.5
* Bug fix in MixtureDistribution class involving numParameters property
* Changed score() function to public from protected in PDFScoringModel

## R1.2.4
* Bug fixes involving Double.MIN_VALUE
* Added MixtureDistribution class
  - Cause some refactoring of distribution related interfaces

## R1.2.3
* Significant improvements to the `ksl.simopt` package for simulation optimization
	- Refactored `ProblemDefinition` class. Moved penalty function modeling into `ProblemDefintion`
	- Added cross-entropy solver
	- Added R-SPLINE solver
	- Refactored simulation oracle usage framework
	- Added screening of solution
* Added chapter 10 to accompanying textbook to cover simulation optimization methods

## R1.2.2
* Added jvmOverloads and started changes to improve usage from java
* Improved RandomElement and interaction with new `RNStreamProvider` usage
* Revised JSON configuration for `ModelBuilderIfc`
* Created `RVType` class to make it easier to specify random variable parameters and configure from JSON
* Added sum() function to `RandomVariable` class

## R1.2.1
* Updated Kotlin complier to version 2.2.0
  * This significantly improves compilation and build times.
* Updated Java compatibility to version 21
* Revised KSLCore build script to use gradle tool chain support and new publishing plugin for Maven
* Updated build dependencies to later versions
	* Removed dependency on guava
	* Updated dependency on Kotlin Dataframe for 1.0.0-Beta2, which may cause breaking changes for clients that use the api through the KSL.
	* Updated derby, Postgres, sqlite to latest releases.
    * No dependency vulnerabilities are reported.
* Added interfaces to support Json string configuration of model elements
* Revised random variable classes to require specification of the stream provider via StreamProviderIfc interface
	* Users specify streams primarily via the stream number not a specific stream instance.  This permits models to not share stream providers, which is essential for simulation optimization.
	* This may cause some code revisions that directly used or supplied the stream via RNStreamIfc
	* Revised book and other examples to illustrate the new approach. See chapter 2 of the textbook.
* Improved interfaces and implementation of non-homogeneous random variables and generators
* Created the `ksl.simopt` package. This purpose of this package is to facilitate the modeling and usage of simulation optimization algorithms with KSL models. This is the first iteration of the package and the API may change.
	* `cache` This package implements basic memory caches for holding simulation results to avoid the repeated execution of simulation models with the same configuration parameters. This avoids long-running execution.
	* `evaluator` This package implements in general form the evaluation of simulation models based on requests from solvers which produce solutions
	* `problem` This package facilitates the defining of simulation optimization problems that can be solved via solvers. 
      - The general form of the optimization problem is a penalty-based constrained optimization problem. 
      - Constraints can be linear, functional, and may include responses that are stochastic.
      - The objective function is specified by a response from the model.
	* `solvers` This package holds simulation optimization algorithms in the form of solvers. This facilitates the definition of search neighborhoods and stopping criteria.

## R1.2.0
- Updated BlockingQueue to enhance notification of waiting senders and receivers
  - Allows new rules to be used for notification
  - Corrected call for filling AmountRequests
- Updated seize() suspending function to allow request selection rules to be invoked upon first seize
- Improved use of interfaces in station package

## R1.1.9
- fixed entity size issue for conveyors
- added the ability to transfer from one conveyor to another
- refactored interfaces in station package
- added additional constructors to DEmpirical and DEmpiricalRV
- allow Signal to signal based on a predicate
- added a BatchQueue to permit entity to wait until a batch is formed
- added the ability to collect statistics in the form of a time series via the TimeSeriesResponse class
- revises resource pools and added new functionality for allocating resources from pools
	- allows movable resources to be in pools
- corrected home base logic for movable resources
- fixed time stamp database conversion issue in Simulation_Run table

## R1.1.8
- improved suspend/resume coding with new Suspension class
  - deprecated suspend() function in favor of newer process interaction functions
- Added AdjustedPPCCorrelation and AdjustedQQCorrelation PDF scoring models
- Added blockages to process interaction, including blocking activities
- Revised seize function to prevent edge case suspend/resume issues
- Added yield suspending function
- Changed default event priority numbering scheme
- updated logging dependencies
- improved the signature for constructing Scenarios

## R1.1.6
- Added blockUntilAllCompleted() suspending function to permit suspension until a set of processes completes.
- Added home base concept for MovableResource
- Completed MSER work for initialization bias deletion point detection
- Completed LogisticFunction scaling implementation for MODA and use in PDFModeler
- Fixed after replication termination issue for suspended processes using the waitFor() suspending function
- Added examples for entity movement

## R1.1.5
- Added blockUntilCompleted() suspending function to permit suspension until another process completes
- Simplified basic suspend() function

## R1.1.4
- updated how processes are started, removed automatic use of process sequence
- fixed random number stream assignment issue
- added piecewise constant continuous empirical random variable and distribution
- minor enhancements to pdf scoring and fitting
- fixed bootstrap standard error estimate
- refactoring to enable future removal of Apache POI dependency

## R1.1.3
- Added ability of IndicatorResponse to observe ResponseCIfc 
- Fixed stupid bug in EventGenerator introduced by typo in release 1.1.2.

## R1.1.2
- Added SAM functional interfaces to station package
- Don't use R1.1.2 due to stupid bug in EventGenerator, now fixed in R1.1.3

## R1.1.1
- Added ksl.modeling.station package
	- facilitate modeling of simple queueing systems
- added maps that can have randomly selected elements
- improved RList, DUniformList
- added BernoulliPicker

## R1.1.0
- Updates to ksl.utilities.distributions.fitting package
	- default scoring models changed to Bayesian Information Criterion, Anderson-Darling, Cramer Von-Mises, Q-Q Correlation
	- Bug fixes for scaling algorithm
	- Ranking criteria for recommending the distribution
	- Bootstrap family recommendation
- Enhancements to database utilities
	- Support for DuckDb database
	- Creation of simple databases based on data classes
	- Improved creation of SQLite, Derby, and DuckDb databases
	- Improved database connection usage
- Enhancements to MODA (multi-objective decision analysis) package. 
	- Improved defintion of metrics and support for database of results.
	- New MODAAnalyzer class to analyze simulation output based on MODA principles.
- Enhancements to MultipleComparisonAnalyzer
	- Save analysis to a database
- Statistics
	- Data classes for saving observations, statistics, histogram, frequencies to database
	- Bug fix for Beta pdf calculation
	- StringFrequency tabulation
	- ErrorMatrix tabulation of confusion matrix results
- Removed dependency on OpenCSV
- Upgraded to kotlin 1.9.20

## R1.0.9
- Bug fixes and improvements in ksl.utilities.distributions.fitting package
  - fixed Weibull estimation edge cases
  - added additional output to html distribution fitting results
- Added the capability in the ksl.controls.experiments package to run many scenarios and perform designed experiments
- Improved support for data frame processing
- Updates to documentation and examples to be consistent with textbook

## R1.0.8
- Addressed new issue with the search interval for MLE computation of gamma shape parameter

## R1.0.7
- Fixed natural logarithm compute issue in Anderson-Darling test statistic
- Fixed interval search issue for Gamma MLE parameter estimation
- Added 1-D discrete Metropolis-Hasting Markov Chain, improved properties of DMarkovChain
- Allow PMF to CDF with 0 probability on mass points
- Updates to documentation, examples

## R1.0.6
- Fixed AcceptanceRejectRV to correctly use majorizing function
- added Logistic random variable and distribution
- updated RVParameters and RVType for more flexibility
- added Laplace distribution
- improved KSLArrays.isAllEqual() and isAllDifferent() to account for double precision
- improved histogram break point creation
- simplified interface for TruncatedRV

## R1.0.4
- Fixed axis label issue in ScatterPlot
- Added examples for bootstrapping, VRT, and MCMC
- New classes for multi-variate copulas, minor revisions in mcmc package
- New regression functionality
- New case-based bootstrap sampling functionality
- Improved control variate implementation

## R1.0.3
- fixed issue with PMFModeler that caused bin probabilities to be incorrectly updated
- added the ability to save plots to PDFModeler

## R1.0.2
- added support for plotting output from simulation (ksl.utilities.io.plotting)
- added distribution fitting and testing capabilities (ksl.utilities.distributions.fitting)
- added conveyors for process modeling (ksl.modeling.entity.Conveyor)
- revised database structure (ksl.utilities.io.dbutil.KSLDatabase)
- added multi-objective decision analysis functionality (ksl.utilities.moda)
- dataframe I/O (ksl.utilities.io.DataFrameUtil)
- updated examples in KSLExamples project
- updated KSLProjectTemplate to use new release
