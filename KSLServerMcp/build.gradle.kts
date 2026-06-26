import org.gradle.api.tasks.application.CreateStartScripts

plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "io.github.rossetti"
// Phase 9 server-stack version (single source: root gradle.properties).
version = (findProperty("kslServerVersion") as String?) ?: "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // The MCP transport over the headless service core. Ktor and the MCP SDK
    // are isolated here; they never reach KSLServiceCore or KSLCore.
    implementation(project(":KSLServiceCore"))
    implementation("io.modelcontextprotocol:kotlin-sdk:0.8.3")
    // Embedded HTTP engine for the Streamable HTTP (SSE) MCP transport.
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.32")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Client engine for the HTTP transport integration test (the SSE client
    // plugin ships in ktor-client-core, pulled transitively by the MCP SDK).
    testImplementation("io.ktor:ktor-client-cio:3.2.3")
    // MM1 / LKInventory / SimOpt manifest-bundle fixtures (KSLTestModels'
    // ManifestBundleFixtures + named builders) for bundles loaded via
    // BundleRegistry.fromDirectories(...) in tests.
    testImplementation(project(":KSLTestModels"))
}

application {
    mainClass.set("ksl.server.mcp.MainKt")
    // Select the MCP logging config explicitly. KSLCore also ships a `logback.xml`
    // whose root console appender defaults to STDOUT; on a shared classpath the
    // auto-selected config is non-deterministic. The MCP stdio transport uses
    // STDOUT as its protocol channel, so a stray stdout log line would corrupt it.
    // This makes the stderr-only config win regardless of classpath order. Applies
    // to the `run` task and the generated start scripts (the deployed launcher).
    applicationDefaultJvmArgs = listOf("-Dlogback.configurationFile=logback-ksl-mcp.xml")
}

kotlin {
    jvmToolchain(21)
}

// Self-contained single-jar for the student "idiot-proof" deployment:
//   java -jar ksl-mcp.jar [--stdio | --doctor | --setup]
// mergeServiceFiles keeps ktor + the MCP SDK's ServiceLoader registrations
// working under shading; there is deliberately NO package relocation, so
// dynamically-loaded bundle jars still resolve ksl.* classes from this jar.
tasks.shadowJar {
    archiveBaseName.set("ksl-mcp")
    archiveClassifier.set("")
    archiveVersion.set("")          // -> build/libs/ksl-mcp.jar
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "ksl.server.mcp.LauncherKt",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

// Stamp the version into the jar manifest (A7), matching KSLServiceCore so all
// server artifacts carry the same Implementation-Version.
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}

// Inspection helper: copies the resolved runtime jars so their APIs can be
// confirmed with javap before coding against them.
tasks.register<Copy>("dumpDeps") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("deps"))
}

// ---- HTTP (SSE) transport launcher (A6) -------------------------------------
// The `application` plugin wires the stdio entrypoint (MainKt) as the `run` task
// and the generated start script. The MCP server also has an HTTP entrypoint
// (MainHttpKt) for the Streamable-HTTP/SSE transport; the following make it
// launchable the same way: a `runHttp` dev task and a second start script
// (`ksl-mcp-http`) bundled into the distribution, so a deployment can start
// either transport from one install. Both reuse the stderr-only logback config —
// harmless for HTTP, and required for stdio (stdout is its protocol channel).
val mcpJvmArgs = listOf("-Dlogback.configurationFile=logback-ksl-mcp.xml")

tasks.register<JavaExec>("runHttp") {
    group = "application"
    description = "Runs the KSL MCP server over the HTTP (SSE / Streamable HTTP) transport."
    mainClass.set("ksl.server.mcp.MainHttpKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs = mcpJvmArgs
}

// A parallel start script for the HTTP entrypoint, reusing the application
// plugin's resolved classpath so it shares the dist's `lib/` layout.
val httpStartScripts = tasks.register<CreateStartScripts>("httpStartScripts") {
    description = "Generates the HTTP-transport launcher script for the distribution."
    mainClass.set("ksl.server.mcp.MainHttpKt")
    applicationName = "ksl-mcp-http"
    outputDir = layout.buildDirectory.dir("scriptsHttp").get().asFile
    classpath = tasks.startScripts.get().classpath
    defaultJvmOpts = mcpJvmArgs
}

distributions {
    main {
        contents {
            from(httpStartScripts) {
                into("bin")
                filePermissions { unix("0755") }
            }
        }
    }
}
