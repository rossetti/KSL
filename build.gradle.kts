
import java.security.MessageDigest

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

// ---- KSLWork payload assembly — the KSL distribution mechanism ---------------------
// Stages the "shared lib/ + thin per-app JAR + launcher" KSLWork layout under build/kslwork,
// which packageKSLWork zips into ksl-suite.zip. This is how the apps, the servers and kslpkg
// are distributed: one payload that runs on the student's system Java 21 — no bundled JRE and
// no native installers (the per-app jpackage/org.beryx.runtime build was removed once this
// replaced it). All 8 desktop apps are thin jars over ONE shared lib/; kslpkg and the
// standalone servers are self-contained fat jars. To cut a release: docs/releasing-suite.md.
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

// Native launcher containers plus the raster sizes used by desktop shells and Swing.
// These are committed derivatives of distribution/icons/source/*.svg so assembling the
// cross-platform suite never depends on iconutil, Pillow, or an SVG renderer.
val kslAppIconSizes = listOf(16, 24, 32, 48, 64, 128, 256, 512, 1024)
fun kslAppIconFiles(target: String): List<File> {
    val dir = file("distribution/icons/export/$target")
    return listOf(dir.resolve("$target.icns"), dir.resolve("$target.ico")) +
        kslAppIconSizes.map { size -> dir.resolve("$target-$size.png") }
}

// Every icon family that must ship a complete SVG + PNG + ICO + ICNS set: the 8 desktop
// apps plus the one shared "server" icon used by the setup-GUI server entry points. Same
// export contract, so validation and file lookup cover them uniformly.
val kslIconTargets: List<String> = kslAppTargets.map { it.second } + "server"

val validateKSLAppIcons = tasks.register("validateKSLAppIcons") {
    group = "verification"
    description = "Validate every desktop app's and the shared server's SVG, PNG, ICO, and ICNS icon assets."
    val sourceFiles = kslIconTargets.map { target ->
        file("distribution/icons/source/${target.lowercase()}.svg")
    }
    val exportFiles = kslIconTargets.flatMap { target -> kslAppIconFiles(target) }
    inputs.files(sourceFiles + exportFiles)
    doLast {
        fun littleEndian16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        fun bigEndian32(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)

        val expectedIcoSizes = setOf(16, 24, 32, 48, 64, 128, 256)
        val expectedIcnsChunks = setOf("icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10")
        kslIconTargets.forEach { target ->
            val source = file("distribution/icons/source/${target.lowercase()}.svg")
            require(source.isFile) { "missing canonical desktop icon: ${source.path}" }
            val svg = source.readText()
            require("<svg" in svg && "viewBox=\"0 0 260 260\"" in svg) {
                "invalid canonical desktop icon: ${source.path}"
            }

            kslAppIconSizes.forEach { size ->
                val png = file("distribution/icons/export/$target/$target-$size.png")
                require(png.isFile) { "missing desktop icon PNG: ${png.path}" }
                val image = requireNotNull(javax.imageio.ImageIO.read(png)) {
                    "unreadable desktop icon PNG: ${png.path}"
                }
                require(image.width == size && image.height == size && image.colorModel.hasAlpha()) {
                    "desktop icon PNG must be ${size}x$size RGBA: ${png.path}"
                }
            }

            val ico = file("distribution/icons/export/$target/$target.ico")
            require(ico.isFile) { "missing Windows desktop icon: ${ico.path}" }
            val icoBytes = ico.readBytes()
            require(icoBytes.size >= 6 && littleEndian16(icoBytes, 0) == 0 && littleEndian16(icoBytes, 2) == 1) {
                "invalid Windows icon header: ${ico.path}"
            }
            val icoCount = littleEndian16(icoBytes, 4)
            require(icoBytes.size >= 6 + icoCount * 16) { "truncated Windows icon: ${ico.path}" }
            val icoSizes = (0 until icoCount).map { index ->
                val width = icoBytes[6 + index * 16].toInt() and 0xff
                if (width == 0) 256 else width
            }.toSet()
            require(icoSizes == expectedIcoSizes) {
                "Windows icon sizes for $target were $icoSizes; expected $expectedIcoSizes"
            }

            val icns = file("distribution/icons/export/$target/$target.icns")
            require(icns.isFile) { "missing macOS desktop icon: ${icns.path}" }
            val icnsBytes = icns.readBytes()
            require(icnsBytes.size >= 8 && String(icnsBytes, 0, 4, Charsets.US_ASCII) == "icns") {
                "invalid macOS icon header: ${icns.path}"
            }
            require(bigEndian32(icnsBytes, 4) == icnsBytes.size) { "invalid macOS icon length: ${icns.path}" }
            val chunks = mutableSetOf<String>()
            var offset = 8
            while (offset < icnsBytes.size) {
                require(offset + 8 <= icnsBytes.size) { "truncated macOS icon chunk: ${icns.path}" }
                val type = String(icnsBytes, offset, 4, Charsets.US_ASCII)
                val length = bigEndian32(icnsBytes, offset + 4)
                require(length >= 8 && offset + length <= icnsBytes.size) {
                    "invalid macOS icon chunk $type: ${icns.path}"
                }
                chunks += type
                offset += length
            }
            require(chunks.containsAll(expectedIcnsChunks)) {
                "macOS icon chunks for $target were $chunks; expected $expectedIcnsChunks"
            }
        }
        logger.lifecycle("validateKSLAppIcons: ${kslIconTargets.size} complete icon families (${kslAppTargets.size} apps + shared server)")
    }
}

tasks.named("check") { dependsOn(validateKSLAppIcons) }

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

// The PACKAGED entry point for a server's launcher, where that differs from the module's
// application{mainClass}. KSLServerMcp's application{} names MainKt — the raw stdio server —
// so `gradle run` starts stdio for developers; but the packaged entry is LauncherKt, which is
// what its own shadowJar manifest declares (Main-Class = ksl.server.mcp.LauncherKt). Reading
// application{} here made the installed ksl-mcp silently ignore --setup/--gui/--doctor and
// fall through to stdio, so `ksl-mcp --doctor` just hung. Overriding here (rather than
// changing application{}) keeps `gradle run` behaving as developers expect.
val serverDistMain: Map<String, String> = mapOf(
    "mcp" to "ksl.server.mcp.LauncherKt",
)

// Standalone MCP servers: (project, Servers/<dir>). Self-contained fat shadowJars that share
// NOTHING with the KSL runtime stack (MCP SDK + Lucene, no KSLCore) — no lib/, no server-lib/.
// ksl-book bakes in the git-ignored _book/ render (empty content if absent); ksl-code bakes a
// Lucene index of KSL source built at assembly time (pinned via -PkslVersion, default develop).
val kslStandaloneServers: List<Pair<Project, String>> = listOf(
    evaluationDependsOn(":KSLCodeMCPServer") to "code",
    evaluationDependsOn(":KSLBookServer") to "book",
)

// Curated example bundles shipped with the suite, so a fresh install can run a real model
// immediately instead of opening an empty model picker. They are slim MANIFEST bundles
// (~730 KB for both) — the models' dependencies are already in the shared lib/, so this is
// 0.5% of the payload. They ship as SOFTWARE (bundles/ -> .support/bundles/ once installed),
// never into the user's workspace: updates refresh them, uninstall removes them, and a user's
// own copy of the same bundleId shadows them because the apps discover this directory LAST
// (see WorkspaceLayout.builtinBundlesDir). The launchers point the apps at it with
// -Dksl.builtinBundles.
val kslExamples = evaluationDependsOn(":KSLExamples")
val exampleBundles: List<Pair<String, String>> = listOf(
    "bookExamplesBundleJar" to "book-examples.jar",
    "animationExamplesBundleJar" to "animation-examples.jar",
)

// Launchers generated from templates. "DOLLAR" stands in for a literal shell '$' so the Kotlin
// string needs no per-character escaping; it is substituted back at the end. The app launcher
// runs a thin `-cp lib/*:App.jar Main`; the CLI launcher runs a self-contained `-jar tool.jar`.
val macLauncherTemplate = """
    #!/bin/bash
    # KSL @NAME@ desktop app — suite launcher (runs on your system Java 21).
    set -e
    DIR="DOLLAR(cd "DOLLAR(dirname "DOLLAR0")" && pwd)"
    # The suite's support root (<KSL_HOME>/.support once installed): holds the shared lib/
    # and the shipped example bundles. NOT the user's KSLWork workspace -- the app resolves
    # that itself from ~/.ksl/settings.toml.
    KSL_SUPPORT="DOLLAR(cd "DOLLARDIR/../.." && pwd)"
    JAVA=java
    [ -n "DOLLARJAVA_HOME" ] && JAVA="DOLLARJAVA_HOME/bin/java"
    VER="DOLLAR("DOLLARJAVA" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
    if [ -z "DOLLARVER" ] || ! [ "DOLLARVER" -ge 21 ] 2>/dev/null; then
      echo "@NAME@ needs Java 21 — the same JDK you use in IntelliJ."
      echo "Found: DOLLAR("DOLLARJAVA" -version 2>&1 | head -1)"
      exit 1
    fi
    # macOS: name the Dock tile and the app menu, or the JVM shows up as plain "java".
    # -Xdock:* is macOS-only (passing it on Linux aborts the JVM with "Unrecognized
    # option"), hence the uname guard -- written as `if`, since a bare `[ ] && x=y`
    # returns 1 off macOS and `set -e` would kill the launcher.
    DOCK=""
    if [ "DOLLAR(uname)" = "Darwin" ]; then DOCK="-Xdock:name=KSL @NAME@"; fi
    exec "DOLLARJAVA" DOLLAR{DOCK:+"DOLLARDOCK"} "-Dksl.builtinBundles=DOLLARKSL_SUPPORT/bundles"@JVMARGS@ -cp "DOLLARKSL_SUPPORT/lib/*:DOLLARDIR/@NAME@.jar" @MAIN@ "DOLLAR@"
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
    # @NAME@ — KSL server launcher (runs on your system Java 21).
    set -e
    DIR="DOLLAR(cd "DOLLAR(dirname "DOLLAR0")" && pwd)"
    # The suite's support root (<KSL_HOME>/.support once installed): the shared lib/ and the
    # shipped example bundles. NOT the user's workspace.
    KSL_SUPPORT="DOLLAR(cd "DOLLARDIR/../.." && pwd)"
    JAVA=java
    [ -n "DOLLARJAVA_HOME" ] && JAVA="DOLLARJAVA_HOME/bin/java"
    VER="DOLLAR("DOLLARJAVA" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
    if [ -z "DOLLARVER" ] || ! [ "DOLLARVER" -ge 21 ] 2>/dev/null; then
      echo "@NAME@ needs Java 21 — the same JDK you use in IntelliJ."
      echo "Found: DOLLAR("DOLLARJAVA" -version 2>&1 | head -1)"
      exit 1
    fi
    exec "DOLLARJAVA"@JVMARGS@ "-Dksl.builtinBundles=DOLLARKSL_SUPPORT/bundles"@SELFD@ -cp "DOLLARDIR/server-lib/*:DOLLARKSL_SUPPORT/lib/*:DOLLARDIR/@JAR@.jar" @MAIN@ "DOLLAR@"
""".trimIndent()

fun macLauncher(name: String, mainClass: String, jvmArgs: String): String =
    macLauncherTemplate.replace("@NAME@", name).replace("@MAIN@", mainClass)
        .replace("@JVMARGS@", jvmArgs).replace("DOLLAR", "\$") + "\n"

fun cliLauncher(name: String, jvmArgs: String): String =
    cliLauncherTemplate.replace("@NAME@", name).replace("@JVMARGS@", jvmArgs).replace("DOLLAR", "\$") + "\n"

// selfD: an extra -D naming this wrapper's own path, for the MCP stdio launcher only (see the
// call site). Substituted BEFORE the DOLLAR pass so it can use DOLLARDIR like the template does.
fun serverLauncher(name: String, jar: String, mainClass: String, jvmArgs: String, selfD: String = ""): String =
    serverLauncherTemplate.replace("@NAME@", name).replace("@JAR@", jar)
        .replace("@MAIN@", mainClass).replace("@JVMARGS@", jvmArgs).replace("@SELFD@", selfD)
        .replace("DOLLAR", "\$") + "\n"

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
    if defined JAVA_HOME set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
    "%JAVAW%" -version >nul 2>&1
    if errorlevel 1 (
      echo @NAME@ needs Java 21 - the same JDK you use in IntelliJ.
      pause
      exit /b 1
    )
    start "" "%JAVAW%"@JVMARGS@ "-Dksl.builtinBundles=%~dp0..\..\bundles" -cp "%~dp0..\..\lib\*;%~dp0@NAME@.jar" @MAIN@ %*
""".trimIndent()

val winServerTemplate = """
    @echo off
    setlocal
    set "JAVA=java"
    if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
    "%JAVA%" -version >nul 2>&1
    if errorlevel 1 (
      echo @NAME@ needs Java 21 - the same JDK you use in IntelliJ.
      exit /b 1
    )
    "%JAVA%"@JVMARGS@ "-Dksl.builtinBundles=%~dp0..\..\bundles"@SELFD@ -cp "%~dp0server-lib\*;%~dp0..\..\lib\*;%~dp0@JAR@.jar" @MAIN@ %*
""".trimIndent()

val winCliTemplate = """
    @echo off
    setlocal
    set "JAVA=java"
    if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
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

fun winServerLauncher(name: String, jar: String, mainClass: String, jvmArgs: String, selfD: String = "") =
    (winServerTemplate.replace("@NAME@", name).replace("@JAR@", jar).replace("@MAIN@", mainClass)
        .replace("@JVMARGS@", jvmArgs).replace("@SELFD@", selfD)).replace("\n", "\r\n") + "\r\n"

fun winCliLauncher(name: String, jvmArgs: String) =
    (winCliTemplate.replace("@NAME@", name).replace("@JVMARGS@", jvmArgs)).replace("\n", "\r\n") + "\r\n"

// Server dirs whose entry point opens the setup GUI (manifest `entry: "gui"`). These get the
// shared server icon staged beside their launcher and a windowless GUI .cmd on Windows; `rest`
// is absent, so it stays terminal-only. Keep in sync with manifest.json's `entry` fields.
val guiServerDirs = setOf("mcp", "code", "book")

// Windowless Windows GUI launcher for a setup-GUI server: javaw + start (no console), invoked
// with NO args so Launcher.main opens the setup window. @EXEC@ is the java-invocation tail —
// a shared-lib classpath + main class for the thin mcp server, or -jar for the fat code/book
// servers — and @DFLAGS@ carries any extra -D properties. Windows needs this separate from the
// stdio .cmd because that one uses console `java` (a real stdio channel the agent drives); a
// double-click of it would leave a console window. The stdio .cmd itself is left untouched.
val winServerGuiTemplate = """
    @echo off
    setlocal
    set "JAVAW=javaw"
    if defined JAVA_HOME set "JAVAW=%JAVA_HOME%\bin\javaw.exe"
    "%JAVAW%" -version >nul 2>&1
    if errorlevel 1 (
      echo @NAME@ needs Java 21 - the same JDK you use in IntelliJ.
      pause
      exit /b 1
    )
    start "" "%JAVAW%"@JVMARGS@@DFLAGS@ @EXEC@
""".trimIndent()

fun winServerGuiLauncher(name: String, exec: String, jvmArgs: String, dFlags: String = "") =
    (winServerGuiTemplate.replace("@NAME@", name).replace("@EXEC@", exec)
        .replace("@JVMARGS@", jvmArgs).replace("@DFLAGS@", dFlags)).replace("\n", "\r\n") + "\r\n"

tasks.register("assembleKSLWork") {
    group = "distribution"
    description = "Assemble the KSLWork payload (shared lib/ + thin app JARs + kslpkg + launchers) under build/kslwork"
    dependsOn(validateKSLAppIcons)
    kslAppTargets.forEach { (app, _) ->
        dependsOn(app.tasks.named("jar"))
        inputs.files(app.configurations.named("runtimeClasspath"))
    }
    inputs.files(kslIconTargets.flatMap { target -> kslAppIconFiles(target) })
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
    exampleBundles.forEach { (task, jar) ->
        dependsOn(kslExamples.tasks.named(task))
        inputs.file(kslExamples.layout.buildDirectory.file("libs/$jar"))
    }
    inputs.file("distribution/bin/ksl")
    inputs.file("distribution/bin/ksl.ps1")
    inputs.file("distribution/bin/ksl.cmd")
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
            kslAppIconFiles(target).forEach { icon ->
                require(icon.isFile) { "missing desktop icon asset: ${icon.path}" }
                icon.copyTo(appDir.resolve(icon.name), overwrite = true)
            }
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
            val primaryMain = serverDistMain[dir]
                ?: server.extensions.getByType(org.gradle.api.plugins.JavaApplication::class.java).mainClass.get()
            val srvJvm = jvmArgsOf(server)
            // Only the MCP stdio wrapper advertises its own path. AgentSetup writes a client
            // config whose `command` is this script — the installed ksl-mcp.jar is THIN (no
            // Main-Class, no deps), so the `java -jar <jar>` config AgentSetup writes by
            // default cannot start it. The HTTP launcher must NOT advertise itself (a config
            // pointing at it would start HTTP mode), and ksl-rest is not an MCP server.
            val selfUnix = if (dir == "mcp") " \"-Dksl.mcp.launcher=DOLLARDIR/$jarBase\"" else ""
            val selfWin = if (dir == "mcp") " \"-Dksl.mcp.launcher=%~dp0$jarBase.cmd\"" else ""
            (listOf(jarBase to primaryMain) + extraLaunchers).forEachIndexed { i, (lname, main) ->
                val su = if (i == 0) selfUnix else ""
                val sw = if (i == 0) selfWin else ""
                srvDir.resolve(lname).apply { writeText(serverLauncher(lname, jarBase, main, srvJvm, su)); setExecutable(true) }
                srvDir.resolve("$lname.cmd").writeText(winServerLauncher(lname, jarBase, main, srvJvm, sw))
            }
            if (dir in guiServerDirs) {
                // Stage the shared server icon (macOS .icns / Windows .ico / Linux PNGs) beside the
                // launcher, so this server's entry point (made by bin/ksl / ksl.ps1) has its artwork.
                kslAppIconFiles("server").forEach { icon ->
                    require(icon.isFile) { "missing server icon asset: ${icon.path}" }
                    icon.copyTo(srvDir.resolve(icon.name), overwrite = true)
                }
                // Windows GUI cmd: windowless javaw, the SAME shared-lib classpath as the stdio
                // launcher, no args (→ setup GUI). -Dksl.mcp.launcher names the STDIO cmd (not this
                // one) so "Configure my coding agent" writes a config the agent can actually run —
                // the thin jar has no Main-Class, so `java -jar` would fail. macOS/Linux reuse the
                // existing launcher (no args) instead and need no extra file (pruned there anyway).
                val guiExec = "-cp \"%~dp0server-lib\\*;%~dp0..\\..\\lib\\*;%~dp0$jarBase.jar\" $primaryMain"
                val guiDFlags = " \"-Dksl.builtinBundles=%~dp0..\\..\\bundles\"" +
                    if (dir == "mcp") " \"-Dksl.mcp.launcher=%~dp0$jarBase.cmd\"" else ""
                srvDir.resolve("$jarBase-gui.cmd").writeText(winServerGuiLauncher(jarBase, guiExec, srvJvm, guiDFlags))
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
            if (dir in guiServerDirs) {
                kslAppIconFiles("server").forEach { icon ->
                    require(icon.isFile) { "missing server icon asset: ${icon.path}" }
                    icon.copyTo(sdir.resolve(icon.name), overwrite = true)
                }
                // Windows GUI cmd: windowless javaw -jar, no args (→ setup GUI). The fat jar
                // declares a Main-Class, so `java -jar` works and no -Dksl.mcp.launcher is needed
                // (matching this server's flag-free stdio launcher). macOS/Linux reuse the launcher.
                val guiExec = "-jar \"%~dp0$name.jar\""
                sdir.resolve("$name-gui.cmd").writeText(winServerGuiLauncher(name, guiExec, fatJvm))
            }
            logger.lifecycle("assembleKSLWork: Servers/$dir -> $name.jar (self-contained fat) + launcher")
        }

        // The shipped example bundles (see `exampleBundles` above). Without these a fresh
        // install opens an empty model picker and the student can do nothing until they
        // build a bundle from source — which would defeat a no-build distribution.
        val bundlesDir = root.resolve("bundles").apply { mkdirs() }
        exampleBundles.forEach { (_, jar) ->
            val src = kslExamples.layout.buildDirectory.file("libs/$jar").get().asFile
            require(src.isFile) { "expected example bundle ${src.path} (its task should have produced it)" }
            src.copyTo(bundlesDir.resolve(jar), overwrite = true)
            logger.lifecycle("assembleKSLWork: bundles/$jar (${src.length() / 1024} KB)")
        }

        // the ksl helper (manage what's installed). Its sources live in distribution/bin/ —
        // the repo path mirrors where they land in the payload — and are copied in verbatim:
        // `ksl` (bash, macOS/Linux) + `ksl.ps1`/`ksl.cmd` (Windows).
        val binDir = root.resolve("bin").apply { mkdirs() }
        file("distribution/bin/ksl").copyTo(binDir.resolve("ksl"), overwrite = true).setExecutable(true)
        file("distribution/bin/ksl.ps1").copyTo(binDir.resolve("ksl.ps1"), overwrite = true)
        file("distribution/bin/ksl.cmd").copyTo(binDir.resolve("ksl.cmd"), overwrite = true)

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

// Stamp manifest.json's `suite` block for a release: compute the SHA-256 of the built
// ksl-suite.zip and write a ready-to-commit manifest to build/release/manifest.json with
// the version, the suite-v<version> asset URL, and that hash (the items catalog is
// preserved). The tracked manifest.json is NOT modified in place — the release runbook
// (docs/releasing-suite.md) copies the stamped file over it deliberately. Version comes from
// -PreleaseVersion, else the kslSuiteVersion property.
tasks.register("stampSuiteManifest") {
    group = "distribution"
    description = "Stamp manifest.json's suite block (version + asset URL + sha256 of ksl-suite.zip)."
    dependsOn("packageKSLWork")
    doLast {
        val version = (project.findProperty("releaseVersion") as String?)?.takeIf { it.isNotBlank() }
            ?: (project.findProperty("kslSuiteVersion") as String?)
            ?: error("set kslSuiteVersion in gradle.properties or pass -PreleaseVersion=X.Y.Z")
        val zip = layout.buildDirectory.file("ksl-suite.zip").get().asFile
        require(zip.exists()) { "expected ${zip.path} — packageKSLWork should have produced it" }

        val md = MessageDigest.getInstance("SHA-256")
        zip.inputStream().buffered().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val asset = "https://github.com/rossetti/KSL/releases/download/suite-v$version/ksl-suite.zip"
        val suiteLine = """  "suite": { "version": "$version", "asset": "$asset", "sha256": "$sha" },"""

        val manifestFile = file("manifest.json")
        val stamped = Regex("""(?m)^[ \t]*"suite":\s*\{.*\},[ \t]*$""")
            .replace(manifestFile.readText()) { suiteLine }
        check(stamped.contains(sha)) { "no \"suite\": { ... } line found to stamp in ${manifestFile.path}" }
        val out = layout.buildDirectory.file("release/manifest.json").get().asFile
        out.parentFile.mkdirs()
        out.writeText(stamped)

        logger.lifecycle("stampSuiteManifest: suite v$version  sha256=$sha")
        logger.lifecycle("  wrote ${out.path}  (review, then copy over manifest.json)")
        logger.lifecycle("  release: gh release create suite-v$version ${zip.path} --title \"KSL Suite $version\"")
    }
}
