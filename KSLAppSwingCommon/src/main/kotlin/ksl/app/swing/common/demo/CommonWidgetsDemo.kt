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

package ksl.app.swing.common.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.datetime.Instant
import ksl.app.session.RunEvent
import ksl.app.session.RunWarningType
import ksl.app.settings.UserSettingsStore
import ksl.app.swing.common.editor.SelectionState
import ksl.app.swing.common.editor.UndoStack
import ksl.app.swing.common.editor.UndoableOperation
import ksl.app.swing.common.overridefield.BooleanTriStateOverrideField
import ksl.app.swing.common.overridefield.DoubleOverrideField
import ksl.app.swing.common.overridefield.IntegerOverrideField
import ksl.app.swing.common.overridefield.JsonControlValueField
import ksl.app.swing.common.overridefield.SectionHeaderWithStatus
import ksl.app.swing.common.overridefield.StringControlValueField
import ksl.app.swing.common.results.DefaultDesktopOpener
import ksl.app.swing.common.results.OpenInFileBrowserAction
import ksl.app.swing.common.runcontrol.ConsoleLogPanel
import ksl.app.swing.common.runcontrol.RunningPostureGuard
import ksl.app.swing.common.validation.DocumentHealthBanner
import ksl.app.swing.common.validation.FieldErrorMarker
import ksl.app.swing.common.validation.JumpToErrorAction
import ksl.app.swing.common.validation.RowStatusIcon
import ksl.app.swing.common.validation.WidgetPathRegistry
import ksl.app.swing.common.workspace.RecentWorkingDirectoriesMenu
import ksl.app.swing.common.workspace.SetWorkingDirectoryAction
import ksl.app.swing.common.workspace.WorkspaceStatusBar
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JSeparator
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Hand-runnable demo composing every Phase-6C Common widget into a
 * single JFrame.  Exists for visual evaluation during Phase 6C —
 * not part of the test suite, not shipped in any app.
 *
 * Run from an IDE: right-click `main` → Run.  No bundles, no
 * orchestrators, no real run — every callback prints to stdout or
 * pushes a fake event into the in-process flows.
 *
 * What's on screen:
 *  - **Menu bar** — *File* with Set Working Directory… and the
 *    Recent Working Directories submenu.
 *  - **Document health banner** — sample errors / warnings.
 *  - **Scenarios mock list** (left) — three rows, each carrying a
 *    `RowStatusIcon` driven from the validation bus.
 *  - **Detail pane** (right) — collapsible *Run Parameters* and
 *    *Control Overrides* sections built from the override-field
 *    primitives.
 *  - **Console** — fed by a `MutableSharedFlow<RunEvent>` that the
 *    *Emit Event* button pushes into.
 *  - **Workspace status bar** — bottom strip; click to reveal in
 *    file manager.
 *  - **Simulate Run** — toggles a running flow that drives the
 *    `RunningPostureGuard` and disables a sample *Edit* action.
 *  - **Open Folder** — exercises `OpenInFileBrowserAction` against
 *    the user's home directory.
 *  - **Undo Stack** — *Toggle Flag* button pushes operations onto
 *    the stack; *Undo* / *Redo* menu entries reflect the state.
 *  - **F8 / Shift+F8** — jump between validation errors via
 *    `JumpToErrorAction`.
 */
object CommonWidgetsDemo {

    @JvmStatic
    fun main(args: Array<String>) {
        SwingUtilities.invokeLater { build().isVisible = true }
    }

    private fun build(): JFrame {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

        // ── Stores and buses ───────────────────────────────────────────────
        val settingsStore = UserSettingsStore()    // real ~/.ksl/settings.toml
        val bus = ValidationFeedbackBus(sampleValidationResult())
        val registry = WidgetPathRegistry()
        val eventFlow = MutableSharedFlow<RunEvent>(replay = 0, extraBufferCapacity = 64)
        val runningFlow = MutableStateFlow(false)
        val undoStack = UndoStack()
        val selection = SelectionState<String>()

        val frame = JFrame("KSL Common Widgets Demo")
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.preferredSize = Dimension(960, 720)

        // ── Menu bar ──────────────────────────────────────────────────────
        val setWdAction = SetWorkingDirectoryAction(settingsStore, parentSupplier = { frame })
        val recentMenu = RecentWorkingDirectoriesMenu(settingsStore, scope)
        val editSampleAction = object : javax.swing.AbstractAction("Edit Sample Document") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                println("[demo] Edit action invoked (disabled when running).")
            }
        }
        val undoAction = object : javax.swing.AbstractAction("Undo") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { undoStack.undo() }
        }.apply { isEnabled = false }
        val redoAction = object : javax.swing.AbstractAction("Redo") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { undoStack.redo() }
        }.apply { isEnabled = false }
        scope.launch { undoStack.state.collect {
            undoAction.isEnabled = it.canUndo
            redoAction.isEnabled = it.canRedo
            undoAction.putValue(javax.swing.Action.NAME, if (it.canUndo) "Undo ${it.undoDescription}" else "Undo")
            redoAction.putValue(javax.swing.Action.NAME, if (it.canRedo) "Redo ${it.redoDescription}" else "Redo")
        } }

        frame.jMenuBar = JMenuBar().apply {
            add(JMenu("File").apply {
                add(JMenuItem(setWdAction))
                add(recentMenu)
                addSeparator()
                add(JMenuItem("Exit").apply { addActionListener { frame.dispose() } })
            })
            add(JMenu("Edit").apply {
                add(JMenuItem(undoAction))
                add(JMenuItem(redoAction))
                addSeparator()
                add(JMenuItem(editSampleAction))
            })
        }

        // Running-posture guard — disables the sample Edit action while "running."
        val guard = RunningPostureGuard(runningFlow, scope)
        guard.register(editSampleAction)

        // ── Top: document health banner ────────────────────────────────────
        val banner = DocumentHealthBanner(bus, registry, scope, onMissingWidget = { issue ->
            println("[demo] DocumentHealthBanner: no widget for ${issue.path}")
        })

        // ── Centre: split between scenarios mock list and detail pane ─────
        val scenariosList = scenariosListPanel(bus, scope, selection)
        val detailPane = detailPane(bus, scope, registry)
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scenariosList, detailPane).apply {
            resizeWeight = 0.25
            dividerLocation = 220
        }

        // ── Action strip beneath the editor ───────────────────────────────
        val emitEventBtn = JButton("Emit Sample Event").apply {
            addActionListener {
                scope.launch { eventFlow.emit(randomRunEvent()) }
            }
        }
        val toggleRunBtn = JButton("Simulate Run").apply {
            addActionListener {
                runningFlow.value = !runningFlow.value
                text = if (runningFlow.value) "Stop Running" else "Simulate Run"
            }
        }
        val openFolderBtn = JButton(
            OpenInFileBrowserAction(
                directoryPath = Path.of(System.getProperty("user.home")),
                desktopOpener = DefaultDesktopOpener,
                onUnavailable = { println("[demo] could not open $it") }
            )
        )
        val toggleFlagBtn = JButton("Toggle Flag (push Undo op)").apply {
            var flag = false
            addActionListener {
                val before = flag
                flag = !flag
                undoStack.push(UndoableOperation(
                    description = "Toggle Flag",
                    undo = { flag = before; println("[demo] flag undone → $flag") },
                    redo = { flag = !before; println("[demo] flag redone → $flag") }
                ))
                println("[demo] flag set → $flag")
            }
        }
        val actionStrip = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            add(emitEventBtn)
            add(toggleRunBtn)
            add(openFolderBtn)
            add(toggleFlagBtn)
        }

        // ── Bottom: console + status bar ───────────────────────────────────
        val console = ConsoleLogPanel(eventFlow, scope).apply {
            preferredSize = Dimension(0, 180)
        }
        val statusBar = WorkspaceStatusBar(
            store = settingsStore,
            scope = scope,
            onSetWorkingDirectory = { setWdAction.actionPerformed(java.awt.event.ActionEvent(frame, 0, "")) }
        )

        // ── F8 / Shift+F8 jump bindings ────────────────────────────────────
        val jumpNext = JumpToErrorAction(bus, registry, JumpToErrorAction.Direction.NEXT,
            onMissingWidget = { println("[demo] jumpNext: no widget for ${it.path}") })
        val jumpPrev = JumpToErrorAction(bus, registry, JumpToErrorAction.Direction.PREVIOUS,
            onMissingWidget = { println("[demo] jumpPrev: no widget for ${it.path}") })
        val root = frame.rootPane
        root.actionMap.put("jumpNext", jumpNext)
        root.actionMap.put("jumpPrev", jumpPrev)
        root.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0), "jumpNext")
        root.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F8, KeyEvent.SHIFT_DOWN_MASK), "jumpPrev")

        // ── Assemble ──────────────────────────────────────────────────────
        val content = JPanel(BorderLayout()).apply {
            add(banner, BorderLayout.NORTH)
            val centre = JPanel(BorderLayout()).apply {
                add(split, BorderLayout.CENTER)
                add(actionStrip, BorderLayout.SOUTH)
            }
            add(centre, BorderLayout.CENTER)
            val bottom = JPanel(BorderLayout()).apply {
                add(console, BorderLayout.CENTER)
                add(statusBar, BorderLayout.SOUTH)
            }
            add(bottom, BorderLayout.SOUTH)
        }
        frame.contentPane = content
        frame.pack()
        frame.setLocationRelativeTo(null)
        return frame
    }

    // ── Scenarios mock list (left pane) ───────────────────────────────────

    private fun scenariosListPanel(
        bus: ValidationFeedbackBus,
        scope: CoroutineScope,
        selection: SelectionState<String>
    ): JComponent {
        val names = listOf("LowLoad", "HighLoad", "Bursty")
        val rows = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            for ((i, name) in names.withIndex()) {
                add(scenarioRow(name, "scenarios[$i]", bus, scope, selection, names))
            }
        }
        val scroll = JScrollPane(rows)
        scroll.border = BorderFactory.createTitledBorder("Scenarios")
        return scroll
    }

    private fun scenarioRow(
        name: String,
        pathPrefix: String,
        bus: ValidationFeedbackBus,
        scope: CoroutineScope,
        selection: SelectionState<String>,
        allNames: List<String>
    ): JComponent {
        val statusIcon = RowStatusIcon(pathPrefix, bus, scope)
        val label = JLabel(name)
        val row = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
            add(statusIcon, BorderLayout.WEST)
            add(label, BorderLayout.CENTER)
        }
        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                when {
                    e.isShiftDown -> selection.extend(name, allNames)
                    e.isControlDown || e.isMetaDown -> selection.toggle(name)
                    else -> selection.select(name)
                }
                println("[demo] selection → ${selection.selection.value}")
            }
        })
        return row
    }

    // ── Detail pane (right) ───────────────────────────────────────────────

    private fun detailPane(
        bus: ValidationFeedbackBus,
        scope: CoroutineScope,
        registry: WidgetPathRegistry
    ): JComponent {
        val outer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        }

        // Run Parameters section
        val runParamsBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = true
        }
        val runHeader = SectionHeaderWithStatus(
            title = "Run Parameters",
            pathPrefix = "scenarios[0].runOverrides",
            bus = bus,
            scope = scope,
            initiallyExpanded = true,
            onToggle = { expanded -> runParamsBody.isVisible = expanded; outer.revalidate(); outer.repaint() }
        )
        runParamsBody.add(labeledRow(
            "Number of replications",
            "scenarios[0].runOverrides.numberOfReplications",
            IntegerOverrideField(modelDefault = 30, onValueChange = { println("[demo] reps → $it") }),
            bus, scope, registry
        ))
        runParamsBody.add(labeledRow(
            "Length of replication",
            "scenarios[0].runOverrides.lengthOfReplication",
            DoubleOverrideField(modelDefault = 500.0, onValueChange = { println("[demo] length → $it") }),
            bus, scope, registry
        ))
        runParamsBody.add(labeledRow(
            "Antithetic",
            "scenarios[0].runOverrides.antitheticOption",
            BooleanTriStateOverrideField(onValueChange = { println("[demo] antithetic → $it") }),
            bus, scope, registry
        ))

        // Control Overrides section
        val controlsBody = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isVisible = true
        }
        val controlsHeader = SectionHeaderWithStatus(
            title = "Control Overrides",
            pathPrefix = "scenarios[0].controlOverrides",
            bus = bus,
            scope = scope,
            initiallyExpanded = true,
            onToggle = { expanded -> controlsBody.isVisible = expanded; outer.revalidate(); outer.repaint() }
        )
        controlsBody.add(labeledRow(
            "Server.policy (allowedValues)",
            "scenarios[0].controlOverrides.string.Server.policy",
            StringControlValueField(
                modelDefault = "ROUND_ROBIN",
                allowedValues = listOf("ROUND_ROBIN", "RANDOM", "PRIORITY"),
                onValueChange = { println("[demo] policy → $it") }
            ),
            bus, scope, registry
        ))
        controlsBody.add(labeledRow(
            "Server.tag (free text)",
            "scenarios[0].controlOverrides.string.Server.tag",
            StringControlValueField(
                modelDefault = "alpha",
                onValueChange = { println("[demo] tag → $it") }
            ),
            bus, scope, registry
        ))
        controlsBody.add(labeledRow(
            "Server.profile (JSON)",
            "scenarios[0].controlOverrides.json.Server.profile",
            JsonControlValueField(
                modelDefault = """{"replicas":1}""",
                onValueChange = { println("[demo] profile → $it") }
            ),
            bus, scope, registry
        ))

        outer.add(runHeader)
        outer.add(runParamsBody)
        outer.add(Box.createVerticalStrut(8))
        outer.add(JSeparator())
        outer.add(Box.createVerticalStrut(8))
        outer.add(controlsHeader)
        outer.add(controlsBody)
        outer.add(Box.createVerticalGlue())

        val scroll = JScrollPane(outer)
        scroll.border = BorderFactory.createTitledBorder("Scenario detail (LowLoad)")
        return scroll
    }

    private fun labeledRow(
        label: String,
        path: String,
        field: JComponent,
        bus: ValidationFeedbackBus,
        scope: CoroutineScope,
        registry: WidgetPathRegistry
    ): JComponent {
        val wrapped = FieldErrorMarker.attach(field, path, bus, scope, registry)
        val row = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(3, 0, 3, 0)
            add(JLabel(label).apply { preferredSize = Dimension(220, preferredSize.height) }, BorderLayout.WEST)
            add(wrapped, BorderLayout.CENTER)
        }
        return row
    }

    // ── Sample data ───────────────────────────────────────────────────────

    private fun sampleValidationResult(): ValidationResult = ValidationResult(
        errors = listOf(
            FieldError(
                path = "scenarios[0].runOverrides.numberOfReplications",
                message = "Number of replications must be ≥ 1.",
                severity = ValidationSeverity.ERROR,
                code = "RUN_PARAM_REPS_INVALID"
            ),
            FieldError(
                path = "scenarios[1].controlOverrides.string.Server.policy",
                message = "Value 'PRIORITY' is not currently supported.",
                severity = ValidationSeverity.ERROR,
                code = "CONTROL_OUT_OF_RANGE"
            )
        ),
        warnings = listOf(
            FieldError(
                path = "scenarios[0].runOverrides.lengthOfReplication",
                message = "Length is unusually short; statistics may be unstable.",
                severity = ValidationSeverity.WARNING,
                code = "RUN_PARAM_LENGTH_SHORT"
            ),
            FieldError(
                path = "scenarios[2].name",
                message = "Scenario name duplicates an earlier scenario.",
                severity = ValidationSeverity.WARNING,
                code = "SCENARIO_NAME_DUPLICATE"
            )
        )
    )

    private var eventCounter: Int = 0
    private fun randomRunEvent(): RunEvent {
        eventCounter++
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        return when (eventCounter % 5) {
            0 -> RunEvent.ReplicationRunStarted("demo", "MM1", 10, now)
            1 -> RunEvent.ReplicationStarted(eventCounter, 10)
            2 -> RunEvent.ReplicationEnded(eventCounter, 10)
            3 -> RunEvent.RunWarning(RunWarningType.InfiniteHorizonNoTimeout("MM1"))
            else -> RunEvent.ScenarioCompleted("Scenario$eventCounter", eventCounter, 3, snapshot = null)
        }
    }
}

fun main() = CommonWidgetsDemo.main(emptyArray())
