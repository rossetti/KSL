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

package ksl.server.tray

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ksl.server.manage.ServerProcessInventory
import java.awt.CheckboxMenuItem
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.ItemEvent
import java.net.URI
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

/**
 * The menu-bar / system-tray presentation of the KSL server. Owns the single AWT [TrayIcon] and binds it
 * to suite health via a light poll loop on the injected [scope]; the menu is deliberately minimal —
 * a disabled status line, Open Console, Start at login, and Quit. Everything detailed (start/stop,
 * configure clients, capabilities, usage) lives in the web console this opens. Lifecycle mutations go
 * through [SuiteChild] (→ the Phase-D controller); the lamp reads /health directly (cheap, read-only).
 */
class ServerTray(
    private val child: SuiteChild,
    private val loginItem: LoginItem,
    private val healthUrl: String,
    private val consoleUrl: String,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 3000,
) {

    private lateinit var trayIcon: TrayIcon
    private lateinit var statusItem: MenuItem
    private lateinit var openItem: MenuItem

    @Volatile
    private var starting = false

    /** Install the tray icon, wire the menu, start the suite child (if down), and begin the lamp loop. */
    fun install() {
        val menu = PopupMenu()
        statusItem = MenuItem("Starting…").also { it.isEnabled = false }
        openItem = MenuItem("Open Console").also { it.addActionListener { openConsole() } }
        val loginToggle = CheckboxMenuItem("Start at login", loginItem.isEnabled()).also { cb ->
            cb.addItemListener { e ->
                cb.state = loginItem.setEnabled(e.stateChange == ItemEvent.SELECTED) // reflect the real outcome
            }
        }
        menu.add(statusItem)
        menu.add(openItem)
        menu.add(loginToggle)
        menu.addSeparator()
        menu.add(MenuItem("Quit").also { it.addActionListener { quit() } })

        trayIcon = TrayIcon(TrayIcons.statusImage(TrayIcons.State.STARTING), "KSL Server", menu).also {
            it.isImageAutoSize = true
            it.addActionListener { openConsole() } // double-click the icon → console
        }
        SystemTray.getSystemTray().add(trayIcon)
        applyState(TrayIcons.State.STOPPED)

        // One loop: start the child if needed, then poll /health and paint the lamp. Off the AWT thread.
        scope.launch {
            starting = withContext(Dispatchers.IO) { runCatching { child.startIfDown() }.getOrDefault(false) }
            while (true) {
                val up = withContext(Dispatchers.IO) { ServerProcessInventory.isSuiteRunning(healthUrl) }
                val state = when {
                    up -> { starting = false; TrayIcons.State.RUNNING }
                    starting -> TrayIcons.State.STARTING
                    else -> TrayIcons.State.STOPPED
                }
                EventQueue.invokeLater { applyState(state) }
                delay(pollMillis)
            }
        }
        logger.info { "KSL Server tray installed; console at $consoleUrl" }
    }

    private fun applyState(state: TrayIcons.State) {
        trayIcon.image = TrayIcons.statusImage(state)
        statusItem.label = when (state) {
            TrayIcons.State.RUNNING -> "● Running"
            TrayIcons.State.STARTING -> "◌ Starting…"
            TrayIcons.State.STOPPED -> "○ Stopped"
        }
        openItem.isEnabled = state == TrayIcons.State.RUNNING
        trayIcon.toolTip = "KSL Server — " + when (state) {
            TrayIcons.State.RUNNING -> "running"
            TrayIcons.State.STARTING -> "starting…"
            TrayIcons.State.STOPPED -> "stopped"
        }
    }

    private fun openConsole() {
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(consoleUrl))
            } else {
                logger.info { "Open $consoleUrl in your browser." }
            }
        }.onFailure { logger.info { "Open $consoleUrl in your browser." } }
    }

    private fun quit() {
        runCatching { child.stopIfOurs() }.onFailure { logger.warn(it) { "error stopping the suite child" } }
        runCatching { SystemTray.getSystemTray().remove(trayIcon) }
        exitProcess(0)
    }
}
