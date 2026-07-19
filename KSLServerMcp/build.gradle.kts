plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "io.github.rossetti"
// Phase 9 server-stack version (single source: root gradle.properties).
version = (findProperty("kslServerVersion") as String?) ?: "1.0.0"

repositories {
    mavenCentral()
}

// Headless server: drop the lets-plot Swing *display* frontend (pulled in
// transitively from KSLCore). Its static initializer eagerly constructs an AWT
// window to probe for a display and throws HeadlessException with none, which
// poisons the LetsPlot class for the whole JVM. Without it the frontend probe
// degrades to a no-op context, so report rendering (HTML-embed and SVG/PNG image
// export via lets-plot-image-export + Apache Batik) works fully headless. The
// desktop apps keep the frontend for interactive display. See the gap-closure
// plan §B5. Applied to all configurations (incl. test + shadowJar) so the shaded
// jar and tests reflect the deployed, headless runtime; compile-safe — no code
// references the frontend.
configurations.all {
    exclude(group = "org.jetbrains.lets-plot", module = "lets-plot-batik")
    exclude(group = "org.jetbrains.lets-plot", module = "platf-batik-jvm")
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

kotlin {
    jvmToolchain(21)
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

// KSLServerMcp is a LIBRARY (the suite's sim-capability): KslMcpServer.registerKslTools + KslMcpTools.
// Phase I removed its standalone scaffolding (Main / MainHttp / Launcher / KslMcpHttpServer / AgentSetup /
// SetupGui) and the application/shadow plugins; the suite (KSLMcpSuite) provides the transport + logback.
