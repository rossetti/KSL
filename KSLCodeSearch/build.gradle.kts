// KSLCodeSearch — the mcp-free source-code search library (Phase A2).
//
// Store + in-memory Lucene search over KSL declarations extracted at build time from ../KSLCore and
// ../KSLExamples via the Kotlin compiler PSI. Copied and refactored from KSLCodeMCPServer: the MCP
// server (CodeMcpServer/ToolHandlers), agent setup (AgentSetup/SetupGui), and launchers are left
// behind in the old module — the MCP capability adapter lives in KSLMcpSuite. No `application`/
// `shadow`, no mcp-sdk. Packages renamed ksl.code.mcp -> ksl.code.search and ksl.code.gen ->
// ksl.code.search.gen so its classes never collide with the still-shipping KSLCodeMCPServer.
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "io.github.rossetti"
version = (findProperty("kslCodeSearchVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

// The KSL git ref the bundled index corresponds to (source-blob citation URLs and the reported
// version). Pin to a release tag for a course build: -PkslVersion=v2.0.1. Defaults to develop.
val kslVersion = (findProperty("kslVersion") as String?) ?: "develop"

// A second source set for the build-time declaration extractor. It depends on the Kotlin compiler
// (PSI) to parse .kt files robustly — a heavyweight dependency deliberately kept OUT of the library
// jar by isolating it here (the jar bundles only `main` + its runtime classpath, never `gen`).
val gen: SourceSet by sourceSets.creating

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.lucene:lucene-core:10.2.2")
    implementation("org.apache.lucene:lucene-analysis-common:10.2.2")
    implementation("org.apache.lucene:lucene-queryparser:10.2.2")
    implementation("org.apache.lucene:lucene-queries:10.2.2")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    // ---- build-time extractor (never shipped) ----
    // Depend on main's compiled classes ONLY (classesDirs, not the full output): the full output is
    // built by `classes`, which drags in processResources -> generateCodeContent -> gen, a cycle.
    // classesDirs is built by compileKotlin alone, so gen sees the shared @Serializable model.
    "genImplementation"(files(sourceSets["main"].output.classesDirs))
    "genImplementation"("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.0")
    "genImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    "genImplementation"("io.github.oshai:kotlin-logging-jvm:7.0.0")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Unit-test the build-time extractor directly (compiler-embeddable stays test/gen-only).
    testImplementation(sourceSets["gen"].output)
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.0")
}

tasks.test { useJUnitPlatform() }

// ---- content generation ----
// Reads ../KSLCore + ../KSLExamples source, writes code/chunks.json + code/meta.json into
// build/generated/code, a resource root bundled into the jar.
val kslRepoRoot = layout.projectDirectory.dir("..")
val generatedCodeDir = layout.buildDirectory.dir("generated/code")
val topicsFile = layout.projectDirectory.file("topics.json")

val generateCodeContent by tasks.registering(JavaExec::class) {
    group = "code"
    description = "Extract public KSL declarations into chunks.json via the Kotlin compiler PSI"
    classpath = sourceSets["gen"].runtimeClasspath
    mainClass.set("ksl.code.search.gen.ExtractDeclarationsKt")
    args(
        kslRepoRoot.asFile.absolutePath,
        generatedCodeDir.get().asFile.absolutePath,
        topicsFile.asFile.absolutePath,
        kslVersion,
    )
    inputs.dir(kslRepoRoot.dir("KSLCore/src/main/kotlin"))
    inputs.dir(kslRepoRoot.dir("KSLExamples/src/main/kotlin"))
    inputs.files(topicsFile)
    inputs.property("kslVersion", kslVersion)
    outputs.dir(generatedCodeDir)
}

sourceSets.main { resources.srcDir(generatedCodeDir) }
tasks.processResources { dependsOn(generateCodeContent) }

// Dev harness: query the bundled index from the command line: ./gradlew searchCode -Pq="..."
val searchCode by tasks.registering(JavaExec::class) {
    group = "code"
    description = "Query the search index from the command line: -Pq=\"...\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ksl.code.search.SearchCliKt")
    args((findProperty("q") as String?)?.split(" ") ?: emptyList<String>())
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}
