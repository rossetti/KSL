# Module KSLAppSwingCommon

Cross-cutting Swing widgets shared by every Phase-6-era KSL app
framework (`KSLAppSwingSingle`, `KSLAppSwingScenario`,
`KSLAppSwingExperiment`, `KSLAppSwingSimopt`).

# Package ksl.app.swing.common

The single public package for cross-app widgets.  What belongs here:

- **Workspace** — `UserSettingsStore`, `WorkspaceStatusBar`,
  `SetWorkingDirectoryAction`, `RecentWorkingDirectoriesMenu`.
- **Validation feedback** — `ValidationFeedbackBus`,
  `FieldErrorMarker`, `RowStatusIcon`, `DocumentHealthBanner`,
  `ValidationToast`, `JumpToErrorAction`, `PathParser`,
  `WidgetPathRegistry`.
- **Override-field primitives** — `IntegerOverrideField`,
  `DoubleOverrideField`, `BooleanTriStateOverrideField`,
  `DurationOverrideField`, `SectionHeaderWithStatus`.
- **Controls / RV / model-configuration widgets** — the per-table
  editor widgets reused across Single, Scenario, and Experiment.
- **Run-controls primitives** — `RunControlBar`, `ConsoleLogPanel`,
  `RunningPostureGuard`, `ExecutionModeToggle`.
- **Results primitives** — `ScenarioResultsDetailView`,
  `OpenReportAction`, `OpenDatabaseAction`, `OpenInFileBrowserAction`.

What does **not** belong here:

- App-specific frames, controllers, or view-models (those live in
  `KSLAppSwing{Single,Scenario,Experiment,Simopt}`).
- Bundle-resolution UI (Scenario-specific).
- Anything that imports `KSLExamples`.

This module is upstream of every app and downstream of `KSLCore`
only.  See workflow-scenario.md §12 (the widget consolidation
punch list) and workflow-single.md §3 (the Single-vs-Common
boundary) for the authoritative list of inhabitants.

This file is the bootstrap marker.  Widget code lands in
subsequent commits, family by family.
