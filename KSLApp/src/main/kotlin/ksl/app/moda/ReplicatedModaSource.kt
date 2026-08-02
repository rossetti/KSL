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

package ksl.app.moda

import kotlinx.serialization.Serializable

/**
 *  How the repeated observations of a response become the single score a study compares on.
 *
 *  A simulated alternative does not have one value for a response; it has one per replication.
 *  Comparing alternatives means reducing each of those to a single number, and which reduction is
 *  right depends on the decision. Recording the choice matters as much as making it, because two
 *  studies over the same runs can reach different conclusions and the reduction is often the reason.
 */
@Serializable
enum class ReplicationAggregation {

    /** The average across replications. The usual choice, and what a long-run average means. */
    MEAN,

    /**
     *  The middle observation. Worth choosing when a response is skewed, as queueing responses
     *  usually are, and the average is pulled around by a few extreme replications.
     */
    MEDIAN,

    /**
     *  A stated percentile. For decisions about what happens on a bad day rather than on an average
     *  one, where a system is chosen for its behaviour at the ninetieth percentile rather than its
     *  mean.
     */
    PERCENTILE,

    /**
     *  The observation from the last replication.
     *
     *  Only meaningful where replications are not repeats of the same thing but successive states of
     *  it, as in a terminating run continued across replications. Averaging those would mix states
     *  the system passed through with the state it ended in.
     */
    LAST
}

/**
 *  A source whose scores come from repeated observation, and which can be asked about the
 *  individual observations as well as the summary.
 *
 *  Two things need the individual observations. Comparing alternatives statistically needs to know
 *  how much they varied, not only where they ended up on average. And running the study once per
 *  replication shows how often a recommendation wins rather than only that it wins on the averages,
 *  which is a different and usually more honest question.
 */
interface ReplicatedModaSourceIfc : ModaSourceIfc {

    /** How the repeated observations become the single score [scores] reports. */
    val aggregation: ReplicationAggregation

    /** The replications this source holds for the named alternative, in order. */
    fun replicationIds(alternative: String): List<Int>

    /**
     *  The individual observations of [metric] for [alternative], ordered by replication, or null
     *  when this source holds none.
     */
    fun replicationScores(alternative: String, metric: String): DoubleArray?

    /**
     *  The replications every named alternative has in common, in order.
     *
     *  Replications are matched by their number rather than by position, because a simulation study
     *  usually drives every alternative with the same random numbers so that replication 7 means the
     *  same conditions for each. Comparing them position by position would break that as soon as one
     *  alternative was missing a replication in the middle.
     */
    fun commonReplicationIds(alternatives: Collection<String>): List<Int> {
        if (alternatives.isEmpty()) return emptyList()
        return alternatives
            .map { replicationIds(it).toSet() }
            .reduce { shared, next -> shared intersect next }
            .sorted()
    }
}
