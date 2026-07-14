
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

// KSL-runtime servers: (project, Servers/<dir>, extra launchers). They build on KSLServiceCore ->
// KSLApp/KSLCore (already in the shared lib/), so they ship THIN over lib/ + a small server-lib/
// of the extras the apps don't carry (KSLServiceCore, Ktor, MCP SDK). The primary launcher's main
// is read from application{}; mcp adds an HTTP entry point that lives outside application{}, named
// explicitly here.
val kslServers: List<Triple<Project, String, List<Pair<String, String>>>> = listOf(
    Triple(evaluationDependsOn(":KSLServerMcp"), "mcp", listOf("ksl-mcp-http" to "ksl.server.mcp.MainHttpKt")),
    Triple(evaluationDependsOn(":KSLServerRest"), "rest", emptyList()),
)

// Standalone MCP servers: (project, Servers/<dir>). Self-contained fat shadowJars that share
// NOTHING with the KSL runtime stack (MCP SDK + Lucene, no KSLCore) — no lib/, no server-lib/.
// ksl-book bakes in the git-ignored _book/ render (empty content if absent); ksl-code bakes a
// Lucene index of KSL source built at assembly time (pinned via -PkslVersion, default develop).
val kslStandaloneServers: List<Pair<Project, String>> = listOf(
    evaluationDependsOn(":KSLCodeMCPServer") to "code",
    evaluationDependsOn(":KSLBookServer") to "book",
)

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
    exec "DOLLARJAVA"@JVMARGS@ -cp "DOLLARKSLWORK/lib/*:DOLLARDIR/@NAME@.jar" @MAIN@ "DOLLAR@"
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
    exec "DOLLARJAVA"@JVMARGS@ -jar "DOLLARDIR/@NAME@.jar" "DOLLAR@"
""".trimIndent()

val serverLauncherTemplate = """
    #!/bin/bash
    # @NAME@ — KSLWork server launcher (runs on your system Java 21).
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
    exec "DOLLARJAVA"@JVMARGS@ -cp "DOLLARDIR/server-lib/*:DOLLARKSLWORK/lib/*:DOLLARDIR/@JAR@.jar" @MAIN@ "DOLLAR@"
""".trimIndent()

fun macLauncher(name: String, mainClass: String, jvmArgs: String): String =
    macLauncherTemplate.replace("@NAME@", name).replace("@MAIN@", mainClass)
        .replace("@JVMARGS@", jvmArgs).replace("DOLLAR", "\$") + "\n"

fun cliLauncher(name: String, jvmArgs: String): String =
    cliLauncherTemplate.replace("@NAME@", name).replace("@JVMARGS@", jvmArgs).replace("DOLLAR", "\$") + "\n"

fun serverLauncher(name: String, jar: String, mainClass: String, jvmArgs: String): String =
    serverLauncherTemplate.replace("@NAME@", name).replace("@JAR@", jar)
        .replace("@MAIN@", mainClass).replace("@JVMARGS@", jvmArgs).replace("DOLLAR", "\$") + "\n"

fun jarOf(task: org.gradle.api.tasks.TaskProvider<*>): java.io.File =
    (task.get() as org.gradle.api.tasks.bundling.Jar).archiveFile.get().asFile

// Each app/server's `application {}` may set applicationDefaultJvmArgs (e.g. the servers'
// -Dlogback.configurationFile that routes logging to stderr — essential for a clean MCP stdio
// channel). Propagate them into the launcher; leading-space keeps the exec line clean when empty.
fun jvmArgsOf(project: Project): String {
    val args = project.extensions.getByType(org.gradle.api.plugins.JavaApplication::class.java)
        .applicationDefaultJvmArgs.joinToString(" ")
    return if (args.isEmpty()) "" else " $args"
}

// Windows .cmd launchers (written CRLF). Batch uses %VAR% / %* / ';' and no '$', so these
// templates need no escaping. GUI apps use javaw + start (no lingering console); the KSL-runtime
// servers and CLIs use java. The full Java-21 check stays in the installer preflight.
val winAppTemplate = """
    @echo off
    setlocal
    set "JAVAW=javaw"
    if defined JAVA_HOME set "JAVAW=%JAVA_HOME%\bin\javaw"
    "%JAVAW%" -version >nul 2>&1
    if errorlevel 1 (
      echo @NAME@ needs Java 21 - the same JDK you use in IntelliJ.
      pause
      exit /b 1
    )
    start "" "%JAVAW%"@JVMARGS@ -cp "%~dp0..\..\lib\*;%~dp0@NAME@.jar" @MAIN@ %*
""".trimIndent()

val winServerTemplate = """
    @echo off
    setlocal
    set "JAVA=java"
    if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
    "%JAVA%" -version >nul 2>&1
    if errorlevel 1 (
      echo @NAME@ needs Java 21 - the same JDK you use in IntelliJ.
      exit /b 1
    )
    "%JAVA%"@JVMARGS@ -cp "%~dp0server-lib\*;%~dp0..\..\lib\*;%~dp0@JAR@.jar" @MAIN@ %*
""".trimIndent()

val winCliTemplate = """
    @echo off
    setlocal
    set "JAVA=java"
    if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"
    "%JAVA%" -version >nul 2>&1
    if errorlevel 1 (
      echo @NAME@ needs Java 21 - the same JDK you use in IntelliJ.
      exit /b 1
    )
    "%JAVA%"@JVMARGS@ -jar "%~dp0@NAME@.jar" %*
""".trimIndent()

fun winAppLauncher(name: String, mainClass: String, jvmArgs: String) =
    (winAppTemplate.replace("@NAME@", name).replace("@MAIN@", mainClass)
        .replace("@JVMARGS@", jvmArgs)).replace("\n", "\r\n") + "\r\n"

fun winServerLauncher(name: String, jar: String, mainClass: String, jvmArgs: String) =
    (winServerTemplate.replace("@NAME@", name).replace("@JAR@", jar).replace("@MAIN@", mainClass)
        .replace("@JVMARGS@", jvmArgs)).replace("\n", "\r\n") + "\r\n"

fun winCliLauncher(name: String, jvmArgs: String) =
    (winCliTemplate.replace("@NAME@", name).replace("@JVMARGS@", jvmArgs)).replace("\n", "\r\n") + "\r\n"

tasks.register("assembleKSLWork") {
    group = "distribution"
    description = "Assemble the KSLWork payload (shared lib/ + thin app JARs + kslpkg + launchers) under build/kslwork"
    kslAppTargets.forEach { (app, _) ->
        dependsOn(app.tasks.named("jar"))
        inputs.files(app.configurations.named("runtimeClasspath"))
    }
    dependsOn(kslBundleTools.tasks.named("shadowJar"))
    inputs.files(kslBundleTools.tasks.named("shadowJar"))
    kslServers.forEach { (server, _, _) ->
        dependsOn(server.tasks.named("jar"))
        inputs.files(server.configurations.named("runtimeClasspath"))
    }
    kslStandaloneServers.forEach { (server, _) ->
        dependsOn(server.tasks.named("shadowJar"))
        inputs.files(server.tasks.named("shadowJar"))
    }
    inputs.file("ksl")
    inputs.file("ksl.ps1")
    inputs.file("ksl.cmd")
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
            val appJvm = jvmArgsOf(app)
            appDir.resolve(target).apply { writeText(macLauncher(target, main, appJvm)); setExecutable(true) }
            appDir.resolve("$target.cmd").writeText(winAppLauncher(target, main, appJvm))
        }

        // kslpkg: the trimmed, self-contained shadow fat jar + a CLI launcher (system Java, no JRE).
        val toolsDir = root.resolve("Tools/kslpkg").apply { mkdirs() }
        jarOf(kslBundleTools.tasks.named("shadowJar")).copyTo(toolsDir.resolve("kslpkg.jar"), overwrite = true)
        val kslpkgJvm = jvmArgsOf(kslBundleTools)
        toolsDir.resolve("kslpkg").apply { writeText(cliLauncher("kslpkg", kslpkgJvm)); setExecutable(true) }
        toolsDir.resolve("kslpkg.cmd").writeText(winCliLauncher("kslpkg", kslpkgJvm))

        // KSL-runtime servers: thin server jar + a small server-lib/. server-lib/ holds the deps
        // the shared lib/ does NOT already carry at the SAME version — i.e. the server-only extras
        // (KSLServiceCore, Ktor, MCP SDK) PLUS the server's own version of any module that skews
        // from lib/ (e.g. caffeine 3.1.8 vs lib/'s 2.9.3). The launcher puts server-lib/ AHEAD of
        // lib/ on the classpath, so the server gets its versions while the apps (which never see
        // server-lib/) keep lib/'s — no build-wide version alignment needed.
        val libModuleVersion = byModule.mapValues { it.value.first() }  // module -> version (one per)
        kslServers.forEach { (server, dir, extraLaunchers) ->
            val srvDir = root.resolve("Servers/$dir").apply { mkdirs() }
            val jarBase = "ksl-$dir"
            jarOf(server.tasks.named("jar")).copyTo(srvDir.resolve("$jarBase.jar"), overwrite = true)
            val serverLibDir = srvDir.resolve("server-lib").apply { mkdirs() }
            val overrides = mutableListOf<String>()
            server.configurations.named("runtimeClasspath").get().resolvedConfiguration.resolvedArtifacts.forEach { art ->
                val id = art.moduleVersion.id
                val libVer = libModuleVersion["${id.group}:${id.name}"]
                if (libVer != id.version) {  // not in lib/, or a different version -> goes in server-lib/
                    art.file.copyTo(serverLibDir.resolve(art.file.name), overwrite = true)
                    if (libVer != null) overrides += "${id.name} ${id.version} (lib/ has $libVer)"
                }
            }
            val primaryMain = server.extensions.getByType(org.gradle.api.plugins.JavaApplication::class.java).mainClass.get()
            val srvJvm = jvmArgsOf(server)
            (listOf(jarBase to primaryMain) + extraLaunchers).forEach { (lname, main) ->
                srvDir.resolve(lname).apply { writeText(serverLauncher(lname, jarBase, main, srvJvm)); setExecutable(true) }
                srvDir.resolve("$lname.cmd").writeText(winServerLauncher(lname, jarBase, main, srvJvm))
            }
            logger.lifecycle("assembleKSLWork: Servers/$dir -> $jarBase.jar + " +
                "${serverLibDir.listFiles()?.size ?: 0} server-lib jars (${overrides.size} shadow lib/) + ${1 + extraLaunchers.size} launcher(s)")
            if (overrides.isNotEmpty()) logger.lifecycle("assembleKSLWork:   $dir server-lib/ overrides -> ${overrides.joinToString(", ")}")
        }

        // Standalone MCP servers: self-contained fat shadowJar + a pass-through launcher (system
        // Java, no shared lib/). ksl-book's content depends on _book/ being rendered at build time.
        kslStandaloneServers.forEach { (server, dir) ->
            val name = "ksl-$dir-mcp"
            val sdir = root.resolve("Servers/$dir").apply { mkdirs() }
            jarOf(server.tasks.named("shadowJar")).copyTo(sdir.resolve("$name.jar"), overwrite = true)
            val fatJvm = jvmArgsOf(server)
            sdir.resolve(name).apply { writeText(cliLauncher(name, fatJvm)); setExecutable(true) }
            sdir.resolve("$name.cmd").writeText(winCliLauncher(name, fatJvm))
            logger.lifecycle("assembleKSLWork: Servers/$dir -> $name.jar (self-contained fat) + launcher")
        }

        // the ksl helper (manage what's installed in a KSLWork) — repo-root scripts shipped in
        // bin/: `ksl` (bash, macOS/Linux) + `ksl.ps1`/`ksl.cmd` (PowerShell + shim, Windows).
        val binDir = root.resolve("bin").apply { mkdirs() }
        file("ksl").copyTo(binDir.resolve("ksl"), overwrite = true).setExecutable(true)
        file("ksl.ps1").copyTo(binDir.resolve("ksl.ps1"), overwrite = true)
        file("ksl.cmd").copyTo(binDir.resolve("ksl.cmd"), overwrite = true)

        logger.lifecycle("assembleKSLWork: shared lib/ = ${libDir.listFiles()?.size ?: 0} jars; " +
            "${kslAppTargets.size} apps; kslpkg; ${kslServers.size} thin + ${kslStandaloneServers.size} fat servers; bin/ksl(+.ps1/.cmd)")
    }
}

// The release payload: zip build/kslwork into build/ksl-suite.zip. Launcher scripts (the
// extension-less files) must survive as executable, so the archive entries are marked 0755
// (harmless for the jars). Runs after assembleKSLWork and finalizes it, so one command emits both.
tasks.register<Zip>("packageKSLWork") {
    group = "distribution"
    description = "Zip the assembled KSLWork payload into build/ksl-suite.zip"
    dependsOn("assembleKSLWork")
    from(kslWorkDir)
    archiveFileName.set("ksl-suite.zip")
    destinationDirectory.set(layout.buildDirectory)
    filePermissions { unix("0755") }
    dirPermissions { unix("0755") }
}
tasks.named("assembleKSLWork") { finalizedBy("packageKSLWork") }