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

package ksl.animation.emitters

import ksl.animation.AnimationEvent
import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueListenerIfc
import ksl.simulation.ModelElement

/**
 * A [QueueListenerIfc] that emits an [AnimationEvent.QueueLengthChanged] whenever a
 * queue's length changes. Registering one on a queue
 * (`queue.addQueueListener(QueueAnimationEmitter())`) is the non-intrusive way to
 * animate that queue — the queue itself is unchanged.
 *
 * The emitter reads everything it needs from the queue passed to [update]: the
 * current [Queue.size] (the queue notifies its listeners *after* applying the
 * enqueue/dequeue/clear, so the size is already up to date), the queue's name, the
 * current simulated time, and the model's animation sink. It therefore holds no
 * reference to the model and can be attached to any queue.
 *
 * It de-duplicates by remembering the last length emitted, so notifications that do
 * not actually change the length (e.g. an "ignored" pass-through) produce no event.
 *
 * @param T the queued object type
 */
class QueueAnimationEmitter<T : ModelElement.QObject> : QueueListenerIfc<T> {

    private var lastLength: Int = -1

    override fun update(status: Queue.Status, queue: Queue<T>, qObject: T?) {
        val sink = queue.model.animationSink
        if (!sink.isActive) return
        // Per-member events so a renderer can show the identified, typed members (8C.2).
        if (qObject != null) {
            when (status) {
                Queue.Status.ENQUEUED -> sink.emit(AnimationEvent.QObjectEnqueued(queue.time, qObject.id, queue.name))
                Queue.Status.DEQUEUED -> sink.emit(AnimationEvent.QObjectDequeued(queue.time, qObject.id, queue.name))
                Queue.Status.IGNORE -> {}
            }
        }
        // Length change (de-duplicated).
        val length = queue.size
        if (length != lastLength) {
            lastLength = length
            sink.emit(AnimationEvent.QueueLengthChanged(queue.time, queue.name, length))
        }
    }
}
