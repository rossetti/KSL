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

package ksl.app.animation.scene

/**
 * The size of the area a scene is being built for, in pixels.
 *
 * Only screen-space chrome needs this, and only the chrome that hugs an edge: a legend sits in the
 * top-right corner, which cannot be expressed without knowing where the right edge is. Everything else in
 * a scene is in world units and is independent of how large the view happens to be.
 */
data class Viewport(val widthPx: Double, val heightPx: Double)

/**
 * What a scene should include. Each flag drops a whole layer rather than changing how anything is drawn,
 * so a viewer's toggles cost nothing when off.
 *
 * The teaching overlays default to **on** here and are gated at *capture* time instead: a trace only
 * carries planned paths or marker pulses if the model opted in, so a scene built from an ordinary trace
 * simply finds nothing to draw. That keeps the "did I enable it?" decision in one place — the run — and
 * lets a viewer show whatever the trace happens to contain.
 *
 * @property showLegend draw the object-class / agent-state legend
 * @property showGrid draw the coordinate reference grid
 * @property showPlannedPaths draw agent routes carried by the trace
 * @property showMarkerPulses draw transient location highlights carried by the trace
 * @property showStationContents draw station-network QObjects at their current station
 * @property showQueueExtents draw a queue's extent line and head bar even when it is empty
 * @property showHeadings draw a short tick in a continuous agent's direction of travel
 */
data class SceneOptions(
    val showLegend: Boolean = true,
    val showGrid: Boolean = false,
    val showPlannedPaths: Boolean = true,
    val showMarkerPulses: Boolean = true,
    val showStationContents: Boolean = false,
    val showQueueExtents: Boolean = true,
    val showHeadings: Boolean = true
) {
    companion object {
        /** Everything off but the essentials — for a still image or a small embedded figure. */
        val MINIMAL = SceneOptions(
            showLegend = false,
            showGrid = false,
            showPlannedPaths = false,
            showMarkerPulses = false,
            showHeadings = false
        )
    }
}
