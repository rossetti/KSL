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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.animation.AnchorRef
import ksl.animation.AnimationEvent
import ksl.animation.AnimationInventory
import ksl.animation.AnimationLayout
import ksl.animation.LayoutPoint
import ksl.animation.LocationLayoutElement
import ksl.animation.TraceFileReader
import ksl.app.swing.animation.io.AnimationSource
import ksl.app.swing.animation.replay.ObjectTypeNames
import ksl.app.swing.animation.replay.ObservedExtent
import ksl.app.swing.animation.replay.ReplayModel
import ksl.app.swing.animation.replay.autoLayout
import ksl.app.swing.animation.replay.objectGlyphSize
import ksl.app.swing.animation.replay.withSeededObjectClasses
import ksl.app.swing.animation.replay.withSpaceGeometry
import ksl.animation.CaptureMode
import ksl.animation.CaptureSpec
import ksl.animation.CaptureWindow
import ksl.animation.ElementKind
import ksl.animation.ElementSelector
import ksl.animation.ValidationReport
import ksl.animation.animationInventory
import ksl.animation.scaffoldLayout
import ksl.animation.validateAgainst
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RVParameterOverride
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationToml
import ksl.app.config.ScenarioSpec
import ksl.app.config.TracingConfig
import ksl.app.KSLAppSession
import ksl.app.RunSpec
import ksl.app.session.AppWorkspacePaths
import ksl.app.editor.BundleLibraryController
import ksl.app.editor.ConfigurationEditorState
import ksl.app.editor.DocumentLifecycleController
import ksl.app.editor.RunLifecycleController
import ksl.app.session.RunEvent
import ksl.app.session.RunHandle
import ksl.app.session.RunResult
import ksl.app.settings.UserSettingsStore
import ksl.app.single.results.SingleAppPaths
import ksl.app.validation.ValidationFeedbackBus
import ksl.controls.ModelControlsExport
import ksl.controls.experiments.ExperimentRunDefaults
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelCatalog
import ksl.utilities.random.rvariable.parameters.RVParameterData
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Headless heart of the animation authoring app (Phase 9C.1): the display-free state and
 * configuration owner that every tab is a thin view over. Mirrors `SingleAppController`'s architecture
 * (probe-time snapshots, `ConfigurationEditorState` for the shared parameter/control/RV panels, TOML
 * config load/save, document dirty-tracking) and adds the three animation-specific concerns:
 *
 *  - **Inventory** ([inventory]) — the animatable elements extracted from a probe build
 *    ([Model.animationInventory], 9A.3); drives editor pick-lists and author-time validation.
 *  - **Capture** ([captureSpec]) — the WHAT/WHEN of trace capture ([CaptureSpec], 9A.2), persisted in
 *    the run configuration's [TracingConfig].
 *  - **Layout** ([layout]) — the presentation ([AnimationLayout]), a *separate document* with its own
 *    file/dirty tracking ([layoutFile]/[layoutDirty]), matching the two-file animation format.
 *
 * Run submission / trace production is intentionally **not** here; it lands with the Capture & Run tabs
 * (9D). This class is therefore fully testable without a display.
 *
 * @param appName window title and (for [ModelReference.Embedded]) the model identifier
 * @param modelBuilder the model builder, probed once at construction for defaults/controls/RVs/inventory
 * @param bundleLibrary non-null only in bundle mode; governs [loadConfiguration] dispatch
 * @param sourceRef the model reference written into a saved configuration
 */
class AnimationAppController(
    val appName: String,
    val modelBuilder: ModelBuilderIfc,
    val bundleLibrary: BundleLibraryController? = null,
    val sourceRef: ModelReference = ModelReference.Embedded(appName),
    /**
     * False for the **no-model** startup state (mirrors the Experiment app: discover bundles, but select no
     * model until the user opens one). In that state the probe builds an empty model, so every snapshot is
     * empty and run/scaffold are inert; the frame shows an "open a model" prompt and switches to a real
     * model-backed controller (via `fromBundle`) once the user picks one.
     */
    val hasModel: Boolean = true,
) : AutoCloseable, ConfigurationEditorState {

    /** The bundle this model came from, or null in embedded/builder mode (for the header selector). */
    val bundleId: String? get() = (sourceRef as? ModelReference.ByBundleAndModelId)?.bundleId

    /** The model's id within its bundle, or null in embedded/builder mode. */
    val modelId: String? get() = (sourceRef as? ModelReference.ByBundleAndModelId)?.modelId

    /** Scope for EDT-confined coroutine work (panel collectors wire here later). */
    override val edtScope: CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    /** Validation bus for the shared parameter/control/RV panels. */
    override val validationBus: ValidationFeedbackBus = ValidationFeedbackBus()

    // ── Probe-time snapshots ────────────────────────────────────────────────

    override val modelDefaults: ExperimentRunDefaults
    override val controlsSnapshot: ModelControlsExport
    override val rvSnapshot: List<RVParameterData>
    override val modelCatalog: ModelCatalog?

    /** Sanitized probe-captured model name; empty when the probe failed. */
    val modelName: String

    /** The animatable elements of the model (9A.3), captured at probe time; empty when the probe failed. */
    val inventory: AnimationInventory

    /** `null` when the probe build succeeded; the underlying Throwable otherwise. */
    val probeFailure: Throwable?

    init {
        val probe = probeModel()
        this.modelDefaults = probe.defaults
        this.controlsSnapshot = probe.controlsSnapshot
        this.rvSnapshot = probe.rvSnapshot
        this.modelCatalog = probe.modelCatalog
        this.modelName = probe.modelName
        this.inventory = probe.inventory
        this.probeFailure = probe.failure
    }

    private data class ProbeResult(
        val defaults: ExperimentRunDefaults,
        val controlsSnapshot: ModelControlsExport,
        val rvSnapshot: List<RVParameterData>,
        val modelCatalog: ModelCatalog?,
        val modelName: String,
        val inventory: AnimationInventory,
        val failure: Throwable?
    )

    private fun probeModel(): ProbeResult = try {
        val model: Model = modelBuilder.build(null, null)
        val descriptor = model.modelDescriptor()
        ProbeResult(
            defaults = descriptor.experimentRunDefaults,
            controlsSnapshot = descriptor.controls,
            rvSnapshot = descriptor.rvParameterData,
            modelCatalog = descriptor.catalog,
            modelName = model.name,
            // Read the manifest from the descriptor (10.1c) — the single source a bundle also caches —
            // instead of re-extracting, so the editor and a cached bundle agree on one inventory.
            inventory = descriptor.animationInventory,
            failure = null
        )
    } catch (t: Throwable) {
        ProbeResult(
            defaults = SAFE_FALLBACK_DEFAULTS,
            controlsSnapshot = ModelControlsExport(modelName = appName),
            rvSnapshot = emptyList(),
            modelCatalog = null,
            modelName = "",
            inventory = AnimationInventory(),
            failure = t
        )
    }

    // ── ConfigurationEditorState — pending overrides ────────────────────────

    private val myRunOverrides = MutableStateFlow(ExperimentRunOverrides())
    override val runOverrides: StateFlow<ExperimentRunOverrides> = myRunOverrides.asStateFlow()

    private val myControlOverrides = MutableStateFlow(ModelControlsExport(modelName = controlsSnapshot.modelName))
    override val controlOverrides: StateFlow<ModelControlsExport> = myControlOverrides.asStateFlow()

    private val myRVOverrides = MutableStateFlow<List<RVParameterOverride>>(emptyList())
    override val rvOverrides: StateFlow<List<RVParameterOverride>> = myRVOverrides.asStateFlow()

    private val myOutputConfig = MutableStateFlow(OutputConfig())
    /** Pending output options; the `outputDirectory` is install-local and re-computed at run time. */
    val outputConfig: StateFlow<OutputConfig> = myOutputConfig.asStateFlow()

    // ── Capture spec (WHAT/WHEN) ────────────────────────────────────────────

    private val myCaptureSpec = MutableStateFlow(CaptureSpec())

    /** Opt-in agent debugging/teaching overlays to capture (G10–G12); default all off. */
    private val myOverlaySpec = MutableStateFlow(ksl.animation.OverlaySpec.OFF)
    val overlaySpec: StateFlow<ksl.animation.OverlaySpec> = myOverlaySpec.asStateFlow()

    /** Toggle capture of the flow-field gradient overlay on the next run (G11). */
    fun setCaptureFlowField(on: Boolean) {
        if (myOverlaySpec.value.flowField != on) myOverlaySpec.value = myOverlaySpec.value.copy(flowField = on)
    }

    /** Toggle capture of the planned-path overlay on the next run (G12). */
    fun setCapturePlannedPaths(on: Boolean) {
        if (myOverlaySpec.value.plannedPaths != on) myOverlaySpec.value = myOverlaySpec.value.copy(plannedPaths = on)
    }

    /** Toggle capture of the transient marker-pulse overlay on the next run (G-animated). */
    fun setCaptureMarkerPulses(on: Boolean) {
        if (myOverlaySpec.value.markerPulses != on) myOverlaySpec.value = myOverlaySpec.value.copy(markerPulses = on)
    }

    /** Toggle capture of the agent velocity-vector overlay on the next run (G10). */
    fun setCaptureVelocities(on: Boolean) {
        if (myOverlaySpec.value.velocities != on) myOverlaySpec.value = myOverlaySpec.value.copy(velocities = on)
    }

    /** Toggle capture of the agent net-force-vector overlay on the next run (G10). */
    fun setCaptureForces(on: Boolean) {
        if (myOverlaySpec.value.forces != on) myOverlaySpec.value = myOverlaySpec.value.copy(forces = on)
    }
    /** The authored capture spec (selective elements + window), persisted in [TracingConfig.capture]. */
    val captureSpec: StateFlow<CaptureSpec> = myCaptureSpec.asStateFlow()

    // ── Document lifecycles: config (run/capture) and layout, tracked separately ──

    private val configLifecycle = DocumentLifecycleController()
    /** Path of the configuration (`.toml`) currently associated with the in-memory state, or null. */
    val currentFile: StateFlow<Path?> = configLifecycle.currentFile
    /** True when the configuration differs from its saved file. */
    val isDirty: StateFlow<Boolean> = configLifecycle.isDirty

    private val layoutLifecycle = DocumentLifecycleController()
    private val myLayout = MutableStateFlow<AnimationLayout?>(null)
    /** The authored presentation layout, or null when none has been set/loaded. */
    val layout: StateFlow<AnimationLayout?> = myLayout.asStateFlow()
    /** Path of the layout (`.lay.json`) currently associated with [layout], or null. */
    val layoutFile: StateFlow<Path?> = layoutLifecycle.currentFile
    /** True when [layout] differs from its saved file. */
    val layoutDirty: StateFlow<Boolean> = layoutLifecycle.isDirty

    init {
        if (modelName.isNotBlank()) {
            myOutputConfig.value = myOutputConfig.value.copy(analysisName = modelName)
        }
    }

    // ── ConfigurationEditorState — mutators (each marks the config dirty) ────

    override fun updateRunOverride(transform: (ExperimentRunOverrides) -> ExperimentRunOverrides) {
        val updated = transform(myRunOverrides.value)
        if (updated != myRunOverrides.value) {
            myRunOverrides.value = updated
            configLifecycle.markDirty()
        }
    }

    override fun setNumericOverride(keyName: String, value: Double) {
        val template = controlsSnapshot.numericControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            numericControls = myControlOverrides.value.numericControls.filter { it.keyName != keyName } + template.copy(value = value)
        )
        configLifecycle.markDirty()
    }

    override fun clearNumericOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.numericControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(numericControls = current.numericControls.filter { it.keyName != keyName })
        configLifecycle.markDirty()
    }

    override fun setStringOverride(keyName: String, value: String) {
        val template = controlsSnapshot.stringControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            stringControls = myControlOverrides.value.stringControls.filter { it.keyName != keyName } + template.copy(value = value)
        )
        configLifecycle.markDirty()
    }

    override fun clearStringOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.stringControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(stringControls = current.stringControls.filter { it.keyName != keyName })
        configLifecycle.markDirty()
    }

    override fun setJsonOverride(keyName: String, jsonValue: String) {
        val template = controlsSnapshot.jsonControls.firstOrNull { it.keyName == keyName } ?: return
        myControlOverrides.value = myControlOverrides.value.copy(
            jsonControls = myControlOverrides.value.jsonControls.filter { it.keyName != keyName } + template.copy(jsonValue = jsonValue)
        )
        configLifecycle.markDirty()
    }

    override fun clearJsonOverride(keyName: String) {
        val current = myControlOverrides.value
        if (current.jsonControls.none { it.keyName == keyName }) return
        myControlOverrides.value = current.copy(jsonControls = current.jsonControls.filter { it.keyName != keyName })
        configLifecycle.markDirty()
    }

    override fun setRVOverride(rvName: String, paramName: String, value: Double) {
        if (rvSnapshot.none { it.rvName == rvName && it.paramName == paramName }) return
        val without = myRVOverrides.value.filterNot { it.rvName == rvName && it.paramName == paramName }
        myRVOverrides.value = without + RVParameterOverride(rvName, paramName, value)
        configLifecycle.markDirty()
    }

    override fun clearRVOverride(rvName: String, paramName: String) {
        val current = myRVOverrides.value
        if (current.none { it.rvName == rvName && it.paramName == paramName }) return
        myRVOverrides.value = current.filterNot { it.rvName == rvName && it.paramName == paramName }
        configLifecycle.markDirty()
    }

    // ── Output mutators ─────────────────────────────────────────────────────

    /** Set the analysis name (identity for output routing). */
    fun setAnalysisName(name: String) {
        if (myOutputConfig.value.analysisName == name) return
        myOutputConfig.value = myOutputConfig.value.copy(analysisName = name)
        configLifecycle.markDirty()
    }

    // ── Capture mutators (each marks the config dirty) ──────────────────────

    /** Set the capture mode ([CaptureMode.ALL] or [CaptureMode.SELECTED]). */
    fun setCaptureMode(mode: CaptureMode) {
        if (myCaptureSpec.value.mode == mode) return
        myCaptureSpec.value = myCaptureSpec.value.copy(mode = mode)
        configLifecycle.markDirty()
    }

    /** Add an element to the capture *include* set (no-op if already present). */
    fun addInclude(kind: ElementKind, name: String) = mutateSelectors { spec ->
        val sel = ElementSelector(kind, name)
        if (sel in spec.include) spec else spec.copy(include = spec.include + sel)
    }

    /** Remove an element from the capture *include* set. */
    fun removeInclude(kind: ElementKind, name: String) = mutateSelectors { spec ->
        spec.copy(include = spec.include.filterNot { it.kind == kind && it.name == name })
    }

    /** Add an element to the capture *exclude* set (no-op if already present). */
    fun addExclude(kind: ElementKind, name: String) = mutateSelectors { spec ->
        val sel = ElementSelector(kind, name)
        if (sel in spec.exclude) spec else spec.copy(exclude = spec.exclude + sel)
    }

    /** Remove an element from the capture *exclude* set. */
    fun removeExclude(kind: ElementKind, name: String) = mutateSelectors { spec ->
        spec.copy(exclude = spec.exclude.filterNot { it.kind == kind && it.name == name })
    }

    /** Set the capture window to `[startTime, endTime]`. */
    fun setCaptureWindow(startTime: Double, endTime: Double) {
        val window = CaptureWindow(startTime, endTime)
        if (myCaptureSpec.value.captureWindow == window) return
        myCaptureSpec.value = myCaptureSpec.value.copy(captureWindow = window)
        configLifecycle.markDirty()
    }

    /** Clear the capture window (capture the whole run). */
    fun clearCaptureWindow() {
        if (myCaptureSpec.value.captureWindow == null) return
        myCaptureSpec.value = myCaptureSpec.value.copy(captureWindow = null)
        configLifecycle.markDirty()
    }

    private inline fun mutateSelectors(transform: (CaptureSpec) -> CaptureSpec) {
        val updated = transform(myCaptureSpec.value)
        if (updated != myCaptureSpec.value) {
            myCaptureSpec.value = updated
            configLifecycle.markDirty()
        }
    }

    // ── Layout document (separate from the config) ──────────────────────────

    /** Replace the in-memory [layout]; marks the layout document dirty. */
    fun setLayout(newLayout: AnimationLayout) {
        myLayout.value = newLayout
        layoutLifecycle.markDirty()
    }

    /** Build a starter layout from a fresh probe model ([Model.scaffoldLayout]) and set it. No-op on build failure. */
    fun scaffoldLayout() {
        if (!hasModel) return // no model selected yet — nothing to scaffold
        buildScaffoldLayout()?.let { setLayout(it) }
    }

    /**
     * Builds a starter layout from a fresh probe model *without* changing the current layout document.
     * Used as the Replay fallback so a produced trace still renders its elements when the author hasn't
     * scaffolded or opened a layout yet. Returns null on build failure.
     */
    fun buildScaffoldLayout(): AnimationLayout? =
        runCatching { modelBuilder.build(null, null).scaffoldLayout() }.getOrNull()
            ?.let { withScaffoldedSpaces(it) }
            ?.let { withModelObjectClasses(it) }
            ?.let { withModelLocations(it) }        // finalize location positions (MDS) first...
            ?.let { withMoverPositionsAtHome(it) }  // ...so movers anchor to the final home-location position

    /**
     * Anchors each scaffolded movable resource at its home-base station's placed position (filling `homeBase`
     * from the inventory when absent), so it renders on the static Layout tab — the scaffold otherwise declares
     * movers with no position, leaving them visible only during replay.
     */
    private fun withMoverPositionsAtHome(layout: AnimationLayout): AnimationLayout {
        if (layout.movableResources.isEmpty()) return layout
        // A mover's home base is a location (fall back to a station for legacy layouts); park homeless movers somewhere.
        val fallback = layout.locations.firstOrNull { it.position != null }?.position ?: layout.stations.firstOrNull()?.position
        return layout.copy(movableResources = layout.movableResources.map { mr ->
            if (mr.position != null) return@map mr // already positioned
            val hb = mr.homeBase ?: inventory.movableHomeBases[mr.name]
            val pos = hb?.let { layout.positionOf(ElementKind.LOCATION, it) ?: layout.positionOf(ElementKind.STATION, it) } ?: fallback
            mr.copy(homeBase = hb ?: mr.homeBase, position = pos)
        })
    }

    /** Generate and apply an auto-layout from the richest available source (see [buildAutoLayout]). */
    fun autoLayout(source: AutoLayoutSource = AutoLayoutSource.AUTO) {
        if (!hasModel) return
        buildAutoLayout(source)?.let { setLayout(it) }
    }

    /**
     * Builds (without applying) the auto-layout for [source]: the most-recent trace — real positions (mined
     * from the trace) plus the model's faithful geometry — when one exists, carries real coordinates, and
     * [source] allows it; otherwise the static model scaffold. Always falls through to the scaffold.
     */
    fun buildAutoLayout(source: AutoLayoutSource = AutoLayoutSource.AUTO): AnimationLayout? {
        val tryTrace = source == AutoLayoutSource.AUTO && hasTrace()
        return (if (tryTrace) buildTraceLayout() else null) ?: buildScaffoldLayout()
    }

    /**
     * Builds a layout from the latest trace, or null to defer to the scaffold. The scaffold wins only for a
     * coordinate-free spatial-mover model (a true DistancesModel): it emits NaN positions, so the trace yields
     * only a crude ring while the scaffold's MDS placement is faithful. Every other model class — process /
     * station / conveyor (no movers) and agent models (their declared space frames them) — renders from the
     * richer trace, even without planar coordinates.
     */
    private fun buildTraceLayout(): AnimationLayout? {
        val trace = myLastTraceFile.value ?: listTraces().firstOrNull() ?: return null
        return runCatching {
            val (header, events) = TraceFileReader.readAll(trace)
            // One pass: planar extent (finite mover coords) + whether the model has any movers / agents.
            val extentAcc = ObservedExtent()
            var hasMovers = false
            var hasAgents = false
            for (event in events) {
                extentAcc.accept(event)
                when (event) {
                    is AnimationEvent.SpatialElementMoved -> hasMovers = true
                    is AnimationEvent.AgentPositionChanged -> hasAgents = true
                    else -> {}
                }
            }
            // Defer to the scaffold ONLY for a coordinate-free spatial-mover model (true DistancesModel): MDS
            // beats the trace's crude ring. Process/station/conveyor (no movers) and agent models render from
            // the trace, framed by their declared space even without planar coordinates.
            if (extentAcc.result() == null && hasMovers && !hasAgents) return@runCatching null
            val replay = ReplayModel.build(AnimationSource(layout = null, header = header, events = events))
            withModelLocations(withModelGeometry(replay.autoLayout(events)))
        }.getOrNull()
    }

    /**
     * Stamps the model's faithful space geometry (obstacle maps / grid-graph costs) onto [layout] from the
     * inventory — the static source a trace can't carry (P5a/G2). Shared by the scaffold and trace paths.
     */
    fun withModelGeometry(layout: AnimationLayout): AnimationLayout =
        layout.withSpaceGeometry(inventory.spaces.mapNotNull { it.geometry })

    /**
     * Seeds an editable object-class per discovered entity type, sized to [layout]'s spaces (C1). Agent types
     * aren't structural (they appear only in a trace), so the scaffold seeds entity types from the inventory;
     * the trace path additionally seeds agent types it observes.
     */
    fun withModelObjectClasses(layout: AnimationLayout): AnimationLayout =
        layout.withSeededObjectClasses(
            // Process entities only: agents are seeded from the trace (movers), since agent visual-ness is runtime (G3).
            inventory.entityTypes.filter { it.include && !it.isAgent }.map { it.typeName },
            objectGlyphSize(layout.spaces)
        )

    /**
     * Stamps a `LocationLayoutElement` for each model-declared named location that has known coordinates
     * (an agent `Context.location(...)` — e.g. a depot / drop-off), unless the layout already has it (G1).
     * These are structural (from the inventory), like the space geometry, so a trace that never *moves*
     * between them still surfaces them. Coordinate-free names (null position) are left for MDS placement.
     */
    fun withModelLocations(layout: AnimationLayout): AnimationLayout {
        // Inventory positions are authoritative (Phase 5 / D1): override a same-named layout location's position with
        // the model position and add any that are absent. Positions come from an agent Context.location, finite
        // LocationIfc coords, or a DistancesModel's MDS placement — so MDS wins over auto-layout's arbitrary ring.
        val known = inventory.locationInfos.mapNotNull { li ->
            val x = li.x; val y = li.y // local vals: a cross-module nullable prop won't smart-cast
            if (x != null && y != null) li.name to LayoutPoint(x, y) else null
        }.toMap()
        if (known.isEmpty()) return layout
        val have = layout.locations.map { it.locationName }.toSet()
        val overridden = layout.locations.map { loc -> known[loc.locationName]?.let { loc.copy(position = it) } ?: loc }
        val added = known.filterKeys { it !in have }.map { (name, p) -> LocationLayoutElement(name, p) }
        return layout.copy(locations = overridden + added)
    }

    /**
     * Ensures an agent model's space(s) are placed in the scaffolded [layout] (10.8, §7.2). Grid agents emit
     * (col,row) cells; without the space the fit collapses them into a blob, so map each inventory [SpaceInfo]
     * to a descriptor (a display cell size for grids, framing ~70% of the canvas) when the layout has none,
     * then bring the model-linked obstacles/costs along via the shared geometry overlay.
     */
    private fun withScaffoldedSpaces(layout: AnimationLayout): AnimationLayout {
        if (layout.spaces.isNotEmpty() || inventory.spaces.isEmpty()) return layout
        return withModelGeometry(layout.copy(spaces = inventory.spaces.map { it.toDescriptor(layout.width, layout.height) }))
    }

    private fun ksl.animation.SpaceInfo.toDescriptor(w: Double, h: Double): ksl.animation.SpatialSpaceDescriptor =
        when (kind) {
            ksl.animation.SpaceInfo.SpaceKind.GRID -> {
                val c = (cols ?: 1).coerceAtLeast(1); val r = (rows ?: 1).coerceAtLeast(1)
                val cell = minOf(w * 0.7 / c, h * 0.7 / r).coerceAtLeast(6.0)
                ksl.animation.SpatialSpaceDescriptor.Grid(name, c, r, cell, torus = torus)
            }
            ksl.animation.SpaceInfo.SpaceKind.CONTINUOUS -> ksl.animation.SpatialSpaceDescriptor.Continuous(
                name, xMin ?: 0.0, xMax ?: w, yMin ?: 0.0, yMax ?: h, torus
            )
        }

    /** Load a layout from [path]; the loaded state is, by definition, the saved state. */
    fun loadLayout(path: Path) {
        // Upgrade legacy layouts (locations saved as stations, Phase 7) before backfilling mover home bases so at-rest
        // movers still anchor to their home, matching what the replay draws.
        myLayout.value = withMoverHomeBases(withStationsMigratedToLocations(AnimationLayout.read(path)))
        layoutLifecycle.markSaved(path)
    }

    /**
     * Reclassify any legacy `stations` entry whose name is a model location into `locations` (Phase 7 migration), so
     * layouts saved before the Station/Location split load with the right kinds and render identically. Idempotent.
     */
    private fun withStationsMigratedToLocations(layout: AnimationLayout): AnimationLayout {
        val locNames = inventory.locations.toSet()
        if (locNames.isEmpty() || layout.stations.none { it.stationName in locNames }) return layout
        val (toLocations, keepStations) = layout.stations.partition { it.stationName in locNames }
        val have = layout.locations.map { it.locationName }.toSet()
        val migrated = toLocations.filterNot { it.stationName in have }
            .map { LocationLayoutElement(it.stationName, it.position, it.label) }
        return layout.copy(stations = keepStations, locations = layout.locations + migrated)
    }

    /** Fill each mover's [MovableResourceLayoutElement.homeBase] from the inventory when absent (10.8 follow-up). */
    private fun withMoverHomeBases(layout: AnimationLayout): AnimationLayout {
        if (inventory.movableHomeBases.isEmpty()) return layout
        if (layout.movableResources.none { it.homeBase == null && inventory.movableHomeBases.containsKey(it.name) }) return layout
        return layout.copy(movableResources = layout.movableResources.map {
            if (it.homeBase == null) it.copy(homeBase = inventory.movableHomeBases[it.name]) else it
        })
    }

    /** Write the current [layout] to [path] (creating parent dirs) and record it as the layout's saved file. */
    fun saveLayout(path: Path) {
        val current = layout.value ?: return
        path.parent?.let { Files.createDirectories(it) }
        // Default to TOML (the convention across the KSL apps); write JSON only when the path is explicitly .json.
        if (path.fileName.toString().endsWith(".json", ignoreCase = true)) current.writeToFile(path) else current.writeTomlToFile(path)
        layoutLifecycle.markSaved(path)
    }

    // ── Layout editing (9F.2): granular mutators over the active layout ──────

    /** Start a new, empty layout (default canvas, no elements); becomes the active, unsaved, unbound document. */
    fun newBlankLayout() {
        myLayout.value = blankLayout()
        layoutLifecycle.reset()   // unbind any saved file — this is a new document…
        layoutLifecycle.markDirty() // …with unsaved content
    }

    /** A blank layout titled after the model, with a default canvas. */
    private fun blankLayout(): AnimationLayout =
        AnimationLayout(title = modelName.ifBlank { appName })

    /** The active layout, or a fresh blank one when none is set yet — so edits can begin on an empty canvas. */
    private fun activeOrBlank(): AnimationLayout = myLayout.value ?: blankLayout()

    /** Move the placed ([kind], [name]) element to ([x], [y]); marks the layout dirty. */
    fun moveLayoutElement(kind: ElementKind, name: String, x: Double, y: Double) =
        setLayout(activeOrBlank().withElementMoved(kind, name, x, y))

    /** Add a default-placed ([kind], [name]) element (cascaded so adds don't perfectly overlap); marks dirty. */
    fun addLayoutElement(kind: ElementKind, name: String) {
        val base = activeOrBlank()
        if (base.isPlaced(kind, name)) return
        // Cascade into wrapping columns that stay inside the canvas, so a many-element add never lands an
        // element off the edge where it can't be selected/moved (#4/#12).
        val n = SUPPORTED_LAYOUT_KINDS.sumOf { base.placedNames(it).size }
        val margin = 80.0; val rowH = 36.0; val colW = 160.0
        val perCol = (((base.height - 2 * margin) / rowH).toInt()).coerceAtLeast(1)
        val x = (margin + (n / perCol) * colW).coerceIn(margin, (base.width - margin).coerceAtLeast(margin))
        val y = margin + (n % perCol) * rowH
        setLayout(base.withElementAdded(kind, name, x, y))
    }

    /**
     * Place (or, if already placed, move) the ([kind], [name]) element at world point ([x], [y]) — the
     * click-to-place action from the layout canvas (10.3); marks the layout dirty.
     */
    fun placeLayoutElement(kind: ElementKind, name: String, x: Double, y: Double) {
        val base = activeOrBlank()
        setLayout(if (base.isPlaced(kind, name)) base.withElementMoved(kind, name, x, y) else base.withElementAdded(kind, name, x, y))
    }

    /**
     * Places movable resource [name] at its home-base location's placed position (10.8/C5). Returns false (no
     * change) when the mover has no home base or that location isn't placed yet — the caller falls back to
     * click-to-place.
     */
    fun placeMoverAtHome(name: String): Boolean {
        val home = inventory.movableHomeBases[name] ?: return false
        val base0 = activeOrBlank()
        val pos = base0.positionOf(ElementKind.LOCATION, home) ?: base0.positionOf(ElementKind.STATION, home) ?: return false
        placeLayoutElement(ElementKind.MOVABLE_RESOURCE, name, pos.x, pos.y)
        setLayout(activeOrBlank().copy(movableResources = activeOrBlank().movableResources.map {
            if (it.name == name) it.copy(homeBase = home) else it
        }))
        return true
    }

    /** Remove the placed ([kind], [name]) element; marks the layout dirty. */
    fun removeLayoutElement(kind: ElementKind, name: String) =
        setLayout(activeOrBlank().withElementRemoved(kind, name))

    /** Resize the layout canvas; marks the layout dirty. */
    fun setLayoutCanvasSize(width: Double, height: Double) =
        setLayout(activeOrBlank().withCanvasSize(width, height))

    /** Set the layout's title (blank clears it). */
    fun setLayoutTitle(title: String) = setLayout(activeOrBlank().copy(title = title.ifBlank { null }))

    /** Set a placed queue's drawing properties: [growthDegrees] direction, member [spacing], [maxShown]. */
    fun setQueueProperties(name: String, growthDegrees: Double, spacing: Double, maxShown: Int) =
        setLayout(activeOrBlank().withQueueProperties(name, growthDegrees, spacing, maxShown))

    /** Set a placed resource glyph's [size]. */
    fun setResourceSize(name: String, size: Double) =
        setLayout(activeOrBlank().withResourceSize(name, size))

    /** Toggle resource [name]'s live "busy/capacity" read-out (P4). */
    fun setResourceShowValue(name: String, show: Boolean) =
        setLayout(activeOrBlank().withResourceShowValue(name, show))

    /** Set resource [name]'s per-state images (null clears a state's image; colors stay as fallbacks) — 10.7. */
    fun setResourceImages(name: String, idle: String?, busy: String?, failed: String?, inactive: String?) =
        setLayout(activeOrBlank().withResourceImages(name, idle, busy, failed, inactive))

    /** Add a background image spanning ([x1],[y1])–([x2],[y2]); defaults to the full canvas. */
    fun addBackgroundImage(imageRef: String, x1: Double = 0.0, y1: Double = 0.0, x2: Double? = null, y2: Double? = null) {
        val base = activeOrBlank()
        setLayout(base.withBackgroundImage(imageRef, x1, y1, x2 ?: base.width, y2 ?: base.height))
    }

    /** Add a background rectangle (e.g. a hand-drawn wall) spanning ([x1],[y1])–([x2],[y2]) — G1. */
    fun addBackgroundRect(x1: Double, y1: Double, x2: Double, y2: Double, color: String, strokeWidth: Double) =
        setLayout(activeOrBlank().withBackgroundRect(x1, y1, x2, y2, color, strokeWidth))

    /** Add a background line segment from ([x1],[y1]) to ([x2],[y2]) — G1. */
    fun addBackgroundLine(x1: Double, y1: Double, x2: Double, y2: Double, color: String, strokeWidth: Double) =
        setLayout(activeOrBlank().withBackgroundLine(x1, y1, x2, y2, color, strokeWidth))

    /** Add a background text annotation [text] anchored at ([x],[y]) with the given font. */
    fun addBackgroundText(x: Double, y: Double, text: String, color: String, fontSize: Double = 12.0, fontFamily: String? = null) =
        setLayout(activeOrBlank().withBackgroundText(x, y, text, color, fontSize, fontFamily))

    /** Remove the background element at [index]. */
    fun removeBackgroundAt(index: Int) = setLayout(activeOrBlank().withBackgroundRemovedAt(index))

    /** Translate the background element (shape) at [index] by ([dx],[dy]) — canvas drag-to-move. */
    fun moveBackgroundAt(index: Int, dx: Double, dy: Double) = setLayout(activeOrBlank().withBackgroundMovedAt(index, dx, dy))

    /** Replace the background element (shape) at [index] — the double-click shape editor (color/stroke/text). */
    fun setBackgroundAt(index: Int, element: ksl.animation.BackgroundElement) =
        setLayout(activeOrBlank().withBackgroundReplacedAt(index, element))

    /** Add a clock display ([label]/[format], size [fontSize]) anchored at ([x],[y]) — the Clock palette tool. */
    fun addClock(x: Double, y: Double, label: String? = "Time", format: String = "0.0", fontSize: Double = 12.0) =
        setLayout(activeOrBlank().withClock(x, y, label, format, fontSize))

    /** Remove the clock at [index]. */
    fun removeClockAt(index: Int) = setLayout(activeOrBlank().withClockRemovedAt(index))

    /** Translate the clock at [index] by ([dx],[dy]) — canvas drag-to-move. */
    fun moveClockAt(index: Int, dx: Double, dy: Double) = setLayout(activeOrBlank().withClockMovedAt(index, dx, dy))

    /** Replace the clock at [index] — the clock editor (label/format) and resize (fontSize). */
    fun setClockAt(index: Int, element: ksl.animation.ClockDisplayElement) =
        setLayout(activeOrBlank().withClockReplacedAt(index, element))

    /** Add a decorative path named [name] routed through the placed [anchorNames] (needs >= 2 resolvable
     *  anchors; each resolves as a location first, then a station). */
    fun addPathThroughStations(name: String, anchorNames: List<String>) {
        val base = activeOrBlank()
        val points = anchorNames.mapNotNull { base.positionOf(ElementKind.LOCATION, it) ?: base.positionOf(ElementKind.STATION, it) }
        if (points.size >= 2) setLayout(base.withPath(name, points))
    }

    /** Add a **functional** path named [name] between anchors [from] and [to] with intermediate [waypoints], so a
     *  move between those anchors follows the polyline (Phase 6). */
    fun addFunctionalPath(name: String, from: AnchorRef, to: AnchorRef, waypoints: List<LayoutPoint>, bidirectional: Boolean = true) =
        setLayout(activeOrBlank().withFunctionalPath(name, from, to, waypoints, bidirectional))

    /** Remove the path named [name]. */
    fun removePath(name: String) = setLayout(activeOrBlank().withPathRemoved(name))

    /** Add/replace an object-class style (shape/color/size) for entity or agent [typeName]. */
    fun addObjectClass(
        typeName: String, shape: ksl.animation.LayoutShape, color: String, size: Double, imageRef: String? = null
    ) = setLayout(activeOrBlank().withObjectClass(typeName, shape, color, size, imageRef))

    /** Remove the object-class style for [typeName]. */
    fun removeObjectClass(typeName: String) = setLayout(activeOrBlank().withObjectClassRemoved(typeName))

    /** Set an agent state→color mapping (V7); marks the layout dirty. */
    fun setAgentStateColor(state: String, color: String) = setLayout(activeOrBlank().withAgentStateColor(state, color))
    fun removeAgentStateColor(state: String) = setLayout(activeOrBlank().withAgentStateColorRemoved(state))

    /** Set an entity process→color mapping (10.1e), tinting entities by their current process; marks dirty. */
    fun setProcessColor(process: String, color: String) = setLayout(activeOrBlank().withProcessColor(process, color))
    fun removeProcessColor(process: String) = setLayout(activeOrBlank().withProcessColorRemoved(process))

    /** Add/replace a continuous space [name] with the given bounds; [torus] wraps motion at the edges. */
    fun addContinuousSpace(name: String, xMin: Double, xMax: Double, yMin: Double, yMax: Double, torus: Boolean = false) =
        setLayout(activeOrBlank().withContinuousSpace(name, xMin, xMax, yMin, yMax, torus))

    /** Add/replace a grid space [name] of [cols]×[rows] cells of [cellSize] at ([originX],[originY]); [torus] wraps. */
    fun addGridSpace(name: String, cols: Int, rows: Int, cellSize: Double, originX: Double = 0.0, originY: Double = 0.0, torus: Boolean = false) =
        setLayout(activeOrBlank().withGridSpace(name, cols, rows, cellSize, originX, originY, torus))

    /** Add/replace a network space [name] of [nodes] and [edges]. */
    fun addNetworkSpace(name: String, nodes: List<ksl.animation.NetworkNode>, edges: List<ksl.animation.NetworkEdge>) =
        setLayout(activeOrBlank().withNetworkSpace(name, nodes, edges))

    /** Remove the space [name]. */
    fun removeSpace(name: String) = setLayout(activeOrBlank().withSpaceRemoved(name))

    /** Obstacle/cost overlays the model exposes (via `Context.attachGeometry`) — the importable geometry (P5c/G2). */
    fun modelSpaceGeometry(): List<ksl.modeling.agent.GridGeometrySpec> = inventory.spaces.mapNotNull { it.geometry }

    /** Import the model's obstacle/cost geometry into the active layout (replacing same-named overlays) — P5c/G2. */
    fun importObstaclesFromModel() {
        val specs = modelSpaceGeometry()
        if (specs.isNotEmpty()) setLayout(activeOrBlank().withSpaceGeometryImported(specs))
    }

    /** Remove the obstacle/cost overlay for [spaceName]. */
    fun removeSpaceGeometry(spaceName: String) = setLayout(activeOrBlank().withSpaceGeometryRemoved(spaceName))

    /**
     * Show response/counter [name] as the given [display] kind (value/bar/plot/summary/histogram), preserving its
     * position. [discrete] applies to HISTOGRAM only: true ⇒ an integer-frequency histogram (the "frequency" form).
     */
    fun setResponseDisplay(name: String, display: ResponseDisplay, discrete: Boolean = false) {
        val base = activeOrBlank()
        val p = base.positionOf(ElementKind.RESPONSE, name)
        setLayout(base.withResponseDisplay(name, display, p?.x ?: 120.0, p?.y ?: 80.0, discrete))
    }

    /** Style the bar read-out [name] (max value, color, size) — chart styling parity with the DSL. */
    fun setBarStyle(name: String, maxValue: Double, color: String, width: Double, height: Double) =
        setLayout(activeOrBlank().withBarStyle(name, maxValue, color, width, height))

    /** Style the plot read-out [name] (color, rolling window, size); [window] = null shows the whole run. */
    fun setPlotStyle(name: String, color: String, window: Double?, width: Double, height: Double) =
        setLayout(activeOrBlank().withPlotStyle(name, color, window, width, height))

    /** Style the histogram read-out [name] (bins, color, size, discrete/frequency form). */
    fun setHistogramStyle(name: String, bins: Int, color: String, width: Double, height: Double, discrete: Boolean) =
        setLayout(activeOrBlank().withHistogramStyle(name, bins, color, width, height, discrete))

    /** Set the value/summary read-out [name]'s decimal places. */
    fun setValueDecimals(name: String, decimals: Int) =
        setLayout(activeOrBlank().withValueDecimals(name, decimals))

    /** Add/replace a conveyor's authored route element (10.5d). */
    fun setConveyorLayout(element: ksl.animation.ConveyorLayoutElement) =
        setLayout(activeOrBlank().withConveyorLayout(element))
    /** Replace the waypoints of conveyor [name]'s segment at [segmentIndex] (10.5d). */
    fun setConveyorSegmentWaypoints(name: String, segmentIndex: Int, waypoints: List<ksl.animation.LayoutPoint>) =
        setLayout(activeOrBlank().withConveyorSegmentWaypoints(name, segmentIndex, waypoints))
    /** Remove the conveyor layout [name] (reverts to straight anchor-to-anchor drawing). */
    fun removeConveyorLayout(name: String) = setLayout(activeOrBlank().withConveyorRemoved(name))

    /** Add/replace a storage (named delay / type holding area) spanning a rectangle (#15). */
    fun addStorage(
        suspensionName: String, x: Double, y: Double, width: Double, height: Double,
        style: ksl.animation.StorageStyle, spacing: Double, maxShown: Int, capacity: Int, byType: Boolean, label: String?
    ) = setLayout(activeOrBlank().withStorageAdded(suspensionName, x, y, width, height, style, spacing, maxShown, capacity, byType, label))
    fun moveStorage(suspensionName: String, x: Double, y: Double) = setLayout(activeOrBlank().withStorageMoved(suspensionName, x, y))
    fun removeStorage(suspensionName: String) = setLayout(activeOrBlank().withStorageRemoved(suspensionName))
    fun setStorageStyle(suspensionName: String, style: ksl.animation.StorageStyle) = setLayout(activeOrBlank().withStorageStyle(suspensionName, style))

    /** Replace all of storage [suspensionName]'s editable properties (position, style, size, layout) — G6 editor. */
    fun setStorageProperties(
        suspensionName: String, x: Double, y: Double, style: ksl.animation.StorageStyle, width: Double, height: Double,
        growthDegrees: Double, spacing: Double, maxShown: Int, capacity: Int, byType: Boolean, label: String?
    ) = setLayout(activeOrBlank().withStorageProperties(
        suspensionName, x, y, style, width, height, growthDegrees, spacing, maxShown, capacity, byType, label
    ))

    /** Set the ([kind],[name]) element's name-label + value annotation overrides (text/offset/visibility) — C3, batch 4. */
    fun setElementLabel(
        kind: ElementKind, name: String, text: String?, dx: Double, dy: Double, visible: Boolean,
        valueDx: Double, valueDy: Double, valueVisible: Boolean
    ) = setLayout(activeOrBlank().withElementLabel(kind, name, text, dx, dy, visible, valueDx, valueDy, valueVisible))

    // ── Validation (author-time, over the probe inventory; 9A.5) ────────────

    /** Validates the current [captureSpec] against the model [inventory]. */
    fun captureValidation(): ValidationReport = myCaptureSpec.value.validateAgainst(inventory)

    /** Validates the current [layout] against the model [inventory]; vacuously valid when no layout is set. */
    fun layoutValidation(): ValidationReport =
        layout.value?.validateAgainst(inventory) ?: ValidationReport(emptyList())

    /** True when at least one `.atf` exists for this model (just produced, or already on disk). */
    fun hasTrace(): Boolean = myLastTraceFile.value != null || listTraces().isNotEmpty()

    /** True when at least one layout exists (active in the editor, or saved on disk). */
    fun hasLayout(): Boolean = layout.value != null || listLayouts().isNotEmpty()

    /** A snapshot of guided-workflow progress (9F.5) for the tab markers and the "next step" banner. */
    fun workflowStatus(): WorkflowStatus = deriveWorkflowStatus(
        captureValid = captureValidation().isValid,
        hasTrace = hasTrace(),
        hasLayout = hasLayout(),
        layoutValid = layoutValidation().isValid
    )

    // ── Configuration snapshot / load / save ────────────────────────────────

    /** Outcome of [loadConfiguration] (mirrors the single-app contract). */
    sealed class LoadResult {
        data class Loaded(val warning: String? = null) : LoadResult()
        data class Rejected(val reason: String) : LoadResult()
        data class WrongMode(val reason: String) : LoadResult()
    }

    /**
     * Snapshot the in-memory editor state as a [RunConfiguration] with one [ScenarioSpec], carrying the
     * authored [captureSpec] in its [TracingConfig]. Pure read — does not mutate or clear [isDirty]. The
     * `outputDirectory` and `animationTraceFile` are install-local (re-computed at run time) and blanked.
     */
    fun currentConfiguration(): RunConfiguration =
        RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = appName,
                    modelReference = sourceRef,
                    runOverrides = myRunOverrides.value,
                    controlOverrides = myControlOverrides.value,
                    rvOverrides = myRVOverrides.value
                )
            ),
            outputConfig = myOutputConfig.value.copy(outputDirectory = null),
            tracingConfig = TracingConfig(animationTraceFile = null, capture = myCaptureSpec.value)
        )

    /**
     * Replace the in-memory config state with the first scenario of [config] (and its capture spec).
     * Clears [isDirty] on success. Does not change [currentFile]; callers should [markSaved] separately
     * after an *Open*. Mode-aware dispatch mirrors the single-app: builder mode accepts
     * [ModelReference.Embedded]; bundle mode accepts a loaded [ModelReference.ByBundleAndModelId].
     */
    fun loadConfiguration(config: RunConfiguration): LoadResult {
        val scenario = config.scenarios.firstOrNull()
            ?: return LoadResult.Rejected("Configuration has no scenarios.")
        val ref = scenario.modelReference
        val warning: String? = when {
            bundleLibrary == null && ref is ModelReference.Embedded ->
                if (ref.modelName != appName)
                    "Loaded modelReference '${ref.modelName}' does not match this app's '$appName'. " +
                        "Overrides applied to whatever names match the current model."
                else null
            bundleLibrary == null -> return LoadResult.WrongMode(
                "This configuration was saved against a ${describeRefVariant(ref)} model, but this app " +
                    "was launched with a developer-supplied modelBuilder()."
            )
            ref is ModelReference.Embedded -> return LoadResult.WrongMode(
                "This configuration was saved against a developer-supplied model ('${ref.modelName}'), " +
                    "but this app was launched from the bundle picker."
            )
            ref is ModelReference.ByBundleAndModelId -> {
                if (bundleLibrary.findBundle(ref.bundleId) == null) {
                    return LoadResult.Rejected(
                        "This configuration references bundle '${ref.bundleId}' which is not loaded. " +
                            "Use Load JAR… to load it, then re-open."
                    )
                }
                val session = sourceRef as? ModelReference.ByBundleAndModelId
                if (session != null && (session.bundleId != ref.bundleId || session.modelId != ref.modelId))
                    "Loaded reference '${ref.bundleId}/${ref.modelId}' does not match this session's " +
                        "'${session.bundleId}/${session.modelId}'. Overrides applied where names match."
                else null
            }
            else -> return LoadResult.WrongMode(
                "This configuration uses a model-reference variant (${describeRefVariant(ref)}) " +
                    "that this app does not support."
            )
        }

        myRunOverrides.value = scenario.runOverrides ?: ExperimentRunOverrides()
        myControlOverrides.value = scenario.controlOverrides
        myRVOverrides.value = scenario.rvOverrides
        myOutputConfig.value = config.outputConfig.copy(outputDirectory = null)
        myCaptureSpec.value = config.tracingConfig.capture
        configLifecycle.clearDirty()
        return LoadResult.Loaded(warning)
    }

    private fun describeRefVariant(ref: ModelReference): String = when (ref) {
        is ModelReference.Embedded -> "developer-supplied (Embedded)"
        is ModelReference.ByBundleAndModelId -> "bundled"
        is ModelReference.ByJar -> "JAR-loaded"
        is ModelReference.ByProviderId -> "provider-keyed"
    }

    /**
     * Reset editor state to defaults — *File → New*. Clears the config document (run/control/RV/output +
     * capture spec) and the layout document.
     */
    fun resetConfiguration() {
        myRunOverrides.value = ExperimentRunOverrides()
        myControlOverrides.value = ModelControlsExport(modelName = controlsSnapshot.modelName)
        myRVOverrides.value = emptyList()
        myOutputConfig.value = OutputConfig(analysisName = if (modelName.isNotBlank()) modelName else "Untitled")
        myCaptureSpec.value = CaptureSpec()
        configLifecycle.reset()
        myLayout.value = null
        layoutLifecycle.reset()
    }

    /** Record that the configuration has been persisted to [path]; sets [currentFile], clears [isDirty]. */
    fun markSaved(path: Path) = configLifecycle.markSaved(path)

    /** Encode [currentConfiguration] to TOML, write it to [path], and record it as the config's saved file. */
    fun saveConfiguration(path: Path) {
        Files.writeString(path, RunConfigurationToml.encode(currentConfiguration()))
        markSaved(path)
    }

    /**
     * Read a configuration TOML from [path] and apply it via [loadConfiguration]. On a successful load the
     * file is recorded as the config's saved file ([currentFile] = [path], [isDirty] = false); on a
     * rejected/wrong-mode result the in-memory state and file binding are left unchanged.
     */
    fun openConfiguration(path: Path): LoadResult {
        val config = RunConfigurationToml.decode(Files.readString(path))
        val result = loadConfiguration(config)
        if (result is LoadResult.Loaded) markSaved(path)
        return result
    }

    // ── Run / trace production (9D.1) ───────────────────────────────────────

    /** Per-document model provider (resolves [ModelReference.Embedded] through [modelBuilder]). */
    private val provider = MapModelProvider(appName, modelBuilder)
    private val session = KSLAppSession(provider = provider)

    /** User-wide settings (workspace, recent list); the app's own workspace folder lives under it. */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /**
     * Overrides the working directory used to route this app's artifacts. Defaults to the shared,
     * user-settable workspace ([UserSettingsStore.activeWorkspace], typically `~/Documents/KSLWork`);
     * tests set it to a temp dir. Read per access — the active workspace may change at runtime.
     */
    var workspaceOverride: Path? = null

    /** The active working directory (the override when set, else the shared user workspace). */
    fun workingDirectory(): Path = workspaceOverride ?: settingsStore.activeWorkspace()

    /** Filesystem-safe per-model folder segment: the sanitized model name, or `Untitled` when unknown. */
    private fun modelFolderName(): String =
        modelName.ifBlank { SingleAppPaths.UNTITLED }.replace(Regex("[^A-Za-z0-9-_.]"), "_")

    /**
     * This app's own folder under the working directory — `<workingDir>/KSLAnimation/` — mirroring the
     * per-app folder convention of the other KSL apps ([AppWorkspacePaths]).
     */
    val appWorkspace: Path
        get() = AppWorkspacePaths.appWorkspaceDir(workingDirectory(), APP_FOLDER)

    /**
     * This model's subfolder under the app folder — `<workingDir>/KSLAnimation/<ModelName>/` — the home for
     * all artifacts (configs, traces, layouts) the app produces for this model.
     */
    val modelWorkspace: Path
        get() = appWorkspace.resolve(modelFolderName())

    /** `<modelWorkspace>/configs/` — saved run configurations (`.toml`). */
    val configsDir: Path get() = modelWorkspace.resolve("configs")

    /** `<modelWorkspace>/traces/` — produced animation traces (`.atf`), one per run. */
    val tracesDir: Path get() = modelWorkspace.resolve("traces")

    /** `<modelWorkspace>/layouts/` — authored layouts (`.lay.json`), many per model. */
    val layoutsDir: Path get() = modelWorkspace.resolve("layouts")

    /** Existing trace files under [tracesDir], most-recently-modified first; empty when none. */
    fun listTraces(): List<Path> = listArtifacts(tracesDir, ".atf")

    /** Existing layout files under [layoutsDir], most-recently-modified first; empty when none. */
    fun listLayouts(): List<Path> = listArtifacts(layoutsDir, ".lay.json", ".lay.toml")

    private fun listArtifacts(dir: Path, vararg suffixes: String): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { p -> Files.isRegularFile(p) && suffixes.any { p.fileName.toString().endsWith(it) } }
                .toList()
        }.sortedByDescending { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
    }

    private val runLifecycle = RunLifecycleController<RunResult>()
    /** Most recently observed terminal [RunResult], or null when none yet. */
    val lastResult: StateFlow<RunResult?> = runLifecycle.lastResult

    private val myRunning = MutableStateFlow(false)
    /** True while a run is in flight. */
    val runningFlow: StateFlow<Boolean> = myRunning.asStateFlow()

    private val myEventFlow = MutableSharedFlow<RunEvent>(replay = 0, extraBufferCapacity = 256)
    /** Hot flow of run events from the active run. */
    val eventFlow: SharedFlow<RunEvent> = myEventFlow.asSharedFlow()

    private val myLastTraceFile = MutableStateFlow<Path?>(null)
    /** The `.atf` trace the most recent successful run produced, or null. */
    val lastTraceFile: StateFlow<Path?> = myLastTraceFile.asStateFlow()

    /**
     * Distinct **named** suspension names harvested from the last produced trace (its `DelayStarted` events),
     * so the Storage tool can offer named delays (which aren't structural and so aren't in the inventory) once
     * a run exists (#15 follow-up). Empty when there is no trace yet.
     */
    fun suspensionNamesFromLastTrace(): List<String> {
        val path = lastTraceFile.value ?: return emptyList()
        return runCatching {
            ksl.animation.TraceFileReader.readAll(path).second
                .filterIsInstance<ksl.animation.AnimationEvent.DelayStarted>()
                .mapNotNull { it.suspensionName?.trim()?.takeIf { n -> n.isNotEmpty() } }
                .distinct()
        }.getOrElse { emptyList() }
    }

    /**
     * Distinct entity/agent **type** names from the most recent trace (its `EntityCreated`/`AgentRegistered`
     * events), so the object-style editor can list agent types — which aren't structural, so aren't in the
     * inventory — once a trace exists (C3). Falls back to the newest trace on disk; empty when there is none.
     */
    fun objectTypeNamesFromLastTrace(): List<String> {
        val path = lastTraceFile.value ?: listTraces().firstOrNull() ?: return emptyList()
        return runCatching {
            val acc = ObjectTypeNames()
            ksl.animation.TraceFileReader.readAll(path).second.forEach(acc::accept)
            acc.result().toList()
        }.getOrElse { emptyList() }
    }

    /**
     * Entity/agent type names to offer in the object-style editor. Process entities are always drawn by the
     * process-view machinery, so they're surfaced structurally (author-time). An agent's visual-ness is a runtime
     * property (drawn only if projected/moved), so agent types are offered post-run from the trace — the movers it
     * actually showed — which keeps control-only agents (e.g. a dispatcher / order-generator) that never draw off
     * the list, before and after a run. See G3.
     */
    fun objectStyleTypeNames(): List<String> {
        val processEntities = inventory.entityTypes.filter { it.include && !it.isAgent }.map { it.typeName }
        return if (hasTrace()) (processEntities + objectTypeNamesFromLastTrace()).distinct() else processEntities
    }

    private var currentHandle: RunHandle? = null

    /**
     * Filesystem-safe base name for this model's artifacts: the analysis name when set, else the model name
     * (else the app name). Shared by the trace file and the suggested layout file so both prefix with the model.
     */
    private fun artifactBaseName(): String =
        (myOutputConfig.value.analysisName.takeIf { it.isNotBlank() && it != SingleAppPaths.UNTITLED }
            ?: modelName.ifBlank { appName })
            .replace(Regex("[^A-Za-z0-9-_]"), "_")

    /**
     * Filesystem-safe, timestamped trace file name: the artifact base name plus `_yyyyMMdd-HHmmss` so successive
     * runs accumulate distinct traces in `traces/` rather than overwriting.
     */
    private fun traceFileName(): String =
        "${artifactBaseName()}_${java.time.LocalDateTime.now().format(TRACE_TIMESTAMP)}.atf"

    /**
     * Suggested base file name (no extension) for a *Save Layout As*, prefixed with the model exactly like the
     * produced trace file — so a saved layout reads `<model>.lay.toml` instead of the auto-layout's title.
     */
    fun suggestedLayoutBaseName(): String = artifactBaseName()

    /**
     * Builds the model fresh, runs it, and writes a `.atf` trace honoring the authored [captureSpec] into
     * this model's `traces/` folder. No-op when a run is already in flight. The run goes through
     * [KSLAppSession] → `SingleRunOrchestrator`, which produces the trace from the [TracingConfig]; on
     * completion [lastTraceFile] points at the produced file.
     */
    fun submit() {
        if (myRunning.value || !hasModel) return // no model selected yet — nothing to run
        val outputDir = modelWorkspace.resolve("output") // KSL runtime output (db/csv/reports)
        Files.createDirectories(outputDir)
        Files.createDirectories(tracesDir)
        val traceFile = tracesDir.resolve(traceFileName())
        Files.deleteIfExists(traceFile) // the attachment creates the file itself
        val config = RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = appName,
                    // Run always resolves through the in-memory provider (Embedded); the saved-config
                    // sourceRef is a persistence concern, not a run concern.
                    modelReference = ModelReference.Embedded(appName),
                    runOverrides = myRunOverrides.value,
                    controlOverrides = myControlOverrides.value,
                    rvOverrides = myRVOverrides.value
                )
            ),
            outputConfig = myOutputConfig.value.copy(outputDirectory = outputDir.toString()),
            tracingConfig = TracingConfig(animationTraceFile = traceFile.toString(), capture = myCaptureSpec.value, overlays = myOverlaySpec.value)
        )
        val handle = session.submit(RunSpec.Single(config))
        currentHandle = handle
        myRunning.value = true
        edtScope.launch { handle.events.collect { myEventFlow.emit(it) } }
        edtScope.launch {
            val result = handle.result.await()
            // Publish the trace BEFORE the result, so observers awaking on lastResult see lastTraceFile set.
            if (result is RunResult.Completed) myLastTraceFile.value = traceFile
            runLifecycle.markRunCompleted(result)
            myRunning.value = false
            currentHandle = null
        }
    }

    /** Cancels the in-flight run, if any. */
    fun cancel() {
        currentHandle?.cancel("Cancelled by user")
    }

    override fun close() {
        currentHandle?.cancel("App closed")
        currentHandle = null
        session.close()
        edtScope.cancel()
    }

    companion object {
        /** This application's folder name under the working directory, e.g. `~/Documents/KSLWork/KSLAnimation/`. */
        const val APP_FOLDER: String = "KSLAnimation"

        private val TRACE_TIMESTAMP: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Defaults used when the developer's `ModelBuilderIfc` throws on the probe build. */
        val SAFE_FALLBACK_DEFAULTS: ExperimentRunDefaults = ExperimentRunDefaults(
            numberOfReplications = 1,
            numChunks = 1,
            startingRepId = 1,
            lengthOfReplication = 1.0,
            lengthOfReplicationWarmUp = 0.0,
            replicationInitializationOption = true,
            maximumAllowedExecutionTimePerReplication = 5.minutes,
            resetStartStreamOption = true,
            advanceNextSubStreamOption = true,
            antitheticOption = false,
            numberOfStreamAdvancesPriorToRunning = 0,
            garbageCollectAfterReplicationFlag = false
        )

        /**
         * A builder that produces an empty model, used for the [forNoModel] startup state. Probing it yields
         * empty defaults/controls/inventory (no failure), so the frame opens cleanly with nothing selected.
         */
        private object NoModelBuilder : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ksl.simulation.ExperimentRunParametersIfc?
            ): Model = Model("No model loaded", autoCSVReports = false)
        }

        /**
         * Builds the **no-model** startup controller (mirrors the Experiment app): it carries [bundleLibrary]
         * so the user can pick/load a model from the frame, but selects none ([hasModel] = false). The frame
         * shows an "open a model" prompt and reopens on a [fromBundle] controller once the user chooses one.
         */
        fun forNoModel(appName: String, bundleLibrary: BundleLibraryController): AnimationAppController =
            AnimationAppController(
                appName = appName,
                modelBuilder = NoModelBuilder,
                bundleLibrary = bundleLibrary,
                sourceRef = ModelReference.Embedded(appName),
                hasModel = false
            )

        /**
         * Builds a controller for the model identified by ([bundleId], [modelId]) in [bundleLibrary], mirroring
         * the bundle-mode startup path (see `KSLAnimationApp`) so startup and model-switching (10.2) share one
         * construction. The new controller re-probes the chosen model — its manifest, defaults, controls and
         * RVs — at construction.
         *
         * @throws IllegalStateException if [bundleLibrary] currently exposes no provider (no bundles loaded)
         */
        fun fromBundle(
            appName: String,
            bundleLibrary: BundleLibraryController,
            bundleId: String,
            modelId: String
        ): AnimationAppController {
            val provider = bundleLibrary.bundleProvider.value
                ?: error("No bundle provider available to open ($bundleId, $modelId).")
            return AnimationAppController(
                appName = appName,
                modelBuilder = provider.builderFor(bundleId, modelId),
                bundleLibrary = bundleLibrary,
                sourceRef = ModelReference.ByBundleAndModelId(bundleId, modelId)
            )
            // A bundled model opens with no layout; the user authors one in the Layout tab and saves it to
            // <modelWorkspace>/layouts/ (layouts are user-authored files, not bundle content).
        }
    }
}
