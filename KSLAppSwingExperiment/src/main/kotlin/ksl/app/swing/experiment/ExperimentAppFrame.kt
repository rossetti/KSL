package ksl.app.swing.experiment

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.WindowConstants

/**
 * Top-level [JFrame] for the designed-experiment reference app.
 */
internal class ExperimentAppFrame : JFrame("KSL Designed-Experiment Run") {

    private val uiScope: CoroutineScope = CoroutineScope(Dispatchers.Swing)
    private val viewModel = ExperimentAppViewModel(scope = uiScope)

    private val modelPicker = ModelPickerPanel(
        initialModelId = viewModel.selectedModelId,
        onModelSelected = { modelId ->
            viewModel.selectModel(modelId)
            experimentSummaryPanel.renderExperiment(viewModel.experiment)
        }
    )
    private val experimentSummaryPanel = ExperimentSummaryPanel(viewModel.experiment)
    private val runControls = RunControlsPanel(
        onRun = { viewModel.submit() },
        onCancel = { viewModel.cancel() }
    )
    private val resultPanel = ResultPanel()

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(760, 560)

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(modelPicker)
            add(experimentSummaryPanel)
            add(runControls)
            add(Box.createVerticalStrut(4))
            add(resultPanel)
        }
        contentPane.add(content, BorderLayout.CENTER)

        viewModel.uiState
            .onEach { state -> renderUiState(state) }
            .launchIn(uiScope)

        addWindowListener(object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent?) {
                viewModel.close()
                uiScope.cancel("frame closed")
            }
        })
    }

    private fun renderUiState(state: UiState) {
        val editing = state is UiState.Idle ||
            state is UiState.Completed ||
            state is UiState.Cancelled ||
            state is UiState.Failed
        modelPicker.setEditingEnabled(editing)
        runControls.renderUiState(state)
        resultPanel.renderUiState(state)
    }
}
