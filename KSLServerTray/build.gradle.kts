// KSLServerTray — the "KSL Server" menu-bar / system-tray agent (Phase F4).
//
// The Postgres.app / Docker-Desktop model: a small resident supervisor that lives in the macOS menu
// bar / Windows system tray, shows a status lamp, starts the long-running suite as a managed CHILD
// process, and opens the web console for detailed work. It is the ONLY AWT here — all the operating
// logic lives in the GUI-agnostic KSLServerManager (ServerManagerController + ServerProcessInventory),
// so this module is a thin presentation over that seam, packaged THIN over the shared lib/ like the
// desktop apps. Start/Stop/Configure/capabilities/usage stay in the console, not the tray.
plugins {
    kotlin("jvm") version "2.2.0"
    application
}

group = "io.github.rossetti"
// Ships with the suite, so it carries the whole-distribution version.
version = (findProperty("kslSuiteVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    // The management seam: controller (StateFlow health/status + start/stop) + process inventory +
    // client-config. Brings KSLServiceCore -> KSLCore/KSLApp transitively (all in the shared lib/).
    implementation(project(":KSLServerManager"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")   // collect the controller's StateFlows
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("ksl.server.tray.MainKt")
    // apple.awt.UIElement=true makes the macOS process a menu-bar AGENT (no Dock tile, no app menu) —
    // exactly a background server's tray icon. Harmless/ignored off macOS. Logs to ~/.ksl/logs.
    applicationDefaultJvmArgs = listOf(
        "-Dapple.awt.UIElement=true",
        "-Dlogback.configurationFile=logback-ksl-server.xml",
    )
}

tasks.test { useJUnitPlatform() }

// The thin distribution jar carries the version so `ksl-server --version` reports the real release.
tasks.jar {
    manifest { attributes["Implementation-Version"] = project.version.toString() }
}
