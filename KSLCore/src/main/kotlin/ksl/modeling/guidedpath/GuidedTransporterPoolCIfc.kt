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
package ksl.modeling.guidedpath

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.GuidedTransporterAllocationRuleIfc
import ksl.modeling.guidedpath.rules.IdleDispositionRuleIfc
import ksl.modeling.queue.QueueCIfc

/**
 * Controlled access to a pool of transporters: the fleet, where entities wait for it, and the two
 * rules that decide which one is sent and where it waits afterwards.
 *
 * Shaped after [ksl.modeling.entity.ResourcePoolCIfc], which likewise carries its selection and
 * allocation rules: **which rule a pool runs is part of what a modeller configures**, not an
 * internal, so it belongs in the contract rather than behind it. Each rule appears twice here, once
 * as the object and once by name, because a study that sweeps a rule wants the name and a model that
 * builds one wants the object.
 */
interface GuidedTransporterPoolCIfc {

    /** The fleet, in declaration order, which is the order ties are broken in. */
    val transporters: List<GuidedTransporterCIfc>

    /** The transporters that could be sent right now: allocated to nobody, and able to move. */
    val idleTransporters: List<GuidedTransporterCIfc>

    /** The transporters a movement gate is holding, or that are under tow. */
    val haltedTransporters: List<GuidedTransporterCIfc>

    /** Where entities wait for a transporter of this pool. */
    val waitingQ: QueueCIfc<ProcessModel.Entity.Request>

    /** How many transporters the pool could actually send. */
    val numAvailableUnits: Int

    /** True when some transporter of the pool could be sent now. */
    val hasIdleTransporter: Boolean

    /** Which idle transporter is sent to a pickup. Substitutable while the model is not running. */
    var allocationRule: GuidedTransporterAllocationRuleIfc

    /** The allocation rule by name, for a study that sweeps it as an input. */
    var allocationRuleName: String

    /** What a released transporter does when nothing is waiting for it. */
    var idleDispositionRule: IdleDispositionRuleIfc

    /** The disposition rule by name, for a study that sweeps it as an input. */
    var idleDispositionRuleName: String
}
