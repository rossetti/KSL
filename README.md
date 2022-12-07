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

KSLExampleProject - a pre-configured project using gradle that is setup to use the KSL

KSLTesting - a separate project that does some basic testing related to the KSL

group = "io.github.rossetti"
name = "KSLCore"
version = "R1.0.0"

## Release Notes

Latest Release: R0.0.1
