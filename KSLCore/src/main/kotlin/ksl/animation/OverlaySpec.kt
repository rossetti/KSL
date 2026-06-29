/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.animation

import kotlinx.serialization.Serializable

/**
 * Opt-in "agent debugging / teaching" overlays (G10–G12): they record *internal computation* that drives
 * agent behavior — velocity/force vectors, the flow-field gradient, and planned routes — rather than the
 * observable state the base animation already shows. All flags default **off**, so a normal run pays zero
 * cost. These are the *capture* gates (whether the trace contains the data); the viewer has its own *display*
 * toggles (whether it draws the data), so a captured overlay can be shown/hidden without re-running.
 *
 * Volume note: the per-step overlays (velocity/force) are the only heavy ones; [vectorSampleInterval] and
 * [agentSubset] keep them bounded. The flow field is a one-time per-replication snapshot; paths are occasional.
 */
@Serializable
data class OverlaySpec(
    /** Record per-agent velocity vectors (G10). */
    val velocities: Boolean = false,
    /** Record per-agent net steering force vectors (G10). */
    val forces: Boolean = false,
    /** Record the flow-field distance gradient as a one-time snapshot per replication (G11). */
    val flowField: Boolean = false,
    /** Record routes the model reports via reportPlannedPath (G12). */
    val plannedPaths: Boolean = false,
    /** Record transient location highlights the model reports via reportMarkerPulse (G-animated). */
    val markerPulses: Boolean = false,
    /** Sample interval (model time) for the per-step vector overlays; null ⇒ 5 samples / simulated second. */
    val vectorSampleInterval: Double? = null,
    /** If non-empty, capture vector overlays only for these agents (to slash volume while teaching). */
    val agentSubset: List<String> = emptyList()
) {
    /** Whether any overlay is enabled (a fast guard so an all-off spec wires nothing). */
    val any: Boolean get() = velocities || forces || flowField || plannedPaths || markerPulses

    companion object {
        /** The default: every overlay off — zero capture cost. */
        val OFF = OverlaySpec()
    }
}
