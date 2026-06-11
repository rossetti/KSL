# KSL Release Notes

Release history for **KSLCore** (`io.github.rossetti:KSLCore`), newest first.
These notes cover the published library, including the `ksl.app.*` infrastructure
that ships inside it; the Swing applications are separate modules (see the README's
build section) and are not part of the KSLCore artifact.

## R1.3

A large feature release: 288 commits to KSLCore since R1.2.7, headlined by three new
(experimental) modeling domains and a model‑packaging / run‑configuration substrate,
plus optimization, reporting, and numerics additions.

### New modeling domains (all new and experimental)

- **Supply‑chain modeling (new, experimental).** A `ksl.modeling.supplychain` package adds
  `SupplyChainModel` with demand/order flows and concrete multi‑echelon inventory policies
  (rQ, (r,S), continuous & periodic, warehouses, cross‑docks) plus cost and transport layers.
  The largest single addition in this release; the API is new and expected to evolve.
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
