
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
// "shared lib/ + thin per-app JAR + launcher" KSLWork layout under build/kslwork, proving
// the thin-jar-over-shared-lib mechanism on system Java. CS1 covers one app (Single);
// later change sets scale to all apps, kslpkg, and the servers.
val kslWorkDir = layout.buildDirectory.dir("kslwork")

// Force :KSLAppSwingSingle to evaluate so its `jar` task and runtimeClasspath exist here.
val singleApp = evaluationDependsOn(":KSLAppSwingSingle")
val singleJarTask = singleApp.tasks.named("jar")
val singleRuntimeCp = singleApp.configurations.named("runtimeClasspath")

// macOS launcher generated from a template. "DOLLAR" stands in for a literal shell '$' so
// the Kotlin string needs no per-character escaping; it is substituted back at the end.
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

fun macLauncher(name: String, mainClass: String): String =
    macLauncherTemplate
        .replace("@NAME@", name)
        .replace("@MAIN@", mainClass)
        .replace("DOLLAR", "\$") + "\n"

tasks.register("assembleKSLWork") {
    group = "distribution"
    description = "Assemble the KSLWork payload (shared lib/ + thin app JARs + launchers) under build/kslwork"
    dependsOn(singleJarTask)
    inputs.files(singleRuntimeCp)
    inputs.files(singleJarTask)
    outputs.dir(kslWorkDir)
    doLast {
        val root = kslWorkDir.get().asFile
        root.deleteRecursively()
        // shared runtime stack = the app's resolved dependency jars (KSLApp, KSLCore,
        // KSLAppSwingCommon, lets-plot, POI, DB drivers, …) — ONE copy for all consumers.
        val libDir = root.resolve("lib").apply { mkdirs() }
        singleRuntimeCp.get().files.forEach { it.copyTo(libDir.resolve(it.name), overwrite = true) }
        // thin app jar (its own classes only), renamed to the stable launcher-contract name.
        val appDir = root.resolve("Apps/Single").apply { mkdirs() }
        val appJar = (singleJarTask.get() as org.gradle.api.tasks.bundling.Jar).archiveFile.get().asFile
        appJar.copyTo(appDir.resolve("Single.jar"), overwrite = true)
        // macOS launcher
        val launcher = appDir.resolve("Single.command")
        launcher.writeText(macLauncher("Single", "ksl.app.swing.single.BundleLaunchedSingleAppKt"))
        launcher.setExecutable(true)
        logger.lifecycle("assembleKSLWork: ${libDir.listFiles()?.size ?: 0} shared libs -> $libDir")
        logger.lifecycle("assembleKSLWork: Apps/Single/{Single.jar, Single.command} -> $appDir")
    }
}