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

package ksl.examples.general.utilities.fitting.mixture

import ksl.utilities.distributions.fitting.mixture.MixtureBootstrap
import ksl.utilities.distributions.fitting.mixture.MixtureModeler

/**
 *  Example 8: how much should you believe the recommendation?
 *
 *  A fit reports one number of components. It does not say whether a slightly different sample
 *  would have produced the same answer — and for this method, very often it would not.
 *
 *  Refitting on resampled data answers that directly. If one number of components wins nearly
 *  every resample, the data determine it. If two are close, the recommendation is nearly a coin
 *  toss and should not be reported as though it were settled.
 *
 *  This is opt-in because it costs a full refit per resample. Start with a small number of
 *  resamples to see what it costs on your data before asking for hundreds.
 *
 *  The data ships with the examples, in
 *  `KSLExamples/chapterFiles/Appendix-Distribution Fitting`. You can open it, plot it, or
 *  load it into the Distribution app; every run of every example sees the same numbers.
 */
fun main() {
    val data = receptionDeskData()

    // The ordinary fit first, so we have something to be sceptical about.
    val results = MixtureModeler(data).fit(numComponentsRange = 1..6)
    val best = results.best
    if (best == null) {
        println("No mixture could be fitted.")
        return
    }
    println("A single fit recommends k = ${best.candidate.numComponents}")
    println("The data really came from ${receptionDeskComponents.size} components.")
    println()

    // Resample the data. Keep this modest at first: each resample is a full refit.
    val started = System.nanoTime()
    val boot = MixtureBootstrap.componentCountFrequency(
        data,
        numBootstrapSamples = 100,
        numComponentsRange = 1..6
    )
    val seconds = (System.nanoTime() - started) / 1e9

    println(boot)
    println("  (${"%.1f".format(seconds)} s for ${boot.numSamples} resamples, " +
            "about ${"%.0f".format(1000.0 * seconds / boot.numSamples)} ms each)")
    println()

    println("How to read this")
    println("----------------")
    println("The bar chart is the whole point. A single tall bar means the number of components")
    println("is well determined. Several comparable bars mean it is not, and any single number")
    println("you quote from one fit is one draw from that spread.")
    println()
    println("Note the distinct-value line. Resampling with replacement repeats values, so a")
    println("resample holds fewer distinct values than the original. That matters here because a")
    println("cut may not split tied observations, so the resamples are structurally a little")
    println("different from the data. The parametric version below avoids that.")
    println()

    // The optimistic bound: sample from the fitted mixture itself. No ties, but it assumes the
    // fit is right — which is the thing being questioned.
    val parametric = MixtureBootstrap.parametricComponentCountFrequency(
        best.distribution,
        sampleSize = data.size,
        numBootstrapSamples = 100,
        numComponentsRange = 1..6
    )
    println(parametric)
    println()
    println("The two disagree in an informative way. The parametric version asks how well the")
    println("procedure recovers an answer it was handed; the nonparametric one asks how much the")
    println("data pin the answer down. Neither alone is the whole story.")
}
