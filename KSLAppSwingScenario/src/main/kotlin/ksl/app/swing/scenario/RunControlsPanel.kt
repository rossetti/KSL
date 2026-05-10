package ksl.app.swing.scenario

import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * Run / Cancel buttons plus a progress display.  Progress is reported
 * per-scenario (`Scenario X / N`) rather than per-replication —
 * `ScenarioOrchestrator` does not emit per-replication events.
 */
internal class RunControlsPanel(
    private val onRun: () -> Unit,
    private val onCancel: () -> Unit
) : JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)) {

    private val runButton = JButton("Run").apply { addActionListener { onRun() } }
    private val cancelButton = JButton("Cancel").apply {
        isEnabled = false
        addActionListener { onCancel() }
    }
    private val progressLabel = JLabel("Idle")
    private val progressBar = JProgressBar(0, 100).apply {
        isStringPainted = true
        value = 0
    }

    init {
        add(runButton)
        add(cancelButton)
        add(progressLabel)
        add(progressBar)
    }

    fun renderUiState(state: UiState) {
        when (state) {
            is UiState.Idle -> {
                runButton.isEnabled = true
                cancelButton.isEnabled = false
                progressLabel.text = "Idle"
                progressBar.value = 0
                progressBar.string = ""
                progressBar.isIndeterminate = false
            }
            is UiState.Submitting -> {
                runButton.isEnabled = false
                cancelButton.isEnabled = true
                progressLabel.text = "Submitting…"
                progressBar.isIndeterminate = true
            }
            is UiState.Running -> {
                runButton.isEnabled = false
                cancelButton.isEnabled = true
                progressBar.isIndeterminate = false
                progressLabel.text =
                    "Scenario ${state.scenariosCompleted} / ${state.totalScenarios}"
                val pct = if (state.totalScenarios > 0) {
                    (100.0 * state.scenariosCompleted / state.totalScenarios).toInt()
                } else 0
                progressBar.value = pct
                progressBar.string = "$pct%"
            }
            is UiState.Completed -> {
                runButton.isEnabled = true
                cancelButton.isEnabled = false
                progressBar.isIndeterminate = false
                progressLabel.text =
                    "Completed (${state.result.summary.completedItems}/${state.result.summary.totalItems})"
                progressBar.value = 100
                progressBar.string = "100%"
            }
            is UiState.Cancelled -> {
                runButton.isEnabled = true
                cancelButton.isEnabled = false
                progressBar.isIndeterminate = false
                progressLabel.text = "Cancelled: ${state.reason}"
            }
            is UiState.Failed -> {
                runButton.isEnabled = true
                cancelButton.isEnabled = false
                progressBar.isIndeterminate = false
                progressLabel.text = "Failed"
            }
        }
    }
}
