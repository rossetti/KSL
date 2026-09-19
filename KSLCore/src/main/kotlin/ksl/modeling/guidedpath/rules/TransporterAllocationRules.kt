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
package ksl.modeling.guidedpath.rules

import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.randomlySelect

/**
 * Chooses which idle transporter is sent to collect an entity.
 *
 * Dispatching is one of the two decisions that most affect how a guide path performs, and it is
 * the one most often varied in a study, so it is a replaceable rule rather than a fixed policy.
 *
 * A rule must choose from the candidates it is given, all of which are idle. It must not move,
 * allocate, or otherwise disturb anything: it decides only. And it must be a pure function of what
 * it is given, or take its randomness from a stream the model controls, or the run stops being
 * reproducible.
 */
fun interface GuidedTransporterAllocationRuleIfc {

    /**
     * @param network the guide path, for measuring distance along it
     * @param pickup where the transporter is wanted
     * @param candidates the idle transporters, never empty
     * @return the one to send, which must be one of the candidates
     */
    fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter

    /**
     * Clears whatever the rule remembers between replications. Does nothing by default.
     *
     * Almost every rule here is a pure function of what it is given and needs this not at all. A
     * rule that cycles, or that remembers who it chose last, is not -- and a rule carrying the end
     * of one replication into the start of the next makes the second replication depend on the
     * first, which is the one thing a replication may never do. The pool calls this as each
     * replication begins, so a stateful rule is correct without its author having to remember.
     */
    fun reset() {}
}

/**
 * Sends whichever idle transporter has the shortest journey to the pickup.
 *
 * The default, and the obvious policy: it minimises the empty running that a fleet does. Note that
 * distance here is distance *along the guide path*, not separation in space. On a one-way loop a
 * transporter standing a few feet past the pickup point may have to go all the way round, so
 * choosing by straight-line distance would routinely send the wrong one -- quietly, and with no
 * symptom other than a fleet that performs worse than it should.
 *
 * Ties go to the earlier transporter in the candidate list, which is declaration order, so the
 * choice is the same on every run.
 */
class ClosestByNetworkDistanceRule : GuidedTransporterAllocationRuleIfc {

    override fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter {
        var best = candidates.first()
        var bestDistance = Double.POSITIVE_INFINITY
        for (candidate in candidates) {
            val from = candidate.frontZone ?: continue
            val at = network.intersectionOf(from)
            val d = if (network.isReachable(at, pickup)) network.distance(at, pickup)
            else Double.POSITIVE_INFINITY
            if (d < bestDistance) {
                bestDistance = d
                best = candidate
            }
        }
        return best
    }

    override fun toString(): String = "ClosestByNetworkDistanceRule"
}

/**
 * Sends whichever idle transporter has the longest journey to the pickup.
 *
 * Rarely what a system should do, but useful as a contrast in a study: it shows how much of a
 * fleet's performance is due to dispatching rather than to the layout or the fleet size.
 */
class FurthestByNetworkDistanceRule : GuidedTransporterAllocationRuleIfc {

    override fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter {
        var best = candidates.first()
        var bestDistance = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val from = candidate.frontZone ?: continue
            val at = network.intersectionOf(from)
            if (!network.isReachable(at, pickup)) continue
            val d = network.distance(at, pickup)
            if (d > bestDistance) {
                bestDistance = d
                best = candidate
            }
        }
        return best
    }

    override fun toString(): String = "FurthestByNetworkDistanceRule"
}

/**
 * Sends whichever idle transporter has been allocated fewest times, spreading work across a fleet.
 *
 * Useful where transporters wear or need charging and the study cares about even usage rather than
 * about minimising empty running.
 */
class LeastUsedTransporterRule : GuidedTransporterAllocationRuleIfc {

    override fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter = candidates.minByOrNull { it.numTimesSeized } ?: candidates.first()

    override fun toString(): String = "LeastUsedTransporterRule"
}

/**
 * Sends an idle transporter chosen at random.
 *
 * The stream is supplied rather than taken from a global source, so that the choice is under the
 * model's control and the run stays reproducible.
 *
 * @param stream the source of randomness for the choice
 */
class RandomTransporterRule(private val stream: RNStreamIfc) : GuidedTransporterAllocationRuleIfc {

    override fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter = candidates.randomlySelect(stream)

    override fun toString(): String = "RandomTransporterRule"
}

/**
 * Sends idle transporters in rotation, beginning after whichever was chosen last.
 *
 * The cyclical unit-selection rule of the reference implementation this subsystem is validated
 * against, and it is here so that a guided-path model can be compared with one built there. It is a
 * fair rule rather than an efficient one: it spreads work over the fleet
 * without regard to where the work is, so on a one-way loop it will routinely send a transporter
 * the long way round when a nearer one is standing idle. [LeastUsedTransporterRule] balances usage
 * with the same indifference to distance but counts seizes instead of taking turns, so the two
 * agree only when every transporter is always available.
 *
 * The rotation is over **declaration order**, taken from the model element identifiers the fleet
 * was created with, so it is the order the modeller wrote and not an accident of which transporters
 * happen to be idle. Busy transporters are skipped, as they are there: the rule chooses the
 * first candidate that follows the last one chosen, wrapping round to the start of the list.
 *
 * Stateful, and therefore [reset] between replications by the pool that owns it.
 */
class CyclicalTransporterRule : GuidedTransporterAllocationRuleIfc {

    /** The identifier of the transporter chosen last, or null before the first choice. */
    private var lastChosenId: Int? = null

    override fun selectTransporter(
        network: GuidedPathNetwork,
        pickup: GuidedPathNetwork.Intersection,
        candidates: List<GuidedTransporter>
    ): GuidedTransporter {
        val last = lastChosenId
        // The first candidate declared after the last one chosen; failing that, back to the start.
        // Candidates arrive in declaration order, so this is one pass rather than a sort.
        val chosen = if (last == null) candidates.first()
        else candidates.firstOrNull { it.id.toInt() > last } ?: candidates.first()
        lastChosenId = chosen.id.toInt()
        return chosen
    }

    override fun reset() {
        lastChosenId = null
    }

    override fun toString(): String = "CyclicalTransporterRule"
}

// ---- naming ------------------------------------------------------------------------------------
//
// [RandomTransporterRule] is absent below because it takes a random number stream, which no name
// could carry; it is set by assigning the object. See the note in `IdleDispositionRules.kt`.

/** The allocation rules a name can select, in the order an interface should offer them. */
val transporterAllocationRuleNames: List<String> =
    listOf("Closest", "Furthest", "LeastUsed", "Cyclical")

/**
 * The rule a name stands for, made fresh -- which matters for [CyclicalTransporterRule], whose
 * turn-taking is state that must not be inherited from a rule the pool used before.
 *
 * @throws IllegalArgumentException if the name is not one of [transporterAllocationRuleNames]
 */
fun createTransporterAllocationRule(name: String): GuidedTransporterAllocationRuleIfc = when (name) {
    "Closest" -> ClosestByNetworkDistanceRule()
    "Furthest" -> FurthestByNetworkDistanceRule()
    "LeastUsed" -> LeastUsedTransporterRule()
    "Cyclical" -> CyclicalTransporterRule()
    else -> throw IllegalArgumentException(
        "Unknown transporter allocation rule '$name'; expected one of " +
                "$transporterAllocationRuleNames. A rule that takes an argument, such as " +
                "RandomTransporterRule, is set by assigning it."
    )
}

/** The name of a rule, or null when it is one no name stands for. */
fun nameOfTransporterAllocationRule(rule: GuidedTransporterAllocationRuleIfc): String? = when (rule) {
    is ClosestByNetworkDistanceRule -> "Closest"
    is FurthestByNetworkDistanceRule -> "Furthest"
    is LeastUsedTransporterRule -> "LeastUsed"
    is CyclicalTransporterRule -> "Cyclical"
    else -> null
}
