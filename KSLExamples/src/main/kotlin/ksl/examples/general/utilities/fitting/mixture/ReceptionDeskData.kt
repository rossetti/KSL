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

package ksl.examples.general.utilities.fitting.mixture

import ksl.utilities.distributions.ContinuousDistributionIfc
import ksl.utilities.distributions.Lognormal
import ksl.utilities.distributions.MixtureDistribution
import ksl.utilities.distributions.Normal
import ksl.utilities.distributions.Uniform
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.random.rvariable.RVType
import java.nio.file.Files
import java.nio.file.Path

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
 *  **The observations come from a file, not from a generator.** They live beside the book's other
 *  input-modeling datasets in `KSLExamples/chapterFiles/Appendix-Distribution Fitting`, as two
 *  plain columns of numbers you can open, plot, or load into the Distribution app. Two reasons to
 *  read them rather than draw them:
 *
 *  1. The tutorial quotes numbers computed from this data. Drawing the sample at run time would
 *  tie every one of those numbers to the random number streams producing the same values forever,
 *  and nothing would announce it if they ever did not.
 *  2. A dataset a reader can look at is worth more than one that only exists while a program runs.
 *
 *  The *truth* below is still built in code, because it is five constants rather than a dataset,
 *  and because the examples compare a fitted mixture against it. `ReceptionDeskDataTest` checks
 *  that the files really are a sample from this truth, drawn the way the header says.
 *
 *  On real data you would not have a truth to compare against, which is what Example 6 is about.
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

/** The stream the shipped sample was drawn from, recorded so the file can be checked against it. */
const val RECEPTION_DESK_STREAM: Int = 1

/** The stream the shipped hold-out sample was drawn from. */
const val RECEPTION_DESK_HOLD_OUT_STREAM: Int = 21

/** How many observations each shipped file holds. */
const val RECEPTION_DESK_SIZE: Int = 400

/** The directory holding the book's input-modeling datasets, relative to the KSLExamples module. */
private const val DATA_DIR: String = "chapterFiles/Appendix-Distribution Fitting"

private const val SERVICE_TIMES_FILE: String = "ReceptionDeskServiceTimes.txt"

private const val HOLD_OUT_FILE: String = "ReceptionDeskHoldOut.txt"

/**
 *  Locates one of the shipped datasets without assuming which directory the JVM was started in.
 *
 *  Running an example from IntelliJ puts the working directory at the repository root; running it
 *  from Gradle puts it at the module. Rather than require one of those, try the places the file
 *  can legitimately be and fail with the list when it is in none of them — a wrong path here would
 *  otherwise surface as an empty array and a fit of nothing.
 */
private fun receptionDeskFile(fileName: String): Path {
    val candidates = listOf(
        Path.of(DATA_DIR, fileName),
        Path.of("KSLExamples", DATA_DIR, fileName),
        Path.of("..", "KSLExamples", DATA_DIR, fileName)
    )
    return candidates.firstOrNull { Files.isRegularFile(it) }
        ?: error(
            "cannot find $fileName. Looked in:\n  " +
                candidates.joinToString("\n  ") { it.toAbsolutePath().normalize().toString() }
        )
}

/**
 *  The reception desk service times: 400 observations, in minutes.
 *
 *  Read from the shipped dataset, so every run of every example sees the same numbers.
 */
fun receptionDeskData(): DoubleArray = KSLFileUtil.scanToArray(receptionDeskFile(SERVICE_TIMES_FILE))

/**
 *  A second, independent sample from the same truth: 400 observations the fitting never sees.
 *
 *  Used to check a fitted mixture against data it was not built from. A model that fits the data
 *  it was built from and nothing else has not learned anything.
 */
fun receptionDeskHoldOut(): DoubleArray = KSLFileUtil.scanToArray(receptionDeskFile(HOLD_OUT_FILE))
