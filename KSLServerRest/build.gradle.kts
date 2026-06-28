plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
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
// plan §B5. Applied to all configurations (incl. test) so tests reflect the
// deployed, headless runtime; compile-safe — no code references the frontend.
configurations.all {
    exclude(group = "org.jetbrains.lets-plot", module = "lets-plot-batik")
    exclude(group = "org.jetbrains.lets-plot", module = "platf-batik-jvm")
}

dependencies {
    // The REST/SSE transport over the headless service core. Ktor is isolated
    // here; it never reaches KSLServiceCore or KSLCore.
    implementation(project(":KSLServiceCore"))
    implementation("io.ktor:ktor-server-core:3.2.3")
    implementation("io.ktor:ktor-server-cio:3.2.3")
    implementation("io.ktor:ktor-server-sse:3.2.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.2.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.3")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.32")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("io.ktor:ktor-server-test-host:3.2.3")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.2.3")
    // MM1 manifest-bundle fixture (KSLTestModels' ManifestBundleFixtures) for
    // bundles loaded via BundleRegistry.fromDirectories(...) in tests.
    testImplementation(project(":KSLTestModels"))
}

application {
    mainClass.set("ksl.server.rest.MainKt")
    // Pin the Logback config explicitly. KSLCore also ships a `logback.xml` on the
    // runtime classpath, so a same-named resource here would make the auto-selected
    // config non-deterministic; the unique `logback-ksl-rest.xml` + this arg make
    // the REST config win regardless of classpath order. Applies to the `run` task
    // and the generated start scripts (the deployed launcher).
    applicationDefaultJvmArgs = listOf("-Dlogback.configurationFile=logback-ksl-rest.xml")
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
