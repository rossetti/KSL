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

- Release 1.3 provides significant new enhancements that are noted in [the release notes](docs/release-notes.md) This includes a simulation application framework to enable the development of KSL based applications.
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

## Build Structure

The KSL is a multi-project Gradle build. The published library is **KSLCore**; the other
modules are examples, tooling, and a set of desktop applications built on top of it.

| Module | Purpose |
|---|---|
| **KSLCore** | The published simulation library (`io.github.rossetti:KSLCore`) — all core simulation, statistics, random-variate, optimization, and reporting functionality, plus the `ksl.app.*` model-bundling and run-configuration infrastructure. |
| **KSLExamples** | Example code, including the examples shown in the textbook. |
| **KSLAppSwingCommon** | Shared Swing building blocks used by the desktop apps (bundle/model pickers, notifications, run console, look-and-feel). |
| **KSLAppSwingSingle** | Desktop app for exercising a single model through its controls/parameters. |
| **KSLAppSwingScenario** | Desktop app for defining and running a set of scenarios. |
| **KSLAppSwingExperiment** | Desktop app for designed experiments over a model. |
| **KSLAppSwingSimopt** | Desktop app for simulation optimization. |
| **KSLAppSwingResults** | Desktop app for browsing and comparing results databases. |
| **KSLAppSwingDistribution** | Desktop app for probability-distribution fitting and analysis. |
| **KSLBundleTools** | Command-line tooling for packaging models as loadable KSL *bundle* JARs. |
| **KSLTesting** | JUnit 5 test suite. |

The desktop apps depend only on **KSLCore** (and **KSLAppSwingCommon**). They load models as
self-describing *bundle JARs* through the `ksl.app.bundle` `ServiceLoader` mechanism, discovered
from `~/.ksl/bundles/` — so models are not compiled into the apps. **KSLProjectTemplate** is a
separate, pre-configured starter project for building your own models against a published KSL
release.

The published version is set in `KSLCore/build.gradle.kts` (the `version` property):

```
group = "io.github.rossetti"
name = "KSLCore"
version = "R1.3"
```
Just add:  
```
api("io.github.rossetti:KSLCore:R1.3")
```
To your build for the latest release.

## Release Notes

The full release history lives in **[docs/release-notes.md](docs/release-notes.md)**.

**Latest — R1.3:** three new (experimental) modeling domains (supply-chain, queueing-network
stations, agent-based), a model-bundling and run-configuration substrate, parallel designed
experiments and scenarios, a multi-format reporting framework, plus dependency changes
(Excel I/O moved from Apache POI to fastexcel; DuckDB removed). See
[the release notes](docs/release-notes.md) for the full list.
