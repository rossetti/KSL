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

package ksl.animation.emitters.agent

import ksl.animation.AnimationEvent
import ksl.animation.OverlaySpec
import ksl.modeling.agent.AgentModel
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * Rate-limited capture of per-agent velocity/force vectors for the G10 overlay. Every interval (default 5
 * samples per simulated second, decoupled from the model's integration `dt`) it emits an
 * [AnimationEvent.AgentVectorSampled] for each agent of [agentModel]'s linked dynamics (optionally restricted
 * to [OverlaySpec.agentSubset]). This is the volume-sensitive overlay; sampling + subset keep it bounded, and
 * it is created only when the velocity or force overlay is enabled.
 *
 * The dynamics are looked up *at fire time* (not at construction), because models typically link them in
 * `initialize()`, after this sampler is created at attach.
 */
class DynamicsVectorSampler(
    private val agentModel: AgentModel,
    private val overlays: OverlaySpec,
    name: String? = null
) : ModelElement(agentModel, name) {

    private val interval: Double = overlays.vectorSampleInterval ?: 0.2 // 5 samples / simulated second
    private val subset: Set<String> = overlays.agentSubset.toSet()
    private val sampleAction = SampleAction()

    override fun initialize() {
        if (overlays.velocities || overlays.forces) sampleAction.schedule(interval)
    }

    private inner class SampleAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            val sink = model.animationSink
            if (sink.isActive) {
                for (ctx in agentModel.animationContexts()) {
                    for (dyn in ctx.dynamicsLinks) {
                        for (s in dyn.overlaySample(overlays.velocities, overlays.forces)) {
                            if (subset.isEmpty() || s.name in subset) {
                                sink.emit(AnimationEvent.AgentVectorSampled(time, s.name, dyn.space.name, s.vx, s.vy, s.fx, s.fy))
                            }
                        }
                    }
                }
            }
            sampleAction.schedule(interval)
        }
    }
}
