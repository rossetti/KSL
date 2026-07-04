plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "io.github.rossetti"
version = (findProperty("kslCodeMcpVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

// The KSL git ref the bundled index corresponds to (source-blob citation URLs and
// the version reported by get_server_info). Pin to a release tag for a course
// build: -PkslVersion=v2.0.1. Defaults to the branch under development.
val kslVersion = (findProperty("kslVersion") as String?) ?: "develop"

// A second source set for the build-time declaration extractor. It depends on the
// Kotlin compiler (PSI) to parse .kt files robustly — a heavyweight dependency we
// deliberately keep OUT of the shipped server jar by isolating it here: shadowJar
// bundles only `main` + its runtime classpath, never `gen`.
val gen: SourceSet by sourceSets.creating

dependencies {
    // ---- runtime server (bundled into the fat jar) ----
    implementation("io.modelcontextprotocol:kotlin-sdk:0.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.apache.lucene:lucene-core:10.2.2")
    implementation("org.apache.lucene:lucene-analysis-common:10.2.2")
    implementation("org.apache.lucene:lucene-queryparser:10.2.2")
    implementation("org.apache.lucene:lucene-queries:10.2.2")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    // ---- build-time extractor (never shipped) ----
    // Kotlin PSI parses every declaration form (generics, annotations, sealed
    // hierarchies, extension functions, companions) that a regex scanner mishandles.
    // Depend on main's compiled classes ONLY (classesDirs, not the full output): the
    // full output is built by `classes`, which drags in processResources ->
    // generateCodeContent -> gen, a configuration-time cycle. classesDirs is built by
    // compileKotlin alone, so gen sees the shared @Serializable model with no cycle.
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

application {
    mainClass.set("ksl.code.mcp.LauncherKt")
    // stdout is the MCP stdio channel; logging must go to stderr only
    applicationDefaultJvmArgs = listOf("-Dlogback.configurationFile=logback-ksl-code-mcp.xml")
}

tasks.test { useJUnitPlatform() }

// ---- content generation ----
// Reads ../KSLCore + ../KSLExamples source, writes code/chunks.json + code/meta.json
// into build/generated/code, which is a resource root bundled into the jar.
val kslRepoRoot = layout.projectDirectory.dir("..")
val generatedCodeDir = layout.buildDirectory.dir("generated/code")
val topicsFile = layout.projectDirectory.file("topics.json")

val generateCodeContent by tasks.registering(JavaExec::class) {
    group = "code"
    description = "Extract public KSL declarations into chunks.json via the Kotlin compiler PSI"
    classpath = sourceSets["gen"].runtimeClasspath
    mainClass.set("ksl.code.gen.ExtractDeclarationsKt")
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

// Dev harness: query the bundled index from the command line.
//   ./gradlew searchCode -Pq="seize release resource"
val searchCode by tasks.registering(JavaExec::class) {
    group = "code"
    description = "Query the search index from the command line: -Pq=\"...\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ksl.code.mcp.SearchCliKt")
    args((findProperty("q") as String?)?.split(" ") ?: emptyList<String>())
}

tasks.shadowJar {
    archiveBaseName.set("ksl-code-mcp")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "ksl.code.mcp.LauncherKt",
            "Implementation-Version" to project.version,
        )
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
        )
    }
}
