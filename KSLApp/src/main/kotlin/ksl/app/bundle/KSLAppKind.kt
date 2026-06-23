package ksl.app.bundle

/**
 * Identifies a KSL application workflow that a bundled model can claim to support.
 *
 * A `KSLBundledModel` declares which app kinds it is suitable for via its
 * `supportedApps` set. Consumers (the four Swing apps today; a future MCP server
 * or REST host tomorrow) use these declarations to filter their model pickers
 * without having to instantiate models — the declaration is part of the bundle's
 * static metadata and is readable from the JAR before any Kotlin class is loaded.
 *
 * The set of kinds is closed by design. Adding a new kind is a deliberate
 * substrate-level change that ripples through every consumer's when-expressions,
 * which is the desired behaviour.
 */
enum class KSLAppKind {
    /** Single-replication or small-run workflow with no batch authoring. */
    SINGLE,

    /** Batch of named scenarios, each a control-settings map over one model. */
    SCENARIO,

    /**
     * Designed experiment over the model's controls. Requires at least two
     * controllable factors to be meaningful.
     */
    EXPERIMENT,

    /**
     * Simulation-optimization workflow. Requires numeric inputs with bounds
     * and at least one response usable as an objective.
     */
    SIMOPT
}
