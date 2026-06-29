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

/**
 * The destination for [AnimationEvent]s produced during a simulation run.
 *
 * The model holds a single sink (reached as `model.animationSink`, decision D1).
 * When animation is not configured the sink is [NullAnimationSink], whose
 * [isActive] is `false`.
 *
 * Every emission site in the simulation follows the same guarded pattern so that
 * no event object is constructed when animation is off (requirement F4):
 *
 * ```kotlin
 * val sink = model.animationSink
 * if (sink.isActive) sink.emit(AnimationEvent.DelayStarted(time, id, duration, arrival))
 * ```
 *
 * Because [NullAnimationSink.isActive] is a constant `false`, the JIT can elide
 * the guarded block entirely, making disabled animation effectively free.
 *
 * The lifecycle callbacks ([onReplicationStart], [onReplicationEnd],
 * [onExperimentEnd]) let a sink manage per-run resources — for example,
 * `MemoryBufferedAnimationSink` flushes accumulated events on [onReplicationEnd],
 * and an asynchronous sink closes its writer on [onExperimentEnd] (requirement
 * F12). They default to no-ops so simple sinks need not implement them.
 *
 * Threading note: [emit] is called on the simulation thread. Implementations
 * that hand work to another thread are responsible for their own safe publication.
 */
interface AnimationSink {

    /**
     * Whether this sink is collecting events. Emission sites must check this
     * before building and emitting an event. A `false` value must be a cheap,
     * branch-predictable constant.
     */
    val isActive: Boolean

    /**
     * Records [event]. Called only when [isActive] is `true`. Must not throw on
     * the simulation thread; implementations should fail soft (e.g. drop and log)
     * rather than disrupt the run.
     */
    fun emit(event: AnimationEvent)

    /** Called at the start of replication [replicationNumber] (1-based). */
    fun onReplicationStart(replicationNumber: Int) {}

    /** Called at the end of replication [replicationNumber] (1-based). */
    fun onReplicationEnd(replicationNumber: Int) {}

    /** Called once after the final replication of the experiment. */
    fun onExperimentEnd() {}
}

/**
 * The no-op sink used whenever animation is disabled. [isActive] is always
 * `false`, [emit] does nothing, and the lifecycle callbacks are inherited no-ops.
 *
 * This is a singleton (`object`) so that the default `model.animationSink`
 * allocates nothing and every disabled model shares the same instance.
 */
object NullAnimationSink : AnimationSink {
    override val isActive: Boolean
        get() = false

    override fun emit(event: AnimationEvent) {}
}
