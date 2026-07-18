// KSLBridge — a thin stdio->HTTP MCP bridge (WS2c). A stdio-only client (Claude Desktop) launches
// this as its MCP server; internally it connects to the long-running KSLMcpSuite over SSE and pumps
// JSON-RPC both ways. It holds NONE of the heavy KSL state — no KSLCore, no Lucene indexes — so it
// is a lightweight per-session process; all the weight lives in the one shared suite server.
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "io.github.rossetti"
version = (findProperty("kslBridgeVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    // Both bridge halves come from the MCP SDK: StdioServerTransport (toward the client) and the
    // SSE client transport (toward the suite). No KSL modules — the bridge stays thin.
    implementation("io.modelcontextprotocol:kotlin-sdk:0.8.3")
    implementation("io.ktor:ktor-client-core:3.2.3")   // carries the client SSE plugin (io.ktor.client.plugins.sse.SSE)
    implementation("io.ktor:ktor-client-cio:3.2.3")    // the HTTP engine
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.8.2")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("ksl.bridge.MainKt")
    // stdout is the MCP stdio channel; logging must go to stderr only.
    applicationDefaultJvmArgs = listOf("-Dlogback.configurationFile=logback-ksl-bridge.xml")
}

tasks.test { useJUnitPlatform() }

tasks.shadowJar {
    archiveBaseName.set("ksl-bridge")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest { attributes["Main-Class"] = "ksl.bridge.MainKt" }
}
