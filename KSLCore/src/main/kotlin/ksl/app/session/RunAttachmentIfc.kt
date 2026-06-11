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

package ksl.app.session

import kotlinx.coroutines.CoroutineScope
import ksl.simulation.Model

/**
 * Plugin slot for observers that need lifecycle-safe access to a running model.
 *
 * Implementations are supplied on a [RunRequest] and managed by [Runner]:
 *
 * 1. [onAttach] is called once, on the simulation thread, **before**
 *    `model.initializeReplications()`.  The attachment has full access to the
 *    model at this point and should register any `ModelElementObserver`
 *    instances and acquire resources (open files, channels, etc.) it needs.
 *
 * 2. If [onAttach] is called, [onDetach] is called in a `finally` block,
 *    **guaranteed to execute regardless of whether the run completed normally,
 *    was cancelled, or threw an exception**.  Implementations should release
 *    all resources and detach any observers registered in [onAttach].  A run
 *    cancelled before worker setup begins may skip both methods.
 *
 * ## Why this is needed
 *
 * [Runner] owns the model's lifecycle during a run.  Without this interface,
 * a caller who manually attaches observers before calling [Runner.submit] has
 * no safe hook to clean them up if the run fails or is cancelled.  [onDetach]
 * solves that by delegating the cleanup responsibility to [Runner]'s `finally`
 * block.
 *
 * ## Using `ModelElementObserver` inside an attachment
 *
 * The KSL model already supports attaching [ksl.observers.ModelElementObserver]
 * instances via `model.attachModelElementObserver(obs)`.  These observers fire
 * synchronously on the simulation thread at fine-grained lifecycle boundaries
 * (`beforeReplication`, `afterReplication`, `warmUp`, `afterExperiment`, etc.).
 * An attachment typically creates one or more observers in [onAttach], attaches
 * them to the model or to specific model elements, and detaches them in
 * [onDetach].
 *
 * ## Using the `scope` parameter for background work
 *
 * The `scope` passed to [onAttach] is the coroutine scope of the run itself.
 * Child coroutines launched in this scope are automatically cancelled when the
 * run ends (for any reason), preventing resource leaks.  For example, an
 * animation trace attachment that writes events asynchronously can create a
 * buffered [kotlinx.coroutines.channels.Channel] and launch a Dispatchers.IO
 * writer coroutine in `scope`:
 *
 * ```kotlin
 * override fun onAttach(model: Model, scope: CoroutineScope) {
 *     val writeChannel = Channel<AnimationEvent>(capacity = Channel.BUFFERED)
 *     scope.launch(Dispatchers.IO) {
 *         for (event in writeChannel) traceWriter.write(event)
 *     }
 *     // register observer that sends to writeChannel
 * }
 * ```
 *
 * ## Zero overhead when no attachments are wired
 *
 * [RunRequest.SingleRun.attachments] defaults to an empty list.  When the list
 * is empty, [Runner] skips the attachment loop entirely — no objects are
 * allocated and no observer callbacks fire beyond the model's own existing
 * lifecycle machinery.
 */
interface RunAttachmentIfc {

    /**
     * Called once on the simulation thread before the experiment is initialized.
     *
     * Register observers, open files, or start background coroutines here.
     * This method must not block indefinitely; it runs on the same thread that
     * will execute the simulation.
     *
     * @param model the model that is about to run; configure observers on it here
     * @param scope the coroutine scope of the run; child coroutines launched here
     *        will be cancelled automatically when the run ends
     */
    fun onAttach(model: Model, scope: CoroutineScope)

    /**
     * Called once after the worker run ends, provided [onAttach] was called.
     *
     * Release all resources acquired in [onAttach] here: close files, detach
     * model element observers, drain channels, etc.  Exceptions thrown from
     * this method are swallowed by [Runner] and logged; they do not affect the
     * terminal [RunResult].  [RunHandle.result] may resolve before cleanup
     * finishes, so attachments that need an explicit cleanup barrier should
     * expose their own completion signal.
     */
    fun onDetach()
}
