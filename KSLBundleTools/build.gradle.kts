plugins {
    kotlin("jvm") version "2.2.0"
    application
    id("com.gradleup.shadow") version "9.0.0"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // kslpkg only loads bundle JARs, instantiates the declared model, and
    // serialises its ModelDescriptor.  KSLCore's runtime tree pulls in DB
    // drivers, Apache POI, lets-plot, etc. that the CLI never exercises;
    // excluding them shrinks the fat JAR from ~154 MB to a fraction of that.
    // Exclusions are kept explicit (rather than using shadow's minimize{},
    // which can break reflection paths used by @KSLControl scanning and by
    // kotlinx-serialization's reflective serializer lookup).
    implementation(project(":KSLCore")) {
        // Database drivers and pooling — kslpkg never opens a DB connection
        exclude(group = "org.duckdb")
        exclude(group = "org.xerial")
        exclude(group = "org.postgresql")
        exclude(group = "org.apache.derby")
        exclude(group = "org.mariadb.jdbc")
        exclude(group = "com.zaxxer")

        // Plotting and SVG — no graphical output from kslpkg
        exclude(group = "org.jetbrains.lets-plot")
        exclude(group = "org.apache.xmlgraphics")

        // Format-specific dataframe modules — the underlying formats are
        // already excluded above; dataframe-core / -csv / -json stay
        exclude(group = "org.jetbrains.kotlinx", module = "dataframe-jdbc")
        exclude(group = "org.jetbrains.kotlinx", module = "dataframe-excel")

        // Swing coroutine dispatcher — kslpkg is a CLI, not a Swing app
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-swing")
    }
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.32")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
}

application {
    mainClass.set("ksl.bundle.tools.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("kslpkg")
    archiveClassifier.set("")
    archiveVersion.set("")
}
