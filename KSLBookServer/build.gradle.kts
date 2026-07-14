plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "io.github.rossetti"
version = (findProperty("kslBookMcpVersion") as String?) ?: "1.0.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk:0.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
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

application {
    mainClass.set("ksl.book.mcp.LauncherKt")
    // stdout is the MCP stdio channel; logging must go to stderr only
    applicationDefaultJvmArgs = listOf("-Dlogback.configurationFile=logback-ksl-book-mcp.xml")
}

tasks.test { useJUnitPlatform() }

// ---- content generation ----
// The rendered Quarto book lives in the repo-root _book/ (git-ignored; copied in
// by hand after `quarto render`). Absent on a fresh clone or in CI — the generator
// degrades gracefully to empty content so the module still builds.
val bookHtmlDir = layout.projectDirectory.dir("../_book")
val generatedBookDir = layout.buildDirectory.dir("generated/book")

val topicsFile = layout.projectDirectory.file("topics.json")

val generateBookContent by tasks.registering(JavaExec::class) {
    group = "book"
    description = "Parse rendered HTML into chunks.json + exercises.json"
    // compiled classes + libs only; using the full runtimeClasspath would pull in
    // processResources and create a task cycle
    classpath = files(sourceSets.main.get().output.classesDirs, configurations.runtimeClasspath.get())
    mainClass.set("ksl.book.gen.ChunkBookKt")
    args(bookHtmlDir.asFile.absolutePath, generatedBookDir.get().asFile.absolutePath, topicsFile.asFile.absolutePath)
    // _book/ is git-ignored and may be absent (fresh clone / CI). Track it as an input
    // only when present, so its absence doesn't fail task validation; the generator
    // itself degrades to empty content (see ChunkBook.main). Re-evaluated each build, so
    // adding or removing _book/ still triggers regeneration.
    if (bookHtmlDir.asFile.exists()) inputs.dir(bookHtmlDir)
    inputs.files(topicsFile)
    outputs.dir(generatedBookDir)
}

sourceSets.main { resources.srcDir(generatedBookDir) }
tasks.processResources { dependsOn(generateBookContent) }

val searchBook by tasks.registering(JavaExec::class) {
    group = "book"
    description = "Query the search index from the command line: -Pq=\"...\""
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("ksl.book.mcp.SearchCliKt")
    args((findProperty("q") as String?)?.split(" ") ?: emptyList<String>())
}

tasks.shadowJar {
    archiveBaseName.set("ksl-book-mcp")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "ksl.book.mcp.LauncherKt",
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
