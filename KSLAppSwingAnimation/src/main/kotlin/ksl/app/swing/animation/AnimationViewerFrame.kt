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

package ksl.app.swing.animation

import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.playback.PlaybackController
import ksl.app.swing.animation.playback.PlaybackPanel
import ksl.app.swing.animation.replay.ReplayModel
import ksl.app.swing.animation.view.SimulationCanvas
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JCheckBoxMenuItem
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The top-level window of the KSL animation viewer. It plays back a two-file animation
 * (`.lay.json` layout + `.atf` trace): the [SimulationCanvas] renders the frame at the
 * [PlaybackController]'s current time, and the [PlaybackPanel] drives time. This is the thin Swing
 * shell; the replay logic lives in the headless `replay`/`view` classes, which the canvas queries.
 */
class AnimationViewerFrame : JFrame("KSL Animation Viewer") {

    private val canvas = SimulationCanvas()
    private val controller = PlaybackController()
    private val playback = PlaybackPanel(controller)

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        jMenuBar = buildMenuBar()
        contentPane.layout = BorderLayout()
        contentPane.add(canvas, BorderLayout.CENTER)
        contentPane.add(playback, BorderLayout.SOUTH)
        // The canvas always shows the controller's current time.
        controller.addTimeListener { canvas.currentTime = it }
        preferredSize = Dimension(1100, 760)
        pack()
        setLocationRelativeTo(null)
    }

    /** Loads a built [source] into the viewer: builds the replay model and resets playback to its start. */
    fun loadSource(source: AnimationSource) {
        val model: ReplayModel = ReplayModel.build(source)
        canvas.replay = model
        controller.pause()
        controller.timeRange = model.timeRange
        controller.seek(model.timeRange.start)
        title = "KSL Animation Viewer — ${source.layout?.title ?: "trace"}"
    }

    /** Loads from files (trace required, layout optional). Convenience for tests and the File menu. */
    fun open(traceFile: Path, layoutFile: Path? = null) = loadSource(AnimationSource.load(layoutFile, traceFile))

    private fun buildMenuBar(): JMenuBar = JMenuBar().apply {
        add(JMenu("File").apply {
            add(JMenuItem("Open…").apply { addActionListener { chooseAndOpen() } })
            addSeparator()
            add(JMenuItem("Exit").apply { addActionListener { dispose() } })
        })
        add(JMenu("View").apply {
            add(JCheckBoxMenuItem("Show Legend", canvas.showLegend).apply {
                addActionListener { canvas.showLegend = isSelected }
            })
        })
    }

    /** Prompts for a `.atf` trace, then auto-uses a sibling `.lay.json` if present (else prompts). */
    private fun chooseAndOpen() {
        val traceChooser = JFileChooser().apply {
            dialogTitle = "Open animation trace (.atf)"
            fileFilter = FileNameExtensionFilter("Animation trace (*.atf)", "atf")
        }
        if (traceChooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val trace = traceChooser.selectedFile

        // Auto-find a sibling layout (JSON or TOML); otherwise prompt.
        val base = trace.nameWithoutExtension()
        val sibling = listOf("$base.lay.json", "$base.lay.toml")
            .map { File(trace.parentFile, it) }.firstOrNull { it.exists() }
        val layout: File? = sibling ?: promptForLayout(trace.parentFile)

        runCatching { open(trace.toPath(), layout?.toPath()) }
            .onFailure { JOptionPane.showMessageDialog(this, "Failed to open: ${it.message}", "Error", JOptionPane.ERROR_MESSAGE) }
    }

    private fun promptForLayout(dir: File?): File? {
        val chooser = JFileChooser(dir).apply {
            dialogTitle = "Open layout (.lay.json / .lay.toml) — optional, Cancel to skip"
            fileFilter = FileNameExtensionFilter("Animation layout (*.json, *.toml)", "json", "toml")
        }
        return if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
    }

    private fun File.nameWithoutExtension(): String = name.substringBeforeLast('.')
}
