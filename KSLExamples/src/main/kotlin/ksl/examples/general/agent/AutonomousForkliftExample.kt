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

package ksl.examples.general.agent

import ksl.modeling.agent.AgentModel
import ksl.modeling.agent.AgentResource
import ksl.modeling.agent.positive
import ksl.modeling.agent.probability
import ksl.modeling.entity.KSLProcess
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  An "agentic resource" worked example. A single forklift services a
 *  stream of pallet-move tasks. It is a real `AgentResource` — entities
 *  seize and release it like any other resource — but it also runs an
 *  autonomous statechart that watches its own battery level. When the
 *  battery drops below a threshold the forklift takes itself off-shift;
 *  when fully charged it returns on-shift and resumes service.
 *
 *  This is the canonical "resources-as-agents" use case from the design
 *  doc: the resource is the *primary identity* of the actor (it is what
 *  you `seize`), but it also has internal state and reactive behavior
 *  that affects when it is available.
 *
 *  Phase 2 building blocks exercised:
 *   - `AgentResource` as a fully usable Resource (seize / release)
 *   - Statechart on a non-Agent owner via the `AgentLike` interface
 *   - `goOffShift` / `goOnShift` driven by a statechart `onCondition`
 *   - Per-allocation hook (`deallocate` override) used to update the
 *     forklift's battery as work is consumed
 */
class AutonomousForkliftExample(parent: ModelElement, name: String? = null) :
    AgentModel(parent, name) {

    /**
     *  An AgentResource subclass that tracks battery level. The
     *  battery is depleted with each completed allocation and recharged
     *  while off-shift.
     */
    inner class Forklift(aName: String) : AgentResource(this@AutonomousForkliftExample, aName, capacity = 1) {

        /** Fraction of full charge remaining, in [0.0, 1.0]. */
        var battery: Double = 1.0
            private set

        /** How much energy each completed move consumes. Must be in [0, 1]. */
        var energyPerMove: Double by probability(AutonomousForkliftExample.Defaults.energyPerMove)

        /** Charge rate per simulated time unit while off-shift. Must be positive. */
        var chargeRatePerTime: Double by positive(AutonomousForkliftExample.Defaults.chargeRatePerTime)

        init {
            statechart {
                initial("available")
                state("available") {
                    onCondition({ battery < AutonomousForkliftExample.Defaults.lowBatteryThreshold }) {
                        battery = battery  // touch (no-op; logging hook if needed)
                        (agent as Forklift).goOffShift()
                        transitionTo("charging")
                    }
                }
                state("charging") {
                    onEntry { chargingStartedAt = agent.currentTime }
                    onCondition({ battery >= AutonomousForkliftExample.Defaults.fullChargeThreshold }) {
                        (agent as Forklift).goOnShift()
                        transitionTo("available")
                    }
                }
            }
        }

        private var chargingStartedAt: Double = Double.NaN

        /**
         *  Hook into the resource's deallocate path. Each time a move
         *  finishes (entity calls release), drop the battery by one
         *  unit of energy.
         */
        override fun deallocate(allocation: ksl.modeling.entity.Allocation) {
            super.deallocate(allocation)
            battery = (battery - energyPerMove).coerceAtLeast(0.0)
        }

        /**
         *  Called by the model on every battery-update tick while the
         *  forklift is charging.
         */
        fun chargeTick(dt: Double) {
            if (isOffShift) {
                battery = (battery + chargeRatePerTime * dt).coerceAtMost(1.0)
            }
        }
    }

    // --- Modeling responses --------------------------------------------------

    private val moveTimeRV =
        ExponentialRV(Defaults.moveTimeMean, streamNum = 1, streamProvider = streamProvider)
    private val arrivalRV =
        ExponentialRV(Defaults.arrivalMean, streamNum = 2, streamProvider = streamProvider)
    val tisResponse: Response = Response(this, "MoveCompletionTime")

    val forklift: Forklift = Forklift("forklift-1")

    /** Mutable global defaults for [AutonomousForkliftExample]. */
    companion object Defaults {
        /** Energy consumed per completed pallet move. Must be in [0, 1]. */
        var energyPerMove: Double by probability(0.15)
        /** Charge rate per simulated time unit while off-shift. Must be positive. */
        var chargeRatePerTime: Double by positive(0.05)
        /** Battery threshold below which the forklift takes itself off-shift. Must be in [0, 1]. */
        var lowBatteryThreshold: Double by probability(0.20)
        /** Battery level at which the forklift comes back on-shift. Must be in [0, 1]. */
        var fullChargeThreshold: Double by probability(0.95)
        /** Period of the recurring battery-update tick event. Must be positive. */
        var chargeTickInterval: Double by positive(0.5)
        /** Mean move-time, simulated time units. Must be positive. */
        var moveTimeMean: Double by positive(2.0)
        /** Mean inter-arrival time between move requests. Must be positive. */
        var arrivalMean: Double by positive(2.5)
    }

    /**
     *  Transient move requests. Modeled as plain `Entity` rather than
     *  `Agent` because they are created at runtime by the
     *  [EntityGenerator] below, and an `Agent` would try to construct
     *  its `AgentMailbox` (a `ModelElement`) mid-replication — KSL
     *  forbids that. Movers do not need a mailbox; they just seize the
     *  forklift and leave.
     */
    private inner class Mover(aName: String? = null) : Entity(aName) {
        val script: KSLProcess = process(isDefaultProcess = true) {
            val a = seize(forklift)
            val moveTime = moveTimeRV.value
            delay(moveTime)
            release(a)
            tisResponse.value = currentTime - this@Mover.createTime
        }
    }

    private val moverGenerator = EntityGenerator(
        ::Mover,
        timeUntilTheFirstEntity = arrivalRV,
        timeBtwEvents = arrivalRV,
    )

    /**
     *  Periodic battery-update event. While the forklift is charging
     *  the tick adds [Forklift.chargeRatePerTime] per simulated unit.
     *  The mover entity generator activates itself automatically.
     */
    override fun initialize() {
        super.initialize()
        scheduleChargeTick()
    }

    private val chargeTickInterval = Defaults.chargeTickInterval

    private fun scheduleChargeTick() {
        // Self-rescheduling event implemented inline; the agent layer
        // does not yet provide a "recurring timer" helper.
        schedule(chargeTickAction, chargeTickInterval)
    }

    private val chargeTickAction = object : EventAction<Nothing>() {
        override fun action(event: ksl.simulation.KSLEvent<Nothing>) {
            forklift.chargeTick(chargeTickInterval)
            schedule(chargeTickInterval)
        }
    }
}

fun main() {
    val model = Model("AutonomousForkliftExample")
    val sys = AutonomousForkliftExample(model, "forklift-demo")
    model.lengthOfReplication = 500.0
    model.numberOfReplications = 1
    model.simulate()
    model.print()
    println("Final battery: ${"%.2f".format(sys.forklift.battery)}")
    println("Forklift off-shift at end: ${sys.forklift.isOffShift}")
    println("Final statechart state: ${sys.forklift.statechart?.currentStateName}")
}
