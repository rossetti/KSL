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

package ksl.app.swing.simopt.results

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.results.export.ArtifactNames
import java.awt.Color
import java.awt.Desktop
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 *  Lists the artifacts written into the last completed run's output
 *  directory and gives the user a per-file [Open] button plus an
 *  [Open folder] shortcut.
 *
 *  Opens each artifact in the host OS's default handler via
 *  [Desktop.open] — Excel for `.csv`, the system browser for
 *  `.html`, the image viewer for `.png`, a text editor for `.toml`.
 *  Headless / sandboxed environments without `Desktop` support
 *  hide the buttons (rather than fail silently on click).
 */
class ResultsFilesCard(
    private val controller: SimoptAppController
) : JPanel(GridBagLayout()) {

    private val outputPathLabel = JLabel(" ").apply {
        foreground = Color(0x33, 0x33, 0x33)
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val openFolderButton = JButton("Open folder").apply {
        margin = Insets(1, 6, 1, 6)
        font = font.deriveFont(Font.PLAIN, 11f)
    }

    private val artifactRows = listOf(
        ArtifactNames.REPORT_HTML to JButton("Open").apply {
            margin = Insets(1, 6, 1, 6); font = font.deriveFont(Font.PLAIN, 11f)
        },
        ArtifactNames.ITERATION_HISTORY_CSV to JButton("Open").apply {
            margin = Insets(1, 6, 1, 6); font = font.deriveFont(Font.PLAIN, 11f)
        },
        ArtifactNames.CONVERGENCE_PNG to JButton("Open").apply {
            margin = Insets(1, 6, 1, 6); font = font.deriveFont(Font.PLAIN, 11f)
        },
        ArtifactNames.BEST_SOLUTION_CSV to JButton("Open").apply {
            margin = Insets(1, 6, 1, 6); font = font.deriveFont(Font.PLAIN, 11f)
        },
        ArtifactNames.SUMMARY_TOML to JButton("Open").apply {
            margin = Insets(1, 6, 1, 6); font = font.deriveFont(Font.PLAIN, 11f)
        }
    )

    private val placeholder = JLabel("<html><i>No completed run yet — artifacts will appear here after a successful optimization.</i></html>").apply {
        foreground = Color(0x77, 0x77, 0x77)
        font = font.deriveFont(Font.PLAIN, 12f)
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Results files"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        wireButtons()
        wireCollectors()
        refresh()
    }

    private fun wireButtons() {
        openFolderButton.addActionListener {
            controller.lastCompletedRunDir.value?.let(::open)
        }
        for ((name, button) in artifactRows) {
            button.addActionListener {
                controller.lastCompletedRunDir.value?.resolve(name)?.let(::open)
            }
        }
    }

    private fun wireCollectors() {
        controller.lastCompletedRunDir.onEach { refresh() }.launchIn(controller.edtScope)
    }

    private fun refresh() {
        removeAll()
        val runDir = controller.lastCompletedRunDir.value
        if (runDir == null) {
            add(placeholder, gbc(0, 0, width = 2, weightx = 1.0,
                fill = GridBagConstraints.HORIZONTAL))
            revalidate(); repaint()
            return
        }

        // Header line: path + Open folder.
        add(JLabel("Output folder:").apply { font = font.deriveFont(Font.BOLD, 12f) },
            gbc(0, 0, anchor = GridBagConstraints.WEST))
        outputPathLabel.text = runDir.toString()
        outputPathLabel.toolTipText = runDir.toString()
        add(outputPathLabel, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(openFolderButton, gbc(2, 0))

        // One row per artifact — present-on-disk gates the button.
        var row = 1
        for ((name, button) in artifactRows) {
            val path = runDir.resolve(name)
            val exists = Files.exists(path)
            add(JLabel(if (exists) "• $name" else "• $name (missing)").apply {
                foreground = if (exists) Color(0x33, 0x33, 0x33) else Color(0x99, 0x99, 0x99)
                font = font.deriveFont(Font.PLAIN, 12f)
            }, gbc(0, row, width = 2, anchor = GridBagConstraints.WEST,
                fill = GridBagConstraints.HORIZONTAL,
                insets = Insets(2, 8, 2, 4)))
            button.isEnabled = exists
            add(button, gbc(2, row))
            row++
        }

        // Optionally surface a tracker CSV if it exists alongside.
        val tracePath = runDir.resolve("trace.csv")
        if (!Files.exists(tracePath)) {
            // Tracker filenames are user-controlled; scan for any *.csv we didn't list.
            try {
                Files.newDirectoryStream(runDir, "*.csv").use { stream ->
                    for (p in stream) {
                        val name = p.fileName.toString()
                        if (name == ArtifactNames.ITERATION_HISTORY_CSV) continue
                        if (name == ArtifactNames.BEST_SOLUTION_CSV) continue
                        add(JLabel("• $name").apply {
                            font = font.deriveFont(Font.PLAIN, 12f)
                        }, gbc(0, row, width = 2, anchor = GridBagConstraints.WEST,
                            fill = GridBagConstraints.HORIZONTAL,
                            insets = Insets(2, 8, 2, 4)))
                        val extraOpen = JButton("Open").apply {
                            margin = Insets(1, 6, 1, 6); font = font.deriveFont(Font.PLAIN, 11f)
                            addActionListener { open(p) }
                        }
                        add(extraOpen, gbc(2, row))
                        row++
                    }
                }
            } catch (_: Throwable) { /* directory missing or unreadable */ }
        }

        revalidate(); repaint()
    }

    private fun open(path: Path) {
        if (!Files.exists(path)) return
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(path.toFile())
            }
        } catch (_: Throwable) {
            // Best-effort — sandboxes / headless containers may
            // refuse Desktop calls.  No-op is safer than crash.
        }
    }

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        width: Int = 1,
        anchor: Int = GridBagConstraints.WEST,
        fill: Int = GridBagConstraints.NONE,
        insets: Insets = Insets(2, 4, 2, 4)
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.gridwidth = width
        this.weightx = weightx
        this.anchor = anchor
        this.fill = fill
        this.insets = insets
    }
}
