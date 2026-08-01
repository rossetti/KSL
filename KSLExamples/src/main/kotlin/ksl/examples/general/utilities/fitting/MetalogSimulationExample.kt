/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.examples.general.utilities.fitting

import ksl.examples.book.chapter4.DriveThroughPharmacyWithQ
import ksl.simulation.Model
import ksl.utilities.distributions.metalog.MetalogBoundedness
import ksl.utilities.distributions.metalog.MetalogDistribution
import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.distributions.fitting.estimators.MetalogParameterEstimator
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.random.rvariable.metalog.Metalog4PRV

/**
 *  Driving a simulation model with a fitted metalog, and changing its parameters afterwards.
 *
 *  The point of this example is that nothing special is required. A metalog random variable is an
 *  ordinary parameterized random variable: it has a registered type, its parameters are named
 *  scalars, and every part of the KSL that works by parameter name works on it. That includes the
 *  parameter setter, the experiment database, the run configuration files and the applications.
 *
 *  The one thing that looks unusual is that an absent bound is carried as an infinity rather than
 *  being left out. An unbounded metalog really does report a lower bound of negative infinity.
 *  Everything that transports random variable parameters in the KSL accepts that.
 */
fun main() {
    val serviceTimeData = observedServiceTimes()
    val fitted = fitTheServiceTime(serviceTimeData)

    println()
    val model = buildModel(fitted)
    runIt(model, "base case")

    println()
    inspectTheParameters(model)

    println()
    changeAParameterAndRerun(model)

    println()
    aChangeThatIsNotADistribution(model)
}

/**
 *  Stands in for a file of observed service times. Right skewed and strictly positive, which is
 *  what a lower-bounded metalog is for.
 */
private fun observedServiceTimes(size: Int = 500): DoubleArray {
    return LognormalRV(mean = 3.0, variance = 2.0, streamNum = 21).sample(size)
}

/**
 *  Four terms, lower bounded. The lower bound is profiled from the data rather than supplied,
 *  which is the default. Supply one when the floor is known — a service time that cannot
 *  physically fall below a setup time, say — because a profiled bound is only a good fit, not a
 *  fact about the system.
 */
private fun fitTheServiceTime(data: DoubleArray): MetalogDistribution {
    println("Fitting the service time")
    println("=".repeat(70))

    val estimator = MetalogParameterEstimator(numTerms = 4, boundedness = MetalogBoundedness.LowerBounded)
    val result = PDFModeler(data).estimateParameters(estimator, automaticShifting = false)
    check(result.success) { "the service time could not be fitted: ${result.message}" }

    val fitted = PDFModeler.createDistribution(result.parameters!!) as MetalogDistribution
    println(result.message)
    println("fitted: $fitted")
    println("support: ${fitted.domain()}")
    println("mean of the fit %.4f versus a sample average of %.4f"
        .format(fitted.mean(), data.average()))
    return fitted
}

/**
 *  An ordinary model, with the fitted metalog installed as the service time. `randomVariable`
 *  returns the concrete registered type, so the model holds a Metalog4PRV rather than a generic
 *  inverse transform wrapper, and the parameter machinery can see through it.
 */
private fun buildModel(fitted: MetalogDistribution): Model {
    val model = Model("Pharmacy with a metalog service time")
    val pharmacy = DriveThroughPharmacyWithQ(model, numServers = 1)
    pharmacy.arrivalGenerator.initialTimeBtwEvents = ExponentialRV(6.0, streamNum = 1)

    val serviceRV = fitted.randomVariable(streamNumber = 2)
    check(serviceRV is Metalog4PRV) { "expected a Metalog4PRV, found ${serviceRV::class.simpleName}" }
    pharmacy.serviceRV.initialRandomSource = serviceRV

    model.numberOfReplications = 20
    model.lengthOfReplication = 20_000.0
    model.lengthOfReplicationWarmUp = 5_000.0
    return model
}

private fun runIt(model: Model, label: String) {
    println("Running: $label")
    println("=".repeat(70))
    model.simulate()
    model.simulationReporter.printHalfWidthSummaryReport()
}

/**
 *  What the rest of the KSL sees. The setter reports every metalog parameter by name, exactly as
 *  it reports `mean` for the exponential arrival process, and these are the names an experiment,
 *  a run configuration or an application uses to address them.
 */
private fun inspectTheParameters(model: Model) {
    println("Random variable parameters visible to the model")
    println("=".repeat(70))
    for ((key, value) in model.rvParameterSetter.flatParametersAsDoubles) {
        println("  ${key.padEnd(48)} $value")
    }
    println()
    println(
        "The upper bound of Infinity is the lower-bounded member declaring that it has no ceiling. " +
                "It is a real parameter value, not a missing one."
    )
}

/**
 *  Changing a fitted parameter and running again. The setter is addressed by random variable name
 *  and parameter name; the change takes effect when it is applied to the model.
 */
private fun changeAParameterAndRerun(model: Model) {
    println("Changing the service time and running again")
    println("=".repeat(70))

    val setter = model.rvParameterSetter
    val serviceName = serviceRandomVariableName(model)
    val before = setter.rvParameters[serviceName]!!.doubleParameter("a1")
    println("$serviceName.a1 is currently $before")

    // a1 is the location of the metalog in its fitting space. For a lower-bounded member the
    // fitting space is logarithmic, so raising a1 scales the service time up rather than shifting
    // it. Increasing it makes the pharmacy busier.
    setter.changeParameters(mapOf(serviceName to mapOf("a1" to before + 0.2)))
    val applied = setter.applyParameterChanges(model)
    println("applied $applied change(s); the service time is now")
    println("  ${model.randomVariables().first { it.name == serviceName }.initialRandomSource}")
    println()

    model.experimentName = "longer service"
    runIt(model, "with a larger a1")
}

/**
 *  Not every set of numbers is a metalog. The coefficients have to describe a strictly increasing
 *  quantile function, or there is no distribution. That is checked when the change is applied, so
 *  an invalid parameter set is refused rather than silently producing nonsense variates.
 */
private fun aChangeThatIsNotADistribution(model: Model) {
    println("A parameter change that is refused")
    println("=".repeat(70))

    val setter = model.rvParameterSetter
    val serviceName = serviceRandomVariableName(model)
    // A negative scale coefficient reverses the quantile function.
    setter.changeParameters(mapOf(serviceName to mapOf("a2" to -1.0)))
    try {
        setter.applyParameterChanges(model)
        println("the change was accepted, which it should not have been")
    } catch (e: IllegalArgumentException) {
        println("refused, as it should be:")
        println("  ${e.message}")
    }
}

/**
 *  The model element name of the pharmacy's service time random variable. Looked up rather than
 *  hard coded, because the name is assigned when the model is built.
 */
private fun serviceRandomVariableName(model: Model): String {
    return model.randomVariables()
        .first { it.initialRandomSource is Metalog4PRV }
        .name
}
