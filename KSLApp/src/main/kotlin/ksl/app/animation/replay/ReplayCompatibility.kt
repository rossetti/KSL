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

package ksl.app.animation.replay

import ksl.animation.AnimationLayout

/**
 * How well a chosen layout lines up with a chosen trace — the binding check at the heart of the Replay
 * pairing (9F.4). A layout binds elements by name; this reports the two ways a pairing can be imperfect:
 *  - [bindingsWithoutData]: layout elements whose name has no data in this trace, so they render but never
 *    animate (e.g. a layout authored for a fuller run, paired with a selectively-captured trace).
 *  - [animatedButUnlaid]: resources/queues present in the trace that the layout doesn't place, so their
 *    activity isn't shown.
 *
 * Neither is an error — replay tolerates both — so this is advisory ("warn and play").
 */
data class LayoutTraceCompatibility(
    val bindingsWithoutData: List<String>,
    val animatedButUnlaid: List<String>
) {
    val isFullyCovered: Boolean get() = bindingsWithoutData.isEmpty() && animatedButUnlaid.isEmpty()

    fun summary(): String {
        if (isFullyCovered) return "✓ Layout matches the trace."
        val parts = mutableListOf<String>()
        if (bindingsWithoutData.isNotEmpty()) parts += "${bindingsWithoutData.size} layout element(s) have no data in this trace (${preview(bindingsWithoutData)})"
        if (animatedButUnlaid.isNotEmpty()) parts += "${animatedButUnlaid.size} animated element(s) not placed (${preview(animatedButUnlaid)})"
        return "⚠ " + parts.joinToString("; ")
    }

    private fun preview(names: List<String>): String {
        val shown = names.take(3).joinToString(", ")
        return if (names.size > 3) "$shown, …" else shown
    }
}

/**
 * Compares a [layout]'s element bindings against the elements actually present in a [model] (a trace's
 * discovered resources/queues/responses). Responses are checked only as layout bindings (a model exposes
 * many responses, so reporting every un-placed one as "unlaid" would be noise) — the unlaid side covers
 * the structural resources and queues.
 */
fun layoutTraceCompatibility(layout: AnimationLayout, model: ReplayModel): LayoutTraceCompatibility {
    val without = mutableListOf<String>()
    layout.resources.forEach { if (it.resourceName !in model.resourceNames) without += "resource '${it.resourceName}'" }
    layout.queues.forEach { if (it.queueName !in model.queueNames) without += "queue '${it.queueName}'" }
    layout.values.forEach { if (it.responseName !in model.responseNames) without += "value '${it.responseName}'" }
    layout.movableResources.forEach { if (it.name !in model.spatialElementNames) without += "mover '${it.name}'" }

    val laidResources = layout.resources.map { it.resourceName }.toSet()
    val laidQueues = layout.queues.map { it.queueName }.toSet()
    val laidMovers = layout.movableResources.map { it.name }.toSet()
    val unlaid = mutableListOf<String>()
    model.resourceNames.forEach { if (it !in laidResources) unlaid += "resource '$it'" }
    model.queueNames.forEach { if (it !in laidQueues) unlaid += "queue '$it'" }
    // Movers present in the trace but not placed won't show their motion — call that out (UX U3).
    model.spatialElementNames.forEach { if (it !in laidMovers) unlaid += "mover '$it'" }

    return LayoutTraceCompatibility(without, unlaid)
}
