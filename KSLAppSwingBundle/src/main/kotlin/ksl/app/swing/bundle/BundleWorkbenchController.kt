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

package ksl.app.swing.bundle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing
import ksl.app.bundle.BundleAuthoringSession
import ksl.app.bundle.BundleLayout
import ksl.app.bundle.BundleValidation
import ksl.app.bundle.KSLAppKind
import ksl.app.config.CatalogValidation
import ksl.app.config.ModelReference
import ksl.app.session.AppWorkspacePaths
import ksl.app.settings.UserSettingsStore
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import ksl.simulation.ModelDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * State holder for the Bundle Workbench — a **thin adapter over the headless
 * [BundleAuthoringSession]** (the capabilities-vs-transport split: all authoring
 * logic lives in the session; this class only binds it to StateFlows for the Swing
 * views). The workbench turns a *builders JAR* into a *bundle JAR*.
 *
 * The document is the in-memory authoring draft (bundle identity + per-model
 * metadata and catalog) accumulated across model selections but not yet
 * assembled into a JAR ([dirty] tracks this). Mutations are synchronous and
 * unit-testable without a running event loop; [scope] exists only for UI collectors.
 */
class BundleWorkbenchController(val appName: String) {

    /** EDT-affine scope for UI collectors; not used by the mutation methods. */
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    /** Shared workspace/recent-directories settings (`~/.ksl`), as in the other apps. */
    val settingsStore: UserSettingsStore = UserSettingsStore()

    /** This app's folder under the active workspace — `<activeWorkspace>/KSL_Bundle_Workbench/`. */
    val appWorkspace: java.nio.file.Path
        get() = AppWorkspacePaths.appWorkspaceDir(settingsStore.activeWorkspace(), appName)

    /** Returns [appWorkspace], creating it if missing (used as the file-dialog start dir). */
    fun ensureAppWorkspace(): java.nio.file.Path {
        val dir = appWorkspace
        runCatching { Files.createDirectories(dir) }
        return dir
    }

    /** Human-readable status / next-step guidance shown on the status line. */
    private val _status = MutableStateFlow("Open a builders JAR to begin.")
    val status: StateFlow<String> = _status.asStateFlow()

    /**
     * Validation feedback for the inline health banner. [validate] publishes the
     * current [BundleValidation.ValidationReport] here as a Swing [ValidationResult];
     * the banner hides itself when there are no errors or warnings.
     */
    val healthBus: ValidationFeedbackBus = ValidationFeedbackBus()

    private var session: BundleAuthoringSession? = null

    /** Editable bundle identity snapshot, mirrored from the session. */
    data class IdentityView(
        val bundleId: String,
        val displayName: String,
        val description: String,
        val version: String,
        val kslApiVersion: String,
        val author: String?,
        val homepage: String?,
        val license: String?,
    )

    /** Editable per-model metadata snapshot (not the catalog, which has its own flow). */
    data class ModelView(
        val modelId: String,
        val displayName: String,
        val builderClass: String,
        val supportedApps: Set<KSLAppKind>,
    )

    private val _currentJar = MutableStateFlow<Path?>(null)
    val currentJar: StateFlow<Path?> = _currentJar.asStateFlow()

    private val _identity = MutableStateFlow<IdentityView?>(null)
    val identity: StateFlow<IdentityView?> = _identity.asStateFlow()

    private val _models = MutableStateFlow<List<ModelView>>(emptyList())
    val models: StateFlow<List<ModelView>> = _models.asStateFlow()

    private val _discoveryErrors = MutableStateFlow<List<String>>(emptyList())
    val discoveryErrors: StateFlow<List<String>> = _discoveryErrors.asStateFlow()

    private val _selectedModelId = MutableStateFlow<String?>(null)
    val selectedModelId: StateFlow<String?> = _selectedModelId.asStateFlow()

    /** A `ModelReference` for the selected model. */
    private val _currentReference = MutableStateFlow<ModelReference?>(null)
    val currentReference: StateFlow<ModelReference?> = _currentReference.asStateFlow()

    private val _currentDescriptor = MutableStateFlow<ModelDescriptor?>(null)
    val currentDescriptor: StateFlow<ModelDescriptor?> = _currentDescriptor.asStateFlow()

    private val _catalogDraft = MutableStateFlow<CatalogDraft?>(null)
    val catalogDraft: StateFlow<CatalogDraft?> = _catalogDraft.asStateFlow()

    private val _catalogProblems = MutableStateFlow<List<CatalogValidation.CatalogProblem>>(emptyList())
    val catalogProblems: StateFlow<List<CatalogValidation.CatalogProblem>> = _catalogProblems.asStateFlow()

    private val _validation = MutableStateFlow<BundleValidation.ValidationReport?>(null)
    val validation: StateFlow<BundleValidation.ValidationReport?> = _validation.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** Path of the most recently assembled bundle JAR, for the status line. */
    private val _lastAssembled = MutableStateFlow<Path?>(null)
    val lastAssembled: StateFlow<Path?> = _lastAssembled.asStateFlow()

    /** The selected `modelId`, or null. */
    val selected: String? get() = _selectedModelId.value

    /**
     * Opens a JAR, auto-detecting whether it is a plain builders JAR or an
     * already-assembled bundle JAR (resume editing) by the presence of `bundle.toml`.
     */
    fun openJar(jar: Path) {
        val isBundle = runCatching {
            JarFile(jar.toFile()).use { it.getJarEntry(BundleLayout.BUNDLE_TOML) != null }
        }.getOrDefault(false)
        if (isBundle) openBundleJar(jar) else openBuildersJar(jar)
    }

    /** Opens a plain builders JAR, discovers its models, selects the first, and validates. */
    fun openBuildersJar(jar: Path) {
        val s = BundleAuthoringSession.open(jar)
        if (s.bundleId.isBlank()) s.bundleId = defaultBundleId(jar)
        adopt(s, jar, fromBundle = false)
    }

    /** Opens an already-assembled bundle JAR, restoring its identity/catalog. */
    fun openBundleJar(jar: Path) {
        adopt(BundleAuthoringSession.openExisting(jar), jar, fromBundle = true)
    }

    private fun adopt(s: BundleAuthoringSession, jar: Path, fromBundle: Boolean) {
        session = s
        _currentJar.value = jar
        _lastAssembled.value = if (fromBundle) jar else null
        _discoveryErrors.value = s.discoveryErrors.map { "${it.builderClass}: ${it.error}" }
        refreshIdentity()
        refreshModels()
        val first = s.models.firstOrNull()?.modelId
        if (first != null) selectModel(first) else clearSelection()
        _dirty.value = false
        _status.value = when {
            s.models.isEmpty() -> "No model builders found in this JAR."
            fromBundle -> "Reopened bundle with ${s.models.size} model(s)."
            else -> "Opened ${s.models.size} model(s). Next: set the bundle identity (Bundle identity tab)."
        }
        validate() // seed the health banner with the opening state
    }

    /** Selects a model, seeding the catalog buffer from its draft. */
    fun selectModel(modelId: String) {
        val s = session ?: return
        val draft = s.models.firstOrNull { it.modelId == modelId } ?: return
        _selectedModelId.value = modelId
        _currentReference.value = ModelReference.ByBundleAndModelId(s.bundleId, modelId)
        _currentDescriptor.value = draft.descriptor
        // Seed the editable catalog buffer from the model's accumulated catalog (if any),
        // else from the descriptor — reusing CatalogDraft.from's descriptor-driven seeding.
        val seed = draft.descriptor.copy(catalog = draft.catalog ?: draft.descriptor.catalog)
        _catalogDraft.value = CatalogDraft.from(seed)
        revalidateCatalog()
    }

    /** Applies an edit to the current catalog buffer and syncs it into the model draft. */
    fun updateDraft(transform: (CatalogDraft) -> CatalogDraft) {
        val s = session ?: return
        val mid = _selectedModelId.value ?: return
        val current = _catalogDraft.value ?: return
        val updated = transform(current)
        _catalogDraft.value = updated
        val draft = s.models.firstOrNull { it.modelId == mid } ?: return
        val catalog = updated.toCatalog()
        draft.catalog = if (catalog.isEmpty) null else catalog
        _dirty.value = true
        _status.value = "● Draft modified — not yet assembled."
        revalidateCatalog()
    }

    /** Edits the bundle identity. */
    fun updateIdentity(transform: (IdentityView) -> IdentityView) {
        val s = session ?: return
        val v = transform(snapshotIdentity(s))
        s.bundleId = v.bundleId
        s.displayName = v.displayName
        s.description = v.description
        s.version = v.version
        s.kslApiVersion = v.kslApiVersion
        s.author = v.author?.ifBlank { null }
        s.homepage = v.homepage?.ifBlank { null }
        s.license = v.license?.ifBlank { null }
        refreshIdentity()
        _selectedModelId.value?.let { _currentReference.value = ModelReference.ByBundleAndModelId(s.bundleId, it) }
        _dirty.value = true
        _status.value = "Bundle identity applied. Next: set each model's supported apps on the Models tab."
        validate() // refresh the health banner so fixed findings clear
    }

    /** Edits one model's metadata (modelId / displayName / supportedApps). */
    fun updateModel(modelId: String, transform: (ModelView) -> ModelView) {
        val s = session ?: return
        val draft = s.models.firstOrNull { it.modelId == modelId } ?: return
        val v = transform(ModelView(draft.modelId, draft.displayName, draft.builderClass, draft.supportedApps.toSet()))
        draft.modelId = v.modelId
        draft.displayName = v.displayName
        draft.supportedApps.clear()
        draft.supportedApps.addAll(v.supportedApps)
        refreshModels()
        _dirty.value = true
        if (_selectedModelId.value == modelId) selectModel(v.modelId)
        _status.value = "Model '${v.modelId}' applied. Next: choose another model, or go to the Catalog tab."
        validate() // refresh the health banner (e.g. supported-apps warnings clear)
    }

    /**
     * Validates the current draft (assembles to a temp JAR and runs BundleValidation),
     * publishing the result to [healthBus] for the inline banner.
     */
    fun validate(): BundleValidation.ValidationReport? {
        val report = session?.validate()
        _validation.value = report
        healthBus.publish(report?.let(::toValidationResult) ?: ValidationResult())
        return report
    }

    /** Assembles the bundle JAR at [output] from the current draft. */
    fun assemble(output: Path, force: Boolean = false) {
        val s = session ?: error("no builders JAR open")
        s.assemble(output, force)
        _lastAssembled.value = output
        _dirty.value = false
        _status.value = "Assembled to ${output.fileName}."
    }

    /** Maps a headless [BundleValidation.ValidationReport] to the Swing [ValidationResult] the banner consumes. */
    private fun toValidationResult(report: BundleValidation.ValidationReport): ValidationResult {
        val errors = mutableListOf<FieldError>()
        val warnings = mutableListOf<FieldError>()
        for (f in report.findings) {
            val message = f.suggestion?.let { "${f.message} — $it" } ?: f.message
            val isError = f.severity == BundleValidation.Severity.ERROR
            val fe = FieldError(
                path = f.locus,
                message = message,
                severity = if (isError) ValidationSeverity.ERROR else ValidationSeverity.WARNING,
                code = "bundle.validation",
            )
            if (isError) errors += fe else warnings += fe
        }
        return ValidationResult(errors, warnings)
    }

    /** The default output path: `<input-stem>-bundle.jar`, or null when no JAR is open. */
    fun defaultOutputPath(): Path? = session?.defaultOutputPath()

    /** Releases the UI scope. */
    fun dispose() {
        scope.cancel()
    }

    private fun refreshIdentity() {
        _identity.value = session?.let { snapshotIdentity(it) }
    }

    private fun refreshModels() {
        _models.value = session?.models?.map {
            ModelView(it.modelId, it.displayName, it.builderClass, it.supportedApps.toSet())
        } ?: emptyList()
    }

    private fun snapshotIdentity(s: BundleAuthoringSession) = IdentityView(
        bundleId = s.bundleId,
        displayName = s.displayName,
        description = s.description,
        version = s.version,
        kslApiVersion = s.kslApiVersion,
        author = s.author,
        homepage = s.homepage,
        license = s.license,
    )

    private fun revalidateCatalog() {
        val descriptor = _currentDescriptor.value
        val draft = _catalogDraft.value
        _catalogProblems.value =
            if (descriptor != null && draft != null) CatalogValidation.validate(draft.toCatalog(), descriptor)
            else emptyList()
    }

    private fun clearSelection() {
        _selectedModelId.value = null
        _currentReference.value = null
        _currentDescriptor.value = null
        _catalogDraft.value = null
        _catalogProblems.value = emptyList()
    }

    private companion object {
        /** A provisional, filesystem-safe bundleId derived from the JAR stem. */
        fun defaultBundleId(jar: Path): String {
            val stem = jar.fileName.toString().removeSuffix(".jar").ifBlank { "bundle" }
            return stem.map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '-' }
                .joinToString("").ifBlank { "bundle" }
        }
    }
}
