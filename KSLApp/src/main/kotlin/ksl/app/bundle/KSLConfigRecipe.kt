package ksl.app.bundle

import java.io.InputStream

/**
 * An author-curated configuration shipped inside a bundle JAR for one of the
 * bundle's models. A recipe is a pre-filled set of inputs (a `RunConfiguration`,
 * a scenario batch, a designed-experiment spec, or an optimization run spec)
 * that an end user can load in a GUI, inspect, tweak, and run.
 *
 * Recipes are how a bundle author hands users a meaningful starting point
 * without forcing them to author a configuration from scratch.
 *
 * Implementations are typically thin wrappers around `ClassLoader.getResourceAsStream`
 * pointing at files under `META-INF/ksl/models/<modelId>/<recipe-dir>/<name>.<ext>`
 * (see `BundleLayout`).
 */
interface KSLConfigRecipe {

    /**
     * Human-visible label used in pickers. Conventionally the file stem
     * (e.g. a recipe stored as `light-load.toml` has `name = "light-load"`),
     * but implementations may choose any unique-per-model label.
     */
    val name: String

    /**
     * The configuration shape carried by this recipe. The consumer uses this
     * to choose the right deserializer; the SPI itself does not parse.
     */
    val kind: ConfigRecipeKind

    /**
     * Opens a fresh `InputStream` over the recipe's underlying bytes. Each
     * call returns a new stream; the caller is responsible for closing it.
     *
     * Returning a stream (rather than a parsed object or a `String`) keeps
     * the bundle SPI independent of any specific config format and avoids
     * slurping potentially large batch files into memory before they are needed.
     */
    fun openStream(): InputStream
}
