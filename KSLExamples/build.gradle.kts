/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

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

    implementation(project(":KSLCore"))
    
    // https://mvnrepository.com/artifact/io.github.microutils/kotlin-logging-jvm
//    api(group = "io.github.microutils", name = "kotlin-logging-jvm", version = "2.1.21")

    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
//    api(group = "ch.qos.logback", name = "logback-classic", version = "1.2.10")
    // https://mvnrepository.com/artifact/ch.qos.logback/logback-core
//    api(group = "ch.qos.logback", name = "logback-core", version = "1.2.10")

//TODO probably not needed because only used in KSLCore
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
//    implementation("org.junit.jupiter:junit-jupiter:5.7.0")
//    implementation("org.jetbrains.kotlin:kotlin-reflect:1.7.10")

    testImplementation(kotlin("test"))
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