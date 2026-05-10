package ksl.app.swing.simopt

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
 * Bottom panel — terminal result display for the optimization run.
 *
 * Renders the best solution (input values + estimated objective) plus
 * the per-iteration convergence history.  "Open report" is
 * non-functional in v1, matching the other Phase 6 modules.
 */
internal class ResultPanel : JPanel(BorderLayout()) {

    private val textArea = JTextArea().apply {
        isEditable = false
        rows = 12
        lineWrap = false
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

    private fun renderCompleted(result: RunResult.OptimizationCompleted) {
        val sb = StringBuilder()
        val best = result.bestSolution
        sb.appendLine("Optimization completed in ${result.summary.completedItems} iterations.")
        sb.appendLine()
        sb.appendLine("Best solution:")
        best.bestSolutionSoFar.inputMap.entries.forEach { (k, v) ->
            sb.appendLine("  $k = $v")
        }
        sb.appendLine("  estimated objective = ${best.estimatedObjFncValue}")
        sb.appendLine()
        sb.appendLine("Iteration history:")
        result.iterationHistory.forEach { snap ->
            val inputs = snap.bestSolutionSoFar.inputMap.entries
                .joinToString(", ") { (k, v) -> "$k=$v" }
            sb.appendLine(
                "  iter ${snap.iterationNumber}: estObj=${snap.estimatedObjFncValue}  best=($inputs)"
            )
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
