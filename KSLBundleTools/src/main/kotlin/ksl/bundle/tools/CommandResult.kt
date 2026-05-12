package ksl.bundle.tools

/**
 * Outcome of a `kslpkg` command invocation. The integer `exitCode` is
 * what the JVM returns to the shell.
 *
 * - `Success` (0): the command ran to completion as intended.
 * - `UserError` (1): the caller supplied bad input — missing or malformed
 *   arguments, a non-existent file, a JAR with no bundles to enrich,
 *   or an output collision without `--force`.
 * - `InternalError` (2): the command failed for a reason unrelated to
 *   user input — a bundle's `builder().build(...)` threw, an I/O write
 *   failed, etc. Distinguishing from `UserError` lets scripts react
 *   differently to "bad input from the operator" vs. "tool broke."
 */
internal enum class CommandResult(val exitCode: Int) {
    Success(0),
    UserError(1),
    InternalError(2)
}
