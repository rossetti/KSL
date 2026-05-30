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

package ksl.app.notification

/**
 *  Host-agnostic sink for transient user-facing notifications.
 *
 *  Any UI shell (Swing toasts, web push, CLI ANSI lines, headless
 *  test collector) implements this interface; substrate code that
 *  needs to surface an event-class message receives a sink reference
 *  and calls one of the methods below.  This replaces the older
 *  per-panel `onMessage: (String, NotificationSeverity) -> Unit`
 *  callback idiom with a single uniform contract.
 *
 *  ## Thread-safety contract
 *
 *  Implementations **must** be safe to call from any thread.  The
 *  intent is that substrate code (which may emit from
 *  [ksl.simulation.SimulationDispatcher.default] background workers
 *  or kotlinx.coroutines IO dispatcher threads) can call into a
 *  notification sink without coordinating its own dispatcher
 *  marshalling.  Implementations marshal to whatever thread they
 *  need internally (e.g. a Swing implementation uses
 *  `SwingUtilities.invokeLater`).
 *
 *  ## Convenience methods
 *
 *  The default [info] / [warn] / [error] methods build a
 *  [NotificationSpec] with the appropriate severity and delegate to
 *  [emit].  Callers that need a non-default dismiss duration build
 *  a `NotificationSpec` explicitly and call [emit] directly.
 */
interface NotificationSink {

    /**
     *  Emit one notification.  Must be safe to call from any thread —
     *  implementations marshal to whatever dispatcher they need.
     */
    fun emit(spec: NotificationSpec)

    /** Convenience: emit an [NotificationSeverity.INFO] notification. */
    fun info(message: String): Unit =
        emit(NotificationSpec(message, NotificationSeverity.INFO))

    /** Convenience: emit a [NotificationSeverity.WARNING] notification. */
    fun warn(message: String): Unit =
        emit(NotificationSpec(message, NotificationSeverity.WARNING))

    /** Convenience: emit a [NotificationSeverity.ERROR] notification. */
    fun error(message: String): Unit =
        emit(NotificationSpec(message, NotificationSeverity.ERROR))

    /**
     *  Accumulates emitted specs into a list for assertion in tests
     *  and headless host fixtures.  Thread-safe — the underlying list
     *  is synchronised so substrate code that emits from background
     *  dispatchers can target this sink without coordinating its own
     *  marshalling.
     *
     *  Top-level nested (not inside the companion object) so the
     *  natural access path is `NotificationSink.Collecting()` rather
     *  than `NotificationSink.Companion.Collecting()`.
     */
    class Collecting : NotificationSink {
        private val lock = Any()
        private val mySpecs: MutableList<NotificationSpec> = mutableListOf()

        override fun emit(spec: NotificationSpec) {
            synchronized(lock) { mySpecs.add(spec) }
        }

        /** Snapshot of the specs emitted so far. */
        fun specs(): List<NotificationSpec> = synchronized(lock) { mySpecs.toList() }

        /** Reset the collected history. */
        fun clear(): Unit = synchronized(lock) { mySpecs.clear() }
    }

    companion object {

        /**
         *  Discards every notification.  Useful as a default for
         *  constructors where a sink is optional (tests, headless
         *  fixtures, code paths that genuinely don't need user
         *  notification).  Always safe to share — stateless.
         */
        val NOOP: NotificationSink = object : NotificationSink {
            override fun emit(spec: NotificationSpec) { /* no-op */ }
        }
    }
}
