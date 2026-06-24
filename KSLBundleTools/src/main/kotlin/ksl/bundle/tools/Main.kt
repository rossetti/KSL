package ksl.bundle.tools

import kotlin.system.exitProcess

/**
 * Entry point for the `kslpkg` CLI tool.
 *
 * Two commands: `inspect` (read-only summary of a bundle JAR) and `assemble`
 * (turn a plain builders JAR into a self-describing bundle JAR — a `bundle.toml`
 * manifest plus a per-model embedded `ModelDescriptor` JSON).
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
        "inspect" -> InspectCommand.run(args.drop(1))
        "assemble" -> AssembleCommand.run(args.drop(1))
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
        |  kslpkg assemble <builders.jar> --id <bundleId> [options]
        |      Turn a plain builders JAR (one or more ksl.simulation.ModelBuilderIfc
        |      classes) into a self-describing bundle JAR: a bundle.toml manifest
        |      plus a per-model descriptor.json. --id is required; other identity
        |      comes from [--name --description --version --author --homepage
        |      --license --tag <t>]. --exclude <id,...> drops discovered models by
        |      modelId (e.g. a shared closure embedded for runtime, not a model).
        |      Default output is <builders-stem>-bundle.jar next to the input;
        |      -o sets it, --force overwrites an existing file.
        |
        |  kslpkg --help        Print this message
        |  kslpkg --version     Print the tool version
        """.trimMargin()
    )
}

internal const val TOOL_VERSION: String = "0.2.0"
