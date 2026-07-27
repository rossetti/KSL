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

package ksl.app.swing.animation.playback

import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.Timer

/**
 * The Swing transport bar for a [PlaybackController]: a play/pause button, a scrub slider, a speed
 * selector, a loop toggle, and a time read-out. It owns a wall-clock [Timer] that, while playing,
 * advances the controller by the real elapsed time each tick (so playback speed is independent of
 * the frame rate). All controller mutation happens on the EDT.
 *
 * The panel both drives the controller (user actions) and reflects it (a time listener updates the
 * slider/label), so programmatic seeks stay in sync. A reentrancy guard keeps the slider's own
 * change events from being treated as user seeks while we are updating it.
 */
class PlaybackPanel(private val controller: PlaybackController) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    private val playButton = JButton("Play")
    private val stopButton = JButton("Stop")
    private val slider = JSlider(0, SLIDER_RESOLUTION, 0)
    private val speedBox = JComboBox(PlaybackController.SPEEDS.map { speedLabel(it) }.toTypedArray()).apply {
        isEditable = true // allow auto-scaled / custom speeds beyond the presets
    }
    private val loopBox = JCheckBox("Loop")
    private val inButton = JButton("[")
    private val outButton = JButton("]")
    private val clearButton = JButton("Clear focus")
    private val timeLabel = JLabel(formatTime(controller.currentTime))

    private var updatingSlider = false
    private var lastTickNanos = 0L

    /** ~30 fps wall-clock driver; advances the controller by real elapsed seconds while playing. */
    private val timer = Timer(FRAME_DELAY_MS) {
        val now = System.nanoTime()
        val elapsed = (now - lastTickNanos) / 1e9
        lastTickNanos = now
        controller.advanceBy(elapsed)
    }

    init {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        speedBox.selectedItem = "1x"

        stopButton.toolTipText = "Stop and rewind to the start"
        inButton.toolTipText = "Set in-point (focus start) to the current time"
        outButton.toolTipText = "Set out-point (focus end) to the current time"
        clearButton.toolTipText = "Remove the in/out focus window (play the full range)"

        add(playButton)
        add(stopButton)
        add(slider)
        add(JLabel("Speed:"))
        add(speedBox)
        add(loopBox)
        add(inButton)
        add(outButton)
        add(clearButton)
        add(timeLabel)

        playButton.addActionListener { controller.togglePlay(); syncPlayButton() }
        stopButton.addActionListener { controller.stop(); syncPlayButton() }
        slider.addChangeListener {
            if (!updatingSlider) controller.seekFraction(slider.value.toDouble() / SLIDER_RESOLUTION)
        }
        speedBox.addActionListener {
            // Tolerate editable/auto-scaled entries like "16.0x", "16x", or "16".
            (speedBox.selectedItem as? String)?.trim()?.removeSuffix("x")?.removeSuffix("X")?.trim()
                ?.toDoubleOrNull()?.let { if (it > 0.0) controller.speed = it }
        }
        loopBox.addActionListener { controller.loop = loopBox.isSelected }
        // In/out points define a focus sub-range; the slider then zooms to it and Loop loops it (8I.7).
        inButton.addActionListener { controller.setIn(controller.currentTime); controller.seek(controller.currentTime) }
        outButton.addActionListener { controller.setOut(controller.currentTime); controller.seek(controller.currentTime) }
        clearButton.addActionListener { controller.clearFocus(); controller.seek(controller.currentTime) }

        controller.addTimeListener { t ->
            updatingSlider = true
            slider.value = (controller.fraction() * SLIDER_RESOLUTION).toInt()
            updatingSlider = false
            timeLabel.text = formatTime(t)
            if (!controller.isPlaying) syncPlayButton()
        }
        syncPlayButton()
    }

    /**
     * Chooses a playback speed so a run spanning [span] base-time units plays in roughly [targetSeconds]
     * of wall-clock, and reflects it in the speed selector. Without this a long run plays at 1x — e.g. a
     * 480-unit run would take 8 minutes, appearing not to animate. A no-op for an empty/tiny span.
     */
    fun applyAutoSpeed(span: Double, targetSeconds: Double = PlaybackController.DEFAULT_TARGET_SECONDS) {
        if (span <= 0.0 || targetSeconds <= 0.0) return
        val speed = PlaybackController.autoSpeedFor(span, targetSeconds)
        speedBox.selectedItem = speedLabel(speed) // its action listener applies the parsed speed…
        controller.speed = speed                  // …and set directly in case the label already matched
    }

    private fun syncPlayButton() {
        playButton.text = if (controller.isPlaying) "Pause" else "Play"
        if (controller.isPlaying) {
            lastTickNanos = System.nanoTime()
            if (!timer.isRunning) timer.start()
        } else {
            if (timer.isRunning) timer.stop()
        }
    }

    private fun formatTime(t: Double): String {
        val f = controller.focus
        return if (f == null) "t = %.1f / %.1f".format(t, controller.timeRange.endInclusive)
        else "t = %.1f  [%.1f–%.1f]".format(t, f.start, f.endInclusive)
    }

    companion object {
        private const val SLIDER_RESOLUTION = 1000
        private const val FRAME_DELAY_MS = 33
        /** "5x" / "0.25x" — a speed in simulated units per real second, as the box shows it. */
        internal fun speedLabel(speed: Double): String =
            if (speed == Math.floor(speed)) "${speed.toInt()}x" else "${speed}x"
    }
}
