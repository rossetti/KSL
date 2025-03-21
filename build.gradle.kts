import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// An example gradle build file for a project that depends on the KSL

plugins {
    `java-library`
    // uncomment for publishing task
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "1.9.0"
    //   id("org.jetbrains.kotlinx.dataframe") version "0.11.0"
    id("org.jetbrains.dokka") version "2.0.0"
}

group = "io.github.rossetti"
version = "R1.2.0"

buildscript {

    dependencies {
        classpath("com.netflix.nebula:gradle-aggregate-javadocs-plugin:2.2.+")
    }
}

apply(plugin = "nebula-aggregate-javadocs")

repositories {
    mavenCentral()
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
}

dependencies {

    api(project(":KSLCore"))
    api(project(":KSLExamples"))

    implementation(kotlin("stdlib-jdk8"))
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "17"
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "17"
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
    jvmTarget = "17"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "17"
}

// these extensions are needed when publishing to maven
// because maven requires javadoc jar, sources jar, and the build jar
// these jars are placed in build/libs by default
java {
    // comment this out to not make jar file with javadocs during normal build
    withJavadocJar()
    // comment this out to not make jar file with source during normal build
    withSourcesJar()
}
