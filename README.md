The Kotlin Simulation Library (KSL) is a Kotlin library for performing Monte Carlo and Discrete-Event
Dynamic System computer simulations.

The KSL has the following functionality:

- Discrete event calendar and executive
- Random number stream control
- Discrete and continuous random variate generation
  - Bernoulli, Beta, ChiSquared, Binomial, Constant, DUniform, Exponential, Gamma, GeneralizedBeta, Geometric, JohnsonB, Laplace, LogLogistic, Lognormal, NegativeBinomial, Normal, PearsonType5, PearsonType6, Poisson, ShiftedGeometric, Triangular, Uniform, Weibull, DEmpirical, Empirical, AR1Normal
- Statistical summary collection including histograms and box plots
- Automated probability distribution modeling
- Monte Carlo simulation
- Event view modeling
- Process view modeling
  - non-stationary arrivals
  - entity modeling, movement
  - resources, mobile resources
  - conveyors
- Simulation data collection to Excel, CSV, databases, and data frames
- Support for multiple comparison with the best
- Framework for defining multiple objective decision analysis (MODA) based simulation analysis
- Framework for performing simulation optimization
- Framework for performing designed simulation experiments
- Utility extensions for working with arrays and files

## KSL Road Map
Who knows what the future may bring! The KSL is a complex and extremely useful library for performing Monte Carlo and discrete event
simulation experiments.  Here is some planned and potential future functionality.

- Release 1.2.1 provides simulation optimization functionality. Future releases will extend this with additional solvers and the ability to execution models as a service. That is software as a service in the cloud.
- A GUI-based simulation application framework is planned to enable the development of KSL based applications.
- Preliminary work has been performed to add animation capabilities. This is still under active investigation.

## Licensing

The KSL is licensed under the [GPL 3.0](https://www.gnu.org/licenses/gpl-3.0.en.html)

Why the GPL and not the LGPL? The KSL has functionality that could be used to form propriety simulation software. 
Using the GPL rather than the LGPL prevents this from happening.  Developers and companies are free to use the KSL. 
Nothing prevents its use in performing (in-house) simulation analysis within industry. In fact, this is encouraged. 
However, developers or companies that want to build and **extend** the KSL (especially for commercial or proprietary reasons), 
are not permitted under the GPL, unless they want to release the functionality under the GPL. 
Developers and companies are encouraged to add functionality to the KSL and release 
the functionality so that everyone can benefit. Developers who want to extend the KSL for proprietary or commercial 
purposes can contact the KSL development team for other possible licensing arrangements.

## Cloning and Setting Up a Project

If you are using IntelliJ, you can use its clone repository functionality to 
set up a working version. Or, simply download the repository and use IntelliJ to open up
the repository.  IntelliJ will recognize the KSL project as a gradle build and configure an appropriate project.

This is a Gradle based project.

## KSL Book

https://rossetti.github.io/KSLBook/

The [book](https://rossetti.github.io/KSLBook/) explains how to use the KSL.  For example, in 
[Chapter 6](https://rossetti.github.io/KSLBook/processview.html) of the text, you will see fully worked out examples of 
how to implement the process view of simulation.  For example, to simulate a simple M/M/c queue, code such as this 
can be easily developed using the KSL.  This code models the usage of a resource via suspending functions, seize(),
delay(), and release(). In addition, it collects statistics on the processing of the entities.

```
    private inner class Customer : Entity() {
        val pharmacyProcess: KSLProcess = process() {
            wip.increment()
            timeStamp = time
            val a = seize(worker)
            delay(serviceTime)
            release(a)
            timeInSystem.value = time - timeStamp
            wip.decrement()
            numCustomers.increment()
        }
    }
```

## KSL Video Series

There is also a [video series](https://video.uark.edu/playlist/dedicated/1_0q40d3tg/) that provides an overview of getting started with the KSL and some of the associated material from the textbook.

## KSL Documentation

If you are looking for the KSL API documentation you can find it here:

https://rossetti.github.io/KSLDocs/

The repository for the documentation is here:

https://github.com/rossetti/KSLDocs

Please be aware that the book and documentation may lag the releases due to lack of developer time.

## Gradle and Build Details

The KSL is organized as a multi-project gradle build. 

KSLCore - the main simulation functionality.

Additional projects are available for illustrating and other work related to the KSL.

KSExamples - a project that has example code that illustrates the KSL and contains the examples shown in the textbook.

KSLProjectTemplate - a pre-configured project using gradle that is set up to use the KSL

KSLTesting - a separate project that does some basic testing related to the KSL

group = "io.github.rossetti"
name = "KSLCore"
version = "R1.2.5"

## Release Notes
Latest Release R1.2.5
* Bug fix in MixtureDistribution class involving numParameters property
* Changed score() function to public from protected in PDFScoringModel

Release R1.2.4
* Bug fixes involving Double.MIN_VALUE
* Added MixtureDistribution class
  - Cause some refactoring of distribution related interfaces

Release R1.2.3
* Significant improvements to the `ksl.simopt` package for simulation optimization
	- Refactored `ProblemDefinition` class. Moved penalty function modeling into `ProblemDefintion`
	- Added cross-entropy solver
	- Added R-SPLINE solver
	- Refactored simulation oracle usage framework
	- Added screening of solution
* Added chapter 10 to accompanying textbook to cover simulation optimization methods

Release R1.2.2
* Added jvmOverloads and started changes to improve usage from java
* Improved RandomElement and interaction with new `RNStreamProvider` usage
* Revised JSON configuration for `ModelBuilderIfc`
* Created `RVType` class to make it easier to specify random variable parameters and configure from JSON
* Added sum() function to `RandomVariable` class

Release R1.2.1
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

Release R1.2.0
- Updated BlockingQueue to enhance notification of waiting senders and receivers
  - Allows new rules to be used for notification
  - Corrected call for filling AmountRequests
- Updated seize() suspending function to allow request selection rules to be invoked upon first seize
- Improved use of interfaces in station package

Release R1.1.9
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

Release R1.1.8
- improved suspend/resume coding with new Suspension class
  - deprecated suspend() function in favor of newer process interaction functions
- Added AdjustedPPCCorrelation and AdjustedQQCorrelation PDF scoring models
- Added blockages to process interaction, including blocking activities
- Revised seize function to prevent edge case suspend/resume issues
- Added yield suspending function
- Changed default event priority numbering scheme
- updated logging dependencies
- improved the signature for constructing Scenarios

Latest Release R1.1.6
- Added blockUntilAllCompleted() suspending function to permit suspension until a set of processes completes.
- Added home base concept for MovableResource
- Completed MSER work for initialization bias deletion point detection
- Completed LogisticFunction scaling implementation for MODA and use in PDFModeler
- Fixed after replication termination issue for suspended processes using the waitFor() suspending function
- Added examples for entity movement

Release: R1.1.5
- Added blockUntilCompleted() suspending function to permit suspension until another process completes
- Simplified basic suspend() function

Release: R1.1.4
- updated how processes are started, removed automatic use of process sequence
- fixed random number stream assignment issue
- added piecewise constant continuous empirical random variable and distribution
- minor enhancements to pdf scoring and fitting
- fixed bootstrap standard error estimate
- refactoring to enable future removal of Apache POI dependency

Release: R1.1.3
- Added ability of IndicatorResponse to observe ResponseCIfc 
- Fixed stupid bug in EventGenerator introduced by typo in release 1.1.2.
 
Release: R1.1.2
- Added SAM functional interfaces to station package
- Don't use R1.1.2 due to stupid bug in EventGenerator, now fixed in R1.1.3
	
Release: R1.1.1
- Added ksl.modeling.station package
	- facilitate modeling of simple queueing systems
- added maps that can have randomly selected elements
- improved RList, DUniformList
- added BernoulliPicker

Release: R1.1.0
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

Release: R1.0.9
- Bug fixes and improvements in ksl.utilities.distributions.fitting package
  - fixed Weibull estimation edge cases
  - added additional output to html distribution fitting results
- Added the capability in the ksl.controls.experiments package to run many scenarios and perform designed experiments
- Improved support for data frame processing
- Updates to documentation and examples to be consistent with textbook

Release: R1.0.8
- Addressed new issue with the search interval for MLE computation of gamma shape parameter

Release: R1.0.7
- Fixed natural logarithm compute issue in Anderson-Darling test statistic
- Fixed interval search issue for Gamma MLE parameter estimation
- Added 1-D discrete Metropolis-Hasting Markov Chain, improved properties of DMarkovChain
- Allow PMF to CDF with 0 probability on mass points
- Updates to documentation, examples

Release: R1.0.6
- Fixed AcceptanceRejectRV to correctly use majorizing function
- added Logistic random variable and distribution
- updated RVParameters and RVType for more flexibility
- added Laplace distribution
- improved KSLArrays.isAllEqual() and isAllDifferent() to account for double precision
- improved histogram break point creation
- simplified interface for TruncatedRV

Release: R1.0.4
- Fixed axis label issue in ScatterPlot
- Added examples for bootstrapping, VRT, and MCMC
- New classes for multi-variate copulas, minor revisions in mcmc package
- New regression functionality
- New case-based bootstrap sampling functionality
- Improved control variate implementation

Release: R1.0.3

- fixed issue with PMFModeler that caused bin probabilities to be incorrectly updated
- added the ability to save plots to PDFModeler

Release: R1.0.2

- added support for plotting output from simulation (ksl.utilities.io.plotting)
- added distribution fitting and testing capabilities (ksl.utilities.distributions.fitting)
- added conveyors for process modeling (ksl.modeling.entity.Conveyor)
- revised database structure (ksl.utilities.io.dbutil.KSLDatabase)
- added multi-objective decision analysis functionality (ksl.utilities.moda)
- dataframe I/O (ksl.utilities.io.DataFrameUtil)
- updated examples in KSLExamples project
- updated KSLProjectTemplate to use new release
	
