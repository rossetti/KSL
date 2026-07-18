// KSLAppSwingServers — the "KSL Server Manager" app (WS3). It configures, starts/stops, health-checks,
// and cleans up the KSL MCP suite for students. Phase 5a is the headless-friendly CORE (process
// lifecycle + health + cleanup, pure JDK); the Swing GUI (KSLAppSwingCommon look/feel) and the client
// configurator are layered on top in later steps. Deliberately light — NO KSLCore.
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
}

group = "io.github.rossetti"
version = (findProperty("kslServerManagerVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    // GUI: match the other KSL desktop apps' look/feel via the shared Swing module (Phase 5c). This
    // pulls the app stack (KSLApp -> KSLCore) in, making the manager a normal-weight desktop app --
    // acceptable because it is launched on demand, not a resident server.
    implementation(project(":KSLCore"))
    implementation(project(":KSLApp"))
    implementation(project(":KSLAppSwingCommon"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0") // Claude config JSON merge
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("ksl.app.servers.ServerManagerAppKt")
}

tasks.test { useJUnitPlatform() }
