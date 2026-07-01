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

import kotlinx.coroutines.launch
import ksl.animation.AnchorKind
import ksl.animation.AnchorRef
import ksl.animation.AnimationTraceHeader
import ksl.animation.ElementKind
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ReplayModel
import ksl.app.swing.animation.view.SimulationCanvas
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The **Layout** tab (9F.3): authors *how* a trace is drawn, editing the controller's active layout
 * document. Left side — a document toolbar (New / Starter / Open / Save / Save As), canvas-size fields, a
 * per-kind element list (placed/unplaced, from the inventory) with Add / Remove and X/Y move fields, and a
 * live validation strip. Right side — a live [SimulationCanvas] preview of the layout (rendered with no
 * trace, so static elements show). Headless-constructible; every control routes through the controller's
 * layout mutators ([LayoutEditing]).
 */
class LayoutPanel(private val controller: AnimationAppController) : JPanel(BorderLayout()) {

    private val canvas = SimulationCanvas().apply { showGrid = true }
    private val validationLabel = JLabel()
    private val legendLabel = JLabel(" ")
    private val canvasTitle = JTextField(14)
    private val canvasWidth = JTextField(6)
    private val canvasHeight = JTextField(6)
    private val coordLabel = JLabel("x = —, y = —")
    private val editors = mutableListOf<KindEditor>()
    private val bgListModel = javax.swing.DefaultListModel<String>()
    private val pathListModel = javax.swing.DefaultListModel<String>()
    private val pathNameField = JTextField(8)
    // Object styles strip (batch 3): edit the selected entity/agent type's glyph (shape/color/size/image).
    private val styleShape = JComboBox(ksl.animation.LayoutShape.entries.toTypedArray())
    private val styleColor = ColorSwatchField("#1f77b4")
    private val styleSize = JTextField(4).apply { text = "12" }
    private val styleImage = JTextField(12).apply { isEditable = false } // chosen image glyph (10.7c), blank = none
    // Process colors (10.1e): edit the selected process's tint color.
    private val processColorField = ColorSwatchField("#ff7f0e")
    // Editable tables (batch 3): one row per discovered entity type / process; edit the selected row in the strip.
    // Object-style rows: the types actually seen animating in the last trace once a run exists (so control-only
    // entities aren't offered), else the structural entity types from the inventory; recomputed on a new run.
    private var styleTypeNames: List<String> = controller.objectStyleTypeNames()
    private val processNames: List<String> by lazy { controller.inventory.entityTypes.flatMap { it.processes }.map { it.name }.distinct() }
    private var styleTableModel: javax.swing.table.AbstractTableModel? = null
    private var procTableModel: javax.swing.table.AbstractTableModel? = null
    private val conveyorListModel = javax.swing.DefaultListModel<String>()
    private val convWidth = JTextField(4)
    private val convColor = ColorSwatchField("#1f77b4")
    private val convArrows = javax.swing.JCheckBox("direction arrows")
    private val storageListModel = javax.swing.DefaultListModel<String>()
    private val storageStyleCombo = JComboBox(ksl.animation.StorageStyle.entries.toTypedArray())
    private val stateColorListModel = javax.swing.DefaultListModel<String>()
    private val agentState = JTextField(10)
    private val agentColor = ColorSwatchField("#d62728")
    private val spaceListModel = javax.swing.DefaultListModel<String>()
    private val obstacleListModel = javax.swing.DefaultListModel<String>()

    // Click-to-place state (10.3). Declared before `init` so buildElementToolbar() can register buttons.
    private var placeArmedKind: ElementKind? = null
    private var placeArmedName: String? = null
    // Canvas selection set (P4). Declared before `init` so refreshAll()'s updateSelectionHandles() can read it.
    private val selected = LinkedHashSet<Pair<ElementKind, String>>()
    private val toolButtons = mutableMapOf<ElementKind, JButton>()
    private var pathButton: JButton? = null
    private var bgButton: JButton? = null
    private var shapeButton: JButton? = null
    private var clockButton: JButton? = null
    private var conveyorButton: JButton? = null
    private var storageButton: JButton? = null
    private val defaultButtonBorder = javax.swing.UIManager.getBorder("Button.border")
    private val armedButtonBorder = BorderFactory.createLineBorder(java.awt.Color(0x1A73E8), 2)

    /** The per-kind element tables + styling tabs (populates [editors]); hosted in the Elements dialog (10.3). */
    private val editorTabs: JComponent = buildEditorTabs()
    private var elementsDialog: javax.swing.JDialog? = null

    init {
        // New paradigm (10.3): the canvas is the dominant area; the element tables live in a side dialog
        // (⊞ Elements…), with the document toolbar + the Add ▸ placement toolbar stacked above the canvas.
        add(JPanel(BorderLayout()).apply {
            add(buildToolbar(), BorderLayout.NORTH)
            add(buildElementToolbar(), BorderLayout.SOUTH)   // Add ▸ click-to-place tools
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply {
            add(buildViewOptions(), BorderLayout.NORTH)
            add(canvas, BorderLayout.CENTER)
            add(buildSouth(), BorderLayout.SOUTH)            // canvas size + live validation strip
        }, BorderLayout.CENTER)
        canvas.preferredSize = Dimension(900, 620)
        installDragToMove()
        // Esc cancels an armed placement (works wherever focus is within the panel).
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke("ESCAPE"), "cancelPlace")
        actionMap.put("cancelPlace", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { cancelPlacement() }
        })
        refreshAll()
        // Reflect external layout changes (open/new/scaffold from the menu) live.
        controller.edtScope.launch { controller.layout.collect { refreshAll() } }
        // Fold the latest trace's entity/agent types into the object-style rows when a run produces one (C3).
        controller.edtScope.launch { controller.lastTraceFile.collect { recomputeStyleTypeNames() } }
    }

    /** Object-style rows = inventory entity types ∪ the last trace's entity/agent types; refreshes the table (C3). */
    private fun recomputeStyleTypeNames() {
        styleTypeNames = controller.objectStyleTypeNames()
        refreshObjectStyles()
    }

    /** Opens (creating once) the Elements dialog that hosts the per-kind tables + styling tabs (10.3). */
    private fun showElementsDialog() {
        val dlg = elementsDialog ?: javax.swing.JDialog(javax.swing.SwingUtilities.getWindowAncestor(this), "Elements").apply {
            defaultCloseOperation = javax.swing.WindowConstants.HIDE_ON_CLOSE
            contentPane.add(editorTabs)
            setSize(560, 640)
            setLocationRelativeTo(this@LayoutPanel)
        }.also { elementsDialog = it }
        dlg.isVisible = true
    }

    private var panMode = false

    /** Preview view-options: grid toggle, zoom controls, a pan toggle, and a live coordinate read-out. */
    private fun buildViewOptions(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(javax.swing.JCheckBox("Show grid", true).apply { addActionListener { canvas.showGrid = isSelected } })
        add(JButton("Zoom +").apply { addActionListener { canvas.zoomIn() } })
        add(JButton("Zoom −").apply { addActionListener { canvas.zoomOut() } })
        add(JButton("Fit").apply { toolTipText = "Reset zoom & pan to fit"; addActionListener { canvas.resetView() } })
        add(javax.swing.JToggleButton("Pan").apply {
            toolTipText = "When on, drag to pan; when off, drag to move elements"
            addActionListener { panMode = isSelected; canvas.panEnabled = panMode }
        })
        add(coordLabel)
    }

    // ── Element toolbar: click-to-place (10.3) ────────────────────────────────────

    /**
     * Names placeable as [kind]. Network stations and spatial locations are now distinct tools (Phase 6):
     * STATION offers `inventory.namesOf(STATION)`, LOCATION offers the model's locations — no longer merged.
     */
    private fun placeableNames(kind: ElementKind): List<String> = controller.inventory.namesOf(kind)

    /** Toolbar/dialog label for [kind]. */
    private fun placeableLabel(kind: ElementKind): String = kind.label()

    /** "Add ▸" toolbar: one button per placeable element kind the model exposes. */
    private fun buildElementToolbar(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        add(JLabel("Add ▸"))
        for (kind in SUPPORTED_LAYOUT_KINDS) {
            if (placeableNames(kind).isEmpty()) continue
            val label = placeableLabel(kind)
            val btn = JButton(label).apply {
                toolTipText = "Pick a $label, then click the canvas to place it (Esc to cancel)"
                // Queues need orientation+length, so they use a two-click head→tail flow (10.4); others single-click.
                addActionListener { if (kind == ElementKind.QUEUE) armQueuePlacement() else armPlacement(kind) }
            }
            toolButtons[kind] = btn
            add(btn)
        }
        // Path is authored from placed stations/locations (not an inventory element), so it gets a dedicated tool.
        if (placeableNames(ElementKind.STATION).isNotEmpty() || placeableNames(ElementKind.LOCATION).isNotEmpty()) {
            val pb = JButton("Path").apply {
                toolTipText = "Click a from-anchor, then a to-anchor (placed stations/locations), dropping waypoints between; double-click to finish (Esc to cancel)"
                addActionListener { armPathPlacement() }
            }
            pathButton = pb
            add(pb)
        }
        // Add Image: choose a file, then click-drag a rectangle on the canvas to size/place it (10.4, §6.5).
        val bb = JButton("Add Image").apply {
            toolTipText = "Choose an image, then drag a rectangle to place it behind everything. Afterward: click to select, drag to move, corner handle to resize, Delete to remove."
            addActionListener { armBackgroundPlacement() }
        }
        bgButton = bb
        add(bb)
        // Conveyor: pick a conveyor (from the inventory), set style, and route a segment's waypoints (10.5d, §6.7).
        if (controller.inventory.conveyorInfos.isNotEmpty()) {
            val cb = JButton("Conveyor").apply {
                toolTipText = "Place a conveyor belt and route a segment's waypoints against placed stations (Esc to cancel)"
                addActionListener { openAddConveyorDialog() }
            }
            conveyorButton = cb
            add(cb)
        }
        // Storage: name a delay/type holding area, pick a style, then drag a rectangle to place it (#15).
        val sb = JButton("Storage").apply {
            toolTipText = "Add a storage (named delay / type holding area): pick a name + style, then drag a rectangle (Esc to cancel)"
            addActionListener { openAddStorageDialog() }
        }
        storageButton = sb
        add(sb)
        // Text: click-to-place a free-floating text annotation. Afterward it's first-class (select/move/edit/delete).
        val shb = JButton("Text").apply {
            toolTipText = "Place a text note (click). Afterward: click to select, drag to move, double-click to edit, Delete to remove. For richer graphics, import a background image."
            addActionListener { openTextDialog() }
        }
        shapeButton = shb
        add(shb)
        // Clock: click-to-place a simulation-time clock. Like Text, it's first-class (select/move/edit/delete).
        val clkb = JButton("Clock").apply {
            toolTipText = "Place a simulation-time clock (click): set label/format/size. Afterward: click to select, drag to move, double-click to edit, Delete to remove."
            addActionListener { openClockDialog() }
        }
        clockButton = clkb
        add(clkb)
    }

    /** Choose which (unplaced) element of [kind] to place, then arm the canvas for a placement click. */
    private fun armPlacement(kind: ElementKind) {
        if (placeArmedKind == kind) { disarmPlace(); return } // clicking the armed tool again cancels
        val label = placeableLabel(kind)
        val unplaced = placeableNames(kind).filter { controller.layout.value?.isPlaced(kind, it) != true }
        val name = when {
            unplaced.isEmpty() -> {
                JOptionPane.showMessageDialog(this, "All $label elements are already placed."); return
            }
            unplaced.size == 1 -> unplaced.first()
            else -> JOptionPane.showInputDialog(
                this, "Place which $label?", "Add $label",
                JOptionPane.QUESTION_MESSAGE, null, unplaced.toTypedArray(), unplaced.first()
            ) as? String ?: return
        }
        // A movable resource with a placed home base drops there directly (C5); else fall back to click-to-place.
        if (kind == ElementKind.MOVABLE_RESOURCE && controller.placeMoverAtHome(name)) { afterEdit(); return }
        armPlace(kind, name)
    }

    /** Highlights [kind]'s toolbar button as the armed tool (or clears all highlights when null). */
    private fun highlightTool(kind: ElementKind?) {
        toolButtons.values.forEach { it.border = defaultButtonBorder }
        pathButton?.border = defaultButtonBorder
        bgButton?.border = defaultButtonBorder
        conveyorButton?.border = defaultButtonBorder
        storageButton?.border = defaultButtonBorder
        shapeButton?.border = defaultButtonBorder
        clockButton?.border = defaultButtonBorder
        kind?.let { toolButtons[it]?.border = armedButtonBorder }
    }

    /** Clears all placement arm-state so a new arm is exclusive (single-click, queue, path, bg, conveyor, storage). */
    private fun clearArmState() {
        placeArmedKind = null; placeArmedName = null
        queueArm = null; queueHead = null
        pathArmName = null; pathFrom = null; pathWaypoints.clear()
        bgArmRef = null; bgStart = null
        conveyorRouteName = null; conveyorRouteSeg = -1; conveyorWaypoints.clear()
        storageArm = null; storageStart = null
        textArm = false
        clockArm = false
    }

    private val crosshair = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.CROSSHAIR_CURSOR)

    private fun armPlace(kind: ElementKind, name: String) {
        clearArmState()
        placeArmedKind = kind
        placeArmedName = name
        canvas.cursor = crosshair
        coordLabel.text = "Click to place $name  (Esc to cancel)"
        highlightTool(kind)
    }

    private fun disarmPlace() {
        placeArmedKind = null
        placeArmedName = null
        canvas.cursor = java.awt.Cursor.getDefaultCursor()
        highlightTool(null)
    }

    // ── Queue two-click placement: head → tail (10.4) ─────────────────────────────

    private data class QueueArm(val name: String, val spacing: Double, val maxShown: Int)
    private var queueArm: QueueArm? = null
    private var queueHead: Pair<Double, Double>? = null

    /** Toolbar Queue tool: pick an unplaced queue + spacing/max, then arm the head→tail clicks. */
    private fun armQueuePlacement() {
        if (queueArm != null) { disarmQueue(); return } // re-clicking the armed tool cancels
        val unplaced = controller.inventory.namesOf(ElementKind.QUEUE)
            .filter { controller.layout.value?.isPlaced(ElementKind.QUEUE, it) != true }
        if (unplaced.isEmpty()) { JOptionPane.showMessageDialog(this, "All queues are already placed."); return }
        val nameCombo = javax.swing.JComboBox(unplaced.toTypedArray())
        val spacing = JTextField("12", 6)
        val maxShown = JTextField("10", 6)
        val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
            add(JLabel("Queue")); add(nameCombo)
            add(JLabel("spacing")); add(spacing)
            add(JLabel("max shown")); add(maxShown)
        }
        val choice = JOptionPane.showConfirmDialog(
            this, form, "Add Queue", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (choice != JOptionPane.OK_OPTION) return
        armQueue(
            nameCombo.selectedItem as String,
            spacing.text.trim().toDoubleOrNull() ?: 12.0,
            maxShown.text.trim().toIntOrNull() ?: 10
        )
    }

    private fun armQueue(name: String, spacing: Double, maxShown: Int) {
        clearArmState()
        queueArm = QueueArm(name, spacing, maxShown)
        queueHead = null
        canvas.cursor = crosshair
        coordLabel.text = "Click the HEAD (front, nearest service)…  (Esc to cancel)"
        highlightTool(ElementKind.QUEUE)
    }

    private fun disarmQueue() {
        queueArm = null
        queueHead = null
        canvas.cursor = java.awt.Cursor.getDefaultCursor()
        highlightTool(null)
    }

    /** Cancels any armed placement (single-click, queue, path, background, conveyor, …) — what Esc triggers. */
    private fun cancelPlacement() {
        if (placeArmedName != null) disarmPlace()
        if (queueArm != null) disarmQueue()
        if (pathArmName != null) disarmPath()
        if (bgArmRef != null) disarmBackground()
        if (conveyorRouteName != null) disarmConveyorRoute()
        if (storageArm != null) disarmStorage()
        if (textArm) disarmText()
    }

    // ── Conveyor tool: pick a conveyor, then route a segment's waypoints (10.5d, §6.7) ──

    private var conveyorRouteName: String? = null
    private var conveyorRouteSeg: Int = -1
    private val conveyorWaypoints = mutableListOf<ksl.animation.LayoutPoint>()

    /** Straight (waypoint-free) segment routes for [conveyor], from its inventory structure. */
    private fun straightSegmentsFor(conveyor: String): List<ksl.animation.SegmentRoute> =
        controller.inventory.conveyorInfos.firstOrNull { it.name == conveyor }?.segments
            ?.map { ksl.animation.SegmentRoute(it.entryLocation, it.exitLocation) } ?: emptyList()

    /** Toolbar Conveyor tool: pick a conveyor + style, create the (straight) belt, and optionally route a segment. */
    private fun openAddConveyorDialog() {
        if (conveyorRouteName != null) { disarmConveyorRoute(); return }
        val infos = controller.inventory.conveyorInfos
        if (infos.isEmpty()) { JOptionPane.showMessageDialog(this, "This model has no conveyors."); return }
        val nameCombo = javax.swing.JComboBox(infos.map { it.name }.toTypedArray())
        val width = JTextField("8", 5)
        val color = JTextField("#888888", 8)
        val showDir = javax.swing.JCheckBox("direction arrows", true)
        val segCombo = javax.swing.JComboBox<String>()
        fun reloadSegments() {
            segCombo.removeAllItems(); segCombo.addItem("(none — straight)")
            infos.firstOrNull { it.name == nameCombo.selectedItem }?.segments
                ?.forEachIndexed { i, s -> segCombo.addItem("${i + 1}. ${s.entryLocation} → ${s.exitLocation}") }
        }
        reloadSegments(); nameCombo.addActionListener { reloadSegments() }
        val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
            add(JLabel("Conveyor")); add(nameCombo)
            add(JLabel("width")); add(width)
            add(JLabel("color")); add(color)
            add(JLabel("")); add(showDir)
            add(JLabel("route segment")); add(segCombo)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Add Conveyor", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val name = nameCombo.selectedItem as String
        controller.setConveyorLayout(ksl.animation.ConveyorLayoutElement(
            conveyorName = name, segments = straightSegmentsFor(name),
            width = width.text.trim().toDoubleOrNull() ?: 8.0,
            color = color.text.trim().ifBlank { "#888888" },
            showDirection = showDir.isSelected
        ))
        afterEdit()
        val seg = segCombo.selectedIndex - 1 // 0 is "(none)"
        if (seg >= 0) armConveyorRoute(name, seg)
    }

    private fun armConveyorRoute(name: String, segmentIndex: Int) {
        clearArmState()
        conveyorRouteName = name; conveyorRouteSeg = segmentIndex
        canvas.cursor = crosshair
        coordLabel.text = "Click waypoints for the segment; double-click to finish  (Esc to cancel)"
        highlightTool(null); conveyorButton?.border = armedButtonBorder
    }

    private fun addConveyorWaypoint(wx: Double, wy: Double) {
        conveyorWaypoints.add(ksl.animation.LayoutPoint(wx, wy))
        coordLabel.text = "Segment waypoints: ${conveyorWaypoints.size}  (double-click to finish)"
    }

    private fun finishConveyorRoute() {
        val name = conveyorRouteName ?: return
        controller.setConveyorSegmentWaypoints(name, conveyorRouteSeg, conveyorWaypoints.toList())
        disarmConveyorRoute(); afterEdit()
    }

    private fun disarmConveyorRoute() {
        conveyorRouteName = null; conveyorRouteSeg = -1; conveyorWaypoints.clear()
        canvas.cursor = java.awt.Cursor.getDefaultCursor()
        highlightTool(null)
    }

    /** The conveyor segment (name, index) whose routed belt passes within [radius] of world ([wx],[wy]), or null. */
    private fun nearestConveyorSegment(wx: Double, wy: Double, radius: Double): Pair<String, Int>? {
        val layout = controller.layout.value ?: return null
        var best: Pair<String, Int>? = null
        var bestDist = radius
        for (conv in layout.conveyors) {
            conv.segments.forEachIndexed { i, seg ->
                val a = layout.positionOf(ElementKind.LOCATION, seg.entryLocation) ?: layout.positionOf(ElementKind.STATION, seg.entryLocation)
                val b = layout.positionOf(ElementKind.LOCATION, seg.exitLocation) ?: layout.positionOf(ElementKind.STATION, seg.exitLocation)
                if (a != null && b != null) {
                    val pts = listOf(a.x to a.y) + seg.waypoints.map { it.x to it.y } + listOf(b.x to b.y)
                    val d = distToPolyline(pts, wx, wy)
                    if (d <= bestDist) { bestDist = d; best = conv.conveyorName to i }
                }
            }
        }
        return best
    }

    /** Min distance from ([px],[py]) to the polyline [pts]. */
    private fun distToPolyline(pts: List<Pair<Double, Double>>, px: Double, py: Double): Double {
        var d = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) d = minOf(d, distToSegment(px, py, pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second))
        return d
    }

    private fun distToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 1e-9) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    // ── Background image: choose file, then click-drag a rectangle (10.4, §6.5) ────

    private var bgArmRef: String? = null
    private var bgStart: Pair<Double, Double>? = null

    /** Toolbar Background tool: choose an image file, then arm a click-drag rectangle to size/place it. */
    private fun armBackgroundPlacement() {
        if (bgArmRef != null) { disarmBackground(); return } // re-clicking the armed tool cancels
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "Choose background image"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif")
        }
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
        armBackground(importImageRef(chooser.selectedFile))
    }

    /**
     * Imports [file] into the workspace's `layouts/images/` folder and returns a layout-relative reference
     * (`images/<name>`), so the layout + its images form a portable, shareable bundle (the renderer resolves
     * relative refs against the layout file's directory). Falls back to the absolute path if the copy fails.
     */
    private fun importImageRef(file: java.io.File): String = runCatching {
        val imagesDir = controller.layoutsDir.resolve("images")
        java.nio.file.Files.createDirectories(imagesDir)
        val dest = imagesDir.resolve(file.name)
        java.nio.file.Files.copy(file.toPath(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        "images/${file.name}"
    }.getOrDefault(file.absolutePath)

    private fun armBackground(imageRef: String) {
        clearArmState()
        bgArmRef = imageRef
        canvas.cursor = crosshair
        coordLabel.text = "Drag a rectangle to size & place the background  (Esc to cancel)"
        highlightTool(null); bgButton?.border = armedButtonBorder
    }

    private fun disarmBackground() {
        bgArmRef = null
        bgStart = null
        canvas.cursor = java.awt.Cursor.getDefaultCursor()
        highlightTool(null)
    }

    /** Adds the chosen background image spanning the dragged rectangle (ignoring a negligible drag). */
    private fun placeBackgroundRect(wx1: Double, wy1: Double, wx2: Double, wy2: Double) {
        val ref = bgArmRef ?: return
        val x1 = minOf(wx1, wx2); val y1 = minOf(wy1, wy2)
        val x2 = maxOf(wx1, wx2); val y2 = maxOf(wy1, wy2)
        if (x2 - x1 < 2.0 || y2 - y1 < 2.0) { disarmBackground(); return } // a stray click, not a rectangle
        controller.addBackgroundImage(ref, x1, y1, x2, y2) // BackgroundElements always render behind everything
        disarmBackground(); afterEdit()
    }

    // ── Text tool: click-to-place a free-floating text annotation (rich graphics are imported as images) ──

    private var textArm: Boolean = false
    private var shapeColor: String = "#444444"
    private var shapeText: String = "Label"
    private var shapeFontSize: Double = 14.0
    private var shapeFontFamily: String = "SansSerif"
    // Clock tool defaults (the model-less time-display widget; mirrors the Text tool above).
    private var clockArm: Boolean = false
    private var clockLabel: String = "Time"
    private var clockFormat: String = "0.0"
    private var clockFontSize: Double = 14.0

    /** Logical font families, guaranteed available on every JVM (cross-platform). */
    private val fontFamilies = arrayOf("SansSerif", "Serif", "Monospaced")

    /**
     * A stateful color swatch button for the always-visible edit strips: shows [hex] as its background, opens a
     * JColorChooser on click, and can be reprogrammed via `hex = …` when the selected row changes. (The one-shot
     * dialogs use `colorPicker` instead.)
     */
    private inner class ColorSwatchField(initial: String) : JButton() {
        var hex: String = initial
            set(value) { field = value; background = runCatching { java.awt.Color.decode(value) }.getOrDefault(java.awt.Color.BLACK) }
        init {
            isOpaque = true
            preferredSize = java.awt.Dimension(48, 20)
            toolTipText = "Click to choose a color"
            background = runCatching { java.awt.Color.decode(initial) }.getOrDefault(java.awt.Color.BLACK)
            addActionListener {
                val picked = javax.swing.JColorChooser.showDialog(this, "Choose color", background)
                if (picked != null) hex = String.format("#%02x%02x%02x", picked.red, picked.green, picked.blue)
            }
        }
    }

    /** A swatch button that opens a color picker; returns the button plus a getter for the chosen hex color. */
    private fun colorPicker(initialHex: String): Pair<JButton, () -> String> {
        var hex = initialHex
        val btn = JButton().apply {
            isOpaque = true
            background = runCatching { java.awt.Color.decode(hex) }.getOrDefault(java.awt.Color.BLACK)
            preferredSize = java.awt.Dimension(48, 20)
            toolTipText = "Click to choose a color"
            addActionListener {
                val picked = javax.swing.JColorChooser.showDialog(this, "Choose color", background)
                if (picked != null) { hex = String.format("#%02x%02x%02x", picked.red, picked.green, picked.blue); background = picked }
            }
        }
        return btn to { hex }
    }

    /** Toolbar Text tool: enter the text, font, size, and color, then click the canvas to place it (Esc to cancel). */
    private fun openTextDialog() {
        if (textArm) { disarmText(); return } // re-clicking the armed tool cancels
        val text = JTextField(shapeText, 14)
        val family = JComboBox(fontFamilies).apply { selectedItem = shapeFontFamily }
        val size = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(shapeFontSize, 4.0, 400.0, 1.0))
        val (colorBtn, colorOf) = colorPicker(shapeColor)
        val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
            add(JLabel("text")); add(text)
            add(JLabel("font")); add(family)
            add(JLabel("size")); add(size)
            add(JLabel("color")); add(colorBtn)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Add text", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        shapeText = text.text.trim().ifBlank { "Label" }
        shapeFontFamily = family.selectedItem as String
        shapeFontSize = (size.value as Number).toDouble()
        shapeColor = colorOf()
        armText()
    }

    private fun armText() {
        clearArmState()
        textArm = true
        canvas.cursor = crosshair
        coordLabel.text = "Click to place the text  (Esc to cancel)"
        highlightTool(null); shapeButton?.border = armedButtonBorder
    }

    private fun disarmText() {
        textArm = false
        canvas.cursor = java.awt.Cursor.getDefaultCursor(); highlightTool(null)
    }

    /** Places a text annotation at the clicked point. It becomes a first-class shape (select/move/edit/delete). */
    private fun placeShapeText(wx: Double, wy: Double) {
        if (!textArm) return
        controller.addBackgroundText(wx, wy, shapeText, shapeColor, shapeFontSize, shapeFontFamily)
        disarmText(); afterEdit()
    }

    // ── Storage tool (#15): name a delay/type, pick a style, then drag a rectangle to place the holding area ──

    private data class StorageArm(
        val name: String, val style: ksl.animation.StorageStyle, val spacing: Double,
        val maxShown: Int, val capacity: Int, val byType: Boolean, val label: String?
    )
    private var storageArm: StorageArm? = null
    private var storageStart: Pair<Double, Double>? = null

    /** Toolbar Storage tool: collect the suspension name + style + options, then arm a click-drag rectangle. */
    private fun openAddStorageDialog() {
        if (storageArm != null) { disarmStorage(); return } // re-clicking the armed tool cancels
        // Suspension names aren't in the inventory (runtime), so seed with entity types (unnamed delays bind to the
        // type name) plus any explicitly-named suspensions harvested from the last run's trace, and allow free text.
        val storageNames = (controller.inventory.namesOf(ElementKind.ENTITY_TYPE) +
            controller.suspensionNamesFromLastTrace()).distinct()
        val nameCombo = JComboBox(storageNames.toTypedArray()).apply { isEditable = true; selectedItem = "" }
        val styleCombo = JComboBox(ksl.animation.StorageStyle.entries.toTypedArray())
        val spacing = JTextField("14", 5); val maxShown = JTextField("30", 5); val capacity = JTextField("0", 5)
        val byType = javax.swing.JCheckBox("by entity type (unnamed delays)", true)
        val labelField = JTextField(12)
        val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
            add(JLabel("suspension / type")); add(nameCombo)
            add(JLabel("style")); add(styleCombo)
            add(JLabel("spacing")); add(spacing)
            add(JLabel("max shown")); add(maxShown)
            add(JLabel("capacity (0=∞)")); add(capacity)
            add(JLabel("")); add(byType)
            add(JLabel("label")); add(labelField)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Add Storage", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val name = ((nameCombo.editor.item ?: nameCombo.selectedItem) as? String)?.trim().orEmpty()
        if (name.isBlank()) { JOptionPane.showMessageDialog(this, "A storage needs a suspension or type name."); return }
        armStorage(StorageArm(
            name, styleCombo.selectedItem as ksl.animation.StorageStyle,
            spacing.text.trim().toDoubleOrNull() ?: 14.0, maxShown.text.trim().toIntOrNull() ?: 30,
            capacity.text.trim().toIntOrNull() ?: 0, byType.isSelected, labelField.text.trim().takeIf { it.isNotEmpty() }
        ))
    }

    private fun armStorage(arm: StorageArm) {
        clearArmState()
        storageArm = arm
        canvas.cursor = crosshair
        coordLabel.text = "Drag a rectangle to size & place the storage \"${arm.name}\"  (Esc to cancel)"
        highlightTool(null); storageButton?.border = armedButtonBorder
    }

    private fun disarmStorage() {
        storageArm = null; storageStart = null
        canvas.cursor = java.awt.Cursor.getDefaultCursor()
        highlightTool(null)
    }

    /** Adds the armed storage spanning the dragged rectangle (ignoring a negligible drag). */
    private fun placeStorageRect(wx1: Double, wy1: Double, wx2: Double, wy2: Double) {
        val a = storageArm ?: return
        val x1 = minOf(wx1, wx2); val y1 = minOf(wy1, wy2); val x2 = maxOf(wx1, wx2); val y2 = maxOf(wy1, wy2)
        if (x2 - x1 < 4.0 || y2 - y1 < 4.0) { disarmStorage(); return } // a stray click, not a rectangle
        controller.addStorage(a.name, x1, y1, x2 - x1, y2 - y1, a.style, a.spacing, a.maxShown, a.capacity, a.byType, a.label)
        disarmStorage(); afterEdit()
    }

    // ── Functional path (Phase 6): click the FROM anchor, drop waypoints in open space, double-click the TO anchor ──

    private var pathArmName: String? = null
    private var pathFrom: AnchorRef? = null
    private val pathWaypoints = mutableListOf<ksl.animation.LayoutPoint>()

    /** Toolbar Path tool: name the path, then pick a from-anchor, optional free waypoints, and a to-anchor. */
    private fun armPathPlacement() {
        if (pathArmName != null) { disarmPath(); return } // re-clicking the armed tool cancels
        if (placedAnchorCount() < 2) {
            JOptionPane.showMessageDialog(this, "Place at least two stations/locations before routing a path."); return
        }
        val name = JOptionPane.showInputDialog(this, "Path name", "Add Path", JOptionPane.QUESTION_MESSAGE)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return
        clearArmState()
        pathArmName = name
        canvas.cursor = crosshair
        coordLabel.text = "Click the FROM anchor (a placed station/location)  (Esc to cancel)"
        highlightTool(null); pathButton?.border = armedButtonBorder
    }

    private fun placedAnchorCount(): Int {
        val l = controller.layout.value ?: return 0
        return l.stations.size + l.locations.count { it.position != null }
    }

    /** One single click: set the from-anchor if unset, else add a free waypoint (anchor clicks finish via double-click). */
    private fun onPathClick(sx: Int, sy: Int) {
        val anchor = pickAnchor(sx, sy)
        if (pathFrom == null) {
            if (anchor == null) { coordLabel.text = "Click a placed station/location to start the path"; return }
            pathFrom = anchor
            coordLabel.text = "From ${anchor.name}: click waypoints, then double-click the TO anchor"
        } else {
            if (anchor != null) return // near an anchor: ignore (the finishing double-click sets the destination)
            val w = canvas.screenToWorld(sx.toDouble(), sy.toDouble())
            pathWaypoints.add(ksl.animation.LayoutPoint(w.x, w.y))
            coordLabel.text = "From ${pathFrom!!.name}: ${pathWaypoints.size} waypoint(s); double-click the TO anchor"
        }
    }

    /** Finish at [to] (the double-clicked anchor): persist a functional path when it is a distinct destination. */
    private fun onPathFinish(to: AnchorRef?) {
        val name = pathArmName ?: return
        val from = pathFrom
        if (from != null && to != null && to != from) {
            controller.addFunctionalPath(name, from, to, pathWaypoints.toList())
            disarmPath(); afterEdit()
        } else {
            coordLabel.text = "Double-click a placed station/location (different from the start) to finish"
        }
    }

    private fun disarmPath() {
        pathArmName = null
        pathFrom = null
        pathWaypoints.clear()
        canvas.cursor = java.awt.Cursor.getDefaultCursor()
        highlightTool(null)
    }

    /** The placed anchor (location-first, then station) nearest screen ([sx], [sy]) within the grab radius, or null. */
    private fun pickAnchor(sx: Int, sy: Int): AnchorRef? {
        val layout = controller.layout.value ?: return null
        val world = canvas.screenToWorld(sx.toDouble(), sy.toDouble())
        val scale = canvas.worldTransform().scaleX.coerceAtLeast(1e-6)
        val radius = HIT_RADIUS_PX / scale
        fun dist(p: ksl.animation.LayoutPoint) = kotlin.math.hypot(p.x - world.x, p.y - world.y)
        layout.locations.filter { it.position != null }.minByOrNull { dist(it.position!!) }
            ?.takeIf { dist(it.position!!) <= radius }
            ?.let { return AnchorRef(AnchorKind.LOCATION, it.locationName) }
        return layout.stations.minByOrNull { dist(it.position) }?.takeIf { dist(it.position) <= radius }
            ?.let { AnchorRef(AnchorKind.NETWORK_STATION, it.stationName) }
    }

    /** Handle one canvas click of the queue flow: first sets the head, second derives direction and finishes. */
    private fun placeQueueClick(wx: Double, wy: Double) {
        val qa = queueArm ?: return
        val head = queueHead
        if (head == null) {
            queueHead = wx to wy
            controller.placeLayoutElement(ElementKind.QUEUE, qa.name, wx, wy) // head position
            coordLabel.text = "Click the TAIL (back) — sets direction & length"
            afterEdit()
        } else {
            val deg = (Math.toDegrees(kotlin.math.atan2(wy - head.second, wx - head.first)) % 360 + 360) % 360
            controller.setQueueProperties(qa.name, deg, qa.spacing, qa.maxShown)
            disarmQueue(); afterEdit()
        }
    }

    /** The placed element under screen point ([sx], [sy]) within the grab radius, or null. */
    private fun pickPlaced(sx: Int, sy: Int): Pair<ElementKind, String>? {
        val layout = controller.layout.value ?: return null
        val world = canvas.screenToWorld(sx.toDouble(), sy.toDouble())
        val scale = canvas.worldTransform().scaleX.coerceAtLeast(1e-6)
        return layout.pickElement(world.x, world.y, radius = HIT_RADIUS_PX / scale)
    }

    /** A small modal editor for a placed element: position plus the kind's editable properties (10.3). */
    private fun showElementEditor(kind: ElementKind, name: String) {
        val layout = controller.layout.value ?: return
        val pos = layout.positionOf(kind, name) ?: return
        val xf = JTextField(trimNum(pos.x), 6)
        val yf = JTextField(trimNum(pos.y), 6)
        val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
            add(JLabel("x")); add(xf)
            add(JLabel("y")); add(yf)
        }
        // Kind-specific properties, mirroring the Elements-dialog property strips.
        val queue = layout.queues.firstOrNull { it.queueName == name }?.takeIf { kind == ElementKind.QUEUE }
        val resource = layout.resources.firstOrNull { it.resourceName == name }?.takeIf { kind == ElementKind.RESOURCE }
        val qDir = queue?.let { JTextField(trimNum(it.growthDegrees), 6) }
        val qSpacing = queue?.let { JTextField(trimNum(it.spacing), 6) }
        val qMax = queue?.let { JTextField(it.maxShown.toString(), 6) }
        val resSize = resource?.let { JTextField(trimNum(it.size), 6) }
        val resShowValue = resource?.let { javax.swing.JCheckBox("show value (busy/capacity)", it.showValue) }
        qDir?.let { form.add(JLabel("direction°")); form.add(it) }
        qSpacing?.let { form.add(JLabel("spacing")); form.add(it) }
        qMax?.let { form.add(JLabel("max shown")); form.add(it) }
        resSize?.let { form.add(JLabel("size")); form.add(it) }
        resShowValue?.let { form.add(JLabel("")); form.add(it) }
        // Per-state resource images (10.7): each row shows the current ref with Choose…/Clear.
        val resImages = resource?.let {
            mapOf("idle" to it.idleImage, "busy" to it.busyImage, "failed" to it.failedImage, "inactive" to it.inactiveImage)
                .mapValues { (state, ref) -> imagePickerRow(form, "$state image", ref) }
        } ?: emptyMap()
        // Response/counter display form (P2): double-clicking a placed response lets you pick how it renders —
        // value / bar / plot / summary / histogram — and, for a histogram, whether it is a discrete
        // (integer-frequency) chart. Switching form moves the element between the layout's display collections.
        val isResponse = kind == ElementKind.RESPONSE || kind == ElementKind.COUNTER
        val curDisplay = if (!isResponse) null else layout.responseDisplayOf(name) ?: ResponseDisplay.VALUE
        val displayCombo = if (!isResponse) null else JComboBox(ResponseDisplay.entries.toTypedArray()).apply { selectedItem = curDisplay }
        val discreteCheck = if (!isResponse) null else javax.swing.JCheckBox("discrete (frequency)", layout.responseHistogramIsDiscrete(name))
        // Per-form styling fields (P6 + one-pass create-and-style): all forms' fields are shown, pre-filled from
        // the element when it already uses that form, else with sensible defaults — so switching the display and
        // styling it happen in a single OK (the styling for the *selected* form is applied after the switch).
        val barEl = layout.bars.firstOrNull { it.responseName == name }
        val plotEl = layout.plots.firstOrNull { it.responseName == name }
        val histEl = layout.histograms.firstOrNull { it.responseName == name }
        val barMax = if (!isResponse) null else JTextField(trimNum(barEl?.maxValue ?: 100.0), 6)
        val barColor = if (!isResponse) null else colorPicker(barEl?.color ?: "#1f77b4")
        val barW = if (!isResponse) null else JTextField(trimNum(barEl?.width ?: 120.0), 6)
        val barH = if (!isResponse) null else JTextField(trimNum(barEl?.height ?: 20.0), 6)
        val plotColor = if (!isResponse) null else colorPicker(plotEl?.color ?: "#1f77b4")
        val plotWindow = if (!isResponse) null else JTextField(plotEl?.windowDuration?.let { trimNum(it) } ?: "", 6)
        val plotW = if (!isResponse) null else JTextField(trimNum(plotEl?.width ?: 220.0), 6)
        val plotH = if (!isResponse) null else JTextField(trimNum(plotEl?.height ?: 110.0), 6)
        val histBins = if (!isResponse) null else JTextField((histEl?.bins ?: 10).toString(), 6)
        val histColor = if (!isResponse) null else colorPicker(histEl?.color ?: "#1f77b4")
        val histW = if (!isResponse) null else JTextField(trimNum(histEl?.width ?: 220.0), 6)
        val histH = if (!isResponse) null else JTextField(trimNum(histEl?.height ?: 120.0), 6)
        val decimalsEl = layout.values.firstOrNull { it.responseName == name }?.decimals
            ?: layout.summaries.firstOrNull { it.responseName == name }?.decimals
        val decimals = if (!isResponse) null else JTextField((decimalsEl ?: 1).toString(), 4)
        if (displayCombo != null) {
            form.add(JLabel("display")); form.add(displayCombo)
            form.add(JLabel("")); form.add(discreteCheck)
            barMax?.let { form.add(JLabel("bar max")); form.add(it) }
            barColor?.let { form.add(JLabel("bar color")); form.add(it.first) }
            barW?.let { form.add(JLabel("bar w,h")); form.add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { add(barW); add(barH) }) }
            plotColor?.let { form.add(JLabel("plot color")); form.add(it.first) }
            plotWindow?.let { form.add(JLabel("plot window (blank=all)")); form.add(it) }
            plotW?.let { form.add(JLabel("plot w,h")); form.add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { add(plotW); add(plotH) }) }
            histBins?.let { form.add(JLabel("hist bins")); form.add(it) }
            histColor?.let { form.add(JLabel("hist color")); form.add(it.first) }
            histW?.let { form.add(JLabel("hist w,h")); form.add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { add(histW); add(histH) }) }
            decimals?.let { form.add(JLabel("decimals")); form.add(it) }
            // The discrete box only applies to a histogram; the rest of the styling applies to whichever display
            // is selected on OK (one-pass create-and-style).
            fun syncDiscrete() { discreteCheck!!.isEnabled = displayCombo.selectedItem == ResponseDisplay.HISTOGRAM }
            displayCombo.addActionListener { syncDiscrete() }
            syncDiscrete()
        }
        // Label + value annotations (C3/batch 4): the name label and the live value/state are independent —
        // each can be retitled (name), moved (dx/dy px from the glyph), or hidden on its own.
        val lbl = layout.labelFor(kind, name)
        val labelText = JTextField(lbl?.text ?: "", 12)
        val labelDx = JTextField(trimNum(lbl?.dx ?: 0.0), 5)
        val labelDy = JTextField(trimNum(lbl?.dy ?: -12.0), 5)
        val labelShow = javax.swing.JCheckBox("show label", lbl?.visible ?: true)
        val valueDx = JTextField(trimNum(lbl?.valueDx ?: 0.0), 5)
        val valueDy = JTextField(trimNum(lbl?.valueDy ?: 14.0), 5)
        val valueShow = javax.swing.JCheckBox("show value", lbl?.valueVisible ?: true)
        form.add(JLabel("label text")); form.add(labelText)
        form.add(JLabel("label dx,dy")); form.add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { add(labelDx); add(labelDy) })
        form.add(JLabel("")); form.add(labelShow)
        // The live value/state annotation applies only to elements that draw one; a station/location/mover draws
        // none, so its value controls would be inert — hide them there (the name label above still applies). A
        // resource's value visibility is the "show value (busy/capacity)" master above, so it doesn't also get the
        // generic per-element value toggle here (that duplicate confused which checkbox does what) — only its offset.
        val showsValueOffset = kind != ElementKind.STATION && kind != ElementKind.LOCATION && kind != ElementKind.MOVABLE_RESOURCE
        if (showsValueOffset) {
            form.add(JLabel("value dx,dy")); form.add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply { add(valueDx); add(valueDy) })
            if (kind != ElementKind.RESOURCE) { form.add(JLabel("")); form.add(valueShow) }
        }

        val choice = JOptionPane.showConfirmDialog(
            this, form, "Edit $name (${kind.label()})", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (choice != JOptionPane.OK_OPTION) return
        controller.moveLayoutElement(
            kind, name, xf.text.trim().toDoubleOrNull() ?: pos.x, yf.text.trim().toDoubleOrNull() ?: pos.y
        )
        if (queue != null) controller.setQueueProperties(
            name, qDir!!.text.trim().toDoubleOrNull() ?: queue.growthDegrees,
            qSpacing!!.text.trim().toDoubleOrNull() ?: queue.spacing,
            qMax!!.text.trim().toIntOrNull() ?: queue.maxShown
        )
        if (resource != null) {
            controller.setResourceSize(name, resSize!!.text.trim().toDoubleOrNull() ?: resource.size)
            controller.setResourceShowValue(name, resShowValue!!.isSelected)
            fun ref(state: String) = resImages[state]?.text?.trim()?.takeIf { it.isNotEmpty() }
            controller.setResourceImages(name, ref("idle"), ref("busy"), ref("failed"), ref("inactive"))
        }
        // Apply the display form, then the selected form's styling — in one pass. Switching the form re-creates
        // the element (at its moved position); the styling for the chosen form is then applied to it, so you can
        // create-and-style a chart in a single OK, and re-editing the same form never resets it.
        if (displayCombo != null) {
            val newForm = displayCombo.selectedItem as ResponseDisplay
            if (newForm != curDisplay) controller.setResponseDisplay(name, newForm, discreteCheck!!.isSelected)
            when (newForm) {
                ResponseDisplay.BAR -> controller.setBarStyle(
                    name, barMax!!.text.trim().toDoubleOrNull() ?: 100.0, barColor!!.second().trim(),
                    barW!!.text.trim().toDoubleOrNull() ?: 120.0, barH!!.text.trim().toDoubleOrNull() ?: 20.0
                )
                ResponseDisplay.PLOT -> controller.setPlotStyle(
                    name, plotColor!!.second().trim(), plotWindow!!.text.trim().toDoubleOrNull(),
                    plotW!!.text.trim().toDoubleOrNull() ?: 220.0, plotH!!.text.trim().toDoubleOrNull() ?: 110.0
                )
                ResponseDisplay.HISTOGRAM -> controller.setHistogramStyle(
                    name, histBins!!.text.trim().toIntOrNull() ?: 10, histColor!!.second().trim(),
                    histW!!.text.trim().toDoubleOrNull() ?: 220.0, histH!!.text.trim().toDoubleOrNull() ?: 120.0,
                    discreteCheck!!.isSelected
                )
                ResponseDisplay.VALUE, ResponseDisplay.SUMMARY -> decimals?.text?.trim()?.toIntOrNull()?.let { controller.setValueDecimals(name, it) }
            }
        }
        controller.setElementLabel(
            kind, name, labelText.text.trim().takeIf { it.isNotEmpty() },
            labelDx.text.trim().toDoubleOrNull() ?: 0.0, labelDy.text.trim().toDoubleOrNull() ?: -12.0, labelShow.isSelected,
            valueDx.text.trim().toDoubleOrNull() ?: 0.0, valueDy.text.trim().toDoubleOrNull() ?: 14.0,
            // A resource's value visibility is driven by its "show value (busy/capacity)" master, so keep the value
            // annotation itself visible (positionable) and let that master gate it; other kinds use their toggle.
            if (kind == ElementKind.RESOURCE) true else valueShow.isSelected
        )
        afterEdit()
    }

    /** Adds a [label] image row to [form]: a read-only path field with Choose…/Clear. Returns the field. */
    private fun imagePickerRow(form: JPanel, label: String, initial: String?): JTextField {
        val field = JTextField(initial ?: "", 14).apply { isEditable = false }
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            add(field)
            add(JButton("Choose…").apply { addActionListener { chooseImageInto(field) } })
            add(JButton("Clear").apply { addActionListener { field.text = "" } })
        }
        form.add(JLabel(label)); form.add(row)
        return field
    }

    /** Opens a file chooser and writes the chosen image path into [field] (10.7). */
    private fun chooseImageInto(field: JTextField) {
        val chooser = javax.swing.JFileChooser().apply {
            dialogTitle = "Choose image"
            fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif")
        }
        if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
            field.text = chooser.selectedFile.absolutePath
        }
    }

    // ── Drag-to-move + selection on the preview canvas (9F.6 / P4) ────────────────

    private var groupDragLast: Pair<Double, Double>? = null // world point, while moving the selection
    private var marqueeStartScreen: java.awt.Point? = null
    private var marqueeStartWorld: Pair<Double, Double>? = null

    /** Replaces the selection with [hit] (or clears it when null) and refreshes the highlight rings. */
    private fun selectOnly(hit: Pair<ElementKind, String>?) {
        selected.clear(); hit?.let { selected.add(it) }
        if (hit != null) { // a glyph selection drops any storage outline / shape / clock selection
            selectedStorage = null; canvas.highlightRectWorld = null
            selectedShapeIndex = null; canvas.shapeHighlightWorld = null
            selectedClockIndex = null; canvas.clockHighlightWorld = null
        }
        updateSelectionHandles()
    }

    /** Selects every placed element whose anchor falls within the world rectangle (the marquee, P4). */
    private fun selectInRect(x1: Double, y1: Double, x2: Double, y2: Double) {
        val loX = minOf(x1, x2); val hiX = maxOf(x1, x2); val loY = minOf(y1, y2); val hiY = maxOf(y1, y2)
        val layout = controller.layout.value ?: return
        selected.clear()
        for (kind in SUPPORTED_LAYOUT_KINDS) for (n in layout.placedNames(kind)) {
            layout.positionOf(kind, n)?.let { if (it.x in loX..hiX && it.y in loY..hiY) selected.add(kind to n) }
        }
        updateSelectionHandles()
    }

    /** Removes the selection — glyph(s), the selected storage, and/or the selected shape (Delete key / context menu). */
    private fun removeSelected() {
        val storage = selectedStorage
        val shapeIdx = selectedShapeIndex
        val clockIdx = selectedClockIndex
        if (selected.isEmpty() && storage == null && shapeIdx == null && clockIdx == null) return
        selected.toList().forEach { (k, n) -> controller.removeLayoutElement(k, n) }
        selected.clear()
        if (storage != null) { controller.removeStorage(storage); selectedStorage = null; canvas.highlightRectWorld = null }
        if (shapeIdx != null) { controller.removeBackgroundAt(shapeIdx); selectedShapeIndex = null; canvas.shapeHighlightWorld = null }
        if (clockIdx != null) { controller.removeClockAt(clockIdx); selectedClockIndex = null; canvas.clockHighlightWorld = null }
        afterEdit()
    }

    /** If [e] is a popup trigger over a placed element/storage, shows a Remove context menu and consumes it. */
    private fun maybePopup(e: java.awt.event.MouseEvent): Boolean {
        if (!e.isPopupTrigger) return false
        val hit = pickPlaced(e.x, e.y) ?: run {
            // No element under the cursor — offer to remove a storage or a background shape if one is here.
            val storage = pickStorage(e.x, e.y)
            if (storage != null) {
                javax.swing.JPopupMenu().apply {
                    add(javax.swing.JMenuItem("Remove storage $storage").apply { addActionListener { controller.removeStorage(storage); afterEdit() } })
                }.show(canvas, e.x, e.y)
            } else pickShape(e.x, e.y)?.let { idx ->
                val kindLabel = controller.layout.value?.background?.getOrNull(idx)?.kind?.name?.lowercase() ?: "shape"
                javax.swing.JPopupMenu().apply {
                    add(javax.swing.JMenuItem("Remove $kindLabel").apply {
                        addActionListener { controller.removeBackgroundAt(idx); selectedShapeIndex = null; canvas.shapeHighlightWorld = null; afterEdit() }
                    })
                }.show(canvas, e.x, e.y)
            } ?: pickClock(e.x, e.y)?.let { idx ->
                javax.swing.JPopupMenu().apply {
                    add(javax.swing.JMenuItem("Remove clock").apply {
                        addActionListener { controller.removeClockAt(idx); selectedClockIndex = null; canvas.clockHighlightWorld = null; afterEdit() }
                    })
                }.show(canvas, e.x, e.y)
            }
            return true
        }
        javax.swing.JPopupMenu().apply {
            // Remove the whole selection when right-clicking one of several selected; else just the clicked element.
            val targets = if (hit in selected && selected.size > 1) selected.toList() else listOf(hit)
            val label = if (targets.size > 1) "Remove ${targets.size} selected" else "Remove ${hit.second}"
            add(javax.swing.JMenuItem(label).apply {
                addActionListener {
                    targets.forEach { (k, n) -> selected.remove(k to n); controller.removeLayoutElement(k, n) }
                    afterEdit()
                }
            })
        }.show(canvas, e.x, e.y)
        return true
    }

    /** Reflects the selected elements' current positions onto the canvas highlight rings (or clears them). */
    private fun updateSelectionHandles() {
        val layout = controller.layout.value
        canvas.selectionHandles = selected.mapNotNull { (k, n) ->
            layout?.positionOf(k, n)?.let { java.awt.geom.Point2D.Double(it.x, it.y) }
        }
        updateLabelGrips()
    }

    // ── Draggable name/value text grips for the single selected element (batch-4 polish) ──

    private var labelDrag: Triple<ElementKind, String, Boolean>? = null // (kind, name, isValueGrip)

    /** The selected element's screen anchor (its glyph position), or null. */
    private fun labelAnchorScreen(kind: ElementKind, name: String): java.awt.geom.Point2D? {
        val pos = controller.layout.value?.positionOf(kind, name) ?: return null
        return canvas.worldTransform().transform(java.awt.geom.Point2D.Double(pos.x, pos.y), null)
    }

    /** The screen point of the name (isValue=false) or value (isValue=true) text grip for ([kind],[name]). */
    private fun labelGripScreen(kind: ElementKind, name: String, isValue: Boolean): java.awt.geom.Point2D? {
        val a = labelAnchorScreen(kind, name) ?: return null
        val lbl = controller.layout.value?.labelFor(kind, name)
        return if (isValue) java.awt.geom.Point2D.Double(a.x + (lbl?.valueDx ?: 0.0), a.y + (lbl?.valueDy ?: 14.0))
        else java.awt.geom.Point2D.Double(a.x + (lbl?.dx ?: 0.0), a.y + (lbl?.dy ?: -12.0))
    }

    /** Shows the name + value text grips only when exactly one element is selected. */
    private fun updateLabelGrips() {
        val sel = selected.singleOrNull()
        canvas.labelGrips = if (sel == null) emptyList()
        else listOfNotNull(labelGripScreen(sel.first, sel.second, false), labelGripScreen(sel.first, sel.second, true))
    }

    /** The selected element's name/value text under screen ([sx],[sy]) — (kind,name,isValue) or null. The grab area
     *  spans the rendered text (from its anchor rightward, over ascent/descent), so you can grab the label directly
     *  instead of hunting for the small handle (G3). */
    private fun pickLabelGrip(sx: Int, sy: Int): Triple<ElementKind, String, Boolean>? {
        val sel = selected.singleOrNull() ?: return null
        val (k, n) = sel
        val fm = canvas.getFontMetrics(canvas.font)
        for (isValue in listOf(false, true)) {
            val g = labelGripScreen(k, n, isValue) ?: continue
            // Text is drawn from the grip point rightward at the baseline; the value text is dynamic (unknown here),
            // so use a generous fixed width for it and the measured name width for the name.
            val w = if (isValue) 44 else fm.stringWidth(controller.layout.value?.labelFor(k, n)?.text ?: n).coerceAtLeast(24)
            if (sx >= g.x - 6 && sx <= g.x + w + 6 && sy >= g.y - fm.ascent - 2 && sy <= g.y + fm.descent + 2)
                return Triple(k, n, isValue)
        }
        return null
    }

    /** Applies a new screen offset to the name (or value) text of ([kind],[name]), preserving the other piece. */
    private fun applyLabelOffset(kind: ElementKind, name: String, isValue: Boolean, dx: Double, dy: Double) {
        val l = controller.layout.value?.labelFor(kind, name)
        if (isValue) controller.setElementLabel(kind, name, l?.text, l?.dx ?: 0.0, l?.dy ?: -12.0, l?.visible ?: true, dx, dy, l?.valueVisible ?: true)
        else controller.setElementLabel(kind, name, l?.text, dx, dy, l?.visible ?: true, l?.valueDx ?: 0.0, l?.valueDy ?: 14.0, l?.valueVisible ?: true)
        afterEdit()
    }

    private var storageMoveDrag: String? = null
    private var selectedStorage: String? = null

    /** The storage [st]'s clickable footprint in world coordinates: a generous box around the belt/line span
     *  (anchor → anchor+width along growthDegrees) padded by the height band (label above, glyphs around) — G6. */
    private fun storageFootprint(st: ksl.animation.StorageLayoutElement): java.awt.geom.Rectangle2D.Double {
        val p = st.position
        val rad = Math.toRadians(st.growthDegrees)
        val ex = p.x + st.width * kotlin.math.cos(rad); val ey = p.y + st.width * kotlin.math.sin(rad)
        val h = st.height.coerceAtLeast(16.0)
        val minX = minOf(p.x, ex) - h * 0.5; val maxX = maxOf(p.x, ex) + h * 0.5
        val minY = minOf(p.y, ey) - h;       val maxY = maxOf(p.y, ey) + h * 0.5
        return java.awt.geom.Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY)
    }

    /** The storage whose footprint contains screen point ([sx],[sy]), or null — clicking anywhere on the box
     *  selects it, rather than requiring a hit near the anchor point (G6, was #15). */
    private fun pickStorage(sx: Int, sy: Int): String? {
        val world = canvas.screenToWorld(sx.toDouble(), sy.toDouble())
        return controller.layout.value?.storages?.firstOrNull { storageFootprint(it).contains(world.x, world.y) }?.suspensionName
    }

    /** Marks [name] as the selected storage (clearing the generic selection), or clears it; refreshes the outline. */
    private fun selectStorage(name: String?) {
        selectedStorage = name
        if (name != null) { selectOnly(null); selectedShapeIndex = null; canvas.shapeHighlightWorld = null; selectedClockIndex = null; canvas.clockHighlightWorld = null } // exclusive
        refreshStorageHighlight()
    }

    /** Reflects the selected storage's current footprint + resize grip onto the canvas (or clears them). */
    private fun refreshStorageHighlight() {
        val st = selectedStorage?.let { sn -> controller.layout.value?.storages?.firstOrNull { it.suspensionName == sn } }
        if (st == null) selectedStorage = null
        canvas.highlightRectWorld = st?.let { storageFootprint(it) }
        canvas.storageResizeGrips = st?.let { listOf(storageResizeGrip(it)) } ?: emptyList()
    }

    private var storageResizeDrag: String? = null

    /** The far corner of storage [st] (anchor + width along growthDegrees + height perpendicular) — the resize grip. */
    private fun storageResizeGrip(st: ksl.animation.StorageLayoutElement): java.awt.geom.Point2D.Double {
        val rad = Math.toRadians(st.growthDegrees)
        val dx = Math.cos(rad); val dy = Math.sin(rad); val px = -Math.sin(rad); val py = Math.cos(rad)
        return java.awt.geom.Point2D.Double(st.position.x + dx * st.width + px * st.height, st.position.y + dy * st.width + py * st.height)
    }

    /** The selected storage if its resize grip is within grab radius of screen ([sx],[sy]), else null (item 2). */
    private fun pickStorageGrip(sx: Int, sy: Int): String? {
        val st = selectedStorage?.let { sn -> controller.layout.value?.storages?.firstOrNull { it.suspensionName == sn } } ?: return null
        val world = canvas.screenToWorld(sx.toDouble(), sy.toDouble())
        val r = HIT_RADIUS_PX / canvas.worldTransform().scaleX.coerceAtLeast(1e-6)
        val g = storageResizeGrip(st)
        return if (kotlin.math.hypot(g.x - world.x, g.y - world.y) <= r) st.suspensionName else null
    }

    /** Resizes storage [name] so its far corner follows world ([wx],[wy]): width along growthDegrees, height perp. */
    private fun resizeStorageTo(name: String, wx: Double, wy: Double) {
        val st = controller.layout.value?.storages?.firstOrNull { it.suspensionName == name } ?: return
        val rad = Math.toRadians(st.growthDegrees)
        val dx = Math.cos(rad); val dy = Math.sin(rad); val px = -Math.sin(rad); val py = Math.cos(rad)
        val rx = wx - st.position.x; val ry = wy - st.position.y
        controller.setStorageProperties(
            name, st.position.x, st.position.y, st.style, rx * dx + ry * dy, rx * px + ry * py,
            st.growthDegrees, st.spacing, st.maxShown, st.capacity, st.byType, st.label
        )
    }

    /** Double-click editor for a storage (G6): position, style, size, direction, and member-layout properties. */
    private fun showStorageEditor(name: String) {
        val st = controller.layout.value?.storages?.firstOrNull { it.suspensionName == name } ?: return
        val x = JTextField(trimNum(st.position.x), 6); val y = JTextField(trimNum(st.position.y), 6)
        val style = JComboBox(ksl.animation.StorageStyle.entries.toTypedArray()).apply { selectedItem = st.style }
        val w = JTextField(trimNum(st.width), 6); val h = JTextField(trimNum(st.height), 6)
        val dir = JTextField(trimNum(st.growthDegrees), 6)
        val spacing = JTextField(trimNum(st.spacing), 6); val maxShown = JTextField(st.maxShown.toString(), 6)
        val capacity = JTextField(st.capacity.toString(), 6)
        val byType = javax.swing.JCheckBox("color by entity type", st.byType)
        val label = JTextField(st.label ?: "", 12)
        val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
            add(JLabel("x")); add(x); add(JLabel("y")); add(y)
            add(JLabel("style")); add(style)
            add(JLabel("width")); add(w); add(JLabel("height")); add(h)
            add(JLabel("direction°")); add(dir)
            add(JLabel("spacing")); add(spacing)
            add(JLabel("max shown")); add(maxShown)
            add(JLabel("capacity (0=∞)")); add(capacity)
            add(JLabel("")); add(byType)
            add(JLabel("label")); add(label)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Edit storage $name", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        controller.setStorageProperties(
            name,
            x.text.trim().toDoubleOrNull() ?: st.position.x, y.text.trim().toDoubleOrNull() ?: st.position.y,
            style.selectedItem as ksl.animation.StorageStyle,
            w.text.trim().toDoubleOrNull() ?: st.width, h.text.trim().toDoubleOrNull() ?: st.height,
            dir.text.trim().toDoubleOrNull() ?: st.growthDegrees,
            spacing.text.trim().toDoubleOrNull() ?: st.spacing,
            maxShown.text.trim().toIntOrNull() ?: st.maxShown,
            capacity.text.trim().toIntOrNull() ?: st.capacity,
            byType.isSelected, label.text.trim()
        )
        afterEdit()
    }

    // ── Background shape selection: click-select, drag-move, Delete, double-click edit (first-class shapes) ──

    private var selectedShapeIndex: Int? = null
    private var shapeMoveDrag: Int? = null
    private var shapeDragLast: Pair<Double, Double>? = null
    private var shapeResizeDrag: Int? = null
    private var selectedClockIndex: Int? = null
    private var clockMoveDrag: Int? = null
    private var clockDragLast: Pair<Double, Double>? = null

    /** The world-coordinate bounding box of background element [b], padded so thin shapes/text are clickable. */
    private fun shapeBounds(b: ksl.animation.BackgroundElement): java.awt.geom.Rectangle2D.Double {
        val pts = b.points
        if (pts.isEmpty()) return java.awt.geom.Rectangle2D.Double(0.0, 0.0, 0.0, 0.0)
        var minX = pts.minOf { it.x }; var maxX = pts.maxOf { it.x }
        var minY = pts.minOf { it.y }; var maxY = pts.maxOf { it.y }
        when (b.kind) {
            ksl.animation.BackgroundKind.TEXT -> { // anchor is baseline-left; text runs right and sits above it
                val ax = pts[0].x; val ay = pts[0].y; val fs = b.fontSize.coerceAtLeast(4.0)
                minX = ax; maxX = ax + maxOf(fs, (b.text?.length ?: 4) * fs * 0.6); minY = ay - fs; maxY = ay + fs * 0.3
            }
            ksl.animation.BackgroundKind.LINE, ksl.animation.BackgroundKind.POLYLINE -> { minX -= 4; maxX += 4; minY -= 4; maxY += 4 }
            else -> {}
        }
        if (maxX - minX < 6) { minX -= 3; maxX += 3 }
        if (maxY - minY < 6) { minY -= 3; maxY += 3 }
        return java.awt.geom.Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY)
    }

    /** The top-most background shape whose bounds contain screen point ([sx],[sy]), or null. */
    private fun pickShape(sx: Int, sy: Int): Int? {
        val bg = controller.layout.value?.background ?: return null
        val w = canvas.screenToWorld(sx.toDouble(), sy.toDouble())
        for (i in bg.indices.reversed()) if (shapeBounds(bg[i]).contains(w.x, w.y)) return i // last drawn = topmost
        return null
    }

    /** Selects background shape [index] (clearing glyph/storage selection), or clears it; refreshes the outline. */
    private fun selectShape(index: Int?) {
        selectedShapeIndex = index
        if (index != null) { selectOnly(null); selectStorage(null); selectedClockIndex = null; canvas.clockHighlightWorld = null } // shape selection is exclusive
        refreshShapeHighlight()
    }

    /** Reflects the selected shape's bounding box + resize grip onto the canvas (or clears them; drops a stale index). */
    private fun refreshShapeHighlight() {
        val bg = controller.layout.value?.background
        val idx = selectedShapeIndex
        if (bg == null || idx == null || idx !in bg.indices) {
            selectedShapeIndex = null; canvas.shapeHighlightWorld = null; canvas.shapeResizeGrips = emptyList(); return
        }
        val b = bg[idx]
        canvas.shapeHighlightWorld = shapeBounds(b)
        canvas.shapeResizeGrips = shapeResizeGrip(b)?.let { listOf(it) } ?: emptyList()
    }

    /** A shape's resize grip in world coords: the far corner of a rect/image, or the bottom-right of a text box;
     *  null for lines (no size to drag). Dragging it resizes the rect/image or scales the text's font. */
    private fun shapeResizeGrip(b: ksl.animation.BackgroundElement): java.awt.geom.Point2D.Double? = when {
        (b.kind == ksl.animation.BackgroundKind.RECT || b.kind == ksl.animation.BackgroundKind.IMAGE) && b.points.size >= 2 ->
            java.awt.geom.Point2D.Double(b.points[1].x, b.points[1].y)
        b.kind == ksl.animation.BackgroundKind.TEXT && b.points.isNotEmpty() ->
            shapeBounds(b).let { java.awt.geom.Point2D.Double(it.maxX, it.maxY) }
        else -> null
    }

    /** The selected shape's index if its resize grip is within grab radius of screen ([sx],[sy]), else null. */
    private fun pickShapeResizeGrip(sx: Int, sy: Int): Int? {
        val idx = selectedShapeIndex ?: return null
        val b = controller.layout.value?.background?.getOrNull(idx) ?: return null
        val g = shapeResizeGrip(b) ?: return null
        val world = canvas.screenToWorld(sx.toDouble(), sy.toDouble())
        val r = HIT_RADIUS_PX / canvas.worldTransform().scaleX.coerceAtLeast(1e-6)
        return if (kotlin.math.hypot(g.x - world.x, g.y - world.y) <= r) idx else null
    }

    /** Resizes shape [index] from a dragged corner: a rect/image's far corner follows ([wx],[wy]); a text's
     *  font scales with the drag distance from its anchor (so dragging the handle grows/shrinks the text). */
    private fun resizeShapeTo(index: Int, wx: Double, wy: Double) {
        val b = controller.layout.value?.background?.getOrNull(index) ?: return
        when (b.kind) {
            ksl.animation.BackgroundKind.RECT, ksl.animation.BackgroundKind.IMAGE -> {
                if (b.points.size < 2) return
                controller.setBackgroundAt(index, b.copy(points = listOf(b.points[0], ksl.animation.LayoutPoint(wx, wy))))
            }
            ksl.animation.BackgroundKind.TEXT -> {
                val a = b.points.firstOrNull() ?: return
                val len = (b.text?.length ?: 4).coerceAtLeast(1)
                val k = kotlin.math.sqrt((len * 0.6) * (len * 0.6) + 0.3 * 0.3).coerceAtLeast(1e-6) // grip ≈ fs·k from anchor
                val fs = (kotlin.math.hypot(wx - a.x, wy - a.y) / k).coerceIn(4.0, 400.0)
                controller.setBackgroundAt(index, b.copy(fontSize = fs))
            }
            else -> return
        }
        refreshShapeHighlight()
    }

    // ── Clock display widget (model-less, index-keyed — mirrors the background-text widget above) ──────

    /** Toolbar Clock tool: enter the label, format, and size, then click the canvas to place it (Esc to cancel). */
    private fun openClockDialog() {
        if (clockArm) { disarmClock(); return } // re-clicking the armed tool cancels
        val label = JTextField(clockLabel, 12)
        val format = JTextField(clockFormat, 8)
        val size = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(clockFontSize, 4.0, 400.0, 1.0))
        val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
            add(JLabel("label")); add(label)
            add(JLabel("number format")); add(format)
            add(JLabel("size")); add(size)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Add clock", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        clockLabel = label.text.trim().ifBlank { "Time" }
        clockFormat = format.text.trim().ifBlank { "0.0" }
        clockFontSize = (size.value as Number).toDouble()
        armClock()
    }

    private fun armClock() {
        clearArmState()
        clockArm = true
        canvas.cursor = crosshair
        coordLabel.text = "Click to place the clock  (Esc to cancel)"
        highlightTool(null); clockButton?.border = armedButtonBorder
    }

    private fun disarmClock() {
        clockArm = false
        canvas.cursor = java.awt.Cursor.getDefaultCursor(); highlightTool(null)
    }

    /** Places a clock at the clicked point. It becomes a first-class widget (select/move/edit/delete). */
    private fun placeClock(wx: Double, wy: Double) {
        if (!clockArm) return
        controller.addClock(wx, wy, clockLabel, clockFormat, clockFontSize)
        disarmClock(); afterEdit()
    }

    /** The world-coordinate bounding box of clock [c] (anchor baseline-left; padded so the text is clickable). */
    private fun clockBounds(c: ksl.animation.ClockDisplayElement): java.awt.geom.Rectangle2D.Double {
        val ax = c.position.x; val ay = c.position.y; val fs = c.fontSize.coerceAtLeast(4.0)
        // The rendered text is "label: <time>"; the time value varies, so estimate a generous width.
        val chars = (c.label ?: "Time").length + 8
        return java.awt.geom.Rectangle2D.Double(ax, ay - fs, maxOf(fs, chars * fs * 0.6), fs * 1.3)
    }

    /** The top-most clock whose bounds contain screen point ([sx],[sy]), or null. */
    private fun pickClock(sx: Int, sy: Int): Int? {
        val cs = controller.layout.value?.clocks ?: return null
        val w = canvas.screenToWorld(sx.toDouble(), sy.toDouble())
        for (i in cs.indices.reversed()) if (clockBounds(cs[i]).contains(w.x, w.y)) return i // last drawn = topmost
        return null
    }

    /** Selects clock [index] (clearing glyph/storage/shape selection), or clears it; refreshes the outline. */
    private fun selectClock(index: Int?) {
        selectedClockIndex = index
        if (index != null) { selectOnly(null); selectStorage(null); selectShape(null) } // clock selection is exclusive
        refreshClockHighlight()
    }

    /** Reflects the selected clock's bounding box onto the canvas (or clears it; drops a stale index). */
    private fun refreshClockHighlight() {
        val cs = controller.layout.value?.clocks
        val idx = selectedClockIndex
        if (cs == null || idx == null || idx !in cs.indices) {
            selectedClockIndex = null; canvas.clockHighlightWorld = null; return
        }
        canvas.clockHighlightWorld = clockBounds(cs[idx])
    }

    /** Double-click editor for a clock: label, number format, and size (resize). */
    private fun showClockEditor(index: Int) {
        val c = controller.layout.value?.clocks?.getOrNull(index) ?: return
        val label = JTextField(c.label ?: "Time", 12)
        val format = JTextField(c.format, 8)
        val size = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(c.fontSize, 4.0, 400.0, 1.0))
        val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
            add(JLabel("label")); add(label)
            add(JLabel("number format")); add(format)
            add(JLabel("size")); add(size)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Edit clock", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        controller.setClockAt(index, c.copy(
            label = label.text.trim().ifBlank { "Time" },
            format = format.text.trim().ifBlank { "0.0" },
            fontSize = (size.value as Number).toDouble()
        ))
        refreshClockHighlight(); afterEdit()
    }

    /** Double-click editor for a background shape: text/font/size/color (TEXT), or color + stroke (rect/line). */
    private fun showShapeEditor(index: Int) {
        val bg = controller.layout.value?.background ?: return
        val b = bg.getOrNull(index) ?: return
        when (b.kind) {
            ksl.animation.BackgroundKind.TEXT -> {
                val text = JTextField(b.text ?: "", 16)
                val family = JComboBox(fontFamilies).apply { selectedItem = b.fontFamily ?: "SansSerif" }
                val size = javax.swing.JSpinner(javax.swing.SpinnerNumberModel(b.fontSize.coerceIn(4.0, 400.0), 4.0, 400.0, 1.0))
                val (colorBtn, colorOf) = colorPicker(b.color)
                val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
                    add(JLabel("text")); add(text)
                    add(JLabel("font")); add(family)
                    add(JLabel("size")); add(size)
                    add(JLabel("color")); add(colorBtn)
                }
                if (JOptionPane.showConfirmDialog(this, form, "Edit text", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
                controller.setBackgroundAt(index, b.copy(
                    text = text.text.trim().ifBlank { b.text }, color = colorOf(),
                    fontSize = (size.value as Number).toDouble(), fontFamily = family.selectedItem as String))
                afterEdit()
            }
            ksl.animation.BackgroundKind.RECT, ksl.animation.BackgroundKind.LINE, ksl.animation.BackgroundKind.POLYLINE -> {
                val (colorBtn, colorOf) = colorPicker(b.color); val stroke = JTextField(trimNum(b.strokeWidth), 4)
                val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
                    add(JLabel("color")); add(colorBtn); add(JLabel("stroke width")); add(stroke)
                }
                if (JOptionPane.showConfirmDialog(this, form, "Edit shape", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
                controller.setBackgroundAt(index, b.copy(
                    color = colorOf(), strokeWidth = stroke.text.trim().toDoubleOrNull() ?: b.strokeWidth))
                afterEdit()
            }
            ksl.animation.BackgroundKind.IMAGE -> { // double-click an image to replace its file (keeps its placement)
                val chooser = JFileChooser(layoutsDirEnsured()).apply {
                    dialogTitle = "Replace image"
                    fileFilter = FileNameExtensionFilter("Images (png, jpg, gif)", "png", "jpg", "jpeg", "gif")
                }
                if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
                controller.setBackgroundAt(index, b.copy(imageRef = importImageRef(chooser.selectedFile)))
                afterEdit()
            }
        }
    }

    // ── Queue rotation grip: drag the tail to set orientation (P3, §6 #9) ──

    private var qRotateDrag: String? = null

    /** The world point of queue [q]'s rotation grip (its tail: head + spacing·maxShown along growthDegrees). */
    private fun queueGrip(q: ksl.animation.QueueLayoutElement): java.awt.geom.Point2D.Double {
        val rad = Math.toRadians(q.growthDegrees)
        val len = q.spacing * q.maxShown.coerceAtLeast(1)
        return java.awt.geom.Point2D.Double(q.position.x + len * kotlin.math.cos(rad), q.position.y + len * kotlin.math.sin(rad))
    }

    private fun refreshQueueGrips() {
        canvas.queueRotateGrips = controller.layout.value?.queues?.map { queueGrip(it) } ?: emptyList()
    }

    /** The queue whose rotation grip is within the grab radius of screen point ([sx],[sy]), or null. */
    private fun pickQueueGrip(sx: Int, sy: Int): String? {
        val world = canvas.screenToWorld(sx.toDouble(), sy.toDouble())
        val r = HIT_RADIUS_PX / canvas.worldTransform().scaleX.coerceAtLeast(1e-6)
        return controller.layout.value?.queues?.firstOrNull {
            val g = queueGrip(it); kotlin.math.hypot(g.x - world.x, g.y - world.y) <= r
        }?.queueName
    }

    /**
     * Drags queue [name]'s tail grip to world ([wx],[wy]): the angle sets the growth direction and the distance
     * from the head sets **maxShown** (`round(distance / spacing)`) — so pulling the tail toward the head shows
     * fewer members and pushing it out shows more (P3 + batch 2 #3).
     */
    private fun rotateQueueTo(name: String, wx: Double, wy: Double) {
        val q = controller.layout.value?.queues?.firstOrNull { it.queueName == name } ?: return
        val dx = wx - q.position.x; val dy = wy - q.position.y
        val deg = (Math.toDegrees(kotlin.math.atan2(dy, dx)) % 360 + 360) % 360
        val maxShown = Math.round(kotlin.math.hypot(dx, dy) / q.spacing.coerceAtLeast(1e-6)).toInt().coerceIn(1, 200)
        controller.setQueueProperties(name, deg, q.spacing, maxShown)
    }

    /** Edit mode: a drag grabs the placed element under the cursor and moves it (wheel-zoom still works). */
    private fun installDragToMove() {
        canvas.panEnabled = false
        val handler = object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) {
                if (maybePopup(e)) return // right-click → Remove context menu
                if (pathArmName != null || conveyorRouteName != null) return // collect on click, never drag
                if (textArm) { // text tool: a click places the annotation
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    placeShapeText(w.x, w.y)
                    return
                }
                if (clockArm) { // clock tool: a click places the clock
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    placeClock(w.x, w.y)
                    return
                }
                if (bgArmRef != null) { // background: press starts the rectangle (10.4)
                    bgStart = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble()).let { it.x to it.y }
                    return
                }
                if (storageArm != null) { // storage: press starts the rectangle (#15)
                    storageStart = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble()).let { it.x to it.y }
                    return
                }
                // Queue head→tail two-click flow takes priority while armed (10.4).
                if (queueArm != null) {
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    placeQueueClick(w.x, w.y)
                    return
                }
                // Click-to-place takes priority over drag/pan: an armed tool places at the clicked point (10.3).
                val pk = placeArmedKind; val pn = placeArmedName
                if (pk != null && pn != null) {
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    controller.placeLayoutElement(pk, pn, w.x, w.y)
                    disarmPlace(); afterEdit()
                    return
                }
                if (panMode) return // Pan mode: let the canvas pan; don't grab elements.
                pickLabelGrip(e.x, e.y)?.let { labelDrag = it; return } // grab the selected element's name/value text grip
                pickQueueGrip(e.x, e.y)?.let { qRotateDrag = it; return } // grab a queue rotation grip
                pickStorageGrip(e.x, e.y)?.let { storageResizeDrag = it; return } // grab the selected storage's resize grip (item 2)
                pickShapeResizeGrip(e.x, e.y)?.let { shapeResizeDrag = it; return } // grab the selected shape's resize grip
                canvas.requestFocusInWindow() // so the Delete key targets the canvas selection
                val hit = pickPlaced(e.x, e.y)
                if (hit == null) pickStorage(e.x, e.y)?.let { storageMoveDrag = it; selectStorage(it); return } // select+drag a storage (G6)
                if (hit == null) pickShape(e.x, e.y)?.let { idx -> // select+drag a background shape (rect/line/text/image)
                    shapeMoveDrag = idx; shapeDragLast = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble()).let { it.x to it.y }
                    selectShape(idx); return
                }
                if (hit == null) pickClock(e.x, e.y)?.let { idx -> // select+drag a clock display
                    clockMoveDrag = idx; clockDragLast = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble()).let { it.x to it.y }
                    selectClock(idx); return
                }
                if (hit != null) {
                    if (hit !in selected) selectOnly(hit) // clicking an unselected element selects just it;
                    // (clicking one already in a multi-selection keeps the selection so the drag moves the group)
                    groupDragLast = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble()).let { it.x to it.y }
                } else {
                    selectStorage(null); selectShape(null); selectClock(null) // empty space → drop any storage/shape/clock outline, then marquee
                    selectOnly(null)
                    marqueeStartScreen = java.awt.Point(e.x, e.y)
                    marqueeStartWorld = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble()).let { it.x to it.y }
                }
            }
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                // Path tool: first click sets the from-anchor, single clicks add waypoints, a double-click on an
                // anchor sets the destination and finishes (Phase 6).
                if (pathArmName != null) {
                    if (e.clickCount >= 2) onPathFinish(pickAnchor(e.x, e.y)) else onPathClick(e.x, e.y)
                    return
                }
                // Conveyor tool: single clicks collect segment waypoints; a double-click finishes (10.5d).
                if (conveyorRouteName != null) {
                    if (e.clickCount >= 2) finishConveyorRoute()
                    else canvas.screenToWorld(e.x.toDouble(), e.y.toDouble()).let { addConveyorWaypoint(it.x, it.y) }
                    return
                }
                // Double-click re-routes a placed conveyor belt, else edits the element under the cursor (10.3).
                if (e.clickCount == 2 && !panMode && placeArmedName == null && queueArm == null) {
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    val seg = nearestConveyorSegment(w.x, w.y, HIT_RADIUS_PX / canvas.worldTransform().scaleX.coerceAtLeast(1e-6))
                    if (seg != null) armConveyorRoute(seg.first, seg.second)
                    else {
                        val hp = pickPlaced(e.x, e.y)
                        if (hp != null) showElementEditor(hp.first, hp.second)
                        else { // G6: edit a storage in place; else edit a background shape (color/stroke/text)
                            val st = pickStorage(e.x, e.y)
                            if (st != null) showStorageEditor(st)
                            else pickShape(e.x, e.y)?.let { showShapeEditor(it) } ?: pickClock(e.x, e.y)?.let { showClockEditor(it) }
                        }
                    }
                }
            }
            override fun mouseDragged(e: java.awt.event.MouseEvent) {
                labelDrag?.let { (k, n, isValue) -> // dragging the selected element's name/value text (batch-4 polish)
                    val a = labelAnchorScreen(k, n) ?: return
                    applyLabelOffset(k, n, isValue, e.x - a.x, e.y - a.y)
                    return
                }
                storageResizeDrag?.let { name -> // resizing a storage by dragging its corner grip (item 2)
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    resizeStorageTo(name, w.x, w.y)
                    return
                }
                shapeResizeDrag?.let { idx -> // resizing a background shape (image/rect) by dragging its far corner
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    resizeShapeTo(idx, w.x, w.y)
                    return
                }
                storageMoveDrag?.let { name -> // moving a storage by dragging it (#15)
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    controller.moveStorage(name, w.x, w.y)
                    return
                }
                shapeMoveDrag?.let { idx -> // moving a background shape by dragging it (translate by the drag delta)
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    shapeDragLast?.let { last -> controller.moveBackgroundAt(idx, w.x - last.first, w.y - last.second) }
                    shapeDragLast = w.x to w.y
                    refreshShapeHighlight()
                    return
                }
                clockMoveDrag?.let { idx -> // moving a clock by dragging it (translate by the drag delta)
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    clockDragLast?.let { last -> controller.moveClockAt(idx, w.x - last.first, w.y - last.second) }
                    clockDragLast = w.x to w.y
                    refreshClockHighlight()
                    return
                }
                qRotateDrag?.let { name -> // rotating a queue by dragging its tail grip (P3)
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    rotateQueueTo(name, w.x, w.y)
                    return
                }
                marqueeStartScreen?.let { startPx -> // rubber-band: update the box, select on release
                    canvas.marqueeScreen = java.awt.geom.Rectangle2D.Double(
                        minOf(startPx.x, e.x).toDouble(), minOf(startPx.y, e.y).toDouble(),
                        kotlin.math.abs(e.x - startPx.x).toDouble(), kotlin.math.abs(e.y - startPx.y).toDouble()
                    )
                    return
                }
                groupDragLast?.let { last -> // move the whole selection by the drag delta (P4)
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    val dx = w.x - last.first; val dy = w.y - last.second
                    val layout = controller.layout.value
                    selected.forEach { (k, n) -> layout?.positionOf(k, n)?.let { controller.moveLayoutElement(k, n, it.x + dx, it.y + dy) } }
                    groupDragLast = w.x to w.y
                    updateSelectionHandles() // rings follow the moved selection
                }
            }
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (maybePopup(e)) return // popup trigger fires on release on some platforms
                val start = bgStart
                if (bgArmRef != null && start != null) { // background: release completes the rectangle (10.4)
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    placeBackgroundRect(start.first, start.second, w.x, w.y)
                }
                storageStart?.let { s -> // storage: release completes the rectangle (#15)
                    if (storageArm != null) { val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble()); placeStorageRect(s.first, s.second, w.x, w.y) }
                }
                marqueeStartWorld?.let { startW -> // finish the marquee: select every placed element inside it
                    val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                    selectInRect(startW.first, startW.second, w.x, w.y)
                }
                groupDragLast = null; qRotateDrag = null; labelDrag = null
                storageStart = null; storageMoveDrag = null; storageResizeDrag = null
                shapeMoveDrag = null; shapeDragLast = null; shapeResizeDrag = null
                clockMoveDrag = null; clockDragLast = null
                marqueeStartScreen = null; marqueeStartWorld = null; canvas.marqueeScreen = null
            }
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val w = canvas.screenToWorld(e.x.toDouble(), e.y.toDouble())
                val base = "x = ${trimNum(w.x)}, y = ${trimNum(w.y)}"
                val hit = pickPlaced(e.x, e.y) // name the element under the cursor (hover read-out)
                coordLabel.text = if (hit != null) "$base  ·  ${hit.second} (${hit.first.label()})" else base
            }
        }
        canvas.addMouseListener(handler)
        canvas.addMouseMotionListener(handler)
        // Delete/Backspace removes the selected element — bound WHEN_FOCUSED on the canvas so it never
        // fires while the user is typing in a field elsewhere in the window.
        canvas.isFocusable = true
        val deleteKeys = canvas.getInputMap(JComponent.WHEN_FOCUSED)
        deleteKeys.put(javax.swing.KeyStroke.getKeyStroke("DELETE"), "deleteSelected")
        deleteKeys.put(javax.swing.KeyStroke.getKeyStroke("BACK_SPACE"), "deleteSelected")
        canvas.actionMap.put("deleteSelected", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { removeSelected() }
        })
    }

    // ── Document toolbar ────────────────────────────────────────────────────────

    private fun buildToolbar(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        border = BorderFactory.createTitledBorder("Layout")
        add(JButton("New (blank)").apply {
            toolTipText = "Start an empty layout and place elements yourself"
            addActionListener { controller.newBlankLayout(); afterEdit() }
        })
        add(JButton("Auto Layout").apply {
            toolTipText = "Generate a layout automatically — from the latest run when one exists " +
                "(faithful positions and geometry), otherwise from the model"
            addActionListener { controller.autoLayout(); afterEdit() }
        })
        add(JButton("from Model").apply {
            toolTipText = "Generate a layout from the static model only (ignore any run)"
            addActionListener { controller.scaffoldLayout(); afterEdit() }
        })
        add(JButton("Open…").apply { toolTipText = "Open a saved layout (.lay.toml / .lay.json)"; addActionListener { onOpen() } })
        add(JButton("Save").apply { toolTipText = "Save to the current layout file (or prompt if none yet)"; addActionListener { onSave() } })
        add(JButton("Save As…").apply { toolTipText = "Save to a new layout file (.lay.toml added to the base name)"; addActionListener { onSaveAs() } })
        add(JButton("⊞ Elements…").apply {
            toolTipText = "Open the element tables (place/remove/edit by name) in a separate window"
            addActionListener { showElementsDialog() }
        })
    }

    // ── Per-kind element editors ─────────────────────────────────────────────────

    private fun buildEditorTabs(): JComponent {
        val tabs = JTabbedPane()
        for (kind in SUPPORTED_LAYOUT_KINDS) {
            val names = placeableNames(kind)
            if (names.isEmpty()) continue
            if (kind == ElementKind.RESPONSE) { // split tally vs time-weighted (V4)
                val tally = names.filterNot { controller.inventory.isTimeWeighted(it) }
                val tw = names.filter { controller.inventory.isTimeWeighted(it) }
                if (tally.isNotEmpty()) addEditorTab(tabs, "Responses", kind, tally)
                if (tw.isNotEmpty()) addEditorTab(tabs, "Time-Weighted Responses", kind, tw)
            } else {
                addEditorTab(tabs, kind.label(), kind, names) // Station and Location are now distinct tabs (Phase 6)
            }
        }
        tabs.addTab("Background & Paths", buildBackgroundPathsTab())
        tabs.addTab("Object Styles", buildObjectStylesTab())
        tabs.addTab("Process Colors", buildProcessColorsTab())
        if (controller.inventory.conveyorInfos.isNotEmpty()) tabs.addTab("Conveyors", buildConveyorsTab())
        tabs.addTab("Storages", buildStoragesTab())
        tabs.addTab("Agents & Spaces", buildAgentsSpacesTab())
        return tabs
    }

    // ── Agents & Spaces tab (V7): agent state colors + spatial spaces ─────────────

    private fun buildAgentsSpacesTab(): JComponent {
        val stateColors = javax.swing.JList(stateColorListModel)
        val spaces = javax.swing.JList(spaceListModel)
        val agentPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Agent state colors")
            add(JScrollPane(stateColors), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("state")); add(agentState); add(JLabel("color")); add(agentColor)
                add(JButton("Set").apply {
                    toolTipText = "Color agents whose state name contains this text with the given hex color"
                    addActionListener { agentState.text.trim().ifBlank { null }?.let { controller.setAgentStateColor(it, agentColor.hex.trim().ifBlank { "#d62728" }); afterEdit() } }
                })
                add(JButton("Remove selected").apply {
                    toolTipText = "Remove the selected agent state-color mapping"
                    addActionListener { stateColors.selectedValue?.substringBefore("  ")?.let { controller.removeAgentStateColor(it); afterEdit() } }
                })
            }, BorderLayout.SOUTH)
        }
        val spacePanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Spatial spaces (grid, continuous, or network)")
            add(JScrollPane(spaces), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Add grid…").apply { toolTipText = "Add a grid space (cols/rows/cell, origin, torus)"; addActionListener { onAddGridSpace() } })
                add(JButton("Add continuous…").apply { toolTipText = "Add a continuous space (bounds, torus)"; addActionListener { onAddContinuousSpace() } })
                add(JButton("Add network…").apply { toolTipText = "Add a network space (nodes 'id x y', edges 'from to [weight]')"; addActionListener { onAddNetworkSpace() } })
                add(JButton("Remove selected").apply {
                    toolTipText = "Remove the selected spatial space"
                    addActionListener { spaces.selectedValue?.substringBefore("  ")?.let { controller.removeSpace(it); afterEdit() } }
                })
            }, BorderLayout.SOUTH)
        }
        // Obstacle overlays (P5c/G2): import the model's grid obstacles into the layout, or remove an overlay.
        val obstacles = javax.swing.JList(obstacleListModel)
        val obstaclePanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Obstacle overlays (from model grid geometry)")
            add(JScrollPane(obstacles), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Import obstacles from model").apply {
                    toolTipText = "Copy the obstacles the model declares (via attachGeometry) into this layout"
                    addActionListener {
                        if (controller.modelSpaceGeometry().isEmpty())
                            JOptionPane.showMessageDialog(this@LayoutPanel, "This model declares no grid obstacles to import.", "Import obstacles", JOptionPane.INFORMATION_MESSAGE)
                        else { controller.importObstaclesFromModel(); afterEdit() }
                    }
                })
                add(JButton("Remove selected").apply {
                    addActionListener { obstacles.selectedValue?.substringBefore("  ")?.let { controller.removeSpaceGeometry(it); afterEdit() } }
                })
            }, BorderLayout.SOUTH)
        }
        return JPanel(java.awt.GridLayout(3, 1)).apply { add(agentPanel); add(spacePanel); add(obstaclePanel) }
    }

    /** Grid-space dialog (P7): collects cols/rows/cellSize plus origin and the torus (edge-wrap) flag. */
    private fun onAddGridSpace() {
        val name = JTextField(10); val cols = JTextField("20", 5); val rows = JTextField("20", 5)
        val cell = JTextField("1.0", 5); val ox = JTextField("0.0", 5); val oy = JTextField("0.0", 5)
        val torus = javax.swing.JCheckBox("torus (wraps at edges)")
        val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
            add(JLabel("name")); add(name)
            add(JLabel("cols")); add(cols); add(JLabel("rows")); add(rows)
            add(JLabel("cell size")); add(cell)
            add(JLabel("origin x")); add(ox); add(JLabel("origin y")); add(oy)
            add(JLabel("")); add(torus)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Add grid space", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val n = name.text.trim().ifBlank { return }
        controller.addGridSpace(
            n, cols.text.trim().toIntOrNull() ?: 20, rows.text.trim().toIntOrNull() ?: 20,
            cell.text.trim().toDoubleOrNull() ?: 1.0, ox.text.trim().toDoubleOrNull() ?: 0.0,
            oy.text.trim().toDoubleOrNull() ?: 0.0, torus.isSelected
        ); afterEdit()
    }

    /** Continuous-space dialog (P7): bounds plus the torus (edge-wrap) flag. */
    private fun onAddContinuousSpace() {
        val name = JTextField(10); val xMin = JTextField("0.0", 5); val xMax = JTextField("100.0", 5)
        val yMin = JTextField("0.0", 5); val yMax = JTextField("100.0", 5)
        val torus = javax.swing.JCheckBox("torus (wraps at edges)")
        val form = JPanel(java.awt.GridLayout(0, 2, 6, 4)).apply {
            add(JLabel("name")); add(name)
            add(JLabel("x min")); add(xMin); add(JLabel("x max")); add(xMax)
            add(JLabel("y min")); add(yMin); add(JLabel("y max")); add(yMax)
            add(JLabel("")); add(torus)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Add continuous space", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val n = name.text.trim().ifBlank { return }
        controller.addContinuousSpace(
            n, xMin.text.trim().toDoubleOrNull() ?: 0.0, xMax.text.trim().toDoubleOrNull() ?: 100.0,
            yMin.text.trim().toDoubleOrNull() ?: 0.0, yMax.text.trim().toDoubleOrNull() ?: 100.0, torus.isSelected
        ); afterEdit()
    }

    /** Network-space dialog (P8): nodes as "id x y" lines and edges as "from to [weight]" lines. */
    private fun onAddNetworkSpace() {
        val name = JTextField(10)
        val nodesArea = javax.swing.JTextArea(6, 20)
        val edgesArea = javax.swing.JTextArea(6, 20)
        val form = JPanel(BorderLayout(6, 6)).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(JLabel("name")); add(name) }, BorderLayout.NORTH)
            add(JPanel(java.awt.GridLayout(1, 2, 8, 0)).apply {
                add(JPanel(BorderLayout()).apply { add(JLabel("nodes:  id x y"), BorderLayout.NORTH); add(JScrollPane(nodesArea), BorderLayout.CENTER) })
                add(JPanel(BorderLayout()).apply { add(JLabel("edges:  from to [weight]"), BorderLayout.NORTH); add(JScrollPane(edgesArea), BorderLayout.CENTER) })
            }, BorderLayout.CENTER)
        }
        if (JOptionPane.showConfirmDialog(this, form, "Add network space", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val n = name.text.trim().ifBlank { return }
        val nodes = nodesArea.text.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (p.size >= 3) ksl.animation.NetworkNode(p[0], ksl.animation.LayoutPoint(p[1].toDoubleOrNull() ?: return@mapNotNull null, p[2].toDoubleOrNull() ?: return@mapNotNull null)) else null
        }.toList()
        val edges = edgesArea.text.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (p.size >= 2) ksl.animation.NetworkEdge(p[0], p[1], p.getOrNull(2)?.toDoubleOrNull() ?: 1.0) else null
        }.toList()
        controller.addNetworkSpace(n, nodes, edges); afterEdit()
    }

    private fun refreshAgentsSpaces() {
        stateColorListModel.clear()
        controller.layout.value?.agentStateColors?.forEach { (s, c) -> stateColorListModel.addElement("$s  → $c") }
        spaceListModel.clear()
        controller.layout.value?.spaces?.forEach { spaceListModel.addElement(describeSpace(it)) }
        obstacleListModel.clear()
        controller.layout.value?.spaceGeometry?.forEach {
            obstacleListModel.addElement("${it.spaceName}  (${it.blockedCells.size} blocked, ${it.cols}×${it.rows})")
        }
    }

    /** A legible one-line summary of a spatial space — name + kind + dimensions (10.8, §7.1). */
    private fun describeSpace(d: ksl.animation.SpatialSpaceDescriptor): String = when (d) {
        is ksl.animation.SpatialSpaceDescriptor.Grid ->
            "${d.name}  (grid ${d.cols}×${d.rows}, cell ${trimNum(d.cellSize)}${if (d.torus) ", torus" else ""})"
        is ksl.animation.SpatialSpaceDescriptor.Continuous ->
            "${d.name}  (continuous [${trimNum(d.xMin)}..${trimNum(d.xMax)}]×[${trimNum(d.yMin)}..${trimNum(d.yMax)}]${if (d.torus) ", torus" else ""})"
        is ksl.animation.SpatialSpaceDescriptor.Network ->
            "${d.name}  (network: ${d.nodes.size} nodes, ${d.edges.size} edges)"
    }

    // ── Conveyors tab (10.5/P1): placed via the Conveyor tool; edit style or remove here ──

    private fun buildConveyorsTab(): JComponent {
        val list = javax.swing.JList(conveyorListModel)
        fun selected(): String? = (list.selectedValue as String?)?.substringBefore("  (")
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) selected()?.let { n ->
                controller.layout.value?.conveyors?.firstOrNull { c -> c.conveyorName == n }?.let { c ->
                    convWidth.text = trimNum(c.width); convColor.hex = c.color; convArrows.isSelected = c.showDirection
                }
            }
        }
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Conveyors — placed via the Conveyor tool; edit style or remove here")
            add(JScrollPane(list), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("width")); add(convWidth)
                add(JLabel("color")); add(convColor)
                add(convArrows)
                add(JButton("Apply style").apply {
                    addActionListener {
                        val n = selected() ?: return@addActionListener
                        val c = controller.layout.value?.conveyors?.firstOrNull { it.conveyorName == n } ?: return@addActionListener
                        controller.setConveyorLayout(c.copy(
                            width = convWidth.text.trim().toDoubleOrNull() ?: c.width,
                            color = convColor.hex.trim().ifBlank { c.color },
                            showDirection = convArrows.isSelected
                        )); afterEdit()
                    }
                })
                add(JButton("Remove").apply {
                    addActionListener { selected()?.let { controller.removeConveyorLayout(it); afterEdit() } }
                })
            }, BorderLayout.SOUTH)
        }
    }

    private fun refreshConveyors() {
        conveyorListModel.clear()
        controller.layout.value?.conveyors?.forEach { c ->
            val wp = c.segments.sumOf { it.waypoints.size }
            conveyorListModel.addElement(
                "${c.conveyorName}  (${c.segments.size} seg, $wp waypts, w=${trimNum(c.width)}, ${if (c.showDirection) "arrows" else "no arrows"})"
            )
        }
    }

    // ── Storages tab (#15): placed via the Storage tool; change style or remove here ──

    private fun buildStoragesTab(): JComponent {
        val list = javax.swing.JList(storageListModel)
        fun selected(): String? = (list.selectedValue as String?)?.substringBefore("  (")
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) selected()?.let { n ->
                controller.layout.value?.storages?.firstOrNull { s -> s.suspensionName == n }?.let { s -> storageStyleCombo.selectedItem = s.style }
            }
        }
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Storages — named delays / type holding areas (placed via the Storage tool)")
            add(JScrollPane(list), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("style")); add(storageStyleCombo)
                add(JButton("Apply style").apply {
                    addActionListener { selected()?.let { controller.setStorageStyle(it, storageStyleCombo.selectedItem as ksl.animation.StorageStyle); afterEdit() } }
                })
                add(JButton("Remove").apply { addActionListener { selected()?.let { controller.removeStorage(it); afterEdit() } } })
            }, BorderLayout.SOUTH)
        }
    }

    private fun refreshStorages() {
        storageListModel.clear()
        controller.layout.value?.storages?.forEach { s ->
            storageListModel.addElement("${s.suspensionName}  (${s.style}, ${trimNum(s.width)}×${trimNum(s.height)})")
        }
    }

    // ── Object Styles tab (batch 3): a table of the model's entity/agent types, edited below ─────────

    private fun buildObjectStylesTab(): JComponent {
        val model = object : javax.swing.table.AbstractTableModel() {
            private val cols = arrayOf("", "Type", "Shape", "Color", "Size", "Image")
            override fun getRowCount() = styleTypeNames.size
            override fun getColumnCount() = cols.size
            override fun getColumnName(c: Int) = cols[c]
            override fun getValueAt(r: Int, c: Int): Any {
                val t = styleTypeNames[r]
                val oc = controller.layout.value?.objectClasses?.firstOrNull { it.typeName == t }
                return when (c) {
                    0 -> t                  // glyph swatch (painted by GlyphSwatchRenderer); value carries the type name
                    1 -> t
                    2 -> oc?.shape?.name ?: "—"
                    3 -> oc?.color ?: "—"
                    4 -> oc?.size?.let { trimNum(it) } ?: "—"
                    else -> oc?.imageRef?.let { java.io.File(it).name } ?: ""
                }
            }
        }
        styleTableModel = model
        val table = javax.swing.JTable(model).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) loadStyleStrip(table.selectedRow) }
        // A glyph preview in the leading column, so the modeler sees the shape/color a type draws as (C3).
        table.columnModel.getColumn(0).apply {
            cellRenderer = GlyphSwatchRenderer(); minWidth = 34; maxWidth = 34; preferredWidth = 34
        }
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Object styles — select a type, then set its glyph below")
            add(JScrollPane(table), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("shape")); add(styleShape)
                add(JLabel("color")); add(styleColor)
                add(JLabel("size")); add(styleSize)
                add(JLabel("image")); add(styleImage)
                add(JButton("Choose…").apply {
                    toolTipText = "Use an image as this type's glyph (sets shape to IMAGE)"
                    addActionListener { chooseImageInto(styleImage) }
                })
                add(JButton("Clear img").apply { addActionListener { styleImage.text = "" } })
                add(JButton("Apply to selected type").apply {
                    addActionListener { table.selectedRow.takeIf { it in styleTypeNames.indices }?.let { applyObjectStyle(styleTypeNames[it]) } }
                })
                add(JButton("Reset selected").apply {
                    addActionListener { table.selectedRow.takeIf { it in styleTypeNames.indices }?.let { controller.removeObjectClass(styleTypeNames[it]); afterEdit() } }
                })
            }, BorderLayout.SOUTH)
        }
    }

    /** Paints a small shape+color preview of an object-style row's type, for the style table's glyph column (C3). */
    private inner class GlyphSwatchRenderer : javax.swing.JComponent(), javax.swing.table.TableCellRenderer {
        private var shape: ksl.animation.LayoutShape = ksl.animation.LayoutShape.CIRCLE
        private var fill: java.awt.Color = java.awt.Color.LIGHT_GRAY
        private var bg: java.awt.Color = java.awt.Color.WHITE

        override fun getTableCellRendererComponent(
            table: javax.swing.JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): java.awt.Component {
            val oc = controller.layout.value?.objectClasses?.firstOrNull { it.typeName == value?.toString() }
            shape = oc?.shape ?: ksl.animation.LayoutShape.CIRCLE
            fill = oc?.color?.let { ksl.app.swing.animation.view.VisualStyle.parseColor(it) } ?: java.awt.Color.LIGHT_GRAY
            bg = if (isSelected) table.selectionBackground else table.background
            return this
        }

        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g as java.awt.Graphics2D
            g2.color = bg
            g2.fillRect(0, 0, width, height)
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            val d = (minOf(width, height) - 10).coerceAtLeast(4)
            val x = (width - d) / 2.0; val y = (height - d) / 2.0
            g2.color = fill
            when (shape) {
                ksl.animation.LayoutShape.SQUARE, ksl.animation.LayoutShape.IMAGE ->
                    g2.fill(java.awt.geom.Rectangle2D.Double(x, y, d.toDouble(), d.toDouble()))
                ksl.animation.LayoutShape.TRIANGLE -> g2.fill(java.awt.Polygon(
                    intArrayOf((x + d / 2.0).toInt(), x.toInt(), (x + d).toInt()),
                    intArrayOf(y.toInt(), (y + d).toInt(), (y + d).toInt()), 3))
                ksl.animation.LayoutShape.DIAMOND -> g2.fill(java.awt.Polygon(
                    intArrayOf((x + d / 2.0).toInt(), (x + d).toInt(), (x + d / 2.0).toInt(), x.toInt()),
                    intArrayOf(y.toInt(), (y + d / 2.0).toInt(), (y + d).toInt(), (y + d / 2.0).toInt()), 4))
                else -> g2.fill(java.awt.geom.Ellipse2D.Double(x, y, d.toDouble(), d.toDouble()))
            }
        }
    }

    /** Loads the selected type's current style into the strip (or leaves the last values when unstyled). */
    private fun loadStyleStrip(row: Int) {
        val t = styleTypeNames.getOrNull(row) ?: return
        val oc = controller.layout.value?.objectClasses?.firstOrNull { it.typeName == t } ?: return
        styleShape.selectedItem = oc.shape; styleColor.hex = oc.color; styleSize.text = trimNum(oc.size); styleImage.text = oc.imageRef ?: ""
    }

    /** Applies the strip's shape/color/size/image to [type] (a chosen image forces the IMAGE glyph). */
    private fun applyObjectStyle(type: String) {
        val size = styleSize.text.trim().toDoubleOrNull() ?: 12.0
        val image = styleImage.text.trim().takeIf { it.isNotEmpty() }
        val shape = if (image != null) ksl.animation.LayoutShape.IMAGE else styleShape.selectedItem as ksl.animation.LayoutShape
        controller.addObjectClass(type, shape, styleColor.hex.trim().ifBlank { "#1f77b4" }, size, image)
        afterEdit()
    }

    // ── Process Colors tab (batch 3): a table of the model's processes, edited below ─────────────────

    private fun buildProcessColorsTab(): JComponent {
        val model = object : javax.swing.table.AbstractTableModel() {
            private val cols = arrayOf("Process", "Color")
            override fun getRowCount() = processNames.size
            override fun getColumnCount() = cols.size
            override fun getColumnName(c: Int) = cols[c]
            override fun getValueAt(r: Int, c: Int): Any {
                val p = processNames[r]
                return if (c == 0) p else (controller.layout.value?.processColors?.get(p) ?: "—")
            }
        }
        procTableModel = model
        val table = javax.swing.JTable(model).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
        table.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) processNames.getOrNull(table.selectedRow)?.let { p ->
                processColorField.hex = controller.layout.value?.processColors?.get(p) ?: "#ff7f0e"
            }
        }
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Process colors — tint entities by their current process; select a process, then set its color")
            add(JScrollPane(table), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("color")); add(processColorField)
                add(JButton("Apply to selected process").apply {
                    addActionListener {
                        table.selectedRow.takeIf { it in processNames.indices }?.let {
                            controller.setProcessColor(processNames[it], processColorField.hex.trim().ifBlank { "#ff7f0e" }); afterEdit()
                        }
                    }
                })
                add(JButton("Reset selected").apply {
                    addActionListener { table.selectedRow.takeIf { it in processNames.indices }?.let { controller.removeProcessColor(processNames[it]); afterEdit() } }
                })
            }, BorderLayout.SOUTH)
        }
    }

    private fun refreshObjectStyles() {
        styleTableModel?.fireTableDataChanged()
        procTableModel?.fireTableDataChanged()
    }

    // ── Background & Paths tab (V5d) ──────────────────────────────────────────────

    private fun buildBackgroundPathsTab(): JComponent {
        val bgList = javax.swing.JList(bgListModel)
        val pathList = javax.swing.JList(pathListModel)
        val stationList = javax.swing.JList(
            (controller.inventory.namesOf(ElementKind.STATION) + controller.inventory.namesOf(ElementKind.LOCATION)).toTypedArray()
        ).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        }
        val bg = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Background images")
            add(JScrollPane(bgList), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Add image…").apply { addActionListener { onAddImage() } })
                add(JButton("Remove selected").apply {
                    addActionListener { bgList.selectedIndex.takeIf { it >= 0 }?.let { controller.removeBackgroundAt(it); afterEdit() } }
                })
            }, BorderLayout.SOUTH)
        }
        val paths = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Paths (a route through stations/locations)")
            add(JScrollPane(stationList), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("name")); add(pathNameField)
                add(JButton("Add path through selected stations").apply {
                    addActionListener {
                        val nm = pathNameField.text.trim().ifBlank { "path${pathListModel.size() + 1}" }
                        controller.addPathThroughStations(nm, stationList.selectedValuesList); afterEdit()
                    }
                })
                add(JButton("Remove selected path").apply {
                    addActionListener { pathList.selectedValue?.let { controller.removePath(it); afterEdit() } }
                })
                add(JScrollPane(pathList).apply { preferredSize = java.awt.Dimension(160, 60) })
            }, BorderLayout.SOUTH)
        }
        return JPanel(java.awt.GridLayout(2, 1)).apply { add(bg); add(paths) }
    }

    private fun onAddImage() {
        val chooser = JFileChooser(layoutsDirEnsured()).apply {
            dialogTitle = "Choose a background image"
            fileFilter = FileNameExtensionFilter("Images (png, jpg, gif)", "png", "jpg", "jpeg", "gif")
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        controller.addBackgroundImage(importImageRef(chooser.selectedFile))
        afterEdit()
    }

    private fun refreshBackgroundPaths() {
        val layout = controller.layout.value
        bgListModel.clear()
        layout?.background?.forEachIndexed { i, b -> bgListModel.addElement("$i: ${b.kind}${b.imageRef?.let { " — " + java.io.File(it).name } ?: ""}") }
        pathListModel.clear()
        layout?.paths?.forEach { pathListModel.addElement(it.name) }
    }

    private fun addEditorTab(tabs: JTabbedPane, title: String, kind: ElementKind, names: List<String>) {
        val editor = KindEditor(kind, names)
        editors += editor
        tabs.addTab("$title (${names.size})", editor)
    }

    private fun editorFor(kind: ElementKind, name: String): KindEditor? =
        editors.firstOrNull { it.kind == kind && name in it.names }

    /**
     * One kind's editor: a table of the inventory [names] with columns **Name · Placed · X · Y**. Check
     * **Placed** to add (at a default position) or uncheck to remove; edit X/Y to move; double-click a row
     * toggles Placed. Rows are multi-select, with **Place selected** / **Remove selected** for bulk edits.
     * The table both shows and edits coordinates, so there's no separate "what does this do?" guesswork.
     */
    private inner class KindEditor(val kind: ElementKind, val names: List<String>) : JPanel(BorderLayout()) {
        val tableModel = ElementTableModel()
        val table = JTable(tableModel).apply {
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            columnModel.getColumn(1).maxWidth = 70 // Placed checkbox
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) rowAtPoint(e.point).takeIf { it >= 0 }?.let { toggle(names[it]) }
                }
            })
        }

        private val isResponseKind = kind == ElementKind.RESPONSE || kind == ElementKind.COUNTER

        // Property editors (V4): queue direction/spacing/max-shown, resource size — operate on the
        // single selected row.
        val qDir = JTextField(4); val qSpacing = JTextField(4); val qMax = JTextField(4)
        val resSize = JTextField(4)
        val displayCombo = JComboBox(ResponseDisplay.entries.toTypedArray())

        init {
            add(JScrollPane(table), BorderLayout.CENTER)
            val footer = JPanel()
            footer.layout = javax.swing.BoxLayout(footer, javax.swing.BoxLayout.Y_AXIS)
            footer.add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Place selected").apply {
                    toolTipText = "Place all highlighted rows (check their Placed box)"
                    addActionListener { selectedNames().forEach { controller.addLayoutElement(kind, it) }; afterEdit() }
                })
                add(JButton("Remove selected").apply {
                    toolTipText = "Remove all highlighted rows from the layout"
                    addActionListener { selectedNames().forEach { controller.removeLayoutElement(kind, it) }; afterEdit() }
                })
                add(JButton("Place all").apply {
                    toolTipText = "Place every element of this kind"
                    addActionListener { names.forEach { controller.addLayoutElement(kind, it) }; afterEdit() }
                })
                add(JButton("Remove all").apply {
                    toolTipText = "Remove every placed element of this kind from the layout"
                    addActionListener { names.forEach { controller.removeLayoutElement(kind, it) }; afterEdit() }
                })
            })
            buildPropertyStrip()?.let { footer.add(it) }
            add(footer, BorderLayout.SOUTH)
            // Keep the property fields in sync with the selected row.
            table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) loadPropertiesFromSelection() }
        }

        /** A kind-specific property strip, or null for kinds without editable properties yet. */
        private fun buildPropertyStrip(): JComponent? = when (kind) {
            ElementKind.QUEUE -> JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("direction°")); add(qDir)
                add(JLabel("spacing")); add(qSpacing)
                add(JLabel("max shown")); add(qMax)
                add(JButton("Apply to selected queue").apply { toolTipText = "Apply the direction/spacing/max-shown to the selected queue"; addActionListener { applyQueueProps() } })
            }
            ElementKind.RESOURCE -> JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("size")); add(resSize)
                add(JButton("Apply to selected resource").apply { toolTipText = "Apply the glyph size to the selected resource"; addActionListener { applyResourceSize() } })
            }
            ElementKind.RESPONSE, ElementKind.COUNTER -> JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JLabel("Graphical display:")); add(displayCombo)
                add(JButton("Apply to selected").apply {
                    toolTipText = "Place the highlighted response(s) as this display type — value read-out, bar, time plot, summary, or histogram"
                    addActionListener {
                        val d = displayCombo.selectedItem as ResponseDisplay
                        selectedNames().forEach { controller.setResponseDisplay(it, d) }; afterEdit()
                    }
                })
            }
            else -> null
        }

        private fun singleSelected(): String? = table.selectedRow.takeIf { it in names.indices }?.let { names[it] }

        private fun loadPropertiesFromSelection() {
            val name = singleSelected() ?: return
            val layout = controller.layout.value ?: return
            if (kind == ElementKind.QUEUE) layout.queues.firstOrNull { it.queueName == name }?.let {
                qDir.text = trimNum(it.growthDegrees); qSpacing.text = trimNum(it.spacing); qMax.text = it.maxShown.toString()
            }
            if (kind == ElementKind.RESOURCE) layout.resources.firstOrNull { it.resourceName == name }?.let {
                resSize.text = trimNum(it.size)
            }
        }

        private fun applyQueueProps() {
            val name = singleSelected() ?: return
            val dir = qDir.text.trim().toDoubleOrNull() ?: return
            val sp = qSpacing.text.trim().toDoubleOrNull() ?: return
            val mx = qMax.text.trim().toIntOrNull() ?: return
            controller.setQueueProperties(name, dir, sp, mx); afterEdit()
        }

        private fun applyResourceSize() {
            val name = singleSelected() ?: return
            val s = resSize.text.trim().toDoubleOrNull() ?: return
            controller.setResourceSize(name, s); afterEdit()
        }

        fun selectedNames(): List<String> = table.selectedRows.filter { it in names.indices }.map { names[it] }

        private fun toggle(name: String) {
            if (controller.layout.value?.isPlaced(kind, name) == true) controller.removeLayoutElement(kind, name)
            else controller.addLayoutElement(kind, name)
            afterEdit()
        }

        /** Refresh cell values without disturbing the row count or the current selection. */
        fun refresh() { if (names.isNotEmpty()) tableModel.fireTableRowsUpdated(0, names.size - 1) }

        internal inner class ElementTableModel : javax.swing.table.AbstractTableModel() {
            private val cols = arrayOf("Name", "Placed", "X", "Y")
            override fun getRowCount() = names.size
            override fun getColumnCount() = cols.size
            override fun getColumnName(c: Int) = cols[c]
            override fun getColumnClass(c: Int): Class<*> = if (c == 1) java.lang.Boolean::class.java else String::class.java
            override fun isCellEditable(r: Int, c: Int): Boolean =
                c == 1 || ((c == 2 || c == 3) && controller.layout.value?.positionOf(kind, names[r]) != null)

            override fun getValueAt(r: Int, c: Int): Any {
                val name = names[r]
                val layout = controller.layout.value
                val pos = layout?.positionOf(kind, name)
                return when (c) {
                    0 -> when {
                        isResponseKind -> {
                            val stat = if (controller.inventory.isTimeWeighted(name)) "time-weighted" else "tally"
                            // Show the current graphical display (value/bar/plot/summary/histogram) so it's discoverable (#13).
                            val disp = layout?.responseDisplayOf(name)?.name?.lowercase()
                            "$name  ($stat${disp?.let { ", shown as $it" } ?: ""})"
                        }
                        // Flag non-reporting queues (not auto-placed; still placeable here) — P5.
                        kind == ElementKind.QUEUE && !controller.inventory.queueReports(name) -> "$name  (not reported)"
                        else -> name
                    }
                    1 -> (layout?.isPlaced(kind, name) == true)
                    2 -> pos?.let { trimNum(it.x) } ?: ""
                    else -> pos?.let { trimNum(it.y) } ?: ""
                }
            }

            override fun setValueAt(value: Any?, r: Int, c: Int) {
                val name = names[r]
                when (c) {
                    1 -> if (value == true) controller.addLayoutElement(kind, name) else controller.removeLayoutElement(kind, name)
                    2 -> {
                        val x = (value as? String)?.trim()?.toDoubleOrNull(); val cur = controller.layout.value?.positionOf(kind, name)
                        if (x != null && cur != null) controller.moveLayoutElement(kind, name, x, cur.y)
                    }
                    3 -> {
                        val y = (value as? String)?.trim()?.toDoubleOrNull(); val cur = controller.layout.value?.positionOf(kind, name)
                        if (y != null && cur != null) controller.moveLayoutElement(kind, name, cur.x, y)
                    }
                }
                afterEdit()
            }
        }
    }

    // ── Canvas size + validation ──────────────────────────────────────────────────

    private fun buildSouth(): JComponent = JPanel(BorderLayout()).apply {
        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            border = BorderFactory.createTitledBorder("Canvas")
            add(JLabel("title")); add(canvasTitle)
            add(JButton("Set").apply {
                toolTipText = "Set the layout's title"
                addActionListener { controller.setLayoutTitle(canvasTitle.text.trim()); afterEdit() }
            })
            add(JLabel("  width")); add(canvasWidth)
            add(JLabel("height")); add(canvasHeight)
            add(JButton("Resize").apply { toolTipText = "Apply the new canvas width/height (world units)"; addActionListener { onResizeCanvas() } })
        }, BorderLayout.NORTH)
        legendLabel.border = BorderFactory.createEmptyBorder(2, 8, 0, 8)
        add(legendLabel, BorderLayout.CENTER)
        validationLabel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        add(validationLabel, BorderLayout.SOUTH)
    }

    /** Object-class legend + a hint that agents are dynamic — clarifies why the editor canvas shows no agents
     *  even though Replay does (item 7). */
    private fun refreshLegend() {
        legendLabel.text = legendTextFor(controller.layout.value?.objectClasses ?: emptyList())
    }

    private fun legendTextFor(classes: List<ksl.animation.ObjectClassDefinition>): String =
        if (classes.isEmpty()) " " else buildString {
            append("<html>Object styles: ")
            classes.joinTo(this, "&nbsp;&nbsp; ") { "<font color='${it.color}'>●</font> ${it.typeName}" }
            append(" &nbsp;<i>— entities/agents render during Replay (load a trace)</i></html>")
        }

    private fun onResizeCanvas() {
        val w = canvasWidth.text.trim().toDoubleOrNull() ?: return
        val h = canvasHeight.text.trim().toDoubleOrNull() ?: return
        controller.setLayoutCanvasSize(w, h)
        afterEdit()
    }

    // ── Document actions ──────────────────────────────────────────────────────────

    private fun onOpen() {
        val path = chooseLayoutFile(open = true) ?: return
        runCatching { controller.loadLayout(path) }
            .onFailure { showError("Failed to open layout: ${it.message}") }
    }

    private fun onSave() {
        if (controller.layout.value == null) return showError("There is no layout to save. Use New (blank) or Auto Layout first.")
        val target = controller.layoutFile.value ?: return onSaveAs()
        runCatching { controller.saveLayout(target) }.onFailure { showError("Failed to save layout: ${it.message}") }
    }

    private fun onSaveAs() {
        if (controller.layout.value == null) return showError("There is no layout to save. Use New (blank) or Auto Layout first.")
        val path = chooseLayoutFile(open = false) ?: return
        runCatching { controller.saveLayout(path) }.onFailure { showError("Failed to save layout: ${it.message}") }
    }

    private fun chooseLayoutFile(open: Boolean): java.nio.file.Path? {
        val startDir = layoutsDirEnsured()
        val chooser = JFileChooser(startDir).apply {
            dialogTitle = if (open) "Open layout" else "Save layout"
            // Layouts default to TOML (matching the other KSL apps); JSON still opens/saves.
            fileFilter = FileNameExtensionFilter("Animation layout (*.lay.toml, *.lay.json)", "toml", "json")
            if (!open) selectedFile = java.io.File(startDir, defaultLayoutName()) // base name only; .lay.toml is added on save
        }
        val ok = if (open) chooser.showOpenDialog(this) else chooser.showSaveDialog(this)
        if (ok != JFileChooser.APPROVE_OPTION) return null
        var file = chooser.selectedFile
        // On save, append .lay.toml unless the user already gave a recognized layout extension.
        if (!open && !file.name.endsWith(".lay.toml") && !file.name.endsWith(".lay.json"))
            file = java.io.File(file.parentFile, "${file.name}.lay.toml")
        return file.toPath()
    }

    /** The layouts folder, created if missing, so the chooser opens *there* (not the user home). */
    private fun layoutsDirEnsured(): java.io.File {
        java.nio.file.Files.createDirectories(controller.layoutsDir)
        return controller.layoutsDir.toFile()
    }

    /** A reasonable default *base* file name for Save As (the bound file's stem, else the layout title/model).
     *  No layout extension — `.lay.toml` is appended exactly once on save. */
    private fun defaultLayoutName(): String {
        controller.layoutFile.value?.let { return stripLayoutExt(it.fileName.toString()) }
        // An unsaved layout defaults its file name to the model (like the trace file), not the layout title —
        // auto-layouts are titled "Replay", which dropped the model prefix the user expects here.
        return controller.suggestedLayoutBaseName()
    }

    /** Strips a trailing `.lay.toml`/`.lay.json` (repeatedly, to undo an accidental double-append) — so the
     *  Save As field shows a bare base name and the extension is added once. */
    private fun stripLayoutExt(name: String): String {
        var n = name
        while (n.endsWith(".lay.toml") || n.endsWith(".lay.json")) n = n.removeSuffix(".lay.toml").removeSuffix(".lay.json")
        return n
    }

    // ── Refresh ─────────────────────────────────────────────────────────────────

    private fun afterEdit() = refreshAll()

    /** Repaints lists, the canvas-size fields, the validation strip, and the preview from the active layout. */
    private fun refreshAll() {
        val layout = controller.layout.value
        editors.forEach { it.refresh() }
        if (layout != null) {
            canvasTitle.text = layout.title ?: ""
            canvasWidth.text = trimNum(layout.width)
            canvasHeight.text = trimNum(layout.height)
        }
        // Grab handles at every placed element's position, so they're visible.
        canvas.editHandles = layout?.let {
            SUPPORTED_LAYOUT_KINDS.flatMap { kind -> it.placedNames(kind).mapNotNull { n -> it.positionOf(kind, n) } }
                .map { p -> java.awt.geom.Point2D.Double(p.x, p.y) }
        } ?: emptyList()
        updateSelectionHandles() // keep highlight rings on the selection (drops any element that was removed)
        refreshStorageHighlight() // keep the storage outline on the selected storage (tracks moves; clears on removal)
        refreshShapeHighlight()   // keep the shape outline on the selected background shape (tracks moves/removal)
        refreshQueueGrips()     // queue rotation grips track position/orientation
        refreshBackgroundPaths()
        refreshObjectStyles()
        refreshConveyors()
        refreshStorages()
        refreshAgentsSpaces()
        recomputeValidation()
        refreshLegend()
        refreshPreview()
    }

    private fun recomputeValidation() {
        if (controller.layout.value == null) {
            validationLabel.text = "No layout — start a New or Starter layout, then place elements."
            return
        }
        val report = controller.layoutValidation()
        validationLabel.text = if (report.isValid) "✓ Layout is valid."
        else "⚠ " + report.issues.joinToString("; ") { "${it.name}: ${it.message}" }
    }

    private fun refreshPreview() {
        val layout = controller.layout.value
        // Resolve relative image refs (images/<name>) against the layout file's folder, or the layouts dir when
        // the layout is still untitled — so imported background images render in the editor preview, not just replay.
        val imageBase = controller.layoutFile.value?.toAbsolutePath()?.parent ?: controller.layoutsDir
        canvas.replay = layout?.let {
            ReplayModel.build(AnimationSource(layout = it, header = AnimationTraceHeader(), events = emptyList(), baseDir = imageBase))
        }
        canvas.currentTime = 0.0
    }

    /** Coordinate display: round to 2 decimals and drop trailing noise (e.g. 80.00000000000001 → "80"). */
    private fun trimNum(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (r == Math.floor(r)) r.toInt().toString() else r.toString()
    }

    private fun ElementKind.label(): String =
        name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun showError(message: String) =
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE)

    // ── Test hooks (headless smoke test) ──────────────────────────────────────────

    /** Inventory names offered for [kind] across its tab(s). */
    internal fun namesShownForTest(kind: ElementKind): List<String> =
        editors.filter { it.kind == kind }.flatMap { it.names }

    private fun ed(kind: ElementKind, name: String) = editorFor(kind, name) ?: error("no editor for $kind/$name")
    private fun rowOf(kind: ElementKind, name: String) = ed(kind, name).names.indexOf(name)

    /** Checks the Placed box for [name] via the table model, as a user click would. */
    internal fun addForTest(kind: ElementKind, name: String) =
        ed(kind, name).tableModel.setValueAt(true, rowOf(kind, name), 1)

    /** Edits the X/Y cells for [name] via the table model. */
    internal fun moveForTest(kind: ElementKind, name: String, x: Double, y: Double) {
        val m = ed(kind, name).tableModel; val r = rowOf(kind, name)
        m.setValueAt(trimNum(x), r, 2); m.setValueAt(trimNum(y), r, 3)
    }

    /** Unchecks the Placed box for [name]. */
    internal fun removeForTest(kind: ElementKind, name: String) =
        ed(kind, name).tableModel.setValueAt(false, rowOf(kind, name), 1)

    /** Places every element of [kind] (the "Place all" button) across its tab(s). */
    internal fun placeAllForTest(kind: ElementKind) {
        editors.filter { it.kind == kind }.flatMap { it.names }.forEach { controller.addLayoutElement(kind, it) }; afterEdit()
    }

    /** Selects multiple rows in one editor and bulk-adds them. */
    internal fun placeManyForTest(kind: ElementKind, vararg elementNames: String) {
        elementNames.forEach { controller.addLayoutElement(kind, it) }; afterEdit()
    }

    /** Applies queue properties to [name] (direction, spacing, maxShown) via the controller. */
    internal fun setQueuePropsForTest(name: String, dir: Double, spacing: Double, maxShown: Int) {
        controller.setQueueProperties(name, dir, spacing, maxShown); afterEdit()
    }

    /** Queue [name]'s current rotation-grip world point, or null (P3). */
    internal fun queueGripForTest(name: String): Pair<Double, Double>? =
        controller.layout.value?.queues?.firstOrNull { it.queueName == name }?.let { queueGrip(it).let { g -> g.x to g.y } }

    /** Simulates dragging queue [name]'s rotation grip to world ([wx],[wy]), applying the new orientation. */
    internal fun rotateQueueToForTest(name: String, wx: Double, wy: Double) { rotateQueueTo(name, wx, wy); afterEdit() }

    /** The number of edit handles drawn on the preview (one per placed element). */
    internal fun handleCountForTest(): Int = canvas.editHandles.size

    /** Selects ([kind],[name]) as a click would, lighting the highlight ring (B-polish). */
    internal fun selectForTest(kind: ElementKind, name: String) = selectOnly(kind to name)

    /** The selected element's name when exactly one is selected, else null. */
    internal fun selectedNameForTest(): String? = selected.singleOrNull()?.second

    /** The set of selected (kind,name) pairs (P4 multi-select). */
    internal fun selectionForTest(): Set<Pair<ElementKind, String>> = selected.toSet()

    /** Selects every placed element within the world rectangle, as the marquee does (P4). */
    internal fun marqueeSelectForTest(x1: Double, y1: Double, x2: Double, y2: Double) = selectInRect(x1, y1, x2, y2)

    /** Moves the whole selection by ([dx],[dy]) in world units, as a group drag does (P4). */
    internal fun moveSelectionForTest(dx: Double, dy: Double) {
        val layout = controller.layout.value ?: return
        selected.forEach { (k, n) -> layout.positionOf(k, n)?.let { controller.moveLayoutElement(k, n, it.x + dx, it.y + dy) } }
        updateSelectionHandles(); afterEdit()
    }

    /** Removes the selected element(s), as the Delete key does (P1). */
    internal fun removeSelectedForTest() = removeSelected()

    /** Selects the background shape at [index] (as a canvas click would); returns whether its outline is shown. */
    internal fun selectShapeForTest(index: Int?): Boolean { selectShape(index); return canvas.shapeHighlightWorld != null }

    /** The currently selected background-shape index, or null. */
    internal fun selectedShapeIndexForTest(): Int? = selectedShapeIndex

    /** The index of the top-most background shape whose bounds contain world ([wx],[wy]), or null — the hit test. */
    internal fun pickShapeAtWorldForTest(wx: Double, wy: Double): Int? {
        val bg = controller.layout.value?.background ?: return null
        for (i in bg.indices.reversed()) if (shapeBounds(bg[i]).contains(wx, wy)) return i
        return null
    }

    /** Selects the clock at [index] (as a canvas click would); returns whether its outline is shown. */
    internal fun selectClockForTest(index: Int?): Boolean { selectClock(index); return canvas.clockHighlightWorld != null }

    /** The currently selected clock index, or null. */
    internal fun selectedClockIndexForTest(): Int? = selectedClockIndex

    /** The index of the top-most clock whose bounds contain world ([wx],[wy]), or null — the hit test. */
    internal fun pickClockAtWorldForTest(wx: Double, wy: Double): Int? {
        val cs = controller.layout.value?.clocks ?: return null
        for (i in cs.indices.reversed()) if (clockBounds(cs[i]).contains(wx, wy)) return i
        return null
    }

    /** Resizes shape [index] so its far corner follows world ([wx],[wy]) — the canvas corner-grip drag. */
    internal fun resizeShapeForTest(index: Int, wx: Double, wy: Double) { resizeShapeTo(index, wx, wy); afterEdit() }

    /** Whether the selected shape currently shows a resize grip (only rect/image do). */
    internal fun shapeHasResizeGripForTest(): Boolean = canvas.shapeResizeGrips.isNotEmpty()

    /** Imports [file] as a layout-relative image ref, copying it into the workspace — the chooser's import step. */
    internal fun importImageRefForTest(file: java.io.File): String = importImageRef(file)

    /** Drags the name (isValue=false) or value (isValue=true) text of ([kind],[name]) by a screen offset (polish). */
    internal fun dragLabelForTest(kind: ElementKind, name: String, isValue: Boolean, dx: Double, dy: Double) =
        applyLabelOffset(kind, name, isValue, dx, dy)

    /** Sets the name-label + value-annotation overrides as the element editor would (P7/C3, batch 4). */
    internal fun setElementLabelForTest(
        kind: ElementKind, name: String, text: String?, dx: Double, dy: Double, visible: Boolean,
        valueDx: Double = 0.0, valueDy: Double = 14.0, valueVisible: Boolean = true
    ) {
        controller.setElementLabel(kind, name, text, dx, dy, visible, valueDx, valueDy, valueVisible); afterEdit()
    }

    /** The number of selection highlight rings drawn on the canvas. */
    internal fun selectionRingCountForTest(): Int = canvas.selectionHandles.size

    /** True while the canvas is drawing at least one selection highlight ring. */
    internal fun selectionRingShownForTest(): Boolean = canvas.selectionHandles.isNotEmpty()

    /** The hover read-out for the element under a pick (name + kind), as mouseMoved composes it, or null. */
    internal fun hoverNameForTest(kind: ElementKind, name: String): String = "$name (${kind.label()})"

    /** Arms click-to-place for ([kind], [name]) as the "Add ▸" toolbar would (10.3). */
    internal fun armPlaceForTest(kind: ElementKind, name: String) = armPlace(kind, name)

    /** True while a placement is armed (awaiting a canvas click). */
    internal fun isArmedForTest(): Boolean = placeArmedName != null

    /** Simulates a canvas click at world ([x], [y]) while a placement is armed: places and disarms. */
    internal fun placeAtForTest(x: Double, y: Double) {
        val kind = placeArmedKind ?: return
        val name = placeArmedName ?: return
        controller.placeLayoutElement(kind, name, x, y); disarmPlace(); afterEdit()
    }

    /** The currently armed kind (for the toolbar affordance test), or null. */
    internal fun armedKindForTest(): ElementKind? = placeArmedKind

    /** True when [kind]'s toolbar button is wearing the armed highlight. */
    internal fun toolHighlightedForTest(kind: ElementKind): Boolean = toolButtons[kind]?.border === armedButtonBorder

    /** Cancels an armed placement, as Esc does. */
    internal fun cancelPlaceForTest() = cancelPlacement()

    /** Arms the queue two-click flow for [name] (bypassing the Add Queue dialog) — 10.4. */
    internal fun armQueueForTest(name: String, spacing: Double, maxShown: Int) = armQueue(name, spacing, maxShown)

    /** Simulates one canvas click of the queue flow at world ([x], [y]). */
    internal fun clickQueueForTest(x: Double, y: Double) = placeQueueClick(x, y)

    /** True while the queue head→tail flow is armed. */
    internal fun isQueueArmedForTest(): Boolean = queueArm != null

    /** Arms the path tool for [name] (bypassing the name prompt) — 10.4. */
    internal fun armPathForTest(name: String) {
        clearArmState(); pathArmName = name; highlightTool(null); pathButton?.border = armedButtonBorder
    }

    /** Sets the from-anchor, as clicking it would. */
    internal fun setPathFromForTest(anchor: AnchorRef) { pathFrom = anchor }

    /** Adds a free waypoint, as clicking open canvas would. */
    internal fun addPathWaypointForTest(x: Double, y: Double) { pathWaypoints.add(ksl.animation.LayoutPoint(x, y)) }

    /** Finishes at [to] (the double-clicked destination anchor). */
    internal fun finishPathForTest(to: AnchorRef) = onPathFinish(to)

    /** True while the path tool is armed. */
    internal fun isPathArmedForTest(): Boolean = pathArmName != null

    /** Arms the background tool with [imageRef] (bypassing the file chooser) — 10.4. */
    internal fun armBackgroundForTest(imageRef: String) = armBackground(imageRef)

    /** Simulates a click-drag rectangle from ([x1],[y1]) to ([x2],[y2]) for the background tool. */
    internal fun dragBackgroundRectForTest(x1: Double, y1: Double, x2: Double, y2: Double) {
        bgStart = x1 to y1; placeBackgroundRect(x1, y1, x2, y2)
    }

    /** True while the background tool is armed. */
    internal fun isBackgroundArmedForTest(): Boolean = bgArmRef != null

    /** Arms + places a text annotation at ([x],[y]). */
    internal fun clickShapeTextForTest(x: Double, y: Double, text: String, color: String) {
        shapeText = text; shapeColor = color; armText(); placeShapeText(x, y)
    }
    /** True while the text tool is armed. */
    internal fun isShapeArmedForTest(): Boolean = textArm

    /** The Save As base name for a bound file name — strips the layout extension (item 1). */
    internal fun stripLayoutExtForTest(name: String): String = stripLayoutExt(name)

    /** The object-class legend text for the given classes — the layout-editor hint (item 7). */
    internal fun legendTextForTest(classes: List<ksl.animation.ObjectClassDefinition>): String = legendTextFor(classes)

    /** Applies per-state resource images as the double-click editor's OK would (10.7). */
    internal fun setResourceImagesForTest(name: String, idle: String?, busy: String?, failed: String?, inactive: String?) {
        controller.setResourceImages(name, idle, busy, failed, inactive); afterEdit()
    }

    /** Sets the display type for response [name] via the controller (as the Display-as control does). */
    internal fun setResponseDisplayForTest(name: String, display: ResponseDisplay) {
        controller.setResponseDisplay(name, display); afterEdit()
    }

    /** Force a synchronous refresh of all views (tests, to avoid waiting on the async layout subscription). */
    internal fun refreshForTest() = refreshAll()

    /** Background list entries shown in the Background & Paths tab. */
    internal fun backgroundListForTest(): List<String> = (0 until bgListModel.size()).map { bgListModel.getElementAt(it) }

    /** Path names shown in the Background & Paths tab. */
    internal fun pathListForTest(): List<String> = (0 until pathListModel.size()).map { pathListModel.getElementAt(it) }

    /** Object-style summaries from the layout (one per styled type). */
    internal fun objectStyleListForTest(): List<String> =
        controller.layout.value?.objectClasses?.map { "${it.typeName}  (${it.shape}, ${it.color}, ${it.size})" } ?: emptyList()

    /** Discovered entity-type / process rows shown in the Object Styles / Process Colors tables (batch 3). */
    internal fun objectStyleTypesForTest(): List<String> = styleTypeNames
    internal fun processNamesForTest(): List<String> = processNames

    /** Applies an object style for [type] using [image] as its glyph, as the Object Styles tab would. */
    internal fun addObjectStyleWithImageForTest(type: String, image: String) {
        styleImage.text = image; applyObjectStyle(type)
    }

    /** Applies a process tint color, as the Process Colors tab would (batch 3). */
    internal fun setProcessColorForTest(process: String, color: String) {
        processColorField.hex = color; controller.setProcessColor(process, color); afterEdit()
    }

    /** Conveyor entries shown in the Conveyors tab (P1). */
    internal fun conveyorTabListForTest(): List<String> = (0 until conveyorListModel.size()).map { conveyorListModel.getElementAt(it) }

    /** Adds a storage spanning the given rectangle, as the Storage tool would (#15). */
    internal fun addStorageForTest(name: String, x: Double, y: Double, w: Double, h: Double, style: ksl.animation.StorageStyle) {
        controller.addStorage(name, x, y, w, h, style, 14.0, 30, 0, true, null); afterEdit()
    }

    /** Storage entries shown in the Storages tab (#15). */
    internal fun storageTabListForTest(): List<String> = (0 until storageListModel.size()).map { storageListModel.getElementAt(it) }

    /** Drags storage [name] (grabbed at its corner) to world ([x],[y]) — the canvas move (#15). */
    internal fun dragStorageToForTest(name: String, x: Double, y: Double) { storageMoveDrag = name; controller.moveStorage(name, x, y); storageMoveDrag = null; afterEdit() }

    /** The storage whose footprint contains world point ([wx],[wy]), or null — the G6 rectangle hit test. */
    internal fun storageAtWorldForTest(wx: Double, wy: Double): String? =
        controller.layout.value?.storages?.firstOrNull { storageFootprint(it).contains(wx, wy) }?.suspensionName

    /** Selects/deselects a storage as a click would (G6); returns whether its highlight outline is now shown. */
    internal fun selectStorageForTest(name: String?): Boolean { selectStorage(name); return canvas.highlightRectWorld != null }

    /** Drags the selected storage [name]'s resize grip to world ([x],[y]) — the canvas resize (item 2). */
    internal fun resizeStorageForTest(name: String, x: Double, y: Double) { resizeStorageTo(name, x, y); afterEdit() }

    /** Creates the (straight) conveyor belt for [name] from its inventory structure, as the dialog would (10.5d). */
    internal fun addConveyorForTest(name: String) {
        controller.setConveyorLayout(ksl.animation.ConveyorLayoutElement(name, straightSegmentsFor(name))); afterEdit()
    }

    /** Arms waypoint routing for [name]'s segment [segmentIndex] (10.5d). */
    internal fun armConveyorRouteForTest(name: String, segmentIndex: Int) = armConveyorRoute(name, segmentIndex)

    /** Collects a waypoint at world ([x],[y]) for the armed conveyor segment. */
    internal fun clickConveyorWaypointForTest(x: Double, y: Double) = addConveyorWaypoint(x, y)

    /** Finishes the conveyor segment route (double-click), committing the waypoints. */
    internal fun finishConveyorRouteForTest() = finishConveyorRoute()

    /** True while a conveyor segment is being routed. */
    internal fun isConveyorRouteArmedForTest(): Boolean = conveyorRouteName != null

    /** The conveyor segment whose belt is within [radius] of world ([wx],[wy]) — the double-click re-route pick. */
    internal fun pickConveyorSegmentForTest(wx: Double, wy: Double, radius: Double): Pair<String, Int>? =
        nearestConveyorSegment(wx, wy, radius)

    /** Agent state-color and space list entries shown in the Agents & Spaces tab. */
    internal fun agentStateColorListForTest(): List<String> = (0 until stateColorListModel.size()).map { stateColorListModel.getElementAt(it) }
    internal fun spaceListForTest(): List<String> = (0 until spaceListModel.size()).map { spaceListModel.getElementAt(it) }

    /** Drives the canvas-size fields + Resize. */
    internal fun resizeCanvasForTest(w: Double, h: Double) {
        canvasWidth.text = w.toString(); canvasHeight.text = h.toString(); onResizeCanvas()
    }

    /** The displayed Name cell for [name] in [kind] (includes any response type tag). */
    internal fun nameCellForTest(kind: ElementKind, name: String): String =
        ed(kind, name).tableModel.getValueAt(rowOf(kind, name), 0) as String

    /** The displayed table cells for [name] in [kind]: Placed flag, X text, Y text. */
    internal fun cellsForTest(kind: ElementKind, name: String): Triple<Boolean, String, String> {
        val m = ed(kind, name).tableModel; val r = rowOf(kind, name)
        return Triple(m.getValueAt(r, 1) as Boolean, m.getValueAt(r, 2) as String, m.getValueAt(r, 3) as String)
    }

    /** Whether the preview currently has a built replay model (i.e. a layout is loaded). */
    internal fun previewHasModelForTest(): Boolean = canvas.replay != null

    /** The base directory the preview resolves relative image refs against (layout file's dir, or layouts dir). */
    internal fun previewBaseDirForTest(): java.nio.file.Path? = canvas.replay?.baseDir

    /** The current validation strip text. */
    internal fun validationTextForTest(): String = validationLabel.text

    /** The preview canvas, for the display-gated drag test. */
    internal fun previewCanvasForTest(): SimulationCanvas = canvas

    /** Toggle pan mode (drag pans vs moves elements), as the Pan button would. */
    internal fun setPanModeForTest(on: Boolean) { panMode = on; canvas.panEnabled = on }

    /** The directory the Open/Save choosers start in (created if missing) — should be the model's layouts/. */
    internal fun chooserStartDirForTest(): java.nio.file.Path = layoutsDirEnsured().toPath()

    private companion object {
        /** Screen-space grab radius (px) for the drag-to-move hit-test. */
        const val HIT_RADIUS_PX = 16.0
    }
}
