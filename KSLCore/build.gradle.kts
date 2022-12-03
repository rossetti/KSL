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

plugins {
    `java-library`
    kotlin("jvm") version "1.7.20"
    kotlin("plugin.serialization") version "1.7.20"
}
group = "io.github.rossetti"
version = "1.0-SNAPSHOT"

repositories {

    mavenCentral()
}

dependencies {

    //TODO probably not needed any more, test it
//    api(group = "io.github.rossetti", name = "JSLCore", version = "R1.0.12")
//    api(group = "io.github.rossetti", name = "JSLExtensions", version = "R1.0.12")

    // https://mvnrepository.com/artifact/io.github.microutils/kotlin-logging-jvm
    api(group = "io.github.microutils", name = "kotlin-logging-jvm", version = "3.0.2")

    // https://mvnrepository.com/artifact/ch.qos.logback/logback-classic
    implementation(group = "ch.qos.logback", name = "logback-classic", version = "1.4.4")
    // https://mvnrepository.com/artifact/ch.qos.logback/logback-core
    implementation(group = "ch.qos.logback", name = "logback-core", version = "1.4.4")

    // this is needed because POI uses log4j internally and SXSSFWorkbook() causes a logging that isn't captured
// https://mvnrepository.com/artifact/org.apache.logging.log4j/log4j-to-slf4j
    implementation("org.apache.logging.log4j:log4j-to-slf4j:2.19.0")

    api("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")

    // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/dataframe-core
    api("org.jetbrains.kotlinx:dataframe-core:0.8.1")

//    implementation("org.junit.jupiter:junit-jupiter:5.9.0")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.7.20")

    // https://mvnrepository.com/artifact/org.ktorm/ktorm-core
    implementation("org.ktorm:ktorm-core:3.5.0")

    implementation(group = "org.apache.commons", name = "commons-math3", version = "3.6.1")

    // https://mvnrepository.com/artifact/org.knowm.xchart/xchart
//    implementation("org.knowm.xchart:xchart:3.8.2")
    
    // https://mvnrepository.com/artifact/com.opencsv/opencsv
    implementation("com.opencsv:opencsv:5.7.1") //TODO this vulnerability is not reported on Maven Central

    // https://db.apache.org/derby/releases
    // 10.16.1.1 is only compatible with Java 17
    implementation(group = "org.apache.derby", name = "derby", version = "10.15.2.0")
    implementation(group = "org.apache.derby", name = "derbyshared", version = "10.15.2.0")
    implementation(group = "org.apache.derby", name = "derbyclient", version = "10.15.2.0")
    implementation(group = "org.apache.derby", name = "derbytools", version = "10.15.2.0")

    implementation(group = "org.postgresql", name = "postgresql", version = "42.5.0")

    implementation(group = "org.xerial", name = "sqlite-jdbc", version = "3.39.4.0")

    implementation(group = "com.zaxxer", name = "HikariCP", version = "5.0.1")

    // https://mvnrepository.com/artifact/org.dhatim/fastexcel-reader
//    implementation("org.dhatim:fastexcel-reader:0.14.0")
    // https://mvnrepository.com/artifact/org.dhatim/fastexcel
//    implementation("org.dhatim:fastexcel:0.14.0")

    // https://mvnrepository.com/artifact/org.apache.poi/poi
    api(group = "org.apache.poi", name = "poi", version = "5.2.3")
    // https://mvnrepository.com/artifact/org.apache.poi/poi-ooxml
    api(group = "org.apache.poi", name = "poi-ooxml", version = "5.2.3")

//    testImplementation(kotlin("test"))
//    testImplementation(group = "io.github.rossetti", name = "JSLCore", version = "R1.0.12")
//    testImplementation(group = "io.github.rossetti", name = "JSLExtensions", version = "R1.0.12")
    implementation(kotlin("stdlib-jdk8"))
}

//tasks.test {
//    useJUnitPlatform()
//}

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