/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

/*
 * Moved from the research repository's ksl.examples.mixture into KSLCore's TEST source set.
 *
 * It is a test fixture, not an example: a known three-component truth and samples drawn from it,
 * so a test can check what a fitter recovers against what generated the data. Keeping it in the
 * test tree means it is not published as library surface, and it sits beside the tests that use
 * it rather than in an examples module they would have to depend on.
 */
package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.MixtureDistribution
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.Uniform
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVType

/**
 *  The dataset used by every example in this package: service times at a hospital reception
 *  desk, in minutes.
 *
 *  Three kinds of visitor, and therefore three components from three *different* families:
 *
 *  | visitor | distribution | share |
 *  |---|---|---|
 *  | booked check-in | Normal, mean 3, variance 0.36 | 50% |
 *  | new registration | Lognormal, mean 9, variance 12 | 35% |
 *  | complex case | Uniform on 16 to 30 | 15% |
 *
 *  The truth is built as a KSL `MixtureDistribution`, so it is an ordinary distribution object:
 *  it has a density, a CDF, a mean, and it can hand you a random variable to sample from. There
 *  is no need to write a sampler by hand, and you should not — a mixture is exactly the sort of
 *  thing the library already knows about.
 *
 *  The data are simulated so that the examples can compare a fitted mixture against the truth.
 *  On real data you would not have that luxury, which is what Example 6 is about.
 */

/** The mixing weights of the three kinds of visitor. */
val receptionDeskWeights: DoubleArray = doubleArrayOf(0.50, 0.35, 0.15)

/** The true components that generated the data. */
val receptionDeskComponents: List<ContinuousDistributionIfc> = listOf(
    Normal(3.0, 0.36),
    Lognormal(9.0, 12.0),
    Uniform(16.0, 30.0)
)

/** The family of each true component, for comparing against what a fit recommends. */
val receptionDeskFamilies: List<RVType> = listOf(RVType.Normal, RVType.Lognormal, RVType.Uniform)

/**
 *  The true mixture, as a distribution you can evaluate and sample from.
 *
 *  `MixtureDistribution` wants the mixing distribution as a CDF rather than as weights, which is
 *  what `KSLRandom.makeCDF` is for.
 */
val receptionDeskTruth: MixtureDistribution = MixtureDistribution(
    receptionDeskComponents,
    KSLRandom.makeCDF(receptionDeskWeights),
    name = "reception desk service times"
)

/**
 *  Generates service times by asking the true mixture for a random variable.
 *
 *  A fixed stream number means you get the same data every time you run any example, so the
 *  numbers you see match the ones in the report. Change [streamNum] to see how much the answers
 *  move from one sample to the next — that variability is exactly what the designed experiment
 *  in the report measures.
 *
 *  @param n how many observations to generate
 *  @param streamNum which random number stream to draw from
 */
fun receptionDeskData(n: Int = 400, streamNum: Int = 1): DoubleArray =
    receptionDeskTruth.randomVariable(streamNum).sample(n)

/**
 *  A second, independent sample from the same truth, drawn from a different stream.
 *
 *  Used to check a fitted mixture against data it has not seen. A model that fits the data it was
 *  built from and nothing else has not learned anything.
 */
fun receptionDeskHoldOut(n: Int = 400): DoubleArray = receptionDeskData(n, streamNum = 21)
