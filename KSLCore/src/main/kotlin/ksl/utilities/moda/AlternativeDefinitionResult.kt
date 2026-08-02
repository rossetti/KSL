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
 *  The reason an alternative offered to a model was not taken into it.
 *
 *  An alternative that is not taken in is simply absent from the results, which on its own looks
 *  indistinguishable from never having been offered. Each case names the alternative and says
 *  enough to correct the call.
 */
sealed interface AlternativeRejection {

    /** The name of the alternative that was not taken in. */
    val alternative: String

    /** A description suitable for showing to a user. */
    val message: String

    /**
     *  The alternative was offered [actual] scores where the model has [expected] metrics.
     *  Every alternative has to be scored on every metric for the alternatives to be comparable.
     */
    data class WrongScoreCount(
        override val alternative: String,
        val expected: Int,
        val actual: Int
    ) : AlternativeRejection {
        override val message: String
            get() = "Alternative '$alternative' was given $actual score(s) but the model has " +
                    "$expected metric(s). Every metric must be scored."
    }

    /**
     *  The alternative was scored on a metric the model does not hold, named [metricName].
     *
     *  Metrics are held by identity rather than by name, so the usual cause is a metric that was
     *  built a second time somewhere rather than the model's own metric being reused. The name is
     *  reported precisely because it will usually look correct: it is the identity that differs, not
     *  the name.
     */
    data class UnknownMetric(
        override val alternative: String,
        val metricName: String
    ) : AlternativeRejection {
        override val message: String
            get() = "Alternative '$alternative' was scored on a metric named '$metricName' that " +
                    "the model does not hold. Metrics are matched by identity rather than by name, " +
                    "so this is usually a separately created metric with the same name; score the " +
                    "alternative against the model's own metric instead."
    }

    /**
     *  The alternative was offered the right number of scores, all on metrics the model holds, but
     *  the metric named [metricName] was left without one because another was scored twice.
     */
    data class MissingScore(
        override val alternative: String,
        val metricName: String
    ) : AlternativeRejection {
        override val message: String
            get() = "Alternative '$alternative' has no score for metric '$metricName', because " +
                    "another metric was scored more than once."
    }
}

/**
 *  Which of the offered alternatives a model took in, and why it left out any that it did not.
 *
 *  @param accepted the names of the alternatives now held by the model, in the order offered
 *  @param rejected one entry for each alternative that was not taken in, saying why
 */
data class AlternativeDefinitionResult(
    val accepted: List<String>,
    val rejected: List<AlternativeRejection>
) {

    /** Indicates whether every offered alternative was taken in. */
    val allAccepted: Boolean
        get() = rejected.isEmpty()

    /** The rejections as lines of text, suitable for logging or showing to a user. */
    fun rejectionMessages(): List<String> = rejected.map { it.message }
}
