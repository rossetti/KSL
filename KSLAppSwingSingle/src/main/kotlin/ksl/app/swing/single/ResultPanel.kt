package ksl.app.swing.single

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
 * Bottom panel — terminal result display.
 *
 * Renders a textual summary of the most recent terminal state.  Validation
 * errors are surfaced by walking the [KSLRuntimeError.ConfigurationError]
 * payload's `validationResult`; runtime errors print the exception chain.
 *
 * "Open report" is intentionally left as a future hook — wiring the HTML
 * report path requires a stable report-output convention from the
 * orchestrator side and is deferred to a Phase 6 follow-up.
 */
internal class ResultPanel : JPanel(BorderLayout()) {

    private val textArea = JTextArea().apply {
        isEditable = false
        rows = 8
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

    /** Re-renders the panel from the current UI state. */
    fun renderUiState(state: UiState) {
        when (state) {
            is UiState.Idle -> {
                textArea.text = ""
                openReportButton.isEnabled = false
            }
            is UiState.Submitting, is UiState.Running -> Unit  // leave previous result visible
            is UiState.Completed -> renderCompleted(state.result)
            is UiState.Cancelled -> textArea.text = "Run cancelled: ${state.reason}"
            is UiState.Failed    -> renderFailed(state.error)
        }
    }

    private fun renderCompleted(result: RunResult.Completed) {
        val s = result.summary
        val sb = StringBuilder()
        sb.appendLine("Run completed.")
        sb.appendLine("  Ending status: ${s.endingStatus}")
        sb.appendLine("  Completed replications: ${s.completedReplications} of ${s.requestedReplications}")
        sb.appendLine("  Wall-clock duration: ${s.wallClockDuration}")
        sb.appendLine()
        sb.appendLine("Across-replication statistics (first 10):")
        result.snapshot.acrossRepStats.take(10).forEach {
            sb.appendLine("  ${it.stat_name}: avg=${it.average}, n=${it.stat_count}")
        }
        textArea.text = sb.toString()
        textArea.caretPosition = 0
        // "Open report" remains disabled — see class KDoc.
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
