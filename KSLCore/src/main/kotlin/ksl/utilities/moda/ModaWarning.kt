/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.moda

/**
 *  Something worth telling the user about that came up while a model was being set up or its
 *  metric domains adjusted.
 *
 *  These are not errors. In each case the model has a defined, defensible result, and evaluation
 *  continues. They are reported because the result is one a user would want to know the reason for:
 *  a metric that turned out not to separate the alternatives at all, or a domain adjustment that
 *  was proposed and then not applied, is easy to misread as a mistake in the model when it is
 *  actually a property of the data.
 *
 *  Every case names the metric it concerns, so a caller can report the warning without having to
 *  work out which metric produced it.
 */
sealed interface ModaWarning {

    /** The name of the metric the warning concerns. */
    val metric: String

    /** A description suitable for showing to a user. */
    val message: String

    /**
     *  Every alternative scored the same on this metric, so its domain was widened around that
     *  common [score] rather than fitted to a range of zero width.
     *
     *  A metric that assigns every alternative the same score carries nothing that could separate
     *  them. All alternatives take the midpoint of the value range, so the metric contributes
     *  equally to each and the decision is left to the metrics that do discriminate.
     */
    data class TiedScores(
        override val metric: String,
        val score: Double
    ) : ModaWarning {
        override val message: String
            get() = "Every alternative scored $score on metric '$metric', so it cannot separate them. " +
                    "It contributes equally to every alternative and changes no ranking."
    }

    /**
     *  A domain of [candidate] was proposed for this metric from the realized scores but not
     *  applied, because at least one realized score falls outside it. The metric keeps its declared
     *  domain for every alternative.
     *
     *  Applying it to only the alternatives it fits would mean evaluating one metric against two
     *  different domains within a single study, which would make the resulting values
     *  incomparable. Reachable only when the metric permits adjusting one limit but not the other.
     */
    data class DomainNotApplied(
        override val metric: String,
        val candidate: String
    ) : ModaWarning {
        override val message: String
            get() = "The domain $candidate proposed for metric '$metric' does not contain every " +
                    "realized score, so the declared domain was kept instead."
    }

    /**
     *  A domain was proposed for this metric that has no width once the metric's own limits were
     *  respected, so it was not applied and the declared domain was kept.
     *
     *  A domain of zero width cannot be transformed into a value, so applying it would produce a
     *  value that is not a number. Reachable when a limit that may not be adjusted already sits at
     *  the value the realized scores suggest for the other limit.
     */
    data class DegenerateDomain(
        override val metric: String,
        val candidate: String
    ) : ModaWarning {
        override val message: String
            get() = "The domain $candidate proposed for metric '$metric' has no width, so the " +
                    "declared domain was kept instead."
    }
}
