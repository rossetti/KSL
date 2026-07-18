// KSLAgentConfig — the single client-config library for the KSL server stack (Phase A1).
//
// Unifies the merge/remove logic that was copied into each server's AgentSetup (KSLServerMcp,
// KSLBookServer, KSLCodeMCPServer) and the manager's SuiteConfigurator: it writes/removes a single
// NAMED MCP-server entry (e.g. "ksl-suite" or "ksl") in Claude Desktop's JSON and Codex's TOML,
// preserving everything else, honoring the KSL_AGENT_CONFIG_HOME sandbox redirect, and never
// clobbering an unparseable config. Deliberately light — NO KSL project dependency; pure config
// codecs + JDK I/O. Consumed by the suite's setup CLI, the suite's loopback configure endpoint, and
// KSLServerManager.
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "io.github.rossetti"
version = (findProperty("kslAgentConfigVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") // Claude Desktop config JSON

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }
