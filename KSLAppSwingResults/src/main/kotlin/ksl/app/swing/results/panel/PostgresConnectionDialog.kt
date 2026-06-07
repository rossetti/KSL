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

package ksl.app.swing.results.panel

import ksl.app.notification.NotificationSink
import ksl.app.swing.results.PostgresConnectionSpec
import ksl.app.swing.results.ResultsAppController
import java.awt.BorderLayout
import java.awt.GridLayout
import java.awt.Window
import java.util.concurrent.ExecutionException
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.SwingWorker

/**
 *  Modal dialog for connecting to a server-based Postgres KSL database.
 *
 *  The connect runs on a [SwingWorker] so an unreachable host cannot
 *  freeze the UI — while it runs, the *Connect* button is disabled and a
 *  "Connecting…" notice shows; success closes the dialog and reports
 *  through [notifier]; failure shows the error in the dialog so the user
 *  can correct the details and retry.
 *
 *  Non-secret fields (server, port, database, user) are remembered for
 *  the rest of the session so reconnecting is quick; the **password is
 *  never stored**.
 */
class PostgresConnectionDialog(
    owner: Window?,
    private val controller: ResultsAppController,
    private val notifier: NotificationSink
) : JDialog(owner, "Connect to Postgres KSL Database", ModalityType.APPLICATION_MODAL) {

    private val serverField = JTextField(lastServer, 18)
    private val portField = JTextField(lastPort, 6)
    private val databaseField = JTextField(lastDatabase, 18)
    private val userField = JTextField(lastUser, 14)
    private val passwordField = JPasswordField(14)
    private val statusLabel = JLabel(" ")
    private val connectButton = JButton("Connect")
    private val cancelButton = JButton("Cancel")

    init {
        layout = BorderLayout()
        add(buildForm(), BorderLayout.CENTER)
        add(buildButtons(), BorderLayout.SOUTH)

        connectButton.addActionListener { connect() }
        cancelButton.addActionListener { dispose() }

        pack()
        setLocationRelativeTo(owner)
    }

    private fun buildForm(): JPanel = JPanel(GridLayout(5, 2, 6, 4)).apply {
        border = BorderFactory.createEmptyBorder(10, 12, 6, 12)
        add(JLabel("Server:")); add(serverField)
        add(JLabel("Port:")); add(portField)
        add(JLabel("Database:")); add(databaseField)
        add(JLabel("User:")); add(userField)
        add(JLabel("Password:")); add(passwordField)
    }

    private fun buildButtons(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(0, 12, 10, 12)
        add(statusLabel, BorderLayout.WEST)
        add(JPanel().apply { add(connectButton); add(cancelButton) }, BorderLayout.EAST)
    }

    private fun connect() {
        val port = portField.text.trim().toIntOrNull()
        if (port == null || port <= 0) {
            showError("Port must be a positive number.")
            return
        }
        if (databaseField.text.isBlank()) {
            showError("Enter a database name.")
            return
        }
        val spec = PostgresConnectionSpec(
            server = serverField.text.trim().ifBlank { "localhost" },
            port = port,
            databaseName = databaseField.text.trim(),
            user = userField.text.trim(),
            password = String(passwordField.password)
        )
        setBusy(true)
        object : SwingWorker<Unit, Void>() {
            override fun doInBackground() {
                controller.connectPostgres(spec)
            }

            override fun done() {
                try {
                    get()   // rethrows any exception raised on the worker thread
                    rememberNonSecret(spec)
                    notifier.info("Connected to ${controller.databaseSummary()}")
                    dispose()
                } catch (t: Throwable) {
                    val cause = (t as? ExecutionException)?.cause ?: t
                    showError("Could not connect:\n${cause.message ?: cause::class.simpleName}")
                    setBusy(false)
                }
            }
        }.execute()
    }

    private fun setBusy(busy: Boolean) {
        connectButton.isEnabled = !busy
        statusLabel.text = if (busy) "Connecting…" else " "
    }

    private fun showError(message: String) {
        JOptionPane.showMessageDialog(this, message, "Connect", JOptionPane.ERROR_MESSAGE)
    }

    private fun rememberNonSecret(spec: PostgresConnectionSpec) {
        lastServer = spec.server
        lastPort = spec.port.toString()
        lastDatabase = spec.databaseName
        lastUser = spec.user
    }

    private companion object {
        // Session-remembered, non-secret connection fields.  The password
        // is intentionally never retained.
        var lastServer = "localhost"
        var lastPort = "5432"
        var lastDatabase = ""
        var lastUser = "postgres"
    }
}
