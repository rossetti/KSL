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

import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.Zone

/**
 * Chooses which of the transporters waiting for a zone gets it when it comes free.
 *
 * This decision changes the course of a run: two transporters waiting at a junction will finish
 * their journeys in a different order depending on which goes first, and everything downstream of
 * that follows. It is therefore made by a named, replaceable rule over an ordered list, never by
 * whatever order a collection happened to iterate in. A model whose outcome depended on iteration
 * order would give different answers on different platforms, which would cost it the reproducibility
 * everything else here is built to preserve.
 *
 * A rule must choose from the transporters it is given, and must be a pure function of them. One
 * that needs randomness must draw it from a stream the model controls.
 *
 * **Choosing is not optional.** A waiting transporter has nothing scheduled, so it moves again only
 * when something hands it the zone. Declining to choose would therefore not leave the zone free and
 * everyone waiting until next time -- there is no next time, because nothing holds the zone and so
 * nothing will ever release it again. Everyone waiting would wait for the rest of the replication,
 * and the run would simply stop advancing with nothing to say why. The return type says so: a rule
 * is asked only when there is at least one transporter to choose from, and it must name one.
 */
fun interface ZoneContentionRuleIfc {

    /**
     * @param zone the zone or link that has just come free
     * @param waiting the transporters waiting for it, in the order they began waiting. Never empty
     * @return the one to wake, which must be one of [waiting]
     */
    fun selectWaiter(zone: Zone, waiting: List<GuidedTransporter>): GuidedTransporter
}

/**
 * Gives the zone to whoever has been waiting longest.
 *
 * The default. It is the discipline a queue is normally expected to follow, it cannot starve
 * anyone, and it is easy to explain when a modeler asks why one transporter went before another.
 */
class FIFOZoneContentionRule : ZoneContentionRuleIfc {
    override fun selectWaiter(zone: Zone, waiting: List<GuidedTransporter>): GuidedTransporter =
        waiting.first()

    override fun toString(): String = "FIFOZoneContentionRule"
}

/**
 * Gives the zone to a transporter carrying something, in preference to an empty one, and otherwise
 * to whoever has waited longest.
 *
 * A loaded transporter is holding up a job, while an empty one is only repositioning, so letting
 * the loaded one through first tends to shorten the time entities spend in the system. It can
 * starve an empty transporter on a busy path, which is a real risk rather than a theoretical one
 * and is why it is not the default.
 */
class LoadedFirstZoneContentionRule : ZoneContentionRuleIfc {
    override fun selectWaiter(zone: Zone, waiting: List<GuidedTransporter>): GuidedTransporter =
        waiting.firstOrNull { it.stateBeforeBlocking == TransporterState.MOVING_LOADED }
            ?: waiting.first()

    override fun toString(): String = "LoadedFirstZoneContentionRule"
}

// ---- naming ------------------------------------------------------------------------------------
//
// Both shipped rules are fully defined by their family, so both are nameable. See the note in
// `IdleDispositionRules.kt` for why that is the test.

/** The contention rules a name can select, in the order an interface should offer them. */
val zoneContentionRuleNames: List<String> = listOf("FIFO", "LoadedFirst")

/**
 * The rule a name stands for, made fresh.
 *
 * @throws IllegalArgumentException if the name is not one of [zoneContentionRuleNames]
 */
fun createZoneContentionRule(name: String): ZoneContentionRuleIfc = when (name) {
    "FIFO" -> FIFOZoneContentionRule()
    "LoadedFirst" -> LoadedFirstZoneContentionRule()
    else -> throw IllegalArgumentException(
        "Unknown zone contention rule '$name'; expected one of $zoneContentionRuleNames."
    )
}

/** The name of a rule, or null when it is one no name stands for. */
fun nameOfZoneContentionRule(rule: ZoneContentionRuleIfc): String? = when (rule) {
    is FIFOZoneContentionRule -> "FIFO"
    is LoadedFirstZoneContentionRule -> "LoadedFirst"
    else -> null
}
