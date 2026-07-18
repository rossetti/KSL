// KSLMcpSuite — the aggregated MCP server (WS2b). One long-running HTTP server that exposes the
// simulation, source-code, and textbook tool surfaces on a single MCP endpoint, so a client
// configures ONE server and gets every KSL tool. Depends on the three server modules for their
// register*Tools providers and index/tool backends; the heavy state is constructed once and shared.
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "io.github.rossetti"
version = (findProperty("kslSuiteMcpVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

// The simulation surface can render fit-report plots off-screen; keep the lets-plot Swing/Batik
// frontend off the classpath (headless), matching KSLServerMcp.
configurations.all {
    exclude(group = "org.jetbrains.lets-plot", module = "lets-plot-batik")
    exclude(group = "org.jetbrains.lets-plot", module = "platf-batik")
}

dependencies {
    implementation(project(":KSLServiceCore"))     // ServerConfig, stores, BundleRegistry, HealthEndpoints, ServerAuth
    implementation(project(":KSLServerMcp"))        // KslMcpServer.registerKslTools, KslMcpTools
    implementation(project(":KSLBookServer"))       // BookMcpServer.registerBookTools, BookStore, BookSearch
    implementation(project(":KSLCodeMCPServer"))    // CodeMcpServer.registerCodeTools, CodeStore, CodeSearch
    implementation("io.modelcontextprotocol:kotlin-sdk:0.8.3")
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.ktor:ktor-server-core:3.2.3")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("ksl.server.suite.MainKt")
    applicationDefaultJvmArgs = listOf("-Dlogback.configurationFile=logback-ksl-suite-mcp.xml")
}

tasks.test { useJUnitPlatform() }

// A self-contained runnable server jar (the aggregator holds all three surfaces + KSLCore, so it is
// NOT a thin jar). mergeServiceFiles keeps ktor + the MCP SDK's ServiceLoader registrations working.
tasks.shadowJar {
    archiveBaseName.set("ksl-suite-mcp")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest { attributes["Main-Class"] = "ksl.server.suite.MainKt" }
}
