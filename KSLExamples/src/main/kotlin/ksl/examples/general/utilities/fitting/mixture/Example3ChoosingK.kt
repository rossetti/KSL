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

/**
 *  Example 3: how the number of components gets chosen, and how much to trust it.
 *
 *  Adding a component can only improve the fit, so the likelihood alone would always pick the
 *  largest number you offer. A criterion adds a penalty for complexity. Four are shown here, and
 *  they do not agree.
 *
 *  Two things to look for:
 *
 *  1. Where each criterion's smallest value falls.
 *  2. Whether that smallest value sits at the largest k you offered. If it does, the criterion
 *     has not found a best answer — it has run out of options, and would keep going if you let
 *     it. That is a very different thing from choosing, and it looks identical if you only print
 *     the winner. Widen the range and watch what moves.
 *
 *  The data ships with the examples, in
 *  `KSLExamples/chapterFiles/Appendix-Distribution Fitting`. You can open it, plot it, or
 *  load it into the Distribution app; every run of every example sees the same numbers.
 */
fun main() {
    val data = receptionDeskData()

    // One fit. Every reported criterion is evaluated on the same candidates afterwards, so this
    // costs nothing extra — and no single criterion gets to decide on its own.
    val results = MixtureModeler(data).fit(numComponentsRange = 1..6)

    println(results.criterionSummary())
    println()
    println("The data really came from ${receptionDeskComponents.size} components.")
    println()

    println("How to read this")
    println("----------------")
    println("Two things matter. Where each criterion's smallest value falls, and whether that")
    println("smallest value sits at the largest k that was fitted. If it does, the criterion did")
    println("not find a best answer — it ran out of candidates, and would keep going if you")
    println("widened the range. Those two situations look identical if you only print the winner,")
    println("which is why the profile is shown rather than just the choice.")
    println()
    println("The four criteria are all penalised likelihoods differing only in how much they")
    println("charge per parameter, so they are reported side by side rather than combined.")
    println("Averaging them would manufacture an agreement the evidence does not support.")
    println()
    println("Recovering the true number of components is genuinely hard. On data where the truth")
    println("is known, the best of these criteria gets it right well under half the time, so a")
    println("single fit's answer deserves less confidence than its precision suggests. Example 8")
    println("measures how stable the answer is on your own data.")
}
