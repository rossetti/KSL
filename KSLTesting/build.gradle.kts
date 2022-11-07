import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// An example gradle build file for a project that depends on the JSL

plugins {
    `java-library`
    kotlin("jvm") version "1.7.20"
}

group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {

    mavenCentral()
}

dependencies {

    // depends on KSLCore as an internal project in multi-project build
    // this permits changes to the KSLCore to be immediately reflected in KSLExtensions
    api(project(":KSLCore"))
    api(project(":KSLExamples"))
//TODO uncomment when extensions are done
//    implementation(project(":KSLExtensions"))

    implementation("org.junit.jupiter:junit-jupiter:5.9.0")

    testImplementation(kotlin("test"))
    testImplementation(group = "io.github.rossetti", name = "JSLCore", version = "R1.0.12")
    testImplementation(group = "io.github.rossetti", name = "JSLExtensions", version = "R1.0.12")
    implementation(kotlin("stdlib-jdk8"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "11"
}

// this is supposed to exclude the logback.xml resource file from the generated jar
// this is good because user can then provide their own logging specification
// TODO need reference to why this is good
tasks.jar {
//    manifest {
//        attributes(
//                "Implementation-Title" to project.name,
//                "Implementation-Version" to project.version
//        )
//    }
    exclude("logback.xml")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}