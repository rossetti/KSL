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

package ksl.app.swing.animation.app

import ksl.animation.AnimationLayout
import ksl.app.animation.web.SelfContainedHtmlExporter
import ksl.animation.AnimationTraceHeader
import ksl.animation.AnimationEvent
import ksl.animation.TraceFileReader
import ksl.app.animation.io.AnimationSource
import ksl.app.swing.animation.playback.PlaybackController
import ksl.app.swing.animation.playback.PlaybackPanel
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.replay.autoLayout
import ksl.app.animation.replay.layoutTraceCompatibility
import ksl.app.swing.animation.view.SimulationCanvas
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The **Replay** tab (9F.4): pairs a chosen trace (`.atf`) with a chosen layout and plays it. Replay is
 * explicitly *trace × layout* — a layout binds to elements by name, so one trace can be viewed through
 * many layouts and the layout can be swapped without reloading the trace. The toolbar offers a **trace**
 * picker (the model's `traces/`, plus Browse), a **layout** picker (the **active** editing layout, an
 * **Auto layout** derived from the model + trace via the same generator the Layout tab uses, the saved
 * `layouts/`, plus Browse), and a compatibility read-out of how the pairing lines up. A [PlaybackPanel]
 * drives time over the [SimulationCanvas].
 */
class ReplayPanel(private val app: AnimationAppController) : JPanel(BorderLayout()) {

    private val canvas = SimulationCanvas()
    private val playbackController = PlaybackController()
    private val playback = PlaybackPanel(playbackController)

    private val traceCombo = JComboBox<TraceItem>()
    private val layoutCombo = JComboBox<LayoutChoice>()
    private val loadButton = JButton("Load")
    private val exportHtmlButton = JButton("Export to HTML…")
    private val loadedLabel = JLabel(" ")
    private val compatLabel = JLabel(" ")
    private val gridToggle = JCheckBox("Show grid")
    private val flowFieldToggle = JCheckBox("Show flow field", true)
    private val pathsToggle = JCheckBox("Show paths", true)
    private val vectorsToggle = JCheckBox("Show vectors", true)
    private val pulsesToggle = JCheckBox("Show pulses", true)
    private val stationItemsToggle = JCheckBox("Show station items") // off by default; the per-station glyphs are noisy
    private val panToggle = JToggleButton("Pan", true)
    private val coordLabel = JLabel(" ")

    private var cachedHeader: AnimationTraceHeader? = null
    private var cachedEvents: List<AnimationEvent> = emptyList()
    private var currentTraceFile: Path? = null
    private var updating = false

    init {
        playbackController.addTimeListener { canvas.currentTime = it }
        add(buildToolbar(), BorderLayout.NORTH)
        add(canvas, BorderLayout.CENTER)
        add(playback, BorderLayout.SOUTH)
        refreshTraceChoices()
        refreshLayoutChoices()
        // Selecting in a combo only *chooses*; nothing loads until Load is pressed (explicit + predictable).
        traceCombo.addActionListener { if (!updating) syncLoadEnabled() }
        loadButton.addActionListener { onLoad() }
        syncLoadEnabled()
        updateEmptyState()
    }

    // ── Toolbar (trace × layout pickers + Load + status) ───────────────────────────

    private fun buildToolbar(): JPanel = JPanel(BorderLayout()).apply {
        // WrapLayout (not plain FlowLayout) so the controls wrap to a second visible row when the window
        // narrows, instead of the Load/Rescan buttons being clipped out of the NORTH band (G5).
        add(JPanel(WrapLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Trace:")); add(traceCombo)
            add(JButton("Browse…").apply { addActionListener { browseTrace() } })
            add(JLabel("   Layout:")); add(layoutCombo)
            add(JButton("Browse…").apply { addActionListener { browseLayout() } })
            loadButton.toolTipText = "Load the selected trace and layout into the viewer"
            add(loadButton)
            add(JButton("Rescan folders").apply {
                toolTipText = "Re-read the model's traces/ and layouts/ folders for new files"
                addActionListener { rescan() }
            })
            add(exportHtmlButton.apply {
                toolTipText = if (SelfContainedHtmlExporter.isAvailable())
                    "Save what is loaded here as a single self-contained web page"
                else
                    "Unavailable in this build - the animation web player was not built"
                isEnabled = SelfContainedHtmlExporter.isAvailable()
                addActionListener { exportHtml() }
            })
        }, BorderLayout.NORTH)
        add(buildViewBar(), BorderLayout.CENTER) // grid + zoom/pan parity with the Layout canvas (10.9)
        add(JPanel(BorderLayout()).apply {
            loadedLabel.border = BorderFactory.createEmptyBorder(2, 8, 0, 8)
            compatLabel.border = BorderFactory.createEmptyBorder(0, 8, 2, 8)
            add(loadedLabel, BorderLayout.NORTH)
            add(compatLabel, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    /**
     * View options for the replay canvas: grid toggle, zoom, fit, pan, and a live coordinate read-out (10.9).
     *
     * `WrapLayout`, not `FlowLayout`, for the same reason the toolbar above uses it. A plain `FlowLayout`
     * does wrap its children into rows when the width is squeezed, but it reports a *single row* as its
     * preferred size; inside a `BorderLayout` region the parent then allots one row's height and the
     * wrapped rows are drawn outside it. The trailing controls do not move down — they disappear. Here
     * that meant **Zoom +, Zoom − and Fit**, the three that get a lost view back, silently vanishing on a
     * narrow window.
     */
    private fun buildViewBar(): JComponent = JPanel(BorderLayout()).apply {
        canvas.panEnabled = panToggle.isSelected // pan-by-drag on by default (no element editing in replay)
        // Two rows, because they answer two different questions: "what is drawn" and "where am I looking".
        // Mixed on one line they read as one undifferentiated strip of controls, and the navigation buttons
        // — the ones that recover a view you have lost — are the hardest to pick out of it.
        //
        // BorderLayout NORTH/SOUTH rather than a Box: each row is handed the full width, which is what
        // WrapLayout measures against, and keeps its own preferred height so a row that wraps does not
        // pad the one that did not.
        add(buildOverlayRow(), BorderLayout.NORTH)
        add(buildNavigationRow(), BorderLayout.SOUTH)
        canvas.addMouseMotionListener(object : java.awt.event.MouseAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                coordLabel.text = "x = ${fmt(w.x)}, y = ${fmt(w.y)}"
            }
        })
    }

    /** What is drawn on top of the animation — every one of these is about the picture's *content*. */
    private fun buildOverlayRow(): JComponent = JPanel(WrapLayout(FlowLayout.LEFT)).apply {
        add(gridToggle.apply { addActionListener { canvas.showGrid = isSelected; canvas.repaint() } })
        // Labels and tooltips for these four come from syncOverlayToggles, which owns them because it
        // rewrites both once a trace is loaded to say what that trace actually contains.
        add(flowFieldToggle.apply { addActionListener { canvas.showFlowField = isSelected } })
        add(pathsToggle.apply { addActionListener { canvas.showPlannedPaths = isSelected } })
        add(vectorsToggle.apply { addActionListener { canvas.showVectors = isSelected } })
        add(pulsesToggle.apply { addActionListener { canvas.showMarkerPulses = isSelected } })
        syncOverlayToggles() // nothing loaded yet: inert, but described normally
        add(stationItemsToggle.apply {
            toolTipText = "Show the items currently at each network station (off by default — the per-station glyphs are noisy)"
            addActionListener { canvas.showStationContents = isSelected }
        })
    }

    /** Where the view is pointed — zoom, fit, pan, and the coordinate read-out that tells you where. */
    private fun buildNavigationRow(): JComponent = JPanel(WrapLayout(FlowLayout.LEFT)).apply {
        add(JButton("Zoom +").apply { addActionListener { canvas.zoomIn() } })
        add(JButton("Zoom −").apply { addActionListener { canvas.zoomOut() } })
        add(JButton("Fit").apply { toolTipText = "Reset zoom & pan to fit"; addActionListener { canvas.resetView() } })
        add(panToggle.apply {
            toolTipText = "Drag to pan the view"
            addActionListener { canvas.panEnabled = isSelected }
        })
        add(coordLabel)
    }

    /** Compact coordinate formatter (drops trailing ".0"). */
    private fun fmt(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)

    /** Re-reads the model's folders for new traces/layouts (used by the toolbar and on tab focus). When a trace is
     *  already loaded, also re-applies the current layout so the replay reflects edits made since it was loaded
     *  (e.g. a location dragged on the Layout tab) — otherwise the replay keeps playing the pre-edit model. */
    fun rescan() {
        refreshTraceChoices(); refreshLayoutChoices(); syncLoadEnabled()
        if (currentTraceFile != null && cachedEvents.isNotEmpty()) applyLayout() else if (currentTraceFile == null) updateEmptyState()
    }

    private fun syncLoadEnabled() { loadButton.isEnabled = traceCombo.selectedItem is TraceItem }

    private fun updateEmptyState() {
        loadedLabel.text = "Nothing loaded yet."
        compatLabel.text = if (traceCombo.itemCount == 0)
            "No traces yet — run a simulation on the Run tab, or Browse to a .atf, then press Load."
        else "Select a trace and a layout, then press Load."
    }

    /** Loads the currently-selected trace × layout. Re-reads the trace only when it changed. */
    private fun onLoad() {
        val item = traceCombo.selectedItem as? TraceItem ?: return
        if (item.path == currentTraceFile && cachedEvents.isNotEmpty()) applyLayout() // same trace, maybe new layout
        else loadTrace(item.path)
    }

    private fun refreshTraceChoices() = withUpdating {
        val selected = currentTraceFile
        traceCombo.removeAllItems()
        app.listTraces().forEach { traceCombo.addItem(TraceItem(it)) }
        selected?.let { sel -> selectComboItem(traceCombo) { it.path == sel } }
    }

    private fun refreshLayoutChoices() = withUpdating {
        val previous = layoutCombo.selectedItem as? LayoutChoice
        layoutCombo.removeAllItems()
        // Default to the active editing layout (so Replay shows exactly what the Layout tab shows); fall back
        // to the unified Auto layout — the SAME generator the Layout tab uses — when nothing is authored yet.
        if (app.layout.value != null) layoutCombo.addItem(LayoutChoice.Active)
        layoutCombo.addItem(LayoutChoice.AutoLayout)
        app.listLayouts().forEach { layoutCombo.addItem(LayoutChoice.Saved(it)) }
        // Keep the prior selection when it still exists; else the combo defaults to its first item (the active
        // layout when one exists, otherwise the auto layout).
        if (previous != null) selectComboItem(layoutCombo) { it == previous }
    }

    // ── Loading / pairing ─────────────────────────────────────────────────────────

    /** Reads [path] into the event cache and renders it through the currently chosen layout. */
    fun loadTrace(path: Path) {
        runCatching { TraceFileReader.readAll(path) }
            .onSuccess { (header, events) ->
                cachedHeader = header; cachedEvents = events; currentTraceFile = path
                applyLayout()
            }
            .onFailure { showError("Failed to open trace: ${it.message}") }
    }

    /**
     * After a run, refresh the choices, prefer the active authored layout (else the Auto layout), and load the
     * just-produced [path] — so Simulate → watch flows without manual picking.
     */
    fun showProducedTrace(path: Path) {
        withUpdating {
            refreshTraceChoices()
            refreshLayoutChoices()
            if (traceCombo.itemCount == 0 || (0 until traceCombo.itemCount).none { traceCombo.getItemAt(it).path == path }) {
                traceCombo.addItem(TraceItem(path))
            }
            selectComboItem(traceCombo) { it.path == path }
            selectComboItem(layoutCombo) { it == if (app.layout.value != null) LayoutChoice.Active else LayoutChoice.AutoLayout }
        }
        loadTrace(path)
    }

    /** Rebuilds the replay from the cached trace events and the currently chosen layout (no re-read). */
    private fun applyLayout() {
        val header = cachedHeader ?: return
        if (cachedEvents.isEmpty()) return
        val choice = layoutCombo.selectedItem as? LayoutChoice ?: LayoutChoice.AutoLayout
        val layout: AnimationLayout? = when (choice) {
            // The unified generator — trace positions + model geometry, the SAME path the Layout tab uses.
            // null (e.g. no model / probe failure) → loadSource's trace-only safety net still renders elements.
            LayoutChoice.AutoLayout -> app.buildAutoLayout()
            LayoutChoice.Active -> app.layout.value // may be null → behaves like the auto fallback
            is LayoutChoice.Saved -> runCatching { AnimationLayout.read(choice.path) }
                .getOrElse { showError("Failed to open layout: ${it.message}"); return }
        }
        loadSource(AnimationSource(layout, header, cachedEvents, currentTraceFile?.toAbsolutePath()?.parent?.toString()))
        updateCompatibility(layout)
        updateLoadedLabel(choice)
    }

    private fun updateLoadedLabel(choice: LayoutChoice) {
        val trace = currentTraceFile?.fileName?.toString() ?: "—"
        val range = canvas.replay?.timeRange
        val t = range?.let { " — ${cachedEvents.size} events, t = ${"%.1f".format(it.start)}…${"%.1f".format(it.endInclusive)}" } ?: ""
        loadedLabel.text = "Loaded: $trace  ×  $choice$t"
    }

    private fun updateCompatibility(resolvedLayout: AnimationLayout?) {
        val model = canvas.replay
        compatLabel.text = when {
            resolvedLayout == null -> "Auto layout derived from the trace."
            model == null -> " "
            else -> layoutTraceCompatibility(resolvedLayout, model).summary()
        }
    }

    /** Builds the replay model from [source] (auto-layout fallback when null), shows it, and sets the speed. */
    private fun loadSource(source: AnimationSource) {
        var model: ReplayModel = ReplayModel.build(source)
        if (source.layout == null) {
            val fallback = model.autoLayout(source.events, source.header.description)
            model = ReplayModel.build(AnimationSource(fallback, source.header, source.events, source.assetBase))
        }
        canvas.replay = model
        syncOverlayToggles()
        playbackController.pause()
        playbackController.timeRange = model.timeRange
        playbackController.seek(model.timeRange.start)
        playback.applyAutoSpeed(model.timeRange.endInclusive - model.timeRange.start)
    }

    // ── Browse ──────────────────────────────────────────────────────────────────

    /**
     * One "Show …" toggle, paired with the trace content it draws and the capture switch that records it.
     *
     * @property present whether the loaded trace carries anything for this overlay
     * @property captureBox the checkbox on the run bar that has to be ticked *before* the run for it to
     *   carry any — the missing half of the story when the view looks empty
     */
    private class OverlayToggle(
        val box: JCheckBox,
        val label: String,
        val tip: String,
        val captureBox: String,
        val present: (ReplayModel) -> Boolean
    )

    // Computed rather than stored: the first sync happens while the view bar is being built, during
    // construction, and a stored (or lazily-delegated) property declared below that point is not yet
    // initialised when it runs. Four items built on demand costs nothing and cannot be broken by moving
    // this declaration around later.
    private val overlayToggles: List<OverlayToggle>
        get() = listOf(
            OverlayToggle(
                flowFieldToggle, "Show flow field",
                "Show the flow-field gradient heatmap, when the trace carries one (G11)",
                "Capture flow field"
            ) { it.flowFieldOverlays.isNotEmpty() },
            OverlayToggle(
                pathsToggle, "Show paths",
                "Show agents' planned routes, when the trace carries them (G12)",
                "Capture paths"
            ) { it.agentsWithPaths.isNotEmpty() },
            OverlayToggle(
                vectorsToggle, "Show vectors",
                "Show agents' velocity (blue) and force (orange) arrows, when the trace carries them (G10)",
                "Capture velocity\" or \"Capture force"
            ) { it.agentsWithVectors.isNotEmpty() },
            OverlayToggle(
                pulsesToggle, "Show pulses",
                "Show transient event highlights (e.g. completed deliveries), when the trace carries them",
                "Capture pulses"
            ) { it.hasMarkerPulses }
        )

    /**
     * Makes each overlay toggle say what the loaded trace actually contains.
     *
     * These toggles only decide whether to *draw* something; whether it was ever *recorded* is a separate
     * choice made before the run, on the run bar, and every one of those is off by default so an ordinary
     * run pays no capture cost. The two halves were never connected in the interface, so a trace captured
     * without routes met a ticked "Show paths" and drew nothing — indistinguishable from a broken renderer,
     * and it cost a reader of this app an afternoon deciding which it was.
     *
     * The checked state is deliberately left alone. It is the user's standing preference for when a trace
     * *does* carry the overlay, and a trace that lacks one should not silently rewrite it; the label and the
     * disabled control are what carry the news.
     */
    private fun syncOverlayToggles() {
        val replay = canvas.replay
        for (t in overlayToggles) {
            val present = replay != null && t.present(replay)
            t.box.isEnabled = present
            t.box.text = if (replay == null || present) t.label else "${t.label} (not captured)"
            // Before anything is loaded there is no trace to make a claim about, so the toggle keeps its
            // ordinary description and is merely inert. "(not captured)" is reserved for the case that
            // actually misleads: a loaded trace that carries nothing for this overlay.
            t.box.toolTipText = if (replay == null || present) t.tip else
                "This trace carries none. Tick \"${t.captureBox}\" on the run bar before simulating."
        }
    }

    /** A directory created if missing, so the chooser opens *there* (not the user home). */
    private fun ensuredDir(p: Path): java.io.File {
        java.nio.file.Files.createDirectories(p)
        return p.toFile()
    }

    private fun browseTrace() {
        val chooser = JFileChooser(ensuredDir(app.tracesDir)).apply {
            dialogTitle = "Open animation trace (.atf)"
            fileFilter = FileNameExtensionFilter("Animation trace (*.atf)", "atf")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val path = chooser.selectedFile.toPath()
        withUpdating {
            if ((0 until traceCombo.itemCount).none { traceCombo.getItemAt(it).path == path }) traceCombo.addItem(TraceItem(path))
            selectComboItem(traceCombo) { it.path == path }
        }
        loadTrace(path)
    }

    private fun browseLayout() {
        val chooser = JFileChooser(ensuredDir(app.layoutsDir)).apply {
            dialogTitle = "Open layout"
            fileFilter = FileNameExtensionFilter("Animation layout (*.lay.json)", "json")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val choice = LayoutChoice.Saved(chooser.selectedFile.toPath())
        withUpdating { layoutCombo.addItem(choice); layoutCombo.selectedItem = choice }
        applyLayout()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private inline fun withUpdating(block: () -> Unit) {
        val prev = updating; updating = true
        try { block() } finally { updating = prev }
    }

    private fun <T> selectComboItem(combo: JComboBox<T>, match: (T) -> Boolean) {
        for (i in 0 until combo.itemCount) if (match(combo.getItemAt(i))) { combo.selectedIndex = i; return }
    }

    /**
     * Writes what is currently loaded — this trace, through this layout — as one self-contained web page.
     *
     * The Replay tab is the right home for it because it already holds exactly the two inputs an export
     * needs, and because this is where a layout stops being a work in progress: whatever was polished on
     * the Layout tab and paired with a trace here is what the page will show. The result needs no server
     * and no install, so it can be mailed to someone or posted to a course site as it stands.
     */
    private fun exportHtml() {
        val header = cachedHeader
        if (header == null || cachedEvents.isEmpty()) {
            showError("Load a trace first, then export what you see.")
            return
        }
        val exporter = SelfContainedHtmlExporter.bundled()
        if (exporter == null) {
            showError(SelfContainedHtmlExporter.MISSING_PLAYER_MESSAGE)
            return
        }
        val suggested = (currentTraceFile?.fileName?.toString() ?: "animation")
            .removeSuffix(".gz").removeSuffix(".atf").trim('.')
        // output/, not traces/: an export is something the app produces for the user to keep, and aiming
        // the Save dialog at the folder holding its own input invites the page to be written in among the
        // .atf files it was made from.
        val chooser = JFileChooser(ensuredDir(app.outputDir)).apply {
            dialogTitle = "Export animation to HTML"
            selectedFile = java.io.File(ensuredDir(app.outputDir), "$suggested.html")
        }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val out = chooser.selectedFile.toPath()

        // Export the layout actually on screen, not whichever file happens to be selected: the user may
        // have auto-laid-out or edited it since, and the page should match what they are looking at.
        val layout = canvas.replay?.layout
        val layoutFile = layout?.let {
            java.nio.file.Files.createTempFile("ksl-export-", ".lay.json").also { tmp -> it.writeToFile(tmp) }
        }
        try {
            val report = exporter.export(
                trace = currentTraceFile ?: return,
                layout = layoutFile,
                out = out,
                title = suggested
            )
            JOptionPane.showMessageDialog(
                this,
                "Exported to:\n$out\n\n${report.summary()}\n\n" +
                    "This page is self-contained - it needs no server and no KSL install.",
                "Export complete",
                JOptionPane.INFORMATION_MESSAGE
            )
        } catch (e: Exception) {
            showError("Export failed: ${e.message}")
        } finally {
            layoutFile?.let { runCatching { java.nio.file.Files.deleteIfExists(it) } }
        }
    }

    private fun showError(message: String) =
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)

    /** A trace choice; displays as the file name. */
    private data class TraceItem(val path: Path) { override fun toString(): String = path.fileName.toString() }

    /** A layout choice in the picker. */
    private sealed class LayoutChoice {
        object Active : LayoutChoice() { override fun toString() = "Active layout (editing)" }
        object AutoLayout : LayoutChoice() { override fun toString() = "Auto layout" }
        data class Saved(val path: Path) : LayoutChoice() { override fun toString(): String = path.fileName.toString() }
    }

    // ── Test hooks (headless) ───────────────────────────────────────────────────────

    /** Loads [path] and returns immediately (the EDT-free path used by tests). */
    internal fun loadTraceForTest(path: Path) = loadTrace(path)

    /** Selects the Auto-layout choice and re-renders. */
    internal fun selectAutoLayoutForTest() {
        withUpdating { selectComboItem(layoutCombo) { it == LayoutChoice.AutoLayout } }
        applyLayout()
    }

    /** Selects a saved layout by file name (must be present in the picker) and re-renders. */
    internal fun selectSavedLayoutForTest(fileName: String) {
        withUpdating { selectComboItem(layoutCombo) { it is LayoutChoice.Saved && it.path.fileName.toString() == fileName } }
        applyLayout()
    }

    /** The element-binding counts of the layout currently rendered (resources to queues), for swap checks. */
    internal fun renderedLayoutSizeForTest(): Pair<Int, Int> =
        canvas.replay?.layout?.let { it.resources.size to it.queues.size } ?: (0 to 0)

    /** Names offered in the trace picker. */
    internal fun traceChoicesForTest(): List<String> = (0 until traceCombo.itemCount).map { traceCombo.getItemAt(it).toString() }

    /** The compatibility read-out text. */
    internal fun compatibilityTextForTest(): String = compatLabel.text

    /** Whether the Load button is enabled (true once a trace is selectable). */
    internal fun loadEnabledForTest(): Boolean = loadButton.isEnabled

    /** The loaded-state read-out text. */
    internal fun loadedTextForTest(): String = loadedLabel.text

    /** The replay canvas (10.9 view-bar tests). */
    internal fun previewCanvasForTest(): SimulationCanvas = canvas

    /** Clicks the grid toggle, as a user would. */
    internal fun clickGridForTest() = gridToggle.doClick()

    /** Clicks the pan toggle, as a user would. */
    internal fun clickPanForTest() = panToggle.doClick()

    /** Rescan folders, select the trace with [fileName], and press Load — the explicit user flow. */
    internal fun loadByNameForTest(fileName: String) {
        rescan()
        withUpdating { selectComboItem(traceCombo) { it.path.fileName.toString() == fileName } }
        syncLoadEnabled()
        onLoad()
    }

    /** Start directories the Browse choosers open in (created if missing) — should be the model's folders. */
    /** Each overlay toggle as the user sees it: label, and whether it can be clicked. */
    internal fun overlayTogglesForTest(): List<Triple<String, Boolean, String>> =
        overlayToggles.map { Triple(it.box.text, it.box.isEnabled, it.box.toolTipText ?: "") }

    internal fun exportChooserDirForTest(): Path = ensuredDir(app.outputDir).toPath()

    internal fun traceChooserDirForTest(): Path = ensuredDir(app.tracesDir).toPath()
    internal fun layoutChooserDirForTest(): Path = ensuredDir(app.layoutsDir).toPath()
}
