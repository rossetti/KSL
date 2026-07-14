package ksl.book.mcp

object BuildInfo {
    /** Implementation-Version from the jar manifest; "dev" when run from classes. */
    val version: String =
        BuildInfo::class.java.`package`?.implementationVersion ?: "dev"
}
