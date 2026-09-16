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

import ksl.utilities.distributions.fitting.mixture.MixtureModeler
import ksl.utilities.io.report.extensions.showHTMLInBrowser

/**
 *  Example 7: judging a fitted mixture with the same tools you would use on any distribution.
 *
 *  A fitted mixture is an ordinary continuous distribution. That means every diagnostic the
 *  library already offers applies to it unchanged — the four-panel fit plot, and the
 *  Anderson–Darling, Cramér–von Mises and Kolmogorov–Smirnov tests with their p-values.
 *
 *  The results object answers the question you actually have — **was a mixture worth it?** — by
 *  comparing the recommendation against the best single distribution. That comparison is exact
 *  rather than approximate: a one-component mixture *is* a single distribution, fitted from the
 *  same catalog and ranked by the same criterion, so no separate fit is involved.
 *
 *  The data ships with the examples, in
 *  `KSLExamples/chapterFiles/Appendix-Distribution Fitting`. You can open it, plot it, or
 *  load it into the Distribution app; every run of every example sees the same numbers.
 */
fun main() {
    val data = receptionDeskData()
    val holdOut = receptionDeskHoldOut()

    // Supply the data, ask for the fit, show the results. One call for the last step.
    val results = MixtureModeler(data).fit(numComponentsRange = 1..6)
    results.showAllResultsInBrowser(heldOut = holdOut)

    // The same results as data, for anything you want to do with them afterwards.
    println("Components as a data frame")
    println("--------------------------")
    println(results.componentsAsDataFrame())
    println()
    println("Criterion profile as a data frame (long form)")
    println("---------------------------------------------")
    println(results.criterionProfileAsDataFrame())
    println()

    // And a self-contained report, plots included, that you can keep or send.
    val report = results.showHTMLInBrowser(heldOut = holdOut)
    println("A written report has opened: ${report.absolutePath}")

    println()
    println("Two plot windows have opened: the fitted mixture and the best single distribution,")
    println("four panels each. Look at the density panel of both — the single distribution has")
    println("one shape to spend, and the data has more than one.")
    println()
    println("A caution the summary cannot make for you. Passing a goodness-of-fit test is weaker")
    println("evidence than it looks when the distribution was chosen by looking at the same data.")
    println("Both fits here were. Read the tests as a comparison between the two rather than as")
    println("an endorsement of either; the held-out coverage line is the honest check.")
}
