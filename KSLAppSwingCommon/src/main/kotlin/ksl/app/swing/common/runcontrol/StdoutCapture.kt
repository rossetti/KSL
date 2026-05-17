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

package ksl.app.swing.common.runcontrol

import java.io.PrintStream

/**
 * Process-wide manager for capturing `System.out` and `System.err`
 * line-by-line into a caller-supplied sink.
 *
 * Single-installer semantics: only one capture can be active per JVM
 * at any time.  [install] is idempotent — calling it while a capture
 * is already active first uninstalls the previous one, then installs
 * the new sink.  [uninstall] is also idempotent — calling it while
 * nothing is installed is a no-op.
 *
 * A one-time JVM shutdown hook is registered on the first install so
 * the original streams are restored if the process is killed without
 * the GUI being able to call [uninstall] (e.g. force-quit, IDE Stop
 * button, crash).  Matters for IDE Run sessions where the JVM may
 * persist across app launches.
 *
 * The sink runs on whichever thread completed the captured line.
 * Sinks targeting Swing components must dispatch onto the EDT
 * themselves.
 *
 * Thread-safe: [install] and [uninstall] are synchronized; [isInstalled]
 * reads a volatile flag.
 */
object StdoutCapture {

    @Volatile private var installed: Boolean = false
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var shutdownHookInstalled: Boolean = false

    /** Whether a capture is currently active. */
    fun isInstalled(): Boolean = installed

    /**
     * Install line-completing tees on `System.out` and `System.err`.
     * The [sink] receives each completed line plus a flag indicating
     * whether it came from stderr (`fromErr = true`) or stdout
     * (`fromErr = false`).
     *
     * If a capture is already installed when called, the previous one
     * is replaced — its sink stops receiving lines and the new sink
     * takes over from the next byte written.
     *
     * @param sink callback that receives `(text, fromErr)` for each
     *   completed line.  Invoked on the writer's thread; not
     *   guaranteed to be the EDT.
     */
    @Synchronized
    fun install(sink: (text: String, fromErr: Boolean) -> Unit) {
        if (installed) {
            uninstallInternal()
        }
        val savedOut = System.out
        val savedErr = System.err
        originalOut = savedOut
        originalErr = savedErr
        System.setOut(TeePrintStream(savedOut) { line -> sink(line, false) })
        System.setErr(TeePrintStream(savedErr) { line -> sink(line, true) })
        installed = true
        ensureShutdownHook()
    }

    /**
     * Restore the original `System.out` and `System.err`.  Safe to
     * call when no capture is installed.
     */
    @Synchronized
    fun uninstall() {
        uninstallInternal()
    }

    private fun uninstallInternal() {
        if (!installed) return
        originalOut?.let { System.setOut(it) }
        originalErr?.let { System.setErr(it) }
        originalOut = null
        originalErr = null
        installed = false
    }

    private fun ensureShutdownHook() {
        if (shutdownHookInstalled) return
        Runtime.getRuntime().addShutdownHook(Thread {
            // Best-effort restoration on JVM exit.  Synchronization not
            // needed — by the time the shutdown hook runs, application
            // threads have stopped writing through the tees.
            originalOut?.let { System.setOut(it) }
            originalErr?.let { System.setErr(it) }
        })
        shutdownHookInstalled = true
    }
}
