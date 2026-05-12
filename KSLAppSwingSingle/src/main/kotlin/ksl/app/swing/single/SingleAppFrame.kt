package ksl.app.swing.single

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
 * The top-level [JFrame] for the single-run reference app.
 *
 * The frame owns its [SingleAppViewModel] and a single [CoroutineScope] for
 * collecting [SingleAppViewModel.uiState] on the EDT.  All panels delegate
 * user actions to the view model and re-render reactively on UI state
 * changes.
 *
 * Lifecycle: closing the window cancels the UI scope and closes the view
 * model (which cancels any in-flight run via [KSLAppSession.close]).
 */
internal class SingleAppFrame : JFrame("KSL Single-Model Run") {

    private val uiScope: CoroutineScope = CoroutineScope(Dispatchers.Swing)
    private val viewModel = SingleAppViewModel(scope = uiScope)

    private val modelPicker = ModelPickerPanel(
        availableModelIds = viewModel.availableModelIds,
        initialModelId = viewModel.selectedModelId,
        onModelSelected = { modelId ->
            viewModel.selectModel(modelId)
            configPanel.renderRunParameters(viewModel.runParameters)
        }
    )
    private val configPanel = ConfigPanel(
        initial = viewModel.runParameters,
        onChanged = { viewModel.updateRunParameters(it) }
    )
    private val runControls = RunControlsPanel(
        onRun = { viewModel.submit() },
        onCancel = { viewModel.cancel() }
    )
    private val resultPanel = ResultPanel()

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(720, 540)

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(modelPicker)
            add(configPanel)
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
        configPanel.setEditingEnabled(editing)
        runControls.renderUiState(state)
        resultPanel.renderUiState(state)
    }
}
