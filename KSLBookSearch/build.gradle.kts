// KSLBookSearch — the mcp-free textbook search library (Phase A2).
//
// Store + in-memory Lucene search over content generated at build time from the rendered Quarto
// book (../_book, git-ignored; absent on a fresh clone / CI, where the generator degrades to empty
// content and the runtime tests skip). Copied and refactored from KSLBookServer: the MCP server
// (BookMcpServer/ToolHandlers), agent setup (AgentSetup/SetupGui), and launchers (Main/Launcher) are
// left behind in the old module — the MCP capability adapter that wraps this library lives in
// KSLMcpSuite. No `application`/`shadow` plugins, no mcp-sdk. Package renamed ksl.book.mcp ->
// ksl.book.search (and ksl.book.gen -> ksl.book.search.gen) so its classes never collide with the
// still-shipping KSLBookServer on a shared classpath.
plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "io.github.rossetti"
version = (findProperty("kslBookSearchVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.lucene:lucene-core:10.2.2")
    implementation("org.apache.lucene:lucene-analysis-common:10.2.2")
    implementation("org.apache.lucene:lucene-queryparser:10.2.2")
    implementation("org.apache.lucene:lucene-queries:10.2.2")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.vladsch.flexmark:flexmark-html2md-converter:0.64.8")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

// ---- content generation ----
// The rendered Quarto book lives in the repo-root _book/ (git-ignored; copied in by hand after
// `quarto render`). Absent on a fresh clone or in CI — the generator degrades gracefully to empty
// content so the module still builds and the runtime tests skip.
val bookHtmlDir = layout.projectDirectory.dir("../_book")
val generatedBookDir = layout.buildDirectory.dir("generated/book")
val topicsFile = layout.projectDirectory.file("topics.json")

val generateBookContent by tasks.registering(JavaExec::class) {
    group = "book"
    description = "Parse rendered HTML into chunks.json + exercises.json"
    // compiled classes + libs only; using the full runtimeClasspath would pull in processResources
    // and create a task cycle
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath.get())
    mainClass.set("ksl.book.search.gen.ChunkBookKt")
    args(bookHtmlDir.asFile.absolutePath, generatedBookDir.get().asFile.absolutePath, topicsFile.asFile.absolutePath)
    // _book/ is git-ignored and may be absent (fresh clone / CI). Track it as an input only when
    // present, so its absence doesn't fail task validation; the generator itself degrades to empty
    // content (see ChunkBook.main). Re-evaluated each build, so adding/removing _book/ regenerates.
    if (bookHtmlDir.asFile.exists()) inputs.dir(bookHtmlDir)
    inputs.files(topicsFile)
    outputs.dir(generatedBookDir)
}

sourceSets.main { resources.srcDir(generatedBookDir) }
tasks.processResources { dependsOn(generateBookContent) }

// Dev harness: query the bundled index from the command line: ./gradlew searchBook -Pq="..."
val searchBook by tasks.registering(JavaExec::class) {
    group = "book"
    description = "Query the search index from the command line: -Pq=\"...\""
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ksl.book.search.SearchCliKt")
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
