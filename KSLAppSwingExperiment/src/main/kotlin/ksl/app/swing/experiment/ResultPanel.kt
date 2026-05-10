package ksl.app.swing.experiment

import ksl.app.session.KSLRuntimeError
import ksl.app.session.RunResult
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * Bottom panel — terminal result display for the designed experiment.
 *
 * Renders one summary row per completed design-point snapshot plus the
 * overall counts from [RunResult.BatchCompleted.summary].  "Open report"
 * is non-functional in v1, matching the other Phase 6 modules.
 */
internal class ResultPanel : JPanel(BorderLayout()) {

    private val textArea = JTextArea().apply {
        isEditable = false
        rows = 10
        lineWrap = true
        wrapStyleWord = true
    }
    private val openReportButton = JButton("Open report").apply { isEnabled = false }

    init {
        border = BorderFactory.createTitledBorder("Result")
        add(JScrollPane(textArea), BorderLayout.CENTER)
        val buttonStrip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        buttonStrip.add(openReportButton)
        add(buttonStrip, BorderLayout.SOUTH)
    }

    fun renderUiState(state: UiState) {
        when (state) {
            is UiState.Idle -> {
                textArea.text = ""
                openReportButton.isEnabled = false
            }
            is UiState.Submitting, is UiState.Running -> Unit
            is UiState.Completed -> renderCompleted(state.result)
            is UiState.Cancelled -> textArea.text = "Run cancelled: ${state.reason}"
            is UiState.Failed    -> renderFailed(state.error)
        }
    }

    private fun renderCompleted(result: RunResult.BatchCompleted) {
        val sb = StringBuilder()
        val s = result.summary
        sb.appendLine("Designed experiment completed.")
        sb.appendLine("  Design points: ${s.completedItems} of ${s.totalItems} completed (${s.failedItems} failed)")
        sb.appendLine()
        sb.appendLine("Per-design-point summary:")
        result.snapshots.forEachIndexed { index, snap ->
            val runName = snap.simulationRun.run_name.ifBlank { "Point ${index + 1}" }
            val firstStat = snap.acrossRepStats.firstOrNull()
            if (firstStat != null) {
                sb.appendLine(
                    "  ${runName}: ${firstStat.stat_name} avg=${firstStat.average} n=${firstStat.stat_count}"
                )
            } else {
                sb.appendLine("  ${runName}: (no across-replication stats)")
            }
        }
        textArea.text = sb.toString()
        textArea.caretPosition = 0
        openReportButton.isEnabled = false
    }

    private fun renderFailed(error: KSLRuntimeError) {
        val sb = StringBuilder()
        sb.appendLine("Run failed.")
        when (error) {
            is KSLRuntimeError.ConfigurationError -> {
                sb.appendLine("  Configuration error: ${error.message}")
                error.validationResult?.errors?.forEach {
                    sb.appendLine("    - ${it.path}: ${it.message} [${it.code}]")
                }
            }
            is KSLRuntimeError.ExecutiveError ->
                sb.appendLine("  Executive error at simTime=${error.simTime}, rep=${error.replicationNumber}: ${error.cause.message}")
            is KSLRuntimeError.ModelBuildError ->
                sb.appendLine("  Model build error: ${error.message}")
            is KSLRuntimeError.JarLoadError ->
                sb.appendLine("  JAR load error: ${error.message}")
        }
        textArea.text = sb.toString()
        textArea.caretPosition = 0
    }
}
