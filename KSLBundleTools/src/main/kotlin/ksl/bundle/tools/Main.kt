package ksl.bundle.tools

import kotlin.system.exitProcess

/**
 * Entry point for the `kslpkg` CLI tool.
 *
 * Phase 6A scope is two commands: `inspect` (read-only summary of a bundle
 * JAR) and `enrich` (rewrite a bundle JAR with embedded `ModelDescriptor`
 * JSON entries). This bootstrap commit wires the module and the dispatch
 * skeleton only; command implementations land in subsequent commits.
 *
 * Argument parsing is intentionally hand-rolled. With two commands and a
 * handful of flags the cost of a parsing library outweighs the benefit;
 * if the command surface grows substantially in Phase 6D we can promote
 * to a dedicated library at that point.
 */
fun main(args: Array<String>) {
    val result = dispatch(args)
    if (result.exitCode != 0) {
        exitProcess(result.exitCode)
    }
}

internal fun dispatch(args: Array<String>): CommandResult {
    if (args.isEmpty()) {
        printUsage()
        return CommandResult.UserError
    }
    return when (val command = args[0]) {
        "--help", "-h", "help" -> {
            printUsage()
            CommandResult.Success
        }
        "--version", "-v", "version" -> {
            println("kslpkg $TOOL_VERSION")
            CommandResult.Success
        }
        "inspect" -> {
            System.err.println("inspect: not yet implemented (Phase 6A commit 2)")
            CommandResult.InternalError
        }
        "enrich" -> {
            System.err.println("enrich: not yet implemented (Phase 6A commit 3)")
            CommandResult.InternalError
        }
        else -> {
            System.err.println("Unknown command: $command")
            printUsage()
            CommandResult.UserError
        }
    }
}

private fun printUsage() {
    println(
        """
        |kslpkg — KSL bundle authoring tool
        |
        |Usage:
        |  kslpkg inspect <jar>
        |      Print a human-readable summary of the bundles in <jar>.
        |
        |  kslpkg enrich <input.jar> [-o <output.jar>] [--force]
        |      Extract a ModelDescriptor for every bundled model and write
        |      a copy of <input.jar> with the descriptors embedded under
        |      META-INF/ksl/models/<modelId>/descriptor.json. Default output
        |      is <input-stem>-enriched.jar next to the input; --force allows
        |      overwriting an existing output file.
        |
        |  kslpkg --help        Print this message
        |  kslpkg --version     Print the tool version
        """.trimMargin()
    )
}

internal const val TOOL_VERSION: String = "0.1.0"
