The Kotlin Simulation Library (KSL) is a Kotlin library for performing Monte Carlo and Discrete-Event
Dynamic System computer simulations.

The KSL has the following functionality:

- Discrete event calendar and executive
- Random number stream control
- Discrete and continuous random variate generation
  - Bernoulli, Beta, ChiSquared, Binomial, Constant, DUniform, Exponential, Gamma, GeneralizedBeta, Geometric, JohnsonB, Laplace, LogLogistic, Lognormal, NegativeBinomial, Normal, PearsonType5, PearsonType6, Poisson, ShiftedGeometric, Triangular, Uniform, Weibull, DEmpirical, Empirical, AR1Normal
- Statistical summary collection including histograms and box plots
- Monte Carlo simulation
- Event view modeling
- Process view modeling
- Simulation data collection to Excel, CSV, databases, and data frames
- Utility extensions for working with arrays and files

## Licensing

The KSL is licensed under the [GPL 3.0](https://www.gnu.org/licenses/gpl-3.0.en.html)

Why the GPL and not the LGPL? The KSL has functionality that could be used to form propriety simulation software. Using the GPL rather than the LGPL prevents this from happening.  Developers and companies are free to use the KSL. Nothing prevents its use in performing (in-house) simulation analysis within industry. In fact, this is encouraged. However, developers or companies that want to build and extend the KSL (especially for commercial or proprietary reasons), are not permitted under the GPL. Developers and companies are encouraged to add functionality to the KSL and release the functionality so that everyone can benefit.

## Cloning and Setting Up a Project

If you are using IntelliJ, you can use its clone repository functionality to 
setup a working version. Or, simply download the repository and use IntelliJ to open up
the repository.  IntelliJ will recognize the KSL project as a gradle build and configure an appropriate project.

This is a Gradle based project.

## KSL Book

https://rossetti.github.io/KSLBook/

The book explains how to use the KSL

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

KSLProjectTemplate - a pre-configured project using gradle that is setup to use the KSL

KSLTesting - a separate project that does some basic testing related to the KSL

group = "io.github.rossetti"
name = "KSLCore"
version = "R1.0.2"

## Release Notes

Latest Release: R1.0.2

- added support for plotting output from simulation (ksl.utilities.io.plotting)
- added distribution fitting and testing capabilities (ksl.utilities.distributions.fitting)
- added conveyors for process modeling (ksl.modeling.entity.Conveyor)
- revised database structure (ksl.utilities.io.dbutil.KSLDatabase)
- added multi-objective decision analysis functionality (ksl.utilities.moda)
- dataframe I/O (ksl.utilities.io.DataFrameUtil)
- updated examples in KSLExamples project
- updated KSLProjectTemplate to use new release
	
