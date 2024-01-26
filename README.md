The Kotlin Simulation Library (KSL) is a Kotlin library for performing Monte Carlo and Discrete-Event
Dynamic System computer simulations.

The KSL has the following functionality:

- Discrete event calendar and executive
- Random number stream control
- Discrete and continuous random variate generation
  - Bernoulli, Beta, ChiSquared, Binomial, Constant, DUniform, Exponential, Gamma, GeneralizedBeta, Geometric, JohnsonB, Laplace, LogLogistic, Lognormal, NegativeBinomial, Normal, PearsonType5, PearsonType6, Poisson, ShiftedGeometric, Triangular, Uniform, Weibull, DEmpirical, Empirical, AR1Normal
- Statistical summary collection including histograms and box plots
- Probability distribution modeling
- Monte Carlo simulation
- Event view modeling
- Process view modeling
  - non-stationary arrivals
  - entity modeling, movement
  - resources, mobile resources
  - conveyors
- Simulation data collection to Excel, CSV, databases, and data frames
- Utility extensions for working with arrays and files

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
version = "R1.0.5"

## Release Notes

Latest Release: R1.0.5
- Fixed AcceptanceRejectRV to correctly use majorizing function
- added Logistic random variable and distribution
- updated RVParameters and RVType for more flexibility
- added Laplace distribution
- improved KSLArrays.isAllEqual() and isAllDifferent() to account for double precision

Release: R1.0.4
- Fixed axis label issue in ScatterPlot
- Added examples for bootstrapping, VRT, and MCMC
- New classes for multi-variate copulas, minor revisions in mcmc package
- New regression functionality
- New case based bootstrap sampling functionality
- Improved control variate implementation

Release: R1.0.3

- fixed issue with PMFModeler that caused bin probabilities to be incorrectly updated
- added ability to save plots to PDFModeler

Release: R1.0.2

- added support for plotting output from simulation (ksl.utilities.io.plotting)
- added distribution fitting and testing capabilities (ksl.utilities.distributions.fitting)
- added conveyors for process modeling (ksl.modeling.entity.Conveyor)
- revised database structure (ksl.utilities.io.dbutil.KSLDatabase)
- added multi-objective decision analysis functionality (ksl.utilities.moda)
- dataframe I/O (ksl.utilities.io.DataFrameUtil)
- updated examples in KSLExamples project
- updated KSLProjectTemplate to use new release
	
