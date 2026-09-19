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

import ksl.controls.KSLStringControl
import ksl.modeling.guidedpath.rules.createIdleDispositionRule
import ksl.modeling.guidedpath.rules.createTransporterAllocationRule
import ksl.modeling.guidedpath.rules.nameOfIdleDispositionRule
import ksl.modeling.guidedpath.rules.nameOfTransporterAllocationRule
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.AbstractResourcePool
import ksl.modeling.entity.Allocation
import ksl.modeling.entity.RequestQ
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.GuidedTransporterAllocationRuleIfc
import ksl.modeling.guidedpath.rules.IdleDisposition
import ksl.modeling.guidedpath.rules.IdleDispositionRuleIfc
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.entity.ResumeSource
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * A set of interchangeable transporters that entities ask for by the group rather than by name.
 *
 * An entity that wants carrying does not care which transporter comes, only that one does. The pool
 * holds the fleet, decides which idle transporter to send, and holds the entities that arrive when
 * none is free.
 *
 * Waiting happens in one place, on the pool, rather than on the individual transporters. That is
 * deliberate: an entity queued against a particular transporter would go on waiting for that one
 * even after a nearer one came free, which is both slower and harder to explain. Whoever has waited
 * longest is woken when any transporter is released, and chooses afresh.
 *
 * @param parent the containing model element
 * @param system the runtime whose guide path these transporters run on
 * @param transporters the fleet, which must all belong to that system
 * @param allocationRule which idle transporter to send
 * @param idleDispositionRule what a released transporter does when nothing is waiting. Settable,
 * because where a fleet idles is a design question a study wants to vary rather than a structural
 * property of the pool. It is read at the moment a transporter is released, so a change takes
 * effect from the next release; the shipped rules carry no state, so none needs resetting between
 * replications.
 * @param name a name for the pool
 */
open class GuidedTransporterPoolWithQ @JvmOverloads constructor(
    parent: ModelElement,
    val system: GuidedPathTransportSystem,
    transporters: List<GuidedTransporter>,
    allocationRule: GuidedTransporterAllocationRuleIfc = ClosestByNetworkDistanceRule(),
    override var idleDispositionRule: IdleDispositionRuleIfc = ParkInPlaceRule(),
    name: String? = null
) : AbstractResourcePool<GuidedTransporter>(parent, name), GuidedTransporterPoolCIfc {

    init {
        require(transporters.isNotEmpty()) { "A transporter pool must contain at least one transporter." }
        for (t in transporters) {
            require(t.system === system) {
                "Transporter (${t.name}) belongs to system (${t.system.name}), not to " +
                        "(${system.name}), so it cannot be pooled here."
            }
            addResource(t)
            t.joinPool(this)
        }
        // So the space can count entities waiting here when the horizon falls: a journey that never
        // started is invisible from the space's own hold queues.
        system.registerPool(this)
    }

    /**
     * Which idle transporter is sent to a pickup.
     *
     * Substitutable while the model is not running. Unlike the disposition rule, an allocation rule
     * may carry state -- a rule that takes turns remembers whose turn it was -- so a swap
     * mid-replication would let one run be dispatched two ways. [initialize] resets whichever rule
     * is in force at the start of every replication, so a rule never inherits the end of the one
     * before it.
     */
    override var allocationRule: GuidedTransporterAllocationRuleIfc = allocationRule
        set(value) {
            require(model.isNotRunning) {
                "The allocation rule of pool ($name) cannot be changed while the model is running."
            }
            field = value
        }

    /**
     * The allocation rule by name, so a scenario or an app can change it without holding a rule
     * object. Reading it reports the rule's own `toString` when the rule is one no name stands for
     * -- a [ksl.modeling.guidedpath.rules.RandomTransporterRule], say, which takes a stream.
     */
    @set:KSLStringControl(
        allowedValues = ["Closest", "Furthest", "LeastUsed", "Cyclical"],
        comment = "Which idle transporter of the pool is sent to a pickup"
    )
    override var allocationRuleName: String
        get() = nameOfTransporterAllocationRule(allocationRule) ?: allocationRule.toString()
        set(value) {
            allocationRule = createTransporterAllocationRule(value)
        }

    /**
     * Where an idle transporter waits, by name, so a scenario or an app can change it without
     * holding a rule object. Reading it reports the rule's own `toString` when the rule is one no
     * name stands for -- a [ksl.modeling.guidedpath.rules.MoveToStagingAreaRule], say, which takes
     * a location.
     */
    @set:KSLStringControl(
        allowedValues = ["ParkInPlace", "ReturnToHomeBase"],
        comment = "What a released transporter does when nothing is waiting for it"
    )
    override var idleDispositionRuleName: String
        get() = nameOfIdleDispositionRule(idleDispositionRule) ?: idleDispositionRule.toString()
        set(value) {
            idleDispositionRule = createIdleDispositionRule(value)
        }

    /** The fleet, in declaration order, which is the order ties are broken in. */
    override val transporters: List<GuidedTransporter>
        get() = myResources

    /**
     * Where entities wait for a transporter of this pool.
     *
     * A [RequestQ], and the same one the allocation is made through, because that is what makes
     * this pool behave like every other resource pool in the library. A request is enqueued **on
     * every call**, whether or not a transporter is free, and removed when one is allocated: so an
     * entity served immediately records a wait of zero rather than no observation at all, which is
     * what `seize` has always done and what the reported mean has to be over to mean anything.
     *
     * It is also what stops the queue being jumped. A released transporter marks the next eligible
     * request `resumePending`, which reserves the pool's availability for it; an entity arriving in
     * the same instant enqueues behind it and finds itself not next, rather than taking the
     * transporter out from under it.
     */
    internal val myWaitingQ: RequestQ = RequestQ(this, "${this.name}:Q")

    /** Entities waiting for any transporter of this pool to become free. */
    override val waitingQ: QueueCIfc<ProcessModel.Entity.Request>
        get() = myWaitingQ

    /**
     * The transporters this pool could send right now: allocated to nobody, and able to move.
     *
     * Both halves are needed. A transporter halted by its movement gate part way through a
     * repositioning move, or one under tow, belongs to nobody and is going nowhere; offering it is
     * offering a vehicle that will not come. Worse, the default rule ranks candidates by distance to
     * the pickup, and a stopped candidate is the one whose distance does not grow while the others'
     * do -- so a blind rule does not merely include the stuck vehicle, it drifts toward it.
     *
     * Use [transporters] for the whole fleet and [haltedTransporters] for the ones a gate is
     * holding.
     */
    override val idleTransporters: List<GuidedTransporter>
        get() = myResources.filter { it.isDispatchable }

    /** True when some transporter of the pool could be sent now. */
    override val hasIdleTransporter: Boolean
        get() = myResources.any { it.isDispatchable }

    /**
     * The transporters of this pool that a movement gate is currently holding, or that are under
     * tow. They are not offered to the allocation rule, and they are here so that a model which
     * installs a gate can report on what its gate is doing.
     */
    override val haltedTransporters: List<GuidedTransporter>
        get() = myResources.filter { it.numBusy == 0 && !it.isDispatchable }

    /**
     * How many transporters this pool has to give, which is how many it could actually send.
     *
     * Overridden, because this is the number the library's own wake path reads when an entity gives
     * a transporter back: a pool that reported a halted vehicle as available would wake a waiting
     * entity to be told there is nothing for it. Every transporter has a capacity of one, so the
     * count of dispatchable transporters is the number of units.
     */
    override val numAvailableUnits: Int
        get() = idleTransporters.size

    /**
     * Clears any state the allocation rule carries, so a replication cannot inherit the end of the
     * one before it. Most rules do nothing here; a rule that takes turns does.
     */
    override fun initialize() {
        super.initialize()
        allocationRule.reset()
    }

    /**
     * Chooses an idle transporter to send to a pickup, or null when none is free.
     *
     * @param pickup where the transporter is wanted
     */
    internal fun selectFor(pickup: GuidedPathNetwork.Intersection): GuidedTransporter? {
        val candidates = idleTransporters
        // Only dispatchable transporters reach a rule, so no rule -- the library's or a modeller's
        // -- has to know that halting exists in order to be correct.
        if (candidates.isEmpty()) return null
        val chosen = allocationRule.selectTransporter(system.network, pickup, candidates)
        check(chosen in candidates) {
            "Allocation rule ($allocationRule) chose transporter (${chosen.name}), which is not " +
                    "among the idle transporters of pool (${this.name}). A rule must choose from " +
                    "the candidates it is given."
        }
        return chosen
    }

    /**
     * Allocates a transporter to an entity, choosing which one only now.
     *
     * The choice is deliberately made here rather than when the entity queued. An entity that has
     * been waiting should get the transporter that is best for it *at the moment one is free*, not
     * the one that happened to be nearest when it joined the queue -- and by then the fleet has
     * moved. This is the same division of labour that [ksl.modeling.spatial.MovableResourcePool]
     * uses, for the same reason.
     *
     * @param entity who the transporter is for
     * @param pickup where it is wanted, which is what the rule ranks by
     * @param allocationName names the allocation
     */
    internal fun allocateFor(
        entity: ProcessModel.Entity,
        pickup: GuidedPathNetwork.Intersection,
        allocationName: String? = null
    ): Allocation {
        require(hasIdleTransporter) { "Pool ($name) has no idle transporter to allocate." }
        val chosen = selectFor(pickup)!!
        val allocation = chosen.allocate(entity, 1, myWaitingQ, allocationName)
        // The requests waiting in this queue are the pool's, not the member's: a request names the
        // pool, because no transporter has been chosen when it queues. Recording the pool here is
        // what lets the release process the queue on the pool's behalf.
        allocation.originatingPool = this
        return allocation
    }

    /**
     * What a transporter does once it has been given back and nobody is waiting for it.
     *
     * Called after the release, which has already offered the transporter to whoever was waiting.
     * Work beats disposition: a fleet that sent a transporter home while an entity was queued for
     * it would be paying for the journey twice, so a non-empty queue means there is nothing to
     * decide here.
     */
    internal fun disposeIfUnwanted(transporter: GuidedTransporter) {
        if (myWaitingQ.isNotEmpty) return
        // A gate that refused this transporter passage is not overruled by a rule about parking.
        // Whatever halted it decides when it moves, and a disposition issued now would be a second
        // journey competing with that decision.
        if (!transporter.isDispatchable) return
        when (val disposition = idleDispositionRule.disposition(transporter)) {
            is IdleDisposition.ParkInPlace -> Unit

            is IdleDisposition.ReturnToHomeBase -> {
                val home = transporter.homeBase
                if (home != null) transporter.sendTo(home)
            }

            is IdleDisposition.MoveTo -> transporter.sendTo(disposition.locationName)
        }
    }

    /**
     * Offers the pool again after one of its transporters became able to move.
     *
     * Called by the transporter itself when a halt is released or a tow ends while nobody holds it.
     * That instant raises this pool's available units without any release having happened, and the
     * library's wake path runs only on a release -- so if this did not exist, an entity could wait
     * out the replication in front of a transporter that was standing free.
     */
    internal fun transporterBecameDispatchable() {
        if (myWaitingQ.isEmpty) return
        myWaitingQ.processWaitingRequestsForResource(
            this,
            KSLEvent.DEFAULT_PRIORITY,
            ResumeSource.REQUEST_Q_CAPACITY_INCREASE,
            "guided_transporter_pool=$name became dispatchable"
        )
    }

    override fun toString(): String =
        "GuidedTransporterPoolWithQ($name, ${myResources.size} transporters, " +
                "${idleTransporters.size} dispatchable, ${haltedTransporters.size} halted, " +
                "${myWaitingQ.size} waiting)"
}
