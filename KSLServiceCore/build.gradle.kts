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

dependencies {
    // KSLServiceCore is the headless, transport-agnostic service core that the
    // REST (Ktor) and MCP transports embed. It depends on KSLCore and the
    // headless KSLApp app-services layer (which hosts the ksl.app.bundle
    // subsystem after the module reorganization) — no transport dependency ever
    // leaks in. kotlinx-datetime and kotlinx-serialization-json arrive
    // transitively via KSLCore's `api` configuration, so the wire DTOs can be
    // @Serializable.
    api(project(":KSLCore"))
    // The bundle subsystem (BundleLoader, LoadedBundle, ManifestBackedBundle,
    // BundleModelProvider, KSLAppKind, KSLConfigRecipe) moved to KSLApp.
    api(project(":KSLApp"))
    // In-memory cache tier for the ResultStore (eviction/TTL/size under
    // concurrency); the persistent tier is a thin JSON-on-disk store.
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    // TOML codec for the server config document — the same `tomlkt` the KSLCore
    // run/optimization config codecs use (KSLCore keeps it `implementation`, so
    // it is not visible transitively; declared here for the server config).
    implementation("net.peanuuutz.tomlkt:tomlkt:0.4.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // The example dogfood bundles (MM1 / LKInventory / SimoptTestModels) are
    // test fixtures only — kept off any runtime classpath, the same hygiene
    // Phase 6F item 7 applies to the apps.
    testImplementation(project(":KSLExamples"))
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.32")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

// Stamp the version into the jar manifest so BuildInfo.version (read from this
// jar's `Implementation-Version` via the package) reports the real version at
// runtime instead of falling back to "dev" (A7).
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}
