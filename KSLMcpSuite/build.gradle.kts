// KSLMcpSuite — the aggregated MCP server and composition root. One long-running HTTP server that
// exposes the simulation, textbook, and source-code tool surfaces on a single MCP endpoint, so a
// client configures ONE server and gets every enabled tool. It owns the McpToolCapability contract
// and the book/code MCP adapters; the simulation tools come from KSLServerMcp, and the search
// backends from the mcp-free KSLBookSearch / KSLCodeSearch libraries. Heavy state is built once and
// shared across SSE sessions.
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "io.github.rossetti"
// The suite reports the whole-distribution version (kslSuiteVersion), stamped into the shadowJar
// manifest below so /version, /health, and the console show the real release, not "dev".
version = (findProperty("kslSuiteVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

// The simulation surface can render fit-report plots off-screen; keep the lets-plot Swing/Batik
// frontend off the classpath (headless), matching KSLServerMcp.
configurations.all {
    exclude(group = "org.jetbrains.lets-plot", module = "lets-plot-batik")
    exclude(group = "org.jetbrains.lets-plot", module = "platf-batik")
}

dependencies {
    implementation(project(":KSLServiceCore"))     // ServerConfig, stores, BundleRegistry, HealthEndpoints, ServerAuth, BuildInfo
    implementation(project(":KSLServerMcp"))        // KslMcpServer.registerKslTools, KslMcpTools
    implementation(project(":KSLAgentConfig"))      // AgentConfigurator for the client-setup CLI
    implementation(project(":KSLBookSearch"))       // BookStore, BookSearch (mcp-free search library)
    implementation(project(":KSLCodeSearch"))       // CodeStore, CodeSearch (mcp-free search library)
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
    // The UIElement flag is a BACKSTOP for the launcher path only; the real fix is
    // configureMacDesktop() in Main.kt, which also covers `java -jar` and IDE runs. Kept here in
    // case a static initializer ever touches AWT before main(). Matches KSLServerTray.
    applicationDefaultJvmArgs = listOf(
        "-Dlogback.configurationFile=logback-ksl-suite-mcp.xml",
        "-Dapple.awt.UIElement=true",
    )
}

tasks.test { useJUnitPlatform() }

// The distribution ships the THIN jar (over the shared lib/ + server-lib/), so stamp its manifest too —
// SuiteBuildInfo reads Implementation-Version from THIS jar to report the release version (kslSuiteVersion),
// not KSLServiceCore's engine version. The shadowJar below keeps its own stamp for a standalone/dev run.
tasks.jar {
    manifest { attributes["Implementation-Version"] = project.version.toString() }
}

// A self-contained runnable server jar (the aggregator holds all three surfaces + KSLCore, so it is
// NOT a thin jar). mergeServiceFiles keeps ktor + the MCP SDK's ServiceLoader registrations working.
tasks.shadowJar {
    archiveBaseName.set("ksl-suite-mcp")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "ksl.server.suite.MainKt"
        // BuildInfo.version reads this from the package's implementationVersion at runtime.
        attributes["Implementation-Version"] = project.version.toString()
    }
}
