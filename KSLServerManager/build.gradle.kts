// KSLServerManager — the GUI-agnostic management layer for the KSL MCP suite (Phase D).
//
// The KSLApp-analog for OPERATING the server (not running models): an HttpAdminOperations client over
// the suite's /admin surface (implementing the SAME ServerAdminOperations the suite serves in-process,
// parsing the SAME DTOs, so an external UI/CLI never diverges from the built-in console), a pure-JDK
// process inventory (discover/health/start/stop/clean up KSL JVMs), local client-config via
// KSLAgentConfig, a ServerManagerController exposing StateFlow state + commands with an INJECTED
// coroutine scope (so a web/CLI/desktop front-end is a genuine peer), and a launcher CLI. NO Swing/AWT
// widgets — the dispatcher is the front-end's to provide.
plugins {
    kotlin("jvm") version "2.2.0"
    application
}

group = "io.github.rossetti"
version = (findProperty("kslServerManagerVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":KSLServiceCore")) // ServerAdminOperations + admin DTOs + usage types + ServerConfig
    implementation(project(":KSLAgentConfig"))  // AgentConfigurator + LaunchSpec (local client config)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")   // parse the admin DTOs from /admin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")      // controller StateFlows + injected scope
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    // The local launcher: detect-or-start the suite, open the console, and own its lifecycle.
    mainClass.set("ksl.server.manage.LauncherKt")
}

tasks.test { useJUnitPlatform() }
