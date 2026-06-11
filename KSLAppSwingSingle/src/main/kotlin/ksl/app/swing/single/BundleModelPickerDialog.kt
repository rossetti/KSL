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

package ksl.app.swing.single

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ksl.app.bundle.LoadedBundle
import ksl.app.config.ModelReference
import ksl.app.editor.BundleLibraryController
import ksl.app.swing.common.bundle.BundleModelPickerPanel
import ksl.simulation.ModelDescriptor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.filechooser.FileNameExtensionFilter

/**
 *  Modal bundle-model picker shown by [KSLSingleApp.launch] when the developer
 *  omits `modelBuilder(...)` from the `kslSingleApp { … }` DSL.  Drives the
 *  controller's bundle-mode launch path.
 *
 *  Presents the shared [BundleModelPickerPanel] — the same bundle → model
 *  two-step + model-info table the Experiment / Simopt / Scenario apps use — so
 *  picking a model looks and feels identical across the apps.  The user can:
 *
 *  - Pick a bundle then a model and click **Pick** — returns [Result.Selected].
 *  - Click **Load JAR…** to extend [BundleLibraryController] with a
 *    user-supplied JAR; the picker refreshes automatically.
 *  - Click **Cancel** (or close the window) — returns [Result.Cancelled].
 *    The caller is responsible for exiting the JVM in that case.
 *
 *  Because the dialog runs before [SingleAppController] exists, it has no parent
 *  window.  Threading: construct and show on the Swing EDT; [show] blocks the
 *  EDT until the user dismisses the dialog (standard modal semantics).
 */
object BundleModelPickerDialog {

    /**
     *  Outcome of the picker.
     *
     *  - [Selected] — the user picked a `(bundleId, modelId)` pair.  The caller
     *    resolves the matching `ksl.simulation.ModelBuilderIfc` via
     *    `bundleLibrary.bundleProvider.value!!.builderFor(bundleId, modelId)`.
     *  - [Cancelled] — the user dismissed the dialog without picking.
     */
    sealed class Result {
        data class Selected(val bundleId: String, val modelId: String) : Result()
        object Cancelled : Result()
    }

    /**
     *  Present the picker modally.  Returns the user's choice.  Must be called
     *  on the Swing EDT.
     *
     *  @param bundleLibrary the (already discovery-probed) library the picker
     *  reads from.  The dialog calls [BundleLibraryController.loadJar] on the
     *  user's behalf when they click *Load JAR…*; passing the same library
     *  instance to the eventual [SingleAppController] preserves any JARs loaded
     *  during picker interaction.
     *  @param dialogTitle the modal's title.  Defaults to "Pick a Model".
     */
    fun show(
        bundleLibrary: BundleLibraryController,
        dialogTitle: String = "Pick a Model"
    ): Result {
        val impl = PickerDialog(bundleLibrary, dialogTitle)
        impl.isVisible = true
        return impl.result
    }
}

/**
 *  The actual `JDialog` — non-public so callers go through
 *  [BundleModelPickerDialog.show].
 */
private class PickerDialog(
    private val bundleLibrary: BundleLibraryController,
    title: String
) : JDialog(null as java.awt.Frame?, title, /* modal = */ true) {

    /** Captured choice; read by [BundleModelPickerDialog.show] after the dialog
     *  is dismissed.  Initialized to Cancelled so a window-close via the [X]
     *  button is treated as Cancel. */
    var result: BundleModelPickerDialog.Result = BundleModelPickerDialog.Result.Cancelled
        private set

    /** Dialog-lifetime scope for the picker's flow collectors, the loaded-bundle
     *  watcher, and off-EDT descriptor resolution; cancelled on close.  The
     *  default dispatcher is fine — the picker re-dispatches its own UI work to
     *  the EDT, and the descriptor build wants to be off the EDT. */
    private val scope = CoroutineScope(SupervisorJob())

    private val referenceFlow =
        MutableStateFlow<ModelReference?>(firstModelRef(bundleLibrary.loadedBundles.value))
    private val descriptorFlow = MutableStateFlow<ModelDescriptor?>(null)

    private val picker = BundleModelPickerPanel(
        loadedBundles = bundleLibrary.loadedBundles,
        currentReference = referenceFlow,
        currentDescriptor = descriptorFlow,
        scope = scope,
        onSelect = ::onModelSelected
    )

    private val pickButton = JButton(object : AbstractAction("Pick") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = onPick()
    }).apply { isEnabled = false }

    private val loadJarButton = JButton(object : AbstractAction("Load JAR…") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = onLoadJar()
    })

    private val cancelButton = JButton(object : AbstractAction("Cancel") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = onCancel()
    })

    /** Banner area for empty-state guidance + Load JAR result messages. */
    private val banner = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(8, 12, 0, 12)
        horizontalAlignment = SwingConstants.LEFT
    }

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) = onCancel()  // [X] == Cancel
        })

        contentPane.layout = BorderLayout()
        contentPane.add(banner, BorderLayout.NORTH)
        contentPane.add(JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            add(picker, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        contentPane.add(buildButtonRow(), BorderLayout.SOUTH)
        rootPane.defaultButton = pickButton

        // Prime the model-info + Pick state for the initial selection (the
        // picker's programmatic sync to the seed doesn't fire onSelect).
        (referenceFlow.value as? ModelReference.ByBundleAndModelId)?.let {
            onModelSelected(it.bundleId, it.modelId)
        }

        // React to bundles loaded via Load JAR…: default to a model when one
        // appears, drive Pick enablement, and refresh the empty-state banner.
        scope.launch {
            bundleLibrary.loadedBundles.collect { bundles ->
                SwingUtilities.invokeLater { onBundlesChanged(bundles) }
            }
        }

        pack()
        size = Dimension(640, 460)
        setLocationRelativeTo(null)
    }

    private fun buildButtonRow(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(loadJarButton) }, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(cancelButton)
            add(pickButton)
        }, BorderLayout.EAST)
    }

    /** First model of the first loaded bundle, or `null` when none are loaded. */
    private fun firstModelRef(bundles: List<LoadedBundle>): ModelReference.ByBundleAndModelId? {
        val bundle = bundles.firstOrNull() ?: return null
        val model = bundle.bundle.models.firstOrNull() ?: return null
        return ModelReference.ByBundleAndModelId(bundle.bundle.bundleId, model.modelId)
    }

    private fun onBundlesChanged(bundles: List<LoadedBundle>) {
        // First bundle just arrived (e.g. via Load JAR…) with nothing selected:
        // default to its first model so the user can Pick immediately.
        if (referenceFlow.value == null) {
            firstModelRef(bundles)?.let { onModelSelected(it.bundleId, it.modelId) }
        }
        pickButton.isEnabled = bundles.isNotEmpty()
        refreshBannerForEmptyState(bundles)
    }

    /** A model was picked (by the user or a default): track it, enable Pick,
     *  and resolve the model-info descriptor off the EDT. */
    private fun onModelSelected(bundleId: String, modelId: String) {
        referenceFlow.value = ModelReference.ByBundleAndModelId(bundleId, modelId)
        pickButton.isEnabled = true
        descriptorFlow.value = null
        scope.launch {
            descriptorFlow.value =
                runCatching { bundleLibrary.findBundle(bundleId)?.descriptorFor(modelId) }.getOrNull()
        }
    }

    private fun onPick() {
        val selection = picker.selectedModel() ?: return
        result = BundleModelPickerDialog.Result.Selected(selection.first, selection.second)
        scope.cancel()
        dispose()
    }

    private fun onCancel() {
        result = BundleModelPickerDialog.Result.Cancelled
        scope.cancel()
        dispose()
    }

    private fun onLoadJar() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Load Bundle JAR"
            fileFilter = FileNameExtensionFilter("Bundle JAR (*.jar)", "jar")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path: Path = chooser.selectedFile?.toPath() ?: return
        // loadedBundles changes propagate to the picker and onBundlesChanged
        // automatically; here we just surface the outcome message.
        when (val outcome = bundleLibrary.loadJar(path)) {
            is BundleLibraryController.LoadBundleResult.Loaded ->
                showBannerInfo("Loaded ${outcome.newBundleIds.size} bundle(s): " +
                    outcome.newBundleIds.joinToString(", "))
            is BundleLibraryController.LoadBundleResult.Reloaded ->
                showBannerInfo("Reloaded from disk: " + outcome.bundleIds.joinToString(", "))
            is BundleLibraryController.LoadBundleResult.AlreadyLoaded ->
                showBannerInfo("Already loaded (no change): " + outcome.bundleIds.joinToString(", "))
            BundleLibraryController.LoadBundleResult.NoBundles ->
                showBannerError("$path declares no KSLModelBundle service.")
            is BundleLibraryController.LoadBundleResult.Failed ->
                showBannerError("Could not load $path: ${outcome.reason}")
        }
    }

    private fun refreshBannerForEmptyState(bundles: List<LoadedBundle>) {
        if (bundles.isEmpty()) {
            banner.foreground = Color(0x6B, 0x6B, 0x6B)
            banner.text = "No bundles loaded.  Click Load JAR… to load one, " +
                "or drop a bundle JAR into ~/.ksl/bundles/."
        } else if (banner.text.isBlank()) {
            banner.text = " "
        }
    }

    private fun showBannerError(message: String) {
        banner.foreground = Color(0xB0, 0x00, 0x20)
        banner.text = message
    }

    private fun showBannerInfo(message: String) {
        banner.foreground = Color(0x6B, 0x6B, 0x6B)
        banner.text = message
    }
}
