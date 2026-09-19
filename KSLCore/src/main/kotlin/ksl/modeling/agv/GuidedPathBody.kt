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
package ksl.modeling.agv

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.Resource
import ksl.modeling.fleet.VehicleBodyIfc
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.spatial.VehicleMovementIfc
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponseCIfc

/**
 * A guided transporter, seen as a fleet vehicle's body.
 *
 * An adapter rather than a supertype on [GuidedTransporter] itself, for one reason worth stating:
 * `board`, `alight` and the blocked clock are `internal` there, and an interface member is public.
 * Making them public to satisfy an interface would put the manifest in a modeller's hands, which is
 * the one thing the manifest exists to prevent.
 */
internal class GuidedPathBody(
    val transporter: GuidedTransporter,
    private val drivingQueue: HoldQueue
) : VehicleBodyIfc, VehicleMovementIfc by transporter {

    override val loadCapacity: Int get() = transporter.loadCapacity
    override val manifest: List<ProcessModel.Entity> get() = transporter.manifest
    override val numLoadsAboard: Int get() = transporter.numLoadsAboard
    override val spareCapacity: Int get() = transporter.spareCapacity
    override val isAtCapacity: Boolean get() = transporter.isAtCapacity
    override val isCarryingLoad: Boolean get() = transporter.isCarryingLoad

    override fun board(load: ProcessModel.Entity) = transporter.board(load)
    override fun alight(load: ProcessModel.Entity) = transporter.alight(load)

    override val fracTimeMoving: TWResponseCIfc get() = transporter.fracTimeMoving
    override val fracTimeTransporting: TWResponseCIfc get() = transporter.fracTimeTransporting
    override val fracTimeMovingEmpty: TWResponseCIfc get() = transporter.fracTimeMovingEmpty
    override val fracTimeBlocked: TWResponseCIfc get() = transporter.fracTimeBlocked
    override val numTimesBlocked: CounterCIfc get() = transporter.numTimesBlocked
    override val cumulativeBlockedTime: Double get() = transporter.cumulativeBlockedTime
    override val numLoadsAboardResponse: TWResponseCIfc? get() = transporter.numLoadsAboardResponse
    override val capacityUtilization: TWResponseCIfc? get() = transporter.capacityUtilization
    override val fracTimeAtCapacity: TWResponseCIfc? get() = transporter.fracTimeAtCapacity
    override val loadsPerLoadedMove: ksl.modeling.variable.ResponseCIfc?
        get() = transporter.loadsPerLoadedMove
    override val zonesEntered: Int get() = transporter.zonesEntered

    override val currentVelocity: Double get() = transporter.currentVelocity

    override val seizable: ksl.modeling.entity.Resource get() = transporter

    override var homeBase: String?
        get() = transporter.homeBase
        set(value) {
            transporter.homeBase = value
        }

    override val movementQueue: HoldQueue
        get() = drivingQueue

    override fun attachContinuationGate(gate: (() -> Boolean)?) {
        transporter.attachMovementGate(if (gate == null) null else { _, _ -> gate() })
    }

    override val isUnderTow: Boolean get() = transporter.isUnderTow

    override var towVelocity: Double?
        get() = transporter.towVelocity
        set(value) {
            transporter.towVelocity = value
        }

    override fun endTow() {
        transporter.towVelocity = null
        if (transporter.transporterState == ksl.modeling.guidedpath.TransporterState.TOWED) {
            transporter.transporterState = ksl.modeling.guidedpath.TransporterState.IDLE
        }
    }

    override fun toString(): String = "GuidedPathBody(${transporter.name})"
}
