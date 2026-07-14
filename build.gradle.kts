
plugins {
    `java-library`
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
    id("org.jetbrains.dokka") version "2.1.0"
}

group = "io.github.rossetti"
version = "R1.2.1"

repositories {
    mavenCentral()
}

// The root project is a coordinator for this multi-project build: it has no
// sources of its own and depends on no module. It previously declared
// api(project(":KSLCore")) + api(project(":KSLExamples")) as a vestigial
// aggregator — that made the root depend on KSLExamples even though nothing
// consumes the root project, so it has been removed. KSLExamples is now a
// true sink (no module, and not the root, depends on it).

kotlin {
    jvmToolchain(21)
}

// Keep generated simulation output out of the source tree during tests. KSL's
// default output root is the relative path "kslOutput", created in the process
// working directory — which for a Gradle test is the module dir, so a test run
// otherwise scatters a kslOutput/ of per-model folders into every module. Point the
// ksl.outputDir hook (see KSL.kt) at each module's build/ so it is gitignored and
// removed by `clean`. This affects ONLY this repo's Test tasks; an IDE run or a
// student's KSLProjectTemplate leaves the property unset and still gets kslOutput/
// at the project root.
allprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("ksl.outputDir", layout.buildDirectory.dir("kslOutput").get().asFile.path)
    }
}

// Repo-wide source-hygiene check (formerly the JUnit test ksl.conventions.BacktickNameAsciiTest):
// no Kotlin function declared with a backtick identifier may contain a non-ASCII character.
// Backtick function names become part of the generated .class file name; a non-ASCII char there
// (em-dash, smart quote, etc.) cannot be encoded under common platform encodings and breaks the
// build. Implemented as a verification task (rather than a unit test) because it is build hygiene,
// not module behavior — it scans every module's sources and runs as part of `check`/`build`.
val checkBacktickNames by tasks.registering {
    group = "verification"
    description = "Fail if any backtick-quoted Kotlin function name contains a non-ASCII character."
    val repoRoot = rootDir
    doLast {
        val funKeyword = Regex("""\bfun\b""")
        val backtickName = Regex("`([^`]+)`")
        val violations = mutableListOf<String>()
        repoRoot.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { idx, raw ->
                        val line = raw.trimStart()
                        // Skip comment lines so backticked prose in KDoc is ignored.
                        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                        if (!funKeyword.containsMatchIn(line)) return@forEachIndexed
                        for (m in backtickName.findAll(raw)) {
                            val name = m.groupValues[1]
                            if (name.any { it.code > 0x7F }) {
                                violations += "${file.relativeTo(repoRoot).path}:${idx + 1}: `$name`"
                            }
                        }
                    }
                }
            }
        if (violations.isNotEmpty()) {
            throw GradleException(buildString {
                appendLine("Found backtick function name(s) with non-ASCII characters.")
                appendLine("Replace the non-ASCII character(s) with an ASCII equivalent (e.g. em-dash -> '-'):")
                violations.forEach { appendLine("  $it") }
            })
        }
    }
}

tasks.named("check") { dependsOn(checkBacktickNames) }

// ---- KSLWork payload assembly — Release & Distribution Plan, Phase 1 --------------
// ADDITIVE: this does NOT replace the jpackage / org.beryx.runtime build. It stages the
// "shared lib/ + thin per-app JAR + launcher" KSLWork layout under build/kslwork, running on
// the student's system Java (no bundled JRE). CS1 proved the mechanism on one app; CS2 scales
// to all 8 desktop apps (thin over ONE shared lib/) + kslpkg (a deliberately-trimmed,
// self-contained fat jar under Tools/). Cross-platform launchers + the zip come in a later step.
val kslWorkDir = layout.buildDirectory.dir("kslwork")

// Desktop apps: module -> Apps/<target> name. Each app's mainClass is read from its own
// `application {}` block at assembly time, so it is never duplicated here.
val kslAppTargets: List<Pair<Project, String>> = linkedMapOf(
    "KSLAppSwingSingle" to "Single",
    "KSLAppSwingScenario" to "Scenario",
    "KSLAppSwingExperiment" to "Experiment",
    "KSLAppSwingSimopt" to "Simopt",
    "KSLAppSwingDistribution" to "Distribution",
    "KSLAppSwingResults" to "Results",
    "KSLAppSwingBundle" to "Bundle",
    "KSLAppSwingAnimation" to "Animation",
).map { (module, target) -> evaluationDependsOn(":$module") to target }

// kslpkg is a deliberately-trimmed, self-contained fat jar (it excludes the DB drivers /
// lets-plot / POI the shared lib/ carries), so it ships standalone under Tools/, NOT over lib/.
val kslBundleTools = evaluationDependsOn(":KSLBundleTools")

// Launchers generated from templates. "DOLLAR" stands in for a literal shell '$' so the Kotlin
// string needs no per-character escaping; it is substituted back at the end. The app launcher
// runs a thin `-cp lib/*:App.jar Main`; the CLI launcher runs a self-contained `-jar tool.jar`.
val macLauncherTemplate = """
    #!/bin/bash
    # KSL @NAME@ desktop app — KSLWork launcher (runs on your system Java 21).
    set -e
    DIR="DOLLAR(cd "DOLLAR(dirname "DOLLAR0")" && pwd)"
    KSLWORK="DOLLAR(cd "DOLLARDIR/../.." && pwd)"
    JAVA=java
    [ -n "DOLLARJAVA_HOME" ] && JAVA="DOLLARJAVA_HOME/bin/java"
    VER="DOLLAR("DOLLARJAVA" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
    if [ -z "DOLLARVER" ] || ! [ "DOLLARVER" -ge 21 ] 2>/dev/null; then
      echo "@NAME@ needs Java 21 — the same JDK you use in IntelliJ."
      echo "Found: DOLLAR("DOLLARJAVA" -version 2>&1 | head -1)"
      exit 1
    fi
    exec "DOLLARJAVA" -cp "DOLLARKSLWORK/lib/*:DOLLARDIR/@NAME@.jar" @MAIN@ "DOLLAR@"
""".trimIndent()

val cliLauncherTemplate = """
    #!/bin/bash
    # @NAME@ — KSLWork CLI launcher (runs on your system Java 21).
    set -e
    DIR="DOLLAR(cd "DOLLAR(dirname "DOLLAR0")" && pwd)"
    JAVA=java
    [ -n "DOLLARJAVA_HOME" ] && JAVA="DOLLARJAVA_HOME/bin/java"
    VER="DOLLAR("DOLLARJAVA" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
    if [ -z "DOLLARVER" ] || ! [ "DOLLARVER" -ge 21 ] 2>/dev/null; then
      echo "@NAME@ needs Java 21 — the same JDK you use in IntelliJ."
      echo "Found: DOLLAR("DOLLARJAVA" -version 2>&1 | head -1)"
      exit 1
    fi
    exec "DOLLARJAVA" -jar "DOLLARDIR/@NAME@.jar" "DOLLAR@"
""".trimIndent()

fun macLauncher(name: String, mainClass: String): String =
    macLauncherTemplate.replace("@NAME@", name).replace("@MAIN@", mainClass).replace("DOLLAR", "\$") + "\n"

fun cliLauncher(name: String): String =
    cliLauncherTemplate.replace("@NAME@", name).replace("DOLLAR", "\$") + "\n"

fun jarOf(task: org.gradle.api.tasks.TaskProvider<*>): java.io.File =
    (task.get() as org.gradle.api.tasks.bundling.Jar).archiveFile.get().asFile

tasks.register("assembleKSLWork") {
    group = "distribution"
    description = "Assemble the KSLWork payload (shared lib/ + thin app JARs + kslpkg + launchers) under build/kslwork"
    kslAppTargets.forEach { (app, _) ->
        dependsOn(app.tasks.named("jar"))
        inputs.files(app.configurations.named("runtimeClasspath"))
    }
    dependsOn(kslBundleTools.tasks.named("shadowJar"))
    inputs.files(kslBundleTools.tasks.named("shadowJar"))
    outputs.dir(kslWorkDir)
    doLast {
        val root = kslWorkDir.get().asFile
        root.deleteRecursively()
        val runtimeCps = kslAppTargets.map { (app, _) -> app.configurations.named("runtimeClasspath").get() }

        // Version-conflict guard: the shared lib/ must carry ONE version per artifact. From a
        // single commit this holds; if two apps ever resolve the same module to different
        // versions, fail loudly rather than silently ship both jars onto the classpath.
        val byModule = linkedMapOf<String, MutableSet<String>>()
        runtimeCps.forEach { cp ->
            cp.resolvedConfiguration.resolvedArtifacts.forEach { art ->
                val id = art.moduleVersion.id
                byModule.getOrPut("${id.group}:${id.name}") { sortedSetOf() }.add(id.version)
            }
        }
        val conflicts = byModule.filterValues { it.size > 1 }
        if (conflicts.isNotEmpty()) throw GradleException(
            "assembleKSLWork: version conflict(s) in the shared lib union (ship one version per artifact):\n" +
                conflicts.entries.joinToString("\n") { "  ${it.key} -> ${it.value}" })

        // shared lib/ = the complete union of the apps' runtime jars, deduped by filename.
        val libDir = root.resolve("lib").apply { mkdirs() }
        runtimeCps.flatMap { it.files }.associateBy { it.name }.values
            .forEach { it.copyTo(libDir.resolve(it.name), overwrite = true) }

        // each app: thin module jar (renamed to the stable launcher-contract name) + launcher.
        kslAppTargets.forEach { (app, target) ->
            val appDir = root.resolve("Apps/$target").apply { mkdirs() }
            jarOf(app.tasks.named("jar")).copyTo(appDir.resolve("$target.jar"), overwrite = true)
            val main = app.extensions.getByType(org.gradle.api.plugins.JavaApplication::class.java).mainClass.get()
            appDir.resolve("$target.command").apply { writeText(macLauncher(target, main)); setExecutable(true) }
        }

        // kslpkg: the trimmed, self-contained shadow fat jar + a CLI launcher (system Java, no JRE).
        val toolsDir = root.resolve("Tools/kslpkg").apply { mkdirs() }
        jarOf(kslBundleTools.tasks.named("shadowJar")).copyTo(toolsDir.resolve("kslpkg.jar"), overwrite = true)
        toolsDir.resolve("kslpkg").apply { writeText(cliLauncher("kslpkg")); setExecutable(true) }

        logger.lifecycle("assembleKSLWork: shared lib/ = ${libDir.listFiles()?.size ?: 0} jars; " +
            "${kslAppTargets.size} apps -> Apps/; kslpkg -> Tools/kslpkg (trimmed fat)")
    }
}